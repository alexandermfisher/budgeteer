package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.bank.BankClient;
import dev.amfshr.budgeteer.bank.BankClientException;
import dev.amfshr.budgeteer.bank.BankConnectionRevokedException;
import dev.amfshr.budgeteer.bank.BankTokens;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.service.common.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MonzoTokenRefreshService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonzoTokenRefreshService")
class MonzoTokenRefreshServiceTest {

    @Mock
    private MonzoConnectionRepository connectionRepository;

    @Mock
    private BankClient bankClient;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private MonzoTokenRefreshService service;

    private User testUser;
    private UUID connectionId;
    private MonzoConnection activeConnection;

    private static final String REFRESH_TOKEN_ENC = "encrypted_refresh_token";
    private static final String REFRESH_TOKEN_PLAIN = "plain_refresh_token";
    private static final String OLD_ACCESS_TOKEN_ENC = "encrypted_old_access_token";

    @BeforeEach
    void setUp() {
        testUser = new User("test@example.com");
        testUser.setId(UUID.randomUUID());

        connectionId = UUID.randomUUID();

        activeConnection = new MonzoConnection(
                testUser,
                "user_abc123",
                OLD_ACCESS_TOKEN_ENC,
                REFRESH_TOKEN_ENC,
                Instant.now().plus(1, ChronoUnit.MINUTES)
        );
        activeConnection.setId(connectionId);
    }

    @Nested
    @DisplayName("refresh(UUID)")
    class RefreshTests {

        @Test
        @DisplayName("should refresh tokens successfully and persist new encrypted values")
        void shouldRefreshTokensSuccessfully() {
            // Given
            String newAccessTokenPlain = "new_access_token";
            String newRefreshTokenPlain = "new_refresh_token";
            String newAccessTokenEnc = "encrypted_new_access_token";
            String newRefreshTokenEnc = "encrypted_new_refresh_token";
            Instant newExpiresAt = Instant.now().plus(6, ChronoUnit.HOURS);

            BankTokens response = new BankTokens(
                    newAccessTokenPlain, newRefreshTokenPlain, newExpiresAt
            );

            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(activeConnection));
            when(encryptionService.decrypt(REFRESH_TOKEN_ENC)).thenReturn(REFRESH_TOKEN_PLAIN);
            when(bankClient.refreshTokens(REFRESH_TOKEN_PLAIN)).thenReturn(response);
            when(encryptionService.encrypt(newAccessTokenPlain)).thenReturn(newAccessTokenEnc);
            when(encryptionService.encrypt(newRefreshTokenPlain)).thenReturn(newRefreshTokenEnc);
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            MonzoConnection result = service.refresh(connectionId);

            // Then
            assertThat(result.getAccessTokenEncrypted()).isEqualTo(newAccessTokenEnc);
            assertThat(result.getRefreshTokenEncrypted()).isEqualTo(newRefreshTokenEnc);
            assertThat(result.getTokenExpiresAt()).isEqualTo(newExpiresAt);
            assertThat(result.isActive()).isTrue();

            ArgumentCaptor<MonzoConnection> captor = ArgumentCaptor.forClass(MonzoConnection.class);
            verify(connectionRepository).save(captor.capture());
            assertThat(captor.getValue().getAccessTokenEncrypted()).isEqualTo(newAccessTokenEnc);
        }

        @Test
        @DisplayName("should use the new refresh token when Monzo rotates it")
        void shouldUseNewRefreshTokenWhenMonzoRotatesIt() {
            // Given
            String rotatedRefreshToken = "rotated_refresh_token";
            String rotatedRefreshTokenEnc = "encrypted_rotated_refresh_token";

            BankTokens response = new BankTokens(
                    "new_access", rotatedRefreshToken, Instant.now().plus(6, ChronoUnit.HOURS)
            );

            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(activeConnection));
            when(encryptionService.decrypt(REFRESH_TOKEN_ENC)).thenReturn(REFRESH_TOKEN_PLAIN);
            when(bankClient.refreshTokens(REFRESH_TOKEN_PLAIN)).thenReturn(response);
            when(encryptionService.encrypt("new_access")).thenReturn("enc_new_access");
            when(encryptionService.encrypt(rotatedRefreshToken)).thenReturn(rotatedRefreshTokenEnc);
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            MonzoConnection result = service.refresh(connectionId);

