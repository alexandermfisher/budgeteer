package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.bank.BankAccount;
import dev.amfshr.budgeteer.bank.BankClient;
import dev.amfshr.budgeteer.bank.BankReauthRequiredException;
import dev.amfshr.budgeteer.bank.BankTransaction;
import dev.amfshr.budgeteer.bank.BankTransactionPage;
import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.repository.MonzoTransactionRepository;
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

    @Mock private BankClient bankClient;
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

            BankAccount openAccount = new BankAccount(
                    "acc_open", "uk_retail", "Current", "GBP", false, java.time.Instant.parse("2024-01-01T00:00:00Z"));
            BankAccount closedAccount = new BankAccount(
                    "acc_closed", "uk_retail", "Old Account", "GBP", true, java.time.Instant.parse("2020-01-01T00:00:00Z"));

            when(bankClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(openAccount, closedAccount));

            MonzoAccount savedOpenAccount = mockAccount("acc_open");
            MonzoAccount savedClosedAccount = mockAccount("acc_closed");
            when(accountRepository.findById("acc_open")).thenReturn(Optional.empty());
            when(accountRepository.findById("acc_closed")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(savedOpenAccount, savedClosedAccount, savedOpenAccount);

            // Backfill walks back through ~12 ≤350-day windows from now → 2015-01-01.
            // The first window (most recent) returns our test tx; all older windows return empty.
            BankTransaction tx = makeTx("tx_001");
            when(bankClient.getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_open"), any(Instant.class), any(Instant.class), isNull()))
                    .thenReturn(new BankTransactionPage(List.of(tx), null),
                            new BankTransactionPage(List.of(), null));

            // When
            service.backfill(connectionId);

            // Then — every backfill call must include both `since` AND `before` (windowed range),
            // and `sinceId` must start null for each window.
            verify(bankClient, atLeastOnce())
                    .getTransactions(eq(ACCESS_TOKEN), eq("acc_open"), any(Instant.class), any(Instant.class), isNull());
            verify(bankClient, never())
                    .getTransactions(any(), eq("acc_closed"), any(Instant.class), any(Instant.class), any(String.class));
            verify(transactionRepository, times(1)).upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("paginates when page is full (100 items)")
        void paginatesWhenPageFull() {
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            BankAccount account = new BankAccount(
                    "acc_001", "uk_retail", null, "GBP", false, java.time.Instant.parse("2024-01-01T00:00:00Z"));
            when(bankClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(account));

            MonzoAccount savedAccount = mockAccount("acc_001");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(savedAccount);

            // First page: 100 items (full window — triggers cursor pagination)
            List<BankTransaction> firstPage = makeNTransactions(100, "tx_page1_");
            // Second page: 5 items (last page within same window)
            List<BankTransaction> secondPage = makeNTransactions(5, "tx_page2_");

            // First window: returns 100 (forces cursor pagination). All older windows: empty.
            // Start-of-window calls have a timestamp `since` and a null cursor.
            when(bankClient.getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), any(Instant.class), any(Instant.class), isNull()))
                    .thenReturn(new BankTransactionPage(firstPage, "tx_page1_099"),
                            new BankTransactionPage(List.of(), null));

            // Cursor follow-up within the window: `since` (timestamp) drops to null, the cursor is the
            // last-seen tx id, and `before` stays pinned to the window end (no longer dropped).
            when(bankClient.getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), any(Instant.class), any(Instant.class), eq("tx_page1_099")))
                    .thenReturn(new BankTransactionPage(secondPage, null));

            service.backfill(connectionId);

            // All 105 transactions upserted, cursor follow-up happened exactly once.
            verify(transactionRepository, times(105))
                    .upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
            verify(bankClient, times(1))
                    .getTransactions(eq(ACCESS_TOKEN), eq("acc_001"), any(Instant.class), any(Instant.class), eq("tx_page1_099"));
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

            BankTransaction tx = makeTx("tx_new");
            when(bankClient.getTransactions(eq(ACCESS_TOKEN), eq("acc_001"), any(Instant.class), any(Instant.class), eq("tx_existing_cursor")))
                    .thenReturn(new BankTransactionPage(List.of(tx), null));

            service.deltaSync("acc_001");

            verify(bankClient).getTransactions(eq(ACCESS_TOKEN), eq("acc_001"), any(Instant.class), any(Instant.class), eq("tx_existing_cursor"));
            verify(transactionRepository, times(1)).upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("null cursor on first run fetches recent transactions")
        void nullCursorFetchesRecent() {
            MonzoAccount account = mockAccount("acc_001");
            when(account.getLastTransactionId()).thenReturn(null);
            when(accountRepository.findById("acc_001")).thenReturn(Optional.of(account));
            when(connectionService.getDecryptedAccessToken(any(), any())).thenReturn(ACCESS_TOKEN);

            when(bankClient.getTransactions(eq(ACCESS_TOKEN), eq("acc_001"), any(Instant.class), any(Instant.class), isNull()))
                    .thenReturn(new BankTransactionPage(List.of(), null));

            service.deltaSync("acc_001");

            verify(bankClient).getTransactions(eq(ACCESS_TOKEN), eq("acc_001"), any(Instant.class), any(Instant.class), isNull());
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

            BankAccount ar = new BankAccount(
                    "acc_001", "uk_retail", null, "GBP", false, java.time.Instant.parse("2024-01-01T00:00:00Z"));
            when(bankClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(ar));

            MonzoAccount account = mockAccount("acc_001");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);

            // First window throws verification_required
            when(bankClient.getTransactions(eq(ACCESS_TOKEN), eq("acc_001"),
                    any(Instant.class), any(Instant.class), isNull()))
                    .thenThrow(new BankReauthRequiredException("SCA expired"));

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
            BankAccount ar = new BankAccount(
                    "acc_001", "uk_retail", null, "GBP", false, java.time.Instant.parse("2025-12-01T00:00:00Z"));
            when(bankClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(ar));

            MonzoAccount account = mockAccount("acc_001");
            when(account.getMonzoCreatedAt()).thenReturn(Instant.parse("2025-12-01T00:00:00Z"));
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);

            // All windows return empty
            when(bankClient.getTransactions(any(), any(), any(Instant.class), any(Instant.class), any()))
                    .thenReturn(new BankTransactionPage(List.of(), null));

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

            BankAccount ar = new BankAccount(
                    "acc_001", "uk_retail", null, "GBP", false, java.time.Instant.parse("2025-10-01T00:00:00Z"));
            when(bankClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(ar));

            MonzoAccount account = mockAccount("acc_001");
            when(account.getMonzoCreatedAt()).thenReturn(Instant.parse("2025-10-01T00:00:00Z"));
            when(account.getBackfillProgressAt()).thenReturn(progressAt);
            when(account.getBackfillProgressCursor()).thenReturn(null);
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);
            when(bankClient.getTransactions(any(), any(), any(Instant.class), any(Instant.class), any()))
                    .thenReturn(new BankTransactionPage(List.of(), null));

            service.backfill(connectionId);

            // The `before` on the first call must not exceed progressAt (we resume from there)
            verify(bankClient, atLeastOnce()).getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), any(Instant.class), any(Instant.class), isNull());
        }

        @Test
        @DisplayName("mid-window resume uses backfillProgressCursor as the `since` cursor")
        void midWindowResumeUsesCursor() {
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            Instant progressAt = Instant.parse("2026-01-01T00:00:00Z");
            String savedCursor = "tx_previously_saved";

            BankAccount ar = new BankAccount(
                    "acc_001", "uk_retail", null, "GBP", false, java.time.Instant.parse("2025-12-01T00:00:00Z"));
            when(bankClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(ar));

            MonzoAccount account = mockAccount("acc_001");
            when(account.getMonzoCreatedAt()).thenReturn(Instant.parse("2025-12-01T00:00:00Z"));
            when(account.getBackfillProgressAt()).thenReturn(progressAt);
            when(account.getBackfillProgressCursor()).thenReturn(savedCursor);
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);
            when(bankClient.getTransactions(any(), any(), any(Instant.class), any(Instant.class), any()))
                    .thenReturn(new BankTransactionPage(List.of(), null));

            service.backfill(connectionId);

            // The first call within the resumed window uses the saved cursor (sent via `since`),
            // with `before` pinned to the window end (the resume point) rather than dropped.
            verify(bankClient, atLeastOnce()).getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), any(Instant.class), any(Instant.class),
                    eq(savedCursor));
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

    private BankTransaction makeTx(String id) {
        return new BankTransaction(
                id, -500, "GBP", "Test", null, null, null, false,
                java.time.Instant.parse("2024-01-01T10:00:00Z"),
                java.time.Instant.parse("2024-01-02T00:00:00Z")
        );
    }

    private List<BankTransaction> makeNTransactions(int n, String prefix) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> makeTx(prefix + String.format("%03d", i)))
                .toList();
    }
}
