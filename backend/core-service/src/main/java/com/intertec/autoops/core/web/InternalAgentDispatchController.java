package com.intertec.autoops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.domain.Approval;
import com.intertec.autoops.core.domain.Job;
import com.intertec.autoops.core.domain.Run;
import com.intertec.autoops.core.domain.RunTargetType;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.repo.JobRepository;
import com.intertec.autoops.core.repo.RunRepository;
import com.intertec.autoops.core.service.ApprovalService;
import com.intertec.autoops.core.service.ApprovalSettingsService;
import com.intertec.autoops.core.service.RunService;
import com.intertec.autoops.core.service.WorkflowComplexity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What an agent's tool call needs from core-service, and nothing else.
 *
 * <p>Split from {@link InternalController} because the responsibility is
 * different in kind: everything there READS a fact for a peer to render.
 * These three endpoints START WORK on a tenant's infrastructure, and that
 * deserves its own file to read and its own place to look when something ran
 * that should not have.
 *
 * <p><strong>The approval decision is made here, not in agent-service.</strong>
 * A job's {@code requires_approval} flag and a workflow's complexity threshold
 * are core's policy — they are edited here, stored here, and already applied
 * to every human run here. Re-implementing that test in agent-service would
 * create a second copy that drifts, and the day it drifts an agent runs
 * unattended something the console swore needed a human. So agent-service asks
 * what to do, and this answers RUN or APPROVAL.
 *
 * <p>Guarded by {@code X-Internal-Token} like the rest of {@code /internal};
 * never routed by the gateway.
 */
@RestController
public class InternalAgentDispatchController {

    private static final Logger log = LoggerFactory.getLogger(InternalAgentDispatchController.class);

    private final JobRepository jobRepository;
    private final RunRepository runRepository;
    private final RunService runService;
    private final ApprovalService approvalService;
    private final ApprovalSettingsService approvalSettings;
    private final WorkflowClient workflowClient;
    private final ObjectMapper objectMapper;

    public InternalAgentDispatchController(JobRepository jobRepository,
                                           RunRepository runRepository,
                                           RunService runService,
                                           ApprovalService approvalService,
                                           ApprovalSettingsService approvalSettings,
                                           WorkflowClient workflowClient,
                                           ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.runRepository = runRepository;
        this.runService = runService;
        this.approvalService = approvalService;
        this.approvalSettings = approvalSettings;
        this.workflowClient = workflowClient;
        this.objectMapper = objectMapper;
    }

    /**
     * @param actor      names the agent, e.g. {@code agent:Patch Operator#12} —
     *                   it becomes the run's {@code triggered_by}
     * @param targetType JOB or WORKFLOW
     * @param inputs     values for the workflow's published input
     *                   form; ignored for jobs and for plain canvas workflows,
     *                   which have no form to fill
     */
    public record DispatchRequest(String tenantId, String actor, String targetType, Long targetId,
                                  Map<String, Object> inputs) {
    }

    /**
     * @param mode       {@code RUN} — started, poll {@code runId};
     *                   {@code APPROVAL} — a human must decide, poll
     *                   {@code approvalId}
     * @param targetName so the agent can name the thing in its transcript
     *                   without a second lookup
     */
    public record DispatchResponse(String mode, Long runId, Long approvalId, String targetName) {
    }

    @PostMapping("/internal/agent/dispatch")
    public DispatchResponse dispatch(@RequestBody DispatchRequest request) {
        RunTargetType targetType = parseTargetType(request.targetType());
        String actor = request.actor() == null || request.actor().isBlank()
                ? "agent" : request.actor();

        return targetType == RunTargetType.WORKFLOW
                ? dispatchWorkflow(request.tenantId(), actor, request.targetId(), request.inputs())
                : dispatchJob(request.tenantId(), actor, request.targetId());
    }

