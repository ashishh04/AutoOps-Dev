package com.intertec.autoops.alerts.client;

import com.intertec.autoops.alerts.config.AlertProperties;
import com.intertec.autoops.alerts.exception.AlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * The only thing in AutoOps that speaks to the alert engine.
 *
 * <p><b>Mapped calls, never a proxy.</b> The engine's API is large — presets,
 * CEL search, deduplication rules, workflow execution, provider credentials —
 * and a pass-through would let any authenticated caller reach all of it with a
 * platform-wide key. Only the three reads the console actually needs exist
 * here, and none of them forwards a caller-supplied query. The since-deleted
 * Dify bridge made the same call for the same reason.
 *
 * <p>Nothing here is cached. An alert's whole value is that it is current.
 */
@Component
public class KeepApiClient {

    private static final Logger log = LoggerFactory.getLogger(KeepApiClient.class);

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;
    private final AlertProperties.Engine engine;

    public KeepApiClient(@Qualifier("engineRestClient") RestClient client,
                         AlertProperties properties) {
        this.client = client;
        this.engine = properties.getEngine();
    }

    /**
     * Every alert the engine is holding, newest first, capped at
     * {@code max-fetch}.
     *
     * <p>The cap is a real limit and not a page size: filtering happens after
     * this returns, so an alert outside the window is invisible to the console
     * even though it exists. That is the price of not handing the engine a
     * caller-built query, and it is the right trade while the alternative is a
     * tenant boundary made of string escaping.
     */
    public List<Map<String, Object>> alerts() {
        return getList("/alerts?limit=" + engine.getMaxFetch());
    }

    public Map<String, Object> alert(String fingerprint) {
        // The fingerprint is a path segment on the engine. It reaches us from a
        // URL, so it is encoded rather than concatenated — a caller must not be
        // able to walk out of /alerts/ into another of the engine's routes.
        String encoded = java.net.URLEncoder.encode(fingerprint, java.nio.charset.StandardCharsets.UTF_8);
        return get("/alerts/" + encoded);
    }

    public List<Map<String, Object>> incidents() {
        Map<String, Object> page = get("/incidents?limit=" + engine.getMaxFetch());
        Object items = page == null ? null : page.get("items");
        if (items instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> typed = (List<Map<String, Object>>) list;
            return typed;
        }
        return List.of();
    }

    /** The whole catalog: available types, plus what is already connected. */
    public Map<String, Object> providers() {
        return get("/providers");
    }

    /**
     * Connects a monitoring source. Uses the ADMIN key — the engine has no role
     * between read-only and write:providers — which is exactly why it is a
     * different credential from the one every read uses.
     */
    public Map<String, Object> installProvider(Map<String, Object> payload) {
        return post("/providers/install", payload);
    }

    public void deleteProvider(String type, String id) {
        String t = java.net.URLEncoder.encode(type, java.nio.charset.StandardCharsets.UTF_8);
        String i = java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8);
        try {
            client.delete().uri("/providers/" + t + "/" + i)
                    .header("x-api-key", adminKey())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw translate(res.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException ex) {
            throw unreachable(ex);
        }
    }

    /** The engine's per-source setup guide. Rewritten before anyone sees it. */
    public String webhookMarkdown(String providerType) {
        String t = java.net.URLEncoder.encode(providerType, java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> body = getWith(adminKey(), "/providers/" + t + "/webhook");
        Object md = body == null ? null : body.get("webhookMarkdown");
        return md == null ? "" : String.valueOf(md);
    }

    /**
     * Forwards one inbound alert. Uses the INGEST key, whose role is
     * write:alert and nothing else — a leak of it cannot read the stream back.
     */
    /**
     * Forwards an arriving alert to the engine.
     *
     * <p>{@code providerId} names the connected source it came through, and it
     * is what makes the alert recognisable as a tenant's own when the LABELS do
     * not survive — which is the common case, not the edge one. The engine runs
     * each source type's own parser, and a parser that rebuilds the alert from
     * the vendor's shape (Alertmanager nests its labels under
     * {@code alerts[].labels}) discards the {@code autoops_tenant} stamp
     * applied at the top level. The alert then belongs to nobody and the
     * customer who just sent it cannot see it.
     *
     * <p>Null when this scope has no matching connection. The alert still
     * arrives; it is simply recognisable only by whatever labels survived.
     */
    public void ingest(String providerType, Map<String, Object> alert, String providerId) {
        String t = java.net.URLEncoder.encode(providerType, java.nio.charset.StandardCharsets.UTF_8);
        String path = "/alerts/event/" + t;
        if (providerId != null && !providerId.isBlank()) {
            path += "?provider_id=" + java.net.URLEncoder.encode(
                    providerId, java.nio.charset.StandardCharsets.UTF_8);
        }
        String key = engine.getIngestApiKey();
        postWith(key == null || key.isBlank() ? adminKey() : key, path, alert);
    }

    private Map<String, Object> post(String path, Map<String, Object> payload) {
        return postWith(adminKey(), path, payload);
    }

    private Map<String, Object> postWith(String key, String path, Map<String, Object> payload) {
        try {
            return client.post().uri(path)
                    .header("x-api-key", key)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw translate(res.getStatusCode());
                    })
                    .body(MAP);
        } catch (ResourceAccessException ex) {
            throw unreachable(ex);
        }
    }

    /**
     * Falls back to the read key only so a deployment that has not set the
     * admin key yet fails at the ENGINE with a clear 403, rather than here with
     * an empty header that looks like a bug in this service.
     */
    private String adminKey() {
        String admin = engine.getAdminApiKey();
        return admin == null || admin.isBlank() ? engine.getApiKey() : admin;
    }

    private List<Map<String, Object>> getList(String path) {
        try {
            List<Map<String, Object>> body = client.get().uri(path)
                    .header("x-api-key", engine.getApiKey())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw translate(res.getStatusCode());
                    })
                    .body(LIST_OF_MAPS);
            return body == null ? List.of() : body;
        } catch (ResourceAccessException ex) {
            throw unreachable(ex);
        }
    }

    private Map<String, Object> get(String path) {
        return getWith(engine.getApiKey(), path);
    }

    private Map<String, Object> getWith(String key, String path) {
        try {
            return client.get().uri(path)
                    .header("x-api-key", key)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw translate(res.getStatusCode());
                    })
                    .body(MAP);
        } catch (ResourceAccessException ex) {
            throw unreachable(ex);
        }
    }

    private AlertException translate(HttpStatusCode status) {
        if (status.value() == 401 || status.value() == 403) {
            // Ours to fix, not the customer's: the platform key is wrong,
            // expired, or too narrow a role for what was asked.
            log.error("Alert engine refused the platform API key ({})", status.value());
            return AlertException.upstream("alert_engine_unauthorized",
                    "The alert engine rejected this platform's credentials");
        }
        if (status.value() == 404) {
            return AlertException.notFound("alert_not_found", "No such alert");
        }
        log.error("Alert engine returned {}", status.value());
        return AlertException.upstream("alert_engine_error",
                "The alert engine could not answer that request");
    }

    private AlertException unreachable(ResourceAccessException ex) {
        // The engine's URL is deliberately absent from the message. It is the
        // white-label boundary, and a 502 body is a place customers read.
        log.error("Alert engine unreachable", ex);
        return AlertException.upstream("alert_engine_unreachable",
                "The alert engine is not responding");
    }
}
