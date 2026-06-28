package dev.amfshr.budgeteer.client.monzo;

import dev.amfshr.budgeteer.common.bank.BankAccount;
import dev.amfshr.budgeteer.common.bank.BankClient;
import dev.amfshr.budgeteer.common.bank.BankClientException;
import dev.amfshr.budgeteer.common.bank.BankConnectionRevokedException;
import dev.amfshr.budgeteer.common.bank.BankIdentity;
import dev.amfshr.budgeteer.common.bank.BankReauthRequiredException;
import dev.amfshr.budgeteer.common.bank.BankTokens;
import dev.amfshr.budgeteer.common.bank.BankTransaction;
import dev.amfshr.budgeteer.common.bank.BankTransactionPage;
import dev.amfshr.budgeteer.client.monzo.dto.MonzoAccountResponse;
import dev.amfshr.budgeteer.client.monzo.dto.MonzoAccountsResponse;
import dev.amfshr.budgeteer.client.monzo.dto.MonzoTransactionResponse;
import dev.amfshr.budgeteer.client.monzo.dto.MonzoTransactionsResponse;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Monzo API HTTP client — implements the provider-neutral {@link BankClient} contract.
 *
 * <p>Handles all HTTP communication with the Monzo API, providing:
 * <ul>
 *   <li>OAuth token exchange and refresh</li>
 *   <li>User identity verification (whoami)</li>
 *   <li>Account and transaction retrieval</li>
 *   <li>Centralized error handling (401 → {@link BankConnectionRevokedException})</li>
 * </ul>
 */
@Component
public class MonzoBankClient implements BankClient {

    private static final Logger log = LoggerFactory.getLogger(MonzoBankClient.class);

    private final MonzoProperties monzoProperties;
    private final RestClient restClient;

    public MonzoBankClient(MonzoProperties monzoProperties, RestClient monzoRestClient) {
        this.monzoProperties = monzoProperties;
        this.restClient = monzoRestClient;
    }

    // ============ BankClient implementation ============

    /**
     * Builds the Monzo authorization URL with all required parameters.
     */
    @Override
    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder
                .fromUriString(monzoProperties.authUrl())
                .queryParam("client_id", monzoProperties.clientId())
                .queryParam("redirect_uri", monzoProperties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    @Override
    public BankTokens exchangeCode(String code) {
        log.debug("Exchanging authorization code for tokens");

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "authorization_code");
        formData.add("client_id", monzoProperties.clientId());
        formData.add("client_secret", monzoProperties.clientSecret());
        formData.add("redirect_uri", monzoProperties.redirectUri());
        formData.add("code", code);

        Map<String, Object> response = executeTokenRequest(formData);

        String accessToken = (String) response.get("access_token");
        if (accessToken == null) {
            throw new BankClientException("No access token in Monzo response");
        }

        Integer expiresIn = (Integer) response.get("expires_in");
        log.info("Successfully exchanged code for tokens [expiresIn={}s]", expiresIn);

        return MonzoMapper.toBankTokens(response);
    }

    @Override
    public BankTokens refreshTokens(String refreshToken) {
        log.debug("Refreshing Monzo tokens");

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "refresh_token");
        formData.add("client_id", monzoProperties.clientId());
        formData.add("client_secret", monzoProperties.clientSecret());
        formData.add("refresh_token", refreshToken);

        Map<String, Object> response = executeTokenRequest(formData);

        String accessToken = (String) response.get("access_token");
        if (accessToken == null) {
            throw new BankClientException("No access token in refresh response");
        }

        Integer expiresIn = (Integer) response.get("expires_in");
        log.info("Successfully refreshed tokens [expiresIn={}s]", expiresIn);

        BankTokens mapped = MonzoMapper.toBankTokens(response);
        String newRefreshToken = (String) response.get("refresh_token");

