package dev.amf.budgeteer.api.auth.dto;

/**
 * Response DTO for authentication operations.
 */
public record AuthResponse(
        String message,
        String email
) {
    /**
     * Creates a success response for magic link request.
     */
    public static AuthResponse magicLinkSent(String email) {
        return new AuthResponse("Check your email for a login link", email);
    }

    /**
     * Creates a success response for token refresh.
     */
    public static AuthResponse tokenRefreshed() {
        return new AuthResponse("Token refreshed", null);
    }

    /**
     * Creates a success response for logout.
     */
    public static AuthResponse loggedOut() {
        return new AuthResponse("Logged out", null);
    }
}
