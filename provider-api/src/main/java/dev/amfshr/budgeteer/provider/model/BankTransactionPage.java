package dev.amfshr.budgeteer.provider.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One page of transactions (each with its verbatim provider JSON) plus an opaque cursor for
 * the next page (null = last page). Replay the cursor as {@link SyncPosition.NextPage}.
 */
public record BankTransactionPage(
        List<Sourced<BankTransaction>> transactions,
        @Nullable String nextCursor
) {}
