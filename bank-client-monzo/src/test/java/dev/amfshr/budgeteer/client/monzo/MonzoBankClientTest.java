package dev.amfshr.budgeteer.client.monzo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.amfshr.budgeteer.bank.BankAccount;
import dev.amfshr.budgeteer.bank.BankClientException;
import dev.amfshr.budgeteer.bank.BankConnectionRevokedException;
import dev.amfshr.budgeteer.bank.BankReauthRequiredException;
import dev.amfshr.budgeteer.bank.BankTokens;
import dev.amfshr.budgeteer.bank.BankTransaction;
import dev.amfshr.budgeteer.bank.BankTransactionPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MonzoBankClient")
class MonzoBankClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private MonzoBankClient client;
    private static final String ACCESS_TOKEN = "test-access-token";

    @BeforeEach
    void setUp() {
        RestClient restClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .build();

        MonzoProperties props = mock(MonzoProperties.class);
        when(props.tokenUrl()).thenReturn(wm.baseUrl() + "/oauth2/token");

        client = new MonzoBankClient(props, restClient, new tools.jackson.databind.ObjectMapper());
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
        @DisplayName("401 throws BankConnectionRevokedException")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.exchangeCode("test-code"))
                    .isInstanceOf(BankConnectionRevokedException.class);
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
        @DisplayName("401 throws BankConnectionRevokedException")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(post(urlPathEqualTo("/oauth2/token"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.refreshTokens("old-refresh"))
                    .isInstanceOf(BankConnectionRevokedException.class);
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

            List<BankAccount> result = client.getAccounts(ACCESS_TOKEN);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).externalId()).isEqualTo("acc_001");
            assertThat(result.get(0).type()).isEqualTo("uk_retail");
            assertThat(result.get(0).currency()).isEqualTo("GBP");
            assertThat(result.get(0).closed()).isFalse();
            assertThat(result.get(1).externalId()).isEqualTo("acc_002");
            assertThat(result.get(1).closed()).isTrue();
        }

        @Test
        @DisplayName("401 throws BankConnectionRevokedException")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(get(urlPathEqualTo("/accounts"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.getAccounts(ACCESS_TOKEN))
                    .isInstanceOf(BankConnectionRevokedException.class);
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

            BankTransactionPage page = client.getTransactions(ACCESS_TOKEN, "acc_001", FROM, TO, null);

            assertThat(page.nextCursor()).isNull(); // short page → end
            assertThat(page.transactions()).hasSize(1);
            BankTransaction tx = page.transactions().get(0);
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

            BankTransactionPage page = client.getTransactions(ACCESS_TOKEN, "acc_001", FROM, TO, null);

            assertThat(page.transactions()).hasSize(100);
            assertThat(page.nextCursor()).isEqualTo("tx_100");
        }

        @Test
        @DisplayName("with pageCursor — sends cursor as `since`, passes `from` as the timestamp window bound")
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

            BankTransactionPage page = client.getTransactions(ACCESS_TOKEN, "acc_001", FROM, TO, "tx_100");

            assertThat(page.transactions()).hasSize(1);
            assertThat(page.transactions().get(0).externalId()).isEqualTo("tx_101");
            assertThat(page.transactions().get(0).merchantName()).isNull();
            assertThat(page.transactions().get(0).settledAt()).isNull();
            assertThat(page.nextCursor()).isNull(); // short page
        }

        @Test
        @DisplayName("401 throws BankConnectionRevokedException")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", FROM, TO, null))
                    .isInstanceOf(BankConnectionRevokedException.class);
        }

        @Test
        @DisplayName("403 verification_required throws BankReauthRequiredException")
        void verificationRequiredThrowsReauth() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(aResponse()
                            .withStatus(403)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"code\":\"forbidden.verification_required\",\"message\":\"Re-authenticate\"}")));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", FROM, TO, null))
                    .isInstanceOf(BankReauthRequiredException.class);
        }

        @Test
        @DisplayName("generic 403 (no verification_required code) throws BankClientException")
        void generic403ThrowsClientException() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(aResponse()
                            .withStatus(403)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"code\":\"forbidden\",\"message\":\"Access denied\"}")));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", FROM, TO, null))
                    .isInstanceOf(BankClientException.class);
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

            BankTransactionPage page = client.getTransactions(ACCESS_TOKEN, "acc_001", FROM, TO, null);

            assertThat(page.transactions()).hasSize(1);
            assertThat(page.transactions().get(0).declined()).isTrue();
            assertThat(page.transactions().get(0).amountMinorUnits()).isEqualTo(0);
        }
    }
}
