package com.intertec.autoops.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.domain.Approval;
import com.intertec.autoops.core.domain.ApprovalStatus;
import com.intertec.autoops.core.domain.Job;
import com.intertec.autoops.core.domain.Run;
import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.domain.RunTargetType;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.repo.ApprovalRepository;
import com.intertec.autoops.core.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Human approval gate. Two triggers: jobs flagged {@code requires_approval}
 * (explicit per-job toggle) and COMPLEX workflows (automatic — see
 * {@link WorkflowComplexity}; simple workflows never queue approvals). A
 * manual run by a non-ADMIN becomes a PENDING approval instead of a run; an
 * ADMIN approving it starts the run (with the admin's token, so the
 * entitlement gate still applies), rejecting closes it. ADMIN manual runs
 * and cron-scheduled runs bypass the gate. Reads are never gated.
 */
@Service
public class ApprovalService {

    static final String ADMIN_ROLE = "ADMIN";

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRepository approvalRepository;
    private final JobRepository jobRepository;
    private final WorkflowClient workflowClient;
    private final RunService runService;
    private final SubscriptionGate gate;
    private final ApprovalSettingsService settings;
    private final NativeInputValidator nativeInputs;
    private final ObjectMapper objectMapper;

    /** Nullable in slice tests that don't import it; events then go unannounced. */
    private final NotificationService notificationService;

    public ApprovalService(ApprovalRepository approvalRepository,
                           JobRepository jobRepository,
                           WorkflowClient workflowClient,
                           RunService runService,
                           SubscriptionGate gate,
                           ApprovalSettingsService settings,
                           NativeInputValidator nativeInputs,
                           ObjectMapper objectMapper,
                           org.springframework.beans.factory.ObjectProvider<NotificationService> notificationService) {
        this.approvalRepository = approvalRepository;
        this.jobRepository = jobRepository;
        this.workflowClient = workflowClient;
        this.runService = runService;
        this.gate = gate;
        this.settings = settings;
        this.nativeInputs = nativeInputs;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService.getIfAvailable();
    }

    private String writeInputs(Map<String, Object> inputs) {
        // Null in, null out. The validator returns null for a workflow that
        // declares no form at all, and serialising that gives the four-character
        // string "null" — which is not SQL NULL, reads as a value on the row,
        // and would be handed to the runtime as inputs.
        if (inputs == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(inputs);
        } catch (Exception ex) {
            throw CoreException.badRequest("invalid_inputs",
                    "Those input values could not be recorded");
        }
    }

