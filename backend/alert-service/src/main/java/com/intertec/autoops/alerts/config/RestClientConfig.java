package com.intertec.autoops.alerts.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * One RestClient per peer, each with BOUNDED connect/read timeouts.
 *
 * <p>The engine runs on the compose network and is normally fast, which is
 * exactly why the timeouts matter: a console page that lists alerts is on the
 * hot path of someone reacting to an outage, and the one time the engine is
 * wedged is the one time that page must fail in seconds rather than hang.
 */
@Configuration
public class RestClientConfig {

    @Bean("pluginRestClient")
    public RestClient pluginRestClient(AlertProperties properties) {
        AlertProperties.Plugin plugin = properties.getPlugin();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) plugin.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) plugin.getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(plugin.getUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean("engineRestClient")
    public RestClient engineRestClient(AlertProperties properties) {
        AlertProperties.Engine engine = properties.getEngine();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) engine.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) engine.getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(engine.getUrl())
                .requestFactory(factory)
                .build();
    }
}
