package com.intertec.autoops.gateway.config;

import com.intertec.autoops.gateway.security.RateLimitFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The rate limiter, wired only when it can actually work.
 *
 * <p>Two conditions, and both are about failing honestly rather than being
 * clever. {@link ConditionalOnBean} on {@code StringRedisTemplate} means a
 * deployment without Redis configured simply has no limiter, instead of one
 * that logs a connection failure on every single request and allows it anyway
 * — the same outcome, buried under noise that hides real Redis incidents.
 *
 * <p>The property switch exists so the limiter can be turned off in an
 * emergency without a rebuild. If a bad limit is ever shipped, the person on
 * call needs one environment variable, not a deploy.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnProperty(value = "autoops.gateway.rate-limit.enabled", havingValue = "true",
            matchIfMissing = true)
    public RateLimitFilter rateLimitFilter(StringRedisTemplate redis,
                                           GatewayProperties properties) {
        return new RateLimitFilter(redis, properties.getRateLimit());
    }
}
