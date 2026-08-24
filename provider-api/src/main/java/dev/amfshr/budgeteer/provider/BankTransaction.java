package dev.amfshr.budgeteer.provider;

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
        @Nullable Instant settledAt,
        @Nullable String rawJson     // verbatim provider JSON for this element; null if unavailable
) {
    @Override
    public String toString() {
        return ("BankTransaction[externalId=%s, amountMinorUnits=%s, currency=%s, "
                + "description=%s, merchantName=%s, merchantCategory=%s, notes=%s, "
                + "declined=%s, createdAt=%s, settledAt=%s, rawJson=%s]")
                .formatted(externalId, amountMinorUnits, currency, description, merchantName,
                        merchantCategory, notes, declined, createdAt, settledAt,
                        rawJson == null ? "null" : "<redacted>");
    }
}