            // Then
            assertThat(result.getRefreshTokenEncrypted()).isEqualTo(rotatedRefreshTokenEnc);
            verify(encryptionService).encrypt(rotatedRefreshToken);
        }

        @Test
        @DisplayName("should use fallback expiry when Monzo does not return expires_in")
        void shouldUseFallbackExpiryWhenMonzoDoesNotReturnExpiry() {
            // Given - expiresAt is null
            BankTokens response = new BankTokens(
                    "new_access", "new_refresh", null
            );

            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(activeConnection));
            when(encryptionService.decrypt(REFRESH_TOKEN_ENC)).thenReturn(REFRESH_TOKEN_PLAIN);
            when(bankClient.refreshTokens(REFRESH_TOKEN_PLAIN)).thenReturn(response);
            when(encryptionService.encrypt(anyString())).thenReturn("some_enc");
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            MonzoConnection result = service.refresh(connectionId);

            // Then - expiry should be set to now + ~6 hours (fallback)
            assertThat(result.getTokenExpiresAt())
                    .isAfter(Instant.now().plus(5, ChronoUnit.HOURS))
                    .isBefore(Instant.now().plus(7, ChronoUnit.HOURS));
        }

        @Test
        @DisplayName("should soft-delete connection and return when Monzo returns 401 (revoked)")
        void shouldDisconnectConnectionWhenMonzoReturns401() {
            // Given
            BankConnectionRevokedException revokedException = new BankConnectionRevokedException(
                    "Your Monzo connection has been revoked."
            );

            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(activeConnection));
            when(encryptionService.decrypt(REFRESH_TOKEN_ENC)).thenReturn(REFRESH_TOKEN_PLAIN);
            when(bankClient.refreshTokens(REFRESH_TOKEN_PLAIN)).thenThrow(revokedException);
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            MonzoConnection result = service.refresh(connectionId);

            // Then - connection is soft-deleted, no exception thrown
            assertThat(result.isActive()).isFalse();
            assertThat(result.getDisconnectedAt()).isNotNull();

            ArgumentCaptor<MonzoConnection> captor = ArgumentCaptor.forClass(MonzoConnection.class);
            verify(connectionRepository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isFalse();
        }

        @Test
        @DisplayName("should re-throw and NOT disconnect connection on non-401 Monzo errors")
        void shouldRethrowOnNonRevocationError() {
            // Given
            BankClientException monzoError = new BankClientException(
                    "Monzo API rate limit reached."
            );

            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(activeConnection));
            when(encryptionService.decrypt(REFRESH_TOKEN_ENC)).thenReturn(REFRESH_TOKEN_PLAIN);
            when(bankClient.refreshTokens(REFRESH_TOKEN_PLAIN)).thenThrow(monzoError);

            // When/Then
            assertThatThrownBy(() -> service.refresh(connectionId))
                    .isInstanceOf(BankClientException.class)
                    .satisfies(ex -> {
                        BankClientException apiEx = (BankClientException) ex;
                        assertThat(apiEx.getMessage()).isEqualTo("Monzo API rate limit reached.");
                    });

            // Connection should NOT have been disconnected
            assertThat(activeConnection.isActive()).isTrue();
            // save() should not have been called since we didn't change the connection
            verify(connectionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw RESOURCE_NOT_FOUND when connection does not exist")
        void shouldThrowWhenConnectionNotFound() {
            // Given
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> service.refresh(connectionId))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
                    });

            verify(bankClient, never()).refreshTokens(anyString());
        }

        @Test
        @DisplayName("should skip refresh and return connection when already inactive")
        void shouldSkipRefreshForInactiveConnection() {
            // Given
            activeConnection.disconnect();

            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(activeConnection));

            // When
            MonzoConnection result = service.refresh(connectionId);

            // Then
            assertThat(result.isActive()).isFalse();
            verify(bankClient, never()).refreshTokens(anyString());
            verify(connectionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("findExpiringConnections()")
    class FindExpiringConnectionsTests {

        @Test
        @DisplayName("should delegate to repository with provided threshold")
        void shouldDelegateToRepository() {
            // Given
            Instant threshold = Instant.now().plus(60, ChronoUnit.MINUTES);
            MonzoConnection conn1 = new MonzoConnection(
                    testUser, "user_1", "enc_a1", "enc_r1",
                    Instant.now().plus(30, ChronoUnit.MINUTES)
            );
            MonzoConnection conn2 = new MonzoConnection(
                    testUser, "user_2", "enc_a2", "enc_r2",
                    Instant.now().minus(1, ChronoUnit.HOURS)
            );

            when(connectionRepository.findActiveExpiringBefore(threshold))
                    .thenReturn(List.of(conn1, conn2));

            // When
            List<MonzoConnection> result = service.findExpiringConnections(threshold);

            // Then
            assertThat(result).containsExactly(conn1, conn2);
            verify(connectionRepository).findActiveExpiringBefore(threshold);
        }

        @Test
        @DisplayName("should return empty list when no connections expiring")
        void shouldReturnEmptyListWhenNoneExpiring() {
            // Given
            Instant threshold = Instant.now().plus(60, ChronoUnit.MINUTES);
            when(connectionRepository.findActiveExpiringBefore(threshold)).thenReturn(List.of());

            // When
            List<MonzoConnection> result = service.findExpiringConnections(threshold);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
