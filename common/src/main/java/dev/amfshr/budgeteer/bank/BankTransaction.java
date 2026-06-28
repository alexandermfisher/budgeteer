package dev.amfshr.budgeteer.bank;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Provider-neutral transaction. {@code amountMinorUnits} is signed: negative = money out.
 * Monzo is already minor units; TrueLayer maps {@code amount×100} with DEBIT→negative,
 * CREDIT→positive.
 */
public record BankTransaction(
        String externalId,
        long amountMinorUnits,
        String currency,
        @Nullable String description,
        @Nullable String merchantName,
        @Nullable String merchantCategory,
        @Nullable String notes,
        boolean declined,
        Instant createdAt,
        @Nullable Instant settledAt
) {}
