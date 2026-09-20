package com.intertec.autoops.core.web;

import com.intertec.autoops.core.domain.CoreAuditEventType;
import com.intertec.autoops.core.domain.Job;
import com.intertec.autoops.core.domain.Project;
import com.intertec.autoops.core.domain.RunTargetType;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.repo.JobRepository;
import com.intertec.autoops.core.repo.ProjectRepository;
import com.intertec.autoops.core.service.ApprovalSettingsService;
import com.intertec.autoops.core.service.AuditService;
import com.intertec.autoops.core.service.PlatformTimelineService;
import com.intertec.autoops.core.service.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What core-service still owns, exposed to the services split out of it.
 * Guarded by {@code X-Internal-Token} (InternalTokenFilter) and never routed
 * by the gateway.
 *
 * <p>Callers and what they need:
 * <ul>
 *   <li><b>workflow-service</b> — the project a workflow belongs to, the run
 *       stats and approval rules its responses carry, and the audit trail.</li>
 *   <li><b>agent-service</b> — the project an agent belongs to, the jobs an
 *       agent may list as tools, and the audit trail.</li>
 * </ul>
 *
 * <p>Everything is tenant-scoped by an explicit {@code tenantId} parameter:
 * there is no user token on these calls, so the caller states the tenant and
 * every lookup stays inside it. Nothing here can return another tenant's row.
 */
@RestController
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);

    private final ProjectRepository projectRepository;
    private final JobRepository jobRepository;
    private final RunService runService;
    private final ApprovalSettingsService approvalSettings;
    private final AuditService auditService;
    private final PlatformTimelineService platformTimeline;

    public InternalController(ProjectRepository projectRepository,
                              JobRepository jobRepository,
                              RunService runService,
                              ApprovalSettingsService approvalSettings,
                              AuditService auditService,
                              PlatformTimelineService platformTimeline) {
        this.projectRepository = projectRepository;
        this.jobRepository = jobRepository;
        this.runService = runService;
        this.approvalSettings = approvalSettings;
        this.auditService = auditService;
        this.platformTimeline = platformTimeline;
    }

    /** Project existence + tenancy. 404 is what the caller turns into its own. */
    @GetMapping("/internal/projects/{id}")
    public Map<String, Object> project(@PathVariable Long id, @RequestParam String tenantId) {
        Project project = projectRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> CoreException.notFound("project_not_found", "No such project"));
        return Map.of("id", project.getId(), "name", project.getName(),
                "status", project.getStatus().name());
    }

    /** One job, for an agent's tools allow-list. */
    @GetMapping("/internal/jobs/{id}")
    public Map<String, Object> job(@PathVariable Long id, @RequestParam String tenantId) {
        Job job = jobRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> CoreException.notFound("job_not_found", "No such job"));
        return Map.of("id", job.getId(), "projectId", job.getProject().getId(),
                "name", job.getName());
    }

    /** A project's jobs, for resolving an agent's tool names in one pass. */
    @GetMapping("/internal/projects/{projectId}/jobs")
    public List<Map<String, Object>> jobsByProject(@PathVariable Long projectId,
                                                   @RequestParam String tenantId) {
        return jobRepository.findByProjectIdAndTenantIdOrderByCreatedAtDesc(projectId, tenantId)
                .stream()
                .map(j -> Map.<String, Object>of("id", j.getId(), "name", j.getName()))
                .toList();
    }

    /**
     * Everything that happened in one project inside a time window.
     *
     * <p>Read by agent-runtime's {@code platform} workflow node — the tool that
     * lets an agent reason about the WORKSPACE rather than about one cloud
     * vendor. It is the only evidence source in the platform that needs no
     * customer credential, because the data is already ours: the runs we
     * executed, the approvals we raised.
     *
     * <p>Tenant and project are both required and are used together on every
     * query. The internal token authorises the caller to ask on a tenant's
     * behalf; it does not let it widen the answer.
     */
    @GetMapping("/internal/platform/timeline")
    public Map<String, Object> platformTimeline(@RequestParam String tenantId,
                                                @RequestParam Long projectId,
                                                @RequestParam(defaultValue = "24") int windowHours) {
        return platformTimeline.timeline(tenantId, projectId, windowHours);
    }

    /**
     * Run aggregates for a project's targets. workflow-service carries these
     * on its list responses, which is why they survive the split unchanged.
     */
    @GetMapping("/internal/runs/stats")
    public List<Map<String, Object>> runStats(@RequestParam String tenantId,
                                              @RequestParam String targetType,
                                              @RequestParam Long projectId) {
        RunTargetType type = parseTargetType(targetType);
        // Read once for the whole project rather than per row: "is this
        // running" is a different question from the finished-run aggregate and
        // needs its own query, but not one query per workflow.
        java.util.Set<Long> active = runService.activeTargets(tenantId, type, projectId);
        java.util.Map<Long, RunService.RunStats> stats =
                runService.statsForProject(tenantId, type, projectId);

        // A target that has NEVER finished a run still has to appear when it is
        // running now, or the first run of a new workflow shows nothing at all.
        java.util.Set<Long> targets = new java.util.LinkedHashSet<>(stats.keySet());
        targets.addAll(active);

        return targets.stream()
                .map(targetId -> {
                    RunService.RunStats s = stats.get(targetId);
                    Map<String, Object> row = new HashMap<>();
                    row.put("targetId", targetId);
                    row.put("total", s != null ? s.total() : 0L);
                    row.put("successRate", s != null ? s.successRate() : null);
                    row.put("lastRunAt", s != null ? s.lastRunAt() : null);
                    row.put("avgDurationMs", s != null ? s.avgDurationMs() : null);
                    row.put("running", active.contains(targetId));
                    return row;
                })
                .toList();
    }

    /** The tenant's effective complexity rules (no row = platform defaults). */
    @GetMapping("/internal/approval-settings")
    public Map<String, Object> approvalSettings(@RequestParam String tenantId) {
        var rules = approvalSettings.rules(tenantId);
        return Map.of("complexNodeThreshold", rules.nodeThreshold(),
                "riskyTypes", List.copyOf(rules.riskyTypes()));
    }

    /**
     * One audit event from a split-out service. The trail stays SINGLE — the
     * Audit page reads one table — so workflow and agent mutations land here
     * next to the job and project events they relate to.
     */
    @PostMapping("/internal/audit")
    public Map<String, String> audit(@RequestBody AuditRequest request) {
        CoreAuditEventType type;
        try {
            type = CoreAuditEventType.valueOf(
                    String.valueOf(request.eventType()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // An unknown event type must not become a 500 in the caller's
            // best-effort audit path — say so and move on.
            log.warn("Rejected unknown internal audit event type '{}'", request.eventType());
            throw CoreException.badRequest("unknown_event_type",
                    "Not an auditable core event: " + request.eventType());
        }
        auditService.record(type, request.tenantId(), request.actor(), request.projectId(),
                request.targetType(), request.targetId(), request.targetName(), request.detail());
        return Map.of("status", "recorded");
    }

    public record AuditRequest(String eventType, String tenantId, String actor, Long projectId,
                               String targetType, Long targetId, String targetName,
                               String detail) {
    }

    /** Health probe for the peers' own readiness checks. */
    @GetMapping("/internal/ping")
    public Map<String, Object> ping() {
        return Map.of("service", "core-service", "at", Instant.now().toString());
    }

    private static RunTargetType parseTargetType(String targetType) {
        try {
            return RunTargetType.valueOf(targetType.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw CoreException.badRequest("unknown_target_type",
                    "targetType must be JOB or WORKFLOW");
        }
    }
}
