package dev.amf.budgeteer.api.auth;

import dev.amf.budgeteer.api.auth.dto.AuthResponse;
import dev.amf.budgeteer.api.auth.dto.LoginRequest;
import dev.amf.budgeteer.api.auth.dto.RefreshRequest;
import dev.amf.budgeteer.api.auth.dto.UserResponse;
import dev.amf.budgeteer.api.common.ApiResponse;
import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.config.AppProperties;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.exception.ApiException;
import dev.amf.budgeteer.security.JweAuthenticationFilter.JweAuthentication;
import dev.amf.budgeteer.service.CookieService;
import dev.amf.budgeteer.service.AuthService;
import dev.amf.budgeteer.service.SessionService;
import dev.amf.budgeteer.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Controller for application authentication endpoints.
 * Handles magic link login, token refresh, logout, and current user info.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final SessionService sessionService;
    private final CookieService cookieService;
    private final AppProperties appProperties;

    public AuthController(AuthService authService,
                          SessionService sessionService,
                          CookieService cookieService,
                          AppProperties appProperties) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.cookieService = cookieService;
        this.appProperties = appProperties;
    }

    /**
     * Request a magic link email.
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        log.info("Magic link requested [email={}]", maskEmail(request.email()));
        authService.requestMagicLink(request.email());
        log.debug("Magic link sent successfully [email={}]", maskEmail(request.email()));
        return ResponseEntity.ok(ApiResponse.of(AuthResponse.magicLinkSent(request.email())));
    }

    /**
     * Verify magic link and create session.
     * GET /api/auth/verify?token=xxx
     * 
     * <p>Content negotiation:
     * <ul>
     *   <li>Accept: application/json → Returns JSON with tokens (for SPAs/APIs)</li>
     *   <li>Accept: text/html or default → 302 redirect to loginSuccessUrl (for browser email clicks)</li>
     * </ul>
     */
    @GetMapping("/verify")
    public ResponseEntity<ApiResponse<AuthResponse>> verify(
            @RequestParam String token,
            HttpServletRequest request,
            HttpServletResponse response) {

        log.debug("Magic link verification attempted [ipAddress={}]", LogSanitizer.sanitize(cookieService.getClientIpAddress(request)));

        String userAgent = request.getHeader("User-Agent");
        String ipAddress = cookieService.getClientIpAddress(request);

        Optional<SessionService.SessionTokens> tokensOpt = authService.verifyMagicLink(token, userAgent, ipAddress);

        if (tokensOpt.isEmpty()) {
            log.warn("Magic link verification failed - invalid or expired token [ipAddress={}]", LogSanitizer.sanitize(ipAddress));
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Invalid or expired magic link token");
        }

        SessionService.SessionTokens tokens = tokensOpt.get();

        // Set cookies (for both browser and API clients)
        cookieService.setAuthCookies(response, tokens.accessToken(), tokens.refreshToken());

        log.info("User authenticated via magic link [ipAddress={}]", LogSanitizer.sanitize(ipAddress));

        // Content negotiation: JSON for APIs/SPAs, redirect for browsers
        if (wantsJson(request)) {
            // API/SPA client: return JSON with tokens in body
            return ResponseEntity.ok(ApiResponse.of(
                    AuthResponse.loginSuccess(tokens.accessToken(), tokens.refreshToken())
            ));
        }

        // Browser: redirect to frontend
        return ResponseEntity.status(302)
                .header("Location", appProperties.getLoginSuccessUrl())
                .body(ApiResponse.of(AuthResponse.loginSuccess(null, null)));
    }

    /**
     * Refresh access token using refresh token.
     * 
     * <p>POST /api/auth/refresh
     * 
     * <p>The refresh token can be provided in two ways:
     * <ol>
     *   <li>Request body: {"refreshToken": "xxx"} - for API clients</li>
     *   <li>Cookie: refresh_token - for browser clients</li>
     * </ol>
     * 
     * <p>Request body takes precedence over cookie.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @RequestBody(required = false) RefreshRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {

        String ipAddress = cookieService.getClientIpAddress(request);
        log.debug("Token refresh requested [ipAddress={}]", LogSanitizer.sanitize(ipAddress));

        // Try body first, then cookie
        String refreshToken = extractRefreshToken(body, request);

        String userAgent = request.getHeader("User-Agent");

        Optional<SessionService.SessionTokens> tokensOpt = sessionService.refreshSession(refreshToken, userAgent, ipAddress);

        if (tokensOpt.isEmpty()) {
            // Clear invalid cookies
            cookieService.clearAuthCookies(response);
            log.warn("Token refresh failed - invalid or expired refresh token [ipAddress={}]", LogSanitizer.sanitize(ipAddress));
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Invalid or expired refresh token");
        }

        SessionService.SessionTokens tokens = tokensOpt.get();

        // Set new cookies (for browser clients)
        cookieService.setAuthCookies(response, tokens.accessToken(), tokens.refreshToken());

        log.debug("Token refresh successful [ipAddress={}]", LogSanitizer.sanitize(ipAddress));

        // Return tokens in body too (for API clients)
        return ResponseEntity.ok(ApiResponse.of(AuthResponse.tokenRefreshed(tokens.accessToken(), tokens.refreshToken())));
    }

    /**
     * Extract refresh token from request body or cookie.
     * Body takes precedence over cookie.
     *
     * @param body    the optional request body (may be null)
     * @param request the HTTP request
     * @return the refresh token
     * @throws ApiException if no refresh token is found
     */
    private String extractRefreshToken(@Nullable RefreshRequest body, HttpServletRequest request) {
        // Try body first
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            return body.refreshToken();
        }
        // Fall back to cookie
        return cookieService.extractRefreshToken(request)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSING_TOKEN, 
                        "Refresh token not found. Provide in body as {\"refreshToken\": \"...\"} or via cookie."));
    }

    /**
     * Logout and revoke refresh token.
     * POST /api/auth/logout
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<AuthResponse>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        log.debug("Logout requested [ipAddress={}]", LogSanitizer.sanitize(cookieService.getClientIpAddress(request)));

        cookieService.extractRefreshToken(request)
                .ifPresent(token -> {
                    sessionService.revokeSession(token);
                    log.info("User logged out - session revoked [ipAddress={}]", LogSanitizer.sanitize(cookieService.getClientIpAddress(request)));
                });

        // Clear cookies
        cookieService.clearAuthCookies(response);

        return ResponseEntity.ok(ApiResponse.of(AuthResponse.loggedOut()));
    }

    /**
     * Get current authenticated user.
     * GET /api/auth/me
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // instanceof handles null safely (returns false for null)
        if (!(auth instanceof JweAuthentication jweAuth)) {
            log.debug("User info requested but not authenticated");
            throw new ApiException(ErrorCode.NOT_AUTHENTICATED);
        }

        Optional<User> userOpt = authService.getUserById(jweAuth.getUserId());

        if (userOpt.isEmpty()) {
            log.warn("Authenticated user not found in database [userId={}]", jweAuth.getUserId());
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }

        log.debug("User info retrieved [userId={}]", jweAuth.getUserId());
        return ResponseEntity.ok(ApiResponse.of(UserResponse.fromUser(userOpt.get())));
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    /**
     * Determines if the client wants a JSON response based on the Accept header.
     * Used for content negotiation between browser requests (redirect) and API requests (JSON).
     *
     * @param request the HTTP request
     * @return true if the client explicitly requests JSON, false otherwise
     */
    private boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("application/json");
    }

    /**
     * Masks an email address for safe logging.
     * Example: "john.doe@example.com" becomes "j***@example.com"
     *
     * @param email the email to mask
     * @return the masked email
     */
    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***" + email.substring(atIndex);
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}
