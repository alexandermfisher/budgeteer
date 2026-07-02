package dev.amfshr.budgeteer.api.dev;

import dev.amfshr.budgeteer.api.common.ApiResponse;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.service.auth.DevAuthService;
import dev.amfshr.budgeteer.service.auth.SessionService;
import dev.amfshr.budgeteer.service.common.CookieService;
import dev.amfshr.budgeteer.util.LogSanitizer;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Development-only authentication endpoints.
 * These endpoints bypass the magic link flow for easier testing.
 * 
 * <p><strong>⚠️ WARNING: This controller only exists in the 'dev' profile!</strong>
 * It will NOT be available in production.
 */
@Validated
@RestController
@RequestMapping("/api/dev/auth")
@Profile("dev")
public class DevAuthController {

    private static final Logger log = LoggerFactory.getLogger(DevAuthController.class);

    private final DevAuthService devAuthService;
    private final SessionService sessionService;
    private final CookieService cookieService;

    public DevAuthController(DevAuthService devAuthService,
                             SessionService sessionService,
                             CookieService cookieService) {
        this.devAuthService = devAuthService;
        this.sessionService = sessionService;
        this.cookieService = cookieService;
    }

    /**
     * Quick login for development - creates a user if needed and returns tokens directly.
     * No magic link required!
     * 
     * <p>POST /api/dev/auth/quick-login
     * 
     * <p>Request body:
     * <pre>{"email": "test@example.com"}</pre>
     * 
     * <p>Response includes tokens in both:
     * <ul>
     *   <li>Response body (for Postman to grab)</li>
     *   <li>HttpOnly cookies (like normal auth)</li>
     * </ul>
     */
    @PostMapping("/quick-login")
    public ResponseEntity<ApiResponse<DevLoginResponse>> quickLogin(
            @Valid @RequestBody DevLoginRequest request,
            HttpServletResponse response) {

        String normalizedEmail = request.email().toLowerCase().trim();

        log.warn("╔════════════════════════════════════════════════════════════╗");
        log.warn("║  🔓 DEV QUICK LOGIN - Bypassing magic link flow!           ║");
        log.warn("║  Email: {}", String.format("%-44s║", normalizedEmail));
        log.warn("╚════════════════════════════════════════════════════════════╝");

        // Find or create user via service
        User user = devAuthService.findOrCreateDevUser(normalizedEmail);

        // Create session
        SessionService.SessionTokens tokens = sessionService.createSession(user, "Postman/Dev", "127.0.0.1");

        // Set cookies (like normal auth)
        cookieService.setAuthCookies(response, tokens.accessToken(), tokens.refreshToken());

        // Also return tokens in body for Postman
        DevLoginResponse devResponse = new DevLoginResponse(
                user.getId().toString(),
                user.getEmail(),
                tokens.accessToken(),
                tokens.refreshToken(),
                "Use accessToken with: Authorization: Bearer <token> OR cookies (auto-set)"
        );

        return ResponseEntity.ok(ApiResponse.of(devResponse));
    }

    /**
     * Request body for quick login.
     */
    public record DevLoginRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Invalid email format")
            String email
    ) {}

    /**
     * Response for dev quick login - includes tokens in body for easy copying.
     */
    public record DevLoginResponse(
            String userId,
            String email,
            String accessToken,
            String refreshToken,
            String hint
    ) {}

    // =========================================================================
    // REVOKE ENDPOINTS
    // =========================================================================

    /**
     * Revoke all sessions for ALL users.
     * Nuclear option - logs everyone out!
     * 
     * <p>POST /api/dev/auth/revoke-all
     */
    @PostMapping("/revoke-all")
    public ResponseEntity<ApiResponse<RevokeResponse>> revokeAllSessions(HttpServletResponse response) {
        log.warn("╔════════════════════════════════════════════════════════════╗");
        log.warn("║  ☢️  REVOKING ALL SESSIONS - Everyone will be logged out!  ║");
        log.warn("╚════════════════════════════════════════════════════════════╝");

        int revoked = devAuthService.revokeAllSessions();
        
        // Clear caller's cookies too
        cookieService.clearAuthCookies(response);

        return ResponseEntity.ok(ApiResponse.of(new RevokeResponse(
                revoked,
                "All sessions revoked. Everyone has been logged out."
        )));
    }

    /**
     * Revoke all sessions for a specific user by email.
     * 
     * <p>POST /api/dev/auth/revoke-user?email=test@example.com
     */
    @PostMapping("/revoke-user")
    public ResponseEntity<ApiResponse<RevokeResponse>> revokeUserSessions(
            @RequestParam @NotBlank(message = "Email is required") @Email(message = "Invalid email format") @Size(max = 255, message = "Email too long") String email,
            HttpServletResponse response) {

        log.warn("╔════════════════════════════════════════════════════════════╗");
        log.warn("║  🔒 REVOKING SESSIONS FOR USER                             ║");
        log.warn("║  Email: {}", String.format("%-44s║", LogSanitizer.maskEmail(email)));
        log.warn("╚════════════════════════════════════════════════════════════╝");

        int revoked = devAuthService.revokeUserSessions(email);
        
        if (revoked == -1) {
            return ResponseEntity.ok(ApiResponse.of(new RevokeResponse(
                    0,
                    "User not found: " + email
            )));
        }
        
        // Clear caller's cookies too (in case they're that user)
        cookieService.clearAuthCookies(response);

        return ResponseEntity.ok(ApiResponse.of(new RevokeResponse(
                revoked,
                "Revoked " + revoked + " session(s) for " + email
        )));
    }

    /**
     * Response for revoke operations.
     */
    public record RevokeResponse(
            int sessionsRevoked,
            String message
    ) {}
}
