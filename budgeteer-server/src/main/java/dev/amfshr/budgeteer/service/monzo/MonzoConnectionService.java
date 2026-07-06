package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.config.MonzoTokenRefreshProperties;
import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.repository.UserRepository;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.service.common.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing Monzo bank account connections.
 *
 * <p>Handles the business logic for creating, retrieving, and managing
 * Monzo OAuth connections. All token encryption/decryption is handled here.
 *
 * <h2>Eager refresh</h2>
 * <p>{@link #getDecryptedAccessToken} and {@link #getDecryptedTokens} include an
 * eager-refresh guard: if the token will expire within the configured eager-refresh
 * window ({@code monzo.token-refresh.eager-refresh-window-minutes}), they proactively
 * refresh via {@link MonzoTokenRefreshService} before returning.
 * This is a belt-and-suspenders measure for active users, complementing the
 * {@link MonzoTokenRefreshJob} background scheduler that covers inactive users.
 */
@Service
public class MonzoConnectionService {

    private static final Logger log = LoggerFactory.getLogger(MonzoConnectionService.class);

    private final MonzoConnectionRepository connectionRepository;
    private final MonzoAccountRepository accountRepository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final MonzoTokenRefreshService tokenRefreshService;

    /** Window used by the eager-refresh guard, driven by {@code monzo.token-refresh.eager-refresh-window-minutes}. */
    private final Duration eagerRefreshWindow;

    public MonzoConnectionService(
            MonzoConnectionRepository connectionRepository,
            MonzoAccountRepository accountRepository,
            UserRepository userRepository,
            EncryptionService encryptionService,
            MonzoTokenRefreshService tokenRefreshService,
            MonzoTokenRefreshProperties properties
    ) {
        this.connectionRepository = connectionRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.tokenRefreshService = tokenRefreshService;
        this.eagerRefreshWindow = Duration.ofMinutes(properties.eagerRefreshWindowMinutes());
    }

    /**
     * Creates a new Monzo connection for a user.
     *
     * <p>Connection handling:
     * <ul>
     *   <li>If user has an active connection for the same Monzo account → update tokens</li>
     *   <li>If user has a soft-deleted connection for the same account → reactivate and update tokens</li>
     *   <li>Otherwise → create new connection</li>
     * </ul>
     *
     * @param userId       the app user's ID
     * @param monzoUserId  the Monzo user ID from /ping/whoami
     * @param accessToken  the plaintext access token
     * @param refreshToken the plaintext refresh token
     * @param expiresAt    when the access token expires
     * @return the created, reactivated, or updated connection
     * @throws ApiException if user not found
     */
    @Transactional
    public MonzoConnection createConnection(
            UUID userId,
            String monzoUserId,
            String accessToken,
            String refreshToken,
            Instant expiresAt
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Check for ANY existing connection to the same Monzo account (including soft-deleted)
        MonzoConnection existing = connectionRepository
                .findByUserIdAndMonzoUserId(userId, monzoUserId)
                .orElse(null);

        if (existing != null) {
            // Encrypt new tokens
            String accessTokenEncrypted = encryptionService.encrypt(accessToken);
            String refreshTokenEncrypted = encryptionService.encrypt(refreshToken);

            if (existing.isActive()) {
                // Active connection: just update tokens
                log.info("Updating existing Monzo connection {} for user {}", existing.getId(), userId);
            } else {
                // Soft-deleted connection: reactivate it
                log.info("Reactivating soft-deleted Monzo connection {} for user {}", existing.getId(), userId);
                existing.reactivate();
            }

            existing.updateTokens(accessTokenEncrypted, refreshTokenEncrypted, expiresAt);
            return connectionRepository.save(existing);
        }

        // No existing connection: create new one
        String accessTokenEncrypted = encryptionService.encrypt(accessToken);
        String refreshTokenEncrypted = encryptionService.encrypt(refreshToken);

        MonzoConnection connection = new MonzoConnection(
                user,
                monzoUserId,
                accessTokenEncrypted,
                refreshTokenEncrypted,
                expiresAt
        );

        MonzoConnection saved = connectionRepository.save(connection);
        log.info("Created new Monzo connection {} for user {}", saved.getId(), userId);

        return saved;
    }

    /**
     * Gets a connection by ID, verifying ownership.
     *
     * @param connectionId the connection ID
     * @param userId       the user ID (for ownership verification)
     * @return the connection
     * @throws ApiException if a connection isn't found or not owned by a user
     */
    @Transactional(readOnly = true)
    public MonzoConnection getConnection(UUID connectionId, UUID userId) {
        return connectionRepository.findByIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Monzo connection not found"
                ));
    }

    /**
     * Gets an active connection by ID, verifying ownership.
     *
     * @param connectionId the connection ID
     * @param userId       the user ID (for ownership verification)
     * @return the active connection
     * @throws ApiException if a connection isn't found, not active, or not owned by a user
     */
    @Transactional(readOnly = true)
    public MonzoConnection getActiveConnection(UUID connectionId, UUID userId) {
        return connectionRepository.findActiveByIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Active Monzo connection not found"
                ));
    }

    /**
     * Lists all active connections for a user.
     *
     * <p>Note: The returned connections contain encrypted tokens.
     * Do not expose tokens in API responses.
     *
     * @param userId the user ID
     * @return list of active connections
     */
    @Transactional(readOnly = true)
    public List<MonzoConnection> listActiveConnections(UUID userId) {
        return connectionRepository.findActiveByUserId(userId);
    }

    /**
     * Disconnects (soft-deletes) a Monzo connection.
     *
     * @param connectionId the connection ID
     * @param userId       the user ID (for ownership verification)
     * @throws ApiException if a connection isn't found or not owned by a user
     */
    @Transactional
    public void disconnectConnection(UUID connectionId, UUID userId) {
        MonzoConnection connection = connectionRepository.findActiveByIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Active Monzo connection not found"
                ));

        connection.disconnect();
        connectionRepository.save(connection);
        log.info("Disconnected Monzo connection {} for user {}", connectionId, userId);
    }

    /**
     * Updates tokens for a connection after a token refresh.
     *
     * @param connectionId the connection ID
     * @param userId       the user ID (for ownership verification)
     * @param accessToken  the new plaintext access token
     * @param refreshToken the new plaintext refresh token
     * @param expiresAt    the new expiration time
     * @return the updated connection
     * @throws ApiException if a connection isn't found or not owned by a user
     */
    @Transactional
    public MonzoConnection updateTokens(
            UUID connectionId,
            UUID userId,
            String accessToken,
            String refreshToken,
            Instant expiresAt
    ) {
        MonzoConnection connection = connectionRepository.findActiveByIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Active Monzo connection not found"
                ));

        // Encrypt new tokens
        String accessTokenEncrypted = encryptionService.encrypt(accessToken);
        String refreshTokenEncrypted = encryptionService.encrypt(refreshToken);

        connection.updateTokens(accessTokenEncrypted, refreshTokenEncrypted, expiresAt);
        MonzoConnection saved = connectionRepository.save(connection);

        log.info("Updated tokens for Monzo connection {}", connectionId);
        return saved;
    }

    /**
     * Decrypts and returns the access token for a connection.
     *
     * <p>Includes an eager-refresh guard: if the token expires within
     * {@link #eagerRefreshWindow}, it is refreshed before being returned.
     *
     * @param connectionId the connection ID
     * @param userId       the user ID (for ownership verification)
     * @return the decrypted access token (refreshed if near expiry)
     * @throws ApiException if a connection isn't found, not owned by a user, or was revoked
     */
    @Transactional(readOnly = true)
    public String getDecryptedAccessToken(UUID connectionId, UUID userId) {
        MonzoConnection connection = getActiveConnection(connectionId, userId);
        connection = refreshIfExpiringSoon(connection);
        return encryptionService.decrypt(connection.getAccessTokenEncrypted());
    }

    /**
     * Decrypts and returns the refresh token for a connection.
     *
     * @param connectionId the connection ID
     * @param userId       the user ID (for ownership verification)
     * @return the decrypted refresh token
     * @throws ApiException if a connection isn't found or not owned by a user
     */
    @Transactional(readOnly = true)
    public String getDecryptedRefreshToken(UUID connectionId, UUID userId) {
        MonzoConnection connection = getActiveConnection(connectionId, userId);
        return encryptionService.decrypt(connection.getRefreshTokenEncrypted());
    }

    /**
     * Decrypts and returns both tokens for a connection.
     *
     * <p>Includes an eager-refresh guard: if the access token expires within
     * {@link #eagerRefreshWindow}, both tokens are refreshed before being returned.
     *
     * @param connectionId the connection ID
     * @param userId       the user ID (for ownership verification)
     * @return record containing both decrypted tokens (refreshed if near expiry)
     * @throws ApiException if a connection isn't found, not owned by a user, or was revoked
     */
    @Transactional(readOnly = true)
    public DecryptedTokens getDecryptedTokens(UUID connectionId, UUID userId) {
        MonzoConnection connection = getActiveConnection(connectionId, userId);
        connection = refreshIfExpiringSoon(connection);
        return new DecryptedTokens(
                encryptionService.decrypt(connection.getAccessTokenEncrypted()),
                encryptionService.decrypt(connection.getRefreshTokenEncrypted())
        );
    }

    /**
     * Checks if a user has any active Monzo connection.
     *
     * @param userId the user ID
     * @return true if a user has at least one active connection
     */
    @Transactional(readOnly = true)
    public boolean hasActiveConnection(UUID userId) {
        return connectionRepository.hasActiveConnectionForUser(userId);
    }

    /**
     * Counts active connections for a user.
     *
     * @param userId the user ID
     * @return number of active connections
     */
    @Transactional(readOnly = true)
    public long countActiveConnections(UUID userId) {
        return connectionRepository.countActiveByUserId(userId);
    }

    /**
     * Returns the token health status for the user's Monzo connections.
     *
     * <p>Evaluates the user's active connections:
     * <ul>
     *   <li>{@link TokenStatus#RECONNECT_REQUIRED} — no active connections</li>
     *   <li>{@link TokenStatus#EXPIRING_SOON} — at least one connection is expiring within
     *       the configured eager-refresh window</li>
     *   <li>{@link TokenStatus#ACTIVE} — all tokens are healthy</li>
     * </ul>
     *
     * @param userId the user ID
     * @return the token status
     */
    @Transactional(readOnly = true)
    public TokenStatus getTokenStatus(UUID userId) {
        List<MonzoConnection> connections = connectionRepository.findActiveByUserId(userId);
        if (connections.isEmpty()) {
            return TokenStatus.RECONNECT_REQUIRED;
        }
        boolean anyExpiringSoon = connections.stream()
                .anyMatch(c -> c.isTokenExpiringSoon(eagerRefreshWindow));
        return anyExpiringSoon ? TokenStatus.EXPIRING_SOON : TokenStatus.ACTIVE;
    }

    /**
     * Returns the aggregate backfill status across the user's non-closed accounts.
     *
     * <p>Priority order (worst-case wins):
     * <ul>
     *   <li>{@link BackfillStatus#NOT_STARTED} — no accounts, or no account has started backfill</li>
     *   <li>{@link BackfillStatus#NEEDS_REAUTH} — at least one account needs re-authentication</li>
     *   <li>{@link BackfillStatus#IN_PROGRESS} — at least one account is actively backfilling</li>
     *   <li>{@link BackfillStatus#COMPLETED} — all accounts have completed backfill</li>
     * </ul>
     *
     * @param userId the user ID
     * @return the aggregate backfill status
     */
    @Transactional(readOnly = true)
    public BackfillStatus getBackfillStatus(UUID userId) {
        List<MonzoAccount> accounts = accountRepository.findActiveByUserId(userId);
        if (accounts.isEmpty()) {
            return BackfillStatus.NOT_STARTED;
        }
        boolean anyNeedsReauth = accounts.stream()
                .anyMatch(a -> a.getBackfillStatus() == MonzoAccount.BackfillStatus.NEEDS_REAUTH);
        if (anyNeedsReauth) return BackfillStatus.NEEDS_REAUTH;

        boolean anyInProgress = accounts.stream()
                .anyMatch(a -> a.getBackfillStatus() == MonzoAccount.BackfillStatus.IN_PROGRESS);
        if (anyInProgress) return BackfillStatus.IN_PROGRESS;

        boolean allCompleted = accounts.stream()
                .allMatch(a -> a.getBackfillStatus() == MonzoAccount.BackfillStatus.COMPLETED);
        if (allCompleted) return BackfillStatus.COMPLETED;

        return BackfillStatus.NOT_STARTED;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Proactively refreshes the connection if its token is expiring soon.
     *
     * <p>{@link MonzoTokenRefreshService#refresh} uses {@code REQUIRES_NEW} propagation,
     * so it runs in its own transaction even when called from within this service's
     * read-only transaction.
     *
     * @param connection the connection to check
     * @return the original connection, or the freshly-refreshed one
     * @throws ApiException if the connection was revoked during refresh
     */
    private MonzoConnection refreshIfExpiringSoon(MonzoConnection connection) {
        if (!connection.isTokenExpiringSoon(eagerRefreshWindow)) {
            return connection;
        }
        log.debug("Connection {} token expiring soon - refreshing eagerly", connection.getId());
        MonzoConnection refreshed = tokenRefreshService.refresh(connection.getId());
        if (!refreshed.isActive()) {
            throw new ApiException(
                    ErrorCode.MONZO_CONNECTION_REVOKED,
                    "Monzo connection was revoked. Please reconnect your account."
            );
        }
        return refreshed;
    }

    // =========================================================================
    // Public types
    // =========================================================================

    /**
     * Token health status for a user's Monzo connections.
     *
     * <p>Returned by {@link MonzoConnectionService#getTokenStatus(UUID)} and
     * exposed via the {@code GET /api/v1/monzo/status} endpoint.
     */
    public enum TokenStatus {
        /** All active connections have healthy tokens. */
        ACTIVE,
        /** At least one connection has a token expiring within the eager-refresh window. */
        EXPIRING_SOON,
        /** No active connections — user must re-run the Monzo OAuth flow. */
        RECONNECT_REQUIRED
    }

    /**
     * Aggregate backfill status across a user's non-closed Monzo accounts.
     *
     * <p>Returned by {@link MonzoConnectionService#getBackfillStatus(UUID)} and
     * exposed via the {@code GET /api/v1/monzo/status} endpoint.
     */
    public enum BackfillStatus {
        /** No accounts exist, or no account has started backfill. */
        NOT_STARTED,
        /** At least one account is actively backfilling. */
        IN_PROGRESS,
        /** At least one account hit an SCA expiry — re-OAuth will resume from the saved checkpoint. */
        NEEDS_REAUTH,
        /** All non-closed accounts have been fully backfilled. */
        COMPLETED
    }

    /**
     * Record holding a decrypted token pair.
     */
    public record DecryptedTokens(String accessToken, String refreshToken) {
    }
}
