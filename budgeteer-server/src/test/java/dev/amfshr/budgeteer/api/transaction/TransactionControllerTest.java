package dev.amfshr.budgeteer.api.transaction;

import dev.amfshr.budgeteer.api.common.GlobalExceptionHandler;
import dev.amfshr.budgeteer.api.common.PageResponse;
import dev.amfshr.budgeteer.config.SecurityConfig;
import dev.amfshr.budgeteer.config.WebMvcConfig;
import dev.amfshr.budgeteer.security.CurrentUserArgumentResolver;
import dev.amfshr.budgeteer.security.JweAuthenticationFilter.JweAuthentication;
import dev.amfshr.budgeteer.service.auth.AuthService;
import dev.amfshr.budgeteer.service.auth.JweTokenService;
import dev.amfshr.budgeteer.service.common.CookieService;
import dev.amfshr.budgeteer.service.transaction.TransactionQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebMvcConfig.class,
        CurrentUserArgumentResolver.class})
@DisplayName("TransactionController")
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionQueryService transactionQueryService;

    // Required by SecurityConfig → JweAuthenticationFilter and CurrentUserArgumentResolver
    @MockitoBean
    private JweTokenService jweTokenService;

    @MockitoBean
    private CookieService cookieService;

    @MockitoBean
    private AuthService authService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        when(transactionQueryService.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0));
    }

    @Test
    @DisplayName("GET /api/v1/transactions rejects unauthenticated requests (403, house-wide default)")
    void listUnauthenticated403() throws Exception {
        mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("returns the envelope with paging metadata and defaults page=0 size=50")
    void returnsEnvelopeWithDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/transactions").with(authenticatedAs(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50));

        verify(transactionQueryService).list(userId, null, null, null, 0, 50);
    }

    @Test
    @DisplayName("size above 200 returns 400")
    void sizeTooLarge400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("size", "201")
                        .with(authenticatedAs(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("negative page returns 400")
    void negativePage400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("page", "-1")
                        .with(authenticatedAs(userId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ISO-8601 instants bind natively on from/to")
    void isoInstantsBind() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-02-01T00:00:00Z")
                        .with(authenticatedAs(userId)))
                .andExpect(status().isOk());

        verify(transactionQueryService).list(userId, null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"), 0, 50);
    }

    @Test
    @DisplayName("malformed instant returns 400")
    void malformedInstant400() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("from", "yesterday")
                        .with(authenticatedAs(userId)))
                .andExpect(status().isBadRequest());
    }

    private RequestPostProcessor authenticatedAs(UUID uid) {
        JweTokenService.TokenClaims claims = new JweTokenService.TokenClaims(
                uid, "test@example.com", Instant.now(),
                Instant.now().plusSeconds(3600), UUID.randomUUID().toString());
        return authentication(new JweAuthentication(claims));
    }
}
