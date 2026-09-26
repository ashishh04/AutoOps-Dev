package com.intertec.autoops.voice.elevenlabs;

import com.intertec.autoops.voice.config.VoiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The only thing in the platform that holds the ElevenLabs API key.
 *
 * <p>It exchanges that key for a signed WebSocket URL scoped to one
 * conversation with the Aegis-01 agent. The URL is valid for 15 minutes and
 * carries no account authority beyond starting that single conversation, which
 * is why it — and never the key — is what reaches the browser.
 */
@Component
public class ElevenLabsClient {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsClient.class);

    /** ElevenLabs stops honouring a signed URL 15 minutes after it is minted. */
    public static final int SIGNED_URL_TTL_SECONDS = 15 * 60;

    private static final String SIGNED_URL_PATH = "/v1/convai/conversation/get-signed-url";

    private final VoiceProperties properties;
    private final RestClient restClient;

    public ElevenLabsClient(VoiceProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.getApiBaseUrl()).build();
    }

    /** ElevenLabs' response body; snake_case is theirs, not ours. */
    record SignedUrlResponse(String signed_url) {
    }

    /**
     * @return a {@code wss://…} URL the browser can hand straight to
     *         {@code startSession({ signedUrl })}
     * @throws ElevenLabsException with a visitor-safe message on any failure
     */
    public String signedUrl() {
        if (!properties.isConfigured()) {
            throw new ElevenLabsException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The voice agent is not configured on this deployment");
        }

        String uri = UriComponentsBuilder.fromPath(SIGNED_URL_PATH)
                .queryParam("agent_id", properties.getAgentId())
                .build()
                .toUriString();

        SignedUrlResponse response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .header("xi-api-key", properties.getApiKey())
                    .retrieve()
                    .onStatus(status -> status.value() == 401 || status.value() == 403,
                            (req, res) -> {
                                // The REASON, not just the code. A 401 here has
                                // two completely different causes with two
                                // different fixes — a key that is wrong, and a
                                // key that is right but too narrowly scoped —
                                // and ElevenLabs distinguishes them in the body
                                // ("missing the permission convai_write"). Logging
                                // only "rejected our API key" throws that away and
                                // sends whoever reads it off rotating a key that
                                // was never the problem.
                                log.error("ElevenLabs refused the signed-URL request ({}): {}. "
                                                + "Check the key's permissions in Dashboard -> Developers -> "
                                                + "API Keys; minting a conversation URL needs Conversational AI "
                                                + "WRITE, which a read-only key does not have.",
                                        res.getStatusCode(), reason(res));
                                throw new ElevenLabsException(HttpStatus.SERVICE_UNAVAILABLE,
                                        "The voice agent is not available right now");
                            })
                    .onStatus(status -> status.value() == 404,
                            (req, res) -> {
                                log.error("ElevenLabs does not know agent id '{}': {}",
                                        properties.getAgentId(), reason(res));
                                throw new ElevenLabsException(HttpStatus.SERVICE_UNAVAILABLE,
                                        "The voice agent is not available right now");
                            })
                    .onStatus(status -> status.value() == 429,
                            (req, res) -> {
                                log.warn("ElevenLabs rate-limited this deployment: {}", reason(res));
                                throw new ElevenLabsException(HttpStatus.TOO_MANY_REQUESTS,
                                        "The voice agent is busy — please try again shortly");
                            })
                    .onStatus(org.springframework.http.HttpStatusCode::isError,
                            (req, res) -> {
                                log.error("ElevenLabs returned {}: {}", res.getStatusCode(), reason(res));
                                throw new ElevenLabsException(HttpStatus.BAD_GATEWAY,
                                        "The voice agent is not available right now");
                            })
                    .body(SignedUrlResponse.class);
        } catch (ElevenLabsException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // Timeout or DNS/connect failure — ours to report, not the visitor's to debug.
            log.error("Could not reach ElevenLabs: {}", e.getMessage());
            throw new ElevenLabsException(HttpStatus.GATEWAY_TIMEOUT,
                    "The voice agent is not responding — please try again shortly", e);
        } catch (RuntimeException e) {
            log.error("Unexpected failure talking to ElevenLabs", e);
            throw new ElevenLabsException(HttpStatus.BAD_GATEWAY,
                    "The voice agent is not available right now", e);
        }

        return required(response);
    }

    /**
     * ElevenLabs' own explanation of a refusal, for the log only.
     *
     * <p>It is their error text about our request — never the key, and never
     * anything the visitor sent — so it is safe here and useless anywhere a
     * customer reads. Capped, because an upstream that answers an API call with
     * an HTML error page should not put a page of markup in the log.
     */
    private static String reason(org.springframework.http.client.ClientHttpResponse res) {
        try (java.io.InputStream body = res.getBody()) {
            String text = new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return "(no response body)";
            }
            return text.length() > 500 ? text.substring(0, 500) + "…" : text;
        } catch (Exception e) {
            return "(response body unreadable: " + e.getMessage() + ")";
        }
    }

    private static String required(SignedUrlResponse response) {
        if (response == null || response.signed_url() == null || response.signed_url().isBlank()) {
            log.error("ElevenLabs returned a success with no signed_url");
            throw new ElevenLabsException(HttpStatus.BAD_GATEWAY,
                    "The voice agent is not available right now");
        }
        return response.signed_url();
    }
}
