package dev.amfshr.budgeteer.provider;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One page of transactions plus an opaque cursor for the next page (null = last page).
 */
public record BankTransactionPage(
        List<BankTransaction> transactions,
        @Nullable String nextCursor
) {}
