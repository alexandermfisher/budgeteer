package dev.amf.budgeteer.integration.repository;

import dev.amf.budgeteer.domain.oauth.OAuthState;
import dev.amf.budgeteer.repository.OAuthStateRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link OAuthStateRepository}.
 *
 * <p>Tests OAuth state persistence, retrieval, expiration, and cleanup
 * against a real PostgreSQL database using Testcontainers.
 */
@Transactional
class OAuthStateRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private OAuthStateRepository oAuthStateRepository;

    @Autowired
    private TestDataFactory testData;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = testData.createVerifiedUser();
    }

    @Nested
    @DisplayName("findByState")
    class FindByState {

        @Test
        @DisplayName("should find OAuth state by state token")
        void shouldFindByStateToken() {
            // Given
            String stateToken = "test-state-token-12345";
            OAuthState state = testData.createValidOAuthStateFor(testUser, stateToken);

            // When
            Optional<OAuthState> found = oAuthStateRepository.findByState(stateToken);

            // Then
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(state.getId());
            assertThat(found.get().getUser().getId()).isEqualTo(testUser.getId());
        }

        @Test
        @DisplayName("should return empty for non-existent state token")
        void shouldReturnEmptyForNonExistentToken() {
            // When
            Optional<OAuthState> found = oAuthStateRepository.findByState("non-existent-token");

            // Then
            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("findValidByState")
    class FindValidByState {

        @Test
        @DisplayName("should find valid (not expired, not used) OAuth state")
        void shouldFindValidState() {
            // Given
            OAuthState state = testData.createValidOAuthStateFor(testUser);

            // When
            Optional<OAuthState> found = oAuthStateRepository.findValidByState(
                    state.getState(), Instant.now());

            // Then
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(state.getId());
            assertThat(found.get().isUsed()).isFalse();
            assertThat(found.get().isExpired()).isFalse();
        }

        @Test
        @DisplayName("should not find expired OAuth state")
        void shouldNotFindExpiredState() {
            // Given
            OAuthState expiredState = testData.createExpiredOAuthStateFor(testUser);

            // When
            Optional<OAuthState> found = oAuthStateRepository.findValidByState(
                    expiredState.getState(), Instant.now());

            // Then
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should not find used OAuth state")
        void shouldNotFindUsedState() {
            // Given
            OAuthState usedState = testData.createUsedOAuthStateFor(testUser);

            // When
            Optional<OAuthState> found = oAuthStateRepository.findValidByState(
                    usedState.getState(), Instant.now());

            // Then
            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("deleteExpiredStates")
    class DeleteExpiredStates {

        @Test
        @DisplayName("should delete all expired states")
        void shouldDeleteExpiredStates() {
            // Given
            OAuthState expiredState1 = testData.createExpiredOAuthStateFor(testUser);
            OAuthState expiredState2 = testData.createExpiredOAuthStateFor(testUser);
            OAuthState validState = testData.createValidOAuthStateFor(testUser);

            // When
            int deletedCount = oAuthStateRepository.deleteExpiredStates(Instant.now());

            // Then
            assertThat(deletedCount).isEqualTo(2);
            assertThat(oAuthStateRepository.findById(expiredState1.getId())).isEmpty();
            assertThat(oAuthStateRepository.findById(expiredState2.getId())).isEmpty();
            assertThat(oAuthStateRepository.findById(validState.getId())).isPresent();
        }

        @Test
        @DisplayName("should return zero when no expired states exist")
        void shouldReturnZeroWhenNoExpiredStates() {
            // Given
            testData.createValidOAuthStateFor(testUser);

            // When
            int deletedCount = oAuthStateRepository.deleteExpiredStates(Instant.now());

            // Then
            assertThat(deletedCount).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("deleteByUserId")
    class DeleteByUserId {

        @Test
        @DisplayName("should delete all states for a user")
        void shouldDeleteAllStatesForUser() {
            // Given
            User otherUser = testData.createVerifiedUser();
            testData.createValidOAuthStateFor(testUser);
            testData.createValidOAuthStateFor(testUser);
            OAuthState otherUserState = testData.createValidOAuthStateFor(otherUser);

            // When
            int deletedCount = oAuthStateRepository.deleteByUserId(testUser.getId());

            // Then
            assertThat(deletedCount).isEqualTo(2);
            assertThat(oAuthStateRepository.findById(otherUserState.getId())).isPresent();
        }

        @Test
        @DisplayName("should return zero when user has no states")
        void shouldReturnZeroWhenUserHasNoStates() {
            // When
            int deletedCount = oAuthStateRepository.deleteByUserId(testUser.getId());

            // Then
            assertThat(deletedCount).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("countPendingByUserId")
    class CountPendingByUserId {

        @Test
        @DisplayName("should count only pending (valid, unused) states")
        void shouldCountOnlyPendingStates() {
            // Given
            testData.createValidOAuthStateFor(testUser);
            testData.createValidOAuthStateFor(testUser);
            testData.createExpiredOAuthStateFor(testUser);
            testData.createUsedOAuthStateFor(testUser);

            // When
            long count = oAuthStateRepository.countPendingByUserId(testUser.getId(), Instant.now());

            // Then
            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("should return zero when user has no pending states")
        void shouldReturnZeroWhenNoPendingStates() {
            // Given
            testData.createExpiredOAuthStateFor(testUser);
            testData.createUsedOAuthStateFor(testUser);

            // When
            long count = oAuthStateRepository.countPendingByUserId(testUser.getId(), Instant.now());

            // Then
            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("should not count states from other users")
        void shouldNotCountStatesFromOtherUsers() {
            // Given
            User otherUser = testData.createVerifiedUser();
            testData.createValidOAuthStateFor(testUser);
            testData.createValidOAuthStateFor(otherUser);
            testData.createValidOAuthStateFor(otherUser);

            // When
            long count = oAuthStateRepository.countPendingByUserId(testUser.getId(), Instant.now());

            // Then
            assertThat(count).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Entity Lifecycle")
    class EntityLifecycle {

        @Test
        @DisplayName("should set createdAt on persist")
        void shouldSetCreatedAtOnPersist() {
            // Given
            OAuthState state = new OAuthState(testUser, "test-state");

            // When
            OAuthState saved = oAuthStateRepository.save(state);

            // Then
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getCreatedAt()).isBefore(Instant.now().plus(1, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("should calculate correct expiry time")
        void shouldCalculateCorrectExpiryTime() {
            // Given
            OAuthState state = new OAuthState(testUser, "test-state");

            // When
            OAuthState saved = oAuthStateRepository.save(state);

            // Then
            assertThat(saved.getExpiresAt())
                    .isAfter(Instant.now().plus(OAuthState.STATE_EXPIRY_MINUTES - 1, ChronoUnit.MINUTES))
                    .isBefore(Instant.now().plus(OAuthState.STATE_EXPIRY_MINUTES + 1, ChronoUnit.MINUTES));
        }

        @Test
        @DisplayName("should persist markUsed state change")
        void shouldPersistMarkUsedStateChange() {
            // Given
            OAuthState state = testData.createValidOAuthStateFor(testUser);
            assertThat(state.isUsed()).isFalse();

            // When
            state.markUsed();
            oAuthStateRepository.save(state);

            // Then
            OAuthState reloaded = oAuthStateRepository.findById(state.getId()).orElseThrow();
            assertThat(reloaded.isUsed()).isTrue();
        }
    }
}
