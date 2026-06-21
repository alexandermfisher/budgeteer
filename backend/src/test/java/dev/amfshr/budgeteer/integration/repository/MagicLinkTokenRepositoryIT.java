package dev.amfshr.budgeteer.integration.repository;

import dev.amfshr.budgeteer.domain.session.MagicLinkToken;
import dev.amfshr.budgeteer.repository.MagicLinkTokenRepository;
import dev.amfshr.budgeteer.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link MagicLinkTokenRepository}.
 * Uses H2 in-memory database via @DataJpaTest.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("MagicLinkTokenRepository Integration Tests")
class MagicLinkTokenRepositoryIT {

    // 64-character hash constants satisfying @Size(min=64, max=64) on tokenHash
    private static final String H1 = "a".repeat(64);
    private static final String H2 = "b".repeat(64);
    private static final String H3 = "c".repeat(64);
    private static final String H4 = "d".repeat(64);
    private static final String H5 = "e".repeat(64);
    private static final String H6 = "f".repeat(64);
    private static final String H7 = "1".repeat(64);
    private static final String H8 = "2".repeat(64);

    @Autowired
    private MagicLinkTokenRepository tokenRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User("test@example.com");
        entityManager.persistAndFlush(testUser);
    }

    // findByTokenHash tests

    @Test
    @DisplayName("findByTokenHash - should find token by hash")
    void findByTokenHash_shouldFindByHash() {
        MagicLinkToken token = new MagicLinkToken(testUser, H1, Instant.now().plusSeconds(900));
        entityManager.persistAndFlush(token);
        entityManager.clear();

        Optional<MagicLinkToken> found = tokenRepository.findByTokenHash(H1);

        assertThat(found).isPresent();
        assertThat(found.get().getTokenHash()).isEqualTo(H1);
    }

    @Test
    @DisplayName("findByTokenHash - should return empty when hash not found")
    void findByTokenHash_shouldReturnEmptyWhenNotFound() {
        Optional<MagicLinkToken> found = tokenRepository.findByTokenHash("nonexistent");
        assertThat(found).isEmpty();
    }

    // deleteTokensOlderThan tests

    @Test
    @DisplayName("deleteTokensOlderThan - should delete tokens created before threshold")
    void deleteTokensOlderThan_shouldDeleteTokensCreatedBeforeThreshold() {
        // Create tokens (createdAt is set by @PrePersist to now)
        MagicLinkToken token1 = new MagicLinkToken(testUser, H1, Instant.now().plusSeconds(900));
        MagicLinkToken token2 = new MagicLinkToken(testUser, H2, Instant.now().plusSeconds(900));
        entityManager.persistAndFlush(token1);
        entityManager.persistAndFlush(token2);
        entityManager.clear();

        // Use future threshold to delete all tokens
        Instant futureThreshold = Instant.now().plus(1, ChronoUnit.DAYS);

        int deleted = tokenRepository.deleteTokensOlderThan(futureThreshold);

        assertThat(deleted).isEqualTo(2);
        assertThat(tokenRepository.findByTokenHash(H1)).isEmpty();
        assertThat(tokenRepository.findByTokenHash(H2)).isEmpty();
    }

    @Test
    @DisplayName("deleteTokensOlderThan - should not delete tokens created after threshold")
    void deleteTokensOlderThan_shouldNotDeleteTokensCreatedAfterThreshold() {
        MagicLinkToken token = new MagicLinkToken(testUser, H1, Instant.now().plusSeconds(900));
        entityManager.persistAndFlush(token);
        entityManager.clear();

        // Use past threshold - token was created now, so it won't be deleted
        Instant pastThreshold = Instant.now().minus(1, ChronoUnit.DAYS);

        int deleted = tokenRepository.deleteTokensOlderThan(pastThreshold);

        assertThat(deleted).isEqualTo(0);
        assertThat(tokenRepository.findByTokenHash(H1)).isPresent();
    }

    // invalidateAllTokensForUser tests

    @Test
    @DisplayName("invalidateAllTokensForUser - should invalidate all pending tokens")
    void invalidateAllTokensForUser_shouldInvalidateAllPendingTokens() {
        MagicLinkToken token1 = new MagicLinkToken(testUser, H1, Instant.now().plusSeconds(900));
        MagicLinkToken token2 = new MagicLinkToken(testUser, H2, Instant.now().plusSeconds(900));
        entityManager.persistAndFlush(token1);
        entityManager.persistAndFlush(token2);
        entityManager.clear();

        Instant now = Instant.now();
        int invalidated = tokenRepository.invalidateAllTokensForUser(testUser, now);
        entityManager.clear();

        assertThat(invalidated).isEqualTo(2);
        MagicLinkToken found1 = tokenRepository.findByTokenHash(H1).orElseThrow();
        MagicLinkToken found2 = tokenRepository.findByTokenHash(H2).orElseThrow();
        assertThat(found1.isUsed()).isTrue();
        assertThat(found2.isUsed()).isTrue();
    }

    @Test
    @DisplayName("invalidateAllTokensForUser - should not invalidate already used tokens")
    void invalidateAllTokensForUser_shouldNotInvalidateAlreadyUsedTokens() {
        MagicLinkToken unusedToken = new MagicLinkToken(testUser, H1, Instant.now().plusSeconds(900));
        MagicLinkToken usedToken = new MagicLinkToken(testUser, H2, Instant.now().plusSeconds(900));
        usedToken.markAsUsed();
        entityManager.persistAndFlush(unusedToken);
        entityManager.persistAndFlush(usedToken);
        entityManager.clear();

        int invalidated = tokenRepository.invalidateAllTokensForUser(testUser, Instant.now());

        assertThat(invalidated).isEqualTo(1);
    }

    @Test
    @DisplayName("invalidateAllTokensForUser - should not affect other users' tokens")
    void invalidateAllTokensForUser_shouldNotAffectOtherUsers() {
        User otherUser = new User("other@example.com");
        entityManager.persistAndFlush(otherUser);
        
        MagicLinkToken myToken = new MagicLinkToken(testUser, H1, Instant.now().plusSeconds(900));
        MagicLinkToken theirToken = new MagicLinkToken(otherUser, H2, Instant.now().plusSeconds(900));
        entityManager.persistAndFlush(myToken);
        entityManager.persistAndFlush(theirToken);
        entityManager.clear();

        tokenRepository.invalidateAllTokensForUser(testUser, Instant.now());
        entityManager.clear();

        MagicLinkToken theirFound = tokenRepository.findByTokenHash(H2).orElseThrow();
        assertThat(theirFound.isUsed()).isFalse();
    }

    // Entity Lifecycle tests

    @Test
    @DisplayName("Entity lifecycle - should persist token successfully")
    void entityLifecycle_shouldPersistToken() {
        MagicLinkToken token = new MagicLinkToken(testUser, H1, Instant.now().plusSeconds(900));

        entityManager.persistAndFlush(token);

        assertThat(tokenRepository.findByTokenHash(H1)).isPresent();
    }

    @Test
    @DisplayName("Entity lifecycle - should enforce unique tokenHash constraint")
    void entityLifecycle_shouldEnforceUniqueTokenHash() {
        MagicLinkToken token1 = new MagicLinkToken(testUser, H1, Instant.now().plusSeconds(900));
        entityManager.persistAndFlush(token1);

        MagicLinkToken token2 = new MagicLinkToken(testUser, H1, Instant.now().plusSeconds(900));

        assertThatThrownBy(() -> entityManager.persistAndFlush(token2))
                .isInstanceOf(jakarta.persistence.PersistenceException.class);
    }
}
