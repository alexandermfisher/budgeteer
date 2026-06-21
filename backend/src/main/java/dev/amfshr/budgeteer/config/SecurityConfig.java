package dev.amfshr.budgeteer.config;

import dev.amfshr.budgeteer.security.JweAuthenticationFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security configuration for the application.
 * Configures JWE token-based authentication via HttpOnly cookies,
 * production security headers (HSTS, CSP), CORS, and request authorization.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // style-src 'unsafe-inline' is required for Tailwind — remove when adopting a nonce or build output
    private static final String CSP_POLICY =
        "default-src 'none'; "
        + "script-src 'self'; "
        + "style-src 'self' 'unsafe-inline'; "
        + "connect-src 'self'; "
        + "img-src 'self' data:; "
        + "font-src 'self'; "
        + "base-uri 'self'; "
        + "form-action 'self'; "
        + "frame-ancestors 'none'";

    private final JweAuthenticationFilter jweAuthenticationFilter;
    private final List<String> corsAllowedOrigins;

    /**
     * Constructs the security configuration.
     *
     * @param jweAuthenticationFilter the JWE token authentication filter
     * @param corsAllowedOrigins      allowed CORS origins from {@code app.cors.allowed-origins}
     */
    public SecurityConfig(
            JweAuthenticationFilter jweAuthenticationFilter,
            @Value("${app.cors.allowed-origins}") List<String> corsAllowedOrigins) {
        this.jweAuthenticationFilter = jweAuthenticationFilter;
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    /**
     * Configures the security filter chain.
     *
     * @param http the HTTP security builder
     * @return the configured security filter chain
     * @throws Exception if configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .addFilterBefore(jweAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/auth/verify").permitAll()
                .requestMatchers("/api/auth/refresh").permitAll()
                .requestMatchers("/api/auth/logout").permitAll()
                .requestMatchers("/api/monzo/callback").permitAll()
                .requestMatchers("/api/health/**").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/actuator/info").permitAll()
                .requestMatchers("/actuator/metrics/**").authenticated()
                .requestMatchers("/actuator/prometheus").authenticated()
                .requestMatchers("/actuator/**").authenticated()
                .requestMatchers("/api/dev/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            // CSRF disabled — SameSite=Lax cookies + JSON-only API prevent cross-site forgery
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(content -> {})
                .xssProtection(xss -> {})
                // AnyRequestMatcher required: TLS terminates at Cloudflare, so request.isSecure()
                // is always false on the internal Docker hop — the default SecureRequestMatcher
                // would suppress HSTS entirely.
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31536000)
                    .includeSubDomains(true)
                    .requestMatcher(AnyRequestMatcher.INSTANCE))
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives(CSP_POLICY))
            );

        return http.build();
    }

    /**
     * CORS configuration source.
     * Dev allows {@code localhost:3000} (Vite dev server); prod allows {@code budgeteer.dev} only.
     * Never uses wildcard — required for credentialed requests (HttpOnly cookies).
     *
     * @return the configured CORS source
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsAllowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
