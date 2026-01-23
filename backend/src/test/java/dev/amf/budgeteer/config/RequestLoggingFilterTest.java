package dev.amf.budgeteer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RequestLoggingFilter.
 * Focuses on security-critical behaviors: sensitive data masking and request correlation.
 */
@DisplayName("RequestLoggingFilter")
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Nested
    @DisplayName("Sensitive Data Masking")
    class SensitiveDataMasking {

        @Test
        @DisplayName("should mask token query parameter in logs")
        void shouldMaskTokenQueryParam() throws Exception {
            try (LogCaptor logCaptor = LogCaptor.forClass(RequestLoggingFilter.class)) {
                // Given
                HttpServletRequest request = mockRequest("GET", "/api/auth/verify", "token=secret-magic-link-123");
                HttpServletResponse response = mockResponse(200);
                FilterChain chain = mock(FilterChain.class);

                // When
                filter.doFilterInternal(request, response, chain);

                // Then
                assertThat(logCaptor.getInfoLogs())
                        .anyMatch(log -> log.contains("token=***"))
                        .noneMatch(log -> log.contains("secret-magic-link-123"));
            }
        }

        @Test
        @DisplayName("should mask OAuth code parameter in logs")
        void shouldMaskOAuthCodeParam() throws Exception {
            try (LogCaptor logCaptor = LogCaptor.forClass(RequestLoggingFilter.class)) {
                // Given
                HttpServletRequest request = mockRequest("GET", "/api/monzo/oauth/callback", 
                        "code=oauth_code_abc123&state=random_state");
                HttpServletResponse response = mockResponse(200);
                FilterChain chain = mock(FilterChain.class);

                // When
                filter.doFilterInternal(request, response, chain);

                // Then
                assertThat(logCaptor.getInfoLogs())
                        .anyMatch(log -> log.contains("code=***"))
                        .anyMatch(log -> log.contains("state=***"))
                        .noneMatch(log -> log.contains("oauth_code_abc123"))
                        .noneMatch(log -> log.contains("random_state"));
            }
        }
    }

    @Nested
    @DisplayName("Request ID Generation")
    class RequestIdGeneration {

        @Test
        @DisplayName("should generate request ID when not provided")
        void shouldGenerateRequestId() throws Exception {
            try (LogCaptor logCaptor = LogCaptor.forClass(RequestLoggingFilter.class)) {
                // Given
                HttpServletRequest request = mockRequest("GET", "/api/auth/me", null);
                HttpServletResponse response = mockResponse(200);
                FilterChain chain = mock(FilterChain.class);

                // When
                filter.doFilterInternal(request, response, chain);

                // Then - logs should contain a requestId
                assertThat(logCaptor.getInfoLogs())
                        .anyMatch(log -> log.contains("requestId="));
            }
        }

        @Test
        @DisplayName("should propagate existing X-Request-ID header")
        void shouldPropagateExistingRequestId() throws Exception {
            try (LogCaptor logCaptor = LogCaptor.forClass(RequestLoggingFilter.class)) {
                // Given
                String existingRequestId = "existing-request-id-123";
                HttpServletRequest request = mockRequest("GET", "/api/auth/me", null);
                when(request.getHeader("X-Request-ID")).thenReturn(existingRequestId);
                HttpServletResponse response = mockResponse(200);
                FilterChain chain = mock(FilterChain.class);

                // When
                filter.doFilterInternal(request, response, chain);

                // Then
                assertThat(logCaptor.getInfoLogs())
                        .anyMatch(log -> log.contains("requestId=" + existingRequestId));
                verify(response).setHeader("X-Request-ID", existingRequestId);
            }
        }
    }

    @Nested
    @DisplayName("Health Check Exclusion")
    class HealthCheckExclusion {

        @Test
        @DisplayName("should not log actuator health requests")
        void shouldNotLogActuatorHealth() throws Exception {
            try (LogCaptor logCaptor = LogCaptor.forClass(RequestLoggingFilter.class)) {
                // Given
                HttpServletRequest request = mockRequest("GET", "/actuator/health", null);
                HttpServletResponse response = mockResponse(200);
                FilterChain chain = mock(FilterChain.class);

                // When
                filter.doFilterInternal(request, response, chain);

                // Then - no logs for health check
                assertThat(logCaptor.getInfoLogs())
                        .noneMatch(log -> log.contains("/actuator/health"));
            }
        }

        @Test
        @DisplayName("should not log legacy health live endpoint")
        void shouldNotLogLegacyHealthLive() throws Exception {
            try (LogCaptor logCaptor = LogCaptor.forClass(RequestLoggingFilter.class)) {
                // Given
                HttpServletRequest request = mockRequest("GET", "/api/health/live", null);
                HttpServletResponse response = mockResponse(200);
                FilterChain chain = mock(FilterChain.class);

                // When
                filter.doFilterInternal(request, response, chain);

                // Then - no logs for health check
                assertThat(logCaptor.getInfoLogs())
                        .noneMatch(log -> log.contains("/api/health/live"));
            }
        }
    }

    @Nested
    @DisplayName("Response Logging")
    class ResponseLogging {

        @Test
        @DisplayName("should log successful response with duration")
        void shouldLogSuccessfulResponse() throws Exception {
            try (LogCaptor logCaptor = LogCaptor.forClass(RequestLoggingFilter.class)) {
                // Given
                HttpServletRequest request = mockRequest("POST", "/api/auth/login", null);
                HttpServletResponse response = mockResponse(200);
                FilterChain chain = mock(FilterChain.class);

                // When
                filter.doFilterInternal(request, response, chain);

                // Then
                assertThat(logCaptor.getInfoLogs())
                        .anyMatch(log -> log.contains("POST /api/auth/login"))
                        .anyMatch(log -> log.contains("-> 200"))
                        .anyMatch(log -> log.contains("ms"));
            }
        }

        @Test
        @DisplayName("should log error response as warning")
        void shouldLogErrorResponseAsWarning() throws Exception {
            try (LogCaptor logCaptor = LogCaptor.forClass(RequestLoggingFilter.class)) {
                // Given
                HttpServletRequest request = mockRequest("GET", "/api/auth/me", null);
                HttpServletResponse response = mockResponse(401);
                FilterChain chain = mock(FilterChain.class);

                // When
                filter.doFilterInternal(request, response, chain);

                // Then - 4xx/5xx should be logged as warnings
                assertThat(logCaptor.getWarnLogs())
                        .anyMatch(log -> log.contains("-> 401"));
            }
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private HttpServletRequest mockRequest(String method, String uri, String queryString) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getQueryString()).thenReturn(queryString);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("JUnit Test");
        return request;
    }

    private HttpServletResponse mockResponse(int status) {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getStatus()).thenReturn(status);
        return response;
    }
}
