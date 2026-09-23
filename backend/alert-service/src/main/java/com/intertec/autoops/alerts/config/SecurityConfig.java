package com.intertec.autoops.alerts.config;

import com.intertec.autoops.alerts.security.InternalTokenFilter;
import com.intertec.autoops.alerts.security.RestAuthEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Stateless resource server on the same auth-service RS256 tokens as every
 * other service.
 *
 * <p>VIEWER is read-only, enforced once here at the edge, the same way
 * rundeck-service does it. Everything about an alert is a GET and any role may
 * read; the writes are on {@code /api/alert-providers}, and connecting a
 * monitoring source means handing a third party's credentials to the platform —
 * not something a read-only role does.
 *
 * <p>There IS now one {@code /internal/**} surface, guarded by
 * {@link com.intertec.autoops.alerts.security.InternalTokenFilter} and routed
 * to by nothing in the gateway. It exists because the escalation agent has to
 * read incidents, and an agent cannot hold a user token — this service sits in
 * front of the incident engine, so there is no way round it. Everything else
 * here is still console-only, through the gateway, with a user token.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Every role except VIEWER may change state. */
    private static final String[] WRITER_ROLES = {"ADMIN", "CLIENT", "PROVIDER"};

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RestAuthEntryPoint entryPoint,
                                                   InternalTokenFilter internalTokenFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(internalTokenFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // permitAll here means "no USER token"; InternalTokenFilter
                        // has already refused anything without the platform token.
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // PUBLIC by design: a customer's monitoring tool has no
                        // AutoOps login. The signed X-API-KEY token is the whole
                        // credential, exactly as /api/hooks/{token} works. It
                        // must precede the writer rule below, which would
                        // otherwise demand a JWT Datadog cannot have.
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/alerts/ingest/**").permitAll()
                        // VIEWER is read-only, enforced once here at the edge.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/**")
                        .hasAnyRole(WRITER_ROLES)
                        .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/**")
                        .hasAnyRole(WRITER_ROLES)
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/**")
                        .hasAnyRole(WRITER_ROLES)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(entryPoint));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(AlertProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwksUri()).build();
        OAuth2TokenValidator<Jwt> accessTokenOnly = token ->
                "access".equals(token.getClaimAsString("tokenType"))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                "invalid_token", "Not an AutoOps access token", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuer()),
                accessTokenOnly));
        return decoder;
    }

    /** Maps the auth-service `role` claim (ADMIN|CLIENT|PROVIDER|VIEWER) to ROLE_*. */
    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            String role = jwt.getClaimAsString("role");
            return new JwtAuthenticationToken(jwt,
                    role != null ? List.of(new SimpleGrantedAuthority("ROLE_" + role)) : List.of(),
                    jwt.getSubject());
        };
    }
}
