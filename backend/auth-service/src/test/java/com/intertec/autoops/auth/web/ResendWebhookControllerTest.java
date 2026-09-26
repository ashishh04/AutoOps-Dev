package com.intertec.autoops.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.auth.config.AuthProperties;
import com.intertec.autoops.auth.domain.OtpDeliveryStatus;
import com.intertec.autoops.auth.domain.OtpEntry;
import com.intertec.autoops.auth.repo.OtpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The webhook is an unauthenticated endpoint that mutates delivery state, so
 * the signature check is the only thing standing between the internet and
 * arbitrary OTP rows. Every one of these tests is about that check.
 */
@ExtendWith(MockitoExtension.class)
class ResendWebhookControllerTest {

    private static final String SECRET_BODY = "c2VjcmV0LWtleS1mb3ItdGVzdGluZy1vbmx5";
    private static final String SECRET = "whsec_" + SECRET_BODY;
    private static final String MESSAGE_ID = "4ef9a417-02e9-4d39-ad75-9611e0fcc33c";

    @Mock
    private OtpRepository otpRepository;

    private ResendWebhookController controller;
    private AuthProperties properties;
    private OtpEntry entry;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        properties.getResend().setWebhookSecret(SECRET);
        controller = new ResendWebhookController(properties, otpRepository, new ObjectMapper());
        entry = new OtpEntry();
        entry.setProviderMessageId(MESSAGE_ID);
        lenient().when(otpRepository.findByProviderMessageId(MESSAGE_ID))
                .thenReturn(Optional.of(entry));
    }

    private static String payload(String type) {
        return "{\"type\":\"" + type + "\",\"data\":{\"email_id\":\"" + MESSAGE_ID + "\"}}";
    }

    /** Signs exactly as Svix does: HMAC-SHA256 over {@code id.timestamp.body}. */
    private static String sign(String id, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET_BODY), "HmacSHA256"));
        byte[] signature = mac.doFinal(
                (id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(signature);
    }

    private static String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private int post(String body, String id, String timestamp, String signature) {
        return controller.handleEvent(body, id, timestamp, signature).getStatusCode().value();
    }

    @Test
    void aValidlySignedDeliveryMovesTheRowToDelivered() throws Exception {
        String body = payload("email.delivered");
        String ts = now();

        assertEquals(200, post(body, "msg_1", ts, sign("msg_1", ts, body)));
        assertEquals(OtpDeliveryStatus.DELIVERED, entry.getDeliveryStatus());
    }

    @Test
    void aBounceMovesTheRowToBounced() throws Exception {
        String body = payload("email.bounced");
        String ts = now();

        assertEquals(200, post(body, "msg_1", ts, sign("msg_1", ts, body)));
        assertEquals(OtpDeliveryStatus.BOUNCED, entry.getDeliveryStatus());
    }

    @Test
    void anUnsignedCallChangesNothing() {
        assertEquals(403, post(payload("email.delivered"), null, null, null));
        verify(otpRepository, never()).save(any());
    }

    @Test
    void aForgedSignatureChangesNothing() {
        String ts = now();
        assertEquals(403, post(payload("email.delivered"), "msg_1", ts,
                "v1," + Base64.getEncoder().encodeToString("not-the-signature".getBytes())));
        verify(otpRepository, never()).save(any());
    }

    @Test
    void aSignatureOverADifferentBodyChangesNothing() throws Exception {
        // The attack this defends against: replay a genuine "delivered" event
        // with the email_id swapped for someone else's.
        String ts = now();
        String signed = sign("msg_1", ts, payload("email.delivered"));

        assertEquals(403, post(payload("email.bounced"), "msg_1", ts, signed));
        verify(otpRepository, never()).save(any());
    }

    @Test
    void anOldTimestampIsRejectedEvenWhenCorrectlySigned() throws Exception {
        // Without a tolerance window a captured request stays replayable
        // forever, because the signature over it never expires.
        String body = payload("email.delivered");
        String stale = String.valueOf(Instant.now().getEpochSecond() - 3600);

        assertEquals(403, post(body, "msg_1", stale, sign("msg_1", stale, body)));
        verify(otpRepository, never()).save(any());
    }

    @Test
    void aSecondSignatureInTheHeaderIsAcceptedSoRotationDoesNotDropEvents() throws Exception {
        String body = payload("email.delivered");
        String ts = now();
        String header = "v1," + Base64.getEncoder().encodeToString("old-secret-sig".getBytes())
                + " " + sign("msg_1", ts, body);

        assertEquals(200, post(body, "msg_1", ts, header));
        assertEquals(OtpDeliveryStatus.DELIVERED, entry.getDeliveryStatus());
    }

    @Test
    void anEventTypeThatIsNotADeliveryOutcomeLeavesTheStatusAlone() throws Exception {
        // A spam complaint means the mail arrived. Overwriting the status with
        // it would report a delivered code as undelivered.
        entry.setDeliveryStatus(OtpDeliveryStatus.DELIVERED);
        String body = payload("email.complained");
        String ts = now();

        assertEquals(200, post(body, "msg_1", ts, sign("msg_1", ts, body)));
        assertEquals(OtpDeliveryStatus.DELIVERED, entry.getDeliveryStatus());
        verify(otpRepository, never()).save(any());
    }

    @Test
    void withNoSecretConfiguredTheWebhookIsClosed() throws Exception {
        properties.getResend().setWebhookSecret("");
        String body = payload("email.delivered");
        String ts = now();

        // Correctly signed for the secret we know, and still refused: a
        // deployment that has not configured the webhook must not accept one.
        assertEquals(403, post(body, "msg_1", ts, sign("msg_1", ts, body)));
        verify(otpRepository, never()).save(any());
    }
}
