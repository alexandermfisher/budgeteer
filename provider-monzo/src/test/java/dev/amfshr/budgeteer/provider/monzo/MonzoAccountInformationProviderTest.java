package dev.amfshr.budgeteer.provider.monzo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.amfshr.budgeteer.provider.model.BankAccount;
import dev.amfshr.budgeteer.provider.model.BankBalance;
import dev.amfshr.budgeteer.provider.exception.ProviderException;
import dev.amfshr.budgeteer.provider.exception.ProviderConnectionRevokedException;
import dev.amfshr.budgeteer.provider.model.BankIdentity;
import dev.amfshr.budgeteer.provider.exception.ProviderReauthRequiredException;
import dev.amfshr.budgeteer.provider.model.BankTokens;
import dev.amfshr.budgeteer.provider.model.BankTransaction;
import dev.amfshr.budgeteer.provider.model.BankTransactionPage;
import dev.amfshr.budgeteer.provider.model.Sourced;
import dev.amfshr.budgeteer.provider.model.SyncPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MonzoAccountInformationProvider")
class MonzoAccountInformationProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private MonzoAccountInformationProvider client;
    private static final String ACCESS_TOKEN = "test-access-token";

    @BeforeEach
    void setUp() {
        RestClient restClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .build();

        MonzoProperties props = mock(MonzoProperties.class);
        when(props.tokenUrl()).thenReturn(wm.baseUrl() + "/oauth2/token");

        client = new MonzoAccountInformationProvider(props, restClient, new tools.jackson.databind.ObjectMapper());
    }

    private static String fixture(String path) throws IOException {
        try (var in = MonzoAccountInformationProviderTest.class.getResourceAsStream("/wiremock/" + path)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ============ buildAuthorizationUrl ============

    @Nested
    @DisplayName("buildAuthorizationUrl")
    class BuildAuthorizationUrl {

        @Test
        @DisplayName("includes client_id, redirect_uri, response_type=code, and state")
        void includesAllParams() {
            MonzoProperties props = mock(MonzoProperties.class);
            when(props.authUrl()).thenReturn("https://auth.monzo.com/");
            when(props.clientId()).thenReturn("client-123");
            when(props.redirectUri()).thenReturn("http://localhost/cb");

            RestClient restClient = RestClient.builder().baseUrl(wm.baseUrl()).build();
            MonzoAccountInformationProvider c = new MonzoAccountInformationProvider(props, restClient,
                    new tools.jackson.databind.ObjectMapper());

            String url = c.buildAuthorizationUrl("state-abc");

            assertThat(url).contains("client_id=client-123");
            assertThat(url).contains("redirect_uri=http://localhost/cb");
            assertThat(url).contains("response_type=code");
            assertThat(url).contains("state=state-abc");
        }
    }

    // ============ exchangeCode ============

    @Nested
    @DisplayName("exchangeCode")
    class ExchangeCode {

        @Test
        @DisplayName("returns BankTokens on success")
        void happyPath() {
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(okJson("""
                            {
                              "access_token": "tok_access",
                              "refresh_token": "tok_refresh",
                              "expires_in": 21600
                            }
                            """)));

            BankTokens tokens = client.exchangeCode("test-code");

            assertThat(tokens.accessToken()).isEqualTo("tok_access");
            assertThat(tokens.refreshToken()).isEqualTo("tok_refresh");
            assertThat(tokens.expiresAt()).isNotNull();
        }

        @Test
        @DisplayName("401 throws ProviderConnectionRevokedException")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.exchangeCode("test-code"))
                    .isInstanceOf(ProviderConnectionRevokedException.class);
        }

        @Test
        @DisplayName("missing access_token throws ProviderException")
        void missingAccessTokenThrows() {
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(okJson("{\"refresh_token\":\"tok_refresh\",\"expires_in\":21600}")));

            assertThatThrownBy(() -> client.exchangeCode("test-code"))
                    .isInstanceOf(ProviderException.class);
        }
    }

    // ============ refreshTokens ============

    @Nested
    @DisplayName("refreshTokens")
    class RefreshTokens {

        @Test
        @DisplayName("returns BankTokens on success")
        void happyPath() {
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(okJson("""
                            {
                              "access_token": "tok_new",
                              "refresh_token": "tok_new_ref",
                              "expires_in": 21600
                            }
                            """)));

            BankTokens tokens = client.refreshTokens("old-refresh");

            assertThat(tokens.accessToken()).isEqualTo("tok_new");
            assertThat(tokens.refreshToken()).isEqualTo("tok_new_ref");
        }

        @Test
        @DisplayName("falls back to old refresh token when Monzo does not rotate it")
        void fallsBackToOldRefreshWhenNotRotated() {
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(okJson("""
                            {
                              "access_token": "tok_access",
                              "expires_in": 21600
                            }
                            """)));

            BankTokens tokens = client.refreshTokens("old-refresh-token");

            assertThat(tokens.accessToken()).isEqualTo("tok_access");
            assertThat(tokens.refreshToken()).isEqualTo("old-refresh-token");
        }

        @Test
        @DisplayName("401 throws ProviderConnectionRevokedException")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.refreshTokens("old-refresh"))
                    .isInstanceOf(ProviderConnectionRevokedException.class);
        }

        @Test
        @DisplayName("403 throws ProviderException")
        void forbiddenThrowsClientException() {
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(forbidden()));

            assertThatThrownBy(() -> client.refreshTokens("old-refresh"))
                    .isInstanceOf(ProviderException.class);
        }
    }

    // ============ getIdentity ============

    @Nested
    @DisplayName("getIdentity")
    class GetIdentity {

        @Test
        @DisplayName("returns BankIdentity on success")
        void happyPath() {
            wm.stubFor(get(urlPathEqualTo("/ping/whoami"))
                    .willReturn(okJson("{\"authenticated\":true,\"user_id\":\"user_abc123\"}")));

            BankIdentity identity = client.getIdentity(ACCESS_TOKEN);

            assertThat(identity.providerUserId()).isEqualTo("user_abc123");
        }

        @Test
        @DisplayName("401 throws ProviderConnectionRevokedException")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(get(urlPathEqualTo("/ping/whoami"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.getIdentity(ACCESS_TOKEN))
                    .isInstanceOf(ProviderConnectionRevokedException.class);
        }

        @Test
        @DisplayName("403 throws ProviderException")
        void forbiddenThrowsClientException() {
            wm.stubFor(get(urlPathEqualTo("/ping/whoami"))
                    .willReturn(forbidden()));

            assertThatThrownBy(() -> client.getIdentity(ACCESS_TOKEN))
                    .isInstanceOf(ProviderException.class);
        }

        @Test
        @DisplayName("missing user_id throws ProviderException")
        void missingUserIdThrows() {
            wm.stubFor(get(urlPathEqualTo("/ping/whoami"))
                    .willReturn(okJson("{\"authenticated\":true}")));

            assertThatThrownBy(() -> client.getIdentity(ACCESS_TOKEN))
                    .isInstanceOf(ProviderException.class);
        }

        @Test
        @DisplayName("blank user_id throws ProviderException")
        void blankUserIdThrows() {
            wm.stubFor(get(urlPathEqualTo("/ping/whoami"))
                    .willReturn(okJson("{\"authenticated\":true,\"user_id\":\"\"}")));

            assertThatThrownBy(() -> client.getIdentity(ACCESS_TOKEN))
                    .isInstanceOf(ProviderException.class);
        }
    }

    // ============ getAccounts ============

    @Nested
    @DisplayName("getAccounts")
    class GetAccounts {

        @Test
        @DisplayName("returns BankAccounts on success")
        void happyPath() {
            wm.stubFor(get(urlPathEqualTo("/accounts"))
                    .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                    .willReturn(okJson("""
                            {
                              "accounts": [
                                {"id":"acc_001","type":"uk_retail","description":"Current Account","currency":"GBP","closed":false},
                                {"id":"acc_002","type":"uk_retail_joint","description":null,"currency":"GBP","closed":true}
                              ]
                            }
                            """)));

            List<Sourced<BankAccount>> result = client.getAccounts(ACCESS_TOKEN);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).payload().externalId()).isEqualTo("acc_001");
            assertThat(result.get(0).payload().type()).isEqualTo("uk_retail");
            assertThat(result.get(0).payload().currency()).isEqualTo("GBP");
            assertThat(result.get(0).payload().closed()).isFalse();
            assertThat(result.get(1).payload().externalId()).isEqualTo("acc_002");
            assertThat(result.get(1).payload().closed()).isTrue();
        }

        @Test
        @DisplayName("401 throws ProviderConnectionRevokedException")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(get(urlPathEqualTo("/accounts"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.getAccounts(ACCESS_TOKEN))
                    .isInstanceOf(ProviderConnectionRevokedException.class);
        }

        @Test
        @DisplayName("403 throws ProviderException")
        void forbiddenThrowsClientException() {
            wm.stubFor(get(urlPathEqualTo("/accounts"))
                    .willReturn(forbidden()));

            assertThatThrownBy(() -> client.getAccounts(ACCESS_TOKEN))
                    .isInstanceOf(ProviderException.class);
        }

        @Test
        @DisplayName("429 throws ProviderException")
        void rateLimitedThrowsClientException() {
            wm.stubFor(get(urlPathEqualTo("/accounts"))
                    .willReturn(aResponse().withStatus(429)));

            assertThatThrownBy(() -> client.getAccounts(ACCESS_TOKEN))
                    .isInstanceOf(ProviderException.class);
        }

        @Test
        @DisplayName("empty list returns empty List")
        void emptyListReturnsEmpty() {
            wm.stubFor(get(urlPathEqualTo("/accounts"))
                    .willReturn(okJson("{\"accounts\":[]}")));

            List<Sourced<BankAccount>> result = client.getAccounts(ACCESS_TOKEN);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("rawJson is populated")
        void rawJsonPopulated() {
            wm.stubFor(get(urlPathEqualTo("/accounts"))
                    .willReturn(okJson("""
                            {"accounts":[{"id":"acc_001","type":"uk_retail","description":"Current","currency":"GBP","closed":false}]}
                            """)));

            List<Sourced<BankAccount>> result = client.getAccounts(ACCESS_TOKEN);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).rawJson()).isNotNull();
            assertThat(result.get(0).rawJson()).contains("\"id\":\"acc_001\"");
        }

        @Test
        @DisplayName("unknown fields are preserved in rawJson")
        void unknownFieldsPreserved() throws Exception {
            wm.stubFor(get(urlPathEqualTo("/accounts"))
                    .willReturn(okJson(fixture("accounts/accounts-unknown-fields.json"))));

            List<Sourced<BankAccount>> result = client.getAccounts(ACCESS_TOKEN);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).rawJson()).contains("\"sort_code\":\"040004\"");
            assertThat(result.get(0).rawJson()).contains("\"account_number\":\"12345678\"");
        }
    }

    // ============ getBalance ============

    @Nested
    @DisplayName("getBalance")
    class GetBalance {

        @Test
        @DisplayName("returns BankBalance on success")
        void happyPath() {
            wm.stubFor(get(urlPathEqualTo("/balance"))
                    .withQueryParam("account_id", equalTo("acc_001"))
                    .willReturn(okJson("{\"balance\":5000,\"total_balance\":6000,\"currency\":\"GBP\",\"spend_today\":-120}")));

            BankBalance balance = client.getBalance(ACCESS_TOKEN, "acc_001");

            assertThat(balance.balanceMinorUnits()).isEqualTo(5000);
            assertThat(balance.currency()).isEqualTo("GBP");
        }

        @Test
        @DisplayName("401 throws ProviderConnectionRevokedException")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(get(urlPathEqualTo("/balance"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.getBalance(ACCESS_TOKEN, "acc_001"))
                    .isInstanceOf(ProviderConnectionRevokedException.class);
        }

        @Test
        @DisplayName("429 throws ProviderException")
        void rateLimitedThrowsClientException() {
            wm.stubFor(get(urlPathEqualTo("/balance"))
                    .willReturn(aResponse().withStatus(429)));

            assertThatThrownBy(() -> client.getBalance(ACCESS_TOKEN, "acc_001"))
                    .isInstanceOf(ProviderException.class);
        }

        @Test
        @DisplayName("malformed body throws ProviderException")
        void malformedBodyThrows() {
            wm.stubFor(get(urlPathEqualTo("/balance"))
                    .willReturn(aResponse().withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"internal\"}")));

            assertThatThrownBy(() -> client.getBalance(ACCESS_TOKEN, "acc_001"))
                    .isInstanceOf(ProviderException.class);
        }
    }

    // ============ getTransactions ============

    @Nested
    @DisplayName("getTransactions")
    class GetTransactions {

        private static final Instant FROM = Instant.parse("2024-01-01T00:00:00Z");
        private static final Instant TO = Instant.parse("2024-12-16T00:00:00Z");

        @Test
        @DisplayName("first page (null cursor) — returns page with nextCursor and correct BankTransactions")
        void firstPage() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("account_id", equalTo("acc_001"))
                    .withQueryParam("since", equalTo("2024-01-01T00:00:00Z"))
                    .withQueryParam("before", equalTo("2024-12-16T00:00:00Z"))
                    .withQueryParam("limit", equalTo("100"))
                    .willReturn(okJson("""
                            {
                              "transactions": [
                                {
                                  "id":"tx_001","amount":-500,"currency":"GBP",
                                  "description":"Coffee","merchant":{"name":"Starbucks","category":"eating_out"},
                                  "notes":"","decline_reason":null,
                                  "created":"2024-06-01T10:00:00Z","settled":"2024-06-02T00:00:00Z"
                                }
                              ]
                            }
                            """)));

            BankTransactionPage page = client.getTransactions(ACCESS_TOKEN, "acc_001", new SyncPosition.FromTime(FROM), TO);

            assertThat(page.nextCursor()).isNull(); // short page → end
            assertThat(page.transactions()).hasSize(1);
            BankTransaction tx = page.transactions().get(0).payload();
            assertThat(tx.externalId()).isEqualTo("tx_001");
            assertThat(tx.amountMinorUnits()).isEqualTo(-500);
            assertThat(tx.currency()).isEqualTo("GBP");
            assertThat(tx.description()).isEqualTo("Coffee");
            assertThat(tx.merchantName()).isEqualTo("Starbucks");
            assertThat(tx.merchantCategory()).isEqualTo("eating_out");
            assertThat(tx.declined()).isFalse();
            assertThat(tx.settledAt()).isNotNull();
        }

        @Test
        @DisplayName("full page (size=PAGE_SIZE) — returns nextCursor = last tx id")
        void fullPageReturnsCursor() {
            // Build a JSON array of 100 transactions (simulate a full page)
            StringBuilder sb = new StringBuilder("{\"transactions\":[");
            for (int i = 1; i <= 100; i++) {
                if (i > 1) sb.append(",");
                sb.append(String.format(
                        "{\"id\":\"tx_%03d\",\"amount\":-10,\"currency\":\"GBP\",\"description\":\"Tx %d\"," +
                                "\"merchant\":null,\"notes\":null,\"decline_reason\":null," +
                                "\"created\":\"2024-06-01T10:00:00Z\",\"settled\":\"2024-06-02T00:00:00Z\"}",
                        i, i));
            }
            sb.append("]}");

            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("since", equalTo("2024-01-01T00:00:00Z"))
                    .withQueryParam("before", equalTo("2024-12-16T00:00:00Z"))
                    .withQueryParam("limit", equalTo("100"))
                    .willReturn(okJson(sb.toString())));

            BankTransactionPage page = client.getTransactions(ACCESS_TOKEN, "acc_001", new SyncPosition.FromTime(FROM), TO);

            assertThat(page.transactions()).hasSize(100);
            assertThat(page.nextCursor()).isEqualTo("tx_100");
        }

        @Test
        @DisplayName("NextPage position — sends the cursor as `since`, keeps `before` pinned")
        void withCursor() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("account_id", equalTo("acc_001"))
                    .withQueryParam("since", equalTo("tx_100"))
                    .withQueryParam("before", equalTo("2024-12-16T00:00:00Z"))
                    .withQueryParam("expand[]", equalTo("merchant"))
                    .withQueryParam("limit", equalTo("100"))
                    .willReturn(okJson("""
                            {"transactions":[
                              {"id":"tx_101","amount":-200,"currency":"GBP","description":"Bus","merchant":null,
                               "notes":null,"decline_reason":null,
                               "created":"2024-01-03T09:00:00Z","settled":null}
                            ]}
                            """)));

            BankTransactionPage page = client.getTransactions(
                    ACCESS_TOKEN, "acc_001", new SyncPosition.NextPage("tx_100"), TO);

            assertThat(page.transactions()).hasSize(1);
            assertThat(page.transactions().get(0).payload().externalId()).isEqualTo("tx_101");
            assertThat(page.transactions().get(0).payload().merchantName()).isNull();
            assertThat(page.transactions().get(0).payload().settledAt()).isNull();
            assertThat(page.nextCursor()).isNull(); // short page
        }

        @Test
        @DisplayName("AfterTransaction position — sends the delta transaction id as `since`")
        void afterTransactionSendsIdAsSince() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("account_id", equalTo("acc_001"))
                    .withQueryParam("since", equalTo("tx_last_synced"))
                    .withQueryParam("before", equalTo("2024-12-16T00:00:00Z"))
                    .willReturn(okJson("""
                            {"transactions":[
                              {"id":"tx_102","amount":-300,"currency":"GBP","description":"Lunch","merchant":null,
                               "notes":null,"decline_reason":null,
                               "created":"2024-01-04T12:00:00Z","settled":null}
                            ]}
                            """)));

            BankTransactionPage page = client.getTransactions(
                    ACCESS_TOKEN, "acc_001", new SyncPosition.AfterTransaction("tx_last_synced"), TO);

            assertThat(page.transactions()).hasSize(1);
            assertThat(page.transactions().get(0).payload().externalId()).isEqualTo("tx_102");
        }

        @Test
        @DisplayName("401 throws ProviderConnectionRevokedException")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", new SyncPosition.FromTime(FROM), TO))
                    .isInstanceOf(ProviderConnectionRevokedException.class);
        }

        @Test
        @DisplayName("403 verification_required throws ProviderReauthRequiredException")
        void verificationRequiredThrowsReauth() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(aResponse()
                            .withStatus(403)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"code\":\"forbidden.verification_required\",\"message\":\"Re-authenticate\"}")));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", new SyncPosition.FromTime(FROM), TO))
                    .isInstanceOf(ProviderReauthRequiredException.class);
        }

        @Test
        @DisplayName("generic 403 (no verification_required code) throws ProviderException")
        void generic403ThrowsClientException() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(aResponse()
                            .withStatus(403)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"code\":\"forbidden\",\"message\":\"Access denied\"}")));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", new SyncPosition.FromTime(FROM), TO))
                    .isInstanceOf(ProviderException.class);
        }

        @Test
        @DisplayName("decline_reason maps to declined=true")
        void declinedTransaction() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("since", equalTo("2024-01-01T00:00:00Z"))
                    .willReturn(okJson("""
                            {"transactions":[
                              {"id":"tx_declined","amount":0,"currency":"GBP","description":"Declined payment",
                               "merchant":{"name":"Shop","category":"shopping"},"notes":null,
                               "decline_reason":"insufficient_funds",
                               "created":"2024-06-01T10:00:00Z","settled":null}
                            ]}
                            """)));

            BankTransactionPage page = client.getTransactions(ACCESS_TOKEN, "acc_001", new SyncPosition.FromTime(FROM), TO);

            assertThat(page.transactions()).hasSize(1);
            assertThat(page.transactions().get(0).payload().declined()).isTrue();
            assertThat(page.transactions().get(0).payload().amountMinorUnits()).isEqualTo(0);
        }

        @Test
        @DisplayName("429 throws ProviderException")
        void rateLimitedThrowsClientException() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(aResponse().withStatus(429)));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", new SyncPosition.FromTime(FROM), TO))
                    .isInstanceOf(ProviderException.class);
        }

        @Test
        @DisplayName("unknown fields are preserved in rawJson")
        void unknownFieldsPreserved() throws Exception {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("since", equalTo("2024-01-01T00:00:00Z"))
                    .willReturn(okJson(fixture("transactions/transactions-unknown-fields.json"))));

            BankTransactionPage page = client.getTransactions(ACCESS_TOKEN, "acc_001", new SyncPosition.FromTime(FROM), TO);

            assertThat(page.transactions()).hasSize(1);
            String raw = page.transactions().get(0).rawJson();
            assertThat(raw).contains("\"local_amount\":-350");
            assertThat(raw).contains("\"category\":\"eating_out\"");
        }

        @Test
        @DisplayName("short page returns nextCursor null")
        void shortPageNullCursor() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("since", equalTo("2024-01-01T00:00:00Z"))
                    .willReturn(okJson("""
                            {"transactions":[
                              {"id":"tx_001","amount":-10,"currency":"GBP","description":"Tx","merchant":null,
                               "notes":null,"decline_reason":null,
                               "created":"2024-06-01T10:00:00Z","settled":null}
                            ]}
                            """)));

            BankTransactionPage page = client.getTransactions(ACCESS_TOKEN, "acc_001", new SyncPosition.FromTime(FROM), TO);

            assertThat(page.nextCursor()).isNull();
        }
    }
}
