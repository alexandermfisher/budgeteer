package dev.amfshr.budgeteer.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Standard wrapper for all API error responses.
 * Provides consistent error structure across all endpoints.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        boolean success,
        ErrorDetails error,
        Instant timestamp
) {
    /**
     * Error details nested within the response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetails(
            String code,
            String message,
            String path,
            Map<String, String> details
    ) {
        public static ErrorDetails of(ErrorCode code, String message, String path) {
            return new ErrorDetails(code.name(), message, path, null);
        }

        public static ErrorDetails of(ErrorCode code, String message, String path, Map<String, String> details) {
            return new ErrorDetails(code.name(), message, path, details);
        }
    }

    /**
     * Create an error response with code and custom message.
     */
    public static ApiError of(ErrorCode code, String message, String path) {
        return new ApiError(
                false,
                ErrorDetails.of(code, message, path),
                Instant.now()
        );
    }

    /**
     * Create an error response using the default message for the code.
     */
    public static ApiError of(ErrorCode code, String path) {
        return of(code, code.getDefaultMessage(), path);
    }

    /**
     * Create an error response with additional details (e.g., validation errors).
     */
    public static ApiError of(ErrorCode code, String message, String path, Map<String, String> details) {
        return new ApiError(
                false,
                ErrorDetails.of(code, message, path, details),
                Instant.now()
        );
    }
}
