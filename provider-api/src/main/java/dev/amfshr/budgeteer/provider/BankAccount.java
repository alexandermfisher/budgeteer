package dev.amfshr.budgeteer.provider;

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
        @Nullable Instant createdAt,
        @Nullable String rawJson     // verbatim provider JSON for this element; null if unavailable
) {
    @Override
    public String toString() {
        return ("BankAccount[externalId=%s, type=%s, description=%s, currency=%s, "
                + "closed=%s, createdAt=%s, rawJson=%s]")
                .formatted(externalId, type, description, currency, closed, createdAt,
                        rawJson == null ? "null" : "<redacted>");
    }
}
