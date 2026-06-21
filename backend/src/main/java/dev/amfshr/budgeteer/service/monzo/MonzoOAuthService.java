package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.config.MonzoProperties;
import dev.amfshr.budgeteer.domain.oauth.OAuthState;
import dev.amfshr.budgeteer.repository.OAuthStateRepository;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.client.monzo.MonzoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Service for handling Monzo OAuth authentication flow.
 *
 * <p>This service manages:
 * <ul>
 *   <li>OAuth state generation and verification (CSRF protection)</li>
 *   <li>Authorization URL building</li>
 *   <li>Token exchange (via MonzoClient)</li>
 *   <li>Monzo user identification (via MonzoClient)</li>
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
    private final MonzoClient monzoClient;
    private final SecureRandom secureRandom;

    @Autowired
    public MonzoOAuthService(
            MonzoProperties monzoProperties,
            OAuthStateRepository stateRepository,
            MonzoClient monzoClient
    ) {
        this.monzoProperties = monzoProperties;
        this.stateRepository = stateRepository;
        this.monzoClient = monzoClient;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Constructor for testing with custom dependencies.
     */
    public MonzoOAuthService(
            MonzoProperties monzoProperties,
            OAuthStateRepository stateRepository,
            MonzoClient monzoClient,
            SecureRandom secureRandom
    ) {
        this.monzoProperties = monzoProperties;
        this.stateRepository = stateRepository;
        this.monzoClient = monzoClient;
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
     * <p>Delegates to MonzoClient for the actual API call.
     *
     * @param code the authorization code from the OAuth callback
     * @return the token response containing access_token, refresh_token, expires_in
     * @throws ApiException if the token exchange fails
     */
    public TokenResponse exchangeCodeForTokens(String code) {
        dev.amfshr.budgeteer.client.monzo.dto.TokenResponse clientResponse = monzoClient.exchangeCode(code);

        return new TokenResponse(
                clientResponse.accessToken(),
                clientResponse.refreshToken(),
                clientResponse.expiresAt()
        );
    }

    /**
     * Gets the Monzo user ID by calling /ping/whoami with the access token.
     *
     * <p>Delegates to MonzoClient for the actual API call.
     *
     * @param accessToken the access token
     * @return the Monzo user ID
     * @throws ApiException if the API call fails or token is revoked
     */
    public String getMonzoUserId(String accessToken) {
        return monzoClient.whoAmI(accessToken);
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
