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
 * Unit tests for {@link MagicLinkToken} entity business logic.
 */
@DisplayName("MagicLinkToken")
class MagicLinkTokenTest {

    private User user;

    @BeforeEach
    void setUp() {
        user = new User("test@example.com");
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("should set user, tokenHash, and expiresAt")
        void shouldSetProperties() {
            Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
            MagicLinkToken token = new MagicLinkToken(user, "hash123", expiresAt);

            assertThat(token.getUser()).isEqualTo(user);
            assertThat(token.getTokenHash()).isEqualTo("hash123");
            assertThat(token.getExpiresAt()).isEqualTo(expiresAt);
            assertThat(token.getUsedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("isExpired")
    class IsExpired {

        @Test
        @DisplayName("should return false when token has not expired")
        void shouldReturnFalseWhenNotExpired() {
            Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
            MagicLinkToken token = new MagicLinkToken(user, "hash", expiresAt);

            assertThat(token.isExpired()).isFalse();
        }

        @Test
        @DisplayName("should return true when token has expired")
        void shouldReturnTrueWhenExpired() {
            Instant expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES);
            MagicLinkToken token = new MagicLinkToken(user, "hash", expiresAt);

            assertThat(token.isExpired()).isTrue();
        }
    }

    @Nested
    @DisplayName("isUsed")
    class IsUsed {

        @Test
        @DisplayName("should return false when usedAt is null")
        void shouldReturnFalseWhenNotUsed() {
            MagicLinkToken token = new MagicLinkToken(user, "hash", Instant.now().plusSeconds(900));

            assertThat(token.isUsed()).isFalse();
        }

        @Test
        @DisplayName("should return true after markAsUsed")
        void shouldReturnTrueAfterMarkAsUsed() {
            MagicLinkToken token = new MagicLinkToken(user, "hash", Instant.now().plusSeconds(900));
            token.markAsUsed();

            assertThat(token.isUsed()).isTrue();
            assertThat(token.getUsedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("should return true when not expired and not used")
        void shouldReturnTrueWhenValid() {
            MagicLinkToken token = new MagicLinkToken(user, "hash", Instant.now().plusSeconds(900));

            assertThat(token.isValid()).isTrue();
        }

        @Test
        @DisplayName("should return false when expired")
        void shouldReturnFalseWhenExpired() {
            MagicLinkToken token = new MagicLinkToken(user, "hash", Instant.now().minusSeconds(60));

            assertThat(token.isValid()).isFalse();
        }

        @Test
        @DisplayName("should return false when used")
        void shouldReturnFalseWhenUsed() {
            MagicLinkToken token = new MagicLinkToken(user, "hash", Instant.now().plusSeconds(900));
            token.markAsUsed();

            assertThat(token.isValid()).isFalse();
        }

        @Test
        @DisplayName("should return false when both expired and used")
        void shouldReturnFalseWhenExpiredAndUsed() {
            MagicLinkToken token = new MagicLinkToken(user, "hash", Instant.now().minusSeconds(60));
            token.markAsUsed();

            assertThat(token.isValid()).isFalse();
        }
    }

    @Nested
    @DisplayName("markAsUsed")
    class MarkAsUsed {

        @Test
        @DisplayName("should set usedAt to current time")
        void shouldSetUsedAt() {
            MagicLinkToken token = new MagicLinkToken(user, "hash", Instant.now().plusSeconds(900));
            Instant before = Instant.now();

            token.markAsUsed();

            assertThat(token.getUsedAt()).isNotNull();
            assertThat(token.getUsedAt()).isAfterOrEqualTo(before);
            assertThat(token.getUsedAt()).isBeforeOrEqualTo(Instant.now());
        }
    }
}
