package dev.amf.budgeteer.api.dev;

import dev.amf.budgeteer.api.common.ApiResponse;
import dev.amf.budgeteer.domain.session.AppRefreshTokenRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.domain.user.UserRepository;
import dev.amf.budgeteer.service.CookieService;
import dev.amf.budgeteer.service.SessionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Optional;

/**
 * Development-only authentication endpoints.
 * These endpoints bypass the magic link flow for easier testing.
 * 
 * <p><strong>⚠️ WARNING: This controller only exists in the 'dev' profile!</strong>
 * It will NOT be available in production.
 */
@RestController
@RequestMapping("/api/test/auth")
@Profile("dev")
public class DevAuthController {

    private static final Logger log = LoggerFactory.getLogger(DevAuthController.class);

    private final UserRepository userRepository;
    private final AppRefreshTokenRepository refreshTokenRepository;
    private final SessionService sessionService;
    private final CookieService cookieService;

    public DevAuthController(UserRepository userRepository,
                             AppRefreshTokenRepository refreshTokenRepository,
                             SessionService sessionService,
                             CookieService cookieService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.sessionService = sessionService;
        this.cookieService = cookieService;
    }

    /**
     * Quick login for development - creates a user if needed and returns tokens directly.
     * No magic link required!
     * 
     * <p>POST /api/test/auth/quick-login
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

        // Find or create user
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseGet(() -> {
                    log.info("Creating new dev user with email: {}", normalizedEmail);
                    User newUser = new User(normalizedEmail);
                    newUser.setEmailVerified(true); // Auto-verify in dev
                    return userRepository.save(newUser);
                });

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
                "Copy the accessToken to use in Authorization header: Bearer <token>"
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
     * <p>POST /api/test/auth/revoke-all
     */
    @PostMapping("/revoke-all")
    @Transactional
    public ResponseEntity<ApiResponse<RevokeResponse>> revokeAllSessions(HttpServletResponse response) {
        log.warn("╔════════════════════════════════════════════════════════════╗");
        log.warn("║  ☢️  REVOKING ALL SESSIONS - Everyone will be logged out!  ║");
        log.warn("╚════════════════════════════════════════════════════════════╝");

        int revoked = refreshTokenRepository.revokeAllTokens(Instant.now());
        
        // Clear caller's cookies too
        cookieService.clearAuthCookies(response);

        log.info("Revoked {} session(s) across all users", revoked);

        return ResponseEntity.ok(ApiResponse.of(new RevokeResponse(
                revoked,
                "All sessions revoked. Everyone has been logged out."
        )));
    }

    /**
     * Revoke all sessions for a specific user by email.
     * 
     * <p>POST /api/test/auth/revoke-user?email=test@example.com
     */
    @PostMapping("/revoke-user")
    @Transactional
    public ResponseEntity<ApiResponse<RevokeResponse>> revokeUserSessions(
            @RequestParam String email,
            HttpServletResponse response) {

        String normalizedEmail = email.toLowerCase().trim();

        log.warn("╔════════════════════════════════════════════════════════════╗");
        log.warn("║  🔒 REVOKING SESSIONS FOR USER                             ║");
        log.warn("║  Email: {}", String.format("%-44s║", normalizedEmail));
        log.warn("╚════════════════════════════════════════════════════════════╝");

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);
        
        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.of(new RevokeResponse(
                    0,
                    "User not found: " + normalizedEmail
            )));
        }

        User user = userOpt.get();
        int revoked = sessionService.revokeAllSessions(user);
        
        // Clear caller's cookies too (in case they're that user)
        cookieService.clearAuthCookies(response);

        log.info("Revoked {} session(s) for user {}", revoked, normalizedEmail);

        return ResponseEntity.ok(ApiResponse.of(new RevokeResponse(
                revoked,
                "Revoked " + revoked + " session(s) for " + normalizedEmail
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
