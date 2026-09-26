package com.intertec.autoops.auth.service;

/**
 * Published by OtpService inside the transaction; consumed by
 * ResendEmailService AFTER_COMMIT so email delivery never holds the DB
 * transaction open. Carries the plaintext OTP in memory only — it is never
 * persisted or logged.
 */
public record OtpEmailEvent(
        Long otpEntryId,
        String email,
        String otp,
        String tenantId,
        String ipAddress) {
}
