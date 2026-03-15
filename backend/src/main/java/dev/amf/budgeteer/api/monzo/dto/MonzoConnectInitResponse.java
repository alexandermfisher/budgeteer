package dev.amf.budgeteer.api.monzo.dto;

/**
 * Response DTO for OAuth connection initiation.
 *
 * <p>Returned when a user starts the Monzo OAuth flow. Contains the authorization URL
 * that the client should redirect to (or open in a new window).
 *
 * @param message          a human-readable message
 * @param authorizationUrl the Monzo authorization URL to redirect to
 */
public record MonzoConnectInitResponse(
        String message,
        String authorizationUrl
) {
}
