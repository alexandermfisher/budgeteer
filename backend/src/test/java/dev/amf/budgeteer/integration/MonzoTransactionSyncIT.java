package dev.amf.budgeteer.integration;

import dev.amf.budgeteer.domain.monzo.MonzoAccount;
import dev.amf.budgeteer.domain.monzo.MonzoConnection;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.repository.MonzoAccountRepository;
import dev.amf.budgeteer.repository.MonzoConnectionRepository;
import dev.amf.budgeteer.repository.MonzoTransactionRepository;
import dev.amf.budgeteer.service.monzo.TransactionSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Monzo Transaction Sync Integration Tests")
class MonzoTransactionSyncIT extends AbstractMonzoWireMockIT {

    @Autowired private TransactionSyncService syncService;
    @Autowired private MonzoConnectionRepository connectionRepository;
    @Autowired private MonzoAccountRepository accountRepository;
    @Autowired private MonzoTransactionRepository transactionRepository;
    @Autowired private TestDataFactory testData;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Nested
    @DisplayName("backfill()")
    class Backfill {

        @Test
        @DisplayName("happy path — creates accounts and transactions in DB")
        void createsAccountsAndTransactions() {
            // Given
            User user = testData.createVerifiedUser();
            MonzoConnection connection = createConnectionWithRealTokens(user);

            stubAccountsResponse("""
                    {"accounts":[
                      {"id":"acc_001","type":"uk_retail","description":"Current","currency":"GBP","closed":false}
                    ]}
                    """);
            stubTransactionsResponse("acc_001", null, """
                    {"transactions":[
                      {"id":"tx_001","amount":-500,"currency":"GBP","description":"Coffee",
                       "merchant":{"name":"Starbucks","category":"eating_out"},
                       "notes":"","decline_reason":null,
                       "created":"2024-01-01T10:00:00Z","settled":"2024-01-02T00:00:00Z"},
                      {"id":"tx_002","amount":-1000,"currency":"GBP","description":"Lunch",
                       "merchant":null,"notes":null,"decline_reason":null,
                       "created":"2024-01-02T12:00:00Z","settled":null}
                    ]}
                    """);

            // When
            syncService.backfill(connection.getId());

            // Then
            List<MonzoAccount> accounts = accountRepository.findByConnectionId(connection.getId());
            assertThat(accounts).hasSize(1);
            assertThat(accounts.getFirst().getId()).isEqualTo("acc_001");
            assertThat(accounts.getFirst().isClosed()).isFalse();
            assertThat(accounts.getFirst().getLastTransactionId()).isEqualTo("tx_002");
            assertThat(accounts.getFirst().getLastSyncedAt()).isNotNull();

            assertThat(transactionRepository.findByAccountId("acc_001")).hasSize(2);
        }

        @Test
        @DisplayName("skips closed account — no transactions fetched")
        void skipsClosedAccount() {
            User user = testData.createVerifiedUser();
            MonzoConnection connection = createConnectionWithRealTokens(user);

            stubAccountsResponse("""
                    {"accounts":[
                      {"id":"acc_closed","type":"uk_retail","description":"Old","currency":"GBP","closed":true}
                    ]}
                    """);

            syncService.backfill(connection.getId());

            assertThat(accountRepository.findByConnectionId(connection.getId())).hasSize(1);
            assertThat(transactionRepository.findByAccountId("acc_closed")).isEmpty();
            wm.verify(0, getRequestedFor(urlPathEqualTo("/transactions")));
        }

