package dev.amf.budgeteer.repository;

import dev.amf.budgeteer.domain.oauth.OAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for OAuth state management.
 *
 * <p>Provides methods for storing and retrieving OAuth state tokens used
 * during the Monzo OAuth flow for CSRF protection.
 */
@Repository
public interface OAuthStateRepository extends JpaRepository<OAuthState, UUID> {

    /**
     * Finds an OAuth state by its state token.
     *
     * @param state the state token to look up
     * @return the OAuth state if found
     */
    Optional<OAuthState> findByState(String state);

    /**
     * Finds a valid (not expired, not used) OAuth state by its state token.
     *
     * @param state the state token to look up
     * @param now   the current time for expiry check
     * @return the OAuth state if found and valid
     */
    @Query("SELECT o FROM OAuthState o WHERE o.state = :state "
            + "AND o.used = false AND o.expiresAt > :now")
    Optional<OAuthState> findValidByState(@Param("state") String state, @Param("now") Instant now);

    /**
     * Deletes all expired OAuth states.
     *
     * <p>Should be called periodically to clean up old states.
     *
     * @param now the current time
     * @return the number of deleted states
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OAuthState o WHERE o.expiresAt < :now")
    int deleteExpiredStates(@Param("now") Instant now);

    /**
     * Deletes all OAuth states for a user.
     *
     * <p>Useful when a user disconnects or wants to cancel pending OAuth flows.
     *
     * @param userId the user ID
     * @return the number of deleted states
     */
    @Modifying
    @Query("DELETE FROM OAuthState o WHERE o.user.id = :userId")
    int deleteByUserId(@Param("userId") UUID userId);

    /**
     * Counts pending (not used, not expired) OAuth states for a user.
     *
     * @param userId the user ID
     * @param now    the current time for expiry check
     * @return the number of pending states
     */
    @Query("SELECT COUNT(o) FROM OAuthState o WHERE o.user.id = :userId "
            + "AND o.used = false AND o.expiresAt > :now")
    long countPendingByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
