package dev.amfshr.budgeteer.provider.model;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Provider-neutral OAuth token response.
 */
public record BankTokens(
        String accessToken,
        @Nullable String refreshToken,
        @Nullable Instant expiresAt
) {}
