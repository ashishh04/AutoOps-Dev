package com.intertec.autoops.alerts.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Clients for the incident surface.
 *
 * <p>The engine client is a SECOND client onto the same alert engine, with its
 * own timeouts, rather than a change to the one the alert pages use. Incident
 * work is heavier — an evidence fetch walks a whole correlation — and a slow
 * incident page must not be able to starve the alerts list of request threads.
 * Both read their address from the same {@link AlertProperties}, so the engine
 * still has exactly one configured location.
 */
@Configuration
@EnableConfigurationProperties(OpsProperties.class)
public class OpsClientConfig {

    @Bean("incidentEngineRestClient")
    public RestClient incidentEngineRestClient(AlertProperties properties) {
        AlertProperties.Engine engine = properties.getEngine();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) engine.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) engine.getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(engine.getUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean("agentServiceRestClient")
    public RestClient agentServiceRestClient(OpsProperties properties) {
        OpsProperties.Agent agent = properties.getAgent();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) agent.getConnectTimeout().toMillis());
        // Short on purpose: these calls START a run and READ its progress, they
        // never wait for it. The run itself takes minutes and is polled.
        factory.setReadTimeout((int) agent.getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(agent.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean("holmesRestClient")
    public RestClient holmesRestClient(OpsProperties properties) {
        OpsProperties.Holmes holmes = properties.getHolmes();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) holmes.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) holmes.getReadTimeout().toMillis());
        // No base URL when disabled: the bean must still exist for injection,
        // and every call site checks isEnabled() before using it.
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (holmes.isEnabled()) {
            builder = builder.baseUrl(holmes.getUrl());
        }
        return builder.build();
    }
}