        // Monzo does not always rotate the refresh token — fall back to the old one
        return new BankTokens(
                mapped.accessToken(),
                newRefreshToken != null ? newRefreshToken : refreshToken,
                mapped.expiresAt()
        );
    }

    @Override
    public BankIdentity getIdentity(String accessToken) {
        log.debug("Fetching Monzo user ID");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("/ping/whoami")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new BankClientException("Empty response from Monzo whoami endpoint");
            }

            String userId = (String) response.get("user_id");
            if (userId == null) {
                throw new BankClientException("No user_id in Monzo whoami response");
            }

            log.debug("Retrieved Monzo user ID");
            return new BankIdentity(userId, null);

        } catch (RestClientResponseException e) {
            handleMonzoError(e, "whoami");
            throw new BankClientException("Failed to get Monzo user ID: " + e.getMessage(), e);
        }
    }

    @Override
    public List<BankAccount> getAccounts(String accessToken) {
        log.debug("Fetching Monzo accounts");

        try {
            MonzoAccountsResponse response = restClient.get()
                    .uri("/accounts")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(MonzoAccountsResponse.class);

            if (response == null) {
                throw new BankClientException("Empty response from Monzo accounts endpoint");
            }

            log.debug("Fetched {} Monzo accounts", response.accounts().size());
            return response.accounts().stream()
                    .map(MonzoMapper::toBankAccount)
                    .toList();

        } catch (RestClientResponseException e) {
            handleMonzoError(e, "accounts");
            throw new BankClientException("Failed to fetch Monzo accounts: " + e.getMessage(), e);
        }
    }

    /**
     * One page of transactions for an account in the half-open window [from, to).
     *
     * <p>The opaque {@code pageCursor} is mapped to Monzo's {@code since} query parameter:
     * on the first page (cursor=null), {@code since} = {@code from} (RFC3339 timestamp);
     * on subsequent pages, {@code since} = cursor (last-seen transaction id as the cursor).
     * {@code before} is always pinned to {@code to} so each page stays bounded.
     *
     * <p>{@code nextCursor} is the last transaction id in the page, or {@code null} when
     * the page is shorter than {@link #PAGE_SIZE} (window exhausted).
     */
    @Override
    public BankTransactionPage getTransactions(
            String accessToken,
            String accountId,
            Instant from,
            Instant to,
            @Nullable String pageCursor
    ) {
        String since = pageCursor != null ? pageCursor : from.toString();
        String before = to.toString();

        log.debug("Fetching Monzo transactions [accountId={}, since={}, before={}, cursor={}]",
                accountId, since, before, pageCursor);

        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/transactions")
                    .queryParam("account_id", accountId)
                    .queryParam("expand[]", "merchant")
                    .queryParam("limit", PAGE_SIZE)
                    .queryParam("since", since)
                    .queryParam("before", before);

            MonzoTransactionsResponse response = restClient.get()
                    .uri(uri.build().toUriString())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(MonzoTransactionsResponse.class);

            if (response == null) {
                throw new BankClientException("Empty response from Monzo transactions endpoint");
            }

            List<MonzoTransactionResponse> raw = response.transactions();
            List<BankTransaction> mapped = raw.stream()
                    .map(MonzoMapper::toBankTransaction)
                    .toList();

            String nextCursor = raw.size() >= PAGE_SIZE
                    ? raw.get(raw.size() - 1).id()
                    : null;

            String cursorLabel = nextCursor != null && nextCursor.length() > 8
                    ? nextCursor.substring(nextCursor.length() - 8) : nextCursor;
            log.debug("→ Monzo returned {} transactions [cursor={}]",
                    mapped.size(),
                    cursorLabel != null ? cursorLabel : "end");

            return new BankTransactionPage(mapped, nextCursor);

        } catch (RestClientResponseException e) {
            handleMonzoError(e, "transactions");
            throw new BankClientException("Failed to fetch Monzo transactions: " + e.getMessage(), e);
        }
    }

    // ============ Private Methods ============

    private static final int PAGE_SIZE = 100;

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
                throw new BankClientException("Empty response from Monzo token endpoint");
            }

            return response;

        } catch (RestClientResponseException e) {
            handleMonzoError(e, "token");
            throw new BankClientException("Failed to exchange/refresh tokens: " + e.getMessage(), e);
        }
    }

    /**
     * Handles Monzo API errors, throwing provider-neutral exceptions.
     */
    private void handleMonzoError(RestClientResponseException e, String endpoint) {
        HttpStatusCode status = e.getStatusCode();

        if (status.value() == 401) {
            log.warn("Monzo API returned 401 for {} - token revoked or invalid", endpoint);
            throw new BankConnectionRevokedException(
                    "Your Monzo connection has been revoked. Please reconnect your account.");
        }

        if (status.value() == 403) {
            String body = e.getResponseBodyAsString();
            if (body.contains("forbidden.verification_required")) {
                log.warn("Monzo API returned 403 verification_required for {} - SCA window has expired", endpoint);
                throw new BankReauthRequiredException(
                        "Monzo requires re-authentication to access older transactions.");
            }
            log.warn("Monzo API returned 403 for {} - insufficient permissions", endpoint);
            throw new BankClientException("Monzo API access denied. You may need to re-authorize.");
        }

        if (status.value() == 429) {
            log.warn("Monzo API rate limited for {}", endpoint);
            throw new BankClientException("Monzo API rate limit reached. Please try again later.");
        }

        log.error("Monzo API error for {}: {} - {}", endpoint, status.value(), e.getResponseBodyAsString());
    }
}
