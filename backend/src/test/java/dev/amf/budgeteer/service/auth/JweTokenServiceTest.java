package dev.amf.budgeteer.service.auth;

import dev.amf.budgeteer.config.JweProperties;
import dev.amf.budgeteer.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link JweTokenService}.
 *
 * <p>These are pure unit tests with no Spring context required.
 * The service is instantiated directly with test configuration.</p>
 */
@DisplayName("JweTokenService")
class JweTokenServiceTest {

    private JweTokenService jweTokenService;
    private JweProperties jweProperties;

    // Test key: exactly 32 bytes encoded as base64
    private static final String TEST_SECRET_KEY = Base64.getEncoder()
            .encodeToString("test-secret-key-that-is-32-bytes".getBytes());

    @BeforeEach
    void setUp() {
        jweProperties = new JweProperties();
        jweProperties.setSecretKey(TEST_SECRET_KEY);
        jweProperties.setAccessTokenExpiry(Duration.ofMinutes(15));
        jweProperties.setRefreshTokenExpiry(Duration.ofDays(7));
        jweProperties.setMagicLinkExpiry(Duration.ofMinutes(15));

        jweTokenService = new JweTokenService(jweProperties);
        jweTokenService.init(); // Initialize the secret key
    }

    private User createTestUser() {
        User user = new User("test@example.com");
        user.setId(UUID.randomUUID());
        user.setEmailVerified(true);
        return user;
    }

    @Nested
    @DisplayName("createAccessToken")
    class CreateAccessToken {

        @Test
        @DisplayName("should create a valid JWE token for a user")
        void shouldCreateValidToken() {
            // Given
            User user = createTestUser();

            // When
            String token = jweTokenService.createAccessToken(user);

            // Then
            assertThat(token).isNotNull();
            assertThat(token).isNotEmpty();
            // JWE tokens have 5 parts separated by dots
            assertThat(token.split("\\.")).hasSize(5);
        }

        @Test
        @DisplayName("should create different tokens for different users")
        void shouldCreateDifferentTokensForDifferentUsers() {
            // Given
            User user1 = createTestUser();
            User user2 = new User("another@example.com");
            user2.setId(UUID.randomUUID());

            // When
            String token1 = jweTokenService.createAccessToken(user1);
            String token2 = jweTokenService.createAccessToken(user2);

            // Then
            assertThat(token1).isNotEqualTo(token2);
        }

        @Test
        @DisplayName("should create different tokens for same user (unique jti)")
        void shouldCreateDifferentTokensForSameUser() {
            // Given
            User user = createTestUser();

            // When
            String token1 = jweTokenService.createAccessToken(user);
            String token2 = jweTokenService.createAccessToken(user);

            // Then
            assertThat(token1).isNotEqualTo(token2);
        }
    }

    @Nested
    @DisplayName("validateAccessToken")
    class ValidateAccessToken {

        @Test
        @DisplayName("should validate and return claims for valid token")
        void shouldValidateValidToken() {
            // Given
            User user = createTestUser();
            String token = jweTokenService.createAccessToken(user);

            // When
            Optional<JweTokenService.TokenClaims> claims = jweTokenService.validateAccessToken(token);

            // Then
            assertThat(claims).isPresent();
            assertThat(claims.get().userId()).isEqualTo(user.getId());
            assertThat(claims.get().email()).isEqualTo(user.getEmail());
            assertThat(claims.get().tokenId()).isNotNull();
            assertThat(claims.get().issuedAt()).isNotNull();
            assertThat(claims.get().expiresAt()).isNotNull();
        }

        @Test
        @DisplayName("should return empty for expired token")
        void shouldReturnEmptyForExpiredToken() {
            // Given - Create service with very short expiry
            JweProperties shortExpiryProps = new JweProperties();
            shortExpiryProps.setSecretKey(TEST_SECRET_KEY);
            shortExpiryProps.setAccessTokenExpiry(Duration.ofMillis(1)); // 1ms expiry

            JweTokenService shortExpiryService = new JweTokenService(shortExpiryProps);
            shortExpiryService.init();

            User user = createTestUser();
            String token = shortExpiryService.createAccessToken(user);

            // Wait for token to expire
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // When
            Optional<JweTokenService.TokenClaims> claims = shortExpiryService.validateAccessToken(token);

            // Then
            assertThat(claims).isEmpty();
        }

        @Test
        @DisplayName("should return empty for malformed token")
        void shouldReturnEmptyForMalformedToken() {
            // When
            Optional<JweTokenService.TokenClaims> claims =
                    jweTokenService.validateAccessToken("not.a.valid.token");

            // Then
            assertThat(claims).isEmpty();
        }

