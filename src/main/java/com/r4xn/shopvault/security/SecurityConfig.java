package com.r4xn.shopvault.security;

import com.r4xn.shopvault.config.AppProperties;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API security: bearer JWTs only, no sessions, no cookies (so CSRF protection is not
 * needed), deny-by-default authorisation, and strict response headers.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_AUTH = {
        "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout"
    };
    private static final String[] DOCS = {"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    /**
     * OpenAPI docs and Swagger UI. They are switched off in production (springdoc.*.enabled=false),
     * in which case these paths simply return 404. Swagger UI needs a looser CSP than the API.
     */
    @Bean
    @Order(1)
    SecurityFilterChain docsChain(HttpSecurity http) throws Exception {
        http.securityMatcher(DOCS)
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, DOCS).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .headers(h -> h
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                        + "frame-ancestors 'none'; form-action 'self'")));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(
            HttpSecurity http, AppProperties props, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        ProblemSecurityHandlers problems = new ProblemSecurityHandlers();
        RequestGuardFilter guard = new RequestGuardFilter(props.security().maxRequestBytes());
        RateLimitFilter rateLimit = new RateLimitFilter(props.security().rateLimit());

        http
                // Bearer tokens in the Authorization header are not sent automatically by browsers,
                // so cross-site request forgery is not possible; there is no session to protect.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .anonymous(Customizer.withDefaults())
                .headers(h -> h
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin")))
                .addFilterBefore(guard, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(rateLimit, RequestGuardFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/*",
                                "/api/v1/categories").permitAll()
                        // Authenticated by HMAC signature inside the controller, not by JWT.
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhook").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**",
                                "/actuator/info").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems));
        return http.build();
    }

    /** No origins configured means no cross-origin browser access at all. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", RequestIdFilter.HEADER));
        cors.setExposedHeaders(List.of(RequestIdFilter.HEADER, "Retry-After", "Idempotent-Replayed", "Location"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
