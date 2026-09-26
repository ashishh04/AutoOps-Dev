package com.intertec.autoops.auth.facade;

import com.intertec.autoops.auth.client.SubscriptionServiceClient;
import com.intertec.autoops.auth.config.AuthProperties;
import com.intertec.autoops.auth.domain.AuditEventType;
import com.intertec.autoops.auth.domain.User;
import com.intertec.autoops.auth.domain.UserRole;
import com.intertec.autoops.auth.domain.UserStatus;
import com.intertec.autoops.auth.exception.AuthException;
import com.intertec.autoops.auth.domain.TenantIdpConfig;
import com.intertec.autoops.auth.service.AuditService;
import com.intertec.autoops.auth.service.EnterpriseSsoService;
import com.intertec.autoops.auth.service.FreeEmailDomains;
import com.intertec.autoops.auth.service.JwtService;
import com.intertec.autoops.auth.service.KeycloakAdminService;
import com.intertec.autoops.auth.service.OtpService;
import com.intertec.autoops.auth.service.RateLimitService;
import com.intertec.autoops.auth.service.RefreshTokenService;
import com.intertec.autoops.auth.service.ResendEmailService;
import com.intertec.autoops.auth.service.SocialOidcService;
import com.intertec.autoops.auth.service.UserService;
import com.intertec.autoops.auth.service.WorkspaceService;
import com.intertec.autoops.auth.web.dto.AuthorizeRequest;
import com.intertec.autoops.auth.web.dto.AuthorizeResponse;
import com.intertec.autoops.auth.web.dto.OnboardRequest;
import com.intertec.autoops.auth.web.dto.TokenResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Per-use-case orchestration: OTP login, refresh rotation, logout, authorize
 * (token + entitlement), SSO, onboarding/offboarding. Controllers stay thin;
 * services stay single-purpose.
 */
@Component
public class AuthFacade {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RateLimitService rateLimitService;
    private final OtpService otpService;
    private final UserService userService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final KeycloakAdminService keycloakAdminService;
    private final SubscriptionServiceClient subscriptionServiceClient;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final ResendEmailService resendEmailService;
    private final SocialOidcService socialOidcService;
    private final EnterpriseSsoService enterpriseSsoService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    /**
     * BCrypt hash of a random throwaway value, compared against when the login
     * email does not resolve to an account — so the unknown-email path costs
     * the same as a real password check (no user-enumeration timing oracle).
     */
    private final String dummyPasswordHash;

