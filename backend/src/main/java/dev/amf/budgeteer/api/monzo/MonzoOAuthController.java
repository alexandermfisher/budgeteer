package dev.amf.budgeteer.api.monzo;

import dev.amf.budgeteer.api.common.ApiResponse;
import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.config.MonzoProperties;
import dev.amf.budgeteer.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Controller handling Monzo OAuth authentication flow.
 * Provides endpoints for initiating OAuth and handling callbacks.
 * 
 * Note: This is a basic implementation. In production, the OAuth state
 * should be stored in session or database, and tokens should be persisted
 * encrypted and associated with the authenticated user.
 */
@RestController
@RequestMapping("/api/monzo/oauth")
public class MonzoOAuthController {

    private static final Logger log = LoggerFactory.getLogger(MonzoOAuthController.class);

    private final MonzoProperties monzoProperties;
    private final RestClient restClient;

    // TODO: In production, store state in session/database per user
    private String storedState;
    // TODO: In production, encrypt and store tokens in database associated with user
    private String accessToken;
    private String refreshToken;

    public MonzoOAuthController(MonzoProperties monzoProperties) {
        this.monzoProperties = monzoProperties;
        this.restClient = RestClient.create();
    }

    /**
     * Initiate OAuth flow - redirects to Monzo authorization page.
     * GET /api/monzo/oauth/connect
     */
    @GetMapping("/connect")
    public RedirectView initiateOAuth() {
        // Generate random state for CSRF protection
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        storedState = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String authorizationUrl = UriComponentsBuilder
                .fromUriString(monzoProperties.authUrl())
                .queryParam("client_id", monzoProperties.clientId())
                .queryParam("redirect_uri", monzoProperties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", storedState)
                .build()
                .toUriString();

        log.info("Redirecting to Monzo OAuth: {}", authorizationUrl);
        return new RedirectView(authorizationUrl);
    }

    /**
     * Handle OAuth callback from Monzo.
     * GET /api/monzo/oauth/callback
     */
    @GetMapping("/callback")
    public ResponseEntity<ApiResponse<OAuthSuccessResponse>> handleCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {

        log.info("Received callback with code: {}... and state: {}",
                code.substring(0, Math.min(10, code.length())), state);

        // Verify state to prevent CSRF
        if (!state.equals(storedState)) {
            log.error("State mismatch! Expected: {}, Got: {}", storedState, state);
            throw new ApiException(ErrorCode.STATE_MISMATCH, "State parameter doesn't match. Possible CSRF attack.");
        }

        // Exchange code for tokens
        try {
            Map<String, Object> tokens = exchangeCodeForTokens(code);

            // Store tokens (in memory for now - TODO: encrypt and persist to database)
            this.accessToken = (String) tokens.get("access_token");
            this.refreshToken = (String) tokens.get("refresh_token");

            log.info("✅ Successfully obtained Monzo tokens!");
            log.info("Access token expires in: {} seconds", tokens.get("expires_in"));

            OAuthSuccessResponse response = new OAuthSuccessResponse(
                    "OAuth flow completed successfully",
                    accessToken.substring(0, Math.min(20, accessToken.length())) + "...",
                    refreshToken != null,
                    (Integer) tokens.get("expires_in"),
                    (String) tokens.get("token_type")
            );

            return ResponseEntity.ok(ApiResponse.of(response));

        } catch (Exception e) {
            log.error("Failed to exchange code for tokens", e);
            throw new ApiException(ErrorCode.MONZO_API_ERROR, "Failed to exchange authorization code: " + e.getMessage(), e);
        }
    }

    /**
     * Check current Monzo connection status.
     * GET /api/monzo/oauth/status
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<OAuthStatusResponse>> getStatus() {
        OAuthStatusResponse status = new OAuthStatusResponse(
                accessToken != null,
                refreshToken != null
        );
        return ResponseEntity.ok(ApiResponse.of(status));
    }

    /**
     * Exchange authorization code for access & refresh tokens.
     */
    private Map<String, Object> exchangeCodeForTokens(String code) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "authorization_code");
        formData.add("client_id", monzoProperties.clientId());
        formData.add("client_secret", monzoProperties.clientSecret());
        formData.add("redirect_uri", monzoProperties.redirectUri());
        formData.add("code", code);

        log.debug("Exchanging code for tokens at: {}", monzoProperties.tokenUrl());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(monzoProperties.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .body(Map.class);

        return response;
    }

    // ============ Response DTOs ============

    /**
     * Response DTO for successful OAuth completion.
     */
    public record OAuthSuccessResponse(
            String message,
            String accessTokenPreview,
            boolean hasRefreshToken,
            Integer expiresIn,
            String tokenType
    ) {}

    /**
     * Response DTO for OAuth connection status.
     */
    public record OAuthStatusResponse(
            boolean connected,
            boolean hasRefreshToken
    ) {}
}
