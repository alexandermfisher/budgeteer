package dev.amf.budgeteer.controller;

import dev.amf.budgeteer.config.MonzoProperties;
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
 * For testing purposes - tokens are stored in memory.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    
    private final MonzoProperties monzoProperties;
    private final RestClient restClient;
    
    // In production, store this in session or database
    private String storedState;
    // Store tokens in memory for testing (use database in production!)
    private String accessToken;
    private String refreshToken;

    public AuthController(MonzoProperties monzoProperties) {
        this.monzoProperties = monzoProperties;
        this.restClient = RestClient.create();
    }

    /**
     * Step 1: Initiate OAuth flow - redirects to Monzo
     * Visit: https://your-tunnel-url/auth/monzo/connect
     */
    @GetMapping("/monzo/connect")
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
     * Step 2: Handle OAuth callback from Monzo
     * Monzo redirects here after user authorizes
     */
    @GetMapping("/callback")
    public ResponseEntity<?> handleCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        
        log.info("Received callback with code: {}... and state: {}", 
                code.substring(0, Math.min(10, code.length())), state);
        
        // Verify state to prevent CSRF
        if (!state.equals(storedState)) {
            log.error("State mismatch! Expected: {}, Got: {}", storedState, state);
            return ResponseEntity.badRequest().body(Map.of(
                "error", "state_mismatch",
                "message", "State parameter doesn't match. Possible CSRF attack."
            ));
        }
        
        // Exchange code for tokens
        try {
            Map<String, Object> tokens = exchangeCodeForTokens(code);
            
            // Store tokens (in memory for testing)
            this.accessToken = (String) tokens.get("access_token");
            this.refreshToken = (String) tokens.get("refresh_token");
            
            log.info("✅ Successfully obtained tokens!");
            log.info("Access token expires in: {} seconds", tokens.get("expires_in"));
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "OAuth flow completed successfully!",
                "access_token_preview", accessToken.substring(0, Math.min(20, accessToken.length())) + "...",
                "has_refresh_token", refreshToken != null,
                "expires_in", tokens.get("expires_in"),
                "token_type", tokens.get("token_type"),
                "next_steps", Map.of(
                    "whoami", "GET /auth/whoami",
                    "accounts", "GET /auth/accounts",
                    "balance", "GET /auth/balance?account_id=ACC_ID"
                )
            ));
        } catch (Exception e) {
            log.error("Failed to exchange code for tokens", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "token_exchange_failed",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Exchange authorization code for access & refresh tokens
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

    /**
     * Test endpoint: Get current user info (whoami)
     */
    @GetMapping("/whoami")
    public ResponseEntity<?> whoAmI() {
        if (accessToken == null) {
            return ResponseEntity.status(401).body(Map.of(
                "error", "not_authenticated",
                "message", "No access token. Visit /auth/monzo/connect first."
            ));
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                .uri(monzoProperties.apiBaseUrl() + "/ping/whoami")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("API call failed", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "api_call_failed",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Test endpoint: Get accounts
     */
    @GetMapping("/accounts")
    public ResponseEntity<?> getAccounts() {
        if (accessToken == null) {
            return ResponseEntity.status(401).body(Map.of(
                "error", "not_authenticated",
                "message", "No access token. Visit /auth/monzo/connect first."
            ));
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                .uri(monzoProperties.apiBaseUrl() + "/accounts")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("API call failed", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "api_call_failed",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Test endpoint: Get balance (requires account_id)
     */
    @GetMapping("/balance")
    public ResponseEntity<?> getBalance(@RequestParam("account_id") String accountId) {
        if (accessToken == null) {
            return ResponseEntity.status(401).body(Map.of(
                "error", "not_authenticated",
                "message", "No access token. Visit /auth/monzo/connect first."
            ));
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                .uri(monzoProperties.apiBaseUrl() + "/balance?account_id=" + accountId)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("API call failed", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "api_call_failed",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Status endpoint: Check current authentication status
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        return ResponseEntity.ok(Map.of(
            "authenticated", accessToken != null,
            "has_refresh_token", refreshToken != null,
            "connect_url", "/auth/monzo/connect"
        ));
    }
}