    public AuthFacade(RateLimitService rateLimitService,
                      OtpService otpService,
                      UserService userService,
                      JwtService jwtService,
                      RefreshTokenService refreshTokenService,
                      KeycloakAdminService keycloakAdminService,
                      SubscriptionServiceClient subscriptionServiceClient,
                      AuditService auditService,
                      WorkspaceService workspaceService,
                      ResendEmailService resendEmailService,
                      SocialOidcService socialOidcService,
                      EnterpriseSsoService enterpriseSsoService,
                      PasswordEncoder passwordEncoder,
                      AuthProperties properties) {
        this.rateLimitService = rateLimitService;
        this.otpService = otpService;
        this.userService = userService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.keycloakAdminService = keycloakAdminService;
        this.subscriptionServiceClient = subscriptionServiceClient;
        this.auditService = auditService;
        this.workspaceService = workspaceService;
        this.resendEmailService = resendEmailService;
        this.socialOidcService = socialOidcService;
        this.enterpriseSsoService = enterpriseSsoService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // ------------------------------------------------------------------
    // Password register / login
    // ------------------------------------------------------------------

    /**
     * Self-service sign-up creates a brand-new tenant (workspace) with the
     * registrant as its (PENDING) ADMIN and emails a verification code. It
     * deliberately ignores X-Tenant-ID: honoring a client-supplied tenant here
     * would let anyone register themselves as an admin of an existing
     * workspace. No tokens are issued until the email is verified.
     *
     * @return the normalized email the verification code was sent to
     */
    public String register(String email, String password, String fullName,
                           String workspaceName, String ipAddress, String userAgent) {
        if (!rateLimitService.allowRegistration(ipAddress)) {
            auditService.record(AuditEventType.RATE_LIMITED, null, email, null, null, ipAddress,
                    userAgent, "register");
            throw AuthException.tooManyRequests("rate_limited",
                    "Too many sign-ups. Please wait and try again.");
        }
        // Serialize concurrent sign-ups for the same email (check-then-insert).
        if (!rateLimitService.tryRegisterLock(email)) {
            throw AuthException.conflict("registration_in_progress",
                    "A registration for this email is already in progress");
        }
        // Globally unique email for self-registration keeps password/OTP login
        // resolvable without a tenant header (see UserService.requireActiveByEmail).
        if (userService.emailExists(email)) {
            throw AuthException.conflict("user_exists",
                    "An account with this email already exists");
        }
        // One organization per corporate email domain: fast feedback here,
        // enforced for real at the verify step (claim requires a VERIFIED email).
        String domain = FreeEmailDomains.corporateDomain(email);
        if (domain != null && workspaceService.domainClaimed(domain)) {
            throw WorkspaceService.companyExists(domain);
        }
        String tenantId = newTenantId(workspaceName, email);
        User user = userService.createWithPassword(email, fullName,
                passwordEncoder.encode(password), UserRole.ADMIN, tenantId);
        // Keep the human-readable name the user typed — the slug is lossy.
        workspaceService.record(tenantId, workspaceName);
        otpService.generate(user.getEmail(), user.getTenantId(), ipAddress);
        auditService.record(AuditEventType.USER_ONBOARDED, user.getId(), user.getEmail(),
                tenantId, null, ipAddress, userAgent, "self-register (pending verification)");
        return user.getEmail();
    }

    /** Confirms the emailed code, activates the PENDING account, and signs in. */
    public TokenResponse verifyRegistration(String email, String otp, String tenantHint,
                                            String deviceId, String ipAddress, String userAgent) {
        if (!rateLimitService.allowVerifyAttempt(email, ipAddress)) {
            auditService.record(AuditEventType.RATE_LIMITED, null, email, tenantHint, null,
                    ipAddress, userAgent, "register/verify");
            throw AuthException.tooManyRequests("rate_limited",
                    "Too many attempts. Please wait and try again.");
        }
        try {
            User user = userService.requireByEmail(email, tenantHint);
            otpService.verify(email, user.getTenantId(), otp, ipAddress);
            if (user.getStatus() == UserStatus.PENDING) {
                // Email now VERIFIED: claim the corporate domain BEFORE
                // activating — a raced duplicate stays PENDING with a clear
                // company_exists instead of getting a workspace.
                workspaceService.claimDomain(user.getTenantId(), user.getEmail());
                user = userService.activate(user.getId());
                auditService.record(AuditEventType.EMAIL_VERIFIED, user.getId(), user.getEmail(),
                        user.getTenantId(), null, ipAddress, userAgent, null);
            } else if (user.getStatus() != UserStatus.ACTIVE) {
                throw AuthException.forbidden("user_not_active", "Account is not active");
            }
            return issueTokens(user, deviceId, ipAddress, userAgent, AuditEventType.LOGIN_SUCCESS);
        } catch (AuthException ex) {
            auditService.record(AuditEventType.LOGIN_FAILURE, null, email, tenantHint, null,
                    ipAddress, userAgent, ex.getError());
            if ("company_exists".equals(ex.getError())) {
                // Safe to surface past the anti-enumeration catch: the OTP was
                // valid, so the caller has proven they own this email.
                throw ex;
            }
            throw AuthException.unauthorized("verification_failed", "Invalid or expired code");
        }
    }

    /** Re-sends the verification code for a PENDING account. Always neutral 200. */
    public void resendRegistrationOtp(String email, String tenantHint, String ipAddress,
                                      String userAgent) {
        if (!rateLimitService.allowOtpRequest(email, ipAddress)) {
            auditService.record(AuditEventType.RATE_LIMITED, null, email, tenantHint, null,
                    ipAddress, userAgent, "register/resend");
            throw AuthException.tooManyRequests("rate_limited",
                    "Too many requests. Please wait and try again.");
        }
        try {
            User user = userService.requireByEmail(email, tenantHint);
            if (user.getStatus() == UserStatus.PENDING) {
                otpService.generate(user.getEmail(), user.getTenantId(), ipAddress);
                auditService.record(AuditEventType.OTP_REQUESTED, user.getId(), email,
                        user.getTenantId(), null, ipAddress, userAgent, "register-resend");
            }
        } catch (AuthException ex) {
            // Anti-enumeration: unknown emails get the same neutral response.
            auditService.record(AuditEventType.LOGIN_FAILURE, null, email, tenantHint, null,
                    ipAddress, userAgent, ex.getError());
        }
    }

    public TokenResponse passwordLogin(String email, String password, String tenantId,
                                       String deviceId, String ipAddress, String userAgent) {
        if (!rateLimitService.allowPasswordLogin(email, ipAddress)) {
            auditService.record(AuditEventType.RATE_LIMITED, null, email, tenantId, null,
                    ipAddress, userAgent, "login");
            throw AuthException.tooManyRequests("rate_limited",
                    "Too many attempts. Please wait and try again.");
        }
        User user;
        try {
            user = userService.requireByEmail(email, tenantId);
        } catch (AuthException ex) {
            // Burn a BCrypt comparison so this path is not measurably faster
            // than a wrong password (anti-enumeration), then fail uniformly:
            // unknown email / disabled / wrong password all look the same to
            // the caller (the reason lives only in the audit log).
            passwordEncoder.matches(password, dummyPasswordHash);
            auditService.record(AuditEventType.LOGIN_FAILURE, null, email, tenantId, null,
                    ipAddress, userAgent, ex.getError());
            throw AuthException.unauthorized("login_failed", "Invalid email or password");
        }
        // OTP-only/SSO accounts have no hash; compare against the dummy so the
        // rejection is not measurably faster than a wrong password.
        boolean matches = passwordEncoder.matches(password,
                user.getPasswordHash() != null ? user.getPasswordHash() : dummyPasswordHash);
        if (user.getPasswordHash() == null || !matches) {
            auditService.record(AuditEventType.LOGIN_FAILURE, user.getId(), email, tenantId, null,
                    ipAddress, userAgent, "bad_password");
            throw AuthException.unauthorized("login_failed", "Invalid email or password");
        }
        // Only a caller who knows the correct password learns the account is
        // unverified/disabled — enumeration stays closed for everyone else.
        if (user.getStatus() == UserStatus.PENDING) {
            auditService.record(AuditEventType.LOGIN_FAILURE, user.getId(), email, tenantId, null,
                    ipAddress, userAgent, "email_unverified");
            throw AuthException.forbidden("email_unverified",
                    "Please verify your email address to continue");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            auditService.record(AuditEventType.LOGIN_FAILURE, user.getId(), email, tenantId, null,
                    ipAddress, userAgent, "user_not_active");
            throw AuthException.unauthorized("login_failed", "Invalid email or password");
        }
        requireSsoNotEnforced(user);
        return issueTokens(user, deviceId, ipAddress, userAgent, AuditEventType.LOGIN_SUCCESS);
    }

    // ------------------------------------------------------------------
    // Password reset (email-code) / change
    // ------------------------------------------------------------------

    /** Emails a reset code to an ACTIVE account. Always a neutral 200. */
    public void forgotPassword(String email, String tenantHint, String ipAddress,
                               String userAgent) {
        if (!rateLimitService.allowOtpRequest(email, ipAddress)) {
            auditService.record(AuditEventType.RATE_LIMITED, null, email, tenantHint, null,
                    ipAddress, userAgent, "password/forgot");
            throw AuthException.tooManyRequests("rate_limited",
                    "Too many requests. Please wait and try again.");
        }
        try {
            User user = userService.requireActiveByEmail(email, tenantHint);
            otpService.generate(user.getEmail(), user.getTenantId(), ipAddress);
            auditService.record(AuditEventType.OTP_REQUESTED, user.getId(), email,
                    user.getTenantId(), null, ipAddress, userAgent, "password-forgot");
        } catch (AuthException ex) {
            // Anti-enumeration: unknown emails get the same neutral response.
            auditService.record(AuditEventType.LOGIN_FAILURE, null, email, tenantHint, null,
                    ipAddress, userAgent, ex.getError());
        }
    }

    /**
     * Verifies the emailed code, sets the new password, kills every existing
     * session and access token (token_version bump + session revocation), and
     * signs the user in with a fresh pair.
     */
    public TokenResponse resetPassword(String email, String otp, String newPassword,
                                       String tenantHint, String deviceId, String ipAddress,
                                       String userAgent) {
        if (!rateLimitService.allowVerifyAttempt(email, ipAddress)) {
            auditService.record(AuditEventType.RATE_LIMITED, null, email, tenantHint, null,
                    ipAddress, userAgent, "password/reset");
            throw AuthException.tooManyRequests("rate_limited",
                    "Too many attempts. Please wait and try again.");
        }
        User user;
        try {
            user = userService.requireActiveByEmail(email, tenantHint);
            otpService.verify(email, user.getTenantId(), otp, ipAddress);
        } catch (AuthException ex) {
            auditService.record(AuditEventType.LOGIN_FAILURE, null, email, tenantHint, null,
                    ipAddress, userAgent, ex.getError());
            throw AuthException.unauthorized("reset_failed", "Invalid or expired code");
        }
        user = userService.replacePassword(user.getId(), passwordEncoder.encode(newPassword));
        int revoked = refreshTokenService.revokeAllForUser(user.getId());
        auditService.record(AuditEventType.PASSWORD_RESET, user.getId(), user.getEmail(),
                user.getTenantId(), null, ipAddress, userAgent, "sessionsRevoked=" + revoked);
        return issueTokens(user, deviceId, ipAddress, userAgent, AuditEventType.LOGIN_SUCCESS);
    }

    /**
     * Authenticated password change: proves the current password, replaces it,
     * kills every other session/token, and returns a fresh pair.
     */
    public TokenResponse changePassword(Jwt jwt, String currentPassword, String newPassword,
                                        String deviceId, String ipAddress, String userAgent) {
        Long userId = ((Number) jwt.getClaim("userId")).longValue();
        User user = userService.requireById(userId);
        if (user.getPasswordHash() == null) {
            throw AuthException.badRequest("no_password_set",
                    "This account has no password; use the password reset flow");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            auditService.record(AuditEventType.LOGIN_FAILURE, userId, user.getEmail(),
                    user.getTenantId(), null, ipAddress, userAgent, "bad_current_password");
            throw AuthException.unauthorized("invalid_password", "Current password is incorrect");
        }
        user = userService.replacePassword(userId, passwordEncoder.encode(newPassword));
        int revoked = refreshTokenService.revokeAllForUser(userId);
        auditService.record(AuditEventType.PASSWORD_CHANGED, userId, user.getEmail(),
                user.getTenantId(), null, ipAddress, userAgent, "sessionsRevoked=" + revoked);
        return issueTokens(user, deviceId, ipAddress, userAgent, AuditEventType.LOGIN_SUCCESS);
    }

    // ------------------------------------------------------------------
    // OTP login
    // ------------------------------------------------------------------

    public void initiateOtpLogin(String email, String tenantId, String ipAddress, String userAgent) {
        if (!rateLimitService.allowOtpRequest(email, ipAddress)) {
            auditService.record(AuditEventType.RATE_LIMITED, null, email, tenantId, null, ipAddress,
                    userAgent, "otp/generate");
            throw AuthException.tooManyRequests("rate_limited",
                    "Too many OTP requests. Please wait and try again.");
        }
        User user;
        try {
            // Resolved BEFORE any code is minted, so an unknown address never
            // reaches the mail provider — no email is spent either way.
            user = userService.requireActiveByEmail(email, tenantId);
        } catch (AuthException ex) {
            auditService.record(AuditEventType.LOGIN_FAILURE, null, email, tenantId, null,
                    ipAddress, userAgent, ex.getError());
            if (properties.getOtp().isRevealUnknownAccount()) {
                // Tell the caller the account does not exist (user_not_found /
                // user_not_active) so they can sign up instead of waiting for a
                // code that will never arrive. Opens user enumeration by
                // design — see AuthProperties.Otp#revealUnknownAccount.
                throw ex;
            }
            // Anti-enumeration: never reveal whether the account exists. The
            // controller returns the same neutral 200 either way.
            return;
        }
        // Key the OTP by the account's own tenant (self-registered users live
        // in their per-workspace tenant, not the header/default one).
        otpService.generate(user.getEmail(), user.getTenantId(), ipAddress);
        auditService.record(AuditEventType.OTP_REQUESTED, user.getId(), email, user.getTenantId(),
                null, ipAddress, userAgent, null);
    }

    public TokenResponse verifyOtpAndIssueTokens(String email, String otp, String tenantId,
                                                 String deviceId, String ipAddress, String userAgent) {
        if (!rateLimitService.allowVerifyAttempt(email, ipAddress)) {
            auditService.record(AuditEventType.RATE_LIMITED, null, email, tenantId, null, ipAddress,
                    userAgent, "otp/verify");
            throw AuthException.tooManyRequests("rate_limited",
                    "Too many attempts. Please wait and try again.");
        }
        try {
            // Resolve the account first so the OTP is looked up in ITS tenant;
            // the uniform catch below keeps the failure surface identical
            // whether or not the account exists (anti-enumeration).
            User user = userService.requireActiveByEmail(email, tenantId);
            otpService.verify(email, user.getTenantId(), otp, ipAddress);
            requireSsoNotEnforced(user);
            return issueTokens(user, deviceId, ipAddress, userAgent, AuditEventType.LOGIN_SUCCESS);
        } catch (AuthException ex) {
            auditService.record(AuditEventType.LOGIN_FAILURE, null, email, tenantId, null, ipAddress,
                    userAgent, ex.getError());
            if ("sso_required".equals(ex.getError())) {
                // Not masked: the OTP was VALID at this point, so the caller
                // proved account ownership — no enumeration risk, and the
                // frontend needs the code to route to the company IdP.
                throw ex;
            }
            // Uniform error: otp_invalid / user_not_found / otp_locked are
            // indistinguishable to the caller (details live in the audit log).
            throw AuthException.unauthorized("login_failed", "Invalid or expired code");
        }
    }

    // ------------------------------------------------------------------
    // Refresh / logout
    // ------------------------------------------------------------------

    public TokenResponse refresh(String refreshToken, String ipAddress, String userAgent) {
        RefreshTokenService.RotationResult result =
                refreshTokenService.rotate(refreshToken, ipAddress, userAgent);
        String accessToken = jwtService.mintAccessToken(result.user());
        return new TokenResponse(accessToken, result.newRefreshToken(), "Bearer",
                jwtService.accessTokenTtlSeconds());
    }

    public void logout(String refreshToken, String ipAddress, String userAgent) {
        refreshTokenService.revokeByToken(refreshToken, ipAddress, userAgent);
    }

    public int logoutAll(Jwt jwt, String ipAddress, String userAgent) {
        Long userId = ((Number) jwt.getClaim("userId")).longValue();
        User user = userService.bumpTokenVersion(userId);
        int revoked = refreshTokenService.revokeAllForUser(userId);
        auditService.record(AuditEventType.LOGOUT_ALL, userId, user.getEmail(), user.getTenantId(),
                null, ipAddress, userAgent, "sessionsRevoked=" + revoked);
        return revoked;
    }

    // ------------------------------------------------------------------
    // Authorize: token validation + linked entitlement check
    // ------------------------------------------------------------------

    public AuthorizeResponse authorize(AuthorizeRequest request, String tenantHeader) {
        Jwt jwt;
        try {
            jwt = jwtService.validateAccessToken(request.token());
        } catch (AuthException ex) {
            return new AuthorizeResponse(false, ex.getMessage(), Map.of());
        }

        Long userId = ((Number) jwt.getClaim("userId")).longValue();
        User user;
        try {
            user = userService.requireById(userId);
        } catch (AuthException ex) {
            return new AuthorizeResponse(false, "Unknown user", Map.of());
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            return new AuthorizeResponse(false, "Account is not active", Map.of());
        }
        Number ver = jwt.getClaim("ver");
        if (ver == null || user.getTokenVersion() != ver.intValue()) {
            return new AuthorizeResponse(false, "Token has been invalidated", Map.of());
        }

        String tenantId = jwt.getClaimAsString("tenantId");
        SubscriptionServiceClient.EntitlementResult entitlement =
                subscriptionServiceClient.checkEntitlement(request.token(), tenantId, request.feature());
        if (!entitlement.entitled()) {
            return new AuthorizeResponse(false,
                    entitlement.reason().isBlank() ? "Not entitled" : entitlement.reason(), Map.of());
        }

        return new AuthorizeResponse(true, "ok", Map.of(
                "sub", jwt.getSubject(),
                "userId", userId,
                "role", jwt.getClaimAsString("role"),
                "tenantId", tenantId,
                "ver", ver.intValue()));
    }

    // ------------------------------------------------------------------
    // Keycloak SSO
    // ------------------------------------------------------------------

    public String ssoInitiate() {
        // state + PKCE verifier are generated server-side and stored (Redis,
        // single-use, 5 min TTL) for validation on the callback.
        return keycloakAdminService.buildAuthorizeUrl();
    }

    public TokenResponse ssoCallback(String code, String state, String tenantId, String ipAddress,
                                     String userAgent) {
        Jwt idToken = keycloakAdminService.exchangeCodeForIdToken(code, state);
        String subject = idToken.getSubject();
        String email = idToken.getClaimAsString("email");
        String fullName = idToken.getClaimAsString("name");
        if (email == null || email.isBlank()) {
            throw AuthException.unauthorized("sso_failed", "SSO identity has no email");
        }
        User user = userService.resolveSsoUser(subject, email, fullName, tenantId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw AuthException.forbidden("user_not_active", "Account is not active");
        }
        return issueTokens(user, null, ipAddress, userAgent, AuditEventType.SSO_LOGIN);
    }

    // ------------------------------------------------------------------
    // Social login (Google / Microsoft — free on every plan)
    // ------------------------------------------------------------------

    public String socialInitiate(String provider) {
        return socialOidcService.buildAuthorizeUrl(provider);
    }

    /**
     * Unlike enterprise Keycloak SSO (no auto-provisioning), social login IS a
     * sign-up path: an unknown email gets a fresh workspace with itself as
     * ADMIN — the provider already verified the address, so the account starts
     * ACTIVE (no OTP step).
     */
    public TokenResponse socialCallback(String provider, String code, String state,
                                        String ipAddress, String userAgent) {
        SocialOidcService.SocialIdentity identity =
                socialOidcService.handleCallback(provider, code, state);
        if (identity.email() == null || identity.email().isBlank()) {
            throw AuthException.unauthorized("sso_failed", "SSO identity has no email");
        }
        User user = userService.findFirstActiveByEmail(identity.email()).orElse(null);
        if (user == null) {
            // One organization per corporate domain — the provider verified
            // this email, so a claimed domain means "join your company's
            // workspace via an admin invite", not a fresh tenant.
            String domain = FreeEmailDomains.corporateDomain(identity.email());
            if (domain != null && workspaceService.domainClaimed(domain)) {
                throw WorkspaceService.companyExists(domain);
            }
            String tenantId = newTenantId(identity.fullName(), identity.email());
            user = userService.onboard(identity.email(), identity.fullName(),
                    UserRole.ADMIN, tenantId);
            workspaceService.record(tenantId, identity.fullName() != null
                    ? identity.fullName() + "'s Workspace" : null);
            workspaceService.claimDomain(tenantId, identity.email());
            auditService.record(AuditEventType.USER_ONBOARDED, user.getId(), user.getEmail(),
                    tenantId, null, ipAddress, userAgent, "social signup via " + provider);
        }
        return issueTokens(user, null, ipAddress, userAgent, AuditEventType.SSO_LOGIN);
    }

    // ------------------------------------------------------------------
    // Enterprise per-tenant SSO (Enterprise-plan feature)
    // ------------------------------------------------------------------

    /**
     * Configuring an IdP is gated by the SSO entitlement (Enterprise plan) —
     * checked here at CONFIG time; login through an already-configured IdP is
     * not re-checked per sign-in.
     */
    public TenantIdpConfig configureIdp(String accessToken, String tenantId, String issuer,
                                        String clientId, String clientSecret,
                                        java.util.Collection<String> emailDomains,
                                        boolean enforceSso, String authorizeUrl, String tokenUrl,
                                        String userinfoUrl, Long actorId, String actorEmail,
                                        String ipAddress, String userAgent) {
        SubscriptionServiceClient.EntitlementResult entitlement =
                subscriptionServiceClient.checkEntitlement(accessToken, tenantId, "SSO");
        if (!entitlement.entitled()) {
            throw AuthException.forbidden("sso_not_in_plan",
                    "Enterprise SSO requires a plan that includes the SSO feature");
        }
        TenantIdpConfig config = enterpriseSsoService.saveConfig(tenantId, issuer, clientId,
                clientSecret, emailDomains, enforceSso, authorizeUrl, tokenUrl, userinfoUrl);
        auditService.record(AuditEventType.IDP_CONFIGURED, actorId, actorEmail, tenantId, null,
                ipAddress, userAgent, "issuer=" + issuer + " enforce=" + enforceSso);
        return config;
    }

    public String enterpriseInitiate(String email) {
        TenantIdpConfig config = enterpriseSsoService.resolveByEmail(email)
                .orElseThrow(() -> AuthException.notFound("sso_not_available",
                        "No enterprise SSO is configured for this email domain"));
        return enterpriseSsoService.buildAuthorizeUrl(config);
    }

    /**
     * Enterprise SSO does NOT auto-provision: the member must already exist in
     * the tenant (onboarded by an admin) — a foreign IdP must never mint
     * accounts in someone's workspace.
     */
    public TokenResponse enterpriseCallback(String code, String state, String ipAddress,
                                            String userAgent) {
        EnterpriseSsoService.IdpIdentity identity =
                enterpriseSsoService.handleCallback(code, state);
        if (identity.email() == null || identity.email().isBlank()) {
            throw AuthException.unauthorized("sso_failed", "SSO identity has no email");
        }
        User user = userService.resolveSsoUser(identity.subject(), identity.email(),
                identity.fullName(), identity.tenantId());
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw AuthException.forbidden("user_not_active", "Account is not active");
        }
        return issueTokens(user, null, ipAddress, userAgent, AuditEventType.SSO_LOGIN);
    }

