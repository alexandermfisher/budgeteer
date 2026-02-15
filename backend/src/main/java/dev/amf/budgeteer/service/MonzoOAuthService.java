package dev.amf.budgeteer.service;

import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.config.MonzoProperties;
import dev.amf.budgeteer.domain.oauth.OAuthState;
import dev.amf.budgeteer.domain.oauth.OAuthStateRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Service for handling Monzo OAuth authentication flow.
 *
 * <p>This service manages:
 * <ul>
 *   <li>OAuth state generation and verification (CSRF protection)</li>
 *   <li>Authorization URL building</li>
 *   <li>Token exchange (authorization code → access/refresh tokens)</li>
 *   <li>Monzo user identification (/ping/whoami)</li>
 * </ul>
 */
@Service
public class MonzoOAuthService {

    private static final Logger log = LoggerFactory.getLogger(MonzoOAuthService.class);

    /**
     * Length of the random state token in bytes (will be Base64 encoded).
     */
    private static final int STATE_BYTES = 32;

    private final MonzoProperties monzoProperties;
    private final OAuthStateRepository stateRepository;
    private final RestClient restClient;
    private final SecureRandom secureRandom;

    @Autowired
    public MonzoOAuthService(
            MonzoProperties monzoProperties,
            OAuthStateRepository stateRepository
    ) {
        this.monzoProperties = monzoProperties;
        this.stateRepository = stateRepository;
        this.restClient = RestClient.create();
        this.secureRandom = new SecureRandom();
    }

    /**
     * Constructor for testing with custom RestClient.
     */
    MonzoOAuthService(
            MonzoProperties monzoProperties,
            OAuthStateRepository stateRepository,
            RestClient restClient,
            SecureRandom secureRandom
    ) {
        this.monzoProperties = monzoProperties;
        this.stateRepository = stateRepository;
        this.restClient = restClient;
        this.secureRandom = secureRandom;
    }

    /**
     * Initiates the OAuth flow by generating a state and building the authorization URL.
     *
     * @param user the authenticated user starting the OAuth flow
     * @return the authorization URL to redirect the user to
     */
    @Transactional
    public String initiateOAuthFlow(User user) {
        // Generate random state for CSRF protection
        String state = generateState();

        // Save state to database
        OAuthState oauthState = new OAuthState(user, state);
        stateRepository.save(oauthState);

        log.info("Initiated OAuth flow for user {} [stateId={}]", user.getId(), oauthState.getId());

        // Build authorization URL
        return buildAuthorizationUrl(state);
    }

    /**
     * Verifies the OAuth state from the callback and returns the associated user.
     *
     * <p>This method:
     * <ol>
     *   <li>Looks up the state in the database</li>
     *   <li>Verifies it's not expired or already used</li>
     *   <li>Marks it as used to prevent replay attacks</li>
     *   <li>Returns the associated user</li>
     * </ol>
     *
     * @param state the state parameter from the OAuth callback
     * @return the user associated with this OAuth flow
     * @throws ApiException if the state is invalid, expired, or already used
     */
    @Transactional
    public User verifyStateAndGetUser(String state) {
        OAuthState oauthState = stateRepository.findByState(state)
                .orElseThrow(() -> {
                    log.warn("OAuth callback with unknown state");
                    return new ApiException(ErrorCode.OAUTH_STATE_INVALID);
                });

        // Check if expired
        if (oauthState.isExpired()) {
            log.warn("OAuth callback with expired state [stateId={}]", oauthState.getId());
            throw new ApiException(ErrorCode.OAUTH_STATE_EXPIRED);
        }

        // Check if already used (replay attack)
        if (oauthState.isUsed()) {
            log.warn("OAuth callback with already-used state [stateId={}]", oauthState.getId());
            throw new ApiException(ErrorCode.OAUTH_STATE_INVALID);
        }

        // Mark as used
        oauthState.markUsed();
        stateRepository.save(oauthState);

        User user = oauthState.getUser();
        log.info("Verified OAuth state for user {} [stateId={}]", user.getId(), oauthState.getId());

        return user;
    }

    /**
     * Exchanges an authorization code for access and refresh tokens.
     *
     * @param code the authorization code from the OAuth callback
     * @return the token response containing access_token, refresh_token, expires_in
     * @throws ApiException if the token exchange fails
     */
    public TokenResponse exchangeCodeForTokens(String code) {
        log.debug("Exchanging authorization code for tokens");

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "authorization_code");
        formData.add("client_id", monzoProperties.clientId());
        formData.add("client_secret", monzoProperties.clientSecret());
        formData.add("redirect_uri", monzoProperties.redirectUri());
        formData.add("code", code);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(monzoProperties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new ApiException(ErrorCode.MONZO_API_ERROR, "Empty response from Monzo token endpoint");
            }

            String accessToken = (String) response.get("access_token");
            String refreshToken = (String) response.get("refresh_token");
            Integer expiresIn = (Integer) response.get("expires_in");

            if (accessToken == null) {
                throw new ApiException(ErrorCode.MONZO_API_ERROR, "No access token in Monzo response");
            }

            log.info("Successfully exchanged code for tokens [expiresIn={}s]", expiresIn);

            return new TokenResponse(
                    accessToken,
                    refreshToken,
                    expiresIn != null ? Instant.now().plusSeconds(expiresIn) : null
            );

        } catch (RestClientException e) {
            log.error("Failed to exchange authorization code for tokens", e);
            throw new ApiException(ErrorCode.MONZO_API_ERROR, "Failed to exchange authorization code: " + e.getMessage(), e);
        }
    }

    /**
     * Gets the Monzo user ID by calling /ping/whoami with the access token.
     *
     * @param accessToken the access token
     * @return the Monzo user ID
     * @throws ApiException if the API call fails
     */
    public String getMonzoUserId(String accessToken) {
        log.debug("Fetching Monzo user ID");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(monzoProperties.apiBaseUrl() + "/ping/whoami")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new ApiException(ErrorCode.MONZO_API_ERROR, "Empty response from Monzo whoami endpoint");
            }

            String userId = (String) response.get("user_id");
            if (userId == null) {
                throw new ApiException(ErrorCode.MONZO_API_ERROR, "No user_id in Monzo whoami response");
            }

            log.debug("Retrieved Monzo user ID");
            return userId;

        } catch (RestClientException e) {
            log.error("Failed to get Monzo user ID", e);
            throw new ApiException(ErrorCode.MONZO_API_ERROR, "Failed to get Monzo user ID: " + e.getMessage(), e);
        }
    }

    /**
     * Cleans up expired OAuth states.
     *
     * <p>Should be called periodically (e.g., via scheduled task) to remove old states.
     *
     * @return the number of deleted states
     */
    @Transactional
    public int cleanupExpiredStates() {
        int deleted = stateRepository.deleteExpiredStates(Instant.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired OAuth states", deleted);
        }
        return deleted;
    }

    // ============ Private Methods ============

    /**
     * Generates a cryptographically secure random state token.
     */
    private String generateState() {
        byte[] randomBytes = new byte[STATE_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Builds the Monzo authorization URL with all required parameters.
     */
    private String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder
                .fromUriString(monzoProperties.authUrl())
                .queryParam("client_id", monzoProperties.clientId())
                .queryParam("redirect_uri", monzoProperties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    // ============ Record Classes ============

    /**
     * Token response from Monzo OAuth token exchange.
     *
     * @param accessToken  the access token
     * @param refreshToken the refresh token (may be null for some flows)
     * @param expiresAt    when the access token expires
     */
    public record TokenResponse(
            String accessToken,
            String refreshToken,
            Instant expiresAt
    ) {
    }
}
