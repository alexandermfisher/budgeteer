package dev.amf.budgeteer.api.dev;

import dev.amf.budgeteer.api.common.GlobalExceptionHandler;
import dev.amf.budgeteer.config.SecurityConfig;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.repository.MonzoConnectionRepository;
import dev.amf.budgeteer.service.auth.AuthService;
import dev.amf.budgeteer.service.auth.DevAuthService;
import dev.amf.budgeteer.service.auth.JweTokenService;
import dev.amf.budgeteer.service.auth.SessionService;
import dev.amf.budgeteer.service.common.CookieService;
import dev.amf.budgeteer.service.monzo.TransactionSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link DevAuthController}.
 * 
 * <p>Note: This controller only exists in the 'dev' profile, so tests
 * use @ActiveProfiles("dev") to activate it.
 */
@WebMvcTest(DevAuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("dev")
@DisplayName("DevAuthController")
class DevAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DevAuthService devAuthService;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private CookieService cookieService;

    @MockitoBean
    private TransactionSyncService transactionSyncService;

    @MockitoBean
    private MonzoConnectionRepository monzoConnectionRepository;

    // Required by SecurityConfig and CurrentUserArgumentResolver
    @MockitoBean
    private JweTokenService jweTokenService;

    @MockitoBean
    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User("test@example.com");
        testUser.setId(UUID.randomUUID());
    }

    @Nested
    @DisplayName("POST /api/test/auth/quick-login")
    class QuickLogin {

        @Test
        @DisplayName("should return tokens for valid email")
        void shouldReturnTokensForValidEmail() throws Exception {
            // Given
            when(devAuthService.findOrCreateDevUser("test@example.com"))
                    .thenReturn(testUser);
            SessionService.SessionTokens tokens = new SessionService.SessionTokens("access-token-123", "refresh-token-456");
            when(sessionService.createSession(eq(testUser), anyString(), anyString()))
                    .thenReturn(tokens);

            // When/Then
            mockMvc.perform(post("/api/test/auth/quick-login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "test@example.com"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.userId").value(testUser.getId().toString()))
                    .andExpect(jsonPath("$.data.email").value("test@example.com"))
                    .andExpect(jsonPath("$.data.accessToken").value("access-token-123"))
                    .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-456"))
                    .andExpect(jsonPath("$.data.hint").exists());

            verify(cookieService).setAuthCookies(any(), eq("access-token-123"), eq("refresh-token-456"));
        }

        @Test
        @DisplayName("should normalize email to lowercase")
        void shouldNormalizeEmailToLowercase() throws Exception {
            // Given
            when(devAuthService.findOrCreateDevUser("test@example.com"))
                    .thenReturn(testUser);
            when(sessionService.createSession(any(), anyString(), anyString()))
                    .thenReturn(new SessionService.SessionTokens("a", "r"));

            // When/Then - email with uppercase should be normalized to lowercase
            mockMvc.perform(post("/api/test/auth/quick-login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "TEST@EXAMPLE.COM"}
                                    """))
                    .andExpect(status().isOk());

            verify(devAuthService).findOrCreateDevUser("test@example.com");
        }

        @Test
        @DisplayName("should return 400 for missing email")
        void shouldReturn400ForMissingEmail() throws Exception {
            mockMvc.perform(post("/api/test/auth/quick-login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("should return 400 for invalid email format")
        void shouldReturn400ForInvalidEmail() throws Exception {
            mockMvc.perform(post("/api/test/auth/quick-login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "not-an-email"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    @Nested
    @DisplayName("POST /api/test/auth/revoke-all")
    class RevokeAll {

        @Test
        @DisplayName("should revoke all sessions and return count")
        void shouldRevokeAllSessions() throws Exception {
            // Given
            when(devAuthService.revokeAllSessions()).thenReturn(5);

            // When/Then
            mockMvc.perform(post("/api/test/auth/revoke-all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionsRevoked").value(5))
                    .andExpect(jsonPath("$.data.message").value("All sessions revoked. Everyone has been logged out."));

            verify(cookieService).clearAuthCookies(any());
        }

        @Test
        @DisplayName("should handle zero sessions")
        void shouldHandleZeroSessions() throws Exception {
            // Given
            when(devAuthService.revokeAllSessions()).thenReturn(0);

            // When/Then
            mockMvc.perform(post("/api/test/auth/revoke-all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionsRevoked").value(0));
        }
    }

    @Nested
    @DisplayName("POST /api/test/auth/revoke-user")
    class RevokeUser {

        @Test
        @DisplayName("should revoke sessions for specific user")
        void shouldRevokeUserSessions() throws Exception {
            // Given
            when(devAuthService.revokeUserSessions("test@example.com")).thenReturn(2);

            // When/Then
            mockMvc.perform(post("/api/test/auth/revoke-user")
                            .param("email", "test@example.com"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionsRevoked").value(2))
                    .andExpect(jsonPath("$.data.message").value("Revoked 2 session(s) for test@example.com"));

            verify(cookieService).clearAuthCookies(any());
        }

        @Test
        @DisplayName("should return message when user not found")
        void shouldHandleUserNotFound() throws Exception {
            // Given - service returns -1 when user not found
            when(devAuthService.revokeUserSessions("unknown@example.com")).thenReturn(-1);

            // When/Then
            mockMvc.perform(post("/api/test/auth/revoke-user")
                            .param("email", "unknown@example.com"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionsRevoked").value(0))
                    .andExpect(jsonPath("$.data.message").value("User not found: unknown@example.com"));
        }

        @Test
        @DisplayName("should return 400 when email parameter missing")
        void shouldReturn400WhenEmailMissing() throws Exception {
            mockMvc.perform(post("/api/test/auth/revoke-user"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 for invalid email format")
        void shouldReturn400ForInvalidEmailFormat() throws Exception {
            mockMvc.perform(post("/api/test/auth/revoke-user")
                            .param("email", "not-an-email"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

            verify(devAuthService, never()).revokeUserSessions(any());
        }

        @Test
        @DisplayName("should return 400 for blank email")
        void shouldReturn400ForBlankEmail() throws Exception {
            mockMvc.perform(post("/api/test/auth/revoke-user")
                            .param("email", "   "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

            verify(devAuthService, never()).revokeUserSessions(any());
        }
    }
}
