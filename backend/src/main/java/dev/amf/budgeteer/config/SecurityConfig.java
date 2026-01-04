package dev.amf.budgeteer.config;

import dev.amf.budgeteer.security.JweAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration for the application.
 * Configures JWE token-based authentication via HttpOnly cookies.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JweAuthenticationFilter jweAuthenticationFilter;

    public SecurityConfig(JweAuthenticationFilter jweAuthenticationFilter) {
        this.jweAuthenticationFilter = jweAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless session - we use JWE tokens in cookies
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            
            // Add JWE authentication filter
            .addFilterBefore(jweAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            
            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public authentication endpoints
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/auth/verify").permitAll()
                .requestMatchers("/api/auth/refresh").permitAll()
                
                // Monzo OAuth endpoints (public for OAuth flow)
                .requestMatchers("/api/monzo/oauth/**").permitAll()
                
                // Health check endpoints (public for monitoring/load balancers)
                .requestMatchers("/api/health/**").permitAll()
                
                // Test endpoints (dev only)
                .requestMatchers("/api/test/**").permitAll()
                
                // Error page
                .requestMatchers("/error").permitAll()
                
                // All other API endpoints require authentication
                .requestMatchers("/api/**").authenticated()
                
                // Everything else is permitted (static resources, etc.)
                .anyRequest().permitAll()
            )
            
            // CSRF configuration - disabled for API endpoints using cookies with SameSite
            // SameSite=Strict cookies provide CSRF protection
            .csrf(csrf -> csrf.disable())
            
            // Security headers
            // Current: Basic headers for clickjacking and MIME sniffing protection
            // 
            // TODO: Add these headers in production (see Infrastructure task in tasks.md):
            // - Strict-Transport-Security (HSTS): Force HTTPS, prevent downgrade attacks
            //   .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000).includeSubDomains(true))
            // - Content-Security-Policy (CSP): Control resource loading, prevent XSS
            //   Add via: .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("...")))
            // - Referrer-Policy: Control referrer info leakage
            //   Add via custom header filter
            // - Permissions-Policy: Disable browser features you don't use (camera, microphone, etc.)
            //
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())           // Prevent clickjacking
                .contentTypeOptions(content -> {})             // Prevent MIME sniffing
                .xssProtection(xss -> {})                      // Legacy XSS filter (browsers mostly ignore now)
            );
        
        return http.build();
    }
}
