package com.intertec.autoops.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** All agent-service settings, kebab-case under {@code autoops.agent.*}. */
@ConfigurationProperties("autoops.agent")
public class AgentProperties {

    public static final String DEV_INTERNAL_TOKEN = "dev-internal-token";

    /** auth-service JWKS endpoint for local RS256 validation. */
    private String jwksUri = "http://localhost:8081/oauth2/jwks";

    /** Expected token issuer (must match auth-service's `iss` claim). */
    private String issuer = "autoops-auth-service";

    /** Shared secret this service requires on its OWN {@code /internal/**}. */
    private String internalToken = DEV_INTERNAL_TOKEN;

    private final Subscription subscription = new Subscription();

    private final Peer core = new Peer("http://localhost:8083", Duration.ofSeconds(5));

    private final Peer workflow = new Peer("http://localhost:8086", Duration.ofSeconds(5));

    /**
     * The Python reasoning runtime.
     *
     * <p>Its read timeout is minutes, not seconds, and that is not an
     * oversight. Every other peer here answers a database question; this one
     * answers a model, and a phased agent's reduce can span three model calls
     * before it reaches a boundary. A five-second budget would abort the call
     * AFTER the tokens were spent, which is the worst of both.
     */
    private final Peer runtime = new Peer("http://localhost:8089", Duration.ofMinutes(4));

    /**
     * plugin-service, where an agent run's lifecycle is reported so a tenant's
     * Slack or Teams hears about it.
     *
     * <p>Tightest timeouts of any peer here, on purpose: this sits on the agent
     * loop's own thread between turns, and the far side only queues the fan-out
     * before answering. {@code PluginClient} never throws and never retries, so
     * a plugin-service that is down or absent costs a notification and nothing
     * else.
     */
    private final Plugin plugin = new Plugin();

    private final Loop loop = new Loop();

    public Plugin getPlugin() {
        return plugin;
    }

    public Loop getLoop() {
        return loop;
    }

    public Peer getRuntime() {
        return runtime;
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

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public Subscription getSubscription() {
        return subscription;
    }

    public Peer getCore() {
        return core;
    }

    public Peer getWorkflow() {
        return workflow;
    }

    /**
     * The agent loop's bounds. Every one of these exists because the loop
     * spends a tenant's money and touches their infrastructure, and none of
     * them should be reachable by anything a model writes.
     */
    public static class Loop {

        /**
         * Whether the reasoning happens in the Python runtime or in this
         * service's own {@code ChatModel} adapters.
         *
         * <p>A kill switch, and it is meant to be a real one. The Java loop is
         * NOT deleted while this exists: an agent runtime that starts
         * misbehaving against a customer's infrastructure at 3am needs an
         * answer that is one environment variable and a restart, not a
         * rollback of a service that also owns the approvals inbox.
         *
         * <p>It is safe to flip mid-flight in one direction only. A run parked
         * on an approval holds a Python state blob in its transcript; turning
         * the runtime OFF leaves that blob unreadable to the Java loop, which
         * fails the run honestly rather than misreading it. Runs started under
         * the Java loop are unaffected either way.
         */
        private boolean runtimeEnabled = true;

        public boolean isRuntimeEnabled() {
            return runtimeEnabled;
        }

        public void setRuntimeEnabled(boolean runtimeEnabled) {
            this.runtimeEnabled = runtimeEnabled;
        }

        /**
         * Model call → tool → model call. A run that never terminates on its
         * own stops here.
         *
         * <p>Twelve is deliberate: enough for a real "check three things, act
         * on what you found, report", small enough that a model stuck in a
         * loop of the same tool costs twelve calls rather than a weekend. It
         * is COPIED onto each run, so lowering it never kills a run already
         * in flight.
         */
        private int maxSteps = 12;

        /** Per model call. Not a budget for the run — a cap on one response. */
        private int maxTokens = 4096;

        /**
         * How long a tool call waits for its run to finish before giving up on
         * WATCHING it. The run itself is not cancelled — it keeps going in
         * core-service, and the operator can still see it. The model is told
         * plainly that the job is still running, which is true, rather than
         * being told it failed, which is not.
         */
        private Duration toolTimeout = Duration.ofMinutes(10);

        /** Gap between polls of a running job. */
        private Duration toolPollInterval = Duration.ofSeconds(3);

        /**
         * How long a run may wait on a human before it is abandoned.
         * Approvals are a human queue; two days covers a weekend, and a run
         * still parked after that is one nobody intends to release.
         *
         * <p>Measured from when the RUN started, not from when it parked —
         * nothing records the park moment, and the run's own start is the
         * closest honest thing there is. It expires slightly early as a
         * result, by however long the agent worked before it asked. The
         * approval itself is never withdrawn: it stays in the inbox, and
         * approving it still starts the automation.
         */
        private Duration approvalTimeout = Duration.ofDays(2);

        /**
         * How often to check whether a parked run's approval has been decided.
         *
         * <p>Bound here so the knob is documented in one place, and read again
         * as a placeholder by {@code AgentApprovalPoller}'s {@code @Scheduled} —
         * an annotation attribute has to be a constant expression, so it cannot
         * take this object. Both read the same key, so they cannot disagree.
         */
        private Duration approvalPollInterval = Duration.ofSeconds(15);

        public Duration getApprovalPollInterval() {
            return approvalPollInterval;
        }

        public void setApprovalPollInterval(Duration approvalPollInterval) {
            this.approvalPollInterval = approvalPollInterval;
        }

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Duration getToolTimeout() {
            return toolTimeout;
        }

        public void setToolTimeout(Duration toolTimeout) {
            this.toolTimeout = toolTimeout;
        }

        public Duration getToolPollInterval() {
            return toolPollInterval;
        }

        public void setToolPollInterval(Duration toolPollInterval) {
            this.toolPollInterval = toolPollInterval;
        }

        public Duration getApprovalTimeout() {
            return approvalTimeout;
        }

        public void setApprovalTimeout(Duration approvalTimeout) {
            this.approvalTimeout = approvalTimeout;
        }
    }

    /** Another AutoOps service reached over its shared-secret internal API. */
    /** A peer with a kill switch, because notifications are optional. */
    public static class Plugin extends Peer {

        private boolean enabled = true;

        public Plugin() {
            super("http://localhost:8088", Duration.ofSeconds(2));
            setConnectTimeout(Duration.ofSeconds(1));
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Peer {

        private String baseUrl;

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout;

        private String internalToken = DEV_INTERNAL_TOKEN;

        public Peer(String baseUrl, Duration readTimeout) {
            this.baseUrl = baseUrl;
            this.readTimeout = readTimeout;
        }

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

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }
    }

    /** Entitlement/quota decisions come from subscription-service. */
    public static class Subscription {

        private String baseUrl = "http://localhost:8082";

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(3);

        /**
         * FAIL-CLOSED by default: when subscription-service is unreachable,
         * mutations are denied. Set true to allow them during outages instead.
         */
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
}
