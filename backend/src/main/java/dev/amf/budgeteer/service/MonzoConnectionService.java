package dev.amf.budgeteer.service;

import dev.amf.budgeteer.domain.monzo.MonzoConnection;
import dev.amf.budgeteer.domain.monzo.MonzoConnectionRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.domain.user.UserRepository;
import dev.amf.budgeteer.exception.ApiException;
import dev.amf.budgeteer.api.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing Monzo bank account connections.
 *
 * <p>Handles the business logic for creating, retrieving, and managing
 * Monzo OAuth connections. All token encryption/decryption is handled here.
 */
@Service
public class MonzoConnectionService {

    private static final Logger log = LoggerFactory.getLogger(MonzoConnectionService.class);

    private final MonzoConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;

    public MonzoConnectionService(
            MonzoConnectionRepository connectionRepository,
            UserRepository userRepository,
            EncryptionService encryptionService
    ) {
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
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
     * @param connectionId the connection ID
     * @param userId       the user ID (for ownership verification)
     * @return the decrypted access token
     * @throws ApiException if a connection isn't found or not owned by a user
     */
    @Transactional(readOnly = true)
    public String getDecryptedAccessToken(UUID connectionId, UUID userId) {
        MonzoConnection connection = getActiveConnection(connectionId, userId);
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
     * @param connectionId the connection ID
     * @param userId       the user ID (for ownership verification)
     * @return record containing both decrypted tokens
     * @throws ApiException if a connection isn't found or not owned by a user
     */
    @Transactional(readOnly = true)
    public DecryptedTokens getDecryptedTokens(UUID connectionId, UUID userId) {
        MonzoConnection connection = getActiveConnection(connectionId, userId);
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
     * Record holding a decrypted token pair.
     */
    public record DecryptedTokens(String accessToken, String refreshToken) {
    }
}
