package dev.amf.budgeteer.integration;

import com.github.tomakehurst.wiremock.client.WireMock;
import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.domain.monzo.MonzoConnection;
import dev.amf.budgeteer.repository.MonzoConnectionRepository;
import dev.amf.budgeteer.domain.oauth.OAuthState;
import dev.amf.budgeteer.repository.OAuthStateRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.repository.UserRepository;
import dev.amf.budgeteer.exception.ApiException;
import dev.amf.budgeteer.service.monzo.MonzoConnectionService;
import dev.amf.budgeteer.service.monzo.MonzoOAuthService;
import io.restassured.RestAssured;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the Monzo OAuth flow.
 *
 * <p>Uses WireMock to mock the Monzo API (token exchange, whoami) and
 * Testcontainers for a real PostgreSQL database.</p>
 *
 * <h3>Scenarios Tested:</h3>
 * <ul>
 *   <li>OAuth callback endpoint - user approves (happy path)</li>
 *   <li>OAuth callback endpoint - user denies access</li>
 *   <li>OAuth callback endpoint - invalid/expired/used state</li>
 *   <li>OAuth callback endpoint - Monzo API errors</li>
 *   <li>Connection management (list, get, disconnect)</li>
 *   <li>User isolation (user A can't see user B's connections)</li>
 * </ul>
 */
@DisplayName("Monzo OAuth Flow Integration Tests")
class MonzoOAuthFlowIT extends AbstractMonzoWireMockIT {

    @LocalServerPort
    private int port;

    @Autowired
    private MonzoOAuthService monzoOAuthService;

    @Autowired
    private MonzoConnectionService monzoConnectionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthStateRepository oAuthStateRepository;

    @Autowired
    private MonzoConnectionRepository monzoConnectionRepository;

    @Autowired
    private TestDataFactory testData;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        monzoConnectionRepository.deleteAll();
        oAuthStateRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ========================================================================
    // OAuth Callback Tests - The Single Entry Point
    // ========================================================================

    @Nested
    @DisplayName("GET /api/monzo/callback - OAuth Callback Endpoint")
    class OAuthCallbackEndpoint {

        @Test
        @DisplayName("Happy Path: User approves → code received → connection created")
        void shouldCreateConnectionWhenUserApproves() {
            // Given - User initiated OAuth and has valid state
            User user = testData.createVerifiedUser();
            OAuthState state = testData.createValidOAuthStateFor(user);

            // Stub Monzo API responses
            stubMonzoTokenExchangeSuccess("test-access-token", "test-refresh-token", "user_monzo123");
            stubMonzoWhoamiSuccess("user_monzo123");

            // When - Monzo redirects to callback with code and state (user approved)
            given()
                .queryParam("code", "valid-authorization-code")
                .queryParam("state", state.getState())
            .when()
                .get("/api/monzo/callback")
            .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.monzoUserId", is("user_monzo123"))
                .body("data.id", notNullValue());

            // Then - Connection is saved in database
            List<MonzoConnection> connections = monzoConnectionRepository.findAll();
            assertThat(connections).hasSize(1);
            assertThat(connections.getFirst().getMonzoUserId()).isEqualTo("user_monzo123");
            assertThat(connections.getFirst().getUser().getId()).isEqualTo(user.getId());
            assertThat(connections.getFirst().isActive()).isTrue();

            // Then - State is marked as used (prevents replay)
            OAuthState updatedState = oAuthStateRepository.findById(state.getId()).orElseThrow();
            assertThat(updatedState.isUsed()).isTrue();

            // Verify Monzo API was called correctly
            wm.verify(postRequestedFor(urlPathEqualTo("/oauth2/token"))
                    .withRequestBody(containing("grant_type=authorization_code"))
                    .withRequestBody(containing("code=valid-authorization-code")));
            wm.verify(getRequestedFor(urlPathEqualTo("/ping/whoami"))
                    .withHeader("Authorization", WireMock.equalTo("Bearer test-access-token")));
        }

