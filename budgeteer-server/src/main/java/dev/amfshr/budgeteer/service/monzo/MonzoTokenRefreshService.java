package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.bank.BankClient;
import dev.amfshr.budgeteer.bank.BankConnectionRevokedException;
import dev.amfshr.budgeteer.bank.BankTokens;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.service.common.EncryptionService;
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
 * <p>Operates without a user context — works directly with connection entities.
 * For user-scoped token access, see {@link MonzoConnectionService}.
 *
 * <h2>Entry points</h2>
 * <ul>
 *   <li>{@link #refresh(UUID)} — refresh a single connection. Uses
 *       {@code REQUIRES_NEW} propagation so each refresh is isolated.</li>
 *   <li>{@link #findExpiringConnections(Instant)} — find connections whose tokens
 *       expire before a given threshold.</li>
 * </ul>
 */
@Service
public class MonzoTokenRefreshService {

    private static final Logger log = LoggerFactory.getLogger(MonzoTokenRefreshService.class);

    private static final long FALLBACK_EXPIRES_SECONDS = 21_600L;

    private final MonzoConnectionRepository connectionRepository;
    private final BankClient bankClient;
    private final EncryptionService encryptionService;

    public MonzoTokenRefreshService(
            MonzoConnectionRepository connectionRepository,
            BankClient bankClient,
            EncryptionService encryptionService
    ) {
        this.connectionRepository = connectionRepository;
        this.bankClient = bankClient;
        this.encryptionService = encryptionService;
    }

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
            BankTokens tokens = bankClient.refreshTokens(plainRefreshToken);

            String newAccessTokenEncrypted = encryptionService.encrypt(tokens.accessToken());
            String newRefreshTokenEncrypted = encryptionService.encrypt(
                    tokens.refreshToken() != null ? tokens.refreshToken() : plainRefreshToken
            );
            Instant newExpiresAt = tokens.expiresAt() != null
                    ? tokens.expiresAt()
                    : Instant.now().plus(FALLBACK_EXPIRES_SECONDS, ChronoUnit.SECONDS);

            connection.updateTokens(newAccessTokenEncrypted, newRefreshTokenEncrypted, newExpiresAt);
            MonzoConnection saved = connectionRepository.save(connection);

            log.info("Refreshed tokens for connection {} [expiresAt={}]", connectionId, newExpiresAt);
            return saved;

        } catch (BankConnectionRevokedException e) {
            log.warn("Connection {} revoked by Monzo during refresh - disconnecting", connectionId);
            connection.disconnect();
            connectionRepository.save(connection);
            return connection;
        }
    }

    @Transactional(readOnly = true)
    public List<MonzoConnection> findExpiringConnections(Instant threshold) {
        return connectionRepository.findActiveExpiringBefore(threshold);
    }
}
