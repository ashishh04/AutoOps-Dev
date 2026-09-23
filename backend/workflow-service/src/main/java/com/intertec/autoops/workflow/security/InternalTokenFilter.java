package com.intertec.autoops.workflow.security;

import com.intertec.autoops.workflow.config.WorkflowProperties;
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
 * {@code X-Internal-Token}. These endpoints serve core-service (runs,
 * approvals, governance, SCM, compliance) and agent-service (tool validation);
 * the gateway routes nothing to them, so the token is defence-in-depth inside
 * the compose network, not an end-user auth scheme.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final WorkflowProperties properties;

    public InternalTokenFilter(WorkflowProperties properties) {
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
