package dev.amf.budgeteer.api.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ErrorCode} enum.
 */
@DisplayName("ErrorCode")
class ErrorCodeTest {

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("all error codes should have non-null HTTP status")
    void allCodesHaveHttpStatus(ErrorCode code) {
        assertThat(code.getHttpStatus()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("all error codes should have non-blank default message")
    void allCodesHaveDefaultMessage(ErrorCode code) {
        assertThat(code.getDefaultMessage()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("getStatusCode() should return HTTP status value")
    void getStatusCodeShouldReturnStatusValue(ErrorCode code) {
        assertThat(code.getStatusCode()).isEqualTo(code.getHttpStatus().value());
    }

    @Test
    @DisplayName("authentication errors should have 401 status")
    void authErrorsShouldHave401Status() {
        assertThat(ErrorCode.INVALID_TOKEN.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.MISSING_TOKEN.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.TOKEN_EXPIRED.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.INVALID_CREDENTIALS.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCode.NOT_AUTHENTICATED.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("authorization errors should have 403 status")
    void authzErrorsShouldHave403Status() {
        assertThat(ErrorCode.ACCESS_DENIED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.INSUFFICIENT_PERMISSIONS.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("validation errors should have 400 status")
    void validationErrorsShouldHave400Status() {
        assertThat(ErrorCode.VALIDATION_ERROR.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.INVALID_REQUEST.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.INVALID_EMAIL.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("not found errors should have 404 status")
    void notFoundErrorsShouldHave404Status() {
        assertThat(ErrorCode.USER_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.TOKEN_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
