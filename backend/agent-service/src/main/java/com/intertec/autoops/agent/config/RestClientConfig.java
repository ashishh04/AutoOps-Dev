package com.intertec.autoops.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * One RestClient per peer service, each with BOUNDED connect/read timeouts —
 * a slow peer must never hang a workflow call. Splitting workflows out of
 * core-service means three of these calls now sit on the request path, so the
 * budgets matter more than they did in the monolith.
 */
@Configuration
public class RestClientConfig {

    @Bean("subscriptionRestClient")
    public RestClient subscriptionRestClient(AgentProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getSubscription().getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getSubscription().getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(properties.getSubscription().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean("coreRestClient")
    public RestClient coreRestClient(AgentProperties properties) {
        return peerClient(properties.getCore());
    }

    @Bean("workflowRestClient")
    public RestClient workflowRestClient(AgentProperties properties) {
        return peerClient(properties.getWorkflow());
    }

    /**
     * The Python reasoning runtime.
     *
     * <p>Built exactly like the others, but its timeout budget comes from
     * {@code autoops.agent.runtime.read-timeout} and is measured in minutes —
     * see {@link AgentProperties#getRuntime()}. It runs on the loop's own
     * executor, never on a request thread, so a long call here holds nothing
     * a user is waiting on.
     */
    @Bean("pluginRestClient")
    public RestClient pluginRestClient(AgentProperties properties) {
        return peerClient(properties.getPlugin());
    }

    @Bean("runtimeRestClient")
    public RestClient runtimeRestClient(AgentProperties properties) {
        return peerClient(properties.getRuntime());
    }

    private static RestClient peerClient(AgentProperties.Peer peer) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) peer.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) peer.getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(peer.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
