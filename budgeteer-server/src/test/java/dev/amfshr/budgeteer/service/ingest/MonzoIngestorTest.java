package dev.amfshr.budgeteer.service.ingest;

import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.account.AccountType;
import dev.amfshr.budgeteer.domain.account.Provider;
import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.monzo.MonzoTransaction;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.repository.AccountRepository;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoTransactionRepository;
import dev.amfshr.budgeteer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MonzoIngestor")
class MonzoIngestorTest {

    @Mock private MonzoAccountRepository monzoAccountRepository;
    @Mock private MonzoTransactionRepository monzoTransactionRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;

    @InjectMocks
    private MonzoIngestor ingestor;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("ingestAccounts")
    class IngestAccounts {

        @Test
        @DisplayName("maps a new raw account with defaults")
        void mapsNewAccountWithDefaults() {
            MonzoAccount raw = rawAccount("acc_1", "uk_retail", false, true);
            when(monzoAccountRepository.findAll()).thenReturn(List.of(raw));
            when(accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, "acc_1"))
                    .thenReturn(Optional.empty());

            List<Account> result = ingestor.ingestAccounts();

            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            Account saved = captor.getValue();
            assertThat(saved.getProvider()).isEqualTo(Provider.MONZO);
            assertThat(saved.getProviderAccountId()).isEqualTo("acc_1");
            assertThat(saved.getAccountType()).isEqualTo(AccountType.CURRENT);
            assertThat(saved.getInstitutionName()).isEqualTo("Monzo");
            assertThat(saved.getDisplayName()).isEqualTo("Current Account");
            assertThat(saved.getCurrency()).isEqualTo("GBP");
            assertThat(saved.isArchived()).isFalse();
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("refreshes account type on re-map (provider-owned)")
        void refreshesAccountTypeOnRemap() {
            MonzoAccount raw = rawAccount("acc_1", "uk_monzo_flex", false, true);
            Account existing = domainAccount("acc_1", AccountType.OTHER);
            when(monzoAccountRepository.findAll()).thenReturn(List.of(raw));
            when(accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, "acc_1"))
                    .thenReturn(Optional.of(existing));

            ingestor.ingestAccounts();

            assertThat(existing.getAccountType()).isEqualTo(AccountType.CREDIT_CARD);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @DisplayName("normalises account types")
        @CsvSource({
                "uk_retail, CURRENT",
                "uk_retail_joint, CURRENT",
                "uk_monzo_flex, CREDIT_CARD",
                "acme_weird_type, OTHER"
        })
        void normalisesAccountTypes(String rawType, AccountType expected) {
            MonzoAccount raw = rawAccount("acc_1", rawType, false, true);
            when(monzoAccountRepository.findAll()).thenReturn(List.of(raw));
            when(accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, "acc_1"))
                    .thenReturn(Optional.empty());

            List<Account> result = ingestor.ingestAccounts();

            assertThat(result.getFirst().getAccountType()).isEqualTo(expected);
        }

        @Test
        @DisplayName("archives a closed raw account")
        void archivesClosedAccount() {
            MonzoAccount raw = rawAccount("acc_1", "uk_retail", true, true);
            when(monzoAccountRepository.findAll()).thenReturn(List.of(raw));
            when(accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, "acc_1"))
                    .thenReturn(Optional.empty());

            List<Account> result = ingestor.ingestAccounts();

