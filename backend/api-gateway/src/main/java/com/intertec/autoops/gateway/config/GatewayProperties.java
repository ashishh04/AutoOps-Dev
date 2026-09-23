package com.intertec.autoops.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** All gateway settings, kebab-case under {@code autoops.gateway.*}. */
@ConfigurationProperties("autoops.gateway")
public class GatewayProperties {

    /** auth-service JWKS endpoint for local RS256 validation. */
    private String jwksUri = "http://localhost:8081/oauth2/jwks";

    /** Expected token issuer (must match auth-service's {@code iss} claim). */
    private String issuer = "autoops-auth-service";

    /** Browser origins allowed to call the platform through the gateway. */
    private List<String> corsAllowedOrigins =
            List.of("http://localhost:5173", "http://localhost:3000");

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

    /**
     * Per-tenant request budget.
     *
     * <p>Defaults are deliberately generous: a limiter that trips on normal use
     * is removed by whoever is on call, and then there is no limiter. The point
     * is to stop one customer exhausting the platform, not to shape traffic.
     */
    private RateLimit rateLimit = new RateLimit();

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public static class RateLimit {

        /** Off leaves the filter unregistered entirely — no Redis call per request. */
        private boolean enabled = true;

        /** Requests allowed per window, per tenant. */
        private int requests = 600;

        private Duration window = Duration.ofMinutes(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRequests() {
            return requests;
        }

        public void setRequests(int requests) {
            this.requests = requests;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }
}