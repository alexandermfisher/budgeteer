package dev.amf.budgeteer.integration.repository;

import dev.amf.budgeteer.domain.session.AppRefreshToken;
import dev.amf.budgeteer.domain.session.AppRefreshTokenRepository;
import dev.amf.budgeteer.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AppRefreshTokenRepository}.
 * Uses H2 in-memory database via @DataJpaTest.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("AppRefreshTokenRepository Integration Tests")
class AppRefreshTokenRepositoryIT {

    @Autowired
    private AppRefreshTokenRepository tokenRepository;

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
        AppRefreshToken token = new AppRefreshToken(testUser, "refresh-hash", Instant.now().plus(7, ChronoUnit.DAYS));
        entityManager.persistAndFlush(token);
        entityManager.clear();

        Optional<AppRefreshToken> found = tokenRepository.findByTokenHash("refresh-hash");

        assertThat(found).isPresent();
        assertThat(found.get().getTokenHash()).isEqualTo("refresh-hash");
    }

    @Test
    @DisplayName("findByTokenHash - should return empty when hash not found")
    void findByTokenHash_shouldReturnEmptyWhenNotFound() {
        Optional<AppRefreshToken> found = tokenRepository.findByTokenHash("nonexistent");
        assertThat(found).isEmpty();
    }

    // findActiveTokensByUser tests

    @Test
    @DisplayName("findActiveTokensByUser - should find only active tokens")
    void findActiveTokensByUser_shouldFindOnlyActiveTokens() {
        AppRefreshToken active = new AppRefreshToken(testUser, "active-hash", Instant.now().plus(7, ChronoUnit.DAYS));
        AppRefreshToken expired = new AppRefreshToken(testUser, "expired-hash", Instant.now().minus(1, ChronoUnit.HOURS));
        AppRefreshToken revoked = new AppRefreshToken(testUser, "revoked-hash", Instant.now().plus(7, ChronoUnit.DAYS));
        revoked.revoke();
        
        entityManager.persistAndFlush(active);
        entityManager.persistAndFlush(expired);
        entityManager.persistAndFlush(revoked);
        entityManager.clear();

        List<AppRefreshToken> activeTokens = tokenRepository.findActiveTokensByUser(testUser, Instant.now());

        assertThat(activeTokens).hasSize(1);
        assertThat(activeTokens.get(0).getTokenHash()).isEqualTo("active-hash");
    }

    @Test
    @DisplayName("findActiveTokensByUser - should not return tokens of other users")
    void findActiveTokensByUser_shouldNotReturnOtherUsersTokens() {
        User otherUser = new User("other@example.com");
        entityManager.persistAndFlush(otherUser);
        
        AppRefreshToken myToken = new AppRefreshToken(testUser, "my-token", Instant.now().plus(7, ChronoUnit.DAYS));
        AppRefreshToken theirToken = new AppRefreshToken(otherUser, "their-token", Instant.now().plus(7, ChronoUnit.DAYS));
        entityManager.persistAndFlush(myToken);
        entityManager.persistAndFlush(theirToken);
        entityManager.clear();

        List<AppRefreshToken> myTokens = tokenRepository.findActiveTokensByUser(testUser, Instant.now());

        assertThat(myTokens).hasSize(1);
        assertThat(myTokens.get(0).getTokenHash()).isEqualTo("my-token");
    }

    // revokeAllTokensForUser tests

    @Test
    @DisplayName("revokeAllTokensForUser - should revoke all active tokens for user")
    void revokeAllTokensForUser_shouldRevokeAllActiveTokens() {
        AppRefreshToken token1 = new AppRefreshToken(testUser, "token1", Instant.now().plus(7, ChronoUnit.DAYS));
        AppRefreshToken token2 = new AppRefreshToken(testUser, "token2", Instant.now().plus(7, ChronoUnit.DAYS));
        entityManager.persistAndFlush(token1);
        entityManager.persistAndFlush(token2);
        entityManager.clear();

        int revoked = tokenRepository.revokeAllTokensForUser(testUser, Instant.now());
        entityManager.clear();

        assertThat(revoked).isEqualTo(2);
        assertThat(tokenRepository.findActiveTokensByUser(testUser, Instant.now())).isEmpty();
    }

    @Test
    @DisplayName("revokeAllTokensForUser - should not affect other users' tokens")
    void revokeAllTokensForUser_shouldNotAffectOtherUsers() {
        User otherUser = new User("other@example.com");
        entityManager.persistAndFlush(otherUser);
        
        AppRefreshToken myToken = new AppRefreshToken(testUser, "my-token", Instant.now().plus(7, ChronoUnit.DAYS));
        AppRefreshToken theirToken = new AppRefreshToken(otherUser, "their-token", Instant.now().plus(7, ChronoUnit.DAYS));
        entityManager.persistAndFlush(myToken);
        entityManager.persistAndFlush(theirToken);
        entityManager.clear();

        tokenRepository.revokeAllTokensForUser(testUser, Instant.now());
        entityManager.clear();

        List<AppRefreshToken> theirTokens = tokenRepository.findActiveTokensByUser(otherUser, Instant.now());
        assertThat(theirTokens).hasSize(1);
    }

    // countActiveSessionsByUser tests

    @Test
    @DisplayName("countActiveSessionsByUser - should count only active sessions")
    void countActiveSessionsByUser_shouldCountOnlyActive() {
        AppRefreshToken active1 = new AppRefreshToken(testUser, "active1", Instant.now().plus(7, ChronoUnit.DAYS));
        AppRefreshToken active2 = new AppRefreshToken(testUser, "active2", Instant.now().plus(7, ChronoUnit.DAYS));
        AppRefreshToken expired = new AppRefreshToken(testUser, "expired", Instant.now().minus(1, ChronoUnit.HOURS));
        AppRefreshToken revoked = new AppRefreshToken(testUser, "revoked", Instant.now().plus(7, ChronoUnit.DAYS));
        revoked.revoke();
        
        entityManager.persistAndFlush(active1);
        entityManager.persistAndFlush(active2);
        entityManager.persistAndFlush(expired);
        entityManager.persistAndFlush(revoked);
        entityManager.clear();

        long count = tokenRepository.countActiveSessionsByUser(testUser, Instant.now());

        assertThat(count).isEqualTo(2);
    }

    // revokeAllTokens (DEV ONLY) tests

    @Test
    @DisplayName("revokeAllTokens - should revoke all tokens in system")
    void revokeAllTokens_shouldRevokeAllTokens() {
        User otherUser = new User("other@example.com");
        entityManager.persistAndFlush(otherUser);
        
        AppRefreshToken token1 = new AppRefreshToken(testUser, "token1", Instant.now().plus(7, ChronoUnit.DAYS));
        AppRefreshToken token2 = new AppRefreshToken(otherUser, "token2", Instant.now().plus(7, ChronoUnit.DAYS));
        entityManager.persistAndFlush(token1);
        entityManager.persistAndFlush(token2);
        entityManager.clear();

        int revoked = tokenRepository.revokeAllTokens(Instant.now());
        entityManager.clear();

        assertThat(revoked).isEqualTo(2);
        assertThat(tokenRepository.findActiveTokensByUser(testUser, Instant.now())).isEmpty();
        assertThat(tokenRepository.findActiveTokensByUser(otherUser, Instant.now())).isEmpty();
    }

    // Device Info tests

    @Test
    @DisplayName("Device info - should persist device info")
    void deviceInfo_shouldPersistDeviceInfo() {
        AppRefreshToken token = new AppRefreshToken(
                testUser, "device-token", Instant.now().plus(7, ChronoUnit.DAYS),
                "Mozilla/5.0 Chrome", "192.168.1.100"
        );
        entityManager.persistAndFlush(token);
        entityManager.clear();

        AppRefreshToken found = tokenRepository.findByTokenHash("device-token").orElseThrow();

        assertThat(found.getUserAgent()).isEqualTo("Mozilla/5.0 Chrome");
        assertThat(found.getIpAddress()).isEqualTo("192.168.1.100");
    }
}