    /**
     * The input form a workflow tool must expose to the model.
     *
     * <p>Without this an agent could only press a button. A workflow that asks
     * for a hostname and a change ticket is useless to an agent that cannot
     * supply either, and guessing the variable names from the workflow title is
     * exactly the kind of invention this codebase does not do.
     *
     * <p><b>One source.</b> A workflow declares its own {@code inputs[]}
     * alongside its {@code nodes[]}, and that is read here. It used to have a
     * second source — a vendor's published form, fetched over the network on
     * every call — which meant the tool schema was only ever as available as a
     * third party, and could disagree with the variables the workflow read.
     *
     * <p>An empty list is a real answer: a workflow that declares no inputs
     * genuinely has no form.
     */
    @GetMapping("/internal/agent/workflow-inputs")
    public Map<String, Object> workflowInputs(@RequestParam String tenantId,
                                              @RequestParam Long workflowId) {
        WorkflowClient.WorkflowView workflow = workflowClient.require(tenantId, workflowId);

        Map<String, Object> out = new HashMap<>();
        out.put("workflowId", workflow.id());
        out.put("name", workflow.name());
        // What the automation actually DOES, for the agent's tool description.
        // A title alone is not enough to decide whether a tool can answer a
        // question: "S3 Public Access Audit" reads like a security scan, and
        // an agent asked for a bucket inventory refused outright rather than
        // call the very tool that returns one.
        out.put("description", descriptionIn(workflow.definition()));
        out.put("fields", nativeInputs(workflow.definition()));
        return out;
    }

    /**
     * The input form a workflow declares for itself, read
     * from {@code inputs[]} in its definition — the same document that carries
     * {@code nodes[]}. Authored under {@code agent-service/agents/} and
     * published with the workflow, so the operator's form and the model's
     * argument schema can never describe different things.
     *
     * <p>Rows carry the five keys agent-service reads
     * ({@code variable/label/type/required/options}) plus the constraints only
     * the console needs ({@code pattern}, {@code min}, {@code max},
     * {@code default}, {@code placeholder}, {@code help}, {@code requiredWhen}).
     * agent-service ignores what it does not recognise, so one declaration
     * drives both the human form and the tool schema.
     *
     * <p>A field with no {@code variable} is dropped: there is nothing to pass
     * it as, and offering an unnamed box to fill in is worse than not showing
     * it. A definition that will not parse yields no form rather than an
     * exception — the workflow may still be perfectly runnable with defaults.
     */
    /**
     * The description a published workflow carries inside its definition.
     *
     * <p>Stored there rather than in a column because the workflows table has
     * none, and because it then travels through rollout with everything else
     * the definition holds. Null for anything authored before this existed —
     * the caller falls back to the title, which is what it used to have.
     */
    private String descriptionIn(String definition) {
        if (definition == null || definition.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(definition).path("description");
            return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
        } catch (Exception ex) {
            return null;   // unparseable definition is already reported elsewhere
        }
    }

    private List<Map<String, Object>> nativeInputs(String definition) {
        if (definition == null || definition.isBlank()) {
            return List.of();
        }
        JsonNode inputs;
        try {
            inputs = objectMapper.readTree(definition).path("inputs");
        } catch (Exception ex) {
            log.warn("Workflow definition is not parseable JSON; reporting no input form: {}",
                    ex.getMessage());
            return List.of();
        }
        if (!inputs.isArray()) {
            return List.of();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode field : inputs) {
            String variable = field.path("variable").asText("");
            if (variable.isBlank()) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("variable", variable);
            row.put("label", field.path("label").asText(variable));
            row.put("type", field.path("type").asText("string"));
            row.put("required", field.path("required").asBoolean(false));
            row.put("options", field.path("options").isArray()
                    ? objectMapper.convertValue(field.get("options"), List.class)
                    : List.of());
            copyIfPresent(field, row, "pattern", "min", "max", "default",
                    "placeholder", "help", "requiredWhen");
            rows.add(row);
        }
        return rows;
    }

    /** Carries a key across only when the author actually set it. */
    private void copyIfPresent(JsonNode field, Map<String, Object> row, String... keys) {
        for (String key : keys) {
            JsonNode value = field.get(key);
            if (value != null && !value.isNull()) {
                row.put(key, objectMapper.convertValue(value, Object.class));
            }
        }
    }

    private DispatchResponse dispatchJob(String tenantId, String actor, Long jobId) {
        Job job = jobRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> CoreException.notFound("job_not_found", "No such job"));

