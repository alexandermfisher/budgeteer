package dev.amf.budgeteer.domain.monzo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link MonzoConnection} entities.
 *
 * <p>Provides data access for Monzo connection management including:
 * <ul>
 *   <li>Finding active connections for a user</li>
 *   <li>Looking up by connection ID with user verification</li>
 *   <li>Finding connections with expired tokens (for refresh jobs)</li>
 * </ul>
 *
 * <h2>Security Note</h2>
 * <p>Most queries filter by user_id to ensure users can only access their own connections.
 * Always use user-scoped queries in service layer.
 */
@Repository
public interface MonzoConnectionRepository extends JpaRepository<MonzoConnection, UUID> {

    /**
     * Find all active connections for a user.
     *
     * @param userId the user's ID
     * @return list of active (non-disconnected) connections
     */
    @Query("SELECT mc FROM MonzoConnection mc WHERE mc.user.id = :userId AND mc.disconnectedAt IS NULL")
    List<MonzoConnection> findActiveByUserId(@Param("userId") UUID userId);

    /**
     * Find a connection by ID, only if it belongs to the specified user.
     * Used to ensure users can only access their own connections.
     *
     * @param id     the connection ID
     * @param userId the user's ID
     * @return the connection if found and owned by user
     */
    @Query("SELECT mc FROM MonzoConnection mc WHERE mc.id = :id AND mc.user.id = :userId")
    Optional<MonzoConnection> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Find an active connection by ID and user.
     *
     * @param id     the connection ID
     * @param userId the user's ID
     * @return the active connection if found
     */
    @Query("SELECT mc FROM MonzoConnection mc WHERE mc.id = :id AND mc.user.id = :userId AND mc.disconnectedAt IS NULL")
    Optional<MonzoConnection> findActiveByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Find an active connection by user and Monzo user ID.
     * Used to check for duplicate connections during OAuth flow.
     *
     * @param userId      the user's ID
     * @param monzoUserId the Monzo user ID
     * @return the active connection if exists
     */
    @Query("SELECT mc FROM MonzoConnection mc WHERE mc.user.id = :userId AND mc.monzoUserId = :monzoUserId AND mc.disconnectedAt IS NULL")
    Optional<MonzoConnection> findActiveByUserIdAndMonzoUserId(
            @Param("userId") UUID userId,
            @Param("monzoUserId") String monzoUserId
    );

    /**
     * Find any connection by user and Monzo user ID (including soft-deleted).
     * Used during reconnection flow to reactivate a previously disconnected connection.
     *
     * @param userId      the user's ID
     * @param monzoUserId the Monzo user ID
     * @return the connection if exists (active or disconnected)
     */
    @Query("SELECT mc FROM MonzoConnection mc WHERE mc.user.id = :userId AND mc.monzoUserId = :monzoUserId")
    Optional<MonzoConnection> findByUserIdAndMonzoUserId(
            @Param("userId") UUID userId,
            @Param("monzoUserId") String monzoUserId
    );

    /**
     * Find all active connections with expired tokens.
     * Used by scheduled job to refresh tokens.
     *
     * @param now current timestamp
     * @return list of connections needing token refresh
     */
    @Query("SELECT mc FROM MonzoConnection mc WHERE mc.disconnectedAt IS NULL AND mc.tokenExpiresAt < :now")
    List<MonzoConnection> findActiveWithExpiredTokens(@Param("now") Instant now);

    /**
     * Count active connections for a user.
     *
     * @param userId the user's ID
     * @return number of active connections
     */
    @Query("SELECT COUNT(mc) FROM MonzoConnection mc WHERE mc.user.id = :userId AND mc.disconnectedAt IS NULL")
    long countActiveByUserId(@Param("userId") UUID userId);

    /**
     * Check if a user has any active Monzo connection.
     *
     * @param userId the user's ID
     * @return true if user has at least one active connection
     */
    @Query("SELECT CASE WHEN COUNT(mc) > 0 THEN true ELSE false END FROM MonzoConnection mc WHERE mc.user.id = :userId AND mc.disconnectedAt IS NULL")
    boolean hasActiveConnectionForUser(@Param("userId") UUID userId);
}
