package dev.amfshr.budgeteer.bank;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Provider-neutral contract for bank data retrieval and OAuth token operations.
 *
 * <p>Implementations (e.g. {@code MonzoBankClient}) handle all provider-specific HTTP details
 * internally. Callers program to this interface and never import a provider type.
 */
public interface BankClient {

    /** Build the provider's OAuth authorization URL for the given CSRF state. */
    String buildAuthorizationUrl(String state);

    /** Exchange an authorization code for tokens (redirect URI etc. come from the impl's config). */
    BankTokens exchangeCode(String code);

    /** Refresh an access token. Implementations fall back to the old refresh token if not rotated. */
    BankTokens refreshTokens(String refreshToken);

    /** Identify the connection at the provider (Monzo user_id; TrueLayer credentials_id + consent). */
    BankIdentity getIdentity(String accessToken);

    /** All accounts for the authenticated connection. */
    List<BankAccount> getAccounts(String accessToken);

    /**
     * Current balance for one account, as reported by the provider. The caller stamps the
     * fetch time; this record carries no timestamp.
     *
     * @throws BankConnectionRevokedException if the connection is revoked (401)
     * @throws BankClientException on any other upstream failure
     */
    BankBalance getBalance(String accessToken, String accountId);

    /**
     * One page of transactions for an account in the half-open window [from, to). Pass a null
     * pageCursor for the first page; pass the returned nextCursor for each subsequent page until
     * nextCursor is null. The cursor is an OPAQUE provider token — the caller persists and replays
     * it (intra-window paging) but never interprets it. The caller drives windowing + commits.
     *
     * @throws BankReauthRequiredException if the provider's SCA window has expired for this range
     * @throws BankConnectionRevokedException if the connection is revoked (401)
     * @throws BankClientException on any other upstream failure
     */
    BankTransactionPage getTransactions(String accessToken, String accountId,
                                        Instant from, Instant to, @Nullable String pageCursor);
}
