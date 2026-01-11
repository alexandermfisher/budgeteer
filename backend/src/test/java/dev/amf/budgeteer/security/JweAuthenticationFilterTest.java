package dev.amf.budgeteer.security;

import dev.amf.budgeteer.service.CookieService;
import dev.amf.budgeteer.service.JweTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JweAuthenticationFilter}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JweAuthenticationFilter")
class JweAuthenticationFilterTest {

    @Mock
    private JweTokenService jweTokenService;

    @Mock
    private CookieService cookieService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private JweAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JweAuthenticationFilter(jweTokenService, cookieService);
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("doFilterInternal")
    class DoFilterInternal {

        @Test
        @DisplayName("should authenticate when Bearer token is valid")
        void shouldAuthenticateWithBearerToken() throws Exception {
            // Given
            String token = "valid-bearer-token";
            UUID userId = UUID.randomUUID();
            JweTokenService.TokenClaims claims = new JweTokenService.TokenClaims(
                    userId, "test@example.com", Instant.now(), Instant.now().plusSeconds(900), "jti-123"
            );

            when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
            when(jweTokenService.validateAccessToken(token)).thenReturn(Optional.of(claims));

            // When
            filter.doFilterInternal(request, response, filterChain);

            // Then
            verify(filterChain).doFilter(request, response);
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.isAuthenticated()).isTrue();
            assertThat(auth.getPrincipal()).isEqualTo(userId);
        }

        @Test
        @DisplayName("should authenticate when cookie token is valid")
        void shouldAuthenticateWithCookieToken() throws Exception {
            // Given
            String token = "valid-cookie-token";
            UUID userId = UUID.randomUUID();
            JweTokenService.TokenClaims claims = new JweTokenService.TokenClaims(
                    userId, "cookie@example.com", Instant.now(), Instant.now().plusSeconds(900), "jti-456"
            );

            when(request.getHeader("Authorization")).thenReturn(null);
            when(cookieService.extractAccessToken(request)).thenReturn(Optional.of(token));
            when(jweTokenService.validateAccessToken(token)).thenReturn(Optional.of(claims));

            // When
            filter.doFilterInternal(request, response, filterChain);

            // Then
            verify(filterChain).doFilter(request, response);
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getPrincipal()).isEqualTo(userId);
        }

        @Test
        @DisplayName("should prefer Bearer token over cookie")
        void shouldPreferBearerOverCookie() throws Exception {
            // Given
            UUID bearerUserId = UUID.randomUUID();
            JweTokenService.TokenClaims bearerClaims = new JweTokenService.TokenClaims(
                    bearerUserId, "bearer@example.com", Instant.now(), Instant.now().plusSeconds(900), "jti-bearer"
            );

            when(request.getHeader("Authorization")).thenReturn("Bearer bearer-token");
            when(jweTokenService.validateAccessToken("bearer-token")).thenReturn(Optional.of(bearerClaims));

            // When
            filter.doFilterInternal(request, response, filterChain);

            // Then
            verify(cookieService, never()).extractAccessToken(any());
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.getPrincipal()).isEqualTo(bearerUserId);
        }

        @Test
        @DisplayName("should not authenticate when no token present")
        void shouldNotAuthenticateWithoutToken() throws Exception {
            // Given
            when(request.getHeader("Authorization")).thenReturn(null);
            when(cookieService.extractAccessToken(request)).thenReturn(Optional.empty());

            // When
            filter.doFilterInternal(request, response, filterChain);

            // Then
            verify(filterChain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("should not authenticate when token is invalid")
        void shouldNotAuthenticateWithInvalidToken() throws Exception {
            // Given - Bearer token present but fails validation
            when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");
            when(jweTokenService.validateAccessToken("invalid-token")).thenReturn(Optional.empty());
            // Note: cookie extraction is not called because Bearer token was found (just invalid)

            // When
            filter.doFilterInternal(request, response, filterChain);

            // Then
            verify(filterChain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("should ignore malformed Authorization header")
        void shouldIgnoreMalformedHeader() throws Exception {
            // Given - header without "Bearer " prefix
            when(request.getHeader("Authorization")).thenReturn("Basic credentials");
            when(cookieService.extractAccessToken(request)).thenReturn(Optional.empty());

            // When
            filter.doFilterInternal(request, response, filterChain);

            // Then
            verify(jweTokenService, never()).validateAccessToken(any());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Nested
    @DisplayName("JweAuthentication")
    class JweAuthenticationTest {

        @Test
        @DisplayName("should have correct properties")
        void shouldHaveCorrectProperties() {
            UUID userId = UUID.randomUUID();
            Instant now = Instant.now();
            JweTokenService.TokenClaims claims = new JweTokenService.TokenClaims(
                    userId, "test@example.com", now, now.plusSeconds(900), "jti-test"
            );

            JweAuthenticationFilter.JweAuthentication auth = new JweAuthenticationFilter.JweAuthentication(claims);

            assertThat(auth.isAuthenticated()).isTrue();
            assertThat(auth.getPrincipal()).isEqualTo(userId);
            assertThat(auth.getUserId()).isEqualTo(userId);
            assertThat(auth.getEmail()).isEqualTo("test@example.com");
            assertThat(auth.getName()).isEqualTo("test@example.com");
            assertThat(auth.getCredentials()).isNull();
            assertThat(auth.getClaims()).isEqualTo(claims);
            assertThat(auth.getAuthorities()).hasSize(1);
            assertThat(auth.getAuthorities().iterator().next().getAuthority()).isEqualTo("ROLE_USER");
        }
    }
}
