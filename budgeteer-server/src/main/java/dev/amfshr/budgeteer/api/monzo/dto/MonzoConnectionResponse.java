package dev.amfshr.budgeteer.api.monzo.dto;

import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a Monzo connection.
 *
 * <p>This DTO is safe to expose in API responses - it contains NO sensitive data
 * like access tokens or refresh tokens.
 *
 * @param id            the connection ID
 * @param monzoUserId   the Monzo user ID (from /ping/whoami)
 * @param isActive      whether the connection is active (not disconnected)
 * @param isTokenExpired whether the access token has expired and needs refresh
 * @param connectedAt   when the connection was established
 * @param expiresAt     when the access token expires
 */
public record MonzoConnectionResponse(
        UUID id,
        String monzoUserId,
        boolean isActive,
        boolean isTokenExpired,
        Instant connectedAt,
        Instant expiresAt
) {
    /**
     * Creates a response DTO from a MonzoConnection entity.
     *
     * @param connection the entity to convert
     * @return the response DTO
     */
    public static MonzoConnectionResponse from(MonzoConnection connection) {
        return new MonzoConnectionResponse(
                connection.getId(),
                connection.getMonzoUserId(),
                connection.isActive(),
                connection.isTokenExpired(),
                connection.getConnectedAt(),
                connection.getTokenExpiresAt()
        );
    }
}
