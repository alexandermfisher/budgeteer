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
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(content -> {})
                .xssProtection(xss -> {})
            );
        
        return http.build();
    }
}
