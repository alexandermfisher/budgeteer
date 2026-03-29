package dev.amf.budgeteer.service.auth;

import dev.amf.budgeteer.config.JweProperties;
import dev.amf.budgeteer.domain.session.AppRefreshToken;
import dev.amf.budgeteer.repository.AppRefreshTokenRepository;
import dev.amf.budgeteer.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SessionService}.
 *
 * <p>Uses Mockito to mock the repository and JweTokenService dependencies.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("SessionService")
class SessionServiceTest {

    @Mock
    private AppRefreshTokenRepository refreshTokenRepository;

    @Mock
    private JweTokenService jweTokenService;

    private JweProperties jweProperties;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        jweProperties = new JweProperties();
        jweProperties.setAccessTokenExpiry(Duration.ofMinutes(15));
        jweProperties.setRefreshTokenExpiry(Duration.ofDays(7));

        sessionService = new SessionService(refreshTokenRepository, jweTokenService, jweProperties);
    }

    private User createTestUser() {
        User user = new User("test@example.com");
        user.setId(UUID.randomUUID());
        user.setEmailVerified(true);
        return user;
    }

    @Nested
    @DisplayName("createSession")
    class CreateSession {

        @Test
        @DisplayName("should create access and refresh tokens")
        void shouldCreateBothTokens() {
            // Given
            User user = createTestUser();
            String expectedAccessToken = "mocked-access-token";
            when(jweTokenService.createAccessToken(user)).thenReturn(expectedAccessToken);
            when(refreshTokenRepository.save(any(AppRefreshToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            SessionService.SessionTokens tokens = sessionService.createSession(user, "TestAgent", "127.0.0.1");

            // Then
            assertThat(tokens.accessToken()).isEqualTo(expectedAccessToken);
            assertThat(tokens.refreshToken()).isNotNull();
            assertThat(tokens.refreshToken()).isNotEmpty();
        }

        @Test
        @DisplayName("should store refresh token hash in database")
        void shouldStoreRefreshTokenHash() {
            // Given
            User user = createTestUser();
            when(jweTokenService.createAccessToken(user)).thenReturn("access-token");

            ArgumentCaptor<AppRefreshToken> tokenCaptor = ArgumentCaptor.forClass(AppRefreshToken.class);
            when(refreshTokenRepository.save(tokenCaptor.capture()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            SessionService.SessionTokens tokens = sessionService.createSession(user, "TestAgent", "127.0.0.1");

            // Then
            AppRefreshToken savedToken = tokenCaptor.getValue();
            assertThat(savedToken.getUser()).isEqualTo(user);
            assertThat(savedToken.getTokenHash()).isNotNull();
            assertThat(savedToken.getTokenHash()).hasSize(64); // SHA-256 hex = 64 chars
            assertThat(savedToken.getUserAgent()).isEqualTo("TestAgent");
            assertThat(savedToken.getIpAddress()).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("should set correct expiry time on refresh token")
        void shouldSetCorrectExpiry() {
            // Given
            User user = createTestUser();
            when(jweTokenService.createAccessToken(user)).thenReturn("access-token");

            ArgumentCaptor<AppRefreshToken> tokenCaptor = ArgumentCaptor.forClass(AppRefreshToken.class);
            when(refreshTokenRepository.save(tokenCaptor.capture()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Instant beforeCreation = Instant.now();

            // When
            sessionService.createSession(user, null, null);

            // Then
            Instant afterCreation = Instant.now();
            AppRefreshToken savedToken = tokenCaptor.getValue();

            Instant expectedMinExpiry = beforeCreation.plus(jweProperties.getRefreshTokenExpiry());
            Instant expectedMaxExpiry = afterCreation.plus(jweProperties.getRefreshTokenExpiry());

            assertThat(savedToken.getExpiresAt()).isBetween(expectedMinExpiry, expectedMaxExpiry);
        }

        @Test
        @DisplayName("should generate unique refresh tokens for each session")
        void shouldGenerateUniqueTokens() {
            // Given
            User user = createTestUser();
            when(jweTokenService.createAccessToken(user)).thenReturn("access-token");
            when(refreshTokenRepository.save(any(AppRefreshToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            SessionService.SessionTokens tokens1 = sessionService.createSession(user, null, null);
            SessionService.SessionTokens tokens2 = sessionService.createSession(user, null, null);

            // Then
            assertThat(tokens1.refreshToken()).isNotEqualTo(tokens2.refreshToken());
        }
    }

    @Nested
    @DisplayName("refreshSession")
    class RefreshSession {

        @Test
        @DisplayName("should return new tokens for valid refresh token")
        void shouldReturnNewTokensForValidToken() {
            // Given
            User user = createTestUser();
            String oldRefreshToken = "old-refresh-token";
            String tokenHash = sessionService.hashToken(oldRefreshToken);

            AppRefreshToken existingToken = new AppRefreshToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));

            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existingToken));
            when(jweTokenService.createAccessToken(user)).thenReturn("new-access-token");
            when(refreshTokenRepository.save(any(AppRefreshToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            Optional<SessionService.SessionTokens> result =
                    sessionService.refreshSession(oldRefreshToken, "Agent", "127.0.0.1");

            // Then
            assertThat(result).isPresent();
            assertThat(result.get().accessToken()).isEqualTo("new-access-token");
            assertThat(result.get().refreshToken()).isNotNull();
            assertThat(result.get().refreshToken()).isNotEqualTo(oldRefreshToken);
        }

        @Test
        @DisplayName("should revoke old token on refresh (token rotation)")
        void shouldRevokeOldTokenOnRefresh() {
            // Given
            User user = createTestUser();
            String oldRefreshToken = "old-refresh-token";
            String tokenHash = sessionService.hashToken(oldRefreshToken);

            AppRefreshToken existingToken = new AppRefreshToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));

            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existingToken));
            when(jweTokenService.createAccessToken(user)).thenReturn("new-access-token");
            when(refreshTokenRepository.save(any(AppRefreshToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            sessionService.refreshSession(oldRefreshToken, null, null);

            // Then
            assertThat(existingToken.isRevoked()).isTrue();
            verify(refreshTokenRepository, times(2)).save(any(AppRefreshToken.class)); // Old revoked + new created
        }

        @Test
        @DisplayName("should return empty for non-existent refresh token")
        void shouldReturnEmptyForNonExistentToken() {
            // Given
            String invalidToken = "invalid-token";
            String tokenHash = sessionService.hashToken(invalidToken);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

            // When
            Optional<SessionService.SessionTokens> result =
                    sessionService.refreshSession(invalidToken, null, null);

            // Then
            assertThat(result).isEmpty();
            verify(jweTokenService, never()).createAccessToken(any());
        }

        @Test
        @DisplayName("should return empty for expired refresh token")
        void shouldReturnEmptyForExpiredToken() {
            // Given
            User user = createTestUser();
            String expiredToken = "expired-token";
            String tokenHash = sessionService.hashToken(expiredToken);

            AppRefreshToken existingToken = new AppRefreshToken(
                    user, tokenHash, Instant.now().minusSeconds(3600)); // Expired 1 hour ago

            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existingToken));

            // When
            Optional<SessionService.SessionTokens> result =
                    sessionService.refreshSession(expiredToken, null, null);

            // Then
            assertThat(result).isEmpty();
            verify(jweTokenService, never()).createAccessToken(any());
        }

        @Test
        @DisplayName("should return empty for revoked refresh token")
        void shouldReturnEmptyForRevokedToken() {
            // Given
            User user = createTestUser();
            String revokedToken = "revoked-token";
            String tokenHash = sessionService.hashToken(revokedToken);

            AppRefreshToken existingToken = new AppRefreshToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));
            existingToken.revoke(); // Already revoked

            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existingToken));

            // When
            Optional<SessionService.SessionTokens> result =
                    sessionService.refreshSession(revokedToken, null, null);

            // Then
            assertThat(result).isEmpty();
            verify(jweTokenService, never()).createAccessToken(any());
        }
    }

    @Nested
    @DisplayName("revokeSession")
    class RevokeSession {

        @Test
        @DisplayName("should revoke existing token and return true")
        void shouldRevokeExistingToken() {
            // Given
            User user = createTestUser();
            String refreshToken = "valid-refresh-token";
            String tokenHash = sessionService.hashToken(refreshToken);

            AppRefreshToken existingToken = new AppRefreshToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));

            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existingToken));
            when(refreshTokenRepository.save(existingToken)).thenReturn(existingToken);

            // When
            boolean result = sessionService.revokeSession(refreshToken);

            // Then
            assertThat(result).isTrue();
            assertThat(existingToken.isRevoked()).isTrue();
            verify(refreshTokenRepository).save(existingToken);
        }

        @Test
        @DisplayName("should return false for non-existent token")
        void shouldReturnFalseForNonExistentToken() {
            // Given
            String invalidToken = "invalid-token";
            String tokenHash = sessionService.hashToken(invalidToken);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

            // When
            boolean result = sessionService.revokeSession(invalidToken);

            // Then
            assertThat(result).isFalse();
            verify(refreshTokenRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("revokeAllSessions")
    class RevokeAllSessions {

        @Test
        @DisplayName("should call repository to revoke all tokens for user")
        void shouldRevokeAllTokensForUser() {
            // Given
            User user = createTestUser();
            when(refreshTokenRepository.revokeAllTokensForUser(eq(user), any(Instant.class))).thenReturn(3);

            // When
            int revoked = sessionService.revokeAllSessions(user);

            // Then
            assertThat(revoked).isEqualTo(3);
            verify(refreshTokenRepository).revokeAllTokensForUser(eq(user), any(Instant.class));
        }

        @Test
        @DisplayName("should return zero when user has no sessions")
        void shouldReturnZeroWhenNoSessions() {
            // Given
            User user = createTestUser();
            when(refreshTokenRepository.revokeAllTokensForUser(eq(user), any(Instant.class))).thenReturn(0);

            // When
            int revoked = sessionService.revokeAllSessions(user);

            // Then
            assertThat(revoked).isZero();
        }
    }

    @Nested
    @DisplayName("validateRefreshToken")
    class ValidateRefreshToken {

        @Test
        @DisplayName("should return user for valid token")
        void shouldReturnUserForValidToken() {
            // Given
            User user = createTestUser();
            String refreshToken = "valid-token";
            String tokenHash = sessionService.hashToken(refreshToken);

            AppRefreshToken existingToken = new AppRefreshToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));

            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existingToken));

            // When
            Optional<User> result = sessionService.validateRefreshToken(refreshToken);

            // Then
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(user);
        }

        @Test
        @DisplayName("should return empty for invalid token")
        void shouldReturnEmptyForInvalidToken() {
            // Given
            String invalidToken = "invalid-token";
            String tokenHash = sessionService.hashToken(invalidToken);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

            // When
            Optional<User> result = sessionService.validateRefreshToken(invalidToken);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for expired token")
        void shouldReturnEmptyForExpiredToken() {
            // Given
            User user = createTestUser();
            String expiredToken = "expired-token";
            String tokenHash = sessionService.hashToken(expiredToken);

            AppRefreshToken existingToken = new AppRefreshToken(
                    user, tokenHash, Instant.now().minusSeconds(3600));

            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existingToken));

            // When
            Optional<User> result = sessionService.validateRefreshToken(expiredToken);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("hashToken")
    class HashToken {

        @Test
        @DisplayName("should produce consistent hash for same input")
        void shouldProduceConsistentHash() {
            // Given
            String token = "my-token";

            // When
            String hash1 = sessionService.hashToken(token);
            String hash2 = sessionService.hashToken(token);

            // Then
            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("should produce different hashes for different inputs")
        void shouldProduceDifferentHashes() {
            // Given
            String token1 = "token-1";
            String token2 = "token-2";

            // When
            String hash1 = sessionService.hashToken(token1);
            String hash2 = sessionService.hashToken(token2);

            // Then
            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("should produce 64-character hex string (SHA-256)")
        void shouldProduceSha256HexString() {
            // Given
            String token = "test-token";

            // When
            String hash = sessionService.hashToken(token);

            // Then
            assertThat(hash).hasSize(64);
            assertThat(hash).matches("[0-9a-f]+"); // Lowercase hex
        }
    }
}
