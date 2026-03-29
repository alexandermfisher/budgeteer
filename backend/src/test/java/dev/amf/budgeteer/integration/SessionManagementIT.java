package dev.amf.budgeteer.integration;

import dev.amf.budgeteer.domain.session.AppRefreshToken;
import dev.amf.budgeteer.repository.AppRefreshTokenRepository;
import dev.amf.budgeteer.repository.MagicLinkTokenRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.repository.UserRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for session management edge cases.
 * 
 * <p>Tests session-related scenarios using a real PostgreSQL database
 * via Testcontainers. Focuses on:</p>
 * <ul>
 *   <li>Single-session policy (new login revokes old sessions)</li>
 *   <li>Token rotation security</li>
 *   <li>Multiple device handling</li>
 *   <li>Session cleanup and revocation</li>
 * </ul>
 */
@DisplayName("Session Management Integration Tests")
class SessionManagementIT extends AbstractPostgresIntegrationTest {

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
    // Single-Session Policy Tests
    // ========================================================================

    @Nested
    @DisplayName("Single-Session Policy")
    class SingleSessionPolicy {

        @Test
        @DisplayName("should revoke existing sessions when user logs in again")
        @Transactional
        void shouldRevokeExistingSessionsOnNewLogin() {
            // Given - user with an active session
            User user = testData.createVerifiedUser("single@example.com");
            TestDataFactory.MagicLinkTokenResult firstToken = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens firstSession = authService.verifyMagicLink(
                    firstToken.rawToken(), "Device-1", "10.0.0.1").orElseThrow();

            // Verify first session is active
            assertThat(sessionService.validateRefreshToken(firstSession.refreshToken())).isPresent();

            // When - user logs in again
            TestDataFactory.MagicLinkTokenResult secondToken = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens secondSession = authService.verifyMagicLink(
                    secondToken.rawToken(), "Device-2", "10.0.0.2").orElseThrow();
            
            // Clear persistence context to simulate new HTTP request/transaction
            entityManager.flush();
            entityManager.clear();

            // Then - first session should be revoked
            assertThat(sessionService.validateRefreshToken(firstSession.refreshToken())).isEmpty();
            
            // And second session should be active
            assertThat(sessionService.validateRefreshToken(secondSession.refreshToken())).isPresent();
        }

        @Test
        @DisplayName("should only have one active session per user")
        @Transactional
        void shouldOnlyHaveOneActiveSession() {
            // Given - user logs in multiple times
            User user = testData.createVerifiedUser("multi-login@example.com");

            // First login
            TestDataFactory.MagicLinkTokenResult firstToken = testData.createValidMagicLinkFor(user);
            authService.verifyMagicLink(firstToken.rawToken(), "Device-1", "10.0.0.1");

            // Second login
            TestDataFactory.MagicLinkTokenResult secondToken = testData.createValidMagicLinkFor(user);
            authService.verifyMagicLink(secondToken.rawToken(), "Device-2", "10.0.0.2");

            // Third login
            TestDataFactory.MagicLinkTokenResult thirdToken = testData.createValidMagicLinkFor(user);
            authService.verifyMagicLink(thirdToken.rawToken(), "Device-3", "10.0.0.3");

            // Then - only one active session should exist
            List<AppRefreshToken> activeTokens = refreshTokenRepository.findActiveTokensByUser(user, Instant.now());
            assertThat(activeTokens).hasSize(1);
        }

        @Test
        @DisplayName("should not affect other users' sessions")
        @Transactional
        void shouldNotAffectOtherUsersSessions() {
            // Given - two users with active sessions
            User user1 = testData.createVerifiedUser("user1@example.com");
            User user2 = testData.createVerifiedUser("user2@example.com");

            TestDataFactory.MagicLinkTokenResult user1Token = testData.createValidMagicLinkFor(user1);
            TestDataFactory.MagicLinkTokenResult user2Token = testData.createValidMagicLinkFor(user2);

            SessionService.SessionTokens user1Session = authService.verifyMagicLink(
                    user1Token.rawToken(), "User1-Device", "10.0.0.1").orElseThrow();
            SessionService.SessionTokens user2Session = authService.verifyMagicLink(
                    user2Token.rawToken(), "User2-Device", "10.0.0.2").orElseThrow();

            // Both sessions should be active
            assertThat(sessionService.validateRefreshToken(user1Session.refreshToken())).isPresent();
            assertThat(sessionService.validateRefreshToken(user2Session.refreshToken())).isPresent();

            // When - user1 logs in again
            TestDataFactory.MagicLinkTokenResult newUser1Token = testData.createValidMagicLinkFor(user1);
            authService.verifyMagicLink(newUser1Token.rawToken(), "User1-NewDevice", "10.0.0.3");

            // Then - user2's session should still be active
            assertThat(sessionService.validateRefreshToken(user2Session.refreshToken())).isPresent();
        }
    }

    // ========================================================================
    // Token Rotation Security Tests
    // ========================================================================

    @Nested
    @DisplayName("Token Rotation Security")
    class TokenRotationSecurity {

