package dev.amf.budgeteer.api.monzo;

import dev.amf.budgeteer.api.common.GlobalExceptionHandler;
import dev.amf.budgeteer.config.MonzoProperties;
import dev.amf.budgeteer.config.SecurityConfig;
import dev.amf.budgeteer.service.CookieService;
import dev.amf.budgeteer.service.JweTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link MonzoOAuthController}.
 * 
 * <p>Tests the Monzo OAuth flow endpoints. Note that the actual token exchange
 * with Monzo API is not tested here - that would be an integration test.
 */
@WebMvcTest(MonzoOAuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("MonzoOAuthController")
class MonzoOAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonzoProperties monzoProperties;

    // Required by SecurityConfig
    @MockitoBean
    private JweTokenService jweTokenService;

    @MockitoBean
    private CookieService cookieService;

    @Nested
    @DisplayName("GET /api/monzo/oauth/connect")
    class Connect {

        @Test
        @DisplayName("should redirect to Monzo OAuth URL with correct params")
        void shouldRedirectToMonzoOAuth() throws Exception {
            // Given
            when(monzoProperties.authUrl()).thenReturn("https://auth.monzo.com");
            when(monzoProperties.clientId()).thenReturn("test-client-id");
            when(monzoProperties.redirectUri()).thenReturn("http://localhost:8080/api/monzo/oauth/callback");

            // When/Then
            mockMvc.perform(get("/api/monzo/oauth/connect"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().exists("Location"))
                    .andExpect(header().string("Location", 
                            org.hamcrest.Matchers.containsString("https://auth.monzo.com")))
                    .andExpect(header().string("Location",
                            org.hamcrest.Matchers.containsString("client_id=test-client-id")))
                    .andExpect(header().string("Location",
                            org.hamcrest.Matchers.containsString("response_type=code")))
                    .andExpect(header().string("Location",
                            org.hamcrest.Matchers.containsString("state=")));
        }
    }

    @Nested
    @DisplayName("GET /api/monzo/oauth/callback")
    class Callback {

        @Test
        @DisplayName("should return error for state mismatch")
        void shouldRejectStateMismatch() throws Exception {
            // Given - state will not match because we haven't initiated OAuth flow
            
            // When/Then
            mockMvc.perform(get("/api/monzo/oauth/callback")
                            .param("code", "auth-code-123")
                            .param("state", "invalid-state"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("STATE_MISMATCH"))
                    .andExpect(jsonPath("$.error.message").value("State parameter doesn't match. Possible CSRF attack."));
        }

        @Test
        @DisplayName("should return 400 for missing code parameter")
        void shouldReturn400ForMissingCode() throws Exception {
            mockMvc.perform(get("/api/monzo/oauth/callback")
                            .param("state", "some-state"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("should return 400 for missing state parameter")
        void shouldReturn400ForMissingState() throws Exception {
            mockMvc.perform(get("/api/monzo/oauth/callback")
                            .param("code", "auth-code"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    @Nested
    @DisplayName("GET /api/monzo/oauth/status")
    class Status {

        @Test
        @DisplayName("should return disconnected status initially")
        void shouldReturnDisconnectedStatusInitially() throws Exception {
            // When/Then - no tokens stored yet
            mockMvc.perform(get("/api/monzo/oauth/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.connected").value(false))
                    .andExpect(jsonPath("$.data.hasRefreshToken").value(false));
        }
    }
}
