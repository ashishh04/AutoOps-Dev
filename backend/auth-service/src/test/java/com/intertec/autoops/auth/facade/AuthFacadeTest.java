package com.intertec.autoops.auth.facade;

import com.intertec.autoops.auth.client.SubscriptionServiceClient;
import com.intertec.autoops.auth.config.AuthProperties;
import com.intertec.autoops.auth.domain.RefreshTokenSession;
import com.intertec.autoops.auth.domain.User;
import com.intertec.autoops.auth.domain.UserRole;
import com.intertec.autoops.auth.domain.UserStatus;
import com.intertec.autoops.auth.exception.AuthException;
import com.intertec.autoops.auth.service.AuditService;
import com.intertec.autoops.auth.service.JwtService;
import com.intertec.autoops.auth.service.KeycloakAdminService;
import com.intertec.autoops.auth.service.OtpService;
import com.intertec.autoops.auth.service.RateLimitService;
import com.intertec.autoops.auth.service.EnterpriseSsoService;
import com.intertec.autoops.auth.service.RefreshTokenService;
import com.intertec.autoops.auth.service.ResendEmailService;
import com.intertec.autoops.auth.service.SocialOidcService;
import com.intertec.autoops.auth.service.UserService;
import com.intertec.autoops.auth.service.WorkspaceService;
import com.intertec.autoops.auth.web.dto.OnboardRequest;
import com.intertec.autoops.auth.web.dto.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthFacadeTest {

    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private OtpService otpService;
    @Mock
    private UserService userService;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private KeycloakAdminService keycloakAdminService;
    @Mock
    private SubscriptionServiceClient subscriptionServiceClient;
    @Mock
    private AuditService auditService;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private ResendEmailService resendEmailService;
    @Mock
    private SocialOidcService socialOidcService;
    @Mock
    private EnterpriseSsoService enterpriseSsoService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthFacade facade;
    private AuthProperties properties;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
        properties = new AuthProperties();
        facade = new AuthFacade(rateLimitService, otpService, userService, jwtService,
                refreshTokenService, keycloakAdminService, subscriptionServiceClient,
                auditService, workspaceService, resendEmailService, socialOidcService,
                enterpriseSsoService, passwordEncoder, properties);
    }

    private User user(long id, String tenant) {
        User u = new User();
        u.setId(id);
        u.setEmail("user" + id + "@example.com");
        u.setRole(UserRole.ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setTenantId(tenant);
        u.setPasswordHash("$2a$10$stored");
        return u;
    }

    private void stubTokenIssue(User u) {
        when(jwtService.mintAccessToken(u)).thenReturn("access");
        when(jwtService.accessTokenTtlSeconds()).thenReturn(900L);
        RefreshTokenSession session = new RefreshTokenSession();
        session.setSessionId("session-1");
        when(refreshTokenService.createSession(eq(u), any(), any(), any()))
                .thenReturn(new RefreshTokenService.IssuedRefreshToken("refresh", session));
    }

    // ------------------------------------------------------------------
    // register: new tenant per sign-up, PENDING until the email is verified
    // ------------------------------------------------------------------

    @Test
    void registerCreatesFreshPendingTenantAndSendsCode() {
        when(rateLimitService.allowRegistration(any())).thenReturn(true);
        when(rateLimitService.tryRegisterLock(any())).thenReturn(true);
        when(userService.emailExists("new@example.com")).thenReturn(false);
        ArgumentCaptor<String> tenantCaptor = ArgumentCaptor.forClass(String.class);
        User created = user(1L, "acme-corp-cafe0123");
        when(userService.createWithPassword(eq("new@example.com"), any(), any(),
                eq(UserRole.ADMIN), tenantCaptor.capture())).thenReturn(created);

        String email = facade.register("new@example.com", "password123",
                "New User", "Acme Corp!", "1.2.3.4", "junit");

        assertEquals(created.getEmail(), email);
        String tenant = tenantCaptor.getValue();
        assertTrue(tenant.matches("acme-corp-[0-9a-f]{8}"),
                "tenant must be a fresh slug, was: " + tenant);
        assertNotEquals("default", tenant);
        // A verification code is emailed; NO tokens until it is confirmed.
        verify(otpService).generate(eq(created.getEmail()), eq(created.getTenantId()), any());
        verify(jwtService, never()).mintAccessToken(any());
        verify(refreshTokenService, never()).createSession(any(), any(), any(), any());
    }

    @Test
    void registerRejectsDuplicateEmailAnywhere() {
        when(rateLimitService.allowRegistration(any())).thenReturn(true);
        when(rateLimitService.tryRegisterLock(any())).thenReturn(true);
        when(userService.emailExists("dup@example.com")).thenReturn(true);

        AuthException ex = assertThrows(AuthException.class, () -> facade.register(
                "dup@example.com", "password123", null, null, "1.2.3.4", "junit"));
        assertEquals("user_exists", ex.getError());
        verify(userService, never()).createWithPassword(any(), any(), any(), any(), any());
    }

    @Test
    void registerIsRateLimited() {
        when(rateLimitService.allowRegistration("1.2.3.4")).thenReturn(false);

        AuthException ex = assertThrows(AuthException.class, () -> facade.register(
                "new@example.com", "password123", null, null, "1.2.3.4", "junit"));
        assertEquals("rate_limited", ex.getError());
        verify(userService, never()).createWithPassword(any(), any(), any(), any(), any());
    }

    @Test
    void verifyRegistrationActivatesPendingAccountAndSignsIn() {
        when(rateLimitService.allowVerifyAttempt(any(), any())).thenReturn(true);
        User pending = user(5L, "acme-cafe0123");
        pending.setStatus(UserStatus.PENDING);
        when(userService.requireByEmail("new@example.com", "default")).thenReturn(pending);
        User active = user(5L, "acme-cafe0123");
        when(userService.activate(5L)).thenReturn(active);
        stubTokenIssue(active);

        TokenResponse response = facade.verifyRegistration("new@example.com", "123456",
                "default", null, "1.2.3.4", "junit");

        assertEquals("access", response.accessToken());
        verify(otpService).verify("new@example.com", "acme-cafe0123", "123456", "1.2.3.4");
        verify(userService).activate(5L);
    }

    @Test
    void verifyRegistrationFailsUniformlyOnBadCode() {
        when(rateLimitService.allowVerifyAttempt(any(), any())).thenReturn(true);
        User pending = user(5L, "acme-cafe0123");
        pending.setStatus(UserStatus.PENDING);
        when(userService.requireByEmail(any(), any())).thenReturn(pending);
        doThrow(AuthException.unauthorized("otp_invalid", "bad"))
                .when(otpService).verify(any(), any(), any(), any());

        AuthException ex = assertThrows(AuthException.class, () -> facade.verifyRegistration(
                "new@example.com", "000000", "default", null, "1.2.3.4", "junit"));
        assertEquals("verification_failed", ex.getError());
        verify(userService, never()).activate(any());
    }

    // ------------------------------------------------------------------
    // password login: rate limit + no timing oracle
    // ------------------------------------------------------------------

    @Test
    void passwordLoginIsRateLimited() {
        when(rateLimitService.allowPasswordLogin("a@example.com", "1.2.3.4")).thenReturn(false);

        AuthException ex = assertThrows(AuthException.class, () -> facade.passwordLogin(
                "a@example.com", "pw", "default", null, "1.2.3.4", "junit"));
        assertEquals("rate_limited", ex.getError());
        verify(userService, never()).requireActiveByEmail(any(), any());
    }

    @Test
    void unknownEmailStillBurnsABcryptComparison() {
        when(rateLimitService.allowPasswordLogin(any(), any())).thenReturn(true);
        when(userService.requireByEmail("ghost@example.com", "default"))
                .thenThrow(AuthException.notFound("user_not_found", "no such user"));

        AuthException ex = assertThrows(AuthException.class, () -> facade.passwordLogin(
                "ghost@example.com", "pw", "default", null, "1.2.3.4", "junit"));
        // Uniform public error...
        assertEquals("login_failed", ex.getError());
        // ...and the dummy-hash comparison ran (anti timing-enumeration).
        verify(passwordEncoder).matches(eq("pw"), anyString());
    }

    @Test
    void otpOnlyAccountRejectsPasswordLoginUniformly() {
        when(rateLimitService.allowPasswordLogin(any(), any())).thenReturn(true);
        User otpOnly = user(2L, "tenant-a");
        otpOnly.setPasswordHash(null);
        when(userService.requireByEmail("otp@example.com", "default")).thenReturn(otpOnly);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        AuthException ex = assertThrows(AuthException.class, () -> facade.passwordLogin(
                "otp@example.com", "pw", "default", null, "1.2.3.4", "junit"));
        assertEquals("login_failed", ex.getError());
        // Compared against the dummy hash rather than short-circuiting.
        verify(passwordEncoder).matches(eq("pw"), anyString());
    }

    @Test
    void pendingAccountWithCorrectPasswordLearnsItIsUnverified() {
        when(rateLimitService.allowPasswordLogin(any(), any())).thenReturn(true);
        User pending = user(3L, "tenant-a");
        pending.setStatus(UserStatus.PENDING);
        when(userService.requireByEmail("p@example.com", "default")).thenReturn(pending);
        when(passwordEncoder.matches("pw", pending.getPasswordHash())).thenReturn(true);

        AuthException ex = assertThrows(AuthException.class, () -> facade.passwordLogin(
                "p@example.com", "pw", "default", null, "1.2.3.4", "junit"));
        // Correct password => actionable error; wrong password stays uniform.
        assertEquals("email_unverified", ex.getError());
    }

    @Test
    void pendingAccountWithWrongPasswordStaysUniform() {
        when(rateLimitService.allowPasswordLogin(any(), any())).thenReturn(true);
        User pending = user(3L, "tenant-a");
        pending.setStatus(UserStatus.PENDING);
        when(userService.requireByEmail("p@example.com", "default")).thenReturn(pending);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        AuthException ex = assertThrows(AuthException.class, () -> facade.passwordLogin(
                "p@example.com", "wrong", "default", null, "1.2.3.4", "junit"));
        assertEquals("login_failed", ex.getError());
    }

    // ------------------------------------------------------------------
    // OTP login: unknown accounts never reach the mail provider
    // ------------------------------------------------------------------

    @Test
    void otpLoginRejectsUnregisteredEmailWithoutMintingACode() {
        when(rateLimitService.allowOtpRequest(any(), any())).thenReturn(true);
        when(userService.requireActiveByEmail("ghost@example.com", "default"))
                .thenThrow(AuthException.notFound("user_not_found", "No account found"));

        AuthException ex = assertThrows(AuthException.class, () -> facade.initiateOtpLogin(
                "ghost@example.com", "default", "1.2.3.4", "junit"));

        assertEquals("user_not_found", ex.getError());
        // The account lookup precedes generation, so no OTP row and no email.
        verify(otpService, never()).generate(any(), any(), any());
    }

    @Test
    void otpLoginStaysNeutralForUnknownEmailsWhenRevealIsDisabled() {
        properties.getOtp().setRevealUnknownAccount(false);
        when(rateLimitService.allowOtpRequest(any(), any())).thenReturn(true);
        when(userService.requireActiveByEmail("ghost@example.com", "default"))
                .thenThrow(AuthException.notFound("user_not_found", "No account found"));

        // Swallowed: the controller returns the same "if the account exists" 200.
        facade.initiateOtpLogin("ghost@example.com", "default", "1.2.3.4", "junit");

        verify(otpService, never()).generate(any(), any(), any());
    }

    @Test
    void otpLoginSendsACodeInTheAccountsOwnTenant() {
        when(rateLimitService.allowOtpRequest(any(), any())).thenReturn(true);
        User existing = user(8L, "acme-cafe0123");
        when(userService.requireActiveByEmail("user8@example.com", "default"))
                .thenReturn(existing);

        facade.initiateOtpLogin("user8@example.com", "default", "1.2.3.4", "junit");

        verify(otpService).generate("user8@example.com", "acme-cafe0123", "1.2.3.4");
    }

    @Test
    void otpLoginIsRateLimitedBeforeAnyLookup() {
        when(rateLimitService.allowOtpRequest("a@example.com", "1.2.3.4")).thenReturn(false);

        AuthException ex = assertThrows(AuthException.class, () -> facade.initiateOtpLogin(
                "a@example.com", "default", "1.2.3.4", "junit"));

        assertEquals("rate_limited", ex.getError());
        verify(userService, never()).requireActiveByEmail(any(), any());
        verify(otpService, never()).generate(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // password reset / change
    // ------------------------------------------------------------------

    @Test
    void resetPasswordKillsAllSessionsAndSignsIn() {
        when(rateLimitService.allowVerifyAttempt(any(), any())).thenReturn(true);
        User user = user(4L, "tenant-a");
        when(userService.requireActiveByEmail("a@example.com", "default")).thenReturn(user);
        when(userService.replacePassword(eq(4L), any())).thenReturn(user);
        when(refreshTokenService.revokeAllForUser(4L)).thenReturn(2);
        stubTokenIssue(user);

        TokenResponse response = facade.resetPassword("a@example.com", "123456",
                "newpassword1", "default", null, "1.2.3.4", "junit");

        assertEquals("access", response.accessToken());
        verify(otpService).verify("a@example.com", "tenant-a", "123456", "1.2.3.4");
        verify(userService).replacePassword(eq(4L), any());
        verify(refreshTokenService).revokeAllForUser(4L);
    }

    @Test
    void resetPasswordRejectsBadCodeWithoutTouchingPassword() {
        when(rateLimitService.allowVerifyAttempt(any(), any())).thenReturn(true);
        User user = user(4L, "tenant-a");
        when(userService.requireActiveByEmail(any(), any())).thenReturn(user);
        doThrow(AuthException.unauthorized("otp_invalid", "bad"))
                .when(otpService).verify(any(), any(), any(), any());

        AuthException ex = assertThrows(AuthException.class, () -> facade.resetPassword(
                "a@example.com", "000000", "newpassword1", "default", null, "1.2.3.4", "junit"));
        assertEquals("reset_failed", ex.getError());
        verify(userService, never()).replacePassword(any(), any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void changePasswordRequiresTheCurrentPassword() {
        User user = user(6L, "tenant-a");
        when(userService.requireById(6L)).thenReturn(user);
        when(passwordEncoder.matches("wrong", user.getPasswordHash())).thenReturn(false);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").claim("userId", 6L).build();

        AuthException ex = assertThrows(AuthException.class,
                () -> facade.changePassword(jwt, "wrong", "newpassword1", null, "1.2.3.4", "junit"));
        assertEquals("invalid_password", ex.getError());
        verify(userService, never()).replacePassword(any(), any());
    }

    @Test
    void changePasswordRotatesEverything() {
        User user = user(6L, "tenant-a");
        when(userService.requireById(6L)).thenReturn(user);
        when(passwordEncoder.matches("current1", user.getPasswordHash())).thenReturn(true);
        when(userService.replacePassword(eq(6L), any())).thenReturn(user);
        when(refreshTokenService.revokeAllForUser(6L)).thenReturn(3);
        stubTokenIssue(user);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").claim("userId", 6L).build();

        TokenResponse response = facade.changePassword(jwt, "current1", "newpassword1",
                null, "1.2.3.4", "junit");

        assertEquals("access", response.accessToken());
        verify(refreshTokenService).revokeAllForUser(6L);
    }

    // ------------------------------------------------------------------
    // admin actions are caller-tenant scoped
    // ------------------------------------------------------------------

    @Test
    void onboardRejectsForeignTenantInRequestBody() {
        OnboardRequest request = new OnboardRequest("x@example.com", null,
                UserRole.CLIENT, "tenant-b");

        AuthException ex = assertThrows(AuthException.class,
                () -> facade.onboard(request, "tenant-a", "1.2.3.4", "junit"));
        assertEquals("cross_tenant_denied", ex.getError());
        verify(userService, never()).onboard(any(), any(), any(), any());
    }

    @Test
    void onboardUsesCallerTenant() {
        OnboardRequest request = new OnboardRequest("x@example.com", null, UserRole.CLIENT, null);
        when(userService.onboard("x@example.com", null, UserRole.CLIENT, "tenant-a"))
                .thenReturn(user(3L, "tenant-a"));

        facade.onboard(request, "tenant-a", "1.2.3.4", "junit");

        verify(userService).onboard("x@example.com", null, UserRole.CLIENT, "tenant-a");
    }

    @Test
    void enforcedSsoBlocksPasswordLoginForMembers() {
        when(rateLimitService.allowPasswordLogin(any(), any())).thenReturn(true);
        User member = user(4L, "tenant-a");
        member.setRole(UserRole.CLIENT);
        when(userService.requireByEmail("m@acme.com", "default")).thenReturn(member);
        when(passwordEncoder.matches("pw", member.getPasswordHash())).thenReturn(true);
        when(enterpriseSsoService.enforced("tenant-a")).thenReturn(true);

        AuthException ex = assertThrows(AuthException.class, () -> facade.passwordLogin(
                "m@acme.com", "pw", "default", null, "1.2.3.4", "junit"));
        assertEquals("sso_required", ex.getError());
    }

    @Test
    void adminsKeepPasswordLoginAsBreakGlassWhenSsoEnforced() {
        when(rateLimitService.allowPasswordLogin(any(), any())).thenReturn(true);
        User admin = user(1L, "tenant-a"); // helper builds an ADMIN
        when(userService.requireByEmail("boss@acme.com", "default")).thenReturn(admin);
        when(passwordEncoder.matches("pw", admin.getPasswordHash())).thenReturn(true);
        when(enterpriseSsoService.enforced("tenant-a")).thenReturn(true);
        stubTokenIssue(admin);

        facade.passwordLogin("boss@acme.com", "pw", "default", null, "1.2.3.4", "junit");

        verify(refreshTokenService).createSession(eq(admin), any(), any(), any());
    }

    @Test
    void enterpriseCallbackRequiresAProvisionedUser() {
        when(enterpriseSsoService.handleCallback("code", "state"))
                .thenReturn(new EnterpriseSsoService.IdpIdentity(
                        "tenant-a", "okta-sub-1", "jane@acme.com", "Jane"));
        when(userService.resolveSsoUser("okta-sub-1", "jane@acme.com", "Jane", "tenant-a"))
                .thenThrow(AuthException.forbidden("user_not_provisioned",
                        "No AutoOps account is linked to this SSO identity"));

        AuthException ex = assertThrows(AuthException.class,
                () -> facade.enterpriseCallback("code", "state", "1.1.1.1", "ua"));
        assertEquals("user_not_provisioned", ex.getError());
    }

    @Test
    void socialCallbackProvisionsAFreshWorkspaceForUnknownEmails() {
        when(socialOidcService.handleCallback("google", "code", "state"))
                .thenReturn(new SocialOidcService.SocialIdentity(
                        "google", "g-sub-1", "new@example.com", "New Person"));
        when(userService.findFirstActiveByEmail("new@example.com"))
                .thenReturn(java.util.Optional.empty());
        User provisioned = user(9L, "new-person-abc123");
        when(userService.onboard(eq("new@example.com"), eq("New Person"),
                eq(UserRole.ADMIN), anyString())).thenReturn(provisioned);
        stubTokenIssue(provisioned);

        facade.socialCallback("google", "code", "state", "1.1.1.1", "ua");

        org.mockito.Mockito.verify(userService).onboard(eq("new@example.com"),
                eq("New Person"), eq(UserRole.ADMIN), anyString());
        org.mockito.Mockito.verify(workspaceService)
                .record(anyString(), eq("New Person's Workspace"));
    }

    // ------ one organization per corporate email domain ------

    @Test
    void registerRejectsAnAlreadyClaimedCompanyDomain() {
        when(rateLimitService.allowRegistration(any())).thenReturn(true);
        when(rateLimitService.tryRegisterLock(any())).thenReturn(true);
        when(userService.emailExists("bob@acme.com")).thenReturn(false);
        when(workspaceService.domainClaimed("acme.com")).thenReturn(true);

        AuthException ex = assertThrows(AuthException.class, () -> facade.register(
                "bob@acme.com", "Str0ngPass!", "Bob", "Acme Two", "1.2.3.4", "junit"));
        assertEquals("company_exists", ex.getError());
        verify(userService, never()).createWithPassword(any(), any(), any(), any(), any());
    }

    @Test
    void freeProviderEmailsNeverClaimOrBlockADomain() {
        // gmail.com is not a corporate domain: no claim check, sign-up proceeds.
        when(rateLimitService.allowRegistration(any())).thenReturn(true);
        when(rateLimitService.tryRegisterLock(any())).thenReturn(true);
        when(userService.emailExists("solo@gmail.com")).thenReturn(false);
        User pending = user(7L, "solo-cafe0123");
        pending.setStatus(UserStatus.PENDING);
        when(userService.createWithPassword(eq("solo@gmail.com"), any(), any(), any(), any()))
                .thenReturn(pending);

        facade.register("solo@gmail.com", "Str0ngPass!", "Solo", "Solo Works",
                "1.2.3.4", "junit");

        verify(workspaceService, never()).domainClaimed(any());
        verify(userService).createWithPassword(eq("solo@gmail.com"), any(), any(), any(), any());
    }

    @Test
    void verifyRegistrationClaimsTheDomainBeforeActivating() {
        when(rateLimitService.allowVerifyAttempt(any(), any())).thenReturn(true);
        User pending = user(5L, "acme-cafe0123");
        pending.setStatus(UserStatus.PENDING);
        when(userService.requireByEmail("user5@example.com", "default")).thenReturn(pending);
        User active = user(5L, "acme-cafe0123");
        when(userService.activate(5L)).thenReturn(active);
        stubTokenIssue(active);

        facade.verifyRegistration("user5@example.com", "123456", "default", null,
                "1.2.3.4", "junit");

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(workspaceService, userService);
        inOrder.verify(workspaceService).claimDomain("acme-cafe0123", "user5@example.com");
        inOrder.verify(userService).activate(5L);
    }

    @Test
    void verifyRegistrationSurfacesCompanyExistsPastTheUniformCatch() {
        // The OTP was valid (email ownership proven), so company_exists must
        // NOT be masked as verification_failed — and the loser stays PENDING.
        when(rateLimitService.allowVerifyAttempt(any(), any())).thenReturn(true);
        User pending = user(5L, "acme-cafe0123");
        pending.setStatus(UserStatus.PENDING);
        pending.setEmail("bob@acme.com");
        when(userService.requireByEmail("bob@acme.com", "default")).thenReturn(pending);
        doThrow(AuthException.conflict("company_exists", "exists"))
                .when(workspaceService).claimDomain("acme-cafe0123", "bob@acme.com");

        AuthException ex = assertThrows(AuthException.class, () -> facade.verifyRegistration(
                "bob@acme.com", "123456", "default", null, "1.2.3.4", "junit"));
        assertEquals("company_exists", ex.getError());
        verify(userService, never()).activate(any());
    }

    @Test
    void socialSignupRejectsAnAlreadyClaimedCompanyDomain() {
        when(socialOidcService.handleCallback("google", "code", "state"))
                .thenReturn(new SocialOidcService.SocialIdentity(
                        "google", "g-sub-2", "bob@acme.com", "Bob"));
        when(userService.findFirstActiveByEmail("bob@acme.com"))
                .thenReturn(java.util.Optional.empty());
        when(workspaceService.domainClaimed("acme.com")).thenReturn(true);

        AuthException ex = assertThrows(AuthException.class,
                () -> facade.socialCallback("google", "code", "state", "1.1.1.1", "ua"));
        assertEquals("company_exists", ex.getError());
        verify(userService, never()).onboard(anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                anyString());
    }

    @Test
    void socialSignupClaimsTheProviderVerifiedDomain() {
        when(socialOidcService.handleCallback("google", "code", "state"))
                .thenReturn(new SocialOidcService.SocialIdentity(
                        "google", "g-sub-3", "eve@fresh-corp.com", "Eve"));
        when(userService.findFirstActiveByEmail("eve@fresh-corp.com"))
                .thenReturn(java.util.Optional.empty());
        when(workspaceService.domainClaimed("fresh-corp.com")).thenReturn(false);
        User provisioned = user(11L, "eve-abc123");
        when(userService.onboard(eq("eve@fresh-corp.com"), eq("Eve"),
                eq(UserRole.ADMIN), anyString())).thenReturn(provisioned);
        stubTokenIssue(provisioned);

        facade.socialCallback("google", "code", "state", "1.1.1.1", "ua");

        verify(workspaceService).claimDomain(anyString(), eq("eve@fresh-corp.com"));
    }

    @Test
    void socialCallbackLogsInExistingUsersWithoutProvisioning() {
        when(socialOidcService.handleCallback("microsoft", "code", "state"))
                .thenReturn(new SocialOidcService.SocialIdentity(
                        "microsoft", "ms-sub-1", "user5@example.com", null));
        User existing = user(5L, "tenant-a");
        when(userService.findFirstActiveByEmail("user5@example.com"))
                .thenReturn(java.util.Optional.of(existing));
        stubTokenIssue(existing);

        facade.socialCallback("microsoft", "code", "state", "1.1.1.1", "ua");

        org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                .onboard(anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void changeRoleRefusesUsersOfOtherTenants() {
        when(userService.requireById(42L)).thenReturn(user(42L, "tenant-b"));

        AuthException ex = assertThrows(AuthException.class,
                () -> facade.changeRole(42L, UserRole.CLIENT, 1L, "tenant-a", "1.1.1.1", "ua"));
        assertEquals("user_not_found", ex.getError());
    }

    @Test
    void changeRoleWorksWithinOwnTenant() {
        User target = user(7L, "tenant-a");
        when(userService.requireById(7L)).thenReturn(target);
        when(userService.countActiveAdmins("tenant-a")).thenReturn(2L);
        when(userService.changeRole(7L, UserRole.CLIENT)).thenReturn(target);

        facade.changeRole(7L, UserRole.CLIENT, 1L, "tenant-a", "1.1.1.1", "ua");

        org.mockito.Mockito.verify(userService).changeRole(7L, UserRole.CLIENT);
    }

    @Test
    void changeRoleRefusesDemotingYourself() {
        // Self-demotion revoked the caller's own member management rights and
        // left the workspace with no way to restore them.
        User self = user(7L, "tenant-a");
        when(userService.requireById(7L)).thenReturn(self);
        when(userService.countActiveAdmins("tenant-a")).thenReturn(5L);

        AuthException ex = assertThrows(AuthException.class,
                () -> facade.changeRole(7L, UserRole.VIEWER, 7L, "tenant-a", "1.1.1.1", "ua"));

        assertEquals("self_role_change_denied", ex.getError());
        verify(userService, never()).changeRole(any(), any());
    }

    @Test
    void changeRoleRefusesDemotingTheLastAdmin() {
        User target = user(7L, "tenant-a");
        when(userService.requireById(7L)).thenReturn(target);
        when(userService.countActiveAdmins("tenant-a")).thenReturn(1L);

        AuthException ex = assertThrows(AuthException.class,
                () -> facade.changeRole(7L, UserRole.CLIENT, 1L, "tenant-a", "1.1.1.1", "ua"));

        assertEquals("last_admin_denied", ex.getError());
        verify(userService, never()).changeRole(any(), any());
    }

    @Test
    void offboardRefusesUsersOfOtherTenants() {
        when(userService.requireById(42L)).thenReturn(user(42L, "tenant-b"));

        AuthException ex = assertThrows(AuthException.class,
                () -> facade.offboard(42L, 1L, "tenant-a", "1.2.3.4", "junit"));
        // 404, not 403: existence of other tenants' user ids is not confirmed.
        assertEquals("user_not_found", ex.getError());
        verify(userService, never()).offboard(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void offboardWorksWithinOwnTenant() {
        User target = user(7L, "tenant-a");
        when(userService.requireById(7L)).thenReturn(target);
        when(userService.countActiveAdmins("tenant-a")).thenReturn(2L);
        when(userService.offboard(7L)).thenReturn(target);
        when(refreshTokenService.revokeAllForUser(7L)).thenReturn(2);

        facade.offboard(7L, 1L, "tenant-a", "1.2.3.4", "junit");

        verify(userService).offboard(7L);
        verify(refreshTokenService).revokeAllForUser(7L);
    }

    @Test
    void offboardRefusesRemovingYourself() {
        User self = user(7L, "tenant-a");
        when(userService.requireById(7L)).thenReturn(self);
        when(userService.countActiveAdmins("tenant-a")).thenReturn(5L);

        AuthException ex = assertThrows(AuthException.class,
                () -> facade.offboard(7L, 7L, "tenant-a", "1.2.3.4", "junit"));

        assertEquals("self_offboard_denied", ex.getError());
        verify(userService, never()).offboard(any());
    }

    @Test
    void offboardRefusesRemovingTheLastAdmin() {
        User target = user(7L, "tenant-a");
        when(userService.requireById(7L)).thenReturn(target);
        when(userService.countActiveAdmins("tenant-a")).thenReturn(1L);

        AuthException ex = assertThrows(AuthException.class,
                () -> facade.offboard(7L, 1L, "tenant-a", "1.2.3.4", "junit"));

        assertEquals("last_admin_denied", ex.getError());
        verify(userService, never()).offboard(any());
    }
}
