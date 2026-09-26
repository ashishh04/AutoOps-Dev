package com.intertec.autoops.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.auth.config.AuthProperties;
import com.intertec.autoops.auth.domain.OtpDeliveryStatus;
import com.intertec.autoops.auth.repo.OtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Optional Resend webhook: moves {@code otp_entries.delivery_status} to
 * DELIVERED / BOUNCED / FAILED by the provider's message id.
 *
 * <p>Resend signs with <b>Svix</b>, not with the ECDSA scheme the previous
 * provider used. The signed content is {@code id.timestamp.body} and the
 * signature is HMAC-SHA256 under a base64 secret carried after a
 * {@code whsec_} prefix. If no secret is configured the webhook is disabled
 * and returns 403, so a deployment that has not set one up cannot be fed
 * arbitrary delivery events.
 */
@RestController
@RequestMapping("/api/auth/webhooks")
public class ResendWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ResendWebhookController.class);

    private static final String ID_HEADER = "svix-id";
    private static final String TIMESTAMP_HEADER = "svix-timestamp";
    private static final String SIGNATURE_HEADER = "svix-signature";

    private static final String SECRET_PREFIX = "whsec_";
    /**
     * How far out of step a webhook's own timestamp may be. Without this the
     * signature alone makes a captured request replayable forever.
     */
    private static final long TOLERANCE_SECONDS = 300;

    private final AuthProperties properties;
    private final OtpRepository otpRepository;
    private final ObjectMapper objectMapper;

    public ResendWebhookController(AuthProperties properties,
                                   OtpRepository otpRepository,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.otpRepository = otpRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/resend")
    @Transactional
    public ResponseEntity<Void> handleEvent(
            @RequestBody String rawBody,
            @RequestHeader(value = ID_HEADER, required = false) String svixId,
            @RequestHeader(value = TIMESTAMP_HEADER, required = false) String svixTimestamp,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String svixSignature) {

        String secret = properties.getResend().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return ResponseEntity.status(403).build(); // webhook disabled
        }
        if (svixId == null || svixTimestamp == null || svixSignature == null
                || !withinTolerance(svixTimestamp)
                || !verify(secret, svixId + "." + svixTimestamp + "." + rawBody, svixSignature)) {
            log.warn("Rejected Resend webhook call with missing/invalid signature");
            return ResponseEntity.status(403).build();
        }

        try {
            applyEvent(objectMapper.readTree(rawBody));
        } catch (Exception ex) {
            log.error("Failed to process Resend webhook payload: {}", ex.getMessage());
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().build();
    }

    /** Resend posts one event per call: {@code {type, created_at, data{...}}}. */
    private void applyEvent(JsonNode event) {
        String messageId = event.path("data").path("email_id").asText(null);
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        OtpDeliveryStatus newStatus = switch (event.path("type").asText("")) {
            case "email.delivered" -> OtpDeliveryStatus.DELIVERED;
            case "email.bounced" -> OtpDeliveryStatus.BOUNCED;
            // Carried over from the previous provider's "deferred" mapping. A
            // delayed mail may still arrive, so this is pessimistic on purpose:
            // a code reported undelivered that then lands is a better failure
            // than one reported fine that never does.
            case "email.delivery_delayed" -> OtpDeliveryStatus.FAILED;
            // email.sent is what we already recorded ourselves, and
            // email.complained is a reputation signal rather than a delivery
            // outcome — neither should overwrite a delivery status.
            default -> null;
        };
        if (newStatus == null) {
            return;
        }
        otpRepository.findByProviderMessageId(messageId).ifPresent(entry -> {
            entry.setDeliveryStatus(newStatus);
            otpRepository.save(entry);
        });
    }

    private static boolean withinTolerance(String timestamp) {
        try {
            long sent = Long.parseLong(timestamp.trim());
            long skew = Math.abs(Instant.now().getEpochSecond() - sent);
            return skew <= TOLERANCE_SECONDS;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * Svix signature check.
     *
     * <p>The header carries a space-separated list of versioned signatures
     * ({@code v1,<base64> v1,<base64>}) because a secret being rotated is
     * signed with both. Any one matching is a pass — which is what makes
     * rotation possible without dropping events.
     */
    private boolean verify(String secret, String signedContent, String header) {
        try {
            byte[] key = Base64.getDecoder().decode(
                    secret.startsWith(SECRET_PREFIX)
                            ? secret.substring(SECRET_PREFIX.length()) : secret);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] expected = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));

            for (String part : header.split(" ")) {
                int comma = part.indexOf(',');
                if (comma < 0 || !"v1".equals(part.substring(0, comma))) {
                    continue;
                }
                byte[] candidate;
                try {
                    candidate = Base64.getDecoder().decode(part.substring(comma + 1));
                } catch (IllegalArgumentException malformed) {
                    continue;
                }
                // Constant-time: a length-aware early return would leak how much
                // of a forged signature was correct.
                if (MessageDigest.isEqual(expected, candidate)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            log.warn("Resend webhook signature verification error: {}", ex.getMessage());
            return false;
        }
    }
}
