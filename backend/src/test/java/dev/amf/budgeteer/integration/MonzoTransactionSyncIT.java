package dev.amf.budgeteer.integration;

import com.github.tomakehurst.wiremock.http.Fault;
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
        @DisplayName("happy path — windowed backfill persists transactions and sets cursor")
        void createsAccountsAndTransactions() {
            // Given
            User user = testData.createVerifiedUser();
            MonzoConnection connection = createConnectionWithRealTokens(user);

            stubAccountsResponse("""
                    {"accounts":[
                      {"id":"acc_001","type":"uk_retail","description":"Current","currency":"GBP","closed":false}
                    ]}
                    """);

            // Default for the FIRST (most recent) window: returns two txs.
            // Older windows return empty. We distinguish via WireMock scenarios so the
            // first request hits the seeded data and subsequent ones return empty.
            stubFirstWindowThenEmpty("acc_001", """
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

            // Then — exactly 2 unique transactions persisted, cursor = newest tx
            List<MonzoAccount> accounts = accountRepository.findByConnectionId(connection.getId());
            assertThat(accounts).hasSize(1);
            assertThat(accounts.getFirst().getId()).isEqualTo("acc_001");
            assertThat(accounts.getFirst().isClosed()).isFalse();
            assertThat(accounts.getFirst().getLastTransactionId()).isEqualTo("tx_002");
            assertThat(accounts.getFirst().getLastSyncedAt()).isNotNull();
            assertThat(transactionRepository.findByAccountId("acc_001")).hasSize(2);

            // Every backfill request must carry both `since` and `before` (windowed) —
            // proves we honour Monzo's 365-day max range. There should be multiple windows
            // (the backfill walks back to 2015), and zero un-windowed requests.
            wm.verify(0, getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("account_id", equalTo("acc_001"))
                    .withoutQueryParam("since"));
            wm.verify(0, getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("account_id", equalTo("acc_001"))
                    .withoutQueryParam("before"));
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
        @DisplayName("pagination within a window — cursor follow-up triggered when page is full")
        void paginatesCorrectly() {
            User user = testData.createVerifiedUser();
            MonzoConnection connection = createConnectionWithRealTokens(user);

            stubAccountsResponse("""
                    {"accounts":[
                      {"id":"acc_001","type":"uk_retail","description":"Current","currency":"GBP","closed":false}
                    ]}
                    """);

            // First window returns 100 transactions → forces a cursor follow-up.
            // Older windows return empty so the test stays deterministic across calendar drift.
            String firstPage = buildTransactionPage(100, "tx_p1_", "2024-01-01T10:00:00Z");
            stubFirstWindowThenEmpty("acc_001", "{\"transactions\":[" + firstPage + "]}");

            // Cursor follow-up within first window returns the remaining 5 (last page).
            String secondPage = buildTransactionPage(5, "tx_p2_", "2024-01-02T10:00:00Z");
            stubCursorPage("acc_001", "tx_p1_099", "{\"transactions\":[" + secondPage + "]}");

            syncService.backfill(connection.getId());

            // 100 (first page) + 5 (cursor follow-up) = 105 unique transactions persisted.
            assertThat(transactionRepository.findByAccountId("acc_001")).hasSize(105);

            // The newest tx — last from the cursor follow-up — becomes the stored cursor.
            MonzoAccount account = accountRepository.findById("acc_001").orElseThrow();
            assertThat(account.getLastTransactionId()).isEqualTo("tx_p2_004");

            // Cursor follow-up fires exactly once (within the first non-empty window).
            wm.verify(1, getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("since_id", equalTo("tx_p1_099")));
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

            // Simulate the account having a cursor set from a previous sync.
            // First deltaSync run — account has no cursor, so the request goes out with no anchor or cursor.
            stubDeltaFirstRun("acc_delta",
                    "{\"transactions\":[{\"id\":\"tx_seed\",\"amount\":-100,\"currency\":\"GBP\"," +
                    "\"description\":\"seed\",\"merchant\":null,\"notes\":null,\"decline_reason\":null," +
                    "\"created\":\"2024-01-01T00:00:00Z\",\"settled\":null}]}");
            syncService.deltaSync("acc_delta");
            wm.resetAll();

            // Now delta sync with the cursor
            stubCursorPage("acc_delta", "tx_seed", """
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

    @Nested
    @DisplayName("backfill() — resumability")
    class BackfillResumability {

        @Test
        @DisplayName("verification_required marks NEEDS_REAUTH and persists partial progress")
        void verificationRequiredMarksNeedsReauth() {
            User user = testData.createVerifiedUser();
            MonzoConnection connection = createConnectionWithRealTokens(user);

            stubAccountsResponse("""
                    {"accounts":[
                      {"id":"acc_001","type":"uk_retail","description":"Current","currency":"GBP",
                       "closed":false,"created":"2025-01-01T00:00:00Z"}
                    ]}
                    """);

            // First windowed request succeeds with one page of results
            stubFirstWindowThenVerificationRequired("acc_001", """
                    {"transactions":[
                      {"id":"tx_001","amount":-500,"currency":"GBP","description":"Coffee",
                       "merchant":null,"notes":null,"decline_reason":null,
                       "created":"2025-10-01T10:00:00Z","settled":"2025-10-02T00:00:00Z"}
                    ]}
                    """);

            syncService.backfill(connection.getId());

            // The account should be marked NEEDS_REAUTH (not COMPLETED)
            MonzoAccount account = accountRepository.findById("acc_001").orElseThrow();
            assertThat(account.getBackfillStatus())
                    .isEqualTo(MonzoAccount.BackfillStatus.NEEDS_REAUTH);

            // The one transaction from the first successful window is persisted
            assertThat(transactionRepository.findByAccountId("acc_001")).hasSize(1);

            // backfillProgressAt is set (so re-OAuth can resume from there)
            assertThat(account.getBackfillProgressAt()).isNotNull();
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

    /**
     * Stub the first windowed backfill request (matched by absence of {@code since_id}, i.e. the
     * start of a window) to return {@code json} once, then empty for all subsequent windows.
     * Uses a WireMock scenario so the response progresses through the state machine.
     */
    private void stubFirstWindowThenEmpty(String accountId, String json) {
        String scenario = "backfill-" + accountId;
        // First windowed request → returns the seeded data
        wm.stubFor(get(urlPathEqualTo("/transactions"))
                .inScenario(scenario)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .withQueryParam("account_id", equalTo(accountId))
                .withQueryParam("expand[]", equalTo("merchant"))
                .withQueryParam("since", matching(".+"))
                .withQueryParam("before", matching(".+"))
                .willSetStateTo("first-window-served")
                .willReturn(okJson(json)));
        // All later windowed requests → empty
        wm.stubFor(get(urlPathEqualTo("/transactions"))
                .inScenario(scenario)
                .whenScenarioStateIs("first-window-served")
                .withQueryParam("account_id", equalTo(accountId))
                .withQueryParam("expand[]", equalTo("merchant"))
                .withQueryParam("since", matching(".+"))
                .withQueryParam("before", matching(".+"))
                .willReturn(okJson("{\"transactions\":[]}")));
    }

    /**
     * Like {@link #stubFirstWindowThenEmpty} but subsequent window requests return 403
     * verification_required, simulating SCA window expiry mid-backfill.
     */
    private void stubFirstWindowThenVerificationRequired(String accountId, String json) {
        String scenario = "backfill-sca-" + accountId;
        wm.stubFor(get(urlPathEqualTo("/transactions"))
                .inScenario(scenario)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .withQueryParam("account_id", equalTo(accountId))
                .withQueryParam("expand[]", equalTo("merchant"))
                .withQueryParam("since", matching(".+"))
                .withQueryParam("before", matching(".+"))
                .willSetStateTo("first-window-served")
                .willReturn(okJson(json)));
        wm.stubFor(get(urlPathEqualTo("/transactions"))
                .inScenario(scenario)
                .whenScenarioStateIs("first-window-served")
                .withQueryParam("account_id", equalTo(accountId))
                .withQueryParam("expand[]", equalTo("merchant"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"forbidden.verification_required\",\"message\":\"SCA required\"}")));
    }

    /** Stub for a cursor-based page (used both for backfill pagination and delta sync). */
    private void stubCursorPage(String accountId, String sinceId, String json) {
        wm.stubFor(get(urlPathEqualTo("/transactions"))
                .withQueryParam("account_id", equalTo(accountId))
                .withQueryParam("expand[]", equalTo("merchant"))
                .withQueryParam("since_id", equalTo(sinceId))
                .willReturn(okJson(json)));
    }

    /** Stub for a delta sync where the account has no cursor yet — no anchor, no cursor. */
    private void stubDeltaFirstRun(String accountId, String json) {
        wm.stubFor(get(urlPathEqualTo("/transactions"))
                .withQueryParam("account_id", equalTo(accountId))
                .withQueryParam("expand[]", equalTo("merchant"))
                .willReturn(okJson(json)));
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
