package com.intertec.autoops.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intertec.autoops.auth.config.AuthProperties;
import com.intertec.autoops.auth.domain.AuditEventType;
import com.intertec.autoops.auth.domain.OtpDeliveryStatus;
import com.intertec.autoops.auth.repo.OtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the OTP email through Resend AFTER the generating transaction commits,
 * so a code that was rolled back is never emailed.
 *
 * <p>One retry on a transient failure; then FAIL-CLOSED:
 * {@code delivery_status=FAILED} plus an {@code OTP_DELIVERY_FAILED} audit
 * event. On acceptance the id from the response body is stored, so the Resend
 * webhook can later move the row to DELIVERED or BOUNCED.
 *
 * <p><b>The email bodies live in the repository, not in a provider template.</b>
 * Resend has no server-side dynamic templates — the previous provider's
 * template id has no equivalent — which is a small improvement: what a customer
 * receives is now reviewable in a diff rather than edited in a vendor console
 * by whoever last had the login. The markup is in
 * {@code resources/email/}; see {@link EmailTemplates}.
 */
@Service
public class ResendEmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);
    private static final int MAX_SEND_ATTEMPTS = 2; // initial attempt + one retry

    private final ResendClient client;
    private final OtpRepository otpRepository;
    private final AuthProperties properties;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResendEmailService(ResendClient client,
                              OtpRepository otpRepository,
                              AuthProperties properties,
                              AuditService auditService) {
        this.client = client;
        this.otpRepository = otpRepository;
        this.properties = properties;
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOtpGenerated(OtpEmailEvent event) {
        // Acceptance is tracked explicitly rather than inferred from the id or
        // from lastError: a 2xx can arrive without a parseable id, and a retry
        // that succeeds after a failed first attempt leaves lastError set.
        // Inferring from those two marked a DELIVERED code FAILED.
        boolean accepted = false;
        String messageId = null;
        String lastError = null;

        EmailTemplates.Rendered email = EmailTemplates.otpCode(
                event.otp(), properties.getOtp().getTtl().toMinutes());
        String payload = body(event.email(), email);

        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            try {
                ResendClient.Result result = client.send(payload);
                if (result.accepted()) {
                    accepted = true;
                    messageId = extractId(result.body());
                    break;
                }
                // The BODY carries Resend's actual reason — an unverified from
                // address, an invalid key, a domain that is not yet verified.
                // Logging the bare status turns every misconfiguration into an
                // unexplained HTTP 403.
                lastError = "HTTP " + result.status() + " " + describe(result.body());
                log.warn("Resend rejected OTP email (attempt {}/{}): {}",
                        attempt, MAX_SEND_ATTEMPTS, lastError);
                if (!retryable(result.status())) {
                    // 4xx is a configuration or credential problem: an immediate
                    // identical retry cannot succeed and just burns quota.
                    break;
                }
            } catch (Exception ex) {
                lastError = ex.getMessage();
                log.warn("Resend call failed (attempt {}/{}): {}",
                        attempt, MAX_SEND_ATTEMPTS, lastError);
            }
        }

        if (accepted) {
            markSent(event, messageId);
        } else {
            markFailed(event, lastError == null ? "no response" : lastError);
        }
    }

    /**
     * 429 and 5xx are transient; every other rejection needs a config change.
     *
     * <p>429 matters more here than it did with the previous provider: Resend
     * rate-limits at 2 requests per second, and a burst of sign-ins can reach
     * it. One retry is the whole budget, so a sustained burst still fails
     * closed rather than queueing.
     */
    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    /** Response body, collapsed and clipped to fit auth_audit_log.detail. */
    private static String describe(String body) {
        if (body == null || body.isBlank()) {
            return "(no response body)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 500 ? flat.substring(0, 500) + "…" : flat;
    }

    /** Resend answers {@code {"id":"..."}}; a missing id is not a failure. */
    private String extractId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            String id = objectMapper.readTree(body).path("id").asText(null);
            return id == null || id.isBlank() ? null : id;
        } catch (Exception ex) {
            log.debug("Resend accepted the send but the body was unreadable: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Best-effort team invitation ("sign in with a one-time code") — an email
     * failure must never break onboarding; the member can still log in.
     */
    public void sendInvite(String email, String workspaceName) {
        try {
            ResendClient.Result result = client.send(body(email,
                    EmailTemplates.workspaceInvite(workspaceName, properties.getAppBaseUrl())));
            if (!result.accepted()) {
                log.warn("Resend rejected invite email: HTTP {} {}",
                        result.status(), describe(result.body()));
            }
        } catch (Exception ex) {
            log.warn("Invite email failed: {}", ex.getMessage());
        }
    }

    /**
     * Built with Jackson rather than concatenated: a subject carries a
     * customer-chosen workspace name, and an unescaped quote in it would
     * produce a malformed request rather than a malformed email.
     *
     * <p>Both parts are always sent. Resend treats {@code html} and
     * {@code text} as alternatives of one message, so the recipient's client
     * picks — and a text part is what keeps the message readable in a terminal
     * client and scoring well with spam filters.
     */
    private String body(String to, EmailTemplates.Rendered email) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("from", properties.getResend().sender());
        node.putArray("to").add(to);
        node.put("subject", email.subject());
        node.put("html", email.html());
        node.put("text", email.text());
        return node.toString();
    }

    private void markSent(OtpEmailEvent event, String messageId) {
        otpRepository.findById(event.otpEntryId()).ifPresent(entry -> {
            entry.setDeliveryStatus(OtpDeliveryStatus.SENT);
            entry.setProviderMessageId(messageId);
            otpRepository.save(entry);
        });
        auditService.record(AuditEventType.OTP_SENT, null, event.email(), event.tenantId(),
                null, event.ipAddress(), null, null);
    }

    private void markFailed(OtpEmailEvent event, String reason) {
        otpRepository.findById(event.otpEntryId()).ifPresent(entry -> {
            entry.setDeliveryStatus(OtpDeliveryStatus.FAILED);
            otpRepository.save(entry);
        });
        auditService.record(AuditEventType.OTP_DELIVERY_FAILED, null, event.email(),
                event.tenantId(), null, event.ipAddress(), null, reason);
    }
}
