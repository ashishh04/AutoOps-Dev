package com.intertec.autoops.gateway.config;

import com.intertec.autoops.gateway.security.RateLimitFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The rate limiter, wired only when it can actually work.
 *
 * <p>The property switch exists so the limiter can be turned off in an
 * emergency without a rebuild. If a bad limit is ever shipped, the person on
 * call needs one environment variable, not a deploy.
 *
 * <p><b>There is deliberately no {@code @ConditionalOnBean(StringRedisTemplate)}
 * here, and there was.</b> It silently disabled the limiter entirely: a
 * {@code @ConditionalOnBean} in a user configuration is evaluated while user
 * configuration is parsed, which happens BEFORE auto-configuration registers
 * {@code StringRedisTemplate} — so the condition never held and the filter bean
 * was never created. Nothing failed and nothing logged; requests simply were
 * not counted. Spring's own documentation restricts that annotation to
 * auto-configuration classes for exactly this reason.
 *
 * <p>The template is safe to require instead: spring-boot-starter-data-redis is
 * a hard dependency of this service, so the bean always exists. Whether Redis
 * ANSWERS is a separate question, and {@link RateLimitFilter} already fails
 * open on it.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    @ConditionalOnProperty(value = "autoops.gateway.rate-limit.enabled", havingValue = "true",
            matchIfMissing = true)
    public RateLimitFilter rateLimitFilter(StringRedisTemplate redis,
                                           GatewayProperties properties) {
        return new RateLimitFilter(redis, properties.getRateLimit());
    }
}