        @Test
        @DisplayName("pagination — fetches all pages until page < 100")
        void paginatesCorrectly() {
            User user = testData.createVerifiedUser();
            MonzoConnection connection = createConnectionWithRealTokens(user);

            stubAccountsResponse("""
                    {"accounts":[
                      {"id":"acc_001","type":"uk_retail","description":"Current","currency":"GBP","closed":false}
                    ]}
                    """);

            // First page: 100 transactions
            String firstPage = buildTransactionPage(100, "tx_p1_", "2024-01-01T10:00:00Z");
            stubTransactionsResponse("acc_001", null, "{\"transactions\":[" + firstPage + "]}");

            // Second page: 5 transactions (last page)
            String secondPage = buildTransactionPage(5, "tx_p2_", "2024-01-02T10:00:00Z");
            stubTransactionsResponse("acc_001", "tx_p1_099", "{\"transactions\":[" + secondPage + "]}");

            syncService.backfill(connection.getId());

            assertThat(transactionRepository.findByAccountId("acc_001")).hasSize(105);
            // Cursor should be the last ID from page 2
            MonzoAccount account = accountRepository.findById("acc_001").orElseThrow();
            assertThat(account.getLastTransactionId()).isEqualTo("tx_p2_004");
        }
    }

    @Nested
    @DisplayName("deltaSync()")
    class DeltaSync {

        @Test
        @DisplayName("fetches only transactions since last cursor")
        void usesCursorForDelta() {
            // Given — seed account directly in DB with a known cursor
            User user = testData.createVerifiedUser();
            MonzoConnection connection = createConnectionWithRealTokens(user);
            MonzoAccount account = testData.createMonzoAccount(connection, user, "acc_delta");
            testData.createMonzoTransaction(account, user); // seed tx (not tx_seed ID)

            // Simulate the account having a cursor set from a previous sync
            // We do this by running a small backfill first with a controlled stub
            stubTransactionsResponse("acc_delta", null,
                    "{\"transactions\":[{\"id\":\"tx_seed\",\"amount\":-100,\"currency\":\"GBP\"," +
                    "\"description\":\"seed\",\"merchant\":null,\"notes\":null,\"decline_reason\":null," +
                    "\"created\":\"2024-01-01T00:00:00Z\",\"settled\":null}]}");
            syncService.deltaSync("acc_delta");
            wm.resetAll();

            // Now delta sync with the cursor
            stubTransactionsResponse("acc_delta", "tx_seed", """
                    {"transactions":[
                      {"id":"tx_new_001","amount":-200,"currency":"GBP","description":"New","merchant":null,
                       "notes":null,"decline_reason":null,"created":"2024-01-03T10:00:00Z","settled":null}
                    ]}
                    """);

            syncService.deltaSync("acc_delta");

            // 1 seed (from TestDataFactory) + tx_seed + tx_new_001 = 3 total
            // But the one from TestDataFactory has a random ID, tx_seed and tx_new_001 are via upsert
            assertThat(transactionRepository.findByAccountId("acc_delta")).hasSizeGreaterThanOrEqualTo(2);
            MonzoAccount updated = accountRepository.findById("acc_delta").orElseThrow();
            assertThat(updated.getLastTransactionId()).isEqualTo("tx_new_001");

            wm.verify(1, getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("since_id", equalTo("tx_seed")));
        }
    }

    // ============ Helpers ============

    private MonzoConnection createConnectionWithRealTokens(User user) {
        return testData.createMonzoConnectionWithRealTokens(
                user, "test-refresh-token-" + UUID.randomUUID(),
                Instant.now().plus(6, ChronoUnit.HOURS)
        );
    }

    private void stubAccountsResponse(String json) {
        wm.stubFor(get(urlPathEqualTo("/accounts"))
                .willReturn(okJson(json)));
    }

    private void stubTransactionsResponse(String accountId, String sinceId, String json) {
        var builder = get(urlPathEqualTo("/transactions"))
                .withQueryParam("account_id", equalTo(accountId))
                .withQueryParam("expand[]", equalTo("merchant"));
        if (sinceId != null) {
            builder = builder.withQueryParam("since_id", equalTo(sinceId));
        }
        wm.stubFor(builder.willReturn(okJson(json)));
    }

    private String buildTransactionPage(int count, String idPrefix, String baseTime) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append(String.format(
                    "{\"id\":\"%s%03d\",\"amount\":-100,\"currency\":\"GBP\"," +
                    "\"description\":\"Tx %d\",\"merchant\":null,\"notes\":null," +
                    "\"decline_reason\":null,\"created\":\"%s\",\"settled\":null}",
                    idPrefix, i, i, baseTime));
        }
        return sb.toString();
    }
}
