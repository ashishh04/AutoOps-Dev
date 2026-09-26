package com.intertec.autoops.agent.client;

import com.intertec.autoops.agent.config.AgentProperties;
import com.intertec.autoops.agent.exception.AgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The agent's allow-list is its entire authority, so this is the security
 * boundary of the whole service — and after the split, the facts it needs
 * live in two other services: jobs and projects in core-service, workflows in
 * workflow-service.
 *
 * <p><strong>Fail closed.</strong> A target is accepted only when its owning
 * service positively confirms it exists, belongs to this tenant, and sits in
 * the agent's own project. Anything else — 404, timeout, service down — is a
 * refusal. The alternative (assume-good when a peer is unreachable) would let
 * an outage be the moment an agent gets pointed at something it should never
 * have been able to touch.
 */
@Component
public class ToolTargetClient {

    private static final Logger log = LoggerFactory.getLogger(ToolTargetClient.class);

    private final RestClient coreRestClient;
    private final RestClient workflowRestClient;
    private final String coreToken;
    private final String workflowToken;

    public ToolTargetClient(@Qualifier("coreRestClient") RestClient coreRestClient,
                            @Qualifier("workflowRestClient") RestClient workflowRestClient,
                            AgentProperties properties) {
        this.coreRestClient = coreRestClient;
        this.workflowRestClient = workflowRestClient;
        this.coreToken = properties.getCore().getInternalToken();
        this.workflowToken = properties.getWorkflow().getInternalToken();
    }


    /**
     * What an agent's tools need that this project cannot reach.
     *
     * <p>Asked BEFORE a run is queued. An agent whose tools want an AWS account
     * in a project with no AWS account used to start, resolve a model, spend
     * tokens on a plan and fail at the first tool call — minutes and money
     * after the click, with a message about a credential. None of that was
     * unknowable: the tools declare what they need and the project either holds
     * it or does not.
     *
     * <p>Answered by core-service because cloud connections are its, and the
     * visibility rule (a connection with no project is global; one with a
     * project serves only that project) is enforced beside the code that binds
     * them. A copy here would drift and become its own bug.
     *
     * <p><b>Fails OPEN.</b> If core-service cannot answer, the run proceeds.
     * This is a courtesy that saves a doomed run, not a permission check — and
     * turning another service's outage into "your agent is misconfigured" would
     * send whoever reads it somewhere useless.
     */
    public List<String> missingConnections(String tenantId, Long projectId,
                                           List<Long> workflowIds) {
        if (projectId == null || workflowIds == null || workflowIds.isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> body = coreRestClient.get()
                    .uri(builder -> builder.path("/internal/agent/readiness")
                            .queryParam("tenantId", tenantId)
                            .queryParam("projectId", projectId)
                            .queryParam("workflowIds", workflowIds)
                            .build())
                    .header("X-Internal-Token", coreToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            Object missing = body == null ? null : body.get("missing");
            if (!(missing instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).toList();
        } catch (Exception ex) {
            log.debug("Could not pre-flight agent tools for project {}: {}",
                    projectId, ex.getMessage());
            return List.of();
        }
    }

    /** A job or workflow as the allow-list needs to see it. */
    public record Target(Long id, Long projectId, String name) {
    }

    /** Project existence + tenancy, from core-service. Fails closed. */
    public void requireProject(String tenantId, Long projectId) {
        try {
            coreRestClient.get()
                    .uri("/internal/projects/{id}?tenantId={tenantId}", projectId, tenantId)
                    .header("X-Internal-Token", coreToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw AgentException.notFound("project_not_found", "No such project");
                    })
                    .toBodilessEntity();
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Project check unavailable for {}/{}: {}", tenantId, projectId,
                    ex.getMessage());
            throw AgentException.serviceUnavailable("core_unavailable",
                    "Project validation is temporarily unavailable — please retry");
        }
    }

    /** One job; empty ONLY on a real 404 from core-service. */
    public Optional<Target> findJob(String tenantId, Long jobId) {
        return find(coreRestClient, coreToken, "/internal/jobs/{id}?tenantId={tenantId}",
                jobId, tenantId, "core-service");
    }

    /** One workflow; empty ONLY on a real 404 from workflow-service. */
    public Optional<Target> findWorkflow(String tenantId, Long workflowId) {
        return find(workflowRestClient, workflowToken,
                "/internal/workflows/{id}?tenantId={tenantId}", workflowId, tenantId,
                "workflow-service");
    }

    /** Every job in the project, keyed by id — one call per agent list. */
    public Map<Long, String> jobNames(String tenantId, Long projectId) {
        return names(coreRestClient, coreToken,
                "/internal/projects/{projectId}/jobs?tenantId={tenantId}", projectId, tenantId,
                "core-service");
    }

    /** Every workflow in the project, keyed by id — one call per agent list. */
    public Map<Long, String> workflowNames(String tenantId, Long projectId) {
        return names(workflowRestClient, workflowToken,
                "/internal/projects/{projectId}/workflows?tenantId={tenantId}", projectId,
                tenantId, "workflow-service");
    }

    /** Workflow count for the shared MAX_AUTOMATIONS budget. */
    public long workflowCount(String tenantId) {
        try {
            Map<String, Object> body = workflowRestClient.get()
                    .uri("/internal/workflows/count?tenantId={tenantId}", tenantId)
                    .header("X-Internal-Token", workflowToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            return body != null && body.get("count") instanceof Number n ? n.longValue() : 0L;
        } catch (Exception ex) {
            // Mirrors workflow-service's own policy for the agent half: an
            // unreachable peer counts 0 rather than blocking creation.
            log.warn("Workflow count unavailable for tenant {} — counting 0 toward the "
                    + "automation budget: {}", tenantId, ex.getMessage());
            return 0L;
        }
    }

    private Optional<Target> find(RestClient client, String token, String uri, Long id,
                                  String tenantId, String peer) {
        try {
            Map<String, Object> body = client.get()
                    .uri(uri, id, tenantId)
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new NotFound();
                    })
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (body == null) {
                return Optional.empty();
            }
            return Optional.of(new Target(asLong(body.get("id")), asLong(body.get("projectId")),
                    String.valueOf(body.get("name"))));
        } catch (NotFound ex) {
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("{} lookup failed for {} #{}: {}", peer, tenantId, id, ex.getMessage());
            throw AgentException.serviceUnavailable("tool_validation_unavailable",
                    "Cannot verify the agent's tools right now — please retry");
        }
    }

    private Map<Long, String> names(RestClient client, String token, String uri, Long projectId,
                                    String tenantId, String peer) {
        try {
            List<Map<String, Object>> rows = client.get()
                    .uri(uri, projectId, tenantId)
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    });
            Map<Long, String> out = new HashMap<>();
            for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
                Long id = asLong(row.get("id"));
                if (id != null) {
                    out.put(id, String.valueOf(row.get("name")));
                }
            }
            return out;
        } catch (Exception ex) {
            // READ path: an unresolved name renders as "unavailable" next to
            // the id, which is honest. Refusing to list agents because a peer
            // is slow would be worse.
            log.warn("{} name lookup failed for tenant {} project {}: {}", peer, tenantId,
                    projectId, ex.getMessage());
            return Map.of();
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    /** Internal signal only: a real 404 from the owning service. */
    private static class NotFound extends RuntimeException {
        NotFound() {
            super(null, null, false, false);
        }
    }
}
