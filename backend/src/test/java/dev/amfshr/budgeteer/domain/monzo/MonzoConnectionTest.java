package dev.amfshr.budgeteer.domain.monzo;

import dev.amfshr.budgeteer.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MonzoConnection} entity.
 */
@DisplayName("MonzoConnection")
class MonzoConnectionTest {

    private User testUser;
    private MonzoConnection connection;

    @BeforeEach
    void setUp() {
        testUser = new User("test@example.com");
        connection = new MonzoConnection(
                testUser,
                "user_abc123",
                "encrypted_access_token",
                "encrypted_refresh_token",
                Instant.now().plus(6, ChronoUnit.HOURS)
        );
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create connection with all fields")
        void shouldCreateConnectionWithAllFields() {
            assertThat(connection.getUser()).isEqualTo(testUser);
            assertThat(connection.getMonzoUserId()).isEqualTo("user_abc123");
            assertThat(connection.getAccessTokenEncrypted()).isEqualTo("encrypted_access_token");
            assertThat(connection.getRefreshTokenEncrypted()).isEqualTo("encrypted_refresh_token");
            assertThat(connection.getTokenExpiresAt()).isNotNull();
            assertThat(connection.getDisconnectedAt()).isNull();
        }

        @Test
        @DisplayName("should be active by default")
        void shouldBeActiveByDefault() {
            assertThat(connection.isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("isActive()")
    class IsActiveTests {

        @Test
        @DisplayName("should return true when disconnectedAt is null")
        void shouldReturnTrueWhenDisconnectedAtIsNull() {
            assertThat(connection.isActive()).isTrue();
        }

        @Test
        @DisplayName("should return false when disconnectedAt is set")
        void shouldReturnFalseWhenDisconnectedAtIsSet() {
            connection.disconnect();
            assertThat(connection.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("isTokenExpired()")
    class IsTokenExpiredTests {

        @Test
        @DisplayName("should return false when token is not expired")
        void shouldReturnFalseWhenTokenIsNotExpired() {
            MonzoConnection futureExpiry = new MonzoConnection(
                    testUser,
                    "user_test",
                    "access",
                    "refresh",
                    Instant.now().plus(1, ChronoUnit.HOURS)
            );
            assertThat(futureExpiry.isTokenExpired()).isFalse();
        }

        @Test
        @DisplayName("should return true when token is expired")
        void shouldReturnTrueWhenTokenIsExpired() {
            MonzoConnection pastExpiry = new MonzoConnection(
                    testUser,
                    "user_test",
                    "access",
                    "refresh",
                    Instant.now().minus(1, ChronoUnit.HOURS)
            );
            assertThat(pastExpiry.isTokenExpired()).isTrue();
        }
    }

    @Nested
    @DisplayName("isTokenExpiringSoon()")
    class IsTokenExpiringSoonTests {

        @Test
        @DisplayName("should return true when token expires within the window")
        void shouldReturnTrueWhenTokenExpiresWithinWindow() {
            MonzoConnection expiringSoon = new MonzoConnection(
                    testUser, "user_test", "access", "refresh",
                    Instant.now().plus(3, ChronoUnit.MINUTES)
            );
            assertThat(expiringSoon.isTokenExpiringSoon(Duration.ofMinutes(5))).isTrue();
        }

        @Test
        @DisplayName("should return false when token expires outside the window")
        void shouldReturnFalseWhenTokenExpiresOutsideWindow() {
            MonzoConnection notExpiringSoon = new MonzoConnection(
                    testUser, "user_test", "access", "refresh",
                    Instant.now().plus(1, ChronoUnit.HOURS)
            );
            assertThat(notExpiringSoon.isTokenExpiringSoon(Duration.ofMinutes(5))).isFalse();
        }

        @Test
        @DisplayName("should return true when token is already expired")
        void shouldReturnTrueWhenTokenAlreadyExpired() {
            MonzoConnection expired = new MonzoConnection(
                    testUser, "user_test", "access", "refresh",
                    Instant.now().minus(1, ChronoUnit.MINUTES)
            );
            assertThat(expired.isTokenExpiringSoon(Duration.ofMinutes(5))).isTrue();
        }

        @Test
        @DisplayName("should use the provided window duration for comparison")
        void shouldUseProvidedWindowDuration() {
            // Token expires in 30 minutes
            MonzoConnection connection = new MonzoConnection(
                    testUser, "user_test", "access", "refresh",
                    Instant.now().plus(30, ChronoUnit.MINUTES)
            );
            // 5-minute window: not expiring soon
            assertThat(connection.isTokenExpiringSoon(Duration.ofMinutes(5))).isFalse();
            // 60-minute window: expiring soon
            assertThat(connection.isTokenExpiringSoon(Duration.ofMinutes(60))).isTrue();
        }
    }

    @Nested
    @DisplayName("disconnect()")
    class DisconnectTests {

        @Test
        @DisplayName("should set disconnectedAt to current time")
        void shouldSetDisconnectedAtToCurrentTime() {
            Instant before = Instant.now();
            connection.disconnect();
            Instant after = Instant.now();

            assertThat(connection.getDisconnectedAt()).isNotNull();
            assertThat(connection.getDisconnectedAt())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(after);
        }

        @Test
        @DisplayName("should mark connection as inactive")
        void shouldMarkConnectionAsInactive() {
            connection.disconnect();
            assertThat(connection.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("updateTokens()")
    class UpdateTokensTests {

        @Test
        @DisplayName("should update all token fields")
        void shouldUpdateAllTokenFields() {
            Instant newExpiry = Instant.now().plus(12, ChronoUnit.HOURS);

            connection.updateTokens(
                    "new_encrypted_access",
                    "new_encrypted_refresh",
                    newExpiry
            );

            assertThat(connection.getAccessTokenEncrypted()).isEqualTo("new_encrypted_access");
            assertThat(connection.getRefreshTokenEncrypted()).isEqualTo("new_encrypted_refresh");
            assertThat(connection.getTokenExpiresAt()).isEqualTo(newExpiry);
        }

        @Test
        @DisplayName("should not affect other fields")
        void shouldNotAffectOtherFields() {
            String originalMonzoUserId = connection.getMonzoUserId();
            User originalUser = connection.getUser();

            connection.updateTokens(
                    "new_access",
                    "new_refresh",
                    Instant.now().plus(1, ChronoUnit.HOURS)
            );

            assertThat(connection.getMonzoUserId()).isEqualTo(originalMonzoUserId);
            assertThat(connection.getUser()).isEqualTo(originalUser);
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should not include encrypted tokens")
        void shouldNotIncludeEncryptedTokens() {
            String result = connection.toString();

            assertThat(result).doesNotContain("encrypted_access_token");
            assertThat(result).doesNotContain("encrypted_refresh_token");
            assertThat(result).doesNotContain("accessToken");
            assertThat(result).doesNotContain("refreshToken");
        }

        @Test
        @DisplayName("should include non-sensitive fields")
        void shouldIncludeNonSensitiveFields() {
            String result = connection.toString();

            assertThat(result).contains("MonzoConnection");
            assertThat(result).contains("monzoUserId='user_abc123'");
            assertThat(result).contains("isActive=true");
        }
    }
}
