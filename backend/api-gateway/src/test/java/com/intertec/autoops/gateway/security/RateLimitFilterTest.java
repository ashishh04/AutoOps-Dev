package com.intertec.autoops.gateway.security;

import com.intertec.autoops.gateway.config.GatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One customer must not be able to exhaust the platform — and the limiter must
 * not be able to take the platform down either.
 *
 * <p>The second half is the one worth testing hardest. A rate limiter that
 * answers 503 when its counter store blips has converted a Redis incident into
 * a total outage; it would be the most effective denial of service in the
 * system. So the fail-open path gets as much attention here as the limit.
 */
class RateLimitFilterTest {

    private StringRedisTemplate redis;
    private GatewayProperties.RateLimit config;
    private RateLimitFilter filter;
    private final List<String> keysSeen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        config = new GatewayProperties.RateLimit();
        config.setRequests(3);
        config.setWindow(Duration.ofMinutes(1));
        filter = new RateLimitFilter(redis, config);
        keysSeen.clear();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Each call returns the next count, as INCR would. */
    private void countsRunUpTo(long... counts) {
        var remaining = new ArrayList<Long>();
        for (long c : counts) {
            remaining.add(c);
        }
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenAnswer(call -> {
                    keysSeen.addAll(call.getArgument(1));
                    return remaining.remove(0);
                });
    }

    private void authenticatedAs(String tenant) {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("someone@example.com")
                .claim("tenantId", tenant)
                .build();
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new JwtAuthenticationToken(jwt, Collections.emptyList())));
    }

    private MockHttpServletResponse run(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("10.0.0.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void allowsRequestsInsideTheBudget() throws Exception {
        countsRunUpTo(1, 2, 3);
        authenticatedAs("acme");

        for (int i = 0; i < 3; i++) {
            assertEquals(200, run("/api/jobs").getStatus());
        }
    }

    @Test
    void refusesTheRequestThatExceedsTheBudget() throws Exception {
        countsRunUpTo(4);
        authenticatedAs("acme");

        MockHttpServletResponse response = run("/api/jobs");

        assertEquals(429, response.getStatus());
        // Retry-After is what makes a 429 actionable. Without it a client
        // either gives up or retries immediately, and the second makes the
        // problem worse at exactly the moment the platform is under load.
        assertEquals("60", response.getHeader("Retry-After"));
    }

    @Test
    void allowsTheRequestWhenRedisIsUnreachable() throws Exception {
        // THE test. A limiter that refuses traffic when its bookkeeping store
        // is down has turned a Redis incident into a platform outage.
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenThrow(new RedisConnectionFailureException("redis is gone"));
        authenticatedAs("acme");

        assertEquals(200, run("/api/jobs").getStatus());
    }

    @Test
    void allowsTheRequestWhenRedisAnswersWithNothing() throws Exception {
        // A null from execute() is not an error and not a count. Treating it as
        // a large number would refuse the request; treating it as the count
        // zero is the same fail-open decision as an exception.
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenReturn(null);
        authenticatedAs("acme");

        assertEquals(200, run("/api/jobs").getStatus());
    }

    @Test
    void budgetsByTenantRatherThanByUser() throws Exception {
        // Keyed on the user, a customer with fifty seats would get fifty times
        // the budget — which is precisely the exhaustion this exists to stop.
        countsRunUpTo(1);
        authenticatedAs("acme");

        run("/api/jobs");

        assertTrue(keysSeen.get(0).contains("tenant:acme"),
                "expected a tenant bucket, got " + keysSeen.get(0));
        assertTrue(!keysSeen.get(0).contains("someone@example.com"));
    }

    @Test
    void fallsBackToThePeerAddressForUnauthenticatedTraffic() throws Exception {
        // Webhook and alert-ingest calls carry no token. They still need a
        // bucket, or the one surface reachable without credentials is the one
        // surface with no limit.
        countsRunUpTo(1);

        run("/api/hooks/abc");

        assertTrue(keysSeen.get(0).contains("ip:10.0.0.7"), keysSeen.get(0));
    }

    @Test
    void ignoresACallerSuppliedForwardedForHeader() throws Exception {
        // X-Forwarded-For is caller-supplied. Trusting it would let anyone mint
        // an unlimited number of buckets by varying one string — a limiter that
        // is trivially bypassed by the traffic it is meant to limit.
        countsRunUpTo(1);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/hooks/abc");
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertTrue(keysSeen.get(0).contains("ip:10.0.0.7"), keysSeen.get(0));
    }

    @Test
    void putsTheWindowInTheKeySoABoundaryBurstCannotDoubleTheLimit() throws Exception {
        // With the window only as a TTL, a burst spanning the boundary is
        // counted against one bucket whose expiry keeps being pushed out — the
        // classic sliding-window mistake that lets through roughly twice the
        // limit.
        countsRunUpTo(1, 1);
        authenticatedAs("acme");
        config.setWindow(Duration.ofMillis(1));

        run("/api/jobs");
        Thread.sleep(5);
        run("/api/jobs");

        assertEquals(2, keysSeen.size());
        assertTrue(!keysSeen.get(0).equals(keysSeen.get(1)),
                "two windows must not share a bucket: " + keysSeen);
    }

    @Test
    void neverLimitsHealthChecks() throws Exception {
        // Rate-limiting health gets the gateway killed by its own orchestrator
        // under exactly the load the limiter exists to survive.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertTrue(keysSeen.isEmpty(), "health must not touch Redis");
    }

    @Test
    void neverLimitsTheLoginPath() throws Exception {
        // auth-service throttles credential attempts itself, and it can do it
        // properly: the requests that matter arrive with no tenant on them, so
        // a per-tenant bucket here would put every failed login on the planet
        // into one shared counter.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertTrue(keysSeen.isEmpty());
    }

    @Test
    void reportsTheBudgetOnEveryAnsweredRequest() throws Exception {
        countsRunUpTo(1);
        authenticatedAs("acme");

        MockHttpServletResponse response = run("/api/jobs");

        assertEquals("3", response.getHeader("X-RateLimit-Limit"));
        assertEquals("2", response.getHeader("X-RateLimit-Remaining"));
    }

    @Test
    void doesNothingAtAllWhenDisabled() throws Exception {
        config.setEnabled(false);

        MockHttpServletResponse response = run("/api/jobs");

        assertEquals(200, response.getStatus());
        assertTrue(keysSeen.isEmpty(), "a disabled limiter must not call Redis");
        assertNull(response.getHeader("X-RateLimit-Limit"));
    }

    @Test
    void theScriptSetsAnExpiryInTheSameStepAsTheIncrement() {
        // Two round trips leave a window where a process dying between them
        // leaves a key with no TTL — and that key holds a customer at their
        // limit permanently, with no symptom but a support ticket.
        String script = new RateLimitFilterScriptProbe().script();
        assertTrue(script.contains("INCR"), script);
        assertTrue(script.contains("PEXPIRE"), script);
        assertTrue(script.indexOf("INCR") < script.indexOf("PEXPIRE"));
    }

    /** Reaches the private script so the atomicity above can be asserted. */
    private static final class RateLimitFilterScriptProbe {
        String script() {
            try {
                var field = RateLimitFilter.class.getDeclaredField("COUNT_IN_WINDOW");
                field.setAccessible(true);
                return ((RedisScript<?>) field.get(null)).getScriptAsString();
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError("the limiter's Lua script moved", ex);
            }
        }
    }
}