        @Test
        @DisplayName("User Denies Access: error parameter returned")
        void shouldReturnErrorWhenUserDeniesAccess() {
            // Given - User initiated OAuth and has valid state
            User user = testData.createVerifiedUser();
            OAuthState state = testData.createValidOAuthStateFor(user);

            // When - Monzo redirects to callback with error (user denied)
            given()
                .queryParam("error", "access_denied")
                .queryParam("error_description", "The user denied access to their Monzo account")
                .queryParam("state", state.getState())
            .when()
                .get("/api/monzo/callback")
            .then()
                .statusCode(400)
                .body("success", is(false))
                .body("error.code", is(ErrorCode.OAUTH_ACCESS_DENIED.name()))
                .body("error.message", containsString("denied"));

            // Then - No connection is created
            List<MonzoConnection> connections = monzoConnectionRepository.findAll();
            assertThat(connections).isEmpty();

            // State is still marked as used (prevents re-use)
            OAuthState updatedState = oAuthStateRepository.findById(state.getId()).orElseThrow();
            assertThat(updatedState.isUsed()).isTrue();

            // Monzo token exchange should NOT have been called
            wm.verify(0, postRequestedFor(urlPathEqualTo("/oauth2/token")));
        }

        @Test
        @DisplayName("Invalid State: State not found in database")
        void shouldReturnErrorForInvalidState() {
            // When - Callback with unknown state
            given()
                .queryParam("code", "valid-code")
                .queryParam("state", "unknown-state-that-does-not-exist")
            .when()
                .get("/api/monzo/callback")
            .then()
                .statusCode(400)
                .body("success", is(false))
                .body("error.code", is(ErrorCode.OAUTH_STATE_INVALID.name()));

            // No connection created
            assertThat(monzoConnectionRepository.findAll()).isEmpty();

            // Monzo API not called
            wm.verify(0, postRequestedFor(urlPathEqualTo("/oauth2/token")));
        }

