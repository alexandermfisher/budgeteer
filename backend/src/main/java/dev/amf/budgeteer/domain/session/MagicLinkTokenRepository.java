package dev.amf.budgeteer.domain.session;

import dev.amf.budgeteer.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for MagicLinkToken entity operations.
 */
@Repository
public interface MagicLinkTokenRepository extends JpaRepository<MagicLinkToken, UUID> {

    /**
     * Finds a magic link token by its hash.
     *
     * @param tokenHash the SHA-256 hash of the token
     * @return the token if found
     */
    Optional<MagicLinkToken> findByTokenHash(String tokenHash);

    /**
     * Deletes all expired or used tokens older than the given threshold.
     * Used for cleanup of old tokens.
     *
     * @param threshold tokens created before this time will be deleted
     * @return the number of tokens deleted
     */
    @Modifying
    @Query("DELETE FROM MagicLinkToken t WHERE t.createdAt < :threshold")
    int deleteTokensOlderThan(Instant threshold);

    /**
     * Invalidates all pending magic link tokens for a user.
     * Called when a user logs in successfully to prevent token reuse.
     *
     * @param user the user whose tokens should be invalidated
     * @param now  the current timestamp to set as used_at
     * @return the number of tokens invalidated
     */
    @Modifying
    @Query("UPDATE MagicLinkToken t SET t.usedAt = :now WHERE t.user = :user AND t.usedAt IS NULL")
    int invalidateAllTokensForUser(User user, Instant now);
}
