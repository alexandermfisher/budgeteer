package dev.amfshr.budgeteer.api.common;

import dev.amfshr.budgeteer.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
    }

    @Nested
    @DisplayName("handleApiException")
    class HandleApiException {

        @Test
        @DisplayName("should return correct status and error body for ApiException")
        void shouldHandleApiException() {
            ApiException ex = new ApiException(ErrorCode.USER_NOT_FOUND, "User 123 not found");

            ResponseEntity<ApiError> response = handler.handleApiException(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().error().code()).isEqualTo("USER_NOT_FOUND");
            assertThat(response.getBody().error().message()).isEqualTo("User 123 not found");
            assertThat(response.getBody().error().path()).isEqualTo("/api/test");
        }

        @Test
        @DisplayName("should include details when ApiException has details")
        void shouldIncludeDetails() {
            Map<String, String> details = Map.of("field", "error");
            ApiException ex = new ApiException(ErrorCode.VALIDATION_ERROR, "Validation failed", details);

            ResponseEntity<ApiError> response = handler.handleApiException(ex, request);

            assertThat(response.getBody().error().details()).containsEntry("field", "error");
        }
    }

    @Nested
    @DisplayName("handleValidationException")
    class HandleValidationException {

        @Test
        @DisplayName("should extract field errors and return 400")
        void shouldExtractFieldErrors() {
            // Given
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(
                    new FieldError("obj", "email", "Invalid email"),
                    new FieldError("obj", "password", "Too short")
            ));

            // When
            ResponseEntity<ApiError> response = handler.handleValidationException(ex, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().error().details())
                    .containsEntry("email", "Invalid email")
                    .containsEntry("password", "Too short");
        }
    }

    @Nested
    @DisplayName("handleMissingParameter")
    class HandleMissingParameter {

        @Test
        @DisplayName("should return 400 with parameter name in message")
        void shouldIncludeParameterName() {
            MissingServletRequestParameterException ex = new MissingServletRequestParameterException("token", "String");

            ResponseEntity<ApiError> response = handler.handleMissingParameter(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().error().code()).isEqualTo("INVALID_REQUEST");
            assertThat(response.getBody().error().message()).contains("token");
        }
    }

    @Nested
    @DisplayName("handleTypeMismatch")
    class HandleTypeMismatch {

        @Test
        @DisplayName("should return 400 with type info")
        void shouldIncludeTypeInfo() {
            MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
            when(ex.getName()).thenReturn("id");
            when(ex.getRequiredType()).thenReturn((Class) Long.class);

            ResponseEntity<ApiError> response = handler.handleTypeMismatch(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().error().message()).contains("id");
            assertThat(response.getBody().error().message()).contains("Long");
        }
    }

    @Nested
    @DisplayName("handleMalformedJson")
    class HandleMalformedJson {

        @Test
        @DisplayName("should return 400 with generic message")
        void shouldReturnGenericMessage() {
            HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);

            ResponseEntity<ApiError> response = handler.handleMalformedJson(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().error().code()).isEqualTo("INVALID_REQUEST");
            assertThat(response.getBody().error().message()).isEqualTo("Malformed request body");
        }
    }

    @Nested
    @DisplayName("handleMethodNotSupported")
    class HandleMethodNotSupported {

        @Test
        @DisplayName("should return 400 with method in message")
        void shouldIncludeMethod() {
            HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("DELETE");

            ResponseEntity<ApiError> response = handler.handleMethodNotSupported(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().error().message()).contains("DELETE");
        }
    }

    @Nested
    @DisplayName("handleNotFound")
    class HandleNotFound {

        @Test
        @DisplayName("should return 404 for unknown endpoints")
        void shouldReturn404() {
            NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/api/unknown", null);

            ResponseEntity<ApiError> response = handler.handleNotFound(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().error().code()).isEqualTo("RESOURCE_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("handleUnexpectedException")
    class HandleUnexpectedException {

        @Test
        @DisplayName("should return 500 with generic message (not leak exception details)")
        void shouldNotLeakExceptionDetails() {
            Exception ex = new RuntimeException("Database connection failed - secret info");

            ResponseEntity<ApiError> response = handler.handleUnexpectedException(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().error().message()).isEqualTo("An unexpected error occurred");
            assertThat(response.getBody().error().message()).doesNotContain("Database");
        }
    }
}