    /**
     * enforce_sso blocks password/OTP login for MEMBERS; workspace ADMINS keep
     * password access as break-glass (a broken IdP must never lock out the
     * people who can fix its configuration).
     */
    private void requireSsoNotEnforced(User user) {
        if (user.getRole() != UserRole.ADMIN
                && enterpriseSsoService.enforced(user.getTenantId())) {
            throw AuthException.forbidden("sso_required",
                    "Your workspace requires signing in through your company's SSO");
        }
    }

    // ------------------------------------------------------------------
    // Admin: onboard / offboard
    // ------------------------------------------------------------------

    /**
     * Admin actions are scoped to the CALLER's tenant (from their access
     * token) — an admin of one workspace must not manage another workspace's
     * users, whatever the request body or headers claim.
     */
    public User onboard(OnboardRequest request, String callerTenantId, String ipAddress,
                        String userAgent) {
        if (request.tenantId() != null && !request.tenantId().isBlank()
                && !request.tenantId().equals(callerTenantId)) {
            throw AuthException.forbidden("cross_tenant_denied",
                    "Cannot onboard users into another tenant");
        }
        User user = userService.onboard(request.email(), request.fullName(), request.role(),
                callerTenantId);
        auditService.record(AuditEventType.USER_ONBOARDED, user.getId(), user.getEmail(),
                callerTenantId, null, ipAddress, userAgent, "role=" + request.role());
        // Best-effort invite ("sign in with a one-time code") — the member can
        // log in either way.
        resendEmailService.sendInvite(user.getEmail(),
                workspaceService.displayName(callerTenantId).orElse(callerTenantId));
        return user;
    }

