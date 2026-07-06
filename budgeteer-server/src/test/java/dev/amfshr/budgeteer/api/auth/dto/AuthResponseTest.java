package dev.amfshr.budgeteer.api.auth.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuthResponse} factory methods.
 */
@DisplayName("AuthResponse")
class AuthResponseTest {

    @Nested
    @DisplayName("magicLinkSent")
    class MagicLinkSent {

        @Test
        @DisplayName("should create response with correct message and email")
        void shouldCreateCorrectResponse() {
            AuthResponse response = AuthResponse.magicLinkSent("test@example.com");

            assertThat(response.message()).isEqualTo("Check your email for a login link");
            assertThat(response.email()).isEqualTo("test@example.com");
            assertThat(response.accessToken()).isNull();
            assertThat(response.refreshToken()).isNull();
        }
    }

    @Nested
    @DisplayName("tokenRefreshed")
    class TokenRefreshed {

        @Test
        @DisplayName("should create response without tokens (cookie-based)")
        void shouldCreateResponseWithoutTokens() {
            AuthResponse response = AuthResponse.tokenRefreshed();

            assertThat(response.message()).isEqualTo("Token refreshed");
            assertThat(response.email()).isNull();
            assertThat(response.accessToken()).isNull();
            assertThat(response.refreshToken()).isNull();
        }

        @Test
        @DisplayName("should create response with tokens (API clients)")
        void shouldCreateResponseWithTokens() {
            AuthResponse response = AuthResponse.tokenRefreshed("access-123", "refresh-456");

            assertThat(response.message()).isEqualTo("Token refreshed");
            assertThat(response.email()).isNull();
            assertThat(response.accessToken()).isEqualTo("access-123");
            assertThat(response.refreshToken()).isEqualTo("refresh-456");
        }
    }

    @Nested
    @DisplayName("loggedOut")
    class LoggedOut {

        @Test
        @DisplayName("should create logout response with null tokens")
        void shouldCreateLogoutResponse() {
            AuthResponse response = AuthResponse.loggedOut();

            assertThat(response.message()).isEqualTo("Logged out");
            assertThat(response.email()).isNull();
            assertThat(response.accessToken()).isNull();
            assertThat(response.refreshToken()).isNull();
        }
    }
}
