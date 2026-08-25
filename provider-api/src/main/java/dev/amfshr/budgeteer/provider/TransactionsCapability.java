package dev.amfshr.budgeteer.provider;

import dev.amfshr.budgeteer.provider.exception.ProviderConnectionRevokedException;
import dev.amfshr.budgeteer.provider.exception.ProviderException;
import dev.amfshr.budgeteer.provider.exception.ProviderReauthRequiredException;
import dev.amfshr.budgeteer.provider.model.BankTransactionPage;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Transaction-history capability: page through an account's transactions one window at a time.
 * The caller owns windowing, cursor persistence, and commits; implementations are stateless
 * per call.
 */
public interface TransactionsCapability {

    /**
     * One page of transactions for an account in the half-open window [from, to). Pass a null
     * pageCursor for the first page; pass the returned nextCursor for each subsequent page until
     * nextCursor is null. The cursor is an OPAQUE provider token — the caller persists and replays
     * it (intra-window paging) but never interprets it. The caller drives windowing + commits.
     *
     * @throws ProviderReauthRequiredException if the provider's SCA window has expired for this range
     * @throws ProviderConnectionRevokedException if the connection is revoked (401)
     * @throws ProviderException on any other upstream failure
     */
    BankTransactionPage getTransactions(String accessToken, String accountId,
                                        Instant from, Instant to, @Nullable String pageCursor);
}