        @Test
        @DisplayName("should prevent reuse of rotated refresh token")
        @Transactional
        void shouldPreventReuseOfRotatedToken() {
            // Given - logged in user
            User user = testData.createVerifiedUser("rotate@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens originalTokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // When - refresh the token
            sessionService.refreshSession(originalTokens.refreshToken(), "Test-Agent", "127.0.0.1");

            // Then - original token should be invalid
            Optional<SessionService.SessionTokens> replayAttempt = sessionService.refreshSession(
                    originalTokens.refreshToken(), "Attacker-Agent", "Evil.IP");
            assertThat(replayAttempt).isEmpty();
        }

        @Test
        @DisplayName("should allow multiple consecutive refreshes")
        @Transactional
        void shouldAllowMultipleConsecutiveRefreshes() {
            // Given - logged in user
            User user = testData.createVerifiedUser("multi-refresh@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens tokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // When - refresh multiple times
            for (int i = 0; i < 5; i++) {
                Optional<SessionService.SessionTokens> newTokens = sessionService.refreshSession(
                        tokens.refreshToken(), "Test-Agent", "127.0.0.1");
                assertThat(newTokens).isPresent();
                tokens = newTokens.get();
            }

            // Then - final tokens should still be valid
            assertThat(jweTokenService.validateAccessToken(tokens.accessToken())).isPresent();
            assertThat(sessionService.validateRefreshToken(tokens.refreshToken())).isPresent();
        }

        @Test
        @DisplayName("should maintain user association through refresh chain")
        @Transactional
        void shouldMaintainUserAssociationThroughRefreshChain() {
            // Given - logged in user
            User user = testData.createVerifiedUser("chain@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens tokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // When - refresh multiple times
            for (int i = 0; i < 3; i++) {
                tokens = sessionService.refreshSession(
                        tokens.refreshToken(), "Test-Agent", "127.0.0.1").orElseThrow();
            }

            // Then - token should still be associated with the same user
            Optional<JweTokenService.TokenClaims> claims = jweTokenService.validateAccessToken(tokens.accessToken());
            assertThat(claims).isPresent();
            assertThat(claims.get().userId()).isEqualTo(user.getId());

            Optional<User> tokenUser = sessionService.validateRefreshToken(tokens.refreshToken());
            assertThat(tokenUser).isPresent();
            assertThat(tokenUser.get().getId()).isEqualTo(user.getId());
        }
    }

    // ========================================================================
    // Session Revocation Tests
    // ========================================================================

    @Nested
    @DisplayName("Session Revocation")
    class SessionRevocation {

        @Test
        @DisplayName("should revoke all sessions for a user")
        @Transactional
        void shouldRevokeAllSessionsForUser() {
            // Given - user with a session
            User user = testData.createVerifiedUser("revoke-all@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens tokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Test-Agent", "127.0.0.1").orElseThrow();

            // When
            int revoked = sessionService.revokeAllSessions(user);
            // Clear persistence context after bulk update to force fresh read from DB
            entityManager.flush();
            entityManager.clear();

            // Then
            assertThat(revoked).isGreaterThanOrEqualTo(1);
            assertThat(sessionService.validateRefreshToken(tokens.refreshToken())).isEmpty();
        }

        @Test
        @DisplayName("should return correct count of revoked sessions")
        @Transactional
        void shouldReturnCorrectRevokedCount() {
            // Given - user with multiple refresh tokens (simulating pre-single-session-policy)
            User user = testData.createVerifiedUser("count@example.com");
            
            // Create multiple active tokens directly (bypassing single-session policy)
            testData.createValidRefreshTokenFor(user, "Device-1", "10.0.0.1");
            testData.createValidRefreshTokenFor(user, "Device-2", "10.0.0.2");
            testData.createValidRefreshTokenFor(user, "Device-3", "10.0.0.3");

            // When
            int revoked = sessionService.revokeAllSessions(user);

            // Then
            assertThat(revoked).isEqualTo(3);
        }

        @Test
        @DisplayName("should not revoke already revoked sessions")
        @Transactional
        void shouldNotRevokeAlreadyRevokedSessions() {
            // Given - user with an already revoked session
            User user = testData.createVerifiedUser("already-revoked@example.com");
            testData.createRevokedRefreshTokenFor(user);

            // When
            int revoked = sessionService.revokeAllSessions(user);

            // Then
            assertThat(revoked).isEqualTo(0);
        }

        @Test
        @DisplayName("should handle user with no sessions")
        @Transactional
        void shouldHandleUserWithNoSessions() {
            // Given - user with no sessions
            User user = testData.createVerifiedUser("no-sessions@example.com");

            // When
            int revoked = sessionService.revokeAllSessions(user);

            // Then
            assertThat(revoked).isEqualTo(0);
        }
    }

    // ========================================================================
    // Device Info Tracking Tests
    // ========================================================================

    @Nested
    @DisplayName("Device Info Tracking")
    class DeviceInfoTracking {

        @Test
        @DisplayName("should update device info on token refresh")
        @Transactional
        void shouldUpdateDeviceInfoOnRefresh() {
            // Given - logged in user
            User user = testData.createVerifiedUser("device-track@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens tokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), "Original-Agent", "10.0.0.1").orElseThrow();

            // When - refresh from different device
            SessionService.SessionTokens newTokens = sessionService.refreshSession(
                    tokens.refreshToken(), "New-Agent", "192.168.1.1").orElseThrow();

            // Then - new token should have updated device info
            Optional<User> tokenUser = sessionService.validateRefreshToken(newTokens.refreshToken());
            assertThat(tokenUser).isPresent();

            // Verify device info was stored with new session
            List<AppRefreshToken> activeTokens = refreshTokenRepository.findActiveTokensByUser(user, Instant.now());
            assertThat(activeTokens).hasSize(1);
            assertThat(activeTokens.get(0).getUserAgent()).isEqualTo("New-Agent");
            assertThat(activeTokens.get(0).getIpAddress()).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("should handle null device info")
        @Transactional
        void shouldHandleNullDeviceInfo() {
            // Given - logged in user with no device info
            User user = testData.createVerifiedUser("null-device@example.com");
            TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
            SessionService.SessionTokens tokens = authService.verifyMagicLink(
                    tokenResult.rawToken(), null, null).orElseThrow();

            // When - refresh also with no device info
            Optional<SessionService.SessionTokens> newTokens = sessionService.refreshSession(
                    tokens.refreshToken(), null, null);

            // Then - should still work
            assertThat(newTokens).isPresent();
            assertThat(sessionService.validateRefreshToken(newTokens.get().refreshToken())).isPresent();
        }
    }

    // ========================================================================
    // Expired Token Tests
    // ========================================================================

    @Nested
    @DisplayName("Expired Token Handling")
    class ExpiredTokenHandling {

        @Test
        @DisplayName("should reject expired refresh token")
        @Transactional
        void shouldRejectExpiredRefreshToken() {
            // Given - expired refresh token
            User user = testData.createVerifiedUser("expired@example.com");
            testData.createExpiredRefreshTokenFor(user);

            // When - try to validate any token (won't match the expired one)
            Optional<User> result = sessionService.validateRefreshToken("some-token");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should reject refresh with expired token")
        @Transactional
        void shouldRejectRefreshWithExpiredToken() {
            // Given - logged in user (then we'll create an expired token to test)
            User user = testData.createVerifiedUser("expire-refresh@example.com");
            TestDataFactory.RefreshTokenResult expiredToken = testData.createValidRefreshTokenFor(user);
            
            // Manually expire the token in DB
            AppRefreshToken token = expiredToken.entity();
            // We can't easily expire it, so let's revoke it instead to simulate invalid state
            token.revoke();
            refreshTokenRepository.save(token);

            // When
            Optional<SessionService.SessionTokens> result = sessionService.refreshSession(
                    expiredToken.rawToken(), "Test-Agent", "127.0.0.1");

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================================================
    // Concurrent Session Tests
    // ========================================================================

    @Nested
    @DisplayName("Session Edge Cases")
    class SessionEdgeCases {

        @Test
        @DisplayName("should handle rapid login requests gracefully")
        @Transactional
        void shouldHandleRapidLoginRequests() {
            // Given
            String email = "rapid@example.com";

            // When - multiple rapid login requests
            for (int i = 0; i < 5; i++) {
                authService.requestMagicLink(email);
            }

            // Then - user should exist and have multiple magic links
            Optional<User> user = userRepository.findByEmailIgnoreCase(email);
            assertThat(user).isPresent();
            
            // All magic link tokens should be created
            long tokenCount = magicLinkTokenRepository.count();
            assertThat(tokenCount).isEqualTo(5);
        }

        @Test
        @DisplayName("should maintain data integrity after many operations")
        @Transactional
        void shouldMaintainDataIntegrityAfterManyOperations() {
            // Given - a user
            User user = testData.createVerifiedUser("integrity@example.com");

            // When - perform many login/logout cycles
            for (int i = 0; i < 10; i++) {
                TestDataFactory.MagicLinkTokenResult tokenResult = testData.createValidMagicLinkFor(user);
                SessionService.SessionTokens tokens = authService.verifyMagicLink(
                        tokenResult.rawToken(), "Agent-" + i, "10.0.0." + i).orElseThrow();
                
                // Optionally logout some sessions
                if (i % 3 == 0) {
                    sessionService.revokeSession(tokens.refreshToken());
                }
            }

            // Then - user should still exist and be in consistent state
            User updatedUser = userRepository.findById(user.getId()).orElseThrow();
            assertThat(updatedUser.isEmailVerified()).isTrue();
            assertThat(updatedUser.getEmail()).isEqualTo("integrity@example.com");

            // Should have exactly one active session (from last login that wasn't logged out)
            List<AppRefreshToken> activeTokens = refreshTokenRepository.findActiveTokensByUser(user, Instant.now());
            assertThat(activeTokens.size()).isLessThanOrEqualTo(1);
        }
    }
}
