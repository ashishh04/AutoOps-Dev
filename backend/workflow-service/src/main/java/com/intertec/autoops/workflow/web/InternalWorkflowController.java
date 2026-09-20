package com.intertec.autoops.workflow.web;

import com.intertec.autoops.workflow.domain.Workflow;
import com.intertec.autoops.workflow.service.WorkflowService;
import com.intertec.autoops.workflow.web.dto.WorkflowRequest;
import com.intertec.autoops.workflow.web.dto.WorkflowView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The service-to-service face of workflow-service. Guarded by
 * {@code X-Internal-Token} (InternalTokenFilter) and never routed by the
 * gateway.
 *
 * <p>Callers and what they need:
 * <ul>
 *   <li><b>core-service</b> — the definition behind a run, an approval
 *       interception, a governance check or a webhook target; the project's
 *       workflows for SCM export and compliance evidence; create/update for
 *       SCM import; the tenant count for the governance quota widget.</li>
 *   <li><b>agent-service</b> — existence and names of the workflows an agent
 *       lists as tools, plus the workflow half of the shared automation
 *       budget.</li>
 * </ul>
 *
 * <p>{@code tenantId} is a REQUIRED parameter on every call: there is no user
 * token here, so the caller states the tenant and every query stays scoped to
 * it. A caller that forgets it gets a 400, never an unscoped result.
 */
@RestController
public class InternalWorkflowController {

    private final WorkflowService workflowService;

    public InternalWorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping("/internal/workflows/{id}")
    public WorkflowView get(@PathVariable Long id, @RequestParam String tenantId) {
        return WorkflowView.from(workflowService.get(tenantId, id));
    }

    @GetMapping("/internal/projects/{projectId}/workflows")
    public List<WorkflowView> listByProject(@PathVariable Long projectId,
                                            @RequestParam String tenantId) {
        return workflowService.listUnchecked(tenantId, projectId).stream()
                .map(WorkflowView::from).toList();
    }

    @GetMapping("/internal/workflows/count")
    public Map<String, Long> count(@RequestParam String tenantId) {
        return Map.of("count", workflowService.countForTenant(tenantId));
    }

    /**
     * Counts for EVERY tenant — the one unscoped read here, and only because
     * the provider console's usage table is itself a cross-tenant view. It
     * returns counts, never workflow content.
     */
    @GetMapping("/internal/workflows/counts")
    public Map<String, Long> counts() {
        return workflowService.countsByTenant();
    }

    /**
     * Delivered copies per catalog item, for the provider's own library view.
     *
     * <p>Unscoped for the same reason as {@link #counts()} — "how many
     * customers hold this template" is inherently cross-tenant — and like it,
     * this returns counts keyed by catalog id and never names a tenant or
     * returns any workflow content.
     */
    @GetMapping("/internal/workflows/rollout-counts")
    public Map<String, Long> rolloutCounts() {
        return workflowService.rolloutCountsBySource();
    }

    /** Trusted create (project already resolved by the caller). */
    @PostMapping("/internal/projects/{projectId}/workflows")
    public WorkflowView create(@PathVariable Long projectId,
                               @RequestParam String tenantId,
                               @RequestParam String actor,
                               @RequestHeader("X-Access-Token") String accessToken,
                               @Valid @RequestBody WorkflowRequest request) {
        return WorkflowView.from(workflowService.createTrusted(tenantId, actor, accessToken,
                projectId, request.name(), request.definition()));
    }

    /**
     * Roll a catalog workflow out into a tenant's project — core-service's
     * provider surface calls this once per target tenant after checking the
     * caller's PROVIDER role. The row it creates is sealed: the tenant can run
     * it but the public API will not serialise its definition back to them.
     *
     * @param delivery {@code PRODUCT} when the workflow is the thing being
     *                 delivered, {@code AGENT_COMPONENT} when it is being
     *                 delivered only so an agent has something to call.
     *                 Defaulted rather than required, so an older core-service
     *                 rolling out against a newer workflow-service delivers a
     *                 visible product — which is the previous behaviour, and
     *                 the safe way for that skew to be wrong.
     */
    @PostMapping("/internal/projects/{projectId}/workflows/rollout")
    public WorkflowView rollOut(@PathVariable Long projectId,
                                @RequestParam String tenantId,
                                @RequestParam String actor,
                                @RequestParam Long sourceId,
                                @RequestParam(defaultValue = "PRODUCT") Workflow.Delivery delivery,
                                @RequestHeader("X-Access-Token") String accessToken,
                                @Valid @RequestBody WorkflowRequest request) {
        return WorkflowView.from(workflowService.rollOut(tenantId, actor, accessToken,
                projectId, sourceId, request.name(), request.definition(), delivery));
    }

    /** Revoke: the provider withdrawing a workflow it rolled out. */
    @DeleteMapping("/internal/workflows/{id}")
    public void revoke(@PathVariable Long id,
                       @RequestParam String tenantId,
                       @RequestHeader("X-Access-Token") String accessToken) {
        workflowService.delete(tenantId, accessToken, id, true);
    }

    @PutMapping("/internal/workflows/{id}")
    public WorkflowView update(@PathVariable Long id,
                               @RequestParam String tenantId,
                               @RequestHeader("X-Access-Token") String accessToken,
                               @Valid @RequestBody WorkflowRequest request) {
        // Trusted service-to-service caller: it has already established that
        // the change is authorised, so provider-owned rows are writable here.
        return WorkflowView.from(workflowService.update(tenantId, accessToken, id,
                request.name(), request.definition(), true));
    }
}
