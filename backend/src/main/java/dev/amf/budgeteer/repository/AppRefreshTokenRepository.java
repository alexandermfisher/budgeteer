package dev.amf.budgeteer.repository;

import dev.amf.budgeteer.domain.session.AppRefreshToken;
import dev.amf.budgeteer.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for AppRefreshToken entity operations.
 */
@Repository
public interface AppRefreshTokenRepository extends JpaRepository<AppRefreshToken, UUID> {

    /**
     * Finds a refresh token by its hash.
     *
     * @param tokenHash the SHA-256 hash of the token
     * @return the token if found
     */
    Optional<AppRefreshToken> findByTokenHash(String tokenHash);

    /**
     * Finds all active (non-revoked, non-expired) refresh tokens for a user.
     * Useful for "logged in devices" feature.
     *
     * @param user the user
     * @param now  current timestamp for expiry check
     * @return list of active refresh tokens
     */
    @Query("SELECT t FROM AppRefreshToken t WHERE t.user = :user AND t.revokedAt IS NULL AND t.expiresAt > :now")
    List<AppRefreshToken> findActiveTokensByUser(User user, Instant now);

    /**
     * Revokes all refresh tokens for a user.
     * Used for "logout everywhere" functionality.
     *
     * @param user the user whose tokens should be revoked
     * @param now  the current timestamp to set as revoked_at
     * @return the number of tokens revoked
     */
    @Modifying
    @Query("UPDATE AppRefreshToken t SET t.revokedAt = :now WHERE t.user = :user AND t.revokedAt IS NULL")
    int revokeAllTokensForUser(User user, Instant now);

    /**
     * Deletes all expired and revoked tokens older than the given threshold.
     * Used for cleanup of old tokens.
     *
     * @param threshold tokens created before this time will be deleted
     * @return the number of tokens deleted
     */
    @Modifying
    @Query("DELETE FROM AppRefreshToken t WHERE t.createdAt < :threshold AND (t.revokedAt IS NOT NULL OR t.expiresAt < :threshold)")
    int deleteOldTokens(Instant threshold);

    /**
     * Counts active sessions for a user.
     *
     * @param user the user
     * @param now  current timestamp for expiry check
     * @return count of active sessions
     */
    @Query("SELECT COUNT(t) FROM AppRefreshToken t WHERE t.user = :user AND t.revokedAt IS NULL AND t.expiresAt > :now")
    long countActiveSessionsByUser(User user, Instant now);

    /**
     * Revokes ALL refresh tokens in the system.
     * ⚠️ DEV ONLY - Used for testing/development cleanup.
     *
     * @param now the current timestamp to set as revoked_at
     * @return the number of tokens revoked
     */
    @Modifying
    @Query("UPDATE AppRefreshToken t SET t.revokedAt = :now WHERE t.revokedAt IS NULL")
    int revokeAllTokens(Instant now);
}
