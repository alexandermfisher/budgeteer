package dev.amf.budgeteer.service.monzo;

import dev.amf.budgeteer.client.monzo.MonzoClient;
import dev.amf.budgeteer.client.monzo.dto.MonzoAccountResponse;
import dev.amf.budgeteer.client.monzo.dto.MonzoTransactionResponse;
import dev.amf.budgeteer.domain.monzo.MonzoAccount;
import dev.amf.budgeteer.domain.monzo.MonzoConnection;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.repository.MonzoAccountRepository;
import dev.amf.budgeteer.repository.MonzoConnectionRepository;
import dev.amf.budgeteer.repository.MonzoTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
                    "acc_open", "uk_retail", "Current", "GBP", false);
            MonzoAccountResponse closedAccount = new MonzoAccountResponse(
                    "acc_closed", "uk_retail", "Old Account", "GBP", true);

            when(monzoClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(openAccount, closedAccount));

            MonzoAccount savedOpenAccount = mockAccount("acc_open");
            MonzoAccount savedClosedAccount = mockAccount("acc_closed");
            when(accountRepository.findById("acc_open")).thenReturn(Optional.empty());
            when(accountRepository.findById("acc_closed")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(savedOpenAccount, savedClosedAccount, savedOpenAccount);

            MonzoTransactionResponse tx = makeTx("tx_001");
            when(monzoClient.getTransactions(ACCESS_TOKEN, "acc_open", null, 100))
                    .thenReturn(List.of(tx));

            // When
            service.backfill(connectionId);

            // Then
            verify(monzoClient, times(1)).getTransactions(any(), eq("acc_open"), any(), anyInt());
            verify(monzoClient, never()).getTransactions(any(), eq("acc_closed"), any(), anyInt());
            verify(transactionRepository, times(1)).upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("paginates when page is full (100 items)")
        void paginatesWhenPageFull() {
            when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
            when(connectionService.getDecryptedAccessToken(connectionId, userId)).thenReturn(ACCESS_TOKEN);

            MonzoAccountResponse account = new MonzoAccountResponse("acc_001", "uk_retail", null, "GBP", false);
            when(monzoClient.getAccounts(ACCESS_TOKEN)).thenReturn(List.of(account));

            MonzoAccount savedAccount = mockAccount("acc_001");
            when(accountRepository.findById("acc_001")).thenReturn(Optional.empty());
            when(accountRepository.save(any())).thenReturn(savedAccount);

            // First page: 100 items
            List<MonzoTransactionResponse> firstPage = makeNTransactions(100, "tx_page1_");
            // Second page: 5 items (last page)
            List<MonzoTransactionResponse> secondPage = makeNTransactions(5, "tx_page2_");

            when(monzoClient.getTransactions(ACCESS_TOKEN, "acc_001", null, 100))
                    .thenReturn(firstPage);
            when(monzoClient.getTransactions(ACCESS_TOKEN, "acc_001", "tx_page1_099", 100))
                    .thenReturn(secondPage);

            service.backfill(connectionId);

            verify(monzoClient, times(2)).getTransactions(any(), any(), any(), anyInt());
            verify(transactionRepository, times(105)).upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
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
            when(monzoClient.getTransactions(ACCESS_TOKEN, "acc_001", "tx_existing_cursor", 100))
                    .thenReturn(List.of(tx));

            service.deltaSync("acc_001");

            verify(monzoClient).getTransactions(ACCESS_TOKEN, "acc_001", "tx_existing_cursor", 100);
            verify(transactionRepository, times(1)).upsert(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
        }

        @Test
        @DisplayName("null cursor on first run fetches recent transactions")
        void nullCursorFetchesRecent() {
            MonzoAccount account = mockAccount("acc_001");
            when(account.getLastTransactionId()).thenReturn(null);
            when(accountRepository.findById("acc_001")).thenReturn(Optional.of(account));
            when(connectionService.getDecryptedAccessToken(any(), any())).thenReturn(ACCESS_TOKEN);

            when(monzoClient.getTransactions(ACCESS_TOKEN, "acc_001", null, 100))
                    .thenReturn(List.of());

            service.deltaSync("acc_001");

            verify(monzoClient).getTransactions(ACCESS_TOKEN, "acc_001", null, 100);
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
