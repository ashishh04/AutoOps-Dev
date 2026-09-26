package com.intertec.autoops.alerts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "autoops.alerts")
public class AlertProperties {

    /** The value shipped in docker-compose.keep.yml. */
    public static final String DEV_API_KEY = "dev-keep-api-key-change-me";

    /** The value shipped in docker-compose.keep.yml. */
    public static final String DEV_INGEST_SECRET = "dev-alert-ingest-secret-change-me";

    /**
     * Shared platform token for the {@code /internal/**} surface.
     *
     * <p>Empty by default, and an empty token REFUSES every internal call
     * rather than accepting them. A service that silently opens its internal
     * surface because a variable was not set is the failure the check exists to
     * prevent.
     */
    private String internalToken = "";

    private String jwksUri = "http://localhost:8081/oauth2/jwks";
    private String issuer = "autoops-auth-service";
    private Engine engine = new Engine();
    private Ingest ingest = new Ingest();
    private Plugin plugin = new Plugin();

    /**
     * plugin-service, where an arriving alert is reported so a tenant's own
     * channels can carry it.
     *
     * <p>Tight timeouts on purpose. This sits on the public ingest path, which
     * answers a customer's monitoring tool; a source that gets a slow or failed
     * response will retry, and duplicate alerts are a worse outcome than a
     * missed Slack message.
     */
    public static class Plugin {

        private String url = "http://localhost:8088";
        private String internalToken = "dev-internal-token";
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(2);

        /** Kill switch: false stops alert-service emitting events at all. */
        private boolean enabled = true;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public void setPlugin(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The public door a customer's monitoring tool posts alerts through. */
    public static class Ingest {
        /** Signs scope into the token a customer pastes into their tool. */
        private String secret = DEV_INGEST_SECRET;
        /**
         * How the customer's tool reaches AutoOps from the internet. Compose
         * DNS is useless in a Datadog webhook field, so this must be the
         * BROWSER-facing origin, not an internal service name.
         */
        private String publicBaseUrl = "http://localhost:5173";

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }
    }

    public Ingest getIngest() {
        return ingest;
    }

    public void setIngest(Ingest ingest) {
        this.ingest = ingest;
    }

    /**
     * The alert engine. Its URL and key live here and nowhere else — no table
     * holds them, no endpoint returns them, no screen renders them.
     */
    public static class Engine {
        private String url = "http://keep-backend:8080";
        private String apiKey = "";
        /**
         * Separate, stronger key used ONLY to connect and disconnect monitoring
         * sources. The engine has no role between read-only and admin, so
         * installing a provider needs admin — and holding one key for both
         * would silently give every list call the power to write. Two keys keep
         * the read path unable to mutate anything even if it tried.
         */
        private String adminApiKey = "";
        /** Write-alerts-only key. Used by the public ingest endpoint and nothing else. */
        private String ingestApiKey = "";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(10);
        private int maxFetch = 1000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getAdminApiKey() {
            return adminApiKey;
        }

        public String getIngestApiKey() {
            return ingestApiKey;
        }

        public void setIngestApiKey(String ingestApiKey) {
            this.ingestApiKey = ingestApiKey;
        }

        public void setAdminApiKey(String adminApiKey) {
            this.adminApiKey = adminApiKey;
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

        public int getMaxFetch() {
            return maxFetch;
        }

        public void setMaxFetch(int maxFetch) {
            this.maxFetch = maxFetch;
        }
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

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

    public Engine getEngine() {
        return engine;
    }

    public void setEngine(Engine engine) {
        this.engine = engine;
    }
}
