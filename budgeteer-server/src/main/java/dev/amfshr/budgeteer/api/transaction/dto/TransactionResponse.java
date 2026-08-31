package dev.amfshr.budgeteer.api.transaction.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** One domain transaction. {@code amountMinorUnits} is signed: negative = money out. */
public record TransactionResponse(
        UUID id, UUID accountId, long amountMinorUnits, String currency, String status,
        @Nullable String description, @Nullable String merchantName,
        @Nullable String merchantCategory, @Nullable String notes,
        boolean excludedFromAnalytics, Instant occurredAt, @Nullable Instant settledAt) {}