    /**
     * Admin changes a member's role within the caller's own tenant. 404 (not
     * 403) for other tenants' users: don't confirm the id exists. The target's
     * token_version bumps, so tokens minted with the old role die instantly.
     */
    public User changeRole(Long userId, UserRole role, Long callerUserId, String callerTenantId,
                           String ipAddress, String userAgent) {
        User target = userService.requireById(userId);
        if (!target.getTenantId().equals(callerTenantId)) {
            throw AuthException.notFound("user_not_found", "User does not exist");
        }
        requireNotSelfDemotion(target, role, callerUserId);
        requireAdminRemains(target, role, callerTenantId);
        User updated = userService.changeRole(userId, role);
        auditService.record(AuditEventType.ROLE_CHANGED, userId, updated.getEmail(),
                callerTenantId, null, ipAddress, userAgent,
                target.getRole() + " -> " + role);
        return updated;
    }

    public void offboard(Long userId, Long callerUserId, String callerTenantId, String ipAddress,
                         String userAgent) {
        User target = userService.requireById(userId);
        // 404 (not 403) for other tenants' users: don't confirm the id exists.
        if (!target.getTenantId().equals(callerTenantId)) {
            throw AuthException.notFound("user_not_found", "User does not exist");
        }
        if (userId.equals(callerUserId)) {
            throw AuthException.forbidden("self_offboard_denied",
                    "You cannot remove your own account. Ask another workspace admin.");
        }
        // Removing the last admin strands the workspace exactly like demoting them.
        requireAdminRemains(target, UserRole.CLIENT, callerTenantId);
        User user = userService.offboard(userId);
        int revoked = refreshTokenService.revokeAllForUser(userId);
        auditService.record(AuditEventType.USER_OFFBOARDED, userId, user.getEmail(), user.getTenantId(),
                null, ipAddress, userAgent, "sessionsRevoked=" + revoked);
    }

