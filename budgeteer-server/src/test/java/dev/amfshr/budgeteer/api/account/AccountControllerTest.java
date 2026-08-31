package dev.amfshr.budgeteer.api.account;

import dev.amfshr.budgeteer.api.account.dto.AccountResponse;
import dev.amfshr.budgeteer.api.account.dto.AccountSummaryResponse;
import dev.amfshr.budgeteer.api.common.GlobalExceptionHandler;
import dev.amfshr.budgeteer.config.SecurityConfig;
import dev.amfshr.budgeteer.config.WebMvcConfig;
import dev.amfshr.budgeteer.security.CurrentUserArgumentResolver;
import dev.amfshr.budgeteer.security.JweAuthenticationFilter.JweAuthentication;
import dev.amfshr.budgeteer.service.account.AccountService;
import dev.amfshr.budgeteer.service.auth.AuthService;
import dev.amfshr.budgeteer.service.auth.JweTokenService;
import dev.amfshr.budgeteer.service.common.CookieService;
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
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebMvcConfig.class,
        CurrentUserArgumentResolver.class})
@DisplayName("AccountController")
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

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
    }

    @Test
    @DisplayName("GET /api/v1/accounts rejects unauthenticated requests (403, house-wide default)")
    void listUnauthenticated403() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/accounts returns the envelope with account data")
    void listReturnsEnvelope() throws Exception {
        AccountResponse account = new AccountResponse(
                UUID.randomUUID(), "MONZO", "CURRENT", "Monzo", "Current", "GBP",
                12345L, Instant.parse("2026-08-31T10:00:00Z"), null, 0, false);
        when(accountService.listAccounts(userId, false)).thenReturn(List.of(account));

        mockMvc.perform(get("/api/v1/accounts").with(authenticatedAs(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].provider").value("MONZO"))
                .andExpect(jsonPath("$.data[0].balanceMinorUnits").value(12345));
    }

    @Test
    @DisplayName("includeArchived=true is passed through to the service")
    void includeArchivedPassedThrough() throws Exception {
        when(accountService.listAccounts(userId, true)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/accounts")
                        .param("includeArchived", "true")
                        .with(authenticatedAs(userId)))
                .andExpect(status().isOk());

        verify(accountService).listAccounts(userId, true);
    }

    @Test
    @DisplayName("GET /accounts/{id}/summary defaults the zone to Europe/London")
    void summaryDefaultsZone() throws Exception {
        UUID accountId = UUID.randomUUID();
        AccountSummaryResponse summary = new AccountSummaryResponse(accountId, "Europe/London",
                new AccountSummaryResponse.WindowSums(0, 0),
                new AccountSummaryResponse.WindowSums(0, 0),
                new AccountSummaryResponse.WindowSums(0, 0));
        when(accountService.getSummary(eq(userId), eq(accountId), any(ZoneId.class))).thenReturn(summary);

        mockMvc.perform(get("/api/v1/accounts/{id}/summary", accountId).with(authenticatedAs(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.zone").value("Europe/London"));

        verify(accountService).getSummary(userId, accountId, ZoneId.of("Europe/London"));
    }

    @Test
    @DisplayName("bad zone returns 400 VALIDATION_ERROR")
    void badZone400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}/summary", UUID.randomUUID())
                        .param("zone", "Mars/Olympus_Mons")
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
