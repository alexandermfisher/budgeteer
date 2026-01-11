package dev.amf.budgeteer.api.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApiError} factory methods.
 */
@DisplayName("ApiError")
class ApiErrorTest {

    @Nested
    @DisplayName("of(ErrorCode, String, String)")
    class OfWithCodeMessagePath {

        @Test
        @DisplayName("should create error with custom message")
        void shouldCreateErrorWithCustomMessage() {
            ApiError error = ApiError.of(ErrorCode.INVALID_TOKEN, "Custom error message", "/api/test");

            assertThat(error.success()).isFalse();
            assertThat(error.timestamp()).isNotNull();
            assertThat(error.error().code()).isEqualTo("INVALID_TOKEN");
            assertThat(error.error().message()).isEqualTo("Custom error message");
            assertThat(error.error().path()).isEqualTo("/api/test");
            assertThat(error.error().details()).isNull();
        }
    }

    @Nested
    @DisplayName("of(ErrorCode, String)")
    class OfWithCodeAndPath {

        @Test
        @DisplayName("should create error with default message")
        void shouldCreateErrorWithDefaultMessage() {
            ApiError error = ApiError.of(ErrorCode.USER_NOT_FOUND, "/api/users/123");

            assertThat(error.success()).isFalse();
            assertThat(error.error().code()).isEqualTo("USER_NOT_FOUND");
            assertThat(error.error().message()).isEqualTo("User not found");
            assertThat(error.error().path()).isEqualTo("/api/users/123");
        }
    }

    @Nested
    @DisplayName("of(ErrorCode, String, String, Map)")
    class OfWithDetails {

        @Test
        @DisplayName("should create error with validation details")
        void shouldCreateErrorWithDetails() {
            Map<String, String> details = Map.of(
                    "email", "Invalid email format",
                    "password", "Password too short"
            );

            ApiError error = ApiError.of(ErrorCode.VALIDATION_ERROR, "Validation failed", "/api/register", details);

            assertThat(error.success()).isFalse();
            assertThat(error.error().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.error().message()).isEqualTo("Validation failed");
            assertThat(error.error().details()).containsEntry("email", "Invalid email format");
            assertThat(error.error().details()).containsEntry("password", "Password too short");
        }
    }

    @Nested
    @DisplayName("ErrorDetails")
    class ErrorDetailsTest {

        @Test
        @DisplayName("of() should create details without extra details")
        void shouldCreateDetailsWithoutExtras() {
            ApiError.ErrorDetails details = ApiError.ErrorDetails.of(ErrorCode.ACCESS_DENIED, "Access denied", "/api/admin");

            assertThat(details.code()).isEqualTo("ACCESS_DENIED");
            assertThat(details.message()).isEqualTo("Access denied");
            assertThat(details.path()).isEqualTo("/api/admin");
            assertThat(details.details()).isNull();
        }
    }
}
