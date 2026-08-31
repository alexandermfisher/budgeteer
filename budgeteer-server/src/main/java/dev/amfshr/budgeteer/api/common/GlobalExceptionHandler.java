package dev.amfshr.budgeteer.api.common;

import dev.amfshr.budgeteer.provider.exception.ProviderException;
import dev.amfshr.budgeteer.provider.exception.ProviderConnectionRevokedException;
import dev.amfshr.budgeteer.provider.exception.ProviderReauthRequiredException;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for all REST controllers.
 * Converts exceptions to standardized ApiError responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle custom API exceptions.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        log.warn("API exception [code={}, uri={}, message={}]",
                ex.getErrorCode(), LogSanitizer.sanitize(request.getRequestURI()), LogSanitizer.sanitize(ex.getMessage()));

        ApiError error = ex.hasDetails()
                ? ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI(), ex.getDetails())
                : ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI());

        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(error);
    }

    /**
     * Handle provider-neutral exceptions, mapping subclasses to the {@code PROVIDER_*} error codes.
     */
    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ApiError> handleProviderException(ProviderException ex, HttpServletRequest request) {
        ErrorCode code;
        if (ex instanceof ProviderConnectionRevokedException) {
            code = ErrorCode.PROVIDER_CONNECTION_REVOKED;
        } else if (ex instanceof ProviderReauthRequiredException) {
            code = ErrorCode.PROVIDER_REAUTH_REQUIRED;
        } else {
            code = ErrorCode.PROVIDER_API_ERROR;
        }

        log.warn("Provider exception [code={}, uri={}, message={}]",
                code, LogSanitizer.sanitize(request.getRequestURI()), LogSanitizer.sanitize(ex.getMessage()));

        ApiError error = ApiError.of(code, ex.getMessage(), request.getRequestURI());

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(error);
    }

    /**
     * Handle validation errors from @Valid annotations.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage())
        );

        log.warn("Validation failed: {}", fieldErrors);

        ApiError error = ApiError.of(
                ErrorCode.VALIDATION_ERROR,
                "Request validation failed",
                request.getRequestURI(),
                fieldErrors
        );

        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(error);
    }

    /**
     * Handle validation errors from @Validated on @RequestParam and @PathVariable.
     * These produce ConstraintViolationException, not MethodArgumentNotValidException.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            // The property path includes the method and param name — extract just the param name
            String path = violation.getPropertyPath().toString();
            String param = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fieldErrors.put(param, violation.getMessage());
        });

        log.warn("Constraint violation: {}", fieldErrors);

        ApiError error = ApiError.of(
                ErrorCode.VALIDATION_ERROR,
                "Request validation failed",
                request.getRequestURI(),
                fieldErrors
        );

        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(error);
    }

    /**
     * Handle missing request parameters.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request) {

        String message = String.format("Required parameter '%s' is missing", ex.getParameterName());
        log.warn("Missing parameter: {}", ex.getParameterName());

        ApiError error = ApiError.of(ErrorCode.INVALID_REQUEST, message, request.getRequestURI());

        return ResponseEntity
                .status(ErrorCode.INVALID_REQUEST.getHttpStatus())
                .body(error);
    }

    /**
     * Handle type mismatch in request parameters.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        String message = String.format("Parameter '%s' must be of type %s",
                ex.getName(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");

        log.warn("Type mismatch: {} - {}", ex.getName(), message);

        ApiError error = ApiError.of(ErrorCode.INVALID_REQUEST, message, request.getRequestURI());

        return ResponseEntity
                .status(ErrorCode.INVALID_REQUEST.getHttpStatus())
                .body(error);
    }

    /**
     * Handle malformed JSON in request body.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMalformedJson(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        log.warn("Malformed request body: {}", ex.getMessage());

        ApiError error = ApiError.of(
                ErrorCode.INVALID_REQUEST,
                "Malformed request body",
                request.getRequestURI()
        );

        return ResponseEntity
                .status(ErrorCode.INVALID_REQUEST.getHttpStatus())
                .body(error);
    }

    /**
     * Handle unsupported HTTP methods.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {

        String message = String.format("Method '%s' not supported for this endpoint", ex.getMethod());
        log.warn("Method not supported: {} for {}", LogSanitizer.sanitize(ex.getMethod()), LogSanitizer.sanitize(request.getRequestURI()));

        ApiError error = ApiError.of(ErrorCode.INVALID_REQUEST, message, request.getRequestURI());

        return ResponseEntity
                .status(ErrorCode.INVALID_REQUEST.getHttpStatus())
                .body(error);
    }

    /**
     * Handle 404 not found (when no handler matches).
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            @SuppressWarnings("unused") NoHandlerFoundException ex,
            HttpServletRequest request) {

        log.warn("Endpoint not found: {}", LogSanitizer.sanitize(request.getRequestURI()));

        ApiError error = ApiError.of(ErrorCode.RESOURCE_NOT_FOUND, request.getRequestURI());

        return ResponseEntity
                .status(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus())
                .body(error);
    }

    /**
     * Catch-all handler for unexpected exceptions.
     * Logs full stack trace but returns generic message to client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unexpected error [uri={}, exceptionType={}, message={}]", 
                LogSanitizer.sanitize(request.getRequestURI()), ex.getClass().getSimpleName(), LogSanitizer.sanitize(ex.getMessage()), ex);

        ApiError error = ApiError.of(
                ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred",
                request.getRequestURI()
        );

        return ResponseEntity
                .status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(error);
    }
}
