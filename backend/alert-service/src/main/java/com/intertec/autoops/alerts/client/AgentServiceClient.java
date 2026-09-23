package com.intertec.autoops.alerts.client;

import com.intertec.autoops.alerts.config.OpsProperties;
import com.intertec.autoops.alerts.exception.AlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Runs an AutoOps agent as an investigation.
 *
 * <p><b>The caller's own token is forwarded</b>, not a service token. An
 * investigation is something a person asked for, and running it as them means
 * agent-service applies the same tenant scoping, project access and audit trail
 * it would for any other run — rather than this service asserting a right on
 * their behalf. The run shows up in the agent's history attributed to the
 * person who pressed the button, which is also what an incident timeline needs.
 */
@Component
public class AgentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;
    private final OpsProperties.Agent config;

    public AgentServiceClient(@Qualifier("agentServiceRestClient") RestClient client,
                              OpsProperties properties) {
        this.client = client;
        this.config = properties.getAgent();
    }

    /**
     * Finds the RCA agent rolled out to this project.
     *
     * <p>Matched by NAME, because agent-service does not expose the graph ref an
     * agent was built from. That makes the name load-bearing, which is why it
     * is configurable rather than a constant — a provider who renames the agent
     * changes one variable instead of losing investigation silently.
     */
    public Long findRcaAgentId(String bearer, Long projectId) {
        if (projectId == null) {
            return null;
        }
        try {
            List<Map<String, Object>> agents = client.get()
                    .uri("/api/projects/" + projectId + "/agents")
                    .header("Authorization", "Bearer " + bearer)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> {
                        throw AlertException.upstream("agent_service_error",
                                "Could not read this project's agents");
                    })
                    .body(LIST);
            if (agents == null) {
                return null;
            }
            String wanted = config.getRcaAgentName().toLowerCase();
            for (Map<String, Object> a : agents) {
                Object name = a.get("name");
                if (name != null && String.valueOf(name).toLowerCase().equals(wanted)
                        && !Boolean.FALSE.equals(a.get("enabled"))) {
                    Object id = a.get("id");
                    return id instanceof Number n ? n.longValue() : null;
                }
            }
            return null;
        } catch (ResourceAccessException ex) {
            throw unreachable(ex);
        }
    }

    /** Starts a run. Returns immediately — the answer does not exist yet. */
    public Long startRun(String bearer, Long agentId, String input) {
        try {
            Map<String, Object> run = client.post()
                    .uri("/api/agents/" + agentId + "/runs")
                    .header("Authorization", "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("input", input))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> {
                        throw AlertException.upstream("agent_run_failed",
                                "The investigation could not be started");
                    })
                    .body(MAP);
            Object id = run == null ? null : run.get("id");
            return id instanceof Number n ? n.longValue() : null;
        } catch (ResourceAccessException ex) {
            throw unreachable(ex);
        }
    }

    /** The full record: status, output, and every step the agent took. */
    public Map<String, Object> getRun(String bearer, Long runId) {
        try {
            return client.get()
                    .uri("/api/agent-runs/" + runId)
                    .header("Authorization", "Bearer " + bearer)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> {
                        throw AlertException.upstream("agent_run_unreadable",
                                "Could not read the investigation's progress");
                    })
                    .body(MAP);
        } catch (ResourceAccessException ex) {
            throw unreachable(ex);
        }
    }

    private AlertException unreachable(ResourceAccessException ex) {
        log.error("agent-service unreachable", ex);
        return AlertException.upstream("agent_service_unreachable",
                "The investigation service is not responding");
    }
}
