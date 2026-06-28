package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.config.MonzoTokenRefreshProperties;
import dev.amfshr.budgeteer.service.common.EncryptionService;
import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.repository.UserRepository;
import dev.amfshr.budgeteer.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MonzoConnectionService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonzoConnectionService")
class MonzoConnectionServiceTest {

    @Mock
    private MonzoConnectionRepository connectionRepository;

    @Mock
    private MonzoAccountRepository accountRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private MonzoTokenRefreshService tokenRefreshService;

    private MonzoConnectionService service;

    private User testUser;
    private UUID userId;
    private UUID connectionId;
    private MonzoConnection testConnection;
    private static final String MONZO_USER_ID = "user_abc123";
    private static final String ACCESS_TOKEN = "access_token_plain";
    private static final String REFRESH_TOKEN = "refresh_token_plain";
    private static final String ACCESS_TOKEN_ENC = "encrypted_access_token";
    private static final String REFRESH_TOKEN_ENC = "encrypted_refresh_token";

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        connectionId = UUID.randomUUID();

        testUser = new User("test@example.com");
        testUser.setId(userId);

        testConnection = new MonzoConnection(
                testUser,
                MONZO_USER_ID,
                ACCESS_TOKEN_ENC,
                REFRESH_TOKEN_ENC,
                Instant.now().plus(6, ChronoUnit.HOURS)
        );
        testConnection.setId(connectionId);

