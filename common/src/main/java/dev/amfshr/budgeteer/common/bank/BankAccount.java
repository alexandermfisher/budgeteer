package dev.amfshr.budgeteer.common.bank;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Provider-neutral bank account.
 */
public record BankAccount(
        String externalId,
        String type,                 // raw provider account-type string
        @Nullable String description,
        String currency,
        boolean closed,
        @Nullable Instant createdAt
) {}
