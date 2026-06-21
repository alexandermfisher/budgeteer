package dev.amfshr.budgeteer.integration;

import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.repository.MonzoTransactionRepository;
import dev.amfshr.budgeteer.service.auth.SessionService;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

@DisplayName("Dev Sync Trigger Integration Tests")
@ActiveProfiles({"integration-test", "dev"})
class DevSyncTriggerIT extends AbstractMonzoWireMockIT {

    @LocalServerPort private int port;

    @Autowired private MonzoConnectionRepository connectionRepository;
    @Autowired private MonzoAccountRepository accountRepository;
    @Autowired private MonzoTransactionRepository transactionRepository;
    @Autowired private SessionService sessionService;
    @Autowired private TestDataFactory testData;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/dev/monzo/backfill triggers backfill and inserts rows")
    void triggerSyncInsertsRows() throws Exception {
        // Given — a user with an active connection and a valid session cookie
        User user = testData.createVerifiedUser();
        testData.createMonzoConnectionWithRealTokens(
                user, "test-refresh-" + UUID.randomUUID(),
                Instant.now().plus(6, ChronoUnit.HOURS)
        );

        SessionService.SessionTokens tokens = sessionService.createSession(user, "Test", "127.0.0.1");

        wm.stubFor(get(urlPathEqualTo("/accounts"))
                .willReturn(okJson("""
                        {"accounts":[
                          {"id":"acc_dev","type":"uk_retail","description":"Dev Account","currency":"GBP","closed":false}
                        ]}
                        """)));
        wm.stubFor(get(urlPathEqualTo("/transactions"))
                .withQueryParam("account_id", equalTo("acc_dev"))
                .willReturn(okJson("""
                        {"transactions":[
                          {"id":"tx_dev_001","amount":-100,"currency":"GBP","description":"Dev tx",
                           "merchant":null,"notes":null,"decline_reason":null,
                           "created":"2024-01-01T10:00:00Z","settled":null}
                        ]}
                        """)));

        // When
        given()
            .cookie("access_token", tokens.accessToken())
            .post("/api/dev/monzo/backfill")
            .then()
            .statusCode(200);

        // Then
        assertThat(accountRepository.findById("acc_dev")).isPresent();
        assertThat(transactionRepository.findByAccountId("acc_dev")).hasSize(1);
    }

    @Test
    @DisplayName("POST /api/dev/monzo/backfill returns 401 when unauthenticated")
    void requiresAuthentication() {
        given()
            .post("/api/dev/monzo/backfill")
            .then()
            .statusCode(401);
    }
}