        service = new MonzoConnectionService(
                connectionRepository,
                accountRepository,
                userRepository,
                encryptionService,
                tokenRefreshService,
                new MonzoTokenRefreshProperties(60, 5)
        );
    }

    @Nested
    @DisplayName("createConnection()")
    class CreateConnectionTests {

        @Test
        @DisplayName("should create new connection when no existing connection")
        void shouldCreateNewConnection() {
            // Given
            Instant expiresAt = Instant.now().plus(6, ChronoUnit.HOURS);
            when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
            when(connectionRepository.findByUserIdAndMonzoUserId(userId, MONZO_USER_ID))
                    .thenReturn(Optional.empty());
            when(encryptionService.encrypt(ACCESS_TOKEN)).thenReturn(ACCESS_TOKEN_ENC);
            when(encryptionService.encrypt(REFRESH_TOKEN)).thenReturn(REFRESH_TOKEN_ENC);
            when(connectionRepository.save(any(MonzoConnection.class))).thenAnswer(inv -> {
                MonzoConnection conn = inv.getArgument(0);
                conn.setId(connectionId);
                return conn;
            });

            // When
            MonzoConnection result = service.createConnection(
                    userId, MONZO_USER_ID, ACCESS_TOKEN, REFRESH_TOKEN, expiresAt
            );

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getMonzoUserId()).isEqualTo(MONZO_USER_ID);
            assertThat(result.getAccessTokenEncrypted()).isEqualTo(ACCESS_TOKEN_ENC);
            assertThat(result.getRefreshTokenEncrypted()).isEqualTo(REFRESH_TOKEN_ENC);

            ArgumentCaptor<MonzoConnection> captor = ArgumentCaptor.forClass(MonzoConnection.class);
            verify(connectionRepository).save(captor.capture());
            MonzoConnection saved = captor.getValue();
            assertThat(saved.getUser()).isEqualTo(testUser);
        }

        @Test
        @DisplayName("should update existing active connection when same Monzo account")
        void shouldUpdateExistingConnection() {
            // Given
            Instant newExpiresAt = Instant.now().plus(12, ChronoUnit.HOURS);
            String newAccessToken = "new_access_token";
            String newRefreshToken = "new_refresh_token";
            String newAccessTokenEnc = "new_encrypted_access";
            String newRefreshTokenEnc = "new_encrypted_refresh";

            when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
            when(connectionRepository.findByUserIdAndMonzoUserId(userId, MONZO_USER_ID))
                    .thenReturn(Optional.of(testConnection));
            when(encryptionService.encrypt(newAccessToken)).thenReturn(newAccessTokenEnc);
            when(encryptionService.encrypt(newRefreshToken)).thenReturn(newRefreshTokenEnc);
            when(connectionRepository.save(any(MonzoConnection.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            MonzoConnection result = service.createConnection(
                    userId, MONZO_USER_ID, newAccessToken, newRefreshToken, newExpiresAt
            );

            // Then
            assertThat(result.getId()).isEqualTo(connectionId);
            assertThat(result.getAccessTokenEncrypted()).isEqualTo(newAccessTokenEnc);
            assertThat(result.getRefreshTokenEncrypted()).isEqualTo(newRefreshTokenEnc);
            assertThat(result.isActive()).isTrue();
        }

        @Test
        @DisplayName("should reactivate soft-deleted connection when reconnecting same Monzo account")
        void shouldReactivateSoftDeletedConnection() {
            // Given
            Instant newExpiresAt = Instant.now().plus(12, ChronoUnit.HOURS);
            String newAccessToken = "new_access_token";
            String newRefreshToken = "new_refresh_token";
            String newAccessTokenEnc = "new_encrypted_access";
            String newRefreshTokenEnc = "new_encrypted_refresh";

            // Soft-delete the test connection
            testConnection.disconnect();
            assertThat(testConnection.isActive()).isFalse();

            when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
            when(connectionRepository.findByUserIdAndMonzoUserId(userId, MONZO_USER_ID))
                    .thenReturn(Optional.of(testConnection));
            when(encryptionService.encrypt(newAccessToken)).thenReturn(newAccessTokenEnc);
            when(encryptionService.encrypt(newRefreshToken)).thenReturn(newRefreshTokenEnc);
            when(connectionRepository.save(any(MonzoConnection.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            MonzoConnection result = service.createConnection(
                    userId, MONZO_USER_ID, newAccessToken, newRefreshToken, newExpiresAt
            );

            // Then
            assertThat(result.getId()).isEqualTo(connectionId);
            assertThat(result.isActive()).isTrue(); // Reactivated!
            assertThat(result.getDisconnectedAt()).isNull();
            assertThat(result.getAccessTokenEncrypted()).isEqualTo(newAccessTokenEnc);
            assertThat(result.getRefreshTokenEncrypted()).isEqualTo(newRefreshTokenEnc);
        }

        @Test
        @DisplayName("should throw when user not found")
        void shouldThrowWhenUserNotFound() {
            // Given
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> service.createConnection(
                    userId, MONZO_USER_ID, ACCESS_TOKEN, REFRESH_TOKEN, Instant.now()
            ))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
                    });

            verify(connectionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getConnection()")
    class GetConnectionTests {

        @Test
        @DisplayName("should return connection when found and owned by user")
        void shouldReturnConnectionWhenFound() {
            // Given
            when(connectionRepository.findByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.of(testConnection));

            // When
            MonzoConnection result = service.getConnection(connectionId, userId);

            // Then
            assertThat(result).isEqualTo(testConnection);
        }

        @Test
        @DisplayName("should throw when connection not found")
        void shouldThrowWhenConnectionNotFound() {
            // Given
            when(connectionRepository.findByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> service.getConnection(connectionId, userId))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
                    });
        }
    }

    @Nested
    @DisplayName("getActiveConnection()")
    class GetActiveConnectionTests {

        @Test
        @DisplayName("should return active connection when found")
        void shouldReturnActiveConnectionWhenFound() {
            // Given
            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.of(testConnection));

            // When
            MonzoConnection result = service.getActiveConnection(connectionId, userId);

            // Then
            assertThat(result).isEqualTo(testConnection);
        }

        @Test
        @DisplayName("should throw when active connection not found")
        void shouldThrowWhenActiveConnectionNotFound() {
            // Given
            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> service.getActiveConnection(connectionId, userId))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
                    });
        }
    }

    @Nested
    @DisplayName("listActiveConnections()")
    class ListActiveConnectionsTests {

        @Test
        @DisplayName("should return list of active connections")
        void shouldReturnActiveConnections() {
            // Given
            MonzoConnection connection2 = new MonzoConnection(
                    testUser, "user_xyz789", "enc1", "enc2",
                    Instant.now().plus(6, ChronoUnit.HOURS)
            );
            connection2.setId(UUID.randomUUID());

            when(connectionRepository.findActiveByUserId(userId))
                    .thenReturn(List.of(testConnection, connection2));

            // When
            List<MonzoConnection> result = service.listActiveConnections(userId);

            // Then
            assertThat(result).hasSize(2);
            assertThat(result).containsExactly(testConnection, connection2);
        }

        @Test
        @DisplayName("should return empty list when no connections")
        void shouldReturnEmptyListWhenNoConnections() {
            // Given
            when(connectionRepository.findActiveByUserId(userId))
                    .thenReturn(List.of());

            // When
            List<MonzoConnection> result = service.listActiveConnections(userId);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("disconnectConnection()")
    class DisconnectConnectionTests {

        @Test
        @DisplayName("should soft-delete connection")
        void shouldSoftDeleteConnection() {
            // Given
            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.of(testConnection));
            when(connectionRepository.save(any(MonzoConnection.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            service.disconnectConnection(connectionId, userId);

            // Then
            assertThat(testConnection.isActive()).isFalse();
            assertThat(testConnection.getDisconnectedAt()).isNotNull();
            verify(connectionRepository).save(testConnection);
        }

        @Test
        @DisplayName("should throw when connection not found")
        void shouldThrowWhenConnectionNotFound() {
            // Given
            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> service.disconnectConnection(connectionId, userId))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
                    });

            verify(connectionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateTokens()")
    class UpdateTokensTests {

        @Test
        @DisplayName("should update tokens with encrypted values")
        void shouldUpdateTokensWithEncryptedValues() {
            // Given
            String newAccessToken = "new_access_token";
            String newRefreshToken = "new_refresh_token";
            String newAccessTokenEnc = "new_enc_access";
            String newRefreshTokenEnc = "new_enc_refresh";
            Instant newExpiresAt = Instant.now().plus(12, ChronoUnit.HOURS);

            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.of(testConnection));
            when(encryptionService.encrypt(newAccessToken)).thenReturn(newAccessTokenEnc);
            when(encryptionService.encrypt(newRefreshToken)).thenReturn(newRefreshTokenEnc);
            when(connectionRepository.save(any(MonzoConnection.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            MonzoConnection result = service.updateTokens(
                    connectionId, userId, newAccessToken, newRefreshToken, newExpiresAt
            );

            // Then
            assertThat(result.getAccessTokenEncrypted()).isEqualTo(newAccessTokenEnc);
            assertThat(result.getRefreshTokenEncrypted()).isEqualTo(newRefreshTokenEnc);
            assertThat(result.getTokenExpiresAt()).isEqualTo(newExpiresAt);
            verify(connectionRepository).save(testConnection);
        }

        @Test
        @DisplayName("should throw when connection not found")
        void shouldThrowWhenConnectionNotFound() {
            // Given
            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> service.updateTokens(
                    connectionId, userId, "token", "token", Instant.now()
            ))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
                    });

            verify(encryptionService, never()).encrypt(anyString());
            verify(connectionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getDecryptedAccessToken()")
    class GetDecryptedAccessTokenTests {

        @Test
        @DisplayName("should return decrypted access token when token is healthy")
        void shouldReturnDecryptedAccessToken() {
            // Given - token expires in 6 hours (not expiring soon)
            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.of(testConnection));
            when(encryptionService.decrypt(ACCESS_TOKEN_ENC)).thenReturn(ACCESS_TOKEN);

            // When
            String result = service.getDecryptedAccessToken(connectionId, userId);

            // Then
            assertThat(result).isEqualTo(ACCESS_TOKEN);
            verifyNoInteractions(tokenRefreshService);
        }

        @Test
        @DisplayName("should eagerly refresh and return new access token when token is expiring soon")
        void shouldEagerlyRefreshWhenTokenExpiringSoon() {
            // Given - token expires in 2 minutes (within the 5-min eager window)
            MonzoConnection expiringSoonConnection = new MonzoConnection(
                    testUser, MONZO_USER_ID, ACCESS_TOKEN_ENC, REFRESH_TOKEN_ENC,
                    Instant.now().plus(2, ChronoUnit.MINUTES)
            );
            expiringSoonConnection.setId(connectionId);

            String newAccessTokenEnc = "new_encrypted_access";
            String newAccessToken = "new_access_token";
            MonzoConnection refreshedConnection = new MonzoConnection(
                    testUser, MONZO_USER_ID, newAccessTokenEnc, REFRESH_TOKEN_ENC,
                    Instant.now().plus(6, ChronoUnit.HOURS)
            );
            refreshedConnection.setId(connectionId);

            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.of(expiringSoonConnection));
            when(tokenRefreshService.refresh(connectionId)).thenReturn(refreshedConnection);
            when(encryptionService.decrypt(newAccessTokenEnc)).thenReturn(newAccessToken);

            // When
            String result = service.getDecryptedAccessToken(connectionId, userId);

            // Then
            assertThat(result).isEqualTo(newAccessToken);
            verify(tokenRefreshService).refresh(connectionId);
        }

        @Test
        @DisplayName("should throw MONZO_CONNECTION_REVOKED when eager refresh disconnects the connection")
        void shouldThrowWhenEagerRefreshDisconnectsConnection() {
            // Given - token expiring soon
            MonzoConnection expiringSoonConnection = new MonzoConnection(
                    testUser, MONZO_USER_ID, ACCESS_TOKEN_ENC, REFRESH_TOKEN_ENC,
                    Instant.now().plus(2, ChronoUnit.MINUTES)
            );
            expiringSoonConnection.setId(connectionId);

            // Refresh service returns a disconnected connection (Monzo revoked it)
            MonzoConnection disconnectedConnection = new MonzoConnection(
                    testUser, MONZO_USER_ID, ACCESS_TOKEN_ENC, REFRESH_TOKEN_ENC,
                    Instant.now().minus(1, ChronoUnit.HOURS)
            );
            disconnectedConnection.setId(connectionId);
            disconnectedConnection.disconnect();

            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.of(expiringSoonConnection));
            when(tokenRefreshService.refresh(connectionId)).thenReturn(disconnectedConnection);

            // When/Then
            assertThatThrownBy(() -> service.getDecryptedAccessToken(connectionId, userId))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.MONZO_CONNECTION_REVOKED);
                    });

            verify(encryptionService, never()).decrypt(anyString());
        }

        @Test
        @DisplayName("should throw when connection not found")
        void shouldThrowWhenConnectionNotFound() {
            // Given
            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> service.getDecryptedAccessToken(connectionId, userId))
                    .isInstanceOf(ApiException.class);

            verify(encryptionService, never()).decrypt(anyString());
        }
    }

    @Nested
    @DisplayName("getDecryptedRefreshToken()")
    class GetDecryptedRefreshTokenTests {

        @Test
        @DisplayName("should return decrypted refresh token")
        void shouldReturnDecryptedRefreshToken() {
            // Given
            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.of(testConnection));
            when(encryptionService.decrypt(REFRESH_TOKEN_ENC)).thenReturn(REFRESH_TOKEN);

            // When
            String result = service.getDecryptedRefreshToken(connectionId, userId);

            // Then
            assertThat(result).isEqualTo(REFRESH_TOKEN);
        }
    }

    @Nested
    @DisplayName("getDecryptedTokens()")
    class GetDecryptedTokensTests {

        @Test
        @DisplayName("should return both decrypted tokens")
        void shouldReturnBothDecryptedTokens() {
            // Given
            when(connectionRepository.findActiveByIdAndUserId(connectionId, userId))
                    .thenReturn(Optional.of(testConnection));
            when(encryptionService.decrypt(ACCESS_TOKEN_ENC)).thenReturn(ACCESS_TOKEN);
            when(encryptionService.decrypt(REFRESH_TOKEN_ENC)).thenReturn(REFRESH_TOKEN);

            // When
            MonzoConnectionService.DecryptedTokens result = service.getDecryptedTokens(connectionId, userId);

            // Then
            assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
            assertThat(result.refreshToken()).isEqualTo(REFRESH_TOKEN);
        }
    }

    @Nested
    @DisplayName("hasActiveConnection()")
    class HasActiveConnectionTests {

        @Test
        @DisplayName("should return true when user has active connection")
        void shouldReturnTrueWhenHasActiveConnection() {
            // Given
            when(connectionRepository.hasActiveConnectionForUser(userId)).thenReturn(true);

            // When
            boolean result = service.hasActiveConnection(userId);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when user has no active connection")
        void shouldReturnFalseWhenNoActiveConnection() {
            // Given
            when(connectionRepository.hasActiveConnectionForUser(userId)).thenReturn(false);

            // When
            boolean result = service.hasActiveConnection(userId);

            // Then
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("countActiveConnections()")
    class CountActiveConnectionsTests {

        @Test
        @DisplayName("should return count of active connections")
        void shouldReturnCountOfActiveConnections() {
            // Given
            when(connectionRepository.countActiveByUserId(userId)).thenReturn(3L);

            // When
            long result = service.countActiveConnections(userId);

            // Then
            assertThat(result).isEqualTo(3L);
        }

        @Test
        @DisplayName("should return zero when no active connections")
        void shouldReturnZeroWhenNoConnections() {
            // Given
            when(connectionRepository.countActiveByUserId(userId)).thenReturn(0L);

            // When
            long result = service.countActiveConnections(userId);

            // Then
            assertThat(result).isZero();
        }
    }

    @Nested
    @DisplayName("getTokenStatus()")
    class GetTokenStatusTests {

        @Test
        @DisplayName("should return RECONNECT_REQUIRED when no active connections")
        void shouldReturnReconnectRequiredWhenNoConnections() {
            // Given
            when(connectionRepository.findActiveByUserId(userId)).thenReturn(List.of());

            // When
            MonzoConnectionService.TokenStatus result = service.getTokenStatus(userId);

            // Then
            assertThat(result).isEqualTo(MonzoConnectionService.TokenStatus.RECONNECT_REQUIRED);
        }

        @Test
        @DisplayName("should return ACTIVE when all tokens are healthy")
        void shouldReturnActiveWhenAllTokensHealthy() {
            // Given - testConnection expires in 6 hours (healthy)
            when(connectionRepository.findActiveByUserId(userId)).thenReturn(List.of(testConnection));

            // When
            MonzoConnectionService.TokenStatus result = service.getTokenStatus(userId);

            // Then
            assertThat(result).isEqualTo(MonzoConnectionService.TokenStatus.ACTIVE);
        }

        @Test
        @DisplayName("should return EXPIRING_SOON when a token is expiring within the window")
        void shouldReturnExpiringSoonWhenTokenNearExpiry() {
            // Given - connection expires in 2 minutes
            MonzoConnection expiringSoon = new MonzoConnection(
                    testUser, MONZO_USER_ID, ACCESS_TOKEN_ENC, REFRESH_TOKEN_ENC,
                    Instant.now().plus(2, ChronoUnit.MINUTES)
            );
            expiringSoon.setId(UUID.randomUUID());
            when(connectionRepository.findActiveByUserId(userId)).thenReturn(List.of(expiringSoon));

            // When
            MonzoConnectionService.TokenStatus result = service.getTokenStatus(userId);

            // Then
            assertThat(result).isEqualTo(MonzoConnectionService.TokenStatus.EXPIRING_SOON);
        }

        @Test
        @DisplayName("should return EXPIRING_SOON when any connection is expiring (mixed state)")
        void shouldReturnExpiringSoonWhenAnyConnectionExpiring() {
            // Given - one healthy, one expiring soon
            MonzoConnection expiringSoon = new MonzoConnection(
                    testUser, "user_other", ACCESS_TOKEN_ENC, REFRESH_TOKEN_ENC,
                    Instant.now().plus(2, ChronoUnit.MINUTES)
            );
            expiringSoon.setId(UUID.randomUUID());
            when(connectionRepository.findActiveByUserId(userId))
                    .thenReturn(List.of(testConnection, expiringSoon));

            // When
            MonzoConnectionService.TokenStatus result = service.getTokenStatus(userId);

            // Then
            assertThat(result).isEqualTo(MonzoConnectionService.TokenStatus.EXPIRING_SOON);
        }
    }
}
