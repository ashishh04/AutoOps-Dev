package com.intertec.autoops.core.web;

import com.intertec.autoops.core.domain.Approval;
import com.intertec.autoops.core.domain.CoreAuditEventType;
import com.intertec.autoops.core.domain.RunTargetType;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.service.ApprovalService;
import com.intertec.autoops.core.service.GovernanceService;
import com.intertec.autoops.core.service.WorkflowReadiness;
import com.intertec.autoops.core.service.WorkflowInputField;
import com.intertec.autoops.core.service.RunService;
import com.intertec.autoops.core.web.dto.ApprovalRequiredResponse;
import com.intertec.autoops.core.web.dto.ApprovalResponse;
import com.intertec.autoops.core.web.dto.RunInputsRequest;
import com.intertec.autoops.core.web.dto.RunResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Run triggers + history. Triggering is a gated mutation (202: the run is
 * queued, execution is async — poll the run for progress). History reads are
 * never gated but are retention-bounded by the plan's history_days. Tenant
 * always from the token claim.
 */
@RestController
public class RunController {

    private final RunService runService;
    private final ApprovalService approvalService;
    private final GovernanceService governanceService;
    private final com.intertec.autoops.core.service.AuditService auditService;

    public RunController(RunService runService, ApprovalService approvalService,
                         GovernanceService governanceService,
                         com.intertec.autoops.core.service.AuditService auditService) {
        this.runService = runService;
        this.approvalService = approvalService;
        this.governanceService = governanceService;
        this.auditService = auditService;
    }

    /**
     * A {@code requires_approval} job run by a non-admin queues a PENDING
     * approval instead of a run ({@code approvalRequired: true} in the body).
     * ENFORCED governance policies (SCM required / failure budget) block
     * manual runs in violating projects with a 403 first.
     */
    @PostMapping("/api/jobs/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object runJob(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        governanceService.assertJobRunAllowed(tenant(jwt), id);
        Approval approval = approvalService.interceptJobRun(tenant(jwt), jwt.getSubject(),
                jwt.getClaimAsString("role"), jwt.getTokenValue(), id);
        if (approval != null) {
            auditService.record(CoreAuditEventType.APPROVAL_REQUESTED, tenant(jwt),
                    jwt.getSubject(), approval.getProjectId(), "JOB", id,
                    approval.getTargetName(), "run blocked pending admin approval");
            return ApprovalRequiredResponse.of(ApprovalResponse.from(approval));
        }
        var run = runService.runJob(tenant(jwt), jwt.getSubject(), jwt.getTokenValue(), id);
        auditService.record(CoreAuditEventType.RUN_TRIGGERED, tenant(jwt), jwt.getSubject(),
                run.getProjectId(), "RUN", run.getId(), run.getTargetName(), "manual job run");
        return RunResponse.summary(run);
    }

    /**
     * Complex workflows (automatic rule — see WorkflowComplexity) run by a
     * non-admin queue a PENDING approval; simple workflows always run.
     */
    /**
     * The published input form for a rolled-out workflow, so the console can
     * render it before triggering a run.
     *
     * <p>Deliberately NOT served by returning the workflow's definition: the
     * definition is the provider's design and workflow-service withholds it
     * from a customer's browser on purpose. This reads the definition
     * key server-side and hands back only the field list.
     *
     * <p>An empty list is the normal answer for a plain {@code nodes[]}
     * workflow — the console then runs it straight away with no dialog.
     */
    @GetMapping("/api/workflows/{id}/inputs")
    public List<WorkflowInputField> workflowInputs(@PathVariable Long id,
                                                               @AuthenticationPrincipal Jwt jwt) {
        governanceService.assertWorkflowRunAllowed(tenant(jwt), id);
        return runService.inputFormFor(tenant(jwt), id);
    }

    /**
     * Whether this workspace can actually run this workflow — asked BEFORE
     * anyone presses Run.
     *
     * <p>A rolled-out workflow arrives complete and looks identical to a ready
     * one: the provider built it against their workspace, and this one has
     * different AI connections and different jobs. Until this existed the only
     * way to find out was to press Run and read a red error, which reads as "the
     * automation is broken" rather than "it is not set up yet".
     *
     * <p>Reads only; spends no tokens and starts nothing.
     */
    @GetMapping("/api/workflows/{id}/readiness")
    public WorkflowReadiness.Readiness workflowReadiness(@PathVariable Long id,
                                                         @AuthenticationPrincipal Jwt jwt) {
        return runService.readinessFor(tenant(jwt), id);
    }

    @PostMapping("/api/workflows/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object runWorkflow(@PathVariable Long id,
                              @RequestBody(required = false) RunInputsRequest body,
                              @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> inputs = body == null ? null : body.inputs();
        governanceService.assertWorkflowRunAllowed(tenant(jwt), id);
        Approval approval = approvalService.interceptWorkflowRun(tenant(jwt), jwt.getSubject(),
                jwt.getClaimAsString("role"), jwt.getTokenValue(), id, inputs);
        if (approval != null) {
            auditService.record(CoreAuditEventType.APPROVAL_REQUESTED, tenant(jwt),
                    jwt.getSubject(), approval.getProjectId(), "WORKFLOW", id,
                    approval.getTargetName(), "run blocked pending admin approval");
            return ApprovalRequiredResponse.of(ApprovalResponse.from(approval));
        }
        var run = runService.runWorkflow(tenant(jwt), jwt.getSubject(), jwt.getTokenValue(), id,
                inputs);
        auditService.record(CoreAuditEventType.RUN_TRIGGERED, tenant(jwt), jwt.getSubject(),
                run.getProjectId(), "RUN", run.getId(), run.getTargetName(),
                "manual workflow run");
        return RunResponse.summary(run);
    }

    /**
     * A project's runs, newest first. Pass {@code targetType} + {@code targetId}
     * together to scope the page to one job's or one workflow's history — the
     * 200-row cap then applies to that target instead of the whole project.
     */
    @GetMapping("/api/projects/{projectId}/runs")
    public List<RunResponse> list(@PathVariable Long projectId,
                                  @RequestParam(required = false) String targetType,
                                  @RequestParam(required = false) Long targetId,
                                  @AuthenticationPrincipal Jwt jwt) {
        return runService.list(tenant(jwt), jwt.getTokenValue(), projectId,
                        parseTargetType(targetType), targetId)
                .stream().map(RunResponse::summary).toList();
    }

    private static RunTargetType parseTargetType(String targetType) {
        if (targetType == null || targetType.isBlank()) {
            return null;
        }
        try {
            return RunTargetType.valueOf(targetType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw CoreException.badRequest("unknown_target_type",
                    "targetType must be JOB or WORKFLOW");
        }
    }

    @GetMapping("/api/runs/{id}")
    public RunResponse get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return RunResponse.detail(runService.get(tenant(jwt), jwt.getTokenValue(), id));
    }

    @PostMapping("/api/runs/{id}/cancel")
    public RunResponse cancel(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        var run = runService.cancel(tenant(jwt), jwt.getTokenValue(), id);
        auditService.record(CoreAuditEventType.RUN_CANCELED, tenant(jwt), jwt.getSubject(),
                run.getProjectId(), "RUN", run.getId(), run.getTargetName(), null);
        return RunResponse.summary(run);
    }

    private String tenant(Jwt jwt) {
        String tenantId = jwt.getClaimAsString("tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            throw CoreException.badRequest("missing_tenant", "Token has no tenantId claim");
        }
        return tenantId;
    }
}