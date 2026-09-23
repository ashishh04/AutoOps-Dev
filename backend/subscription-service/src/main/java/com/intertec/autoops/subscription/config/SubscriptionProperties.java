package com.intertec.autoops.subscription.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** All subscription-service settings, kebab-case under {@code autoops.subscription.*}. */
@ConfigurationProperties("autoops.subscription")
public class SubscriptionProperties {

    /** auth-service JWKS endpoint for local RS256 validation. */
    private String jwksUri = "http://localhost:8081/oauth2/jwks";

    /** Expected token issuer (must match auth-service's `iss` claim). */
    private String issuer = "autoops-auth-service";

    /** Billing period length for the stubbed billing provider. */
    private Duration billingPeriod = Duration.ofDays(30);

    /** How long entitlement decisions are cached in Redis. */
    private Duration entitlementCacheTtl = Duration.ofSeconds(60);

    /** Which PaymentProvider bean charges: "stub" today, "stripe" later. */
    private String paymentProvider = "stub";

    /** Dev/test knob: makes the stub provider decline every charge. */
    private boolean paymentStubFails = false;

    /**
     * Whether a subscription's BILLING LIFECYCLE can deny access.
     *
     * <p>Null means "decide from the payment provider", and that default is the
     * point. While {@link #paymentProvider} is {@code stub} nobody can actually
     * pay — so enforcing "your trial has ended" locks every customer out of a
     * product they have no way to buy. That is what was happening in demos.
     *
     * <p>Deriving it rather than adding a flag also means it corrects itself:
     * the day a real provider is configured, enforcement turns on without
     * anybody remembering to flip anything. Set it explicitly to override in
     * either direction — {@code true} to rehearse the paywall against the stub,
     * {@code false} to run an unmetered environment against a real provider.
     *
     * <p>This gates the LIFECYCLE only. Plan features and numeric quotas are
     * product behaviour, not payment, and are enforced either way.
     */
    private Boolean enforceBilling = null;

    /** Resolved: explicit setting if given, otherwise "only with a real provider". */
    public boolean isBillingEnforced() {
        return enforceBilling != null ? enforceBilling : !"stub".equalsIgnoreCase(paymentProvider);
    }

    public Boolean getEnforceBilling() {
        return enforceBilling;
    }

    public void setEnforceBilling(Boolean enforceBilling) {
        this.enforceBilling = enforceBilling;
    }

    public static final String DEV_INTERNAL_TOKEN = "dev-internal-token";

    /**
     * Shared secret for {@code /internal/**} service-to-service calls (the
     * scheduler's tenant-scoped entitlement checks — no user token exists
     * there). Env SUBSCRIPTION_INTERNAL_TOKEN; prod refuses the dev default.
     */
    private String internalToken = DEV_INTERNAL_TOKEN;

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getBillingPeriod() {
        return billingPeriod;
    }

    public void setBillingPeriod(Duration billingPeriod) {
        this.billingPeriod = billingPeriod;
    }

    public Duration getEntitlementCacheTtl() {
        return entitlementCacheTtl;
    }

    public void setEntitlementCacheTtl(Duration entitlementCacheTtl) {
        this.entitlementCacheTtl = entitlementCacheTtl;
    }

    public String getPaymentProvider() {
        return paymentProvider;
    }

    public void setPaymentProvider(String paymentProvider) {
        this.paymentProvider = paymentProvider;
    }

    public boolean isPaymentStubFails() {
        return paymentStubFails;
    }

    public void setPaymentStubFails(boolean paymentStubFails) {
        this.paymentStubFails = paymentStubFails;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }
}
