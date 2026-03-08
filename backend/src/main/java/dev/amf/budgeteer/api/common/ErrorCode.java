package dev.amf.budgeteer.api.common;

import org.springframework.http.HttpStatus;

/**
 * Standard error codes for API responses.
 * Each code has an associated HTTP status and default message.
 */
public enum ErrorCode {

    // ============ Authentication Errors (401) ============
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid or expired token"),
    MISSING_TOKEN(HttpStatus.UNAUTHORIZED, "Authentication token not provided"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token has expired"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    NOT_AUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),

    // ============ Authorization Errors (403) ============
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    INSUFFICIENT_PERMISSIONS(HttpStatus.FORBIDDEN, "Insufficient permissions"),

    // ============ Validation Errors (400) ============
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    INVALID_EMAIL(HttpStatus.BAD_REQUEST, "Invalid email format"),
    STATE_MISMATCH(HttpStatus.BAD_REQUEST, "State parameter mismatch"),
    OAUTH_STATE_INVALID(HttpStatus.BAD_REQUEST, "OAuth state is invalid or has already been used"),
    OAUTH_STATE_EXPIRED(HttpStatus.BAD_REQUEST, "OAuth state has expired - please try connecting again"),
    OAUTH_ACCESS_DENIED(HttpStatus.BAD_REQUEST, "User denied access to their account"),
    OAUTH_CODE_MISSING(HttpStatus.BAD_REQUEST, "Authorization code is required"),
    OAUTH_TOKEN_EXCHANGE_FAILED(HttpStatus.BAD_REQUEST, "Failed to exchange authorization code for tokens"),

    // ============ Not Found Errors (404) ============
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "Token not found"),

    // ============ Conflict Errors (409) ============
    ALREADY_EXISTS(HttpStatus.CONFLICT, "Resource already exists"),
    TOKEN_ALREADY_USED(HttpStatus.CONFLICT, "Token has already been used"),

    // ============ External Service Errors (502) ============
    MONZO_API_ERROR(HttpStatus.BAD_GATEWAY, "Monzo API error"),
    EMAIL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "Email service error"),

    // ============ Server Errors (500) ============
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),
    TOKEN_GENERATION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate token");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public int getStatusCode() {
        return httpStatus.value();
    }
}
