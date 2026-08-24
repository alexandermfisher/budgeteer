package dev.amfshr.budgeteer.integration;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.provider.exception.ProviderException;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.service.common.EncryptionService;
import dev.amfshr.budgeteer.service.monzo.MonzoConnectionService;
import dev.amfshr.budgeteer.service.monzo.MonzoTokenRefreshService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link MonzoTokenRefreshService}.
 *
 * <p>Uses WireMock to mock the Monzo token endpoint and Testcontainers (via
 * {@link AbstractPostgresIntegrationTest}) for a real PostgreSQL database.
 *
 * <h3>Scenarios tested</h3>
 * <ul>
 *   <li>Successful refresh: tokens updated in DB with new encrypted values</li>
 *   <li>Token rotation: new refresh token persisted when Monzo returns one</li>
 *   <li>401 revocation: connection soft-deleted, no exception propagated</li>
 *   <li>500 transient error: connection NOT disconnected, exception propagated</li>
 *   <li>Batch refresh: multiple connections refreshed independently</li>
 *   <li>Eager refresh via getDecryptedAccessToken: inline refresh for near-expiry tokens</li>
 * </ul>
 */
@DisplayName("Monzo Token Refresh Integration Tests")
class MonzoTokenRefreshIT extends AbstractMonzoWireMockIT {

    @Autowired
    private MonzoTokenRefreshService tokenRefreshService;

    @Autowired
    private MonzoConnectionService connectionService;

    @Autowired
    private MonzoConnectionRepository connectionRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private TestDataFactory testData;

    @BeforeEach
    void setUp() {
        connectionRepository.deleteAll();
    }

    // =========================================================================
    // MonzoTokenRefreshService.refresh() tests
    // =========================================================================

    @Nested
    @DisplayName("refresh() — single connection")
    class RefreshSingleConnection {

        @Test
        @DisplayName("should update connection tokens in DB on successful Monzo response")
        void shouldUpdateTokensInDbOnSuccess() {
            // Given
            User user = testData.createVerifiedUser();
            String knownRefreshToken = "original-refresh-token-" + UUID.randomUUID();
            MonzoConnection connection = testData.createMonzoConnectionWithRealTokens(
                    user, knownRefreshToken, Instant.now().minus(1, ChronoUnit.MINUTES)
            );
            String originalAccessEnc = connection.getAccessTokenEncrypted();
            String originalRefreshEnc = connection.getRefreshTokenEncrypted();
            Instant originalExpiry = connection.getTokenExpiresAt();

            stubMonzoTokenRefreshSuccess("new-access-token", "new-refresh-token", 21600);

            // When
            MonzoConnection refreshed = tokenRefreshService.refresh(connection.getId());

            // Then
            assertThat(refreshed.isActive()).isTrue();
            assertThat(refreshed.getAccessTokenEncrypted()).isNotEqualTo(originalAccessEnc);
            assertThat(refreshed.getRefreshTokenEncrypted()).isNotEqualTo(originalRefreshEnc);
            assertThat(refreshed.getTokenExpiresAt()).isAfter(originalExpiry);

            // Verify tokens decrypt to the values Monzo returned
            assertThat(encryptionService.decrypt(refreshed.getAccessTokenEncrypted()))
                    .isEqualTo("new-access-token");
            assertThat(encryptionService.decrypt(refreshed.getRefreshTokenEncrypted()))
                    .isEqualTo("new-refresh-token");

            // Verify Monzo was called with the right refresh token
            wm.verify(postRequestedFor(urlPathEqualTo("/oauth2/token"))
                    .withRequestBody(containing("grant_type=refresh_token"))
                    .withRequestBody(containing("refresh_token=" + knownRefreshToken)));

            // Verify DB is updated
            MonzoConnection fromDb = connectionRepository.findById(connection.getId()).orElseThrow();
            assertThat(encryptionService.decrypt(fromDb.getAccessTokenEncrypted()))
                    .isEqualTo("new-access-token");
        }

        @Test
        @DisplayName("should persist new refresh token when Monzo rotates it")
        void shouldPersistRotatedRefreshToken() {
            // Given
            User user = testData.createVerifiedUser();
            MonzoConnection connection = testData.createMonzoConnectionWithRealTokens(
                    user, "original-refresh", Instant.now().minus(1, ChronoUnit.MINUTES)
            );

            stubMonzoTokenRefreshSuccess("fresh-access", "rotated-refresh-token", 21600);

            // When
            MonzoConnection refreshed = tokenRefreshService.refresh(connection.getId());

            // Then
            assertThat(encryptionService.decrypt(refreshed.getRefreshTokenEncrypted()))
                    .isEqualTo("rotated-refresh-token");
        }

