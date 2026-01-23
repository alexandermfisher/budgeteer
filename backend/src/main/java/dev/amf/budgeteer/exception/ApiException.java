package dev.amf.budgeteer.exception;

import dev.amf.budgeteer.api.common.ErrorCode;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Base exception for all API errors.
 * Carries an ErrorCode which determines HTTP status and default message.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, String> details;

    /**
     * Create exception with error code and default message.
     */
    public ApiException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Create exception with error code and custom message.
     */
    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Create exception with error code, custom message, and cause.
     */
    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Create exception with error code and additional details (e.g., validation errors).
     */
    public ApiException(ErrorCode errorCode, String message, Map<String, String> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return errorCode.getHttpStatus();
    }

    public Map<String, String> getDetails() {
        return details;
    }

    public boolean hasDetails() {
        return details != null && !details.isEmpty();
    }
}
