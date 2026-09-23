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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The investigation engine.
 *
 * <p>One call, {@code POST /api/chat}, which answers with the analysis, every
 * tool it ran, and suggested follow-ups. Non-streaming deliberately: the result
 * is stored against the incident and read back by everyone who opens it, so the
 * interesting artifact is the finished investigation, not watching it think.
 * Streaming would also mean holding a connection open through the gateway for
 * minutes, which Spring Cloud Gateway MVC is the wrong shape for.
 *
 * <p>Like the alert engine, this is <b>never named to a customer</b>. It is
 * "the investigation engine", and its address lives here and in configuration
 * and nowhere else.
 */
@Component
public class HolmesClient {

    private static final Logger log = LoggerFactory.getLogger(HolmesClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;
    private final OpsProperties.Holmes holmes;

    public HolmesClient(@Qualifier("holmesRestClient") RestClient client,
                        OpsProperties properties) {
        this.client = client;
        this.holmes = properties.getHolmes();
    }

    public boolean isEnabled() {
        return holmes.isEnabled();
    }

    /**
     * @param ask     the question, with the incident's evidence already in it
     * @param model   the tenant's own model, or null to let the engine choose
     * @param history prior turns, so a follow-up question knows what was asked
     */
    public Map<String, Object> investigate(String ask, String model,
                                           java.util.List<Map<String, Object>> history) {
        if (!isEnabled()) {
            throw AlertException.badRequest("investigation_disabled",
                    "Investigation is not switched on for this platform.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ask", ask);
        payload.put("stream", false);
        if (model != null && !model.isBlank()) {
            payload.put("model", model);
        }
        if (history != null && !history.isEmpty()) {
            payload.put("conversation_history", history);
        }
        try {
            RestClient.RequestBodySpec spec = client.post().uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON);
            if (holmes.getApiKey() != null && !holmes.getApiKey().isBlank()) {
                spec = spec.header("Authorization", "Bearer " + holmes.getApiKey());
            }
            return spec.body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> {
                        throw translate(rs.getStatusCode());
                    })
                    .body(MAP);
        } catch (ResourceAccessException ex) {
            // Includes the read timeout. An investigation that overran is not a
            // platform fault the customer can act on, so it says so plainly.
            log.error("Investigation engine unreachable or timed out", ex);
            throw AlertException.upstream("investigation_unreachable",
                    "The investigation did not finish in time. Try again.");
        }
    }

    private AlertException translate(HttpStatusCode status) {
        if (status.value() == 401 || status.value() == 403) {
            log.error("Investigation engine refused the platform credentials ({})", status.value());
            return AlertException.upstream("investigation_unauthorized",
                    "The investigation engine rejected this platform's credentials");
        }
        log.error("Investigation engine returned {}", status.value());
        return AlertException.upstream("investigation_failed",
                "The investigation could not be completed");
    }
}
