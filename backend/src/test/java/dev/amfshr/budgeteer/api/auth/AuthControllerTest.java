package dev.amfshr.budgeteer.api.auth;

import dev.amfshr.budgeteer.api.common.GlobalExceptionHandler;
import dev.amfshr.budgeteer.config.AppProperties;
import dev.amfshr.budgeteer.config.SecurityConfig;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.security.JweAuthenticationFilter.JweAuthentication;
import dev.amfshr.budgeteer.service.auth.AuthService;
import dev.amfshr.budgeteer.service.common.CookieService;
import dev.amfshr.budgeteer.service.auth.JweTokenService;
import dev.amfshr.budgeteer.service.auth.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link AuthController}.
 * 
 * <p>Uses @WebMvcTest to test only the web layer with security enabled.
 * Service dependencies are mocked using @MockitoBean (Spring Boot 4+).
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("AuthController")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private CookieService cookieService;

    @MockitoBean
    private AppProperties appProperties;

    @MockitoBean
    private JweTokenService jweTokenService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User("test@example.com");
        testUser.setId(UUID.randomUUID());
        testUser.setEmailVerified(true);
    }

    /**
     * Creates a SecurityContext with JweAuthentication for the test user.
     */
    private SecurityContext createAuthenticatedContext() {
        Instant now = Instant.now();
        JweTokenService.TokenClaims claims = new JweTokenService.TokenClaims(
                testUser.getId(),
                testUser.getEmail(),
                now,
                now.plusSeconds(900),
                UUID.randomUUID().toString()
        );
        JweAuthentication authentication = new JweAuthentication(claims);
        
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        return context;
    }

    @Nested
    @DisplayName("POST /api/auth/login")
    class Login {

        @Test
        @DisplayName("should return 200 and success message for valid email")
        void shouldReturnSuccessForValidEmail() throws Exception {
            // Given
            doNothing().when(authService).requestMagicLink("test@example.com");

            // When/Then
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "test@example.com"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.message").value("Check your email for a login link"))
                    .andExpect(jsonPath("$.data.email").value("test@example.com"));

            verify(authService).requestMagicLink("test@example.com");
        }

        @Test
        @DisplayName("should return 400 for missing email")
        void shouldReturn400ForMissingEmail() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details.email").value("Email is required"));

            verify(authService, never()).requestMagicLink(any());
        }

        @Test
        @DisplayName("should return 400 for invalid email format")
        void shouldReturn400ForInvalidEmailFormat() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "not-an-email"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details.email").value("Invalid email format"));

            verify(authService, never()).requestMagicLink(any());
        }

        @Test
        @DisplayName("should return 400 for blank email")
        void shouldReturn400ForBlankEmail() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "   "}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

            verify(authService, never()).requestMagicLink(any());
        }

        @Test
        @DisplayName("should return 400 for malformed JSON")
        void shouldReturn400ForMalformedJson() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("not valid json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.message").value("Malformed request body"));
        }
    }

    @Nested
    @DisplayName("GET /api/auth/verify")
    class Verify {

        @Test
        @DisplayName("should return 302 redirect on valid token")
        void shouldRedirectOnValidToken() throws Exception {
            // Given
            SessionService.SessionTokens tokens = new SessionService.SessionTokens("access-token", "refresh-token");
            when(authService.verifyMagicLink(eq("valid-token"), any(), any()))
                    .thenReturn(Optional.of(tokens));
            when(appProperties.getLoginSuccessUrl()).thenReturn("http://localhost:3000/dashboard");

            // When/Then
            mockMvc.perform(get("/api/auth/verify")
                            .param("token", "valid-token"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "http://localhost:3000/dashboard"));

            verify(cookieService).setAuthCookies(any(), eq("access-token"), eq("refresh-token"));
        }

        @Test
        @DisplayName("should return 401 for invalid token")
        void shouldReturn401ForInvalidToken() throws Exception {
            // Given
            when(authService.verifyMagicLink(eq("invalid-token"), any(), any()))
                    .thenReturn(Optional.empty());

            // When/Then
            mockMvc.perform(get("/api/auth/verify")
                            .param("token", "invalid-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"))
                    .andExpect(jsonPath("$.error.message").value("Invalid or expired magic link token"));
        }

        @Test
        @DisplayName("should return 400 for missing token parameter")
        void shouldReturn400ForMissingToken() throws Exception {
            mockMvc.perform(get("/api/auth/verify"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("should return 400 for blank token parameter")
        void shouldReturn400ForBlankToken() throws Exception {
            mockMvc.perform(get("/api/auth/verify")
                            .param("token", "   "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

            verify(authService, never()).verifyMagicLink(any(), any(), any());
        }

        @Test
        @DisplayName("should extract client IP and user agent")
        void shouldExtractClientInfo() throws Exception {
            // Given
            SessionService.SessionTokens tokens = new SessionService.SessionTokens("access", "refresh");
            when(authService.verifyMagicLink(eq("token"), eq("Mozilla/5.0"), eq("192.168.1.1")))
                    .thenReturn(Optional.of(tokens));
            when(appProperties.getLoginSuccessUrl()).thenReturn("http://localhost/");
            when(cookieService.getClientIpAddress(any())).thenReturn("192.168.1.1");

            // When/Then
            mockMvc.perform(get("/api/auth/verify")
                            .param("token", "token")
                            .header("User-Agent", "Mozilla/5.0"))
                    .andExpect(status().isFound());

            verify(authService).verifyMagicLink("token", "Mozilla/5.0", "192.168.1.1");
        }
    }

    @Nested
    @DisplayName("POST /api/auth/refresh")
    class Refresh {

        @Test
        @DisplayName("should return 200 with new tokens when refresh token in body")
        void shouldRefreshWithBodyToken() throws Exception {
            // Given
            SessionService.SessionTokens newTokens = new SessionService.SessionTokens("new-access", "new-refresh");
            when(sessionService.refreshSession(eq("valid-refresh"), any(), any()))
                    .thenReturn(Optional.of(newTokens));

            // When/Then
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken": "valid-refresh"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.message").value("Token refreshed"))
                    .andExpect(jsonPath("$.data.accessToken").value("new-access"))
                    .andExpect(jsonPath("$.data.refreshToken").value("new-refresh"));

            verify(cookieService).setAuthCookies(any(), eq("new-access"), eq("new-refresh"));
        }

        @Test
        @DisplayName("should return 200 with new tokens when refresh token in cookie")
        void shouldRefreshWithCookieToken() throws Exception {
            // Given
            SessionService.SessionTokens newTokens = new SessionService.SessionTokens("new-access", "new-refresh");
            when(cookieService.extractRefreshToken(any())).thenReturn(Optional.of("cookie-refresh"));
            when(sessionService.refreshSession(eq("cookie-refresh"), any(), any()))
                    .thenReturn(Optional.of(newTokens));

            // When/Then
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").value("new-access"));
        }

        @Test
        @DisplayName("should prefer body token over cookie token")
        void shouldPreferBodyOverCookie() throws Exception {
            // Given
            SessionService.SessionTokens newTokens = new SessionService.SessionTokens("new-access", "new-refresh");
            when(cookieService.extractRefreshToken(any())).thenReturn(Optional.of("cookie-token"));
            when(sessionService.refreshSession(eq("body-token"), any(), any()))
                    .thenReturn(Optional.of(newTokens));

            // When/Then
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken": "body-token"}
                                    """))
                    .andExpect(status().isOk());

            verify(sessionService).refreshSession(eq("body-token"), any(), any());
            verify(sessionService, never()).refreshSession(eq("cookie-token"), any(), any());
        }

        @Test
        @DisplayName("should return 401 for invalid refresh token")
        void shouldReturn401ForInvalidToken() throws Exception {
            // Given
            when(sessionService.refreshSession(eq("invalid-token"), any(), any()))
                    .thenReturn(Optional.empty());

            // When/Then
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken": "invalid-token"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"))
                    .andExpect(jsonPath("$.error.message").value("Invalid or expired refresh token"));

            verify(cookieService).clearAuthCookies(any());
        }

        @Test
        @DisplayName("should return 401 when no refresh token provided")
        void shouldReturn401WhenNoToken() throws Exception {
            // Given - no token in body and mock returns empty for cookie extraction
            when(cookieService.extractRefreshToken(any())).thenReturn(Optional.empty());

            // When/Then - API returns 401 with MISSING_TOKEN error
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("MISSING_TOKEN"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/logout")
    class Logout {

        @Test
        @DisplayName("should return 200 and clear cookies on logout")
        void shouldLogoutAndClearCookies() throws Exception {
            // Given
            when(cookieService.extractRefreshToken(any())).thenReturn(Optional.of("refresh-token"));
            when(sessionService.revokeSession("refresh-token")).thenReturn(true);

            // When/Then
            mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.message").value("Logged out"));

            verify(sessionService).revokeSession("refresh-token");
            verify(cookieService).clearAuthCookies(any());
        }

        @Test
        @DisplayName("should return 200 even without refresh token cookie")
        void shouldSucceedWithoutCookie() throws Exception {
            // Given
            when(cookieService.extractRefreshToken(any())).thenReturn(Optional.empty());

            // When/Then
            mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.message").value("Logged out"));

            verify(sessionService, never()).revokeSession(any());
            verify(cookieService).clearAuthCookies(any());
        }
    }

    @Nested
    @DisplayName("GET /api/auth/me")
    class Me {

        @Test
        @DisplayName("should return 403 when not authenticated")
        void shouldReturn403WhenNotAuthenticated() throws Exception {
            // Given - no authentication context
            // When/Then - Spring Security returns 403 for protected endpoints
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("should return user data when authenticated")
        void shouldReturnUserWhenAuthenticated() throws Exception {
            // Given
            when(authService.getUserById(testUser.getId()))
                    .thenReturn(Optional.of(testUser));
            
            // Set up security context with JweAuthentication
            SecurityContext context = createAuthenticatedContext();

            // When/Then
            mockMvc.perform(get("/api/auth/me")
                            .with(securityContext(context)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(testUser.getId().toString()))
                    .andExpect(jsonPath("$.data.email").value("test@example.com"));
        }

        @Test
        @DisplayName("should return 404 when authenticated user not found in database")
        void shouldReturn404WhenUserNotFound() throws Exception {
            // Given
            when(authService.getUserById(testUser.getId()))
                    .thenReturn(Optional.empty());
            
            SecurityContext context = createAuthenticatedContext();

            // When/Then
            mockMvc.perform(get("/api/auth/me")
                            .with(securityContext(context)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
        }
    }
}
