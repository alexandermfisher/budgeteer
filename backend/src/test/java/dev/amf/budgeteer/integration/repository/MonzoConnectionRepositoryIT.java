package dev.amf.budgeteer.integration.repository;

import dev.amf.budgeteer.domain.monzo.MonzoConnection;
import dev.amf.budgeteer.domain.monzo.MonzoConnectionRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.integration.AbstractPostgresIntegrationTest;
import dev.amf.budgeteer.integration.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MonzoConnectionRepository}.
 *
 * <p>Tests Monzo connection persistence, user isolation, soft delete,
 * and token management against a real PostgreSQL database using Testcontainers.
 */
@Transactional
class MonzoConnectionRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private MonzoConnectionRepository monzoConnectionRepository;

    @Autowired
    private TestDataFactory testData;

    private User testUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        testUser = testData.createVerifiedUser();
        otherUser = testData.createVerifiedUser();
    }

    @Nested
    @DisplayName("findActiveByUserId")
    class FindActiveByUserId {

        @Test
        @DisplayName("should find all active connections for a user")
        void shouldFindActiveConnectionsForUser() {
            // Given
            MonzoConnection connection1 = testData.createActiveMonzoConnectionFor(testUser, "user_abc123");
            MonzoConnection connection2 = testData.createActiveMonzoConnectionFor(testUser, "user_def456");
            testData.createDisconnectedMonzoConnectionFor(testUser); // Should not be included
            testData.createActiveMonzoConnectionFor(otherUser); // Different user

            // When
            List<MonzoConnection> connections = monzoConnectionRepository.findActiveByUserId(testUser.getId());

            // Then
            assertThat(connections).hasSize(2);
            assertThat(connections).extracting(MonzoConnection::getId)
                    .containsExactlyInAnyOrder(connection1.getId(), connection2.getId());
        }

        @Test
        @DisplayName("should return empty list when user has no active connections")
        void shouldReturnEmptyListWhenNoActiveConnections() {
            // Given
            testData.createDisconnectedMonzoConnectionFor(testUser);

            // When
            List<MonzoConnection> connections = monzoConnectionRepository.findActiveByUserId(testUser.getId());

            // Then
            assertThat(connections).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByIdAndUserId")
    class FindByIdAndUserId {

        @Test
        @DisplayName("should find connection when ID and user match")
        void shouldFindConnectionWhenIdAndUserMatch() {
            // Given
            MonzoConnection connection = testData.createActiveMonzoConnectionFor(testUser);

            // When
            Optional<MonzoConnection> found = monzoConnectionRepository.findByIdAndUserId(
                    connection.getId(), testUser.getId());

            // Then
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(connection.getId());
        }

        @Test
        @DisplayName("should not find connection when user does not match")
        void shouldNotFindConnectionWhenUserDoesNotMatch() {
            // Given
            MonzoConnection connection = testData.createActiveMonzoConnectionFor(testUser);

            // When - Try to access with different user
            Optional<MonzoConnection> found = monzoConnectionRepository.findByIdAndUserId(
                    connection.getId(), otherUser.getId());

            // Then
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should find disconnected connections (for viewing history)")
        void shouldFindDisconnectedConnections() {
            // Given
            MonzoConnection disconnected = testData.createDisconnectedMonzoConnectionFor(testUser);

            // When
            Optional<MonzoConnection> found = monzoConnectionRepository.findByIdAndUserId(
                    disconnected.getId(), testUser.getId());

            // Then
            assertThat(found).isPresent();
            assertThat(found.get().isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("findActiveByIdAndUserId")
    class FindActiveByIdAndUserId {

        @Test
        @DisplayName("should find active connection when ID and user match")
        void shouldFindActiveConnection() {
            // Given
            MonzoConnection connection = testData.createActiveMonzoConnectionFor(testUser);

            // When
            Optional<MonzoConnection> found = monzoConnectionRepository.findActiveByIdAndUserId(
                    connection.getId(), testUser.getId());

            // Then
            assertThat(found).isPresent();
            assertThat(found.get().isActive()).isTrue();
        }

        @Test
        @DisplayName("should not find disconnected connection")
        void shouldNotFindDisconnectedConnection() {
            // Given
            MonzoConnection disconnected = testData.createDisconnectedMonzoConnectionFor(testUser);

            // When
            Optional<MonzoConnection> found = monzoConnectionRepository.findActiveByIdAndUserId(
                    disconnected.getId(), testUser.getId());

            // Then
            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("findActiveByUserIdAndMonzoUserId")
    class FindActiveByUserIdAndMonzoUserId {

        @Test
        @DisplayName("should find connection by user and Monzo user ID")
        void shouldFindByUserAndMonzoUserId() {
            // Given
            String monzoUserId = "user_unique123";
            MonzoConnection connection = testData.createActiveMonzoConnectionFor(testUser, monzoUserId);

            // When
            Optional<MonzoConnection> found = monzoConnectionRepository.findActiveByUserIdAndMonzoUserId(
                    testUser.getId(), monzoUserId);

            // Then
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(connection.getId());
        }

        @Test
        @DisplayName("should not find disconnected connection by Monzo user ID")
        void shouldNotFindDisconnectedByMonzoUserId() {
            // Given
            String monzoUserId = "user_disconnected";
            MonzoConnection connection = testData.createActiveMonzoConnectionFor(testUser, monzoUserId);
            connection.disconnect();
            monzoConnectionRepository.save(connection);

            // When
            Optional<MonzoConnection> found = monzoConnectionRepository.findActiveByUserIdAndMonzoUserId(
                    testUser.getId(), monzoUserId);

            // Then
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should not find another user's connection")
        void shouldNotFindAnotherUsersConnection() {
            // Given
            String monzoUserId = "user_shared";
            testData.createActiveMonzoConnectionFor(otherUser, monzoUserId);

            // When
            Optional<MonzoConnection> found = monzoConnectionRepository.findActiveByUserIdAndMonzoUserId(
                    testUser.getId(), monzoUserId);

            // Then
            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("findActiveWithExpiredTokens")
    class FindActiveWithExpiredTokens {

        @Test
        @DisplayName("should find connections with expired tokens")
        void shouldFindConnectionsWithExpiredTokens() {
            // Given
            MonzoConnection expiredConnection = testData.createMonzoConnectionWithExpiredTokens(testUser);
            testData.createActiveMonzoConnectionFor(testUser); // Valid tokens
            testData.createDisconnectedMonzoConnectionFor(testUser); // Disconnected

            // When
            List<MonzoConnection> expired = monzoConnectionRepository.findActiveWithExpiredTokens(Instant.now());

            // Then
            assertThat(expired).hasSize(1);
            assertThat(expired.get(0).getId()).isEqualTo(expiredConnection.getId());
        }

        @Test
        @DisplayName("should return empty list when no expired tokens")
        void shouldReturnEmptyWhenNoExpiredTokens() {
            // Given
            testData.createActiveMonzoConnectionFor(testUser);

            // When
            List<MonzoConnection> expired = monzoConnectionRepository.findActiveWithExpiredTokens(Instant.now());

            // Then
            assertThat(expired).isEmpty();
        }
    }

    @Nested
    @DisplayName("countActiveByUserId")
    class CountActiveByUserId {

        @Test
        @DisplayName("should count only active connections")
        void shouldCountOnlyActiveConnections() {
            // Given
            testData.createActiveMonzoConnectionFor(testUser);
            testData.createActiveMonzoConnectionFor(testUser);
            testData.createDisconnectedMonzoConnectionFor(testUser);
            testData.createActiveMonzoConnectionFor(otherUser);

            // When
            long count = monzoConnectionRepository.countActiveByUserId(testUser.getId());

            // Then
            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("should return zero when user has no active connections")
        void shouldReturnZeroWhenNoActiveConnections() {
            // When
            long count = monzoConnectionRepository.countActiveByUserId(testUser.getId());

            // Then
            assertThat(count).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("hasActiveConnectionForUser")
    class HasActiveConnectionForUser {

        @Test
        @DisplayName("should return true when user has active connection")
        void shouldReturnTrueWhenHasActiveConnection() {
            // Given
            testData.createActiveMonzoConnectionFor(testUser);

            // When
            boolean hasConnection = monzoConnectionRepository.hasActiveConnectionForUser(testUser.getId());

            // Then
            assertThat(hasConnection).isTrue();
        }

        @Test
        @DisplayName("should return false when user has no active connection")
        void shouldReturnFalseWhenNoActiveConnection() {
            // Given
            testData.createDisconnectedMonzoConnectionFor(testUser);

            // When
            boolean hasConnection = monzoConnectionRepository.hasActiveConnectionForUser(testUser.getId());

            // Then
            assertThat(hasConnection).isFalse();
        }

        @Test
        @DisplayName("should return false for user with no connections")
        void shouldReturnFalseForUserWithNoConnections() {
            // When
            boolean hasConnection = monzoConnectionRepository.hasActiveConnectionForUser(testUser.getId());

            // Then
            assertThat(hasConnection).isFalse();
        }
    }

    @Nested
    @DisplayName("Entity Lifecycle")
    class EntityLifecycle {

        @Test
        @DisplayName("should set connectedAt and updatedAt on persist")
        void shouldSetTimestampsOnPersist() {
            // Given
            MonzoConnection connection = new MonzoConnection(
                    testUser,
                    "user_test",
                    "encrypted_access",
                    "encrypted_refresh",
                    Instant.now().plus(6, ChronoUnit.HOURS)
            );

            // When
            MonzoConnection saved = monzoConnectionRepository.save(connection);

            // Then
            assertThat(saved.getConnectedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
            assertThat(saved.getDisconnectedAt()).isNull();
        }

        @Test
        @DisplayName("should update updatedAt on save")
        void shouldUpdateUpdatedAtOnSave() throws InterruptedException {
            // Given
            MonzoConnection connection = testData.createActiveMonzoConnectionFor(testUser);
            Instant originalUpdatedAt = connection.getUpdatedAt();

            // Small delay to ensure timestamp difference
            Thread.sleep(10);

            // When
            connection.updateTokens("new_access", "new_refresh", Instant.now().plus(6, ChronoUnit.HOURS));
            MonzoConnection updated = monzoConnectionRepository.saveAndFlush(connection);

            // Then
            assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
        }

        @Test
        @DisplayName("should persist disconnect (soft delete)")
        void shouldPersistSoftDelete() {
            // Given
            MonzoConnection connection = testData.createActiveMonzoConnectionFor(testUser);
            assertThat(connection.isActive()).isTrue();

            // When
            connection.disconnect();
            monzoConnectionRepository.save(connection);

            // Then
            MonzoConnection reloaded = monzoConnectionRepository.findById(connection.getId()).orElseThrow();
            assertThat(reloaded.isActive()).isFalse();
            assertThat(reloaded.getDisconnectedAt()).isNotNull();
        }

        @Test
        @DisplayName("should persist token updates")
        void shouldPersistTokenUpdates() {
            // Given
            MonzoConnection connection = testData.createActiveMonzoConnectionFor(testUser);
            String newAccessToken = "new_encrypted_access_token";
            String newRefreshToken = "new_encrypted_refresh_token";
            Instant newExpiry = Instant.now().plus(12, ChronoUnit.HOURS);

            // When
            connection.updateTokens(newAccessToken, newRefreshToken, newExpiry);
            monzoConnectionRepository.save(connection);

            // Then
            MonzoConnection reloaded = monzoConnectionRepository.findById(connection.getId()).orElseThrow();
            assertThat(reloaded.getAccessTokenEncrypted()).isEqualTo(newAccessToken);
            assertThat(reloaded.getRefreshTokenEncrypted()).isEqualTo(newRefreshToken);
            assertThat(reloaded.getTokenExpiresAt()).isEqualTo(newExpiry);
        }
    }

    @Nested
    @DisplayName("User Isolation")
    class UserIsolation {

        @Test
        @DisplayName("should enforce user isolation - cannot access other user's connections")
        void shouldEnforceUserIsolation() {
            // Given
            MonzoConnection otherUserConnection = testData.createActiveMonzoConnectionFor(otherUser);

            // When - Try various methods to access other user's connection
            Optional<MonzoConnection> byIdAndUser = monzoConnectionRepository.findByIdAndUserId(
                    otherUserConnection.getId(), testUser.getId());
            Optional<MonzoConnection> activeByIdAndUser = monzoConnectionRepository.findActiveByIdAndUserId(
                    otherUserConnection.getId(), testUser.getId());
            List<MonzoConnection> activeForUser = monzoConnectionRepository.findActiveByUserId(testUser.getId());

            // Then - All should not expose other user's connection
            assertThat(byIdAndUser).isEmpty();
            assertThat(activeByIdAndUser).isEmpty();
            assertThat(activeForUser).isEmpty();
        }

        @Test
        @DisplayName("should allow same Monzo user ID for different app users")
        void shouldAllowSameMonzoUserIdForDifferentAppUsers() {
            // Given - Two app users connect the same Monzo account (edge case but valid)
            String sharedMonzoUserId = "user_shared_account";
            MonzoConnection connection1 = testData.createActiveMonzoConnectionFor(testUser, sharedMonzoUserId);
            MonzoConnection connection2 = testData.createActiveMonzoConnectionFor(otherUser, sharedMonzoUserId);

            // When
            Optional<MonzoConnection> forTestUser = monzoConnectionRepository.findActiveByUserIdAndMonzoUserId(
                    testUser.getId(), sharedMonzoUserId);
            Optional<MonzoConnection> forOtherUser = monzoConnectionRepository.findActiveByUserIdAndMonzoUserId(
                    otherUser.getId(), sharedMonzoUserId);

            // Then - Each user gets their own connection
            assertThat(forTestUser).isPresent();
            assertThat(forTestUser.get().getId()).isEqualTo(connection1.getId());
            assertThat(forOtherUser).isPresent();
            assertThat(forOtherUser.get().getId()).isEqualTo(connection2.getId());
        }
    }
}
