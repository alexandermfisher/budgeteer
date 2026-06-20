package dev.amf.budgeteer.api.dev;

import dev.amf.budgeteer.api.common.GlobalExceptionHandler;
import dev.amf.budgeteer.config.SecurityConfig;
import dev.amf.budgeteer.config.WebMvcConfig;
import dev.amf.budgeteer.domain.monzo.MonzoAccount;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.exception.ApiException;
import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.repository.MonzoAccountRepository;
import dev.amf.budgeteer.repository.MonzoConnectionRepository;
import dev.amf.budgeteer.repository.MonzoTransactionRepository;
import dev.amf.budgeteer.security.CurrentUserArgumentResolver;
import dev.amf.budgeteer.security.JweAuthenticationFilter.JweAuthentication;
import dev.amf.budgeteer.service.auth.AuthService;
import dev.amf.budgeteer.service.auth.JweTokenService;
import dev.amf.budgeteer.service.common.CookieService;
import dev.amf.budgeteer.service.monzo.TransactionSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DevMonzoController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebMvcConfig.class,
        CurrentUserArgumentResolver.class})
@ActiveProfiles("dev")
@DisplayName("DevMonzoController")
class DevMonzoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionSyncService transactionSyncService;

    @MockitoBean
    private MonzoConnectionRepository connectionRepository;

    @MockitoBean
    private MonzoAccountRepository accountRepository;

    @MockitoBean
    private MonzoTransactionRepository transactionRepository;

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

    @Nested
    @DisplayName("POST /api/dev/monzo/backfill")
    class TriggerBackfill {

        @Test
        @DisplayName("should trigger backfill and return 200")
        void shouldTriggerBackfillAndReturn200() throws Exception {
            // Given
            var connection = mockConnection(userId);
            when(connectionRepository.findActiveByUserId(userId)).thenReturn(List.of(connection));
            doNothing().when(transactionSyncService).backfill(any());

            // When/Then
            mockMvc.perform(post("/api/dev/monzo/backfill").with(authenticatedAs(userId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(transactionSyncService).backfill(connection.getId());
        }

        @Test
        @DisplayName("should return 404 when no active Monzo connection")
        void shouldReturn404WhenNoConnection() throws Exception {
            // Given
            when(connectionRepository.findActiveByUserId(userId)).thenReturn(List.of());

            // When/Then
            mockMvc.perform(post("/api/dev/monzo/backfill").with(authenticatedAs(userId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

            verify(transactionSyncService, never()).backfill(any());
        }

        @Test
        @DisplayName("should return 401 when not authenticated")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            mockMvc.perform(post("/api/dev/monzo/backfill"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /api/dev/monzo/reset-backfill/{accountId}")
    class ResetBackfill {

        @Test
        @DisplayName("resets backfill state and deletes transactions")
        void resetsBackfillState() throws Exception {
            MonzoAccount account = mock(MonzoAccount.class);
            when(account.getUserId()).thenReturn(userId);
            when(accountRepository.findById("acc_001")).thenReturn(java.util.Optional.of(account));
            when(accountRepository.save(any())).thenReturn(account);

            mockMvc.perform(post("/api/dev/monzo/reset-backfill/acc_001").with(authenticatedAs(userId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(transactionRepository).deleteByAccountId("acc_001");
            verify(account).setBackfillStatus(null);
            verify(account).setBackfillProgressAt(null);
            verify(account).setBackfillProgressCursor(null);
        }

        @Test
        @DisplayName("returns 404 when account not found")
        void returns404WhenNotFound() throws Exception {
            when(accountRepository.findById("acc_missing")).thenReturn(java.util.Optional.empty());

            mockMvc.perform(post("/api/dev/monzo/reset-backfill/acc_missing").with(authenticatedAs(userId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        @DisplayName("returns 403 when account belongs to a different user")
        void returns403WhenWrongUser() throws Exception {
            MonzoAccount account = mock(MonzoAccount.class);
            when(account.getUserId()).thenReturn(UUID.randomUUID()); // different user
            when(accountRepository.findById("acc_001")).thenReturn(java.util.Optional.of(account));

            mockMvc.perform(post("/api/dev/monzo/reset-backfill/acc_001").with(authenticatedAs(userId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        }
    }

    // ============ Helpers ============

    private RequestPostProcessor authenticatedAs(UUID uid) {
        JweTokenService.TokenClaims claims = new JweTokenService.TokenClaims(
                uid,
                "test@example.com",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                UUID.randomUUID().toString()
        );
        return authentication(new JweAuthentication(claims));
    }

    private dev.amf.budgeteer.domain.monzo.MonzoConnection mockConnection(UUID uid) {
        User user = new User("test@example.com");
        try {
            var f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, uid);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new dev.amf.budgeteer.domain.monzo.MonzoConnection(
                user, "monzo_user_123",
                "enc-access-token", "enc-refresh-token",
                Instant.now().plusSeconds(3600));
    }
}
