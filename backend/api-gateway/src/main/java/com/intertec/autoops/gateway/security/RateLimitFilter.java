package com.intertec.autoops.gateway.security;

import com.intertec.autoops.gateway.config.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * One customer cannot exhaust the platform.
 *
 * <h2>Why this is hand-written</h2>
 * Spring Cloud Gateway ships {@code RequestRateLimiter} with a Redis backend,
 * and it is reactive-only. This gateway is {@code spring-cloud-starter-gateway-mvc}
 * — the servlet flavour — so that filter does not exist here. The alternative
 * to writing one was rewriting the gateway onto WebFlux, which is a far larger
 * change than a fixed-window counter.
 *
 * <h2>Why Redis rather than an in-process counter</h2>
 * A counter held in the JVM is per-replica: two gateway instances mean twice
 * the intended limit, and the limit silently changes every time the deployment
 * is scaled. Redis is already in the stack for exactly this class of shared
 * state.
 *
 * <h2>It fails OPEN, on purpose</h2>
 * If Redis is unreachable the request is allowed. A rate limiter that returns
 * 503 when its bookkeeping store blips has converted a Redis incident into a
 * total platform outage — it would be the most effective denial of service in
 * the system. The same reasoning as tracing: a facility that observes or
 * protects traffic must never be able to take down the traffic.
 *
 * <h2>What a bucket is keyed on</h2>
 * The TENANT, falling back to the authenticated subject, falling back to the
 * client address. Keying on the user would let one customer with fifty seats
 * take fifty times the budget; keying only on IP would put a whole office
 * behind one NAT into a single bucket. The tenant is the unit the product is
 * sold in, so it is the unit the budget belongs to.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /**
     * INCR and EXPIRE as one atomic step.
     *
     * <p>Doing it in two round trips leaves a window where a process dying
     * between them leaves a key with no TTL — and that key then holds a
     * customer at their limit permanently, with no way to notice except a
     * support ticket.
     */
    private static final RedisScript<Long> COUNT_IN_WINDOW = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final GatewayProperties.RateLimit config;

    public RateLimitFilter(StringRedisTemplate redis, GatewayProperties.RateLimit config) {
        this.redis = redis;
        this.config = config;
    }

    /**
     * Health and identity are never limited.
     *
     * <p>Rate-limiting the health endpoint gets the gateway killed by its own
     * orchestrator under exactly the load the limiter exists to survive. Login
     * is excluded for a different reason: auth-service throttles credential
     * attempts itself, and a generic per-tenant bucket here cannot — the
     * requests that matter arrive with no tenant on them at all.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!config.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return path.startsWith("/actuator/")
                || path.startsWith("/api/auth/")
                || path.startsWith("/oauth2/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String bucket = bucketFor(request);
        long used;
        try {
            Long count = redis.execute(COUNT_IN_WINDOW, List.of(bucket),
                    String.valueOf(config.getWindow().toMillis()));
            used = count == null ? 0 : count;
        } catch (Exception ex) {
            // Allowed, and logged at WARN rather than ERROR: the platform is
            // working, the limiter is not.
            log.warn("Rate limiter could not reach Redis; allowing the request: {}",
                    ex.getMessage());
            chain.doFilter(request, response);
            return;
        }

        long remaining = Math.max(0, config.getRequests() - used);
        response.setHeader("X-RateLimit-Limit", String.valueOf(config.getRequests()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));

        if (used > config.getRequests()) {
            long retryAfter = Math.max(1, config.getWindow().toSeconds());
            response.setStatus(429);
            // Retry-After is what makes a 429 actionable rather than a mystery:
            // a client that is not told when to come back either gives up or
            // retries immediately, and the second makes the problem worse.
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"rate_limited\",\"message\":\"Too many requests. "
                            + "Retry in " + retryAfter + "s.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Which budget this request spends from.
     *
     * <p>The window is part of the KEY, not just the TTL. With the window only
     * as an expiry, a burst arriving as one window ends and another begins is
     * counted against a single bucket whose TTL keeps being refreshed — the
     * classic sliding-window mistake that lets through roughly double the
     * limit at a boundary.
     */
    private String bucketFor(HttpServletRequest request) {
        long window = Math.max(1, config.getWindow().toMillis());
        long slot = System.currentTimeMillis() / window;
        return "ratelimit:" + identity(request) + ":" + slot;
    }

    private String identity(HttpServletRequest request) {
        if (SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            String tenant = jwt.getClaimAsString("tenantId");
            if (tenant != null && !tenant.isBlank()) {
                return "tenant:" + tenant;
            }
            if (jwt.getSubject() != null) {
                return "sub:" + jwt.getSubject();
            }
        }
        // Unauthenticated traffic — webhook and alert ingest — falls back to
        // the peer address. Deliberately NOT X-Forwarded-For: that header is
        // caller-supplied, and trusting it here would let anyone mint an
        // unlimited number of buckets by varying one string.
        return "ip:" + request.getRemoteAddr();
    }
}
