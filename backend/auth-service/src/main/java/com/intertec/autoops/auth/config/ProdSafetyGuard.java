package com.intertec.autoops.auth.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Refuses to start with an unsafe production configuration. Development
 * defaults (gateway secret, DB password, ephemeral JWT keys, the dev-only
 * token endpoint) must never survive into prod silently.
 */
@Component
public class ProdSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(ProdSafetyGuard.class);

    private final Environment environment;
    private final AuthProperties properties;

    public ProdSafetyGuard(Environment environment, AuthProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @PostConstruct
    public void verifyProdConfiguration() {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        if (!profiles.contains("prod")) {
            return;
        }

        List<String> problems = new ArrayList<>();
        if (profiles.contains("dev")) {
            problems.add("the 'dev' profile (unauthenticated /api/auth/dev/token) is active alongside prod");
        }
        if ("gateway-secret".equals(properties.getGatewayClient().getClientSecret())) {
            problems.add("GATEWAY_CLIENT_SECRET is still the development default");
        }
        if ("autoops".equals(environment.getProperty("spring.datasource.password"))) {
            problems.add("DB_PASSWORD is still the development default");
        }
        String keystorePath = properties.getKeystorePath();
        if (keystorePath == null || keystorePath.isBlank()) {
            problems.add("JWT_KEYSTORE_PATH must point to a persistent PKCS#12 keystore in prod");
        }
        if ("REPLACE_ME".equals(properties.getResend().getApiKey())
                || properties.getResend().getApiKey().isBlank()) {
            problems.add("RESEND_API_KEY is not configured");
        }
        // onboarding@resend.dev only delivers to the Resend account owner, so
        // in prod it is indistinguishable from email being switched off.
        if ("onboarding@resend.dev".equals(properties.getResend().getFromEmail())) {
            problems.add("RESEND_FROM_EMAIL is still the shared test sender, which only "
                    + "delivers to the Resend account owner — set an address on a verified domain");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start with unsafe prod configuration: " + String.join("; ", problems));
        }
        log.info("Prod safety checks passed");
    }
}