    /**
     * An admin demoting themselves drops their own member-management rights
     * with no way back: the roster is admin-only, so the console goes empty
     * and nobody in the workspace can restore the role. Promoting yourself is
     * a no-op you already have, so only demotion is refused.
     */
    private void requireNotSelfDemotion(User target, UserRole role, Long callerUserId) {
        if (target.getId().equals(callerUserId) && target.getRole() != role) {
            throw AuthException.forbidden("self_role_change_denied",
                    "You cannot change your own role. Ask another workspace admin to do it.");
        }
    }

    /** A workspace must always keep at least one admin who can sign in. */
    private void requireAdminRemains(User target, UserRole role, String callerTenantId) {
        if (target.getRole() == UserRole.ADMIN && role != UserRole.ADMIN
                && userService.countActiveAdmins(callerTenantId) <= 1) {
            throw AuthException.forbidden("last_admin_denied",
                    "This is the workspace's only admin. Promote another member to Admin first.");
        }
    }

    // ------------------------------------------------------------------

    private TokenResponse issueTokens(User user, String deviceId, String ipAddress, String userAgent,
                                      AuditEventType successEvent) {
        String accessToken = jwtService.mintAccessToken(user);
        RefreshTokenService.IssuedRefreshToken issued =
                refreshTokenService.createSession(user, deviceId, ipAddress, userAgent);
        auditService.record(successEvent, user.getId(), user.getEmail(), user.getTenantId(),
                issued.session().getSessionId(), ipAddress, userAgent, null);
        return new TokenResponse(accessToken, issued.token(), "Bearer", jwtService.accessTokenTtlSeconds());
    }

    /** Used by DevTokenController (dev profile only). */
    public TokenResponse devIssueTokens(String email, String tenantId, String ipAddress, String userAgent) {
        User user = userService.requireActiveByEmail(email, tenantId);
        return issueTokens(user, "dev", ipAddress, userAgent, AuditEventType.LOGIN_SUCCESS);
    }

    /**
     * Tenant id for a new self-registered workspace: a slug of the workspace
     * name (falling back to the email's local part) plus a random suffix so
     * ids are unguessable-unique. Fits the tenant_id VARCHAR(64) column.
     */
    private String newTenantId(String workspaceName, String email) {
        String base = workspaceName != null && !workspaceName.isBlank()
                ? workspaceName
                : email.substring(0, Math.max(email.indexOf('@'), 0));
        String slug = base.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "workspace";
        }
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        byte[] suffix = new byte[4];
        RANDOM.nextBytes(suffix);
        return slug + "-" + HexFormat.of().formatHex(suffix);
    }
}
