package dev.amf.budgeteer.service.monzo;

import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.client.monzo.MonzoClient;
import dev.amf.budgeteer.client.monzo.dto.MonzoAccountResponse;
import dev.amf.budgeteer.client.monzo.dto.MonzoTransactionResponse;
import dev.amf.budgeteer.domain.monzo.MonzoAccount;
import dev.amf.budgeteer.domain.monzo.MonzoConnection;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.exception.ApiException;
import dev.amf.budgeteer.repository.MonzoAccountRepository;
import dev.amf.budgeteer.repository.MonzoConnectionRepository;
import dev.amf.budgeteer.repository.MonzoTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TransactionSyncService")
class TransactionSyncServiceTest {

    @Mock private MonzoClient monzoClient;
    @Mock private MonzoConnectionService connectionService;
    @Mock private MonzoConnectionRepository connectionRepository;
    @Mock private MonzoAccountRepository accountRepository;
    @Mock private MonzoTransactionRepository transactionRepository;
    @Mock private PlatformTransactionManager txManager;

    @InjectMocks
    private TransactionSyncService service;

    private User user;
    private MonzoConnection connection;
    private UUID connectionId;
    private UUID userId;
    private static final String ACCESS_TOKEN = "test-access-token";

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        connectionId = UUID.randomUUID();
        user = mock(User.class);
        connection = mock(MonzoConnection.class);

        when(user.getId()).thenReturn(userId);
        when(connection.getId()).thenReturn(connectionId);
        when(connection.getUser()).thenReturn(user);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Nested
    @DisplayName("backfill")
    class Backfill {

        @Test
        @DisplayName("syncs two accounts, skips closed account transactions")
        void syncsTwoAccounts() {
            // Given
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            MonzoAccountResponse openAccount = new MonzoAccountResponse(
                    "acc_open", "uk_retail", "Current", "GBP", false, "2024-01-01T00:00:00Z");
            MonzoAccountResponse closedAccount = new MonzoAccountResponse(
                    "acc_closed", "uk_retail", "Old Account", "GBP", true, "2020-01-01T00:00:00Z");

            when(monzoClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(openAccount, closedAccount));

            MonzoAccount savedOpenAccount = mockAccount("acc_open");
            MonzoAccount savedClosedAccount = mockAccount("acc_closed");
            when(accountRepository.findById("acc_open")).thenReturn(Optional.empty());
            when(accountRepository.findById("acc_closed")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(savedOpenAccount, savedClosedAccount, savedOpenAccount);

            // Backfill walks back through ~12 ≤350-day windows from now → 2015-01-01.
            // The first window (most recent) returns our test tx; all older windows return empty.
            MonzoTransactionResponse tx = makeTx("tx_001");
            when(monzoClient.getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_open"), anyString(), anyString(), isNull(), eq(100)))
                    .thenReturn(List.of(tx), List.of());

            // When
            service.backfill(connectionId);

            // Then — every backfill call must include both `since` AND `before` (windowed range),
            // and `sinceId` must start null for each window.
            verify(monzoClient, atLeastOnce())
                    .getTransactions(eq(ACCESS_TOKEN), eq("acc_open"), anyString(), anyString(), isNull(), eq(100));
            verify(monzoClient, never())
                    .getTransactions(any(), eq("acc_closed"), any(), any(), any(), anyInt());
            verify(transactionRepository, times(1)).upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("paginates when page is full (100 items)")
        void paginatesWhenPageFull() {
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            MonzoAccountResponse account = new MonzoAccountResponse(
                    "acc_001", "uk_retail", null, "GBP", false, "2024-01-01T00:00:00Z");
            when(monzoClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(account));

            MonzoAccount savedAccount = mockAccount("acc_001");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(savedAccount);

            // First page: 100 items (full window — triggers cursor pagination)
            List<MonzoTransactionResponse> firstPage = makeNTransactions(100, "tx_page1_");
            // Second page: 5 items (last page within same window)
            List<MonzoTransactionResponse> secondPage = makeNTransactions(5, "tx_page2_");

            // First window: returns 100 (forces cursor pagination). All older windows: empty.
            // Start-of-window calls have a timestamp `since` and a null cursor.
            when(monzoClient.getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), anyString(), anyString(), isNull(), eq(100)))
                    .thenReturn(firstPage, List.of());

            // Cursor follow-up within the window: `since` (timestamp) drops to null, the cursor is the
            // last-seen tx id, and `before` stays pinned to the window end (no longer dropped).
            when(monzoClient.getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), isNull(), anyString(), eq("tx_page1_099"), eq(100)))
                    .thenReturn(secondPage);

