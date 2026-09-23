package com.intertec.autoops.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.workflow.client.AgentClient;
import com.intertec.autoops.workflow.client.CoreClient;
import com.intertec.autoops.workflow.domain.Workflow;
import com.intertec.autoops.workflow.exception.WorkflowException;
import com.intertec.autoops.workflow.repo.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Automation workflows inside a project. Behaviour is unchanged from the
 * monolith: MAX_AUTOMATIONS on create (counted over the tenant's workflows
 * AND agents — one shared automation budget, the agent half coming from
 * agent-service — deleting either frees a slot), MAX_NODES on any definition
 * change (node count parsed SERVER-SIDE from the canvas JSON, never trusted
 * from the client), and the plain subscription gate on everything else. Reads
 * are never gated.
 *
 * <p>What DID change: the owning project is verified over core-service's
 * internal API instead of by a database foreign key, so
 * {@code project_not_found} is now an HTTP round trip on every write.
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowRepository workflowRepository;
    private final CoreClient coreClient;
    private final AgentClient agentClient;
    private final SubscriptionGate gate;
    private final ObjectMapper objectMapper;

    public WorkflowService(WorkflowRepository workflowRepository,
                           CoreClient coreClient,
                           AgentClient agentClient,
                           SubscriptionGate gate,
                           ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.coreClient = coreClient;
        this.agentClient = agentClient;
        this.gate = gate;
        this.objectMapper = objectMapper;
    }

    /**
     * What the CUSTOMER sees: everything except sealed agent components.
     *
     * <p>A component was delivered because an agent needs something to call,
     * not because anyone sold it. Listing it invites the fair question "I did
     * not ask for this, why is it in my environment?", and worse, lets a
     * customer run a product they were never licensed. The agent still reaches
     * it — tool resolution goes through {@link #listUnchecked}, which does not
     * filter.
     */
    @Transactional(readOnly = true)
    public List<Workflow> list(String tenantId, Long projectId) {
        coreClient.requireProject(tenantId, projectId);
        return workflowRepository.findByProjectIdAndTenantIdAndDeliveryOrderByCreatedAtDesc(
                projectId, tenantId, Workflow.Delivery.PRODUCT);
    }

    /**
     * The INTERNAL listing: every row, components included.
     *
     * <p>This is what {@code RolloutService.resolveTools} reads to turn an
     * agent's allow-list of refs into the ids of the copies this project holds.
     * It must not filter — a component hidden from this would make the agent
     * that needs it undeliverable.
     */
    @Transactional(readOnly = true)
    public List<Workflow> listUnchecked(String tenantId, Long projectId) {
        return workflowRepository.findByProjectIdAndTenantIdOrderByCreatedAtDesc(projectId, tenantId);
    }

    @Transactional(readOnly = true)
    public Workflow get(String tenantId, Long id) {
        return workflowRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> WorkflowException.notFound("workflow_not_found",
                        "No such workflow"));
    }

    /**
     * Roll a catalog workflow out into a tenant's project. The only path that
     * creates a PROVIDER-origin row, and the only path the public API offers
     * for creating a workflow at all — tenants no longer author them.
     *
     * <p>Called by core-service over /internal after it has checked the
     * caller's PROVIDER role and resolved the project, so the project round
     * trip is skipped for the same reason {@link #createTrusted} skips it.
     */
    @Transactional
    public Workflow rollOut(String tenantId, String actor, String accessToken,
                            Long projectId, Long sourceId, String name, String definition,
                            Workflow.Delivery delivery) {
        // One delivered copy per catalog item per project. The name check in
        // doCreate stops the obvious repeat, but only while the names still
        // match: rename the catalog item, roll out again, and it lets a second
        // copy of the same source through. Matching on sourceId closes that,
        // and gives the provider a conflict that says what actually happened
        // instead of "a workflow with this name already exists".
        if (sourceId != null
                && workflowRepository.existsByProjectIdAndSourceIdAndDelivery(
                        projectId, sourceId, delivery)) {
            throw WorkflowException.conflict("already_delivered",
                    "This project already has this workflow. Edit the delivered copy to "
                            + "update it, or roll out to a different project.");
        }
        // planGate=false: the gate reads the tenant from the bearer token, and
        // on a rollout that token is the PROVIDER's — it would test the wrong
        // subscription entirely. core-service checks the RECEIVING customer's
        // subscription before it calls here (RolloutService#requireLiveSubscription).
        Workflow workflow = doCreate(tenantId, actor, accessToken, projectId, name,
                definition, false, delivery);
        workflow.setOrigin(Workflow.Origin.PROVIDER);
        workflow.setSourceId(sourceId);
        workflow.setDelivery(delivery);
        Workflow saved = workflowRepository.save(workflow);
        log.info("Rolled catalog workflow {} out to tenant {} project {} as workflow {} ({})",
                sourceId, tenantId, projectId, saved.getId(), delivery);
        return saved;
    }

    /**
     * Create for a TRUSTED internal caller (core-service's SCM import), which
     * has already resolved the project against its own database. Skipping the
     * project round trip is not just an optimisation: core-service calls this
     * from inside a transaction, and bouncing straight back into core-service
     * for a fact it just proved would put two services' connection pools on
     * the same request path for no gain.
     */
    @Transactional
    public Workflow createTrusted(String tenantId, String actor, String accessToken,
                                  Long projectId, String name, String definition) {
        return doCreate(tenantId, actor, accessToken, projectId, name, definition, true,
                Workflow.Delivery.PRODUCT);
    }

    /**
     * @param planGate whether to test the ACCESS TOKEN's plan. False only on
     *                 the rollout path, where the token belongs to the provider
     *                 rather than to the tenant receiving the workflow.
     */
    private Workflow doCreate(String tenantId, String actor, String accessToken,
                              Long projectId, String name, String definition,
                              boolean planGate, Workflow.Delivery delivery) {
        // Scoped to the delivery kind, because a sealed agent component and a
        // separately licensed copy of the same catalog workflow carry the same
        // name by construction. That is not ambiguous to the customer: only the
        // PRODUCT copy is ever listed to them.
        if (workflowRepository.existsByProjectIdAndNameAndDelivery(projectId, name, delivery)) {
            throw WorkflowException.conflict("workflow_exists",
                    "A workflow with this name already exists in the project");
        }
        if (planGate) {
            gate.requireQuota(accessToken, "MAX_AUTOMATIONS", automationCount(tenantId),
                    "automations (workflows and agents)");
        }
        int nodeCount = countNodes(definition);
        if (planGate) {
            gate.requireNodeCapacity(accessToken, nodeCount);
        }

        Workflow workflow = new Workflow();
        workflow.setTenantId(tenantId);
        workflow.setProjectId(projectId);
        workflow.setName(name);
        workflow.setDefinition(definition);
        workflow.setNodeCount(nodeCount);
        workflow.setCreatedBy(actor);
        workflow.setDelivery(delivery);
        Workflow saved = workflowRepository.save(workflow);
        log.info("Tenant {} created workflow {} ({} nodes)", tenantId, saved.getId(), nodeCount);
        return saved;
    }

    @Transactional
    public Workflow update(String tenantId, String accessToken, Long id,
                           String name, String definition, boolean callerIsProvider) {
        Workflow workflow = get(tenantId, id);
        requireOwner(workflow, callerIsProvider, "edited");
        if (definition != null) {
            int nodeCount = countNodes(definition);
            gate.requireNodeCapacity(accessToken, nodeCount);
            workflow.setDefinition(definition);
            workflow.setNodeCount(nodeCount);
        } else {
            gate.requireActive(accessToken);
        }
        if (name != null && !name.isBlank()) {
            workflow.setName(name);
        }
        return workflowRepository.save(workflow);
    }

    @Transactional
    public Workflow setEnabled(String tenantId, String accessToken, Long id, boolean enabled) {
        gate.requireActive(accessToken);
        Workflow workflow = get(tenantId, id);
        workflow.setEnabled(enabled);
        return workflowRepository.save(workflow);
    }

    /**
     * Deleting frees a MAX_AUTOMATIONS slot. A rolled-out workflow is removed
     * by the PROVIDER revoking it, not by the tenant deleting it — otherwise
     * a customer could drop an automation the provider is accountable for.
     */
    @Transactional
    public void delete(String tenantId, String accessToken, Long id, boolean callerIsProvider) {
        gate.requireActive(accessToken);
        Workflow workflow = get(tenantId, id);
        requireOwner(workflow, callerIsProvider, "deleted");
        workflowRepository.delete(workflow);
        log.info("Tenant {} deleted workflow {}", tenantId, id);
    }

    /**
     * A PROVIDER-authored workflow may only be changed by a PROVIDER. Enabling
     * and disabling deliberately does NOT go through here: whether a rolled-out
     * automation is live in their own workspace is the tenant's call.
     */
    private void requireOwner(Workflow workflow, boolean callerIsProvider, String verb) {
        if (workflow.isProviderAuthored() && !callerIsProvider) {
            throw WorkflowException.forbidden("provider_managed",
                    "This workflow is managed by your provider and cannot be " + verb
                            + ". You can enable, disable and run it.");
        }
    }

    /** Quota basis for agent-service's half of the shared budget. */
    @Transactional(readOnly = true)
    public long countForTenant(String tenantId) {
        return workflowRepository.countByTenantId(tenantId);
    }

    /**
     * Per-tenant counts for the PROVIDER console's usage table, which used to
     * get this from a SQL subquery when workflows shared core-service's
     * database.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> countsByTenant() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : workflowRepository.countGroupedByTenant()) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * Delivered copies per catalog item, keyed by source id as a STRING —
     * JSON object keys are strings, and returning Long keys here only to have
     * Jackson stringify them anyway invites a caller to look one up with a
     * number and miss.
     */
    /** Every live copy of one catalog workflow, for the provider's revoke screen. */
    public List<Workflow> deliveries(Long sourceId) {
        return sourceId == null
                ? List.of()
                : workflowRepository.findBySourceIdOrderByTenantIdAscIdAsc(sourceId);
    }

    public Map<String, Long> rolloutCountsBySource() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : workflowRepository.countGroupedBySourceId()) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return counts;
    }

    // ------------------------------------------------------------------

    private long automationCount(String tenantId) {
        return workflowRepository.countByTenantId(tenantId)
                + agentClient.countForTenant(tenantId);
    }

    /**
     * Server-side node count from the canvas JSON's {@code nodes} array — a
     * client-supplied count could bypass MAX_NODES.
     */
    int countNodes(String definition) {
        if (definition == null || definition.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(definition);
            if (!root.isObject()) {
                throw WorkflowException.badRequest("invalid_definition",
                        "Workflow definition must be a JSON object");
            }
            return root.path("nodes").size();
        } catch (WorkflowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw WorkflowException.badRequest("invalid_definition",
                    "Workflow definition is not valid JSON");
        }
    }
}
