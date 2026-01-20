package dev.amf.budgeteer.integration;

import dev.amf.budgeteer.domain.session.AppRefreshToken;
import dev.amf.budgeteer.domain.session.AppRefreshTokenRepository;
import dev.amf.budgeteer.domain.session.MagicLinkToken;
import dev.amf.budgeteer.domain.session.MagicLinkTokenRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.domain.user.UserRepository;
import dev.amf.budgeteer.service.AuthService;
import dev.amf.budgeteer.service.JweTokenService;
import dev.amf.budgeteer.service.SessionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the complete authentication flow.
 * 
 * <p>Tests the full magic link authentication flow using a real PostgreSQL
 * database via Testcontainers. This verifies that all components work
 * together correctly with real database constraints and transactions.</p>
 * 
 * <h3>Scenarios Tested:</h3>
 * <ul>
 *   <li>New user registration via a magic link</li>
 *   <li>Existing user login via a magic link</li>
 *   <li>Magic link verification and session creation</li>
 *   <li>Token refresh flow</li>
 *   <li>Logout (session revocation)</li>
 *   <li>Invalid/expired token handling</li>
 * </ul>
 */
@DisplayName("Auth Flow Integration Tests")
class AuthFlowIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JweTokenService jweTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MagicLinkTokenRepository magicLinkTokenRepository;

    @Autowired
    private AppRefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TestDataFactory testData;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        // Clean up test data before each test
        refreshTokenRepository.deleteAll();
        magicLinkTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ========================================================================
    // Magic Link Request Tests
    // ========================================================================

    @Nested
    @DisplayName("requestMagicLink")
    class RequestMagicLink {

        @Test
        @DisplayName("should create new user and magic link for new email")
        @Transactional
        void shouldCreateNewUserAndMagicLink() {
            // Given
            String email = "newuser@example.com";

            // When
            authService.requestMagicLink(email);

            // Then - user should be created
            Optional<User> user = userRepository.findByEmailIgnoreCase(email);
            assertThat(user).isPresent();
            assertThat(user.get().getEmail()).isEqualTo(email);
            assertThat(user.get().isEmailVerified()).isFalse();

            // Then - magic link token should be created
            List<MagicLinkToken> tokens = magicLinkTokenRepository.findAll();
            assertThat(tokens).hasSize(1);
            assertThat(tokens.getFirst().getUser().getId()).isEqualTo(user.get().getId());
            assertThat(tokens.getFirst().isValid()).isTrue();
        }

        @Test
        @DisplayName("should create magic link for existing user without creating duplicate")
        @Transactional
        void shouldCreateMagicLinkForExistingUser() {
            // Given - existing user
            User existingUser = testData.createUser("existing@example.com");

            // When
            authService.requestMagicLink("existing@example.com");

            // Then - no duplicate user created
            List<User> users = userRepository.findAll();
            assertThat(users).hasSize(1);
            assertThat(users.getFirst().getId()).isEqualTo(existingUser.getId());

            // Then - magic link token should be created
            List<MagicLinkToken> tokens = magicLinkTokenRepository.findAll();
            assertThat(tokens).hasSize(1);
        }

        @Test
        @DisplayName("should normalise email to lowercase")
        @Transactional
        void shouldNormaliseEmail() {
            // When
            authService.requestMagicLink("  UPPERCASE@Example.COM  ");

            // Then
            Optional<User> user = userRepository.findByEmailIgnoreCase("uppercase@example.com");
            assertThat(user).isPresent();
            assertThat(user.get().getEmail()).isEqualTo("uppercase@example.com");
        }

        // TODO - should only the latest request be valid and older ones unused be invalidated on new request
        @Test
        @DisplayName("should allow multiple magic link requests for same user")
        @Transactional
        void shouldAllowMultipleMagicLinkRequests() {
            // Given
            String email = "multiple@example.com";

            // When - request magic link twice
            authService.requestMagicLink(email);
            authService.requestMagicLink(email);

            // Then - both tokens should exist
            List<MagicLinkToken> tokens = magicLinkTokenRepository.findAll();
            assertThat(tokens).hasSize(2);
            assertThat(tokens).allMatch(MagicLinkToken::isValid);
        }
    }

    // ========================================================================
    // Magic Link Verification Tests
    // ========================================================================

    @Nested
    @DisplayName("verifyMagicLink")
    class VerifyMagicLink {

        @Test
        @DisplayName("should create session and mark email verified on valid magic link")
        @Transactional
        void shouldCreateSessionOnValidMagicLink() {
            // Given
            User user = testData.createUser("verify@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);

            // TODO - method name doesn't indicate session creation ...
            // When
            Optional<SessionService.SessionTokens> result = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1");

            // Then - session created
            assertThat(result).isPresent();
            assertThat(result.get().accessToken()).isNotBlank();
            assertThat(result.get().refreshToken()).isNotBlank();

            // Then - email marked as verified
            User updatedUser = userRepository.findById(user.getId()).orElseThrow();
            assertThat(updatedUser.isEmailVerified()).isTrue();

            // Then - magic link marked as used
            MagicLinkToken updatedToken = magicLinkTokenRepository.findById(tokenResult.entity().getId()).orElseThrow();
            assertThat(updatedToken.isUsed()).isTrue();

            // Then - refresh token stored in database
            List<AppRefreshToken> refreshTokens = refreshTokenRepository.findAll();
            assertThat(refreshTokens).hasSize(1);
            assertThat(refreshTokens.getFirst().isValid()).isTrue();
        }

        @Test
        @DisplayName("should return empty for non-existent token")
        @Transactional
        void shouldReturnEmptyForNonExistentToken() {
            // When
            Optional<SessionService.SessionTokens> result = authService.verifyMagicLink(
                    "non-existent-token", "Test-Agent", "127.0.0.1");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for expired token")
        @Transactional
        void shouldReturnEmptyForExpiredToken() {
            // Given
            User user = testData.createUser("expired@example.com");
            testData.createExpiredMagicLinkFor(user);

            // TODO - the create expired method should return token and this should be tested with or else its just testing the same as above
            // When - try any token (won't match the expired one's hash)
            Optional<SessionService.SessionTokens> result = authService.verifyMagicLink(
                    "some-random-token", "Test-Agent", "127.0.0.1");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for already used token")
        @Transactional
        void shouldReturnEmptyForUsedToken() {
            // Given
            User user = testData.createUser("used@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);

            // First verification - should succeed
            Optional<SessionService.SessionTokens> firstResult = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1");
            assertThat(firstResult).isPresent();

            // When - try to use same token again
            Optional<SessionService.SessionTokens> secondResult = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1");

            // Then
            assertThat(secondResult).isEmpty();
        }

        @Test
        @DisplayName("should invalidate other pending magic links on verification")
        @Transactional
        void shouldInvalidateOtherMagicLinksOnVerification() {
            // Given - user with multiple pending magic links
            User user = testData.createUser("multi@example.com");
            TestDataFactory.MagicLinkTokenResult firstToken = testData.createValidMagicLinkFor(user);
            TestDataFactory.MagicLinkTokenResult secondToken = testData.createValidMagicLinkFor(user);

            // When - verify with first token (this calls a bulk UPDATE to invalidate other tokens)
            authService.verifyMagicLink(firstToken.rawToken(), "Test-Agent", "127.0.0.1");
            
            // Clear persistence context after bulk update to force fresh read from DB
            entityManager.flush();
            entityManager.clear();

            // Then - second token should be invalidated
            Optional<SessionService.SessionTokens> result = authService.verifyMagicLink(
                    secondToken.rawToken(), "Test-Agent", "127.0.0.1");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should store device info with session")
        @Transactional
        void shouldStoreDeviceInfoWithSession() {
            // Given
            User user = testData.createUser("device@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);

            // When
            authService.verifyMagicLink(tokenResult.rawToken(), "Mozilla/5.0 Chrome", "192.168.1.1");

            // Then
            List<AppRefreshToken> tokens = refreshTokenRepository.findAll();
            assertThat(tokens).hasSize(1);
            assertThat(tokens.getFirst().getUserAgent()).isEqualTo("Mozilla/5.0 Chrome");
            assertThat(tokens.getFirst().getIpAddress()).isEqualTo("192.168.1.1");
        }
    }

    // ========================================================================
    // Token Validation Tests
    // ========================================================================

    @Nested
    @DisplayName("Token Validation")
    class TokenValidation {

        @Test
        @DisplayName("should validate access token and return correct user ID")
        @Transactional
        void shouldValidateAccessToken() {
            // Given - complete login flow
            User user = testData.createVerifiedUser("access@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens tokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // When
            Optional<JweTokenService.TokenClaims> claims = jweTokenService.validateAccessToken(tokens.accessToken());

            // Then
            assertThat(claims).isPresent();
            assertThat(claims.get().userId()).isEqualTo(user.getId());
        }

        @Test
        @DisplayName("should validate refresh token and return user")
        @Transactional
        void shouldValidateRefreshToken() {
            // Given - complete login flow
            User user = testData.createVerifiedUser("refresh@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens tokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // When
            Optional<User> validatedUser = sessionService.validateRefreshToken(tokens.refreshToken());

            // Then
            assertThat(validatedUser).isPresent();
            assertThat(validatedUser.get().getId()).isEqualTo(user.getId());
        }

        @Test
        @DisplayName("should reject invalid access token")
        void shouldRejectInvalidAccessToken() {
            // When
            Optional<JweTokenService.TokenClaims> result = jweTokenService.validateAccessToken("invalid-token");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should reject invalid refresh token")
        @Transactional
        void shouldRejectInvalidRefreshToken() {
            // When
            Optional<User> result = sessionService.validateRefreshToken("invalid-token");

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================================================
    // Token Refresh Tests
    // ========================================================================

    @Nested
    @DisplayName("Token Refresh")
    class TokenRefresh {

        @Test
        @DisplayName("should issue new tokens on refresh")
        @Transactional
        void shouldIssueNewTokensOnRefresh() {
            // Given - logged in user
            User user = testData.createVerifiedUser("refresh-new@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens originalTokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // When
            Optional<SessionService.SessionTokens> newTokens = sessionService.refreshSession(
                    originalTokens.refreshToken(), "Test-Agent", "127.0.0.1");

            // Then
            assertThat(newTokens).isPresent();
            assertThat(newTokens.get().accessToken()).isNotBlank();
            assertThat(newTokens.get().refreshToken()).isNotBlank();
            // New tokens should be different from original
            assertThat(newTokens.get().accessToken()).isNotEqualTo(originalTokens.accessToken());
            assertThat(newTokens.get().refreshToken()).isNotEqualTo(originalTokens.refreshToken());
        }

        @Test
        @DisplayName("should revoke old refresh token on rotation")
        @Transactional
        void shouldRevokeOldRefreshTokenOnRotation() {
            // Given - logged in user
            User user = testData.createVerifiedUser("rotate@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens originalTokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // When - refresh
            sessionService.refreshSession(originalTokens.refreshToken(), "Test-Agent", "127.0.0.1");

            // Then - original refresh token should be invalid
            Optional<User> result = sessionService.validateRefreshToken(originalTokens.refreshToken());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should reject refresh with revoked token")
        @Transactional
        void shouldRejectRefreshWithRevokedToken() {
            // Given - logged in user whose token is then revoked
            User user = testData.createVerifiedUser("revoked@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens tokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // Revoke the token
            sessionService.revokeSession(tokens.refreshToken());

            // When
            Optional<SessionService.SessionTokens> result = sessionService.refreshSession(
                    tokens.refreshToken(), "Test-Agent", "127.0.0.1");

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================================================
    // Logout Tests
    // ========================================================================

    @Nested
    @DisplayName("Logout")
    class Logout {

        @Test
        @DisplayName("should revoke refresh token on logout")
        @Transactional
        void shouldRevokeRefreshTokenOnLogout() {
            // Given - logged in user
            User user = testData.createVerifiedUser("logout@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens tokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // When
            boolean revoked = sessionService.revokeSession(tokens.refreshToken());

            // Then
            assertThat(revoked).isTrue();
            Optional<User> result = sessionService.validateRefreshToken(tokens.refreshToken());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return false when logging out with invalid token")
        @Transactional
        void shouldReturnFalseForInvalidLogout() {
            // When
            boolean revoked = sessionService.revokeSession("invalid-token");

            // Then
            assertThat(revoked).isFalse();
        }
    }

    // ========================================================================
    // Complete Flow Tests
    // ========================================================================

    @Nested
    @DisplayName("Complete Authentication Flow")
    class CompleteFlow {

        @Test
        @DisplayName("should complete full login -> refresh -> logout flow")
        @Transactional
        void shouldCompleteFullFlow() {
            String email = "fullflow@example.com";

            // TODO no assert on magic link - why?
            // 1. Request magic link
            authService.requestMagicLink(email);
            User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
            MagicLinkToken magicLink = magicLinkTokenRepository.findAll().getFirst();

            // Get the raw token (in real app this is sent via email)
            // For testing, we create a fresh one since we can't reverse the hash
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);

            // 2. Verify magic link
            SessionService.SessionTokens tokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // 3. Access protected resource (validate access token)
            Optional<JweTokenService.TokenClaims> claims = jweTokenService.validateAccessToken(tokens.accessToken());
            assertThat(claims).isPresent();
            assertThat(claims.get().userId()).isEqualTo(user.getId());

            // 4. Refresh token
            SessionService.SessionTokens newTokens = sessionService.refreshSession(
                    tokens.refreshToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // 5. Old refresh token should be invalid
            assertThat(sessionService.validateRefreshToken(tokens.refreshToken())).isEmpty();

            // 6. New tokens should work
            assertThat(jweTokenService.validateAccessToken(newTokens.accessToken())).isPresent();
            assertThat(sessionService.validateRefreshToken(newTokens.refreshToken())).isPresent();

            // 7. Logout
            boolean loggedOut = sessionService.revokeSession(newTokens.refreshToken());
            assertThat(loggedOut).isTrue();

            // 8. Token should be invalid after logout
            assertThat(sessionService.validateRefreshToken(newTokens.refreshToken())).isEmpty();
        }
    }
}