        @Test
        @DisplayName("Expired State: State is older than 10 minutes")
        void shouldReturnErrorForExpiredState() {
            // Given - User has an expired state
            User user = testData.createVerifiedUser();
            OAuthState expiredState = testData.createExpiredOAuthStateFor(user);

            // When - Callback with expired state
            given()
                .queryParam("code", "valid-code")
                .queryParam("state", expiredState.getState())
            .when()
                .get("/api/monzo/callback")
            .then()
                .statusCode(400)
                .body("success", is(false))
                .body("error.code", is(ErrorCode.OAUTH_STATE_EXPIRED.name()));

            // No connection created
            assertThat(monzoConnectionRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("Replay Attack: Same state used twice")
        void shouldPreventReplayAttackWithUsedState() {
            // Given - State that was already used
            User user = testData.createVerifiedUser();
            OAuthState usedState = testData.createUsedOAuthStateFor(user);

            // When - Attacker tries to reuse the state
            given()
                .queryParam("code", "valid-code")
                .queryParam("state", usedState.getState())
            .when()
                .get("/api/monzo/callback")
            .then()
                .statusCode(400)
                .body("success", is(false))
                .body("error.code", is(ErrorCode.OAUTH_STATE_INVALID.name()));
        }

        @Test
        @DisplayName("Missing Code: No code parameter but no error either")
        void shouldReturnErrorWhenCodeIsMissing() {
            // Given
            User user = testData.createVerifiedUser();
            OAuthState state = testData.createValidOAuthStateFor(user);

            // When - Callback with state but no code or error
            given()
                .queryParam("state", state.getState())
            .when()
                .get("/api/monzo/callback")
            .then()
                .statusCode(400)
                .body("success", is(false))
                .body("error.code", is(ErrorCode.OAUTH_CODE_MISSING.name()));
        }

        @Test
        @DisplayName("Token Exchange Failure: Monzo rejects the authorization code")
        void shouldReturnErrorWhenTokenExchangeFails() {
            // Given
            User user = testData.createVerifiedUser();
            OAuthState state = testData.createValidOAuthStateFor(user);

            // Stub Monzo to reject the token exchange
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(aResponse()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                        "error": "invalid_grant",
                                        "error_description": "The authorization code has expired"
                                    }
                                    """)));

            // When - Callback with code that Monzo rejects
            given()
                .queryParam("code", "expired-or-invalid-code")
                .queryParam("state", state.getState())
            .when()
                .get("/api/monzo/callback")
            .then()
                .statusCode(anyOf(is(400), is(502))); // Either bad request or gateway error

            // No connection created
            assertThat(monzoConnectionRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("Full Happy Path: Initiate → Approve → Callback → Connection Created")
        void shouldCompleteFullOAuthFlowThroughCallback() {
            // Given - User
            User user = testData.createVerifiedUser();

            // 1. Initiate OAuth (generates state and returns auth URL)
            String authUrl = monzoOAuthService.initiateOAuthFlow(user);
            assertThat(authUrl).contains("state=");

            // Extract state from authorization URL
            String state = extractStateFromUrl(authUrl);

            // Stub Monzo API for successful flow
            stubMonzoTokenExchangeSuccess("full-flow-access-token", "full-flow-refresh-token", "user_fullflow");
            stubMonzoWhoamiSuccess("user_fullflow");

            // 2. Simulate user approving at Monzo → Monzo redirects to callback
            given()
                .queryParam("code", "authorization-code-from-monzo")
                .queryParam("state", state)
            .when()
                .get("/api/monzo/callback")
            .then()
                .statusCode(200)
                .body("success", is(true))
                .body("data.monzoUserId", is("user_fullflow"))
                .body("data.isActive", is(true));

            // 3. Verify connection is in database
            List<MonzoConnection> connections = monzoConnectionRepository.findAll();
            assertThat(connections).hasSize(1);
            MonzoConnection connection = connections.getFirst();
            assertThat(connection.getMonzoUserId()).isEqualTo("user_fullflow");
            assertThat(connection.getUser().getId()).isEqualTo(user.getId());

            // 4. Verify state cannot be reused
            given()
                .queryParam("code", "another-code")
                .queryParam("state", state)
            .when()
                .get("/api/monzo/callback")
            .then()
                .statusCode(400)
                .body("error.code", is(ErrorCode.OAUTH_STATE_INVALID.name()));
        }
    }

    // ========================================================================
    // Service-Level Tests (for detailed unit testing of components)
    // ========================================================================

    @Nested
    @DisplayName("OAuth State Verification (Service Level)")
    class StateVerificationService {

        @Test
        @DisplayName("should return user for valid state")
        @Transactional
        void shouldReturnUserForValidState() {
            // Given
            User user = testData.createVerifiedUser();
            OAuthState state = testData.createValidOAuthStateFor(user);

            // When
            User verifiedUser = monzoOAuthService.verifyStateAndGetUser(state.getState());

            // Then
            assertThat(verifiedUser.getId()).isEqualTo(user.getId());

            // State is marked as used
            OAuthState updatedState = oAuthStateRepository.findById(state.getId()).orElseThrow();
            assertThat(updatedState.isUsed()).isTrue();
        }

        @Test
        @DisplayName("should throw for invalid state")
        void shouldThrowForInvalidState() {
            assertThatThrownBy(() -> monzoOAuthService.verifyStateAndGetUser("unknown-state"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("state");
        }

        @Test
        @DisplayName("should throw for expired state")
        @Transactional
        void shouldThrowForExpiredState() {
            User user = testData.createVerifiedUser();
            OAuthState expiredState = testData.createExpiredOAuthStateFor(user);

            assertThatThrownBy(() -> monzoOAuthService.verifyStateAndGetUser(expiredState.getState()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("expired");
        }
    }

    @Nested
    @DisplayName("Token Exchange (Service Level)")
    class TokenExchangeService {

        @Test
        @DisplayName("should exchange code for tokens successfully")
        void shouldExchangeCodeForTokens() {
            stubMonzoTokenExchangeSuccess("test-access-token", "test-refresh-token", "user_test");

            MonzoOAuthService.TokenResponse tokens = monzoOAuthService.exchangeCodeForTokens("valid-code");

            assertThat(tokens.accessToken()).isEqualTo("test-access-token");
            assertThat(tokens.refreshToken()).isEqualTo("test-refresh-token");
            assertThat(tokens.expiresAt()).isNotNull();
        }
    }

    // ========================================================================
    // Connection Management Tests
    // ========================================================================

    @Nested
    @DisplayName("Connection Management")
    class ConnectionManagement {

        @Test
        @DisplayName("should return only user's own connections")
        @Transactional
        void shouldReturnOnlyUsersConnections() {
            User user1 = testData.createVerifiedUser();
            User user2 = testData.createVerifiedUser();
            testData.createActiveMonzoConnectionFor(user1, "user_monzo1");
            testData.createActiveMonzoConnectionFor(user1, "user_monzo2");
            testData.createActiveMonzoConnectionFor(user2, "user_monzo3");

            List<MonzoConnection> connections = monzoConnectionService.listActiveConnections(user1.getId());

            assertThat(connections).hasSize(2);
            assertThat(connections)
                    .extracting(MonzoConnection::getMonzoUserId)
                    .containsExactlyInAnyOrder("user_monzo1", "user_monzo2");
        }

        @Test
        @DisplayName("should soft-delete connection on disconnect")
        @Transactional
        void shouldSoftDeleteConnection() {
            User user = testData.createVerifiedUser();
            MonzoConnection connection = testData.createActiveMonzoConnectionFor(user);

            monzoConnectionService.disconnectConnection(connection.getId(), user.getId());

            MonzoConnection updatedConnection = monzoConnectionRepository.findById(connection.getId())
                    .orElseThrow();
            assertThat(updatedConnection.isActive()).isFalse();
            assertThat(updatedConnection.getDisconnectedAt()).isNotNull();
        }

        @Test
        @DisplayName("should not allow disconnecting another user's connection")
        @Transactional
        void shouldNotAllowDisconnectingOtherUsersConnection() {
            User user1 = testData.createVerifiedUser();
            User user2 = testData.createVerifiedUser();
            MonzoConnection user2Connection = testData.createActiveMonzoConnectionFor(user2);

            assertThatThrownBy(() ->
                    monzoConnectionService.disconnectConnection(user2Connection.getId(), user1.getId()))
                    .isInstanceOf(ApiException.class);

            MonzoConnection stillActive = monzoConnectionRepository.findById(user2Connection.getId())
                    .orElseThrow();
            assertThat(stillActive.isActive()).isTrue();
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void stubMonzoTokenExchangeSuccess(String accessToken, String refreshToken, String userId) {
        wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(okJson(String.format("""
                        {
                            "access_token": "%s",
                            "refresh_token": "%s",
                            "token_type": "Bearer",
                            "expires_in": 21600,
                            "user_id": "%s"
                        }
                        """, accessToken, refreshToken, userId))));
    }

    private void stubMonzoWhoamiSuccess(String userId) {
        wm.stubFor(get(urlPathEqualTo("/ping/whoami"))
                .willReturn(okJson(String.format("""
                        {
                            "authenticated": true,
                            "client_id": "test_client",
                            "user_id": "%s"
                        }
                        """, userId))));
    }

    private String extractStateFromUrl(String authUrl) {
        String state = authUrl.substring(authUrl.indexOf("state=") + 6);
        if (state.contains("&")) {
            state = state.substring(0, state.indexOf("&"));
        }
        return state;
    }
}
