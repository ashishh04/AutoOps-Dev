package com.intertec.autoops.agent.web;

import com.intertec.autoops.agent.domain.Agent;
import com.intertec.autoops.agent.service.AgentService;
import com.intertec.autoops.agent.web.dto.AgentRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The service-to-service face of agent-service. Guarded by
 * {@code X-Internal-Token} (InternalTokenFilter) and never routed by the
 * gateway.
 *
 * <p>Callers and what they need:
 * <ul>
 *   <li><b>workflow-service</b> — how many agents a tenant holds, because
 *       workflows and agents share a single MAX_AUTOMATIONS budget.</li>
 *   <li><b>core-service</b> — rolling a catalog agent out to a tenant, and
 *       revoking one, on behalf of a PROVIDER whose role it has already
 *       checked.</li>
 * </ul>
 */
@RestController
public class InternalAgentController {

    private final AgentService agentService;

    public InternalAgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/internal/agents/count")
    public Map<String, Long> count(@RequestParam String tenantId) {
        return Map.of("count", agentService.countForTenant(tenantId));
    }

    /**
     * Delivered copies per catalog item, keyed by catalog id, for the provider's
     * library view. Cross-tenant by nature — "how many customers hold this" is
     * the question — but it names no tenant and returns no persona.
     */
    @GetMapping("/internal/agents/rollout-counts")
    public Map<String, Long> rolloutCounts() {
        return agentService.rolloutCountsBySource();
    }

    /** What core-service echoes back after a rollout — never the persona. */
    public record RolledOutAgent(Long id, String name, int toolCount) {
    }

    @PostMapping("/internal/projects/{projectId}/agents/rollout")
    public RolledOutAgent rollOut(@PathVariable Long projectId,
                                  @RequestParam String tenantId,
                                  @RequestParam String actor,
                                  @RequestParam Long sourceId,
                                  @RequestHeader("X-Access-Token") String accessToken,
                                  @Valid @RequestBody AgentRequest request) {
        Agent agent = agentService.rollOut(tenantId, actor, accessToken, projectId, sourceId,
                request.name(), request.description(), request.model(),
                request.instructions(), request.graphRef(), request.graphVersion(),
                request.tools(), request.phases());
        return new RolledOutAgent(agent.getId(), agent.getName(), agent.getToolCount());
    }

    /** Revoke: the provider withdrawing an agent it rolled out. */
    @DeleteMapping("/internal/agents/{id}")
    public void revoke(@PathVariable Long id,
                       @RequestParam String tenantId,
                       @RequestHeader("X-Access-Token") String accessToken) {
        agentService.delete(tenantId, accessToken, id, true);
    }
}
