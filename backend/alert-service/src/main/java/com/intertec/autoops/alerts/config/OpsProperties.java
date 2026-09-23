package com.intertec.autoops.alerts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The investigation engine — separate from {@link AlertProperties} on purpose.
 *
 * <p>Correlation and root-cause analysis are a different capability from the
 * alert plane: the alert engine is always present, while this one is optional,
 * costs money per call, and can be absent without any alerts page degrading.
 * Keeping the configuration apart makes "is investigation switched on?" a
 * single, answerable question.
 */
@ConfigurationProperties(prefix = "autoops.ops")
public class OpsProperties {

    private Holmes holmes = new Holmes();
    private Agent agent = new Agent();

    /** AutoOps's own agents, used to investigate estates the engine cannot see. */
    public static class Agent {
        private String baseUrl = "http://agent-service:8087";
        /**
         * Matched by NAME because agent-service does not expose an agent's
         * graph ref. Configurable so a rename is one variable rather than
         * investigation silently falling back to an engine with no AWS tools.
         */
        private String rcaAgentName = "AWS Incident RCA Analyst";
        private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(3);
        private java.time.Duration readTimeout = java.time.Duration.ofSeconds(15);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getRcaAgentName() {
            return rcaAgentName;
        }

        public void setRcaAgentName(String rcaAgentName) {
            this.rcaAgentName = rcaAgentName;
        }

        public java.time.Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(java.time.Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public java.time.Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(java.time.Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public static class Holmes {
        /** Blank disables investigation entirely — the console hides it rather than erroring. */
        private String url = "";
        private String apiKey = "";
        private Duration connectTimeout = Duration.ofSeconds(5);
        /**
         * Generous by necessity. A real investigation runs a dozen tools and
         * several model round trips; the reference run took 24s and a cold one
         * can take minutes. Still bounded — this call is never made on a page
         * load, only on an explicit "investigate".
         */
        private Duration readTimeout = Duration.ofMinutes(5);

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
            return url != null && !url.isBlank();
        }
    }

    public Holmes getHolmes() {
        return holmes;
    }

    public void setHolmes(Holmes holmes) {
        this.holmes = holmes;
    }
}
