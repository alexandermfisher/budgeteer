package dev.amf.budgeteer.client.monzo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.client.monzo.dto.MonzoAccountResponse;
import dev.amf.budgeteer.client.monzo.dto.MonzoTransactionResponse;
import dev.amf.budgeteer.config.MonzoProperties;
import dev.amf.budgeteer.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
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
        @DisplayName("first page — no sinceId, always includes expand[]=merchant")
        void firstPageNoSinceId() {
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

            List<MonzoTransactionResponse> result = client.getTransactions(ACCESS_TOKEN, "acc_001", null, 100);

            assertThat(result).hasSize(1);
            MonzoTransactionResponse tx = result.get(0);
            assertThat(tx.id()).isEqualTo("tx_001");
            assertThat(tx.amount()).isEqualTo(-500);
            assertThat(tx.currency()).isEqualTo("GBP");
            assertThat(tx.merchant()).isNotNull();
            assertThat(tx.merchant().name()).isEqualTo("Starbucks");
            assertThat(tx.merchant().category()).isEqualTo("eating_out");
            assertThat(tx.declineReason()).isNull();
        }

        @Test
        @DisplayName("subsequent page — sinceId included in request")
        void withSinceId() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .withQueryParam("account_id", equalTo("acc_001"))
                    .withQueryParam("since_id", equalTo("tx_100"))
                    .withQueryParam("expand[]", equalTo("merchant"))
                    .withQueryParam("limit", equalTo("100"))
                    .willReturn(okJson("""
                            {"transactions":[
                              {"id":"tx_101","amount":-200,"currency":"GBP","description":"Bus","merchant":null,
                               "notes":null,"decline_reason":null,
                               "created":"2024-01-03T09:00:00Z","settled":null}
                            ]}
                            """)));

            List<MonzoTransactionResponse> result = client.getTransactions(ACCESS_TOKEN, "acc_001", "tx_100", 100);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("tx_101");
            assertThat(result.get(0).merchant()).isNull();
            assertThat(result.get(0).settled()).isNull();
        }

        @Test
        @DisplayName("401 maps to MONZO_CONNECTION_REVOKED")
        void unauthorizedThrowsRevoked() {
            wm.stubFor(get(urlPathEqualTo("/transactions"))
                    .willReturn(unauthorized()));

            assertThatThrownBy(() -> client.getTransactions(ACCESS_TOKEN, "acc_001", null, 100))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.MONZO_CONNECTION_REVOKED));
        }
    }
}
