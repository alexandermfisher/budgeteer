package dev.amf.budgeteer.api.auth.dto;

/**
 * Response DTO for authentication operations.
 * 
 * <p>For browser clients: tokens are set as HttpOnly cookies.
 * <p>For API clients: tokens are included in the response body.
 */
public record AuthResponse(
        String message,
        String email,
        String accessToken,
        String refreshToken
) {
    /**
     * Creates a success response for magic link request.
     */
    public static AuthResponse magicLinkSent(String email) {
        return new AuthResponse("Check your email for a login link", email, null, null);
    }

    /**
     * Creates a success response for token refresh (cookie-based, no tokens in body).
     */
    public static AuthResponse tokenRefreshed() {
        return new AuthResponse("Token refreshed", null, null, null);
    }

    /**
     * Creates a success response for token refresh with tokens in body (for API clients).
     */
    public static AuthResponse tokenRefreshed(String accessToken, String refreshToken) {
        return new AuthResponse("Token refreshed", null, accessToken, refreshToken);
    }

    /**
     * Creates a success response for logout.
     */
    public static AuthResponse loggedOut() {
        return new AuthResponse("Logged out", null, null, null);
    }
}
