package dev.amfshr.budgeteer.api.monzo;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.api.common.GlobalExceptionHandler;
import dev.amfshr.budgeteer.bank.BankTokens;
import dev.amfshr.budgeteer.config.SecurityConfig;
import dev.amfshr.budgeteer.config.WebMvcConfig;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.security.CurrentUserArgumentResolver;
import dev.amfshr.budgeteer.security.JweAuthenticationFilter.JweAuthentication;
import dev.amfshr.budgeteer.service.auth.AuthService;
import dev.amfshr.budgeteer.service.common.CookieService;
import dev.amfshr.budgeteer.service.auth.JweTokenService;
import dev.amfshr.budgeteer.service.monzo.MonzoConnectionService;
import dev.amfshr.budgeteer.service.monzo.MonzoOAuthService;
import dev.amfshr.budgeteer.service.monzo.TransactionSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link MonzoController}.
 *
 * <p>Tests the Monzo OAuth flow and connection management endpoints.
 */
@WebMvcTest(MonzoController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebMvcConfig.class, CurrentUserArgumentResolver.class})
@DisplayName("MonzoController")
class MonzoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonzoOAuthService oauthService;

    @MockitoBean
    private MonzoConnectionService connectionService;

    @MockitoBean
    private TransactionSyncService syncService;

    // Required by SecurityConfig
    @MockitoBean
    private JweTokenService jweTokenService;

    @MockitoBean
    private CookieService cookieService;

    @MockitoBean
    private AuthService authService;

    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User("test@example.com");
        // Use reflection to set the ID
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(testUser, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Configure AuthService to return the test user
        when(authService.getUserById(userId)).thenReturn(java.util.Optional.of(testUser));
    }

    // ============ OAuth Connect Tests ============

    @Nested
    @DisplayName("GET /api/v1/monzo/connect")
    class Connect {

        @Test
        @DisplayName("should redirect to Monzo OAuth URL when authenticated")
        void shouldRedirectToMonzoOAuth() throws Exception {
            // Given
            String expectedUrl = "https://auth.monzo.com?client_id=xxx&state=abc123";
            when(oauthService.initiateOAuthFlow(any(User.class))).thenReturn(expectedUrl);

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/connect").with(user(testUser)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", expectedUrl));

            verify(oauthService).initiateOAuthFlow(any(User.class));
        }

        @Test
        @DisplayName("should return 403 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/monzo/connect"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(oauthService);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/monzo/connect")
    class ConnectJson {

        @Test
        @DisplayName("should return JSON with authorization URL when authenticated")
        void shouldReturnJsonWithAuthUrl() throws Exception {
            // Given
            String expectedUrl = "https://auth.monzo.com?client_id=xxx&state=abc123";
            when(oauthService.initiateOAuthFlow(any(User.class))).thenReturn(expectedUrl);

            // When/Then
            mockMvc.perform(post("/api/v1/monzo/connect").with(user(testUser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.authorizationUrl").value(expectedUrl))
                    .andExpect(jsonPath("$.data.message").isNotEmpty());

            verify(oauthService).initiateOAuthFlow(any(User.class));
        }

        @Test
        @DisplayName("should return 403 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            mockMvc.perform(post("/api/v1/monzo/connect"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(oauthService);
        }
    }

    // ============ OAuth Callback Tests ============

    @Nested
    @DisplayName("GET /api/v1/monzo/callback")
    class Callback {

        @Test
        @DisplayName("should complete OAuth flow and return connection details")
        void shouldCompleteOAuthFlow() throws Exception {
            // Given
            String code = "auth-code-123";
            String state = "valid-state";
            String monzoUserId = "user_123";

            BankTokens tokens = new BankTokens(
                    "access-token",
                    "refresh-token",
                    Instant.now().plusSeconds(3600)
            );

            MonzoConnection connection = createMockConnection(userId, monzoUserId);

            when(oauthService.verifyStateAndGetUser(state)).thenReturn(testUser);
            when(oauthService.exchangeCodeForTokens(code)).thenReturn(tokens);
            when(oauthService.getMonzoUserId("access-token")).thenReturn(monzoUserId);
            when(connectionService.createConnection(
                    eq(userId), eq(monzoUserId), eq("access-token"), eq("refresh-token"), any()))
                    .thenReturn(connection);

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/callback")
                            .param("code", code)
                            .param("state", state))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.monzoUserId").value(monzoUserId))
                    .andExpect(jsonPath("$.data.isActive").value(true));

            verify(oauthService).verifyStateAndGetUser(state);
            verify(oauthService).exchangeCodeForTokens(code);
            verify(oauthService).getMonzoUserId("access-token");
            verify(connectionService).createConnection(
                    eq(userId), eq(monzoUserId), eq("access-token"), eq("refresh-token"), any());
            verify(syncService).backfillAsync(connection.getId());
        }

        @Test
        @DisplayName("should return error for invalid state")
        void shouldRejectInvalidState() throws Exception {
            // Given
            when(oauthService.verifyStateAndGetUser("invalid-state"))
                    .thenThrow(new ApiException(ErrorCode.OAUTH_STATE_INVALID));

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/callback")
                            .param("code", "auth-code-123")
                            .param("state", "invalid-state"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("OAUTH_STATE_INVALID"));

            verify(oauthService).verifyStateAndGetUser("invalid-state");
            verifyNoInteractions(connectionService);
        }

        @Test
        @DisplayName("should return error for expired state")
        void shouldRejectExpiredState() throws Exception {
            // Given
            when(oauthService.verifyStateAndGetUser("expired-state"))
                    .thenThrow(new ApiException(ErrorCode.OAUTH_STATE_EXPIRED));

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/callback")
                            .param("code", "auth-code-123")
                            .param("state", "expired-state"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("OAUTH_STATE_EXPIRED"));
        }

        @Test
        @DisplayName("should return 400 for missing code parameter")
        void shouldReturn400ForMissingCode() throws Exception {
            // Given - state verification must succeed first
            when(oauthService.verifyStateAndGetUser("some-state")).thenReturn(testUser);
            
            // When/Then - code is missing, so controller throws OAUTH_CODE_MISSING
            mockMvc.perform(get("/api/v1/monzo/callback")
                            .param("state", "some-state"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("OAUTH_CODE_MISSING"));
            
            verify(oauthService).verifyStateAndGetUser("some-state");
            // Token exchange should NOT be called since code is missing
            verify(oauthService, never()).exchangeCodeForTokens(any());
        }

        @Test
        @DisplayName("should return 400 for missing state parameter")
        void shouldReturn400ForMissingState() throws Exception {
            mockMvc.perform(get("/api/v1/monzo/callback")
                            .param("code", "auth-code"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("should return 400 for blank state parameter")
        void shouldReturn400ForBlankState() throws Exception {
            mockMvc.perform(get("/api/v1/monzo/callback")
                            .param("code", "auth-code")
                            .param("state", "   "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(oauthService);
        }

        @Test
        @DisplayName("should handle Monzo API error during token exchange")
        void shouldHandleMonzoApiError() throws Exception {
            // Given
            when(oauthService.verifyStateAndGetUser("valid-state")).thenReturn(testUser);
            when(oauthService.exchangeCodeForTokens("code"))
                    .thenThrow(new ApiException(ErrorCode.MONZO_API_ERROR, "Token exchange failed"));

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/callback")
                            .param("code", "code")
                            .param("state", "valid-state"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error.code").value("MONZO_API_ERROR"));
        }
    }

    // ============ Connection List Tests ============

    @Nested
    @DisplayName("GET /api/v1/monzo/connections")
    class ListConnections {

        @Test
        @DisplayName("should return list of connections for authenticated user")
        void shouldReturnConnectionsList() throws Exception {
            // Given
            MonzoConnection connection = createMockConnection(userId, "user_abc");
            when(connectionService.listActiveConnections(any(UUID.class)))
                    .thenReturn(List.of(connection));

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/connections").with(user(testUser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].monzoUserId").value("user_abc"))
                    .andExpect(jsonPath("$.data[0].isActive").value(true));

            verify(connectionService).listActiveConnections(any(UUID.class));
        }

        @Test
        @DisplayName("should return empty list when no connections")
        void shouldReturnEmptyListWhenNoConnections() throws Exception {
            // Given
            when(connectionService.listActiveConnections(any(UUID.class)))
                    .thenReturn(Collections.emptyList());

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/connections").with(user(testUser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("should return 403 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/monzo/connections"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(connectionService);
        }
    }

    // ============ Get Single Connection Tests ============

    @Nested
    @DisplayName("GET /api/v1/monzo/connections/{id}")
    class GetConnection {

        @Test
        @DisplayName("should return connection details for valid ID")
        void shouldReturnConnectionDetails() throws Exception {
            // Given
            UUID connectionId = UUID.randomUUID();
            MonzoConnection connection = createMockConnection(userId, "user_xyz", connectionId);
            when(connectionService.getConnection(eq(connectionId), any(UUID.class))).thenReturn(connection);

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/connections/{id}", connectionId).with(user(testUser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(connectionId.toString()))
                    .andExpect(jsonPath("$.data.monzoUserId").value("user_xyz"));

            verify(connectionService).getConnection(eq(connectionId), any(UUID.class));
        }

        @Test
        @DisplayName("should return 404 for non-existent connection")
        void shouldReturn404ForNonExistentConnection() throws Exception {
            // Given
            UUID connectionId = UUID.randomUUID();
            when(connectionService.getConnection(eq(connectionId), any(UUID.class)))
                    .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/connections/{id}", connectionId).with(user(testUser)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        @DisplayName("should return 403 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/monzo/connections/{id}", UUID.randomUUID()))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(connectionService);
        }
    }

    // ============ Disconnect Tests ============

    @Nested
    @DisplayName("DELETE /api/v1/monzo/connections/{id}")
    class DisconnectConnection {

        @Test
        @DisplayName("should disconnect connection and return 204")
        void shouldDisconnectConnection() throws Exception {
            // Given
            UUID connectionId = UUID.randomUUID();
            doNothing().when(connectionService).disconnectConnection(eq(connectionId), any(UUID.class));

            // When/Then
            mockMvc.perform(delete("/api/v1/monzo/connections/{id}", connectionId).with(user(testUser)))
                    .andExpect(status().isNoContent());

            verify(connectionService).disconnectConnection(eq(connectionId), any(UUID.class));
        }

        @Test
        @DisplayName("should return 404 for non-existent connection")
        void shouldReturn404ForNonExistentConnection() throws Exception {
            // Given
            UUID connectionId = UUID.randomUUID();
            doThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND))
                    .when(connectionService).disconnectConnection(eq(connectionId), any(UUID.class));

            // When/Then
            mockMvc.perform(delete("/api/v1/monzo/connections/{id}", connectionId).with(user(testUser)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        @DisplayName("should return 403 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            mockMvc.perform(delete("/api/v1/monzo/connections/{id}", UUID.randomUUID()))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(connectionService);
        }
    }

    // ============ Status Tests ============

    @Nested
    @DisplayName("GET /api/v1/monzo/status")
    class GetStatus {

        @Test
        @DisplayName("should return connected status when user has connections")
        void shouldReturnConnectedStatus() throws Exception {
            // Given
            when(connectionService.hasActiveConnection(any(UUID.class))).thenReturn(true);
            when(connectionService.countActiveConnections(any(UUID.class))).thenReturn(2L);

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/status").with(user(testUser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.connected").value(true))
                    .andExpect(jsonPath("$.data.connectionCount").value(2));
        }

        @Test
        @DisplayName("should return disconnected status when no connections")
        void shouldReturnDisconnectedStatus() throws Exception {
            // Given
            when(connectionService.hasActiveConnection(any(UUID.class))).thenReturn(false);
            when(connectionService.countActiveConnections(any(UUID.class))).thenReturn(0L);

            // When/Then
            mockMvc.perform(get("/api/v1/monzo/status").with(user(testUser)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.connected").value(false))
                    .andExpect(jsonPath("$.data.connectionCount").value(0));
        }

        @Test
        @DisplayName("should return 403 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/monzo/status"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(connectionService);
        }
    }

    // ============ Helper Methods ============

    /**
     * Creates a RequestPostProcessor for authenticated requests.
     * Uses JweAuthentication to match the real authentication flow.
     */
    private RequestPostProcessor user(User user) {
        // Create token claims for the JweAuthentication
        Instant now = Instant.now();
        JweTokenService.TokenClaims claims = new JweTokenService.TokenClaims(
                userId,
                user.getEmail(),
                now,                           // issuedAt
                now.plusSeconds(3600),         // expiresAt
                UUID.randomUUID().toString()   // tokenId
        );
        JweAuthentication auth = new JweAuthentication(claims);
        return authentication(auth);
    }

    private MonzoConnection createMockConnection(UUID userId, String monzoUserId) {
        return createMockConnection(userId, monzoUserId, UUID.randomUUID());
    }

    private MonzoConnection createMockConnection(UUID userId, String monzoUserId, UUID connectionId) {
        User user = new User("test@example.com");
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        MonzoConnection connection = new MonzoConnection(
                user,
                monzoUserId,
                "encrypted-access-token",
                "encrypted-refresh-token",
                Instant.now().plusSeconds(3600)
        );

        // Set ID using reflection
        try {
            var idField = MonzoConnection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(connection, connectionId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return connection;
    }
}
