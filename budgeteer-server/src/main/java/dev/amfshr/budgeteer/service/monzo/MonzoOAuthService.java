package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.bank.BankClient;
import dev.amfshr.budgeteer.bank.BankTokens;
import dev.amfshr.budgeteer.domain.oauth.OAuthState;
import dev.amfshr.budgeteer.repository.OAuthStateRepository;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Service for handling Monzo OAuth authentication flow.
 *
 * <p>This service manages:
 * <ul>
 *   <li>OAuth state generation and verification (CSRF protection)</li>
 *   <li>Token exchange and identity lookup (via {@link BankClient})</li>
 * </ul>
 */
@Service
public class MonzoOAuthService {

    private static final Logger log = LoggerFactory.getLogger(MonzoOAuthService.class);

    /**
     * Length of the random state token in bytes (will be Base64 encoded).
     */
    private static final int STATE_BYTES = 32;

    private final OAuthStateRepository stateRepository;
    private final BankClient bankClient;
    private final SecureRandom secureRandom;

    @Autowired
    public MonzoOAuthService(
            OAuthStateRepository stateRepository,
            BankClient bankClient
    ) {
        this.stateRepository = stateRepository;
        this.bankClient = bankClient;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Constructor for testing with custom dependencies.
     */
    public MonzoOAuthService(
            OAuthStateRepository stateRepository,
            BankClient bankClient,
            SecureRandom secureRandom
    ) {
        this.stateRepository = stateRepository;
        this.bankClient = bankClient;
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

        // Build authorization URL via the bank client
        return bankClient.buildAuthorizationUrl(state);
    }

    /**
     * Verifies the OAuth state from the callback and returns the associated user.
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

        if (oauthState.isExpired()) {
            log.warn("OAuth callback with expired state [stateId={}]", oauthState.getId());
            throw new ApiException(ErrorCode.OAUTH_STATE_EXPIRED);
        }

        if (oauthState.isUsed()) {
            log.warn("OAuth callback with already-used state [stateId={}]", oauthState.getId());
            throw new ApiException(ErrorCode.OAUTH_STATE_INVALID);
        }

        oauthState.markUsed();
        stateRepository.save(oauthState);

        User user = oauthState.getUser();
        log.info("Verified OAuth state for user {} [stateId={}]", user.getId(), oauthState.getId());

        return user;
    }

    /**
     * Exchanges an authorization code for access and refresh tokens.
     *
     * <p>Delegates to {@link BankClient} for the actual API call.
     *
     * @param code the authorization code from the OAuth callback
     * @return the token response containing access_token, refresh_token, expires_at
     * @throws ApiException if the token exchange fails
     */
    public BankTokens exchangeCodeForTokens(String code) {
        return bankClient.exchangeCode(code);
    }

    /**
     * Gets the Monzo user ID by calling the bank's identity endpoint.
     *
     * <p>Delegates to {@link BankClient} for the actual API call.
     *
     * @param accessToken the access token
     * @return the provider user ID
     * @throws ApiException if the API call fails or token is revoked
     */
    public String getMonzoUserId(String accessToken) {
        return bankClient.getIdentity(accessToken).providerUserId();
    }

    /**
     * Cleans up expired OAuth states.
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

    private String generateState() {
        byte[] randomBytes = new byte[STATE_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
