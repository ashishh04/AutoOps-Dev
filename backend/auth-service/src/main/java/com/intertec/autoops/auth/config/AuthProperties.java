package com.intertec.autoops.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * All auth-service settings, kebab-case under {@code autoops.auth.*}.
 */
@ConfigurationProperties("autoops.auth")
public class AuthProperties {

    /** Token issuer (`iss` claim). */
    private String issuer = "autoops-auth-service";

    private Duration accessTokenTtl = Duration.ofMinutes(15);

    private Duration refreshTokenTtl = Duration.ofDays(30);

    /** Where OAuth2Authorization state lives: jdbc | redis. */
    private String tokenStore = "jdbc";

    /** PKCS#12 keystore for RSA signing keys; blank => dev auto-generated pair. */
    private String keystorePath = "";

    private String keystorePassword = "";

    private String keystoreAlias = "autoops-auth";

    /**
     * Where the console lives, for links inside emails. Not derivable from the
     * request: mail is sent after the transaction commits, off the request
     * thread, and a link built from a Host header is a link an attacker can
     * choose.
     */
    private String appBaseUrl = "http://localhost:5173";

    private final Otp otp = new Otp();
    private final RateLimit rateLimit = new RateLimit();
    private final Subscription subscription = new Subscription();
    private final Resend resend = new Resend();
    private final GatewayClient gatewayClient = new GatewayClient();
    private final Keycloak keycloak = new Keycloak();
    private final Social social = new Social();
    private final Cors cors = new Cors();
    private final Tenant tenant = new Tenant();
    private final Retention retention = new Retention();

    /** Proxies (exact IPs or CIDRs) allowed to set X-Forwarded-For / X-Real-IP. */
    private List<String> trustedProxies = List.of();

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public String getTokenStore() {
        return tokenStore;
    }

