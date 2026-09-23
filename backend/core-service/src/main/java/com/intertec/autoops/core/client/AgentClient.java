package com.intertec.autoops.core.client;

import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.exception.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Map;

/**
 * The write path into agent-service, used only by the PROVIDER surface:
 * agents are BUILT in the platform catalog here and rolled out to tenants.
 * Reading agents is still agent-service's own API — this client never fetches
 * one, so a persona cannot leak through core-service either.
 *
 * <p>Guarded end to end by {@code X-Internal-Token}; the PROVIDER's own access
 * token rides along in {@code X-Access-Token} so agent-service's subscription
 * gate still applies to the RECEIVING tenant's plan.
 */
@Component
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final RestClient agentRestClient;
    private final String internalToken;

    public AgentClient(@Qualifier("agentRestClient") RestClient agentRestClient,
                       CoreProperties properties) {
        this.agentRestClient = agentRestClient;
        this.internalToken = properties.getAgent().getInternalToken();
    }

    /**
     * Provider library: how many delivered copies each catalog agent has.
     *
     * <p>Empty map on failure rather than a throw — see the matching method on
     * {@link WorkflowClient}. A count is decoration; the catalog is not.
     */
    public Map<String, Long> rolloutCountsBySource() {
        try {
            Map<String, Long> counts = agentRestClient.get()
                    .uri("/internal/agents/rollout-counts")
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Long>>() {
                    });
            return counts != null ? counts : Map.of();
        } catch (Exception ex) {
            log.warn("Agent rollout counts unavailable: {}", ex.getMessage());
            return Map.of();
        }
    }

    /** What agent-service echoes back — never the persona. */
    public record RolledOutAgent(Long id, String name, int toolCount) {
    }

    /**
     * Roll a catalog agent out into a tenant's project. As with workflows, the
     * caller must have already proved the PROVIDER role AND that the project
     * belongs to the tenant.
     */
    /**
     * @param instructions the persona, for a JSON-authored agent. Null for a
     *                     Python one, whose persona never leaves the runtime's
     *                     image.
     * @param graphRef     the module in agent-runtime's registry, for a
     *                     Python-authored agent. Null for a JSON one.
     */
    public RolledOutAgent rollOut(String tenantId, String actor, String accessToken,
                                  Long projectId, Long sourceId, String name, String description,
                                  String model, String instructions, String graphRef,
                                  String graphVersion, String tools, String phases) {
        // HashMap, not Map.of: every field below is legitimately null for one
        // agent shape or the other, and Map.of rejects nulls.
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", description);
        body.put("model", model);
        body.put("instructions", instructions);
        body.put("graphRef", graphRef);
        body.put("graphVersion", graphVersion);
        body.put("tools", tools);
        // Only ever set for a JSON agent. A Python agent's phases are in its
        // module, and sending them would let a catalog row reshape a graph that
        // was reviewed and released.
        body.put("phases", phases);
        try {
            return agentRestClient.post()
                    .uri("/internal/projects/{projectId}/agents/rollout"
                                    + "?tenantId={tenantId}&actor={actor}&sourceId={sourceId}",
                            projectId, tenantId, actor, sourceId)
                    .header("X-Internal-Token", internalToken)
                    .header("X-Access-Token", accessToken)
                    .body(body)
                    .retrieve()
                    .body(RolledOutAgent.class);
        } catch (RestClientResponseException ex) {
            throw rejected(ex);
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    /**
     * A 4xx from agent-service is a DECISION the provider needs to read — "this
     * project already has this agent" — not an outage.
     *
     * <p>Without this branch every failure went through {@link #unavailable},
     * so a refused delivery came back as "agent-service is unavailable, please
     * retry": wrong, and actively misleading, because retrying could never
     * succeed. RolloutService collects the message per target, so this is the
     * text the provider console shows next to the customer that was skipped.
     */
    private CoreException rejected(RestClientResponseException ex) {
        Map<?, ?> body;
        try {
            body = ex.getResponseBodyAs(Map.class);
        } catch (Exception ignored) {
            body = null; // Not the uniform {"error","message"} body — use the status.
        }
        String code = field(body, "error", "agent_rejected");
        String message = field(body, "message", "agent-service rejected this delivery");
        if (ex.getStatusCode().is5xxServerError()) {
            log.warn("agent-service failed the call: {} {}", ex.getStatusCode(), message);
            return CoreException.badGateway(code, message);
        }
        log.info("agent-service refused the call: {} ({})", message, code);
        return switch (ex.getStatusCode().value()) {
            case 403 -> CoreException.forbidden(code, message);
            case 404 -> CoreException.notFound(code, message);
            case 409 -> CoreException.conflict(code, message);
            default -> CoreException.badRequest(code, message);
        };
    }

    private static String field(Map<?, ?> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    /** Revoke a rolled-out agent. */
    public void revoke(String tenantId, String accessToken, Long agentId) {
        try {
            agentRestClient.delete()
                    .uri("/internal/agents/{id}?tenantId={tenantId}", agentId, tenantId)
                    .header("X-Internal-Token", internalToken)
                    .header("X-Access-Token", accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    private CoreException unavailable(Exception ex) {
        log.warn("agent-service call failed: {}", ex.getMessage());
        return CoreException.serviceUnavailable("agent_unavailable",
                "agent-service is unavailable, please retry");
    }
}
