package dev.amfshr.budgeteer.integration;

import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.account.Provider;
import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.monzo.MonzoTransaction;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.repository.AccountRepository;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.repository.MonzoTransactionRepository;
import dev.amfshr.budgeteer.repository.TransactionRepository;
import dev.amfshr.budgeteer.service.common.EncryptionService;
import dev.amfshr.budgeteer.service.monzo.TransactionSyncJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Full sync → ingest → balance pipeline")
class SyncPipelineIT extends AbstractMonzoWireMockIT {

    @Autowired private TransactionSyncJob syncJob;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private MonzoAccountRepository monzoAccountRepository;
    @Autowired private MonzoTransactionRepository monzoTransactionRepository;
    @Autowired private MonzoConnectionRepository connectionRepository;
    @Autowired private EncryptionService encryptionService;
    @Autowired private TestDataFactory testData;

    private MonzoAccount rawAccount;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        monzoTransactionRepository.deleteAll();
        monzoAccountRepository.deleteAll();
        connectionRepository.deleteAll();

        User user = testData.createVerifiedUser();
        MonzoConnection connection = testData.createMonzoConnectionWithRealTokens(
                user, "refresh-token", Instant.now().plusSeconds(3600));
        rawAccount = testData.createMonzoAccount(connection, user, "acc_pipe_001");
    }

    @Test
    @DisplayName("one job run captures encrypted raw JSON, ingests the domain, and stamps the balance")
    void fullChain_rawCaptured_domainIngested_balanceStamped() {
        wm.stubFor(get(urlPathEqualTo("/transactions"))
                .willReturn(okJson("""
                        {"transactions":[
                          {"id":"tx_pipe_001","amount":-500,"currency":"GBP","description":"Coffee",
                           "merchant":{"name":"Starbucks","category":"eating_out"},"notes":"",
                           "decline_reason":null,
                           "created":"2026-08-01T10:00:00Z","settled":"2026-08-02T00:00:00Z"}
                        ]}
                        """)));
        loadStubFromFile("wiremock/mappings/monzo/balance/balance-success.json");

        syncJob.syncAllAccounts();

        // Raw capture: encrypted, not plaintext, round-trips through EncryptionService
        MonzoTransaction rawTx = monzoTransactionRepository.findById("tx_pipe_001").orElseThrow();
        String encrypted = rawTx.getRawPayloadEncrypted();
        assertThat(encrypted).isNotNull();
        assertThat(encrypted).doesNotContain("Starbucks");
        assertThat(encryptionService.decrypt(encrypted)).contains("\"id\":\"tx_pipe_001\"");

        // Domain ingested
        Account domainAccount = accountRepository
                .findByProviderAndProviderAccountId(Provider.MONZO, "acc_pipe_001").orElseThrow();
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(transactionRepository.findAll().getFirst().getProviderTransactionId())
                .isEqualTo("tx_pipe_001");

        // Balance stamped from the provider snapshot
        assertThat(domainAccount.getBalanceMinorUnits()).isEqualTo(12345);
        assertThat(domainAccount.getBalanceAsOf()).isNotNull();
    }
}
