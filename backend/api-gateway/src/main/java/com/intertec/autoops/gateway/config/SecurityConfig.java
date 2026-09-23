package com.intertec.autoops.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import com.intertec.autoops.gateway.security.RateLimitFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Gateway security: coarse authentication at the edge.
 *
 * <ul>
 *   <li>Auth flows and the public plan catalog pass through anonymously —
 *       auth-service enforces its own rules.</li>
 *   <li>Every other route requires a valid AutoOps RS256 access token,
 *       verified locally against auth-service's JWKS (kid-aware, cached).</li>
 *   <li>Fine-grained authorization (roles, entitlements) stays in the
 *       downstream services; the gateway never makes business decisions.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final GatewayProperties properties;

    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(GatewayProperties properties,
                          @Autowired(required = false) RateLimitFilter rateLimitFilter) {
        this.properties = properties;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Anonymous flows — enforced by auth-service itself.
                        .requestMatchers("/api/auth/**", "/oauth2/**").permitAll()
                        // Public plan catalog for the pricing page.
                        .requestMatchers(HttpMethod.GET, "/api/plans", "/api/plans/**").permitAll()
                        // Aegis-01 on the landing page: the visitor has no
                        // account yet, so there is no token to require.
                        // voice-agent rate-limits these itself, which is what
                        // protects the ElevenLabs credits behind them.
                        .requestMatchers(HttpMethod.GET, "/api/voice/config").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/voice/session").permitAll()
                        // Inbound webhook triggers — the unguessable token is
                        // the credential; core-service validates it.
                        .requestMatchers(HttpMethod.POST, "/api/hooks/**").permitAll()
                        // Inbound alerts from a customer's monitoring tool.
                        // Datadog has no AutoOps login; the signed X-API-KEY
                        // token alert-service verifies IS the credential, the
                        // same arrangement as /api/hooks/** above.
                        .requestMatchers(HttpMethod.POST, "/api/alerts/ingest/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(
                                    "{\"error\":\"unauthorized\",\"message\":\"A valid access token is required\"}");
                        })
                        .jwt(Customizer.withDefaults()));

        // AFTER authorisation, not before. The bucket is keyed on the tenant in
        // the JWT, and a filter placed ahead of the resource server would run
        // before that token is parsed — so every authenticated request would
        // fall back to the caller's IP and a whole office behind one NAT would
        // share a single budget.
        //
        // Registered only when Redis is actually wired: a limiter with no
        // counter store would log a warning on every request forever.
        if (rateLimitFilter != null) {
            http.addFilterAfter(rateLimitFilter, AuthorizationFilter.class);
        }
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwksUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuer()),
                accessTokenOnly()));
        return decoder;
    }

    /**
     * Refresh tokens are also RS256-signed by auth-service; only tokens minted
     * with {@code tokenType=access} may cross the gateway. Package-private so
     * the rule is unit-testable without a JWKS fetch.
     */
    static OAuth2TokenValidator<Jwt> accessTokenOnly() {
        return token -> "access".equals(token.getClaimAsString("tokenType"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "Not an AutoOps access token", null));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getCorsAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Tenant-ID", "X-Device-ID"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}