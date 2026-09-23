package com.intertec.autoops.alerts.security;

import com.intertec.autoops.alerts.config.AlertProperties;
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
 * Guards the one {@code /internal/**} surface this service has.
 *
 * <p>It had none until now, and the class comment on {@code SecurityConfig}
 * said so as a statement of fact: nothing in the platform called alert-service,
 * because the console was its only consumer. That changed when the escalation
 * agent needed to read incidents — an agent cannot reach a console, and the
 * incident engine is behind this service rather than in front of it.
 *
 * <p>Copied deliberately from core-service's filter of the same name rather
 * than generalised into a shared module. Two services with one shared secret
 * and one shared filter is a single blast radius; the duplication is thirty
 * lines and the alternative is a library that every service must upgrade in
 * step.
 *
 * <p>The gateway routes nothing here, so this is defence-in-depth inside the
 * compose network rather than an end-user auth scheme.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final AlertProperties properties;

    public InternalTokenFilter(AlertProperties properties) {
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
        // An unset token refuses everything rather than accepting everything.
        // A misconfigured service that silently opens its internal surface is
        // the failure this whole check exists to prevent.
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
