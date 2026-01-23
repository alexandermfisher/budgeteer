package dev.amf.budgeteer.security;

import dev.amf.budgeteer.service.CookieService;
import dev.amf.budgeteer.service.JweTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Security filter that extracts and validates JWE access tokens from cookies.
 * Sets the Spring Security context when a valid token is found.
 */
@Component
public class JweAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JweAuthenticationFilter.class);

    private final JweTokenService jweTokenService;
    private final CookieService cookieService;

    public JweAuthenticationFilter(JweTokenService jweTokenService, CookieService cookieService) {
        this.jweTokenService = jweTokenService;
        this.cookieService = cookieService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain) throws ServletException, IOException {

        // Try Bearer header first, then fall back to cookie
        extractBearerToken(request)
                .or(() -> cookieService.extractAccessToken(request))
                .flatMap(jweTokenService::validateAccessToken)
                .ifPresent(claims -> {
                    JweAuthentication authentication = new JweAuthentication(claims);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("Authenticated user {} from JWE token", claims.userId());
                });

        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the JWT token from the Authorization header if present.
     * Expected format: "Authorization: Bearer &lt;token&gt;"
     *
     * @param request the HTTP request
     * @return Optional containing the token, or empty if not present
     */
    private Optional<String> extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return Optional.of(header.substring(7));
        }
        return Optional.empty();
    }

    /**
     * Custom authentication token for JWE-authenticated users.
     */
    public static class JweAuthentication extends AbstractAuthenticationToken {

        private final UUID userId;
        private final String email;
        private final JweTokenService.TokenClaims claims;

        public JweAuthentication(JweTokenService.TokenClaims claims) {
            super(List.of(new SimpleGrantedAuthority("ROLE_USER")));
            this.userId = claims.userId();
            this.email = claims.email();
            this.claims = claims;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null; // No credentials - token-based auth
        }

        @Override
        public Object getPrincipal() {
            return userId;
        }

        public UUID getUserId() {
            return userId;
        }

        public String getEmail() {
            return email;
        }

        public JweTokenService.TokenClaims getClaims() {
            return claims;
        }

        @Override
        public String getName() {
            return email;
        }
    }
}