            assertThat(result.getFirst().isArchived()).isTrue();
        }

        @Test
        @DisplayName("archives when the connection is inactive")
        void archivesWhenConnectionInactive() {
            MonzoAccount raw = rawAccount("acc_1", "uk_retail", false, false);
            when(monzoAccountRepository.findAll()).thenReturn(List.of(raw));
            when(accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, "acc_1"))
                    .thenReturn(Optional.empty());

            List<Account> result = ingestor.ingestAccounts();

            assertThat(result.getFirst().isArchived()).isTrue();
        }

        @Test
        @DisplayName("un-archives a reopened account")
        void unarchivesReopenedAccount() {
            MonzoAccount raw = rawAccount("acc_1", "uk_retail", false, true);
            Account existing = domainAccount("acc_1", AccountType.CURRENT);
            existing.archive();
            when(monzoAccountRepository.findAll()).thenReturn(List.of(raw));
            when(accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, "acc_1"))
                    .thenReturn(Optional.of(existing));

            ingestor.ingestAccounts();

            assertThat(existing.isArchived()).isFalse();
        }
    }

    @Nested
    @DisplayName("ingestTransactions")
    class IngestTransactions {

        private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
        private static final Instant T2 = Instant.parse("2026-01-02T10:00:00Z");
        private static final Instant T3 = Instant.parse("2026-01-03T10:00:00Z");

        private Account account;
        private UUID accountId;

        @BeforeEach
        void setUpAccount() {
            accountId = UUID.randomUUID();
            account = mock(Account.class);
            when(account.getId()).thenReturn(accountId);
            when(account.getUser()).thenReturn(user);
            when(account.getProviderAccountId()).thenReturn("acc_1");
            when(account.getRawSyncedThrough()).thenReturn(null);
        }

        @Test
        @DisplayName("declined rows are never mapped but still advance the cursor")
        void skipsDeclined_cursorStillAdvances() {
            MonzoTransaction declined = rawTx("tx_1", T1, true, null);
            when(monzoTransactionRepository.findByAccountIdUpdatedAfter("acc_1", Instant.EPOCH))
                    .thenReturn(List.of(declined));

            ingestor.ingestTransactions(account);

            verifyNoInteractions(transactionRepository);
            verify(account).setRawSyncedThrough(T1);
            verify(accountRepository).save(account);
        }

        @Test
        @DisplayName("maps PENDING (no settled) and SETTLED status")
        void mapsPendingAndSettledStatus() {
            MonzoTransaction pending = rawTx("tx_pending", T1, false, null);
            MonzoTransaction settled = rawTx("tx_settled", T2, false, T2);
            when(monzoTransactionRepository.findByAccountIdUpdatedAfter("acc_1", Instant.EPOCH))
                    .thenReturn(List.of(pending, settled));

            ingestor.ingestTransactions(account);

            verify(transactionRepository).upsert(eq(userId), eq(accountId), eq("MONZO"),
                    eq("tx_pending"), anyLong(), any(), eq("PENDING"),
                    any(), any(), any(), any(), any(), isNull());
            verify(transactionRepository).upsert(eq(userId), eq(accountId), eq("MONZO"),
                    eq("tx_settled"), anyLong(), any(), eq("SETTLED"),
                    any(), any(), any(), any(), any(), eq(T2));
        }

        @Test
        @DisplayName("widens the raw INTEGER amount to long")
        void widensAmountToLong() {
            MonzoTransaction tx = rawTx("tx_1", T1, false, null);
            when(monzoTransactionRepository.findByAccountIdUpdatedAfter("acc_1", Instant.EPOCH))
                    .thenReturn(List.of(tx));

            ingestor.ingestTransactions(account);

            verify(transactionRepository).upsert(any(), any(), any(), any(), eq(-500L), any(),
                    any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("advances the cursor to the max processed raw updated_at")
        void advancesCursorToMaxUpdatedAt() {
            MonzoTransaction tx1 = rawTx("tx_1", T1, false, null);
            MonzoTransaction tx3 = rawTx("tx_3", T3, false, null);
            MonzoTransaction tx2 = rawTx("tx_2", T2, false, null);
            when(monzoTransactionRepository.findByAccountIdUpdatedAfter("acc_1", Instant.EPOCH))
                    .thenReturn(List.of(tx1, tx3, tx2));

            ingestor.ingestTransactions(account);

            verify(account).setRawSyncedThrough(T3);
            verify(accountRepository).save(account);
        }

        @Test
        @DisplayName("no new rows: cursor untouched, no save")
        void noNewRows_cursorUntouched_noSave() {
            when(account.getRawSyncedThrough()).thenReturn(T1);
            when(monzoTransactionRepository.findByAccountIdUpdatedAfter("acc_1", T1))
                    .thenReturn(List.of());

            ingestor.ingestTransactions(account);

            verify(account, never()).setRawSyncedThrough(any());
            verify(accountRepository, never()).save(any());
            verifyNoInteractions(transactionRepository);
        }
    }

    // ============ Helpers ============

    private MonzoAccount rawAccount(String id, String type, boolean closed, boolean connectionActive) {
        MonzoAccount raw = mock(MonzoAccount.class);
        MonzoConnection connection = mock(MonzoConnection.class);
        when(connection.isActive()).thenReturn(connectionActive);
        when(raw.getId()).thenReturn(id);
        when(raw.getUser()).thenReturn(user);
        when(raw.getAccountType()).thenReturn(type);
        when(raw.getDescription()).thenReturn("Current Account");
        when(raw.getCurrency()).thenReturn("GBP");
        when(raw.isClosed()).thenReturn(closed);
        when(raw.getConnection()).thenReturn(connection);
        return raw;
    }

    private Account domainAccount(String providerAccountId, AccountType type) {
        return new Account(user, Provider.MONZO, providerAccountId, type, "Monzo", null, "GBP");
    }

    private MonzoTransaction rawTx(String id, Instant updatedAt, boolean declined, @Nullable Instant settledAt) {
        MonzoTransaction tx = mock(MonzoTransaction.class);
        when(tx.getId()).thenReturn(id);
        when(tx.getUpdatedAt()).thenReturn(updatedAt);
        when(tx.isDeclined()).thenReturn(declined);
        when(tx.getAmount()).thenReturn(-500);
        when(tx.getCurrency()).thenReturn("GBP");
        when(tx.getDescription()).thenReturn("Test");
        when(tx.getMerchantName()).thenReturn(null);
        when(tx.getMerchantCategory()).thenReturn(null);
        when(tx.getNotes()).thenReturn(null);
        when(tx.getMonzoCreatedAt()).thenReturn(Instant.parse("2026-01-01T09:00:00Z"));
        when(tx.getMonzoSettledAt()).thenReturn(settledAt);
        return tx;
    }
}
