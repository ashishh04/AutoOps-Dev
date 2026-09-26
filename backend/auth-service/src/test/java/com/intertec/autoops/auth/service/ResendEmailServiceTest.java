package com.intertec.autoops.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.auth.config.AuthProperties;
import com.intertec.autoops.auth.domain.AuditEventType;
import com.intertec.autoops.auth.domain.OtpDeliveryStatus;
import com.intertec.autoops.auth.domain.OtpEntry;
import com.intertec.autoops.auth.repo.OtpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResendEmailServiceTest {

    @Mock
    private ResendClient client;
    @Mock
    private OtpRepository otpRepository;
    @Mock
    private AuditService auditService;

    private ResendEmailService service;
    private AuthProperties properties;
    private OtpEntry entry;

    private static final OtpEmailEvent EVENT =
            new OtpEmailEvent(1L, "a@example.com", "123456", "tenant-a", "1.2.3.4");

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        service = new ResendEmailService(client, otpRepository, properties, auditService);
        entry = new OtpEntry();
        entry.setId(1L);
    }

    private void otpRowExists() {
        when(otpRepository.findById(1L)).thenReturn(Optional.of(entry));
    }

    private static ResendClient.Result result(int status, String body) {
        return new ResendClient.Result(status, body);
    }

    @Test
    void acceptedSendIsMarkedSentWithTheProviderId() throws Exception {
        otpRowExists();
        when(client.send(anyString()))
                .thenReturn(result(200, "{\"id\":\"4ef9a417-02e9-4d39-ad75-9611e0fcc33c\"}"));

        service.onOtpGenerated(EVENT);

        assertEquals(OtpDeliveryStatus.SENT, entry.getDeliveryStatus());
        assertEquals("4ef9a417-02e9-4d39-ad75-9611e0fcc33c", entry.getProviderMessageId());
        verify(auditService).record(eq(AuditEventType.OTP_SENT), any(), eq("a@example.com"),
                eq("tenant-a"), any(), any(), any(), any());
    }

    @Test
    void retryThatSucceedsWithoutAParseableIdIsStillSent() throws Exception {
        // Regression carried over from the previous provider: success was once
        // inferred from (id != null || lastError == null). A 5xx first attempt
        // followed by a 2xx with no usable id satisfied neither, so a DELIVERED
        // code was marked FAILED and the user was told delivery had failed.
        otpRowExists();
        when(client.send(anyString()))
                .thenReturn(result(503, "{\"message\":\"temporarily unavailable\"}"))
                .thenReturn(result(200, ""));

        service.onOtpGenerated(EVENT);

        assertEquals(OtpDeliveryStatus.SENT, entry.getDeliveryStatus());
        assertNull(entry.getProviderMessageId());
        verify(client, times(2)).send(anyString());
    }

    @Test
    void aRejectionFailsClosedAndRecordsTheProvidersOwnReason() throws Exception {
        otpRowExists();
        // The shape Resend actually returns for the mistake people make first.
        when(client.send(anyString())).thenReturn(result(403,
                "{\"statusCode\":403,\"message\":\"The example.com domain is not verified\"}"));

        service.onOtpGenerated(EVENT);

        assertEquals(OtpDeliveryStatus.FAILED, entry.getDeliveryStatus());
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditEventType.OTP_DELIVERY_FAILED), any(),
                eq("a@example.com"), eq("tenant-a"), any(), any(), any(), reason.capture());
        assertTrue(reason.getValue().contains("domain is not verified"),
                "the audit row must carry the provider's reason, not a bare status");
    }

    @Test
    void aConfigurationRejectionIsNotRetried() throws Exception {
        otpRowExists();
        when(client.send(anyString())).thenReturn(result(401, "{\"message\":\"Invalid API key\"}"));

        service.onOtpGenerated(EVENT);

        // An identical immediate retry on a bad key cannot succeed; it only
        // burns quota, and Resend allows 2 requests per second.
        verify(client, times(1)).send(anyString());
        assertEquals(OtpDeliveryStatus.FAILED, entry.getDeliveryStatus());
    }

    @Test
    void rateLimitingIsRetried() throws Exception {
        otpRowExists();
        when(client.send(anyString()))
                .thenReturn(result(429, "{\"message\":\"Too many requests\"}"))
                .thenReturn(result(200, "{\"id\":\"abc\"}"));

        service.onOtpGenerated(EVENT);

        verify(client, times(2)).send(anyString());
        assertEquals(OtpDeliveryStatus.SENT, entry.getDeliveryStatus());
    }

    @Test
    void transportFailureFailsClosed() throws Exception {
        otpRowExists();
        when(client.send(anyString())).thenThrow(new java.net.SocketTimeoutException("timed out"));

        service.onOtpGenerated(EVENT);

        assertEquals(OtpDeliveryStatus.FAILED, entry.getDeliveryStatus());
        verify(auditService).record(eq(AuditEventType.OTP_DELIVERY_FAILED), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void thePayloadCarriesTheConfiguredSenderAndTheCode() throws Exception {
        otpRowExists();
        properties.getResend().setFromEmail("no-reply@autoops.io");
        properties.getResend().setFromName("AutoOps");
        when(client.send(anyString())).thenReturn(result(200, "{\"id\":\"x\"}"));

        service.onOtpGenerated(EVENT);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(client).send(payload.capture());
        var json = new ObjectMapper().readTree(payload.getValue());
        assertEquals("AutoOps <no-reply@autoops.io>", json.get("from").asText());
        assertEquals("a@example.com", json.get("to").get(0).asText());
        assertTrue(json.get("subject").asText().toLowerCase().contains("verification code"));
    }

    @Test
    void bothAnHtmlAndATextPartAreSent() throws Exception {
        // Resend treats them as alternatives of one message. HTML-only scores
        // worse with spam filters and is unreadable in a terminal client.
        otpRowExists();
        when(client.send(anyString())).thenReturn(result(200, "{\"id\":\"x\"}"));

        service.onOtpGenerated(EVENT);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(client).send(payload.capture());
        var json = new ObjectMapper().readTree(payload.getValue());
        assertTrue(json.get("html").asText().contains("<!doctype html>"));
        assertTrue(json.get("html").asText().contains("123456"));
        assertTrue(json.get("text").asText().contains("123456"));
    }

    @Test
    void theInviteLinksToTheConfiguredConsole() throws Exception {
        properties.setAppBaseUrl("https://app.example.com");
        when(client.send(anyString())).thenReturn(result(200, "{\"id\":\"x\"}"));

        service.sendInvite("new@example.com", "Acme");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(client).send(payload.capture());
        var json = new ObjectMapper().readTree(payload.getValue());
        assertTrue(json.get("html").asText().contains("https://app.example.com/login"));
        assertEquals("You've been added to Acme on AutoOps", json.get("subject").asText());
    }

    @Test
    void anInviteFailureNeverThrows() throws Exception {
        // Onboarding must not break because email did. The member can still
        // sign in with a one-time code.
        when(client.send(anyString())).thenThrow(new IllegalStateException("boom"));

        service.sendInvite("new@example.com", "Acme");

        verify(otpRepository, never()).save(any());
    }
}
