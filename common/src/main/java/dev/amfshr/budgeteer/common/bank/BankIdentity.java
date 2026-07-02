package dev.amfshr.budgeteer.common.bank;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Provider-neutral identity result.
 */
public record BankIdentity(
        String providerUserId,
        @Nullable Instant consentExpiresAt
) {}
