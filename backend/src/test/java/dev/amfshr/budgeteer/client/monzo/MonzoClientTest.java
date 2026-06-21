package dev.amfshr.budgeteer.client.monzo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.client.monzo.dto.MonzoAccountResponse;
import dev.amfshr.budgeteer.client.monzo.dto.MonzoTransactionResponse;
import dev.amfshr.budgeteer.config.MonzoProperties;
import dev.amfshr.budgeteer.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MonzoClient")
class MonzoClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private MonzoClient client;
    private static final String ACCESS_TOKEN = "test-access-token";

    @BeforeEach
    void setUp() {
        RestClient restClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .build();

        MonzoProperties props = mock(MonzoProperties.class);
        when(props.tokenUrl()).thenReturn(wm.baseUrl() + "/oauth2/token");

        client = new MonzoClient(props, restClient);
    }

    // ============ getAccounts ============

    @Nested
    @DisplayName("getAccounts")
    class GetAccounts {

        @Test
        @DisplayName("returns accounts on success")
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

            List<MonzoAccountResponse> result = client.getAccounts(ACCESS_TOKEN);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo("acc_001");
            assertThat(result.get(0).type()).isEqualTo("uk_retail");
            assertThat(result.get(0).currency()).isEqualTo("GBP");
            assertThat(result.get(0).closed()).isFalse();
            assertThat(result.get(1).id()).isEqualTo("acc_002");
            assertThat(result.get(1).closed()).isTrue();
        }

        @Test
        @DisplayName("401 maps to MONZO_CONNECTION_REVOKED")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(get(urlPathEqualTo("/accounts"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.getAccounts(ACCESS_TOKEN))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.MONZO_CONNECTION_REVOKED));
        }
    }

    // ============ getTransactions ============

    @Nested
    @DisplayName("getTransactions")
    class GetTransactions {

        @Test
        @DisplayName("no anchor and no cursor — only base params on URL")
        void noAnchorNoCursor() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("account_id", equalTo("acc_001"))
                    .withQueryParam("expand[]", equalTo("merchant"))
                    .withQueryParam("limit", equalTo("100"))
                    .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                    .willReturn(okJson("""
                            {
                              "transactions": [
                                {
                                  "id":"tx_001","amount":-500,"currency":"GBP",
                                  "description":"Coffee","merchant":{"name":"Starbucks","category":"eating_out"},
                                  "notes":"","decline_reason":null,
                                  "created":"2024-01-01T10:00:00Z","settled":"2024-01-02T00:00:00Z"
                                }
                              ]
                            }
                            """)));

            List<MonzoTransactionResponse> result = client.getTransactions(ACCESS_TOKEN, "acc_001", null, null, null, 100);

            assertThat(result).hasSize(1);
            MonzoTransactionResponse tx = result.get(0);
            assertThat(tx.id()).isEqualTo("tx_001");
            assertThat(tx.amount()).isEqualTo(-500);
            assertThat(tx.currency()).isEqualTo("GBP");
            assertThat(tx.merchant()).isNotNull();
            assertThat(tx.merchant().name()).isEqualTo("Starbucks");
            assertThat(tx.merchant().category()).isEqualTo("eating_out");
            assertThat(tx.declineReason()).isNull();

            wm.verify(getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("account_id", equalTo("acc_001")));
            wm.verify(0, getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("since", matching(".*")));
            wm.verify(0, getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("before", matching(".*")));
        }

        @Test
        @DisplayName("with since + before window — both included on URL, no since_id")
        void withSinceAndBeforeWindow() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("account_id", equalTo("acc_001"))
                    .withQueryParam("since", equalTo("2024-01-01T00:00:00Z"))
                    .withQueryParam("before", equalTo("2024-12-16T00:00:00Z"))
                    .withQueryParam("expand[]", equalTo("merchant"))
                    .withQueryParam("limit", equalTo("100"))
                    .willReturn(okJson("""
                            {"transactions":[
                              {"id":"tx_old","amount":-100,"currency":"GBP","description":"Old","merchant":null,
                               "notes":null,"decline_reason":null,
                               "created":"2024-06-01T09:00:00Z","settled":"2024-06-02T09:00:00Z"}
                            ]}
                            """)));

            List<MonzoTransactionResponse> result = client.getTransactions(
                    ACCESS_TOKEN, "acc_001", "2024-01-01T00:00:00Z", "2024-12-16T00:00:00Z", null, 100);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("tx_old");

            // Monzo has no since_id param; the timestamp window is sent verbatim on `since`/`before`.
            wm.verify(0, getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("since_id", matching(".*")));
        }

        @Test
        @DisplayName("with sinceId (cursor) — sent as `since`, no `since_id` or `before`")
        void withSinceId() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("account_id", equalTo("acc_001"))
                    .withQueryParam("since", equalTo("tx_100"))
                    .withQueryParam("expand[]", equalTo("merchant"))
                    .withQueryParam("limit", equalTo("100"))
                    .willReturn(okJson("""
                            {"transactions":[
                              {"id":"tx_101","amount":-200,"currency":"GBP","description":"Bus","merchant":null,
                               "notes":null,"decline_reason":null,
                               "created":"2024-01-03T09:00:00Z","settled":null}
                            ]}
                            """)));

            List<MonzoTransactionResponse> result = client.getTransactions(ACCESS_TOKEN, "acc_001", null, null, "tx_100", 100);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("tx_101");
            assertThat(result.get(0).merchant()).isNull();
            assertThat(result.get(0).settled()).isNull();

            // The cursor is sent via `since` (Monzo accepts a tx id there); no `since_id`, no `before`.
            wm.verify(getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("since", equalTo("tx_100")));
            wm.verify(0, getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("since_id", matching(".*")));
            wm.verify(0, getRequestedFor(urlPathEqualTo("/transactions"))
                    .withQueryParam("before", matching(".*")));
        }

        @Test
        @DisplayName("401 maps to MONZO_CONNECTION_REVOKED")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", null, null, null, 100))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.MONZO_CONNECTION_REVOKED));
        }

        @Test
        @DisplayName("403 verification_required maps to MONZO_VERIFICATION_REQUIRED")
        void verificationRequiredMapsToCorrectCode() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(aResponse()
                            .withStatus(403)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"code\":\"forbidden.verification_required\",\"message\":\"Re-authenticate\"}")));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", null, null, null, 100))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.MONZO_VERIFICATION_REQUIRED));
        }

        @Test
        @DisplayName("generic 403 (no verification_required code) maps to MONZO_API_ERROR")
        void generic403MapsToApiError() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(aResponse()
                            .withStatus(403)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"code\":\"forbidden\",\"message\":\"Access denied\"}")));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", null, null, null, 100))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.MONZO_API_ERROR));
        }
    }
}
