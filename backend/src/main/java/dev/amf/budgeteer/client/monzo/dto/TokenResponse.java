package dev.amf.budgeteer.client.monzo.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record TokenResponse(
        String accessToken,
        @Nullable String refreshToken,
        @Nullable Instant expiresAt
) {
}
