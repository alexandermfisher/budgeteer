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
        authService.requestMagicLink(request.email());
        return ResponseEntity.ok(ApiResponse.of(AuthResponse.magicLinkSent(request.email())));
    }

    /**
     * Verify magic link and create session.
     * GET /api/auth/verify?token=xxx
     */
    @GetMapping("/verify")
    public ResponseEntity<ApiResponse<AuthResponse>> verify(
            @RequestParam String token,
            HttpServletRequest request,
            HttpServletResponse response) {

        String userAgent = request.getHeader("User-Agent");
        String ipAddress = cookieService.getClientIpAddress(request);

        Optional<SessionService.SessionTokens> tokensOpt = authService.verifyMagicLink(token, userAgent, ipAddress);

        if (tokensOpt.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Invalid or expired magic link token");
        }

        SessionService.SessionTokens tokens = tokensOpt.get();

        // Set cookies
        cookieService.setAuthCookies(response, tokens.accessToken(), tokens.refreshToken());

        // Redirect to login success URL
        response.setHeader("Location", appProperties.getLoginSuccessUrl());
        return ResponseEntity.status(302)
                .body(ApiResponse.of(new AuthResponse("Login successful", null, null, null)));
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

        // Try body first, then cookie
        String refreshToken = extractRefreshToken(body, request);

        String userAgent = request.getHeader("User-Agent");
        String ipAddress = cookieService.getClientIpAddress(request);

        Optional<SessionService.SessionTokens> tokensOpt = sessionService.refreshSession(refreshToken, userAgent, ipAddress);

        if (tokensOpt.isEmpty()) {
            // Clear invalid cookies
            cookieService.clearAuthCookies(response);
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Invalid or expired refresh token");
        }

        SessionService.SessionTokens tokens = tokensOpt.get();

        // Set new cookies (for browser clients)
        cookieService.setAuthCookies(response, tokens.accessToken(), tokens.refreshToken());

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

        cookieService.extractRefreshToken(request)
                .ifPresent(sessionService::revokeSession);

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
            throw new ApiException(ErrorCode.NOT_AUTHENTICATED);
        }

        Optional<User> userOpt = authService.getUserById(jweAuth.getUserId());

        if (userOpt.isEmpty()) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }

        return ResponseEntity.ok(ApiResponse.of(UserResponse.fromUser(userOpt.get())));
    }
}