            service.backfill(connectionId);

            // All 105 transactions upserted, cursor follow-up happened exactly once.
            verify(transactionRepository, times(105))
                    .upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
            verify(monzoClient, times(1))
                    .getTransactions(eq(ACCESS_TOKEN), eq("acc_001"), isNull(), anyString(), eq("tx_page1_099"), eq(100));
        }
    }

    @Nested
    @DisplayName("deltaSync")
    class DeltaSync {

        @Test
        @DisplayName("uses lastTransactionId as cursor")
        void usesCursor() {
            MonzoAccount account = mockAccount("acc_001");
            when(account.getLastTransactionId()).thenReturn("tx_existing_cursor");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.of(account));
            when(connectionService.getDecryptedAccessToken(any(), any())).thenReturn(ACCESS_TOKEN);

            MonzoTransactionResponse tx = makeTx("tx_new");
            when(monzoClient.getTransactions(ACCESS_TOKEN, "acc_001", null, null, "tx_existing_cursor", 100))
                    .thenReturn(List.of(tx));

            service.deltaSync("acc_001");

            verify(monzoClient).getTransactions(ACCESS_TOKEN, "acc_001", null, null, "tx_existing_cursor", 100);
            verify(transactionRepository, times(1)).upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("null cursor on first run fetches recent transactions")
        void nullCursorFetchesRecent() {
            MonzoAccount account = mockAccount("acc_001");
            when(account.getLastTransactionId()).thenReturn(null);
            when(accountRepository.findById("acc_001")).thenReturn(Optional.of(account));
            when(connectionService.getDecryptedAccessToken(any(), any())).thenReturn(ACCESS_TOKEN);

            when(monzoClient.getTransactions(ACCESS_TOKEN, "acc_001", null, null, null, 100))
                    .thenReturn(List.of());

            service.deltaSync("acc_001");

            verify(monzoClient).getTransactions(ACCESS_TOKEN, "acc_001", null, null, null, 100);
        }
    }

    @Nested
    @DisplayName("backfill — resumability")
    class BackfillResumability {

        @Test
        @DisplayName("verification_required mid-window marks account NEEDS_REAUTH and does not rethrow")
        void verificationRequiredMarksNeedsReauth() {
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            MonzoAccountResponse ar = new MonzoAccountResponse(
                    "acc_001", "uk_retail", null, "GBP", false, "2024-01-01T00:00:00Z");
            when(monzoClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(ar));

            MonzoAccount account = mockAccount("acc_001");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);

            // First window throws verification_required
            when(monzoClient.getTransactions(eq(ACCESS_TOKEN), eq("acc_001"),
                    anyString(), anyString(), isNull(), eq(100)))
                    .thenThrow(new ApiException(ErrorCode.MONZO_VERIFICATION_REQUIRED, "SCA expired"));

            // Should not throw
            service.backfill(connectionId);

            ArgumentCaptor<MonzoAccount.BackfillStatus> statusCaptor =
                    ArgumentCaptor.forClass(MonzoAccount.BackfillStatus.class);
            verify(account, atLeastOnce()).setBackfillStatus(statusCaptor.capture());
            assertThat(statusCaptor.getAllValues()).contains(MonzoAccount.BackfillStatus.NEEDS_REAUTH);
        }

        @Test
        @DisplayName("reaching the account floor marks account COMPLETED")
        void reachingFloorMarksCompleted() {
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            // Account opened recently — one short window gets us to the floor
            MonzoAccountResponse ar = new MonzoAccountResponse(
                    "acc_001", "uk_retail", null, "GBP", false, "2025-12-01T00:00:00Z");
            when(monzoClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(ar));

            MonzoAccount account = mockAccount("acc_001");
            when(account.getMonzoCreatedAt()).thenReturn(Instant.parse("2025-12-01T00:00:00Z"));
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);

            // All windows return empty
            when(monzoClient.getTransactions(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of());

            service.backfill(connectionId);

            ArgumentCaptor<MonzoAccount.BackfillStatus> statusCaptor =
                    ArgumentCaptor.forClass(MonzoAccount.BackfillStatus.class);
            verify(account, atLeastOnce()).setBackfillStatus(statusCaptor.capture());
            assertThat(statusCaptor.getAllValues()).contains(MonzoAccount.BackfillStatus.COMPLETED);
        }

        @Test
        @DisplayName("resumes from backfillProgressAt — does not re-fetch later windows")
        void resumesFromProgressAt() {
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            Instant progressAt = Instant.parse("2025-12-01T00:00:00Z");

            MonzoAccountResponse ar = new MonzoAccountResponse(
                    "acc_001", "uk_retail", null, "GBP", false, "2025-10-01T00:00:00Z");
            when(monzoClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(ar));

            MonzoAccount account = mockAccount("acc_001");
            when(account.getMonzoCreatedAt()).thenReturn(Instant.parse("2025-10-01T00:00:00Z"));
            when(account.getBackfillProgressAt()).thenReturn(progressAt);
            when(account.getBackfillProgressCursor()).thenReturn(null);
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);
            when(monzoClient.getTransactions(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of());

            service.backfill(connectionId);

            // The `before` on the first call must not exceed progressAt (we resume from there)
            verify(monzoClient, atLeastOnce()).getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), any(), eq(progressAt.toString()), isNull(), eq(100));
        }

        @Test
        @DisplayName("mid-window resume uses backfillProgressCursor as the `since` cursor")
        void midWindowResumeUsesCursor() {
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            Instant progressAt = Instant.parse("2026-01-01T00:00:00Z");
            String savedCursor = "tx_previously_saved";

            MonzoAccountResponse ar = new MonzoAccountResponse(
                    "acc_001", "uk_retail", null, "GBP", false, "2025-12-01T00:00:00Z");
            when(monzoClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(ar));

            MonzoAccount account = mockAccount("acc_001");
            when(account.getMonzoCreatedAt()).thenReturn(Instant.parse("2025-12-01T00:00:00Z"));
            when(account.getBackfillProgressAt()).thenReturn(progressAt);
            when(account.getBackfillProgressCursor()).thenReturn(savedCursor);
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);
            when(monzoClient.getTransactions(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of());

            service.backfill(connectionId);

            // The first call within the resumed window uses the saved cursor (sent via `since`),
            // with `before` pinned to the window end (the resume point) rather than dropped.
            verify(monzoClient, atLeastOnce()).getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), isNull(), eq(progressAt.toString()),
                    eq(savedCursor), eq(100));
        }
    }

    // ============ Helpers ============

    private MonzoAccount mockAccount(String id) {
        MonzoAccount account = mock(MonzoAccount.class);
        MonzoConnection conn = mock(MonzoConnection.class);
        when(account.getId()).thenReturn(id);
        when(account.getUserId()).thenReturn(userId);
        when(account.getConnection()).thenReturn(conn);
        when(conn.getId()).thenReturn(connectionId);
        return account;
    }

    private MonzoTransactionResponse makeTx(String id) {
        return new MonzoTransactionResponse(
                id, -500, "GBP", "Test", null, null, null,
                "2024-01-01T10:00:00Z", "2024-01-02T00:00:00Z"
        );
    }

    private List<MonzoTransactionResponse> makeNTransactions(int n, String prefix) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> makeTx(prefix + String.format("%03d", i)))
                .toList();
    }
}
