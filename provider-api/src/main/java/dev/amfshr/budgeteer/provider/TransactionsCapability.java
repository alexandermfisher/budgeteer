package dev.amfshr.budgeteer.provider;

import dev.amfshr.budgeteer.provider.exception.ProviderConnectionRevokedException;
import dev.amfshr.budgeteer.provider.exception.ProviderException;
import dev.amfshr.budgeteer.provider.exception.ProviderReauthRequiredException;
import dev.amfshr.budgeteer.provider.model.BankTransactionPage;
import dev.amfshr.budgeteer.provider.model.SyncPosition;

import java.time.Instant;

/**
 * Transaction-history capability: page through an account's transactions one window at a time.
 * The caller owns windowing, cursor persistence, and commits; implementations are stateless
 * per call.
 */
public interface TransactionsCapability {

    /**
     * One page of transactions for an account, starting at {@code position} and bounded by
     * {@code to} (exclusive). Open a fetch with {@link SyncPosition.FromTime} (windowed
     * backfill / time-based delta) or {@link SyncPosition.AfterTransaction} (id-based delta);
     * continue by replaying the returned {@code nextCursor} as {@link SyncPosition.NextPage}
     * until {@code nextCursor} is null. The caller drives windowing + commits.
     *
     * <p>Implementations must switch exhaustively over the position and throw
     * {@link ProviderException} for any kind their API cannot serve — never silently
     * reinterpret one kind as another.
     *
     * @throws ProviderReauthRequiredException if the provider's SCA window has expired for this range
     * @throws ProviderConnectionRevokedException if the connection is revoked (401)
     * @throws ProviderException on any other upstream failure, or an unsupported position kind
     */
    BankTransactionPage getTransactions(String accessToken, String accountId,
                                        SyncPosition position, Instant to);
}
