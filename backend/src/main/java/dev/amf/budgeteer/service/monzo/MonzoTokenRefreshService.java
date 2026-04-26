package dev.amf.budgeteer.service.monzo;

import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.client.monzo.MonzoClient;
import dev.amf.budgeteer.domain.monzo.MonzoConnection;
import dev.amf.budgeteer.exception.ApiException;
import dev.amf.budgeteer.repository.MonzoConnectionRepository;
import dev.amf.budgeteer.service.common.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * System-scoped service for refreshing Monzo OAuth tokens.
 *
 * <p>Designed for use by background jobs and the eager-refresh guard in
 * {@link MonzoConnectionService}. Operates without a user context — it works
 * directly with connection entities rather than using user-scoped service methods.
 * For user-scoped token access, see {@link MonzoConnectionService}.
 *
 * <h2>Entry points</h2>
 * <ul>
 *   <li>{@link #refresh(UUID)} — refresh a single connection. Uses
 *       {@code REQUIRES_NEW} propagation so each refresh is isolated in its own
 *       transaction. Safe to call from within an existing read-only transaction
 *       (the eager-refresh guard).</li>
 *   <li>{@link #findExpiringConnections(Instant)} — find connections whose tokens
 *       expire before a given threshold. Called by the scheduled job.</li>
 * </ul>
 *
 * <h2>Failure handling</h2>
 * <ul>
 *   <li>401 from Monzo (token revoked): connection is soft-deleted, no exception thrown.</li>
 *   <li>Other Monzo errors: exception re-thrown; the caller (job) logs and skips.</li>
 * </ul>
 */
@Service
public class MonzoTokenRefreshService {

    private static final Logger log = LoggerFactory.getLogger(MonzoTokenRefreshService.class);

    /**
     * Fallback token lifetime when Monzo does not return {@code expires_in}.
     * Monzo's typical access token lifetime is 6 hours.
     */
    private static final long FALLBACK_EXPIRES_SECONDS = 21_600L;

    private final MonzoConnectionRepository connectionRepository;
    private final MonzoClient monzoClient;
    private final EncryptionService encryptionService;

    public MonzoTokenRefreshService(
            MonzoConnectionRepository connectionRepository,
            MonzoClient monzoClient,
            EncryptionService encryptionService
    ) {
        this.connectionRepository = connectionRepository;
        this.monzoClient = monzoClient;
        this.encryptionService = encryptionService;
    }

    /**
     * Refreshes the Monzo OAuth tokens for a single connection.
     *
     * <p>Uses {@code REQUIRES_NEW} propagation so:
     * <ul>
     *   <li>Each connection's refresh is isolated — one failure does not roll back others.</li>
     *   <li>The method works correctly when called from within an existing read-only
     *       transaction (the eager-refresh guard in {@link MonzoConnectionService}).</li>
     * </ul>
     *
     * <p>Outcomes:
     * <ul>
     *   <li><strong>Success</strong>: tokens updated with new encrypted values and expiry.</li>
     *   <li><strong>401 (revoked)</strong>: connection soft-deleted, returned as inactive — no exception.</li>
     *   <li><strong>Other error</strong>: exception re-thrown after logging.</li>
     *   <li><strong>Already inactive</strong>: returned immediately without calling Monzo.</li>
     * </ul>
     *
     * @param connectionId the ID of the connection to refresh
     * @return the updated connection (fresh tokens, or soft-deleted if revoked)
     * @throws ApiException if the connection is not found, or on non-401 Monzo errors
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MonzoConnection refresh(UUID connectionId) {
        MonzoConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Connection not found for refresh: " + connectionId
                ));

        if (!connection.isActive()) {
            log.debug("Skipping refresh for already-inactive connection {}", connectionId);
            return connection;
        }

        log.debug("Refreshing tokens for connection {}", connectionId);

        String plainRefreshToken = encryptionService.decrypt(connection.getRefreshTokenEncrypted());

        try {
            MonzoClient.TokenResponse response = monzoClient.refreshTokens(plainRefreshToken);

            String newAccessTokenEncrypted = encryptionService.encrypt(response.accessToken());
            // MonzoClient already falls back to the old refresh token if Monzo does not rotate it
            String newRefreshTokenEncrypted = encryptionService.encrypt(
                    response.refreshToken() != null ? response.refreshToken() : plainRefreshToken
            );
            Instant newExpiresAt = response.expiresAt() != null
                    ? response.expiresAt()
                    : Instant.now().plus(FALLBACK_EXPIRES_SECONDS, ChronoUnit.SECONDS);

            connection.updateTokens(newAccessTokenEncrypted, newRefreshTokenEncrypted, newExpiresAt);
            MonzoConnection saved = connectionRepository.save(connection);

            log.info("Refreshed tokens for connection {} [expiresAt={}]", connectionId, newExpiresAt);
            return saved;

        } catch (ApiException e) {
            if (e.getErrorCode() == ErrorCode.MONZO_CONNECTION_REVOKED) {
                log.warn("Connection {} revoked by Monzo during refresh - disconnecting", connectionId);
                connection.disconnect();
                connectionRepository.save(connection);
                return connection;
            }
            log.error("Failed to refresh tokens for connection {}: {}", connectionId, e.getMessage());
            throw e;
        }
    }

    /**
     * Returns all active connections with tokens expiring before the given threshold.
     *
     * <p>Pass {@code Instant.now().plus(window)} to find tokens expiring within
     * the next {@code window} duration.
     *
     * @param threshold connections with {@code tokenExpiresAt < threshold} are returned
     * @return list of connections requiring refresh
     */
    @Transactional(readOnly = true)
    public List<MonzoConnection> findExpiringConnections(Instant threshold) {
        return connectionRepository.findActiveExpiringBefore(threshold);
    }
}