        @Test
        @DisplayName("should soft-delete connection when Monzo returns 401 (token revoked)")
        void shouldDisconnectConnectionOnRevocation() {
            // Given
            User user = testData.createVerifiedUser();
            MonzoConnection connection = testData.createMonzoConnectionWithRealTokens(
                    user, "revoked-refresh-token", Instant.now().minus(1, ChronoUnit.MINUTES)
            );

            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));

            // When - should NOT throw for 401
            MonzoConnection result = tokenRefreshService.refresh(connection.getId());

            // Then
            assertThat(result.isActive()).isFalse();
            assertThat(result.getDisconnectedAt()).isNotNull();

            // Verify DB is updated
            MonzoConnection fromDb = connectionRepository.findById(connection.getId()).orElseThrow();
            assertThat(fromDb.isActive()).isFalse();
        }

        @Test
        @DisplayName("should NOT disconnect connection on transient Monzo error (500)")
        void shouldNotDisconnectOnTransientError() {
            // Given
            User user = testData.createVerifiedUser();
            MonzoConnection connection = testData.createMonzoConnectionWithRealTokens(
                    user, "valid-refresh-token", Instant.now().minus(1, ChronoUnit.MINUTES)
            );

            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

            // When/Then - should throw
            assertThatThrownBy(() -> tokenRefreshService.refresh(connection.getId()))
                    .isInstanceOf(ProviderException.class);

            // Then - connection still active in DB
            MonzoConnection fromDb = connectionRepository.findById(connection.getId()).orElseThrow();
            assertThat(fromDb.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("findExpiringConnections()")
    class FindExpiringConnections {

        @Test
        @DisplayName("should return connections expiring before threshold")
        void shouldReturnConnectionsExpiringBeforeThreshold() {
            // Given
            User user = testData.createVerifiedUser();

            // Expired 1 hour ago
            MonzoConnection expired = testData.createMonzoConnectionWithRealTokens(
                    user, "refresh-1", Instant.now().minus(1, ChronoUnit.HOURS)
            );
            // Expiring in 30 minutes (within 60-min window)
            MonzoConnection expiringSoon = testData.createMonzoConnectionWithRealTokens(
                    user, "refresh-2", Instant.now().plus(30, ChronoUnit.MINUTES)
            );
            // Healthy - expires in 3 hours
            testData.createMonzoConnectionWithRealTokens(
                    user, "refresh-3", Instant.now().plus(3, ChronoUnit.HOURS)
            );

            // When
            var expiring = tokenRefreshService.findExpiringConnections(
                    Instant.now().plus(60, ChronoUnit.MINUTES)
            );

            // Then
            assertThat(expiring)
                    .extracting(MonzoConnection::getId)
                    .containsExactlyInAnyOrder(expired.getId(), expiringSoon.getId());
        }

        @Test
        @DisplayName("should not return disconnected connections")
        void shouldNotReturnDisconnectedConnections() {
            // Given
            User user = testData.createVerifiedUser();
            MonzoConnection active = testData.createMonzoConnectionWithRealTokens(
                    user, "refresh-active", Instant.now().minus(1, ChronoUnit.HOURS)
            );
            // Disconnected + expired
            MonzoConnection disconnected = testData.createMonzoConnectionWithRealTokens(
                    user, "refresh-disc", Instant.now().minus(1, ChronoUnit.HOURS)
            );
            disconnected.disconnect();
            connectionRepository.save(disconnected);

            // When
            var expiring = tokenRefreshService.findExpiringConnections(
                    Instant.now().plus(60, ChronoUnit.MINUTES)
            );

            // Then
            assertThat(expiring)
                    .extracting(MonzoConnection::getId)
                    .containsOnly(active.getId());
        }
    }

    @Nested
    @DisplayName("Batch refresh (multiple connections)")
    class BatchRefresh {

        @Test
        @DisplayName("should refresh multiple connections independently — one revocation does not stop others")
        void shouldRefreshMultipleConnectionsIndependently() {
            // Given
            User user1 = testData.createVerifiedUser();
            User user2 = testData.createVerifiedUser();
            User user3 = testData.createVerifiedUser();

            MonzoConnection conn1 = testData.createMonzoConnectionWithRealTokens(
                    user1, "refresh-token-1", Instant.now().minus(1, ChronoUnit.HOURS)
            );
            MonzoConnection conn2 = testData.createMonzoConnectionWithRealTokens(
                    user2, "refresh-token-2", Instant.now().minus(1, ChronoUnit.HOURS)
            );
            MonzoConnection conn3 = testData.createMonzoConnectionWithRealTokens(
                    user3, "refresh-token-3", Instant.now().minus(1, ChronoUnit.HOURS)
            );

            // conn1 and conn3: success; conn2: revoked
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .withRequestBody(containing("refresh_token=refresh-token-1"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(tokenResponseBody("access-1", "refresh-1-new", 21600))));
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .withRequestBody(containing("refresh_token=refresh-token-2"))
                    .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .withRequestBody(containing("refresh_token=refresh-token-3"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(tokenResponseBody("access-3", "refresh-3-new", 21600))));

            // When
            tokenRefreshService.refresh(conn1.getId());
            tokenRefreshService.refresh(conn2.getId());
            tokenRefreshService.refresh(conn3.getId());

            // Then
            MonzoConnection fromDb1 = connectionRepository.findById(conn1.getId()).orElseThrow();
            MonzoConnection fromDb2 = connectionRepository.findById(conn2.getId()).orElseThrow();
            MonzoConnection fromDb3 = connectionRepository.findById(conn3.getId()).orElseThrow();

            assertThat(fromDb1.isActive()).isTrue();
            assertThat(encryptionService.decrypt(fromDb1.getAccessTokenEncrypted())).isEqualTo("access-1");

            assertThat(fromDb2.isActive()).isFalse(); // revoked → disconnected

            assertThat(fromDb3.isActive()).isTrue();
            assertThat(encryptionService.decrypt(fromDb3.getAccessTokenEncrypted())).isEqualTo("access-3");
        }
    }

    @Nested
    @DisplayName("Eager refresh via MonzoConnectionService")
    class EagerRefreshViaConnectionService {

        @Test
        @DisplayName("getDecryptedAccessToken() should return fresh token after eager refresh")
        void shouldReturnFreshTokenAfterEagerRefresh() {
            // Given - token expires in 2 minutes (within 5-min eager window)
            User user = testData.createVerifiedUser();
            MonzoConnection connection = testData.createMonzoConnectionWithRealTokens(
                    user, "eager-refresh-token", Instant.now().plus(2, ChronoUnit.MINUTES)
            );

            stubMonzoTokenRefreshSuccess("eagerly-refreshed-access", "new-refresh-after-eager", 21600);

            // When
            String decryptedToken = connectionService.getDecryptedAccessToken(
                    connection.getId(), user.getId()
            );

            // Then
            assertThat(decryptedToken).isEqualTo("eagerly-refreshed-access");

            // DB reflects the refresh
            MonzoConnection fromDb = connectionRepository.findById(connection.getId()).orElseThrow();
            assertThat(encryptionService.decrypt(fromDb.getAccessTokenEncrypted()))
                    .isEqualTo("eagerly-refreshed-access");
        }

        @Test
        @DisplayName("getDecryptedAccessToken() should throw PROVIDER_CONNECTION_REVOKED if eager refresh disconnects connection")
        void shouldThrowWhenEagerRefreshRevealsRevocation() {
            // Given
            User user = testData.createVerifiedUser();
            MonzoConnection connection = testData.createMonzoConnectionWithRealTokens(
                    user, "about-to-be-revoked", Instant.now().plus(2, ChronoUnit.MINUTES)
            );

            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));

            // When/Then
            assertThatThrownBy(() ->
                    connectionService.getDecryptedAccessToken(connection.getId(), user.getId()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_CONNECTION_REVOKED);
                    });

            // Connection should be disconnected in DB
            MonzoConnection fromDb = connectionRepository.findById(connection.getId()).orElseThrow();
            assertThat(fromDb.isActive()).isFalse();
        }

        @Test
        @DisplayName("getDecryptedAccessToken() should NOT call refresh for healthy tokens")
        void shouldNotRefreshHealthyToken() {
            // Given - token expires in 2 hours (healthy)
            User user = testData.createVerifiedUser();
            MonzoConnection connection = testData.createMonzoConnectionWithRealTokens(
                    user, "healthy-refresh-token", Instant.now().plus(2, ChronoUnit.HOURS)
            );

            // When
            connectionService.getDecryptedAccessToken(connection.getId(), user.getId());

            // Then - Monzo was NOT called
            wm.verify(0, postRequestedFor(urlPathEqualTo("/oauth2/token")));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void stubMonzoTokenRefreshSuccess(String accessToken, String refreshToken, int expiresIn) {
        wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tokenResponseBody(accessToken, refreshToken, expiresIn))));
    }

    private String tokenResponseBody(String accessToken, String refreshToken, int expiresIn) {
        return String.format("""
                {
                    "access_token": "%s",
                    "refresh_token": "%s",
                    "token_type": "Bearer",
                    "expires_in": %d
                }
                """, accessToken, refreshToken, expiresIn);
    }
}
