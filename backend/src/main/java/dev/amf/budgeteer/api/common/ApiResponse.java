package dev.amf.budgeteer.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Standard wrapper for all successful API responses.
 * Provides consistent structure across all endpoints.
 *
 * @param <T> the type of data being returned
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        Instant timestamp
) {
    /**
     * Create a successful response with data.
     */
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(true, data, Instant.now());
    }

    /**
     * Create a successful response with no data.
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, Instant.now());
    }
}
