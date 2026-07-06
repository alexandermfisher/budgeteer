package dev.amfshr.budgeteer.exception;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApiException}.
 */
@DisplayName("ApiException")
class ApiExceptionTest {

    @Nested
    @DisplayName("Constructor with ErrorCode only")
    class ConstructorWithCodeOnly {

        @Test
        @DisplayName("should use default message from error code")
        void shouldUseDefaultMessage() {
            ApiException ex = new ApiException(ErrorCode.USER_NOT_FOUND);

            assertThat(ex.getMessage()).isEqualTo("User not found");
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ex.getDetails()).isNull();
            assertThat(ex.hasDetails()).isFalse();
        }
    }

    @Nested
    @DisplayName("Constructor with ErrorCode and message")
    class ConstructorWithCodeAndMessage {

        @Test
        @DisplayName("should use custom message")
        void shouldUseCustomMessage() {
            ApiException ex = new ApiException(ErrorCode.VALIDATION_ERROR, "Email is invalid");

            assertThat(ex.getMessage()).isEqualTo("Email is invalid");
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("Constructor with ErrorCode, message and cause")
    class ConstructorWithCause {

        @Test
        @DisplayName("should include cause")
        void shouldIncludeCause() {
            RuntimeException cause = new RuntimeException("Original error");
            ApiException ex = new ApiException(ErrorCode.MONZO_API_ERROR, "Monzo call failed", cause);

            assertThat(ex.getMessage()).isEqualTo("Monzo call failed");
            assertThat(ex.getCause()).isEqualTo(cause);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }
    }

    @Nested
    @DisplayName("Constructor with ErrorCode, message and details")
    class ConstructorWithDetails {

        @Test
        @DisplayName("should include validation details")
        void shouldIncludeDetails() {
            Map<String, String> details = Map.of(
                    "email", "Invalid format",
                    "password", "Too short"
            );
            ApiException ex = new ApiException(ErrorCode.VALIDATION_ERROR, "Validation failed", details);

            assertThat(ex.getMessage()).isEqualTo("Validation failed");
            assertThat(ex.getDetails()).isEqualTo(details);
            assertThat(ex.hasDetails()).isTrue();
        }

        @Test
        @DisplayName("hasDetails() should return false for empty map")
        void hasDetailsShouldReturnFalseForEmptyMap() {
            ApiException ex = new ApiException(ErrorCode.VALIDATION_ERROR, "Validation failed", Map.of());

            assertThat(ex.hasDetails()).isFalse();
        }
    }

    @Nested
    @DisplayName("getHttpStatus()")
    class GetHttpStatus {

        @Test
        @DisplayName("should delegate to error code")
        void shouldDelegateToErrorCode() {
            ApiException ex401 = new ApiException(ErrorCode.INVALID_TOKEN);
            ApiException ex403 = new ApiException(ErrorCode.ACCESS_DENIED);
            ApiException ex404 = new ApiException(ErrorCode.USER_NOT_FOUND);
            ApiException ex500 = new ApiException(ErrorCode.INTERNAL_ERROR);

            assertThat(ex401.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(ex403.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex404.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ex500.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