        if (job.isRequiresApproval()) {
            Approval approval = approvalService.requestFromAgent(tenantId, actor,
                    RunTargetType.JOB, jobId, null);
            log.info("Tenant {} agent {} raised approval {} for job {}",
                    tenantId, actor, approval.getId(), jobId);
            return new DispatchResponse("APPROVAL", null, approval.getId(), job.getName());
        }

        Run run = runService.runFromAgent(job, actor);
        log.info("Tenant {} agent {} started run {} for job {}",
                tenantId, actor, run.getId(), jobId);
        return new DispatchResponse("RUN", run.getId(), null, job.getName());
    }

    private DispatchResponse dispatchWorkflow(String tenantId, String actor, Long workflowId,
                                              Map<String, Object> inputs) {
        WorkflowClient.WorkflowView workflow = workflowClient.require(tenantId, workflowId);

        // Same rule the console applies to a person: complex workflows are
        // gated automatically, simple ones are not.
        boolean complex = WorkflowComplexity.isComplex(workflow.definition(),
                workflow.nodeCount(), approvalSettings.rules(tenantId));

        if (complex) {
            Approval approval = approvalService.requestFromAgent(tenantId, actor,
                    RunTargetType.WORKFLOW, workflowId, inputs);
            log.info("Tenant {} agent {} raised approval {} for workflow {}",
                    tenantId, actor, approval.getId(), workflowId);
            return new DispatchResponse("APPROVAL", null, approval.getId(), workflow.name());
        }

        Run run = runService.runFromAgent(workflow, actor, inputs);
        log.info("Tenant {} agent {} started run {} for workflow {}",
                tenantId, actor, run.getId(), workflowId);
        return new DispatchResponse("RUN", run.getId(), null, workflow.name());
    }

    /**
     * A run's live state, for an agent waiting on the tool it just started.
     *
     * <p>The log is capped: it goes into a model's context window, where a
     * 400 KB PowerShell transcript would either blow the request or crowd out
     * everything else the agent knows. The TAIL is kept — a failure's cause is
     * at the end — and the truncation is stated in the text so the model does
     * not read a fragment as the whole story.
     */
    @GetMapping("/internal/agent/runs/{id}")
    public Map<String, Object> run(@PathVariable Long id, @RequestParam String tenantId,
                                   @RequestParam(defaultValue = "8000") int maxLogChars) {
        Run run = runRepository.findById(id)
                .filter(r -> r.getTenantId().equals(tenantId))
                .orElseThrow(() -> CoreException.notFound("run_not_found", "No such run"));

        Map<String, Object> out = new HashMap<>();
        out.put("id", run.getId());
        out.put("status", run.getStatus().name());
        out.put("terminal", run.getStatus().isTerminal());
        out.put("targetName", run.getTargetName());
        out.put("stepCompleted", run.getStepCompleted());
        out.put("stepTotal", run.getStepTotal());
        out.put("error", run.getError());
        out.put("log", tail(run.getLog(), Math.clamp(maxLogChars, 500, 60_000)));
        return out;
    }

    /** An approval's verdict, for an agent run paused on it. */
    @GetMapping("/internal/agent/approvals/{id}")
    public Map<String, Object> approval(@PathVariable Long id, @RequestParam String tenantId) {
        Approval approval = approvalService.get(tenantId, id);

        Map<String, Object> out = new HashMap<>();
        out.put("id", approval.getId());
        out.put("status", approval.getStatus().name());
        out.put("targetName", approval.getTargetName());
        out.put("decidedBy", approval.getDecidedBy());
        // Set only once APPROVED: approving starts the run, and this is the
        // run the agent attaches to instead of starting another.
        out.put("runId", approval.getRunId());
        return out;
    }

    private static RunTargetType parseTargetType(String raw) {
        try {
            return RunTargetType.valueOf(String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ex) {
            throw CoreException.badRequest("bad_target_type",
                    "targetType must be JOB or WORKFLOW");
        }
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return "… [" + (text.length() - max) + " earlier characters omitted] …\n"
                + text.substring(text.length() - max);
    }
}
