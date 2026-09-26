package com.intertec.autoops.auth.config;

import com.intertec.autoops.auth.security.JwtAuthFilter;
import com.intertec.autoops.auth.security.RestAuthEntryPoint;
import com.intertec.autoops.auth.security.TenantFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless API filter chain (order 2). The Spring Authorization Server chain
 * (order 1, /oauth2/**) is defined in {@link AuthorizationServerConfig}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/register",
            "/api/auth/register/verify",
            "/api/auth/register/resend",
            "/api/auth/login",
            "/api/auth/password/forgot",
            "/api/auth/password/reset",
            // /api/auth/password/change is NOT public: bearer required.
            "/api/auth/otp/generate",
            "/api/auth/otp/verify",
            "/api/auth/refresh",
            "/api/auth/logout",
            // Machine credential exchange — the API key IS the credential.
            "/api/auth/token/api-key",
            "/api/auth/authorize",   // gateway Basic auth enforced in-controller (GatewayAuthGuard)
            // Keycloak flow + direct Google/Microsoft social login.
            "/api/auth/sso/**",
            // Enterprise SSO: login-page routing + IdP round-trip (config
            // endpoints under /config are NOT public — anyRequest applies).
            "/api/auth/enterprise-sso/resolve",
            "/api/auth/enterprise-sso/initiate",
            "/api/auth/enterprise-sso/callback",
            "/api/auth/webhooks/resend",
            "/api/auth/dev/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/actuator/health",
            "/actuator/info",
            // /actuator/prometheus is intentionally NOT public: scrape it with
            // an authenticated client or from an internal network only.
    };

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      TenantFilter tenantFilter,
                                                      JwtAuthFilter jwtAuthFilter,
                                                      RestAuthEntryPoint entryPoint) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers("/api/auth/onboard", "/api/auth/offboard/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(entryPoint))
                // Tenant filter runs first, then JWT auth (both before the username/password slot).
                .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
