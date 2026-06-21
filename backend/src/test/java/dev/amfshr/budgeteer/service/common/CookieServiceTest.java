package dev.amfshr.budgeteer.service.common;

import dev.amfshr.budgeteer.config.JweProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CookieService}.
 *
 * <p>These are pure unit tests using Spring's MockHttpServletRequest/Response.
 * No Spring context is required.</p>
 */
@DisplayName("CookieService")
class CookieServiceTest {

    private CookieService cookieService;
    private JweProperties jweProperties;
    private MockHttpServletResponse response;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        jweProperties = new JweProperties();
        jweProperties.setAccessTokenExpiry(Duration.ofMinutes(15));
        jweProperties.setRefreshTokenExpiry(Duration.ofDays(7));

        // Test with secure=false (development mode)
        cookieService = new CookieService(jweProperties, false);

        response = new MockHttpServletResponse();
        request = new MockHttpServletRequest();
    }

    @Nested
    @DisplayName("setAccessTokenCookie")
    class SetAccessTokenCookie {

        @Test
        @DisplayName("should set access token cookie with correct attributes")
        void shouldSetAccessTokenCookie() {
            // Given
            String token = "test-access-token";

            // When
            cookieService.setAccessTokenCookie(response, token);

            // Then
            String cookieHeader = response.getHeader(HttpHeaders.SET_COOKIE);
            assertThat(cookieHeader).isNotNull();
            assertThat(cookieHeader).contains("access_token=test-access-token");
            assertThat(cookieHeader).contains("HttpOnly");
            assertThat(cookieHeader).contains("Path=/api");
            assertThat(cookieHeader).contains("SameSite=Lax");
        }

        @Test
        @DisplayName("should set Max-Age based on configured expiry")
        void shouldSetMaxAgeFromConfig() {
            // Given
            String token = "test-access-token";

            // When
            cookieService.setAccessTokenCookie(response, token);

            // Then
            String cookieHeader = response.getHeader(HttpHeaders.SET_COOKIE);
            assertThat(cookieHeader).contains("Max-Age=900"); // 15 minutes = 900 seconds
        }

        @Test
        @DisplayName("should not set Secure flag in development mode")
        void shouldNotSetSecureFlagInDevMode() {
            // Given
            String token = "test-access-token";

            // When
            cookieService.setAccessTokenCookie(response, token);

            // Then
            String cookieHeader = response.getHeader(HttpHeaders.SET_COOKIE);
            // In dev mode (secure=false), the Secure attribute should not be present
            // Note: Spring's ResponseCookie only adds Secure when true
            assertThat(cookieHeader).doesNotContain("Secure");
        }

        @Test
        @DisplayName("should set Secure flag in production mode")
        void shouldSetSecureFlagInProdMode() {
            // Given
            CookieService secureCookieService = new CookieService(jweProperties, true);
            String token = "test-access-token";

            // When
            secureCookieService.setAccessTokenCookie(response, token);

            // Then
            String cookieHeader = response.getHeader(HttpHeaders.SET_COOKIE);
            assertThat(cookieHeader).contains("Secure");
        }
    }

    @Nested
    @DisplayName("setRefreshTokenCookie")
    class SetRefreshTokenCookie {

        @Test
        @DisplayName("should set refresh token cookie with correct attributes")
        void shouldSetRefreshTokenCookie() {
            // Given
            String token = "test-refresh-token";

            // When
            cookieService.setRefreshTokenCookie(response, token);

            // Then
            String cookieHeader = response.getHeader(HttpHeaders.SET_COOKIE);
            assertThat(cookieHeader).isNotNull();
            assertThat(cookieHeader).contains("refresh_token=test-refresh-token");
            assertThat(cookieHeader).contains("HttpOnly");
            assertThat(cookieHeader).contains("Path=/api/auth"); // More restrictive path
            assertThat(cookieHeader).contains("SameSite=Lax");
        }

        @Test
        @DisplayName("should have longer Max-Age than access token")
        void shouldHaveLongerMaxAge() {
            // Given
            String token = "test-refresh-token";

            // When
            cookieService.setRefreshTokenCookie(response, token);

            // Then
            String cookieHeader = response.getHeader(HttpHeaders.SET_COOKIE);
            // 7 days = 604800 seconds
            assertThat(cookieHeader).contains("Max-Age=604800");
        }
    }

    @Nested
    @DisplayName("setAuthCookies")
    class SetAuthCookies {

        @Test
        @DisplayName("should set both access and refresh token cookies")
        void shouldSetBothCookies() {
            // Given
            String accessToken = "test-access-token";
            String refreshToken = "test-refresh-token";

            // When
            cookieService.setAuthCookies(response, accessToken, refreshToken);

            // Then
            var cookieHeaders = response.getHeaders(HttpHeaders.SET_COOKIE);
            assertThat(cookieHeaders).hasSize(2);

            boolean hasAccessToken = cookieHeaders.stream()
                    .anyMatch(h -> h.contains("access_token=test-access-token"));
            boolean hasRefreshToken = cookieHeaders.stream()
                    .anyMatch(h -> h.contains("refresh_token=test-refresh-token"));

            assertThat(hasAccessToken).isTrue();
            assertThat(hasRefreshToken).isTrue();
        }
    }

    @Nested
    @DisplayName("clearAuthCookies")
    class ClearAuthCookies {

        @Test
        @DisplayName("should clear both cookies by setting empty value and Max-Age=0")
        void shouldClearBothCookies() {
            // When
            cookieService.clearAuthCookies(response);

            // Then
            var cookieHeaders = response.getHeaders(HttpHeaders.SET_COOKIE);
            assertThat(cookieHeaders).hasSize(2);

            // Both cookies should have Max-Age=0 to delete them
            for (String header : cookieHeaders) {
                assertThat(header).contains("Max-Age=0");
            }
        }

        @Test
        @DisplayName("should maintain correct paths when clearing")
        void shouldMaintainCorrectPaths() {
            // When
            cookieService.clearAuthCookies(response);

            // Then
            var cookieHeaders = response.getHeaders(HttpHeaders.SET_COOKIE);

            boolean hasAccessPath = cookieHeaders.stream()
                    .anyMatch(h -> h.contains("access_token") && h.contains("Path=/api"));
            boolean hasRefreshPath = cookieHeaders.stream()
                    .anyMatch(h -> h.contains("refresh_token") && h.contains("Path=/api/auth"));

            assertThat(hasAccessPath).isTrue();
            assertThat(hasRefreshPath).isTrue();
        }
    }

    @Nested
    @DisplayName("extractCookie")
    class ExtractCookie {

        @Test
        @DisplayName("should extract existing cookie value")
        void shouldExtractExistingCookie() {
            // Given
            request.setCookies(new Cookie("test_cookie", "test_value"));

            // When
            Optional<String> value = cookieService.extractCookie(request, "test_cookie");

            // Then
            assertThat(value).isPresent();
            assertThat(value.get()).isEqualTo("test_value");
        }

        @Test
        @DisplayName("should return empty for non-existent cookie")
        void shouldReturnEmptyForNonExistentCookie() {
            // Given
            request.setCookies(new Cookie("other_cookie", "other_value"));

            // When
            Optional<String> value = cookieService.extractCookie(request, "test_cookie");

            // Then
            assertThat(value).isEmpty();
        }

        @Test
        @DisplayName("should return empty when no cookies present")
        void shouldReturnEmptyWhenNoCookies() {
            // Given - request with no cookies

            // When
            Optional<String> value = cookieService.extractCookie(request, "test_cookie");

            // Then
            assertThat(value).isEmpty();
        }

        @Test
        @DisplayName("should find cookie among multiple cookies")
        void shouldFindCookieAmongMultiple() {
            // Given
            request.setCookies(
                    new Cookie("cookie1", "value1"),
                    new Cookie("target", "target_value"),
                    new Cookie("cookie3", "value3")
            );

            // When
            Optional<String> value = cookieService.extractCookie(request, "target");

            // Then
            assertThat(value).isPresent();
            assertThat(value.get()).isEqualTo("target_value");
        }
    }

    @Nested
    @DisplayName("extractAccessToken")
    class ExtractAccessToken {

        @Test
        @DisplayName("should extract access token from cookies")
        void shouldExtractAccessToken() {
            // Given
            request.setCookies(new Cookie("access_token", "my-access-token"));

            // When
            Optional<String> token = cookieService.extractAccessToken(request);

            // Then
            assertThat(token).isPresent();
            assertThat(token.get()).isEqualTo("my-access-token");
        }
    }

    @Nested
    @DisplayName("extractRefreshToken")
    class ExtractRefreshToken {

        @Test
        @DisplayName("should extract refresh token from cookies")
        void shouldExtractRefreshToken() {
            // Given
            request.setCookies(new Cookie("refresh_token", "my-refresh-token"));

            // When
            Optional<String> token = cookieService.extractRefreshToken(request);

            // Then
            assertThat(token).isPresent();
            assertThat(token.get()).isEqualTo("my-refresh-token");
        }
    }

    @Nested
    @DisplayName("getClientIpAddress")
    class GetClientIpAddress {

        @Test
        @DisplayName("should return X-Forwarded-For header if present")
        void shouldReturnXForwardedFor() {
            // Given
            request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");
            request.setRemoteAddr("127.0.0.1");

            // When
            String ip = cookieService.getClientIpAddress(request);

            // Then
            assertThat(ip).isEqualTo("203.0.113.195"); // First IP in list
        }

        @Test
        @DisplayName("should return remote address if X-Forwarded-For not present")
        void shouldReturnRemoteAddrIfNoXForwardedFor() {
            // Given
            request.setRemoteAddr("192.168.1.1");

            // When
            String ip = cookieService.getClientIpAddress(request);

            // Then
            assertThat(ip).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("should handle single IP in X-Forwarded-For")
        void shouldHandleSingleIpInXForwardedFor() {
            // Given
            request.addHeader("X-Forwarded-For", "203.0.113.195");
            request.setRemoteAddr("127.0.0.1");

            // When
            String ip = cookieService.getClientIpAddress(request);

            // Then
            assertThat(ip).isEqualTo("203.0.113.195");
        }

        @Test
        @DisplayName("should handle blank X-Forwarded-For")
        void shouldHandleBlankXForwardedFor() {
            // Given
            request.addHeader("X-Forwarded-For", "   ");
            request.setRemoteAddr("192.168.1.1");

            // When
            String ip = cookieService.getClientIpAddress(request);

            // Then
            assertThat(ip).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("should return null when X-Forwarded-For contains an injected non-IP string")
        void shouldRejectInjectedXForwardedFor() {
            // Given - attacker-controlled header value
            request.addHeader("X-Forwarded-For", "'; DROP TABLE users; --");
            request.setRemoteAddr("127.0.0.1");

            // When
            String ip = cookieService.getClientIpAddress(request);

            // Then - falls through to remoteAddr (127.0.0.1), which is valid
            assertThat(ip).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("should fall back to X-Real-IP when X-Forwarded-For is absent")
        void shouldFallBackToXRealIp() {
            // Given
            request.addHeader("X-Real-IP", "10.0.0.5");
            request.setRemoteAddr("127.0.0.1");

            // When
            String ip = cookieService.getClientIpAddress(request);

            // Then
            assertThat(ip).isEqualTo("10.0.0.5");
        }

        @Test
        @DisplayName("should reject invalid X-Real-IP and fall back to remote address")
        void shouldRejectInvalidXRealIpAndFallBack() {
            // Given
            request.addHeader("X-Real-IP", "not-an-ip");
            request.setRemoteAddr("192.168.0.1");

            // When
            String ip = cookieService.getClientIpAddress(request);

            // Then
            assertThat(ip).isEqualTo("192.168.0.1");
        }
    }

    @Nested
    @DisplayName("Cookie Constants")
    class CookieConstants {

        @Test
        @DisplayName("should have correct cookie names")
        void shouldHaveCorrectCookieNames() {
            assertThat(CookieService.ACCESS_TOKEN_COOKIE).isEqualTo("access_token");
            assertThat(CookieService.REFRESH_TOKEN_COOKIE).isEqualTo("refresh_token");
        }
    }
}
