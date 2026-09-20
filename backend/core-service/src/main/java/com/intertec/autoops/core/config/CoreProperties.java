package com.intertec.autoops.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** All core-service settings, kebab-case under {@code autoops.core.*}. */
@ConfigurationProperties("autoops.core")
public class CoreProperties {

    /** auth-service JWKS endpoint for local RS256 validation. */
    private String jwksUri = "http://localhost:8081/oauth2/jwks";

    /** Expected token issuer (must match auth-service's `iss` claim). */
    private String issuer = "autoops-auth-service";

    /**
     * Shared secret this service REQUIRES on its own {@code /internal/**}
     * endpoints — the ones workflow-service and agent-service call.
     */
    private String internalToken = Subscription.DEV_INTERNAL_TOKEN;

    private final Subscription subscription = new Subscription();

    private final Workflow workflow = new Workflow();

    private final Runtime runtime = new Runtime();

    private final Agent agent = new Agent();

    private final Plugin plugin = new Plugin();

    private final Execution execution = new Execution();

    private final Scheduler scheduler = new Scheduler();

    private final Cloud cloud = new Cloud();

    private final Health health = new Health();

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

    public Workflow getWorkflow() {
        return workflow;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public Agent getAgent() {
        return agent;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public Execution getExecution() {
        return execution;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public Cloud getCloud() {
        return cloud;
    }

    public Health getHealth() {
        return health;
    }

    /**
     * Extra probe targets for the provider Platform Health page.
     *
     * <p>Every other service already has a base URL on this class because this
     * service calls it for real work. These two it never calls — they are here
     * only so the health page can report on the whole platform instead of the
     * subset core-service happens to depend on. auth-service is not listed
     * because its base URL is derivable from the JWKS URI we already hold.
     */
    public static class Health {

        private String voiceBaseUrl = "http://localhost:8085";

        private String gatewayBaseUrl = "http://localhost:8080";

        public String getVoiceBaseUrl() {
            return voiceBaseUrl;
        }

        public void setVoiceBaseUrl(String voiceBaseUrl) {
            this.voiceBaseUrl = voiceBaseUrl;
        }

        public String getGatewayBaseUrl() {
            return gatewayBaseUrl;
        }

        public void setGatewayBaseUrl(String gatewayBaseUrl) {
            this.gatewayBaseUrl = gatewayBaseUrl;
        }
    }

    /**
     * plugin-service: outbound notification channels (Slack, Teams, Outlook,
     * Gmail, GitHub, webhook) and the tenant rules that fire them.
     *
     * <p>Timeouts are the tightest of any peer here, and deliberately so. This
     * call sits on the run engine's hot path — every status transition posts
     * one — and it is best-effort: a notification that cannot be sent must
     * never slow a run down, let alone fail it. plugin-service queues the
     * fan-out and returns, so anything beyond a couple of seconds means it is
     * unhealthy and we should give up rather than wait.
     */
    public static class Plugin {

        private String baseUrl = "http://localhost:8088";

        private Duration connectTimeout = Duration.ofSeconds(1);

        private Duration readTimeout = Duration.ofSeconds(2);

        private String internalToken = Subscription.DEV_INTERNAL_TOKEN;

        /** Kill switch: false stops core-service emitting events at all. */
        private boolean enabled = true;

        /**
         * A run still in RUNNING this long after it started is reported as
         * STALLED. Generous by default — a legitimately long deployment must
         * not page anyone — and only ever emitted once per run.
         */
        private Duration stalledAfter = Duration.ofHours(2);

        /**
         * How far past its due time a scheduled job may drift before it counts
         * as MISSED. Must comfortably exceed the scheduler poll interval, or a
         * job that is merely about to fire gets reported as missed.
         */
        private Duration missedAfter = Duration.ofMinutes(10);

        /** How often the watchdog looks for missed and stalled runs. */
        private Duration watchdogInterval = Duration.ofMinutes(5);

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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getStalledAfter() {
            return stalledAfter;
        }

        public void setStalledAfter(Duration stalledAfter) {
            this.stalledAfter = stalledAfter;
        }

        public Duration getMissedAfter() {
            return missedAfter;
        }

        public void setMissedAfter(Duration missedAfter) {
            this.missedAfter = missedAfter;
        }

        public Duration getWatchdogInterval() {
            return watchdogInterval;
        }

        public void setWatchdogInterval(Duration watchdogInterval) {
            this.watchdogInterval = watchdogInterval;
        }
    }

    /**
     * workflow-service: workflow DEFINITIONS moved out of this service, but
     * runs, approvals, governance, compliance and SCM sync still need them.
     */
    /**
     * agent-runtime, which executes native workflow graphs on LangGraph.
     *
     * <p>Replaces the Dify bridge. api-gateway does not route to this service
     * at all — the only caller is core-service over the compose network,
     * holding the shared secret, exactly as agent-service already does.
     */
    public static class Runtime {

        private String baseUrl = "http://localhost:8089";

        private Duration connectTimeout = Duration.ofSeconds(2);

        /**
         * Bounds the gap BETWEEN node events, not the length of the workflow.
         *
         * <p>That distinction is what streaming buys. A blocking call sits on
         * one idle socket for the whole run and dies on the first read timeout;
         * a streamed one has traffic every few seconds, so a workflow may run
         * for an hour under a five-minute timeout as long as it keeps making
         * progress. A node that produces nothing for this long is genuinely
         * stuck.
         */
        private Duration readTimeout = Duration.ofMinutes(5);

        private String internalToken = Subscription.DEV_INTERNAL_TOKEN;

        /** The model a workflow uses when its definition names none. */
        private String defaultModel = "";

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

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }
    }

    public static class Workflow {

        private String baseUrl = "http://localhost:8086";

        private Duration connectTimeout = Duration.ofSeconds(2);

        /** Generous: SCM import writes a project's whole workflow set. */
        private Duration readTimeout = Duration.ofSeconds(10);

        private String internalToken = Subscription.DEV_INTERNAL_TOKEN;

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

    /**
     * agent-service: agents are BUILT by the provider here and rolled out to
     * tenants, so this service needs a write path into agent-service. The only
     * calls are rollout and revoke — reads still belong to agent-service's own
     * API.
     */
    public static class Agent {

        private String baseUrl = "http://localhost:8087";

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(10);

        private String internalToken = Subscription.DEV_INTERNAL_TOKEN;

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

    /** Cloud integrations: credential protection. */
    public static class Cloud {

        public static final String DEV_KEY = "dev-cloud-cred-key";

        /** AES key material for credentials at rest (env CLOUD_CRED_KEY). */
        private String credentialKey = DEV_KEY;

        public String getCredentialKey() {
            return credentialKey;
        }

        public void setCredentialKey(String credentialKey) {
            this.credentialKey = credentialKey;
        }
    }

    /** Run execution: worker pool, executor mode, step timing. */
    public static class Execution {

        /** Concurrent JOB runs; excess queue up. */
        private int poolSize = 4;

        /**
         * Concurrent WORKFLOW runs. Separate from {@link #poolSize} because a
         * workflow holds its thread while it waits on agent-runtime, and a
         * workflow containing a `job` node causes a second run that needs a
         * thread of its own. Sharing one pool deadlocks — see ExecutionConfig.
         */
        private int workflowPoolSize = 4;

        /**
         * {@code simulated} (default) or {@code remote} — remote hands every
         * step to job-service for REAL execution (commands, scripts, ssh, ...).
         */
        private String mode = "simulated";

        /** job-service base URL (remote mode). Internal network only. */
        private String jobServiceUrl = "http://localhost:8084";

        /** Shared secret sent as X-Internal-Token (remote mode). */
        private String jobServiceToken = "dev-internal-token";

        /**
         * The execution engine that REPLACES job-service
         * ({@code autoops.core.execution.mode=rundeck}).
         *
         * <p>This is rundeck-service's address, not Rundeck's own: core-service
         * never speaks to the engine directly. Keeping one hop in between is
         * what lets the engine's URL and admin token stay in a single service
         * that no tenant-facing route reaches.
         */
        private String rundeckServiceUrl = "http://localhost:8090";

        private String rundeckServiceToken = "dev-internal-token";

        /** Per-step wall-clock budget passed to job-service. */
        private Duration stepTimeout = Duration.ofSeconds(60);

        /** Pause between a failed attempt and its retry. */
        private Duration retryDelay = Duration.ofSeconds(2);

        /** Simulated per-step duration bounds (simulated mode only). */
        private Duration simulatedStepMinDelay = Duration.ofMillis(300);

        private Duration simulatedStepMaxDelay = Duration.ofMillis(1500);

        public String getRundeckServiceUrl() {
            return rundeckServiceUrl;
        }

        public void setRundeckServiceUrl(String rundeckServiceUrl) {
            this.rundeckServiceUrl = rundeckServiceUrl;
        }

        public String getRundeckServiceToken() {
            return rundeckServiceToken;
        }

        public void setRundeckServiceToken(String rundeckServiceToken) {
            this.rundeckServiceToken = rundeckServiceToken;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getJobServiceUrl() {
            return jobServiceUrl;
        }

        public void setJobServiceUrl(String jobServiceUrl) {
            this.jobServiceUrl = jobServiceUrl;
        }

        public String getJobServiceToken() {
            return jobServiceToken;
        }

        public void setJobServiceToken(String jobServiceToken) {
            this.jobServiceToken = jobServiceToken;
        }

        public Duration getStepTimeout() {
            return stepTimeout;
        }

        public void setStepTimeout(Duration stepTimeout) {
            this.stepTimeout = stepTimeout;
        }

        public Duration getRetryDelay() {
            return retryDelay;
        }

        public void setRetryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
        }

        public int getWorkflowPoolSize() {
            return workflowPoolSize;
        }

        public void setWorkflowPoolSize(int workflowPoolSize) {
            this.workflowPoolSize = workflowPoolSize;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public Duration getSimulatedStepMinDelay() {
            return simulatedStepMinDelay;
        }

        public void setSimulatedStepMinDelay(Duration simulatedStepMinDelay) {
            this.simulatedStepMinDelay = simulatedStepMinDelay;
        }

        public Duration getSimulatedStepMaxDelay() {
            return simulatedStepMaxDelay;
        }

        public void setSimulatedStepMaxDelay(Duration simulatedStepMaxDelay) {
            this.simulatedStepMaxDelay = simulatedStepMaxDelay;
        }
    }

    /** Cron scheduler for jobs with a schedule. */
    public static class Scheduler {

        private boolean enabled = true;

        private Duration pollInterval = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }
    }

    /** Entitlement/quota decisions come from subscription-service. */
    public static class Subscription {

        private String baseUrl = "http://localhost:8082";

        /** Bounded timeouts — a slow subscription-service must never hang a create. */
        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(3);

        /**
         * FAIL-CLOSED by default: when subscription-service is unreachable,
         * mutations are denied. Set true to allow them during outages instead.
         */
        private boolean entitlementFailOpen = false;

        public static final String DEV_INTERNAL_TOKEN = "dev-internal-token";

        /**
         * Shared secret for the scheduler's tenant-scoped entitlement checks
         * (subscription-service {@code /internal/**}). Env
         * SUBSCRIPTION_INTERNAL_TOKEN; prod refuses the dev default.
         */
        private String internalToken = DEV_INTERNAL_TOKEN;

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

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }
    }
}
