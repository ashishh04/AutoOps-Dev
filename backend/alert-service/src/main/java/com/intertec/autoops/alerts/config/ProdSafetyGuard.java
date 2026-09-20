package com.intertec.autoops.alerts.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Refuses to start with an unsafe production configuration (same convention as
 * core-service, rundeck-service and auth-service).
 *
 * <p>The checks here are shorter than rundeck-service's because this service
 * holds far less: no database, no per-tenant credentials, no execution. What it
 * does hold is one key to an engine carrying <b>every tenant's alerts</b>, and
 * an alert stream is a map of what is broken in a customer's estate. That is
 * the thing being guarded.
 */
@Component
public class ProdSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(ProdSafetyGuard.class);

    /** The value shipped in docker-compose.keep.yml. */
    private static final String DEV_ADMIN_KEY = "dev-keep-admin-key-change-me";

    private final Environment environment;
    private final AlertProperties properties;

    public ProdSafetyGuard(Environment environment, AlertProperties properties) {
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
            problems.add("the 'dev' profile is active alongside prod");
        }
        if (properties.getJwksUri().contains("localhost")) {
            problems.add("AUTH_JWKS_URI still points at localhost");
        }
        String apiKey = properties.getEngine().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            problems.add("KEEP_API_KEY is unset — the alert plane is unreachable and every "
                    + "alerts page in the console would answer 502");
        }
        if (AlertProperties.DEV_API_KEY.equals(apiKey)) {
            problems.add("KEEP_API_KEY is still the development default (" + AlertProperties.DEV_API_KEY
                    + ") — it reads every tenant's alerts, and it ships in this repository");
        }
        String adminKey = properties.getEngine().getAdminApiKey();
        if (DEV_ADMIN_KEY.equals(adminKey)) {
            problems.add("KEEP_ADMIN_KEY is still the development default (" + DEV_ADMIN_KEY
                    + ") — it is admin on the alert plane for every tenant, and it ships "
                    + "in this repository");
        }
        // A read timeout in the minutes is not a safety net, it is a way to
        // exhaust the request threads of a console page everyone opens at once
        // during an incident.
        if (properties.getEngine().getReadTimeout().toSeconds() > 30) {
            problems.add("KEEP_READ_TIMEOUT is over 30s — a wedged engine would pin request "
                    + "threads here for as long as it stays wedged");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start with unsafe prod configuration: " + String.join("; ", problems));
        }
        log.info("Prod safety checks passed");
    }
}
