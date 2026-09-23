package com.intertec.autoops.rundeck.security;

import com.intertec.autoops.rundeck.config.RundeckProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Every {@code /internal/**} call must present the shared platform token in
 * {@code X-Internal-Token}. These endpoints let core-service run a Rundeck job
 * as a workflow step; the gateway routes nothing to them.
 *
 * <p>The bar is higher here than in the other services that carry this filter.
 * A caller who reaches {@code /internal/rundeck/dispatch} without a token would
 * be able to run any job on any tenant's fleet, choosing the tenant themselves —
 * so the check is constant-time and happens before any controller is resolved.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final RundeckProperties properties;

    public InternalTokenFilter(RundeckProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String expected = properties.getInternalToken();
        String presented = request.getHeader(HEADER);
        // A BLANK expected token refuses everything, and that guard is not
        // decoration. Without it a service whose token is unconfigured accepts
        // `X-Internal-Token:` with an empty value, because
        // constantTimeEquals("", "") is true — so losing the configuration
        // OPENS the internal surface instead of closing it, which is the wrong
        // direction for the one credential that lets a caller roll out agents,
        // revoke them and read sealed personas.
        if (expected == null || expected.isBlank()
                || presented == null || !constantTimeEquals(presented, expected)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_internal_token\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
