package com.intertec.autoops.auth.service;

import com.intertec.autoops.auth.config.AuthProperties;
import com.intertec.autoops.auth.domain.AuditEventType;
import com.intertec.autoops.auth.domain.OtpEntry;
import com.intertec.autoops.auth.exception.AuthException;
import com.intertec.autoops.auth.repo.OtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * OTP lifecycle: SecureRandom generation, SHA-256 hashing (plaintext is never
 * stored or logged), constant-time verification, attempt counting, and
 * lockout. Delivery happens asynchronously via {@link OtpEmailEvent}.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpRepository otpRepository;
    private final AuthProperties properties;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    /** True only under the dev profile; enables printing the plaintext OTP to the log. */
    private final boolean devMode;

    public OtpService(OtpRepository otpRepository,
                      AuthProperties properties,
                      AuditService auditService,
                      ApplicationEventPublisher eventPublisher,
                      Environment environment) {
        this.otpRepository = otpRepository;
        this.properties = properties;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.devMode = environment.acceptsProfiles(Profiles.of("dev"));
    }

    /** Generates and stores a hashed OTP, then queues delivery (AFTER_COMMIT). */
    @Transactional
    public void generate(String email, String tenantId, String ipAddress) {
        // Single active code per account: supersede any outstanding entry so
        // repeated /generate calls can't mint fresh entries to dodge the
        // per-OTP lockout (the per-account verify limiter is the hard cap).
        otpRepository.findTopByEmailAndTenantIdAndConsumedAtIsNullOrderByCreatedAtDesc(
                        normalize(email), tenantId)
                .ifPresent(previous -> {
                    previous.setConsumedAt(Instant.now());
                    otpRepository.save(previous);
                });

        String otp = randomOtp(properties.getOtp().getLength());
        String otpHash = sha256Hex(otp);

        OtpEntry entry = new OtpEntry();
        entry.setEmail(normalize(email));
        entry.setTenantId(tenantId);
        entry.setOtpHash(otpHash);
        entry.setAttempts(0);
        entry.setMaxAttempts(properties.getOtp().getMaxAttempts());
        entry.setExpiresAt(Instant.now().plus(properties.getOtp().getTtl()));
        otpRepository.save(entry);

        // Dev convenience: Resend isn't wired locally, so print the code to the
        // console. Guarded by the dev profile — never active in prod.
        if (devMode) {
            log.warn("[DEV ONLY] OTP for {} (tenant {}) = {}", normalize(email), tenantId, otp);
        }

        // Delivered by ResendEmailService after this transaction commits.
        eventPublisher.publishEvent(new OtpEmailEvent(entry.getId(), normalize(email), otp,
                tenantId, ipAddress));
    }

    /**
     * Verifies and consumes the latest outstanding OTP or throws AuthException.
     *
     * <p>noRollbackFor: the mismatch path increments {@code attempts} (and sets
     * {@code lockedAt} at the cap) and THEN throws. Without it the exception
     * would roll the increment back and the lockout could never engage. Every
     * other throw in this method happens before any write.
     */
    @Transactional(noRollbackFor = AuthException.class)
    public void verify(String email, String tenantId, String otp, String ipAddress) {
        OtpEntry entry = otpRepository
                .findTopByEmailAndTenantIdAndConsumedAtIsNullOrderByCreatedAtDesc(
                        normalize(email), tenantId)
                .orElseThrow(() -> AuthException.unauthorized("otp_invalid",
                        "Invalid or expired code"));

        if (entry.getLockedAt() != null) {
            throw AuthException.unauthorized("otp_locked",
                    "Too many incorrect attempts. Request a new code.");
        }
        if (Instant.now().isAfter(entry.getExpiresAt())) {
            throw AuthException.unauthorized("otp_expired", "Code has expired. Request a new one.");
        }

        boolean matches = MessageDigest.isEqual(
                entry.getOtpHash().getBytes(StandardCharsets.UTF_8),
                sha256Hex(otp == null ? "" : otp.trim()).getBytes(StandardCharsets.UTF_8));

        if (!matches) {
            entry.setAttempts(entry.getAttempts() + 1);
            if (entry.getAttempts() >= entry.getMaxAttempts()) {
                entry.setLockedAt(Instant.now());
                auditService.record(AuditEventType.OTP_LOCKOUT, null, normalize(email), tenantId,
                        null, ipAddress, null, "attempts=" + entry.getAttempts());
            }
            otpRepository.save(entry);
            throw AuthException.unauthorized("otp_invalid", "Invalid or expired code");
        }

        entry.setConsumedAt(Instant.now());
        otpRepository.save(entry);
    }

    // ------------------------------------------------------------------

    private String randomOtp(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** SHA-256 hex digest; package-visible so RefreshTokenService can reuse it. */
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
