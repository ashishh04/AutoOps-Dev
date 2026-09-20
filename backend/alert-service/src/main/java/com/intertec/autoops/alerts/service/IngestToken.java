package com.intertec.autoops.alerts.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * The credential a customer pastes into Datadog, Grafana or an Airflow DAG.
 *
 * <p><b>Signed, not stored.</b> It carries its own scope — tenant, project,
 * source type — and a MAC over them, so ingest needs no table, no lookup and no
 * migration, and this service keeps owning nothing. That matters more than it
 * sounds: the alternative is a credentials table in the one service whose whole
 * design is that it holds no state.
 *
 * <pre>
 *   {base64url(tenant:project:type)}.{base64url(HMAC-SHA256(secret, payload))}
 * </pre>
 *
 * <p>The trade accepted deliberately: a single token cannot be revoked on its
 * own. Rotating {@code ALERT_INGEST_SECRET} invalidates every token at once,
 * which is the right blast radius for "a key leaked" and the wrong one for
 * "this one connection is stale". Per-token revocation needs a table; when that
 * arrives, this stays valid as the derivation.
 */
public final class IngestToken {

    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    /** Scope decoded from a token that verified. */
    public record Scope(String tenantId, String projectId, String providerType) {
    }

    private IngestToken() {
    }

    public static String mint(String secret, String tenantId, String projectId, String type) {
        String payload = ENC.encodeToString(
                (tenantId + ":" + projectId + ":" + type).getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(secret, payload);
    }

    /**
     * Verifies and decodes. Empty for anything that does not verify — a bad
     * MAC, a malformed token, the wrong number of parts.
     *
     * <p>Every failure returns the SAME empty result. Distinguishing "malformed"
     * from "bad signature" in the response would tell an attacker which half of
     * a guess was right.
     */
    public static Optional<Scope> verify(String secret, String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        String provided = token.substring(dot + 1);

        String expected = sign(secret, payload);
        // Constant time: a byte-by-byte comparison leaks how much of a forged
        // MAC was correct, which is enough to build one a byte at a time.
        if (!MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        try {
            String decoded = new String(DEC.decode(payload), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[2].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Scope(parts[0], parts[1], parts[2]));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return ENC.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot sign ingest token", ex);
        }
    }
}
