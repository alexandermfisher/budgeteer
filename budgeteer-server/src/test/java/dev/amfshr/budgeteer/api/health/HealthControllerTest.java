package dev.amfshr.budgeteer.api.health;

import dev.amfshr.budgeteer.api.common.GlobalExceptionHandler;
import dev.amfshr.budgeteer.config.SecurityConfig;
import dev.amfshr.budgeteer.service.auth.AuthService;
import dev.amfshr.budgeteer.service.common.CookieService;
import dev.amfshr.budgeteer.service.auth.JweTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link HealthController}.
 * 
 * <p>Tests health check endpoints which are public (no auth required).
 */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("HealthController")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataSource dataSource;

    // Required by SecurityConfig and CurrentUserArgumentResolver
    @MockitoBean
    private JweTokenService jweTokenService;

    @MockitoBean
    private CookieService cookieService;

    @MockitoBean
    private AuthService authService;

    @Nested
    @DisplayName("GET /api/health")
    class Health {

        @Test
        @DisplayName("should return UP status with profile and timestamp")
        void shouldReturnUpStatus() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("UP"))
                    .andExpect(jsonPath("$.data.profile").exists())
                    .andExpect(jsonPath("$.data.timestamp").exists());
        }
    }

    @Nested
    @DisplayName("GET /api/health/live")
    class Live {

        @Test
        @DisplayName("should return OK for liveness probe")
        void shouldReturnOkForLiveness() throws Exception {
            mockMvc.perform(get("/api/health/live"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("OK"));
        }
    }

    @Nested
    @DisplayName("GET /api/health/ready")
    class Ready {

        @Test
        @DisplayName("should return UP when database is healthy")
        void shouldReturnUpWhenDbHealthy() throws Exception {
            // Given - mock healthy database connection
            Connection mockConnection = mock(Connection.class);
            when(mockConnection.isValid(anyInt())).thenReturn(true);
            when(dataSource.getConnection()).thenReturn(mockConnection);

            // When/Then
            mockMvc.perform(get("/api/health/ready"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("UP"))
                    .andExpect(jsonPath("$.data.database.status").value("UP"))
                    .andExpect(jsonPath("$.data.database.error").isEmpty());
        }

        @Test
        @DisplayName("should return 503 when database is down")
        void shouldReturn503WhenDbDown() throws Exception {
            // Given - mock failed database connection
            when(dataSource.getConnection()).thenThrow(new RuntimeException("Connection refused"));

            // When/Then
            mockMvc.perform(get("/api/health/ready"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.data.status").value("DOWN"))
                    .andExpect(jsonPath("$.data.database.status").value("DOWN"));
        }

        @Test
        @DisplayName("should return 503 when database connection is invalid")
        void shouldReturn503WhenConnectionInvalid() throws Exception {
            // Given - mock connection that fails validation
            Connection mockConnection = mock(Connection.class);
            when(mockConnection.isValid(anyInt())).thenReturn(false);
            when(dataSource.getConnection()).thenReturn(mockConnection);

            // When/Then
            mockMvc.perform(get("/api/health/ready"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.data.status").value("DOWN"))
                    .andExpect(jsonPath("$.data.database.status").value("DOWN"));
        }
    }
}
