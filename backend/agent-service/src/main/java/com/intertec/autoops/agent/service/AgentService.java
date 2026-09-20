package com.intertec.autoops.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intertec.autoops.agent.client.ToolTargetClient;
import com.intertec.autoops.agent.domain.Agent;
import com.intertec.autoops.agent.exception.AgentException;
import com.intertec.autoops.agent.repo.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI agents inside a project. Behaviour is unchanged from when this lived in
 * core-service: MAX_AUTOMATIONS on create (agents and workflows share one
 * automation budget — the workflow half now fetched from workflow-service),
 * and the plain subscription gate on everything else. Reads are never gated.
 *
 * <p>An agent's power is exactly its {@code tools} allow-list, so that list
 * stays the security boundary: every entry is re-resolved against the OWNING
 * PROJECT on every write. What the split changed is where the answer comes
 * from — jobs from core-service, workflows from workflow-service — and that
 * a peer which cannot answer is a REFUSAL, not a pass. The stored JSON is the
 * normalized result of that check, never the raw request body.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRepository agentRepository;
    private final ToolTargetClient toolTargets;
    private final SubscriptionGate gate;
    private final ObjectMapper objectMapper;

    public AgentService(AgentRepository agentRepository,
                        ToolTargetClient toolTargets,
                        SubscriptionGate gate,
                        ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.toolTargets = toolTargets;
        this.gate = gate;
        this.objectMapper = objectMapper;
    }

    /**
     * One entry of the allow-list, resolved for display.
     *
     * <p>{@code mutating} is carried so an edit can send back what it was
     * given. A console that reads {type, id} and writes {type, id} silently
     * DROPS the flag the provider catalog declared, and phase narrowing then
     * hides the agent's own read-only automations from the phase that
     * gathers evidence. Null means undeclared, which is a third state and not
     * the same as false — see ToolRef.
     */
    public record ToolView(String type, Long id, String name, boolean available,
                           Boolean mutating) {
    }

    @Transactional(readOnly = true)
    public List<Agent> list(String tenantId, Long projectId) {
        toolTargets.requireProject(tenantId, projectId);
        return agentRepository.findByProjectIdAndTenantIdOrderByCreatedAtDesc(projectId, tenantId);
    }

    @Transactional(readOnly = true)
    public Agent get(String tenantId, Long id) {
        return agentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> AgentException.notFound("agent_not_found", "No such agent"));
    }

    /**
     * Roll a catalog agent out into a tenant's project — the only path that
     * creates a PROVIDER-origin row, and the only way an agent comes into
     * existence now that tenants no longer build them.
     *
     * <p>The tool allow-list is still re-resolved against the target project
     * here, exactly as for a tenant-built agent. That check is not skippable
     * even for a provider: an allow-list entry pointing at a job in a
     * different project is the one way a rolled-out agent could reach across
     * a tenant boundary.
     */
    @Transactional
    public Agent rollOut(String tenantId, String actor, String accessToken, Long projectId,
                         Long sourceId, String name, String description, String model,
                         String instructions, String graphRef, String graphVersion,
                         String tools) {
        // One delivered copy per catalog item per project. The name check in
        // doCreate stops the obvious repeat, but only while the names still
        // match: rename the catalog item, roll out again, and it lets a second
        // copy of the same source through. Matching on sourceId closes that,
        // and gives the provider a conflict that says what actually happened
        // instead of "an agent with this name already exists".
        if (sourceId != null && agentRepository.existsByProjectIdAndSourceId(projectId, sourceId)) {
            throw AgentException.conflict("already_delivered",
                    "This project already has this agent. Edit the delivered copy to update "
                            + "it, or roll out to a different project.");
        }
        // planGate=false: the gate reads the tenant from the bearer token, and
        // on a rollout that token is the PROVIDER's — it would test the wrong
        // subscription. core-service checks the RECEIVING customer's
        // subscription before calling here.
        Agent agent = doCreate(tenantId, actor, accessToken, projectId, name, description,
                model, instructions, tools, false);
        agent.setOrigin(Agent.Origin.PROVIDER);
        agent.setSourceId(sourceId);
        // Set only on the rollout path. A customer cannot point an agent at a
        // graph module themselves — the module is the provider's product, and
        // the only legitimate way to acquire one is to be given it.
        agent.setGraphRef(blankToNull(graphRef));
        agent.setGraphVersion(blankToNull(graphVersion));
        Agent saved = agentRepository.save(agent);
        log.info("Rolled catalog agent {} out to tenant {} project {} as agent {}",
                sourceId, tenantId, projectId, saved.getId());
        return saved;
    }

    @Transactional
    public Agent create(String tenantId, String actor, String accessToken, Long projectId,
                        String name, String description, String model, String instructions,
                        String tools) {
        return doCreate(tenantId, actor, accessToken, projectId, name, description, model,
                instructions, tools, true);
    }

    /**
     * @param planGate whether to test the ACCESS TOKEN's plan. False only on
     *                 the rollout path, where the token belongs to the provider
     *                 rather than to the tenant receiving the agent.
     */
    private Agent doCreate(String tenantId, String actor, String accessToken, Long projectId,
                           String name, String description, String model, String instructions,
                           String tools, boolean planGate) {
        toolTargets.requireProject(tenantId, projectId);
        if (agentRepository.existsByProjectIdAndName(projectId, name)) {
            throw AgentException.conflict("agent_exists",
                    "An agent with this name already exists in the project");
        }
        if (planGate) {
            gate.requireQuota(accessToken, "MAX_AUTOMATIONS", automationCount(tenantId),
                    "automations (workflows and agents)");
        }
        // NOT gated: the allow-list is re-resolved against the destination
        // project on every path, provider or not.
        String normalizedTools = normalizeTools(tenantId, projectId, tools);

        Agent agent = new Agent();
        agent.setTenantId(tenantId);
        agent.setProjectId(projectId);
        agent.setName(name);
        agent.setDescription(description);
        agent.setModel(blankToNull(model));
        agent.setInstructions(blankToNull(instructions));
        agent.setTools(normalizedTools);
        agent.setToolCount(countTools(normalizedTools));
        agent.setCreatedBy(actor);
        Agent saved = agentRepository.save(agent);
        log.info("Tenant {} created agent {} ({} tools)", tenantId, saved.getId(),
                saved.getToolCount());
        return saved;
    }

    /**
     * Partial update: a null field is left untouched, so the designer can save
     * the persona without resending the whole allow-list. Renaming onto another
     * agent's name in the same project is a conflict, matching the unique key.
     */
    @Transactional
    public Agent update(String tenantId, String accessToken, Long id, String name,
                        String description, String model, String instructions, String tools,
                        boolean callerIsProvider) {
        gate.requireActive(accessToken);
        Agent agent = get(tenantId, id);
        requireOwner(agent, callerIsProvider, "edited");
        Long projectId = agent.getProjectId();
        if (name != null && !name.isBlank() && !name.equals(agent.getName())) {
            if (agentRepository.existsByProjectIdAndName(projectId, name)) {
                throw AgentException.conflict("agent_exists",
                        "An agent with this name already exists in the project");
            }
            agent.setName(name);
        }
        if (description != null) {
            agent.setDescription(blankToNull(description));
        }
        if (model != null) {
            agent.setModel(blankToNull(model));
        }
        if (instructions != null) {
            agent.setInstructions(blankToNull(instructions));
        }
        if (tools != null) {
            String normalizedTools = normalizeTools(tenantId, projectId, tools);
            agent.setTools(normalizedTools);
            agent.setToolCount(countTools(normalizedTools));
        }
        return agentRepository.save(agent);
    }

    /** Disabling is the kill switch: a disabled agent may not act at all. */
    @Transactional
    public Agent setEnabled(String tenantId, String accessToken, Long id, boolean enabled) {
        gate.requireActive(accessToken);
        Agent agent = get(tenantId, id);
        agent.setEnabled(enabled);
        return agentRepository.save(agent);
    }

    /**
     * Deleting frees a MAX_AUTOMATIONS slot. A rolled-out agent is withdrawn
     * by the PROVIDER revoking it, not by the tenant deleting it.
     */
    @Transactional
    public void delete(String tenantId, String accessToken, Long id, boolean callerIsProvider) {
        gate.requireActive(accessToken);
        Agent agent = get(tenantId, id);
        requireOwner(agent, callerIsProvider, "deleted");
        agentRepository.delete(agent);
        log.info("Tenant {} deleted agent {}", tenantId, id);
    }

    /**
     * A PROVIDER-built agent may only be changed by a PROVIDER. Enable/disable
     * is deliberately NOT routed through here — disabling is the kill switch,
     * and a customer must always be able to stop an agent acting in their own
     * workspace, whoever built it.
     */
    /**
     * Point a delivered agent at a different model.
     *
     * <p><b>The one field a tenant may change on a provider-managed agent.</b>
     * Which LLM processes a customer's infrastructure data is their decision —
     * cost, data residency, and which vendor they are willing to send an
     * inventory of their servers to. Sealing that alongside the persona would
     * mean the provider picks a customer's AI vendor for them.
     *
     * <p>Everything that makes the agent what it is stays sealed: the persona
     * is still unreadable and the tool allow-list still cannot be widened.
     * The model is operational configuration, not design.
     *
     * <p>The id is not validated here. {@code ModelProviderService
     * .resolveForModel} already refuses a model no enabled connection in that
     * workspace offers, and duplicating that check would put a second, weaker
     * copy of the rule in a service that cannot see the tenant's providers.
     */
    @Transactional
    public Agent setModel(String tenantId, String accessToken, Long id, String model) {
        gate.requireActive(accessToken);
        Agent agent = get(tenantId, id);
        if (model == null || model.isBlank()) {
            throw AgentException.badRequest("model_required",
                    "Choose a model — an agent with none cannot resolve a credential to run on");
        }
        agent.setModel(model.trim());
        log.info("Tenant {} pointed agent {} at model {}", tenantId, id, model.trim());
        return agentRepository.save(agent);
    }

    private void requireOwner(Agent agent, boolean callerIsProvider, String verb) {
        if (agent.isProviderAuthored() && !callerIsProvider) {
            throw AgentException.forbidden("provider_managed",
                    "This agent is managed by your provider and cannot be " + verb
                            + ". You can enable, disable and run it.");
        }
    }

    /** Quota basis for workflow-service's half of the shared budget. */
    @Transactional(readOnly = true)
    public long countForTenant(String tenantId) {
        return agentRepository.countByTenantId(tenantId);
    }

    /**
     * Delivered copies per catalog item, keyed by source id as a STRING — JSON
     * object keys are strings, and returning Long keys only for Jackson to
     * stringify them anyway invites a caller to look one up with a number and
     * silently miss.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> rolloutCountsBySource() {
        Map<String, Long> counts = new java.util.HashMap<>();
        for (Object[] row : agentRepository.countGroupedBySourceId()) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * Resolves each agent's allow-list to display names in TWO calls — one to
     * core-service for the project's jobs, one to workflow-service for its
     * workflows — never one call per tool.
     *
     * <p>A tool whose target has since been deleted comes back
     * {@code available=false} rather than silently disappearing: an agent
     * pointing at something that no longer exists is a configuration problem
     * its owner has to see.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ToolView>> describeTools(String tenantId, Long projectId,
                                                   List<Agent> agents) {
        if (agents.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> jobs = toolTargets.jobNames(tenantId, projectId);
        Map<Long, String> workflows = toolTargets.workflowNames(tenantId, projectId);
        return agents.stream().collect(Collectors.toMap(Agent::getId,
                agent -> parseTools(agent.getTools()).stream().map(ref -> {
                    String name = ("JOB".equals(ref.type()) ? jobs : workflows).get(ref.id());
                    return new ToolView(ref.type(), ref.id(),
                            name != null ? name
                                    : "Deleted " + ref.type().toLowerCase(Locale.ROOT)
                                            + " #" + ref.id(),
                            name != null, ref.mutating());
                }).toList()));
    }

    /** Single-agent convenience for the create/update/get responses. */
    @Transactional(readOnly = true)
    public List<ToolView> describeTools(String tenantId, Agent agent) {
        return describeTools(tenantId, agent.getProjectId(), List.of(agent))
                .getOrDefault(agent.getId(), List.of());
    }

    // ------------------------------------------------------------------

    /**
     * Workflows and agents draw on ONE MAX_AUTOMATIONS budget; workflow-service
     * counts the same pair from its side.
     */
    private long automationCount(String tenantId) {
        return agentRepository.countByTenantId(tenantId)
                + toolTargets.workflowCount(tenantId);
    }

    /**
     * A requested tool, and whether its author said it changes customer state.
     *
     * <p>{@code mutating} is a {@link Boolean} rather than a primitive because
     * "not declared" and "declared false" are different facts. Phase narrowing
     * fails closed on the former — {@code AgentToolbox} treats an unmarked tool
     * as mutating, which hides it from the evidence-gathering phase — so
     * flattening the two would silently turn every undeclared tool into a
     * read-only one. Carrying the null keeps that decision where it belongs.
     *
     * <p>Identity is {@code (type, id)} alone. The de-duplicating set must not
     * treat the same automation declared twice with different flags as two
     * tools; the first declaration wins, as it does for every other field.
     */
    private record ToolRef(String type, Long id, Boolean mutating) {

        @Override
        public boolean equals(Object other) {
            return other instanceof ToolRef ref
                    && Objects.equals(type, ref.type)
                    && Objects.equals(id, ref.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id);
        }
    }

    /**
     * Validates the requested allow-list against the agent's own project and
     * returns the canonical JSON to store — duplicates collapsed, unknown or
     * out-of-project targets refused. A blank list stores NULL.
     */
    private String normalizeTools(String tenantId, Long projectId, String tools) {
        List<ToolRef> refs = parseRequestedTools(tools);
        if (refs.isEmpty()) {
            return null;
        }
        ArrayNode out = objectMapper.createArrayNode();
        for (ToolRef ref : refs) {
            var target = "JOB".equals(ref.type())
                    ? toolTargets.findJob(tenantId, ref.id())
                    : toolTargets.findWorkflow(tenantId, ref.id());
            boolean inProject = target
                    .filter(t -> projectId.equals(t.projectId()))
                    .isPresent();
            if (!inProject) {
                throw AgentException.badRequest("unknown_tool_target",
                        "An agent can only use automations from its own project — "
                                + ref.type().toLowerCase(Locale.ROOT) + " #" + ref.id()
                                + " is not one of them");
            }
            ObjectNode node = out.addObject();
            node.put("type", ref.type());
            node.put("id", ref.id());
            if (ref.mutating() != null) {
                // Carried through rather than recomputed. The provider catalog
                // declares this per tool and validates it against the IAM
                // permissions the workflow asks for; dropping it here used to
                // leave every delivered agent's read-only tools looking
                // state-changing, which hid them from GATHER and produced runs
                // that reported having collected nothing.
                node.put("mutating", ref.mutating());
            }
        }
        return out.toString();
    }

    /** Request-side parse: strict, because this is the security boundary. */
    private List<ToolRef> parseRequestedTools(String tools) {
        if (tools == null || tools.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(tools);
        } catch (Exception ex) {
            throw AgentException.badRequest("invalid_tools",
                    "Agent tools must be a JSON array of {type, id} entries");
        }
        if (!root.isArray()) {
            throw AgentException.badRequest("invalid_tools",
                    "Agent tools must be a JSON array of {type, id} entries");
        }
        Set<ToolRef> unique = new LinkedHashSet<>();
        for (JsonNode entry : root) {
            String type = entry.path("type").asText("").trim().toUpperCase(Locale.ROOT);
            JsonNode id = entry.path("id");
            if (!id.isIntegralNumber()
                    || (!"JOB".equals(type) && !"WORKFLOW".equals(type))) {
                throw AgentException.badRequest("invalid_tools",
                        "Each agent tool needs a type of JOB or WORKFLOW and a numeric id");
            }
            // Absent stays absent: see ToolRef. Anything that is present but
            // not a boolean is a malformed grant rather than a default, and is
            // rejected with the rest of the shape validation above.
            JsonNode declared = entry.path("mutating");
            if (!declared.isMissingNode() && !declared.isNull() && !declared.isBoolean()) {
                throw AgentException.badRequest("invalid_tools",
                        "An agent tool's \"mutating\" flag must be true or false");
            }
            unique.add(new ToolRef(type, id.asLong(),
                    declared.isBoolean() ? declared.asBoolean() : null));
        }
        return new ArrayList<>(unique);
    }

    /** Read-side parse: tolerant, because this JSON was written by us. */
    private List<ToolRef> parseTools(String tools) {
        if (tools == null || tools.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(tools);
            if (!root.isArray()) {
                return List.of();
            }
            List<ToolRef> refs = new ArrayList<>();
            for (JsonNode entry : root) {
                String type = "JOB".equals(entry.path("type").asText()) ? "JOB" : "WORKFLOW";
                // The read side is tolerant by design, so an unreadable flag is
                // carried as "undeclared" rather than guessed. This parse feeds
                // counting and description, neither of which consults it —
                // AgentToolbox re-reads the stored JSON itself.
                JsonNode declared = entry.path("mutating");
                refs.add(new ToolRef(type, entry.path("id").asLong(),
                        declared.isBoolean() ? declared.asBoolean() : null));
            }
            return refs;
        } catch (Exception ex) {
            log.warn("Unreadable tools JSON on an agent — treating it as empty: {}",
                    ex.getMessage());
            return List.of();
        }
    }

    private int countTools(String normalizedTools) {
        return parseTools(normalizedTools).size();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Package-private hook so tests can read the stored allow-list. */
    List<Long> toolIds(Agent agent, String type) {
        return parseTools(agent.getTools()).stream()
                .filter(ref -> ref.type().equals(type))
                .map(ToolRef::id)
                .toList();
    }
}
