package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.provider.model.BankAccount;
import dev.amfshr.budgeteer.provider.AccountsCapability;
import dev.amfshr.budgeteer.provider.TransactionsCapability;
import dev.amfshr.budgeteer.provider.exception.ProviderReauthRequiredException;
import dev.amfshr.budgeteer.provider.model.BankTransaction;
import dev.amfshr.budgeteer.provider.model.BankTransactionPage;
import dev.amfshr.budgeteer.provider.model.Sourced;
import dev.amfshr.budgeteer.provider.model.SyncPosition;
import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.repository.MonzoTransactionRepository;
import dev.amfshr.budgeteer.service.common.EncryptionService;
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

    @Mock private AccountsCapability accountsCapability;
    @Mock private TransactionsCapability transactionsCapability;
    @Mock private MonzoConnectionService connectionService;
    @Mock private MonzoConnectionRepository connectionRepository;
    @Mock private MonzoAccountRepository accountRepository;
    @Mock private MonzoTransactionRepository transactionRepository;
    @Mock private EncryptionService encryptionService;
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
                    "acc_open", "uk_retail", "Current", "GBP", false, Instant.parse("2024-01-01T00:00:00Z"));
            BankAccount closedAccount = new BankAccount(
                    "acc_closed", "uk_retail", "Old Account", "GBP", true, Instant.parse("2020-01-01T00:00:00Z"));

            when(accountsCapability.getAccounts(ACCESS_TOKEN))
                    .thenReturn(List.of(new Sourced<>(openAccount, null), new Sourced<>(closedAccount, null)));

            MonzoAccount savedOpenAccount = mockAccount("acc_open");
            MonzoAccount savedClosedAccount = mockAccount("acc_closed");
            when(accountRepository.findById("acc_open")).thenReturn(Optional.empty());
            when(accountRepository.findById("acc_closed")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(savedOpenAccount, savedClosedAccount, savedOpenAccount);

            // Backfill walks back through ~12 ≤350-day windows from now → 2015-01-01.
            // The first window (most recent) returns our test tx; all older windows return empty.
            Sourced<BankTransaction> tx = makeTx("tx_001");
            when(transactionsCapability.getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_open"), any(SyncPosition.FromTime.class), any(Instant.class)))
                    .thenReturn(new BankTransactionPage(List.of(tx), null),
                            new BankTransactionPage(List.of(), null));

            // When
            service.backfill(connectionId);

            // Then — every window opens with a FromTime position; the closed account is never fetched.
            verify(transactionsCapability, atLeastOnce())
                    .getTransactions(eq(ACCESS_TOKEN), eq("acc_open"), any(SyncPosition.FromTime.class), any(Instant.class));
            verify(transactionsCapability, never())
                    .getTransactions(any(), eq("acc_closed"), any(SyncPosition.class), any(Instant.class));
            verify(transactionRepository, times(1)).upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        }

        @Test
        @DisplayName("paginates when page is full (100 items)")
        void paginatesWhenPageFull() {
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            BankAccount account = new BankAccount(
                    "acc_001", "uk_retail", null, "GBP", false, Instant.parse("2024-01-01T00:00:00Z"));
            when(accountsCapability.getAccounts(ACCESS_TOKEN))
                    .thenReturn(List.of(new Sourced<>(account, null)));

            MonzoAccount savedAccount = mockAccount("acc_001");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(savedAccount);

            // First page: 100 items (full window — triggers cursor pagination)
            List<Sourced<BankTransaction>> firstPage = makeNTransactions(100, "tx_page1_");
            // Second page: 5 items (last page within same window)
            List<Sourced<BankTransaction>> secondPage = makeNTransactions(5, "tx_page2_");

            // First window: returns 100 (forces cursor pagination). All older windows: empty.
            // Start-of-window calls use a FromTime position.
            when(transactionsCapability.getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), any(SyncPosition.FromTime.class), any(Instant.class)))
                    .thenReturn(new BankTransactionPage(firstPage, "tx_page1_099"),
                            new BankTransactionPage(List.of(), null));

            // Cursor follow-up within the window replays the cursor as a NextPage position,
            // with `before` staying pinned to the window end.
            when(transactionsCapability.getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), eq(new SyncPosition.NextPage("tx_page1_099")), any(Instant.class)))
                    .thenReturn(new BankTransactionPage(secondPage, null));

            service.backfill(connectionId);

            // All 105 transactions upserted, cursor follow-up happened exactly once.
            verify(transactionRepository, times(105))
                    .upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
            verify(transactionsCapability, times(1))
                    .getTransactions(eq(ACCESS_TOKEN), eq("acc_001"), eq(new SyncPosition.NextPage("tx_page1_099")), any(Instant.class));
        }
    }

    @Nested
    @DisplayName("deltaSync")
    class DeltaSync {

        @Test
        @DisplayName("uses AfterTransaction position with the stored lastTransactionId")
        void usesAfterTransactionPosition() {
            MonzoAccount account = mockAccount("acc_001");
            when(account.getLastTransactionId()).thenReturn("tx_last_synced");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.of(account));
            when(connectionService.getDecryptedAccessToken(any(), any())).thenReturn(ACCESS_TOKEN);

            Sourced<BankTransaction> tx = makeTx("tx_new");
            when(transactionsCapability.getTransactions(eq(ACCESS_TOKEN), eq("acc_001"),
                    eq(new SyncPosition.AfterTransaction("tx_last_synced")), any(Instant.class)))
                    .thenReturn(new BankTransactionPage(List.of(tx), null));

            service.deltaSync("acc_001");

            verify(transactionsCapability).getTransactions(eq(ACCESS_TOKEN), eq("acc_001"),
                    eq(new SyncPosition.AfterTransaction("tx_last_synced")), any(Instant.class));
            verify(transactionRepository, times(1)).upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        }

        @Test
        @DisplayName("no stored lastTransactionId falls back to a FromTime fetch")
        void noStoredIdFallsBackToFromTime() {
            MonzoAccount account = mockAccount("acc_001");
            when(account.getLastTransactionId()).thenReturn(null);
            when(accountRepository.findById("acc_001")).thenReturn(Optional.of(account));
            when(connectionService.getDecryptedAccessToken(any(), any())).thenReturn(ACCESS_TOKEN);

            when(transactionsCapability.getTransactions(eq(ACCESS_TOKEN), eq("acc_001"),
                    any(SyncPosition.FromTime.class), any(Instant.class)))
                    .thenReturn(new BankTransactionPage(List.of(), null));

            service.deltaSync("acc_001");

            verify(transactionsCapability).getTransactions(eq(ACCESS_TOKEN), eq("acc_001"),
                    any(SyncPosition.FromTime.class), any(Instant.class));
        }
    }

    @Nested
    @DisplayName("raw capture")
    class RawCapture {

        @Test
        @DisplayName("persists the encrypted raw payload on transaction upsert")
        void persistsEncryptedRawPayload() {
            MonzoAccount account = mockAccount("acc_001");
            when(account.getLastTransactionId()).thenReturn("tx_last");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.of(account));
            when(connectionService.getDecryptedAccessToken(any(), any())).thenReturn(ACCESS_TOKEN);

            String rawJson = "{\"id\":\"tx_new\",\"account_number\":\"12345678\"}";
            Sourced<BankTransaction> tx = makeTx("tx_new", rawJson);
            when(transactionsCapability.getTransactions(any(), any(), any(SyncPosition.class), any(Instant.class)))
                    .thenReturn(new BankTransactionPage(List.of(tx), null));
            when(encryptionService.encrypt(rawJson)).thenReturn("ciphertext");

            service.deltaSync("acc_001");

            verify(encryptionService).encrypt(rawJson);
            verify(transactionRepository).upsert(eq("tx_new"), any(), any(), anyInt(), any(), any(),
                    any(), any(), any(), anyBoolean(), any(), any(), eq("ciphertext"));
        }

        @Test
        @DisplayName("null rawJson stores NULL and never calls encrypt")
        void nullRawJson_storesNull_noEncryptCall() {
            MonzoAccount account = mockAccount("acc_001");
            when(account.getLastTransactionId()).thenReturn("tx_last");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.of(account));
            when(connectionService.getDecryptedAccessToken(any(), any())).thenReturn(ACCESS_TOKEN);

            when(transactionsCapability.getTransactions(any(), any(), any(SyncPosition.class), any(Instant.class)))
                    .thenReturn(new BankTransactionPage(List.of(makeTx("tx_new")), null));

            service.deltaSync("acc_001");

            verify(encryptionService, never()).encrypt(any());
            verify(transactionRepository).upsert(eq("tx_new"), any(), any(), anyInt(), any(), any(),
                    any(), any(), any(), anyBoolean(), any(), any(), isNull());
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
                    "acc_001", "uk_retail", null, "GBP", false, Instant.parse("2024-01-01T00:00:00Z"));
            when(accountsCapability.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(new Sourced<>(ar, null)));

            MonzoAccount account = mockAccount("acc_001");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);

            // First window throws verification_required
            when(transactionsCapability.getTransactions(eq(ACCESS_TOKEN), eq("acc_001"),
                    any(SyncPosition.class), any(Instant.class)))
                    .thenThrow(new ProviderReauthRequiredException("SCA expired"));

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
                    "acc_001", "uk_retail", null, "GBP", false, Instant.parse("2025-12-01T00:00:00Z"));
            when(accountsCapability.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(new Sourced<>(ar, null)));

            MonzoAccount account = mockAccount("acc_001");
            when(account.getMonzoCreatedAt()).thenReturn(Instant.parse("2025-12-01T00:00:00Z"));
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);

            // All windows return empty
            when(transactionsCapability.getTransactions(any(), any(), any(SyncPosition.class), any(Instant.class)))
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
                    "acc_001", "uk_retail", null, "GBP", false, Instant.parse("2025-10-01T00:00:00Z"));
            when(accountsCapability.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(new Sourced<>(ar, null)));

            MonzoAccount account = mockAccount("acc_001");
            when(account.getMonzoCreatedAt()).thenReturn(Instant.parse("2025-10-01T00:00:00Z"));
            when(account.getBackfillProgressAt()).thenReturn(progressAt);
            when(account.getBackfillProgressCursor()).thenReturn(null);
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);
            when(transactionsCapability.getTransactions(any(), any(), any(SyncPosition.class), any(Instant.class)))
                    .thenReturn(new BankTransactionPage(List.of(), null));

            service.backfill(connectionId);

            // The resumed window opens with a FromTime position (no saved cursor)
            verify(transactionsCapability, atLeastOnce()).getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), any(SyncPosition.FromTime.class), any(Instant.class));
        }

        @Test
        @DisplayName("mid-window resume replays backfillProgressCursor as a NextPage position")
        void midWindowResumeUsesCursor() {
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            Instant progressAt = Instant.parse("2026-01-01T00:00:00Z");
            String savedCursor = "tx_previously_saved";

            BankAccount ar = new BankAccount(
                    "acc_001", "uk_retail", null, "GBP", false, Instant.parse("2025-12-01T00:00:00Z"));
            when(accountsCapability.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(new Sourced<>(ar, null)));

            MonzoAccount account = mockAccount("acc_001");
            when(account.getMonzoCreatedAt()).thenReturn(Instant.parse("2025-12-01T00:00:00Z"));
            when(account.getBackfillProgressAt()).thenReturn(progressAt);
            when(account.getBackfillProgressCursor()).thenReturn(savedCursor);
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(account);
            when(transactionsCapability.getTransactions(any(), any(), any(SyncPosition.class), any(Instant.class)))
                    .thenReturn(new BankTransactionPage(List.of(), null));

            service.backfill(connectionId);

            // The first call within the resumed window replays the saved cursor as NextPage,
            // with `before` pinned to the window end (the resume point) rather than dropped.
            verify(transactionsCapability, atLeastOnce()).getTransactions(
                    eq(ACCESS_TOKEN), eq("acc_001"), eq(new SyncPosition.NextPage(savedCursor)), any(Instant.class));
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

    private Sourced<BankTransaction> makeTx(String id) {
        return makeTx(id, null);
    }

    private Sourced<BankTransaction> makeTx(String id, @org.jspecify.annotations.Nullable String rawJson) {
        return new Sourced<>(new BankTransaction(
                id, -500, "GBP", "Test", null, null, null, false,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z")
        ), rawJson);
    }

    private List<Sourced<BankTransaction>> makeNTransactions(int n, String prefix) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> makeTx(prefix + String.format("%03d", i)))
                .toList();
    }
}
