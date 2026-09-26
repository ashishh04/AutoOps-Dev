package com.intertec.autoops.auth.service;

import com.intertec.autoops.auth.config.AuthProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The whole Resend transport: one POST to {@code /emails} with a bearer token.
 *
 * <p>No vendor SDK. Resend's send API is a single JSON request, so a dependency
 * would buy nothing but a transitive HTTP stack to keep patched — the SendGrid
 * client this replaced dragged in Apache HttpClient for exactly one call.
 *
 * <p>It exists as a separate bean rather than inline in
 * {@link ResendEmailService} so the service can be unit-tested against a
 * mocked transport instead of a real socket.
 *
 * <p><b>Timeouts are bounded at 3 seconds</b>, the same budget the previous
 * provider had: OTP mail is sent after the generating transaction commits, but
 * a slow provider must never be able to pin a request thread.
 */
@Component
public class ResendClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** What the caller needs to decide retry-or-fail. */
    public record Result(int status, String body) {

        public boolean accepted() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient http;
    private final AuthProperties properties;

    public ResendClient(AuthProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * @param json a complete Resend send payload
     * @return the status and body; the body carries Resend's own reason for a
     *         rejection ("The from address is not verified", "Invalid API key"),
     *         which is the part worth logging
     */
    public Result send(String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getResend().getBaseUrl() + "/emails"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + properties.getResend().getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body());
    }
}