        @Test
        @DisplayName("should return empty for null token")
        void shouldReturnEmptyForNullToken() {
            // When
            Optional<JweTokenService.TokenClaims> claims = jweTokenService.validateAccessToken(null);

            // Then - Should handle null gracefully (return empty, not throw)
            assertThat(claims).isEmpty();
        }

        @Test
        @DisplayName("should return empty for empty token")
        void shouldReturnEmptyForEmptyToken() {
            // When
            Optional<JweTokenService.TokenClaims> claims = jweTokenService.validateAccessToken("");

            // Then
            assertThat(claims).isEmpty();
        }

        @Test
        @DisplayName("should return empty for token encrypted with different key")
        void shouldReturnEmptyForTokenWithDifferentKey() {
            // Given - Create another service with different key
            String differentKey = Base64.getEncoder()
                    .encodeToString("different-key-that-is-32-bytes!!".getBytes());

            JweProperties differentKeyProps = new JweProperties();
            differentKeyProps.setSecretKey(differentKey);
            differentKeyProps.setAccessTokenExpiry(Duration.ofMinutes(15));

            JweTokenService differentKeyService = new JweTokenService(differentKeyProps);
            differentKeyService.init();

            User user = createTestUser();
            String token = differentKeyService.createAccessToken(user);

            // When - Try to validate with original service (different key)
            Optional<JweTokenService.TokenClaims> claims = jweTokenService.validateAccessToken(token);

            // Then
            assertThat(claims).isEmpty();
        }
    }

    @Nested
    @DisplayName("init")
    class Init {

        @Test
        @DisplayName("should throw exception for invalid key length")
        void shouldThrowForInvalidKeyLength() {
            // Given - Key that's not 32 bytes
            JweProperties invalidProps = new JweProperties();
            invalidProps.setSecretKey(Base64.getEncoder().encodeToString("short-key".getBytes()));

            JweTokenService invalidService = new JweTokenService(invalidProps);

            // When/Then
            assertThatThrownBy(invalidService::init)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32 bytes");
        }

        @Test
        @DisplayName("should throw exception when key is missing")
        void shouldThrowWhenKeyMissing() {
            // Given
            JweProperties noKeyProps = new JweProperties();
            noKeyProps.setSecretKey(null);
            noKeyProps.setAccessTokenExpiry(Duration.ofMinutes(15));

            JweTokenService noKeyService = new JweTokenService(noKeyProps);

            // When/Then - Should throw because key is required
            assertThatThrownBy(noKeyService::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWE_SECRET_KEY is not configured");
        }

        @Test
        @DisplayName("should throw exception when key is blank")
        void shouldThrowWhenKeyBlank() {
            // Given
            JweProperties blankKeyProps = new JweProperties();
            blankKeyProps.setSecretKey("   ");
            blankKeyProps.setAccessTokenExpiry(Duration.ofMinutes(15));

            JweTokenService blankKeyService = new JweTokenService(blankKeyProps);

            // When/Then - Should throw because key is required
            assertThatThrownBy(blankKeyService::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWE_SECRET_KEY is not configured");
        }
    }

    @Nested
    @DisplayName("TokenClaims record")
    class TokenClaimsRecord {

        @Test
        @DisplayName("should contain all expected fields from validated token")
        void shouldContainAllExpectedFields() {
            // Given
            User user = createTestUser();
            String token = jweTokenService.createAccessToken(user);

            // When
            Optional<JweTokenService.TokenClaims> claimsOpt = jweTokenService.validateAccessToken(token);

            // Then
            assertThat(claimsOpt).isPresent();
            JweTokenService.TokenClaims claims = claimsOpt.get();

            assertThat(claims.userId()).isEqualTo(user.getId());
            assertThat(claims.email()).isEqualTo(user.getEmail());
            assertThat(claims.tokenId()).isNotBlank();
            assertThat(claims.issuedAt()).isNotNull();
            assertThat(claims.expiresAt()).isNotNull();
            assertThat(claims.expiresAt()).isAfter(claims.issuedAt());
        }

        @Test
        @DisplayName("expiry should match configured duration")
        void expiryShouldMatchConfiguredDuration() {
            // Given
            User user = createTestUser();
            String token = jweTokenService.createAccessToken(user);

            // When
            Optional<JweTokenService.TokenClaims> claimsOpt = jweTokenService.validateAccessToken(token);

            // Then
            assertThat(claimsOpt).isPresent();
            JweTokenService.TokenClaims claims = claimsOpt.get();

            Duration actualDuration = Duration.between(claims.issuedAt(), claims.expiresAt());
            Duration configuredDuration = jweProperties.getAccessTokenExpiry();

            // Allow 1 second tolerance for test execution time
            assertThat(actualDuration).isBetween(
                    configuredDuration.minusSeconds(1),
                    configuredDuration.plusSeconds(1)
            );
        }
    }
}