    /**
     * Null rather than a throw on unreadable JSON: an approval row that somehow
     * holds corrupt input is still an approval an admin has to be able to
     * reject, and RunService re-validates before the run starts anyway — a
     * required field lost here surfaces there as "Fill in: …".
     */
    private Map<String, Object> readInputs(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            log.warn("Approval carried unreadable inputs; running without them");
            return null;
        }
    }

    /**
     * Intercepts a manual job run. Returns a new PENDING approval when the
     * job needs one and the caller isn't an admin; null means "run it now".
     */
    @Transactional
    public Approval interceptJobRun(String tenantId, String actor, String role,
                                    String accessToken, Long jobId) {
        Job job = jobRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> CoreException.notFound("job_not_found", "No such job"));
        if (!job.isRequiresApproval() || ADMIN_ROLE.equals(role)) {
            return null;
        }
        return queueApproval(tenantId, actor, accessToken, RunTargetType.JOB,
                job.getId(), job.getName(), job.getProject().getId());
    }

    /**
     * Intercepts a manual workflow run: only COMPLEX workflows are gated
     * (automatic rule, no per-workflow toggle); null means "run it now".
     */
    @Transactional
    public Approval interceptWorkflowRun(String tenantId, String actor, String role,
                                         String accessToken, Long workflowId) {
        return interceptWorkflowRun(tenantId, actor, role, accessToken, workflowId, null);
    }

    /**
     * @param inputs the run-input values the requester filled in. Validated
     *               here so a form with a missing required field is refused at
     *               request time rather than parked in an approval queue and
     *               failing on the admin who approves it. Stored on the
     *               approval so {@link #approve} can replay the run faithfully.
     */
    @Transactional
    public Approval interceptWorkflowRun(String tenantId, String actor, String role,
                                         String accessToken, Long workflowId,
                                         Map<String, Object> inputs) {
        WorkflowClient.WorkflowView workflow = workflowClient.require(tenantId, workflowId);
        if (!WorkflowComplexity.isComplex(workflow.definition(), workflow.nodeCount(),
                settings.rules(tenantId)) || ADMIN_ROLE.equals(role)) {
            return null;
        }
        // Validated and carried onto the approval row. This used to happen
        // only for vendor-backed workflows, so approving a NATIVE one started
        // it with `inputs` null — the operator filled in the form, an admin
        // approved, and the run began with nothing. The form is the contract
        // whatever engine runs it.
        String inputsJson = writeInputs(nativeInputs.validate(workflow.definition(), inputs));
        return queueApproval(tenantId, actor, accessToken, RunTargetType.WORKFLOW,
                workflow.id(), workflow.name(), workflow.projectId(), inputsJson);
    }

    /**
     * An agent asking for a target it may not run unattended.
     *
     * <p>Unconditional: agent-service has already decided this call needs a
     * human, and the answer to "does this need approval" is not re-derived
     * here. There is no role to exempt either — an agent is never an ADMIN,
     * whoever built it.
     *
     * <p>The approval lands in the SAME inbox a person's request would, and
     * the existing {@link #approve} starts the run exactly as it always has.
     * The agent then attaches to {@code runId} rather than starting a second
     * run of its own, which is why nothing about the decision path changes.
     * {@code requestedBy} names the agent, so the inbox says who asked; the
     * run itself records the approving admin, because they are the one who
     * authorised it.
     */
    @Transactional
    public Approval requestFromAgent(String tenantId, String actor, RunTargetType targetType,
                                     Long targetId, Map<String, Object> inputs) {
        if (targetType == RunTargetType.WORKFLOW) {
            WorkflowClient.WorkflowView workflow = workflowClient.require(tenantId, targetId);
            // Validated NOW, at the agent's call, not when an admin approves.
            // An agent that omitted a required field should be told so while
            // it can still fix it and retry — parking the mistake in a queue
            // and failing on the human who approves it wastes their time and
            // teaches the model nothing.
            String inputsJson = writeInputs(nativeInputs.validate(
                    workflow.definition(), inputs));
            return queueApprovalRow(tenantId, actor, RunTargetType.WORKFLOW, workflow.id(),
                    workflow.name(), workflow.projectId(), inputsJson);
        }
        Job job = jobRepository.findByIdAndTenantId(targetId, tenantId)
                .orElseThrow(() -> CoreException.notFound("job_not_found", "No such job"));
        return queueApprovalRow(tenantId, actor, RunTargetType.JOB, job.getId(), job.getName(),
                job.getProject().getId());
    }

    /** One approval, tenant-scoped — how a paused agent run learns the verdict. */
    @Transactional(readOnly = true)
    public Approval get(String tenantId, Long approvalId) {
        return approvalRepository.findById(approvalId)
                .filter(a -> a.getTenantId().equals(tenantId))
                .orElseThrow(() -> CoreException.notFound("approval_not_found",
                        "No such approval"));
    }

    private Approval queueApproval(String tenantId, String actor, String accessToken,
                                   RunTargetType targetType, Long targetId, String targetName,
                                   Long projectId) {
        return queueApproval(tenantId, actor, accessToken, targetType, targetId, targetName,
                projectId, null);
    }

    private Approval queueApproval(String tenantId, String actor, String accessToken,
                                   RunTargetType targetType, Long targetId, String targetName,
                                   Long projectId, String inputsJson) {
        gate.requireActive(accessToken);
        return queueApprovalRow(tenantId, actor, targetType, targetId, targetName, projectId,
                inputsJson);
    }

    private Approval queueApprovalRow(String tenantId, String actor, RunTargetType targetType,
                                      Long targetId, String targetName, Long projectId) {
        return queueApprovalRow(tenantId, actor, targetType, targetId, targetName, projectId,
                null);
    }

    private Approval queueApprovalRow(String tenantId, String actor, RunTargetType targetType,
                                      Long targetId, String targetName, Long projectId,
                                      String inputsJson) {
        if (approvalRepository.existsByTargetTypeAndTargetIdAndTenantIdAndStatus(
                targetType, targetId, tenantId, ApprovalStatus.PENDING)) {
            throw CoreException.conflict("approval_pending",
                    "An approval request for this " + targetType.name().toLowerCase()
                            + " is already waiting for an admin");
        }
        Approval approval = new Approval();
        approval.setTenantId(tenantId);
        approval.setProjectId(projectId);
        approval.setTargetType(targetType);
        approval.setTargetId(targetId);
        approval.setTargetName(targetName);
        approval.setInputs(inputsJson);
        approval.setRequestedBy(actor);
        Approval saved = approvalRepository.save(approval);
        log.info("Tenant {} queued approval {} for {} {} (requested by {})",
                tenantId, saved.getId(), targetType, targetId, actor);
        if (notificationService != null) {
            notificationService.publish(tenantId,
                    com.intertec.autoops.core.domain.AppNotification.Kind.ALERT,
                    "Approval requested: " + targetName,
                    actor + " wants to run this " + targetType.name().toLowerCase()
                            + " — an admin decision is needed.",
                    "/app/projects/" + projectId + "/approvals");
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Approval> list(String tenantId, Long projectId) {
        return projectId != null
                ? approvalRepository.findTop200ByTenantIdAndProjectIdOrderByCreatedAtDesc(tenantId, projectId)
                : approvalRepository.findTop200ByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** Approving starts the run in the same transaction (admin's token gates it). */
    @Transactional
    public Approval approve(String tenantId, String approver, String role,
                            String accessToken, Long approvalId) {
        Approval approval = requirePending(tenantId, role, approvalId);
        Run run = approval.getTargetType() == RunTargetType.WORKFLOW
                ? runService.runWorkflow(tenantId, approver, accessToken, approval.getTargetId(),
                        readInputs(approval.getInputs()))
                : runService.runJob(tenantId, approver, accessToken, approval.getTargetId());
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setDecidedBy(approver);
        approval.setDecidedAt(Instant.now());
        approval.setRunId(run.getId());
        log.info("Tenant {} approval {} approved by {} -> run {}",
                tenantId, approvalId, approver, run.getId());
        notifyDecision(approval, approver, "approved — the run has started");
        return approvalRepository.save(approval);
    }

    @Transactional
    public Approval reject(String tenantId, String approver, String role, Long approvalId) {
        Approval approval = requirePending(tenantId, role, approvalId);
        approval.setStatus(ApprovalStatus.REJECTED);
        approval.setDecidedBy(approver);
        approval.setDecidedAt(Instant.now());
        log.info("Tenant {} approval {} rejected by {}", tenantId, approvalId, approver);
        notifyDecision(approval, approver, "rejected");
        return approvalRepository.save(approval);
    }

    private void notifyDecision(Approval approval, String approver, String outcome) {
        if (notificationService != null) {
            notificationService.publish(approval.getTenantId(),
                    com.intertec.autoops.core.domain.AppNotification.Kind.SYSTEM,
                    "Approval " + (outcome.startsWith("approved") ? "approved" : "rejected")
                            + ": " + approval.getTargetName(),
                    approver + " " + outcome + " (requested by "
                            + approval.getRequestedBy() + ").",
                    "/app/projects/" + approval.getProjectId() + "/approvals");
        }
    }

    private Approval requirePending(String tenantId, String role, Long approvalId) {
        if (!ADMIN_ROLE.equals(role)) {
            throw CoreException.forbidden("approval_admin_only",
                    "Only an admin can approve or reject requests");
        }
        Approval approval = approvalRepository.findByIdAndTenantId(approvalId, tenantId)
                .orElseThrow(() -> CoreException.notFound("approval_not_found", "No such approval"));
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            throw CoreException.conflict("approval_resolved",
                    "This request has already been " + approval.getStatus().name().toLowerCase());
        }
        return approval;
    }
}