    public void setTokenStore(String tokenStore) {
        this.tokenStore = tokenStore;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public void setKeystorePath(String keystorePath) {
        this.keystorePath = keystorePath;
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    public String getKeystoreAlias() {
        return keystoreAlias;
    }

    public void setKeystoreAlias(String keystoreAlias) {
        this.keystoreAlias = keystoreAlias;
    }

    public String getAppBaseUrl() {
        return appBaseUrl;
    }

    public void setAppBaseUrl(String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
    }

    public Otp getOtp() {
        return otp;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Subscription getSubscription() {
        return subscription;
    }

    public Resend getResend() {
        return resend;
    }

    public GatewayClient getGatewayClient() {
        return gatewayClient;
    }

    public Keycloak getKeycloak() {
        return keycloak;
    }

    public Social getSocial() {
        return social;
    }

    public Cors getCors() {
        return cors;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public Retention getRetention() {
        return retention;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public static class Otp {
        private int length = 6;
        private Duration ttl = Duration.ofMinutes(5);
        private int maxAttempts = 5;

        /**
         * Whether {@code POST /api/auth/otp/generate} tells the caller that no
         * account exists for the email (404 user_not_found) instead of the
         * neutral "if the account exists" 200.
         *
         * <p>ON (the default) is a UX choice: an unregistered visitor is told
         * to sign up instead of waiting for a code that will never arrive.
         * The cost is user enumeration — anyone can probe which emails have
         * accounts, bounded only by the OTP rate limiter. Set to {@code false}
         * to restore the neutral response. Note this does NOT affect email
         * spend: an unknown address never reached the mail provider either
         * way, because the account lookup happens before the code is minted.
         *
         * <p>{@code /password/forgot} and {@code /register/resend} stay
         * neutral regardless.
         */
        private boolean revealUnknownAccount = true;

        public int getLength() {
            return length;
        }

        public void setLength(int length) {
            this.length = length;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public boolean isRevealUnknownAccount() {
            return revealUnknownAccount;
        }

        public void setRevealUnknownAccount(boolean revealUnknownAccount) {
            this.revealUnknownAccount = revealUnknownAccount;
        }
    }

    public static class RateLimit {
        private int otpRequests = 5;
        private int verifyAttempts = 10;
        /** Password-login attempts per account (and xIP_LIMIT_FACTOR per IP) per window. */
        private int loginAttempts = 10;
        /** Self-service registrations per IP per window. */
        private int registrations = 10;
        private Duration window = Duration.ofMinutes(15);

        public int getOtpRequests() {
            return otpRequests;
        }

        public void setOtpRequests(int otpRequests) {
            this.otpRequests = otpRequests;
        }

        public int getVerifyAttempts() {
            return verifyAttempts;
        }

        public void setVerifyAttempts(int verifyAttempts) {
            this.verifyAttempts = verifyAttempts;
        }

        public int getLoginAttempts() {
            return loginAttempts;
        }

        public void setLoginAttempts(int loginAttempts) {
            this.loginAttempts = loginAttempts;
        }

        public int getRegistrations() {
            return registrations;
        }

        public void setRegistrations(int registrations) {
            this.registrations = registrations;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    public static class Subscription {
        private String baseUrl = "http://localhost:8082";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(3);
        private boolean entitlementFailOpen = false;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public boolean isEntitlementFailOpen() {
            return entitlementFailOpen;
        }

        public void setEntitlementFailOpen(boolean entitlementFailOpen) {
            this.entitlementFailOpen = entitlementFailOpen;
        }
    }

    /**
     * Direct social OIDC (Google / Microsoft) — a LOGIN convenience free on
     * every plan. Enterprise "SSO" (enforced org-wide IdPs) is a separate,
     * plan-gated feature. A provider with a blank client-id is disabled.
     */
    public static class Social {
        private final Provider google = new Provider();
        private final Provider microsoft = new Provider();

        /** Public base URL providers redirect back to (the gateway in dev/docker). */
        private String redirectBaseUrl = "http://localhost:8080";

        public Provider getGoogle() {
            return google;
        }

        public Provider getMicrosoft() {
            return microsoft;
        }

        public String getRedirectBaseUrl() {
            return redirectBaseUrl;
        }

        public void setRedirectBaseUrl(String redirectBaseUrl) {
            this.redirectBaseUrl = redirectBaseUrl;
        }

        public static class Provider {
            private String clientId = "";
            private String clientSecret = "";

            public String getClientId() {
                return clientId;
            }

            public void setClientId(String clientId) {
                this.clientId = clientId;
            }

            public String getClientSecret() {
                return clientSecret;
            }

            public void setClientSecret(String clientSecret) {
                this.clientSecret = clientSecret;
            }
        }
    }

    /**
     * Resend (https://resend.com). One API key and one verified sender; there
     * is no template id, because Resend has no server-side dynamic templates —
     * the OTP body lives in {@code ResendEmailService}.
     */
    public static class Resend {
        private String apiKey = "";
        /**
         * Must be an address on a domain verified in Resend. The default is
         * Resend's shared test sender, which ONLY delivers to the address that
         * owns the Resend account — it proves the integration and nothing else.
         */
        private String fromEmail = "onboarding@resend.dev";
        private String fromName = "AutoOps";
        /** Svix signing secret ({@code whsec_...}). Empty disables the webhook. */
        private String webhookSecret = "";
        /** Overridable so a test can point the client at a loopback stub. */
        private String baseUrl = "https://api.resend.com";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getFromEmail() {
            return fromEmail;
        }

        public void setFromEmail(String fromEmail) {
            this.fromEmail = fromEmail;
        }

        public String getFromName() {
            return fromName;
        }

        public void setFromName(String fromName) {
            this.fromName = fromName;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /** {@code AutoOps <no-reply@example.com>} — what the Resend API wants. */
        public String sender() {
            return fromName == null || fromName.isBlank()
                    ? fromEmail : fromName + " <" + fromEmail + ">";
        }
    }

    public static class GatewayClient {
        private String clientId = "gateway";
        private String clientSecret = "gateway-secret";

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }
    }

    public static class Keycloak {
        private String issuerUri = "http://localhost:8180/realms/autoops";
        private String clientId = "autoops-auth";
        private String clientSecret = "";
        private String redirectUri = "http://localhost:8081/api/auth/sso/callback";
        /**
         * Frontend URL to 302 to after a successful SSO callback; tokens are
         * appended in the URL FRAGMENT (never sent to servers or logged).
         * Blank => the callback returns the token JSON directly (API clients).
         */
        private String successRedirect = "";

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public String getSuccessRedirect() {
            return successRedirect;
        }

        public void setSuccessRedirect(String successRedirect) {
            this.successRedirect = successRedirect;
        }
    }

    public static class Cors {
        private List<String> allowedOrigins =
                List.of("http://localhost:5173", "http://localhost:3000");

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    /** How long rows outlive their usefulness before PurgeService deletes them. */
    public static class Retention {
        /** Expired/consumed OTP challenges (delivery status has settled by then). */
        private Duration otpEntries = Duration.ofDays(1);
        /** Expired or revoked refresh sessions (kept briefly for forensics). */
        private Duration refreshSessions = Duration.ofDays(30);
        /** Security audit trail. */
        private Duration auditLog = Duration.ofDays(180);

        public Duration getOtpEntries() {
            return otpEntries;
        }

        public void setOtpEntries(Duration otpEntries) {
            this.otpEntries = otpEntries;
        }

        public Duration getRefreshSessions() {
            return refreshSessions;
        }

        public void setRefreshSessions(Duration refreshSessions) {
            this.refreshSessions = refreshSessions;
        }

        public Duration getAuditLog() {
            return auditLog;
        }

        public void setAuditLog(Duration auditLog) {
            this.auditLog = auditLog;
        }
    }

    public static class Tenant {
        /** Used when require-header is false and no X-Tenant-ID is sent. */
        private String defaultTenant = "default";

        /** Reject requests without X-Tenant-ID (enable behind a trusted gateway). */
        private boolean requireHeader = false;

        public String getDefaultTenant() {
            return defaultTenant;
        }

        public void setDefaultTenant(String defaultTenant) {
            this.defaultTenant = defaultTenant;
        }

        public boolean isRequireHeader() {
            return requireHeader;
        }

        public void setRequireHeader(boolean requireHeader) {
            this.requireHeader = requireHeader;
        }
    }
}
