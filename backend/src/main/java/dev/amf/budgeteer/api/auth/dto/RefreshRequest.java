package dev.amf.budgeteer.api.auth.dto;

import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Request body for token refresh when not using cookies.
 *
 * <p>This is optional - if the refresh_token cookie is present, it will be used instead.
 * This DTO exists to support API clients (mobile apps, Postman) that don't use cookies.
 *
 * @param refreshToken the refresh token (may be null if using cookies)
 */
public record RefreshRequest(
        @Nullable @Size(max = 1024, message = "Refresh token too long") String refreshToken
) {}
