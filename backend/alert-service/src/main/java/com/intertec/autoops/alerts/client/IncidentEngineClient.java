package com.intertec.autoops.alerts.client;

import com.intertec.autoops.alerts.config.AlertProperties;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The incident half of the alert engine.
 *
 * <p>Same rules as the alert client it sits beside: mapped calls only, never a
 * proxy, and no caller-supplied query reaches the engine. The engine's incident
 * API also offers merge, split, resolve-prediction and workflow triggers — none
 * of which are exposed, because each is a way to mutate correlation from
 * outside the thing that owns it.
 */
@Component
public class IncidentEngineClient {

    private static final Logger log = LoggerFactory.getLogger(IncidentEngineClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;
    private final AlertProperties.Engine engine;

    public IncidentEngineClient(@Qualifier("incidentEngineRestClient") RestClient client,
                                AlertProperties properties) {
        this.client = client;
        this.engine = properties.getEngine();
    }

    /** Newest first, capped. Scoping happens after this returns. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> incidents(int limit) {
        Map<String, Object> page = get(read(), "/incidents?limit=" + limit
                + "&sorting=-last_seen_time");
        Object items = page == null ? null : page.get("items");
        return items instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    public Map<String, Object> incident(String id) {
        return get(read(), "/incidents/" + enc(id));
    }

    /** The evidence: the alerts correlation decided are the same problem. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> incidentAlerts(String id, int limit) {
        Map<String, Object> page = get(read(), "/incidents/" + enc(id) + "/alerts?limit=" + limit);
        Object items = page == null ? null : page.get("items");
        return items instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /** The correlation rules, so "why are these grouped?" has an answer. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rules() {
        try {
            List<Map<String, Object>> body = client.get().uri("/rules")
                    .header("x-api-key", read())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> {
                        throw translate(rs.getStatusCode());
                    })
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    });
            return body == null ? List.of() : body;
        } catch (ResourceAccessException ex) {
            throw unreachable(ex);
        }
    }

    public void setStatus(String id, String status, String comment) {
        post(write(), "/incidents/" + enc(id) + "/status",
                comment == null || comment.isBlank()
                        ? Map.of("status", status)
                        : Map.of("status", status, "comment", comment));
    }

    public void comment(String id, String comment) {
        // The engine models a comment as a status change that does not change
        // the status — same endpoint shape, current status echoed back.
        post(write(), "/incidents/" + enc(id) + "/comment",
                Map.of("status", "firing", "comment", comment));
    }

    public void assign(String id, String user) {
        post(write(), "/incidents/" + enc(id) + "/assign?user=" + enc(user), Map.of());
    }

    /**
     * Stores arbitrary JSON against the incident.
     *
     * <p>This is what lets an investigation survive without this service owning
     * a database: the engine already persists enrichment per incident and hands
     * it back on read. A Holmes run costs real money and tens of seconds, so
     * re-running it on every page load would be indefensible.
     */
    public void enrich(String id, Map<String, Object> enrichments) {
        post(write(), "/incidents/" + enc(id) + "/enrich",
                Map.of("enrichments", enrichments, "force", true));
    }

    private String read() {
        return engine.getApiKey();
    }

    /** Status, assignment and enrichment are writes; the read key cannot do them. */
    private String write() {
        String admin = engine.getAdminApiKey();
        return admin == null || admin.isBlank() ? engine.getApiKey() : admin;
    }

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private Map<String, Object> get(String key, String path) {
        try {
            return client.get().uri(path)
                    .header("x-api-key", key)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> {
                        throw translate(rs.getStatusCode());
                    })
                    .body(MAP);
        } catch (ResourceAccessException ex) {
            throw unreachable(ex);
        }
    }

    private void post(String key, String path, Map<String, Object> payload) {
        try {
            client.post().uri(path)
                    .header("x-api-key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> {
                        throw translate(rs.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException ex) {
            throw unreachable(ex);
        }
    }

    private AlertException translate(HttpStatusCode status) {
        if (status.value() == 404) {
            return AlertException.notFound("incident_not_found", "No such incident");
        }
        if (status.value() == 401 || status.value() == 403) {
            log.error("Alert engine refused the platform key for an incident call ({})",
                    status.value());
            return AlertException.upstream("alert_engine_unauthorized",
                    "The alert engine rejected this platform's credentials");
        }
        log.error("Alert engine returned {} for an incident call", status.value());
        return AlertException.upstream("alert_engine_error",
                "The alert engine could not answer that request");
    }

    private AlertException unreachable(ResourceAccessException ex) {
        log.error("Alert engine unreachable", ex);
        return AlertException.upstream("alert_engine_unreachable",
                "The alert engine is not responding");
    }
}
