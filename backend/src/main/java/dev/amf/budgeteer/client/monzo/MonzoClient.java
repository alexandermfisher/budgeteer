package dev.amf.budgeteer.client.monzo;

import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.config.MonzoProperties;
import dev.amf.budgeteer.exception.ApiException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Map;

/**
 * Client for Monzo API operations.
 *
 * <p>This client handles all HTTP communication with the Monzo API, providing:
 * <ul>
 *   <li>OAuth token exchange and refresh</li>
 *   <li>User identity verification (whoami)</li>
 *   <li>Centralized error handling (401 → MONZO_CONNECTION_REVOKED)</li>
 * </ul>
 *
 * <p>Future enhancements (see MonzoClient Resilience ticket):
 * <ul>
 *   <li>Connection pooling</li>
 *   <li>Timeouts</li>
 *   <li>Retry logic</li>
 *   <li>Circuit breaker</li>
 * </ul>
 */
@Component
public class MonzoClient {

    private static final Logger log = LoggerFactory.getLogger(MonzoClient.class);

    private final MonzoProperties monzoProperties;
    private final RestClient restClient;

    public MonzoClient(MonzoProperties monzoProperties, RestClient monzoRestClient) {
        this.monzoProperties = monzoProperties;
        this.restClient = monzoRestClient;
    }

    /**
     * Exchanges an authorization code for access and refresh tokens.
     *
     * @param code the authorization code from OAuth callback
     * @return the token response
     * @throws ApiException if the exchange fails
     */
    public TokenResponse exchangeCode(String code) {
        log.debug("Exchanging authorization code for tokens");

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "authorization_code");
        formData.add("client_id", monzoProperties.clientId());
        formData.add("client_secret", monzoProperties.clientSecret());
        formData.add("redirect_uri", monzoProperties.redirectUri());
        formData.add("code", code);

        Map<String, Object> response = executeTokenRequest(formData);

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
    }

    /**
     * Refreshes an access token using a refresh token.
     *
     * @param refreshToken the refresh token
     * @return the new token response
     * @throws ApiException if the refresh fails (including if token was revoked)
     */
    public TokenResponse refreshTokens(String refreshToken) {
        log.debug("Refreshing Monzo tokens");

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "refresh_token");
        formData.add("client_id", monzoProperties.clientId());
        formData.add("client_secret", monzoProperties.clientSecret());
        formData.add("refresh_token", refreshToken);

        Map<String, Object> response = executeTokenRequest(formData);

        String accessToken = (String) response.get("access_token");
        String newRefreshToken = (String) response.get("refresh_token");
        Integer expiresIn = (Integer) response.get("expires_in");

        if (accessToken == null) {
            throw new ApiException(ErrorCode.MONZO_API_ERROR, "No access token in refresh response");
        }

        log.info("Successfully refreshed tokens [expiresIn={}s]", expiresIn);

        return new TokenResponse(
                accessToken,
                newRefreshToken != null ? newRefreshToken : refreshToken,
                expiresIn != null ? Instant.now().plusSeconds(expiresIn) : null
        );
    }

    /**
     * Gets the Monzo user ID by calling /ping/whoami.
     *
     * @param accessToken the access token
     * @return the Monzo user ID
     * @throws ApiException if the call fails or token is revoked
     */
    public String whoAmI(String accessToken) {
        log.debug("Fetching Monzo user ID");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("/ping/whoami")
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

        } catch (RestClientResponseException e) {
            handleMonzoError(e, "whoami");
            throw new ApiException(ErrorCode.MONZO_API_ERROR, "Failed to get Monzo user ID: " + e.getMessage(), e);
        }
    }

    // ============ Private Methods ============

    /**
     * Executes a token request (exchange or refresh).
     */
    private Map<String, Object> executeTokenRequest(MultiValueMap<String, String> formData) {
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

            return response;

        } catch (RestClientResponseException e) {
            handleMonzoError(e, "token");
            throw new ApiException(ErrorCode.MONZO_API_ERROR, "Failed to exchange/refresh tokens: " + e.getMessage(), e);
        }
    }

    /**
     * Handles Monzo API errors, specifically detecting 401 (revoked tokens).
     *
     * @param e        the exception from RestClient
     * @param endpoint the endpoint that was called (for logging)
     * @throws ApiException with MONZO_CONNECTION_REVOKED for 401 errors
     */
    private void handleMonzoError(RestClientResponseException e, String endpoint) {
        HttpStatusCode status = e.getStatusCode();

        if (status.value() == 401) {
            log.warn("Monzo API returned 401 for {} - token revoked or invalid", endpoint);
            throw new ApiException(ErrorCode.MONZO_CONNECTION_REVOKED,
                    "Your Monzo connection has been revoked. Please reconnect your account.");
        }

        if (status.value() == 403) {
            log.warn("Monzo API returned 403 for {} - insufficient permissions", endpoint);
            throw new ApiException(ErrorCode.MONZO_API_ERROR,
                    "Monzo API access denied. You may need to re-authorize.");
        }

        if (status.value() == 429) {
            log.warn("Monzo API rate limited for {}", endpoint);
            throw new ApiException(ErrorCode.MONZO_API_ERROR,
                    "Monzo API rate limit reached. Please try again later.");
        }

        log.error("Monzo API error for {}: {} - {}", endpoint, status.value(), e.getResponseBodyAsString());
    }

    // ============ Response Records ============

    /**
     * Token response from Monzo OAuth.
     *
     * @param accessToken  the access token
     * @param refreshToken the refresh token (may be null)
     * @param expiresAt    when the access token expires
     */
    public record TokenResponse(
            String accessToken,
            @Nullable String refreshToken,
            @Nullable Instant expiresAt
    ) {
    }
}
