package dev.amf.budgeteer.domain.session;

import dev.amf.budgeteer.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AppRefreshToken} entity business logic.
 */
@DisplayName("AppRefreshToken")
class AppRefreshTokenTest {

    private User user;

    @BeforeEach
    void setUp() {
        user = new User("test@example.com");
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("should set basic properties")
        void shouldSetBasicProperties() {
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
            AppRefreshToken token = new AppRefreshToken(user, "hash123", expiresAt);

            assertThat(token.getUser()).isEqualTo(user);
            assertThat(token.getTokenHash()).isEqualTo("hash123");
            assertThat(token.getExpiresAt()).isEqualTo(expiresAt);
            assertThat(token.getRevokedAt()).isNull();
            assertThat(token.getUserAgent()).isNull();
            assertThat(token.getIpAddress()).isNull();
        }

        @Test
        @DisplayName("should set device info when provided")
        void shouldSetDeviceInfo() {
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
            AppRefreshToken token = new AppRefreshToken(user, "hash123", expiresAt, "Mozilla/5.0", "192.168.1.1");

            assertThat(token.getUserAgent()).isEqualTo("Mozilla/5.0");
            assertThat(token.getIpAddress()).isEqualTo("192.168.1.1");
        }
    }

    @Nested
    @DisplayName("isExpired")
    class IsExpired {

        @Test
        @DisplayName("should return false when token has not expired")
        void shouldReturnFalseWhenNotExpired() {
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
            AppRefreshToken token = new AppRefreshToken(user, "hash", expiresAt);

            assertThat(token.isExpired()).isFalse();
        }

        @Test
        @DisplayName("should return true when token has expired")
        void shouldReturnTrueWhenExpired() {
            Instant expiresAt = Instant.now().minus(1, ChronoUnit.HOURS);
            AppRefreshToken token = new AppRefreshToken(user, "hash", expiresAt);

            assertThat(token.isExpired()).isTrue();
        }
    }

    @Nested
    @DisplayName("isRevoked")
    class IsRevoked {

        @Test
        @DisplayName("should return false when not revoked")
        void shouldReturnFalseWhenNotRevoked() {
            AppRefreshToken token = new AppRefreshToken(user, "hash", Instant.now().plus(7, ChronoUnit.DAYS));

            assertThat(token.isRevoked()).isFalse();
        }

        @Test
        @DisplayName("should return true after revoke()")
        void shouldReturnTrueAfterRevoke() {
            AppRefreshToken token = new AppRefreshToken(user, "hash", Instant.now().plus(7, ChronoUnit.DAYS));
            token.revoke();

            assertThat(token.isRevoked()).isTrue();
            assertThat(token.getRevokedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("should return true when not expired and not revoked")
        void shouldReturnTrueWhenValid() {
            AppRefreshToken token = new AppRefreshToken(user, "hash", Instant.now().plus(7, ChronoUnit.DAYS));

            assertThat(token.isValid()).isTrue();
        }

        @Test
        @DisplayName("should return false when expired")
        void shouldReturnFalseWhenExpired() {
            AppRefreshToken token = new AppRefreshToken(user, "hash", Instant.now().minus(1, ChronoUnit.HOURS));

            assertThat(token.isValid()).isFalse();
        }

        @Test
        @DisplayName("should return false when revoked")
        void shouldReturnFalseWhenRevoked() {
            AppRefreshToken token = new AppRefreshToken(user, "hash", Instant.now().plus(7, ChronoUnit.DAYS));
            token.revoke();

            assertThat(token.isValid()).isFalse();
        }

        @Test
        @DisplayName("should return false when both expired and revoked")
        void shouldReturnFalseWhenExpiredAndRevoked() {
            AppRefreshToken token = new AppRefreshToken(user, "hash", Instant.now().minus(1, ChronoUnit.HOURS));
            token.revoke();

            assertThat(token.isValid()).isFalse();
        }
    }

    @Nested
    @DisplayName("revoke")
    class Revoke {

        @Test
        @DisplayName("should set revokedAt to current time")
        void shouldSetRevokedAt() {
            AppRefreshToken token = new AppRefreshToken(user, "hash", Instant.now().plus(7, ChronoUnit.DAYS));
            Instant before = Instant.now();

            token.revoke();

            assertThat(token.getRevokedAt()).isNotNull();
            assertThat(token.getRevokedAt()).isAfterOrEqualTo(before);
            assertThat(token.getRevokedAt()).isBeforeOrEqualTo(Instant.now());
        }
    }
}
