package dev.amfshr.budgeteer.api.account.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** One bank account as the product sees it. Balance fields are null until the first refresh. */
public record AccountResponse(
        UUID id, String provider, String accountType, String institutionName,
        @Nullable String displayName, String currency,
        @Nullable Long balanceMinorUnits, @Nullable Instant balanceAsOf,
        @Nullable Long creditLimitMinorUnits, int displayOrder, boolean archived) {}
