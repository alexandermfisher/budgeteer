package dev.amfshr.budgeteer.integration;

import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.account.Provider;
import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.monzo.MonzoTransaction;
import dev.amfshr.budgeteer.domain.transaction.Transaction;
import dev.amfshr.budgeteer.domain.transaction.TransactionStatus;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.repository.AccountRepository;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.repository.MonzoTransactionRepository;
import dev.amfshr.budgeteer.repository.TransactionRepository;
import dev.amfshr.budgeteer.service.ingest.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ingest raw → domain pipeline")
class IngestIT extends AbstractPostgresIntegrationTest {

    @Autowired private IngestService ingestService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private MonzoAccountRepository monzoAccountRepository;
    @Autowired private MonzoTransactionRepository monzoTransactionRepository;
    @Autowired private MonzoConnectionRepository connectionRepository;
    @Autowired private TestDataFactory testData;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User user;
    private MonzoConnection connection;
    private MonzoAccount rawAccount;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        monzoTransactionRepository.deleteAll();
        monzoAccountRepository.deleteAll();
        connectionRepository.deleteAll();

        user = testData.createVerifiedUser();
        connection = testData.createActiveMonzoConnectionFor(user);
        rawAccount = testData.createMonzoAccount(connection, user);
    }

    @Test
    @DisplayName("maps raw account + transaction to the domain end to end")
    void ingestsRawToDomainEndToEnd() {
        MonzoTransaction rawTx = testData.createMonzoTransaction(rawAccount, user);

        ingestService.ingestAll();

        Account domainAccount = accountRepository
                .findByProviderAndProviderAccountId(Provider.MONZO, rawAccount.getId()).orElseThrow();
        assertThat(domainAccount.getInstitutionName()).isEqualTo("Monzo");
        assertThat(domainAccount.isArchived()).isFalse();
        assertThat(domainAccount.getRawSyncedThrough()).isNotNull();

        List<Transaction> domainTxs = transactionRepository.findAll();
        assertThat(domainTxs).hasSize(1);
        Transaction domainTx = domainTxs.getFirst();
        assertThat(domainTx.getProviderTransactionId()).isEqualTo(rawTx.getId());
        assertThat(domainTx.getAmountMinorUnits()).isEqualTo(rawTx.getAmount());
        assertThat(domainTx.getStatus()).isEqualTo(TransactionStatus.PENDING); // factory tx is unsettled
    }

    @Test
    @DisplayName("re-running the ingest creates zero duplicates")
    void remapIsIdempotent_noDuplicateRows() {
        testData.createMonzoTransaction(rawAccount, user);

        ingestService.ingestAll();
        // Second run must re-map without duplicating (cursor may or may not skip; upsert absorbs)
        resetCursorToEpoch();
        ingestService.ingestAll();

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("settled flip on the raw row propagates to the domain on re-map")
    void settledFlipPropagates() {
        MonzoTransaction rawTx = testData.createMonzoTransaction(rawAccount, user);
        ingestService.ingestAll();
        assertThat(transactionRepository.findAll().getFirst().getStatus())
                .isEqualTo(TransactionStatus.PENDING);

        // Raw re-touch: settlement arrives, updated_at bumps past the cursor
        jdbcTemplate.update(
                "update monzo_transactions set monzo_settled_at = now(), updated_at = now() where id = ?",
                rawTx.getId());

        ingestService.ingestAll();

        assertThat(transactionRepository.findAll().getFirst().getStatus())
                .isEqualTo(TransactionStatus.SETTLED);
    }

    @Test
    @DisplayName("declined raw transactions are never mapped, cursor still advances")
    void declinedNeverMapped() {
        MonzoTransaction rawTx = testData.createMonzoTransaction(rawAccount, user);
        jdbcTemplate.update(
                "update monzo_transactions set is_declined = true, updated_at = now() where id = ?",
                rawTx.getId());

        ingestService.ingestAll();

        assertThat(transactionRepository.count()).isZero();
        Account domainAccount = accountRepository
                .findByProviderAndProviderAccountId(Provider.MONZO, rawAccount.getId()).orElseThrow();
        assertThat(domainAccount.getRawSyncedThrough()).isNotNull();
    }

    @Test
    @DisplayName("user-edited notes survive a re-map")
    void notesSurviveRemap() {
        MonzoTransaction rawTx = testData.createMonzoTransaction(rawAccount, user);
        ingestService.ingestAll();

        jdbcTemplate.update("update transactions set notes = 'my note' where provider_transaction_id = ?",
                rawTx.getId());
        // Re-touch the raw row so the cursor picks it up again
        jdbcTemplate.update(
                "update monzo_transactions set notes = 'provider note', updated_at = now() where id = ?",
                rawTx.getId());

        ingestService.ingestAll();

        assertThat(transactionRepository.findAll().getFirst().getNotes()).isEqualTo("my note");
    }

    @Test
    @DisplayName("closed raw account archives the domain account")
    void closedRawAccountArchivesDomain() {
        ingestService.ingestAll();
        assertThat(accountRepository.findAll().getFirst().isArchived()).isFalse();

        rawAccount.setClosed(true);
        monzoAccountRepository.save(rawAccount);

        ingestService.ingestAll();

        assertThat(accountRepository.findAll().getFirst().isArchived()).isTrue();
    }

    @Test
    @DisplayName("cursor is persisted across runs and only advances on new raw rows")
    void cursorPersistedAcrossRuns() {
        testData.createMonzoTransaction(rawAccount, user);
        ingestService.ingestAll();

        Instant cursorAfterFirst = accountRepository.findAll().getFirst().getRawSyncedThrough();
        assertThat(cursorAfterFirst).isNotNull();

        ingestService.ingestAll();     // nothing new

        assertThat(accountRepository.findAll().getFirst().getRawSyncedThrough())
                .isEqualTo(cursorAfterFirst);
    }

    private void resetCursorToEpoch() {
        jdbcTemplate.update("update bank_accounts set raw_synced_through = null");
    }
}
