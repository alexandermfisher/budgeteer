package dev.amfshr.budgeteer.integration;

import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.repository.AccountRepository;
import dev.amfshr.budgeteer.repository.TransactionRepository;
import dev.amfshr.budgeteer.service.auth.SessionService;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@DisplayName("Account endpoints")
class AccountEndpointsIT extends AbstractMonzoWireMockIT {

    @LocalServerPort private int port;

    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private SessionService sessionService;
    @Autowired private TestDataFactory testData;

    private User user;
    private String accessToken;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        user = testData.createVerifiedUser();
        accessToken = sessionService.createSession(user, "Test", "127.0.0.1").accessToken();
    }

    @Test
    @DisplayName("GET /api/v1/accounts rejects unauthenticated requests (403, house-wide default)")
    void unauthenticated403() {
        given().get("/api/v1/accounts").then().statusCode(403);
    }

    @Test
    @DisplayName("GET /api/v1/accounts returns the user's accounts in the envelope")
    void listsOwnAccounts() {
        testData.createBankAccount(user, "acc_ep_001");

        given().cookie("access_token", accessToken)
                .get("/api/v1/accounts")
                .then().statusCode(200)
                .body("success", is(true))
                .body("data", hasSize(1))
                .body("data[0].provider", is("MONZO"))
                .body("data[0].institutionName", is("Monzo"));
    }

    @Test
    @DisplayName("user isolation: another user's accounts are invisible")
    void userIsolation() {
        User other = testData.createVerifiedUser();
        testData.createBankAccount(other);

        given().cookie("access_token", accessToken)
                .get("/api/v1/accounts")
                .then().statusCode(200)
                .body("data", hasSize(0));
    }

    @Test
    @DisplayName("archived accounts are hidden unless includeArchived=true")
    void archivedHiddenByDefault() {
        Account account = testData.createBankAccount(user);
        account.archive();
        accountRepository.save(account);

        given().cookie("access_token", accessToken)
                .get("/api/v1/accounts")
                .then().statusCode(200)
                .body("data", hasSize(0));

        given().cookie("access_token", accessToken)
                .queryParam("includeArchived", true)
                .get("/api/v1/accounts")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].archived", is(true));
    }

    @Test
    @DisplayName("summary sums the account's transactions; out is a positive magnitude")
    void summaryComputesSums() {
        Account account = testData.createBankAccount(user);
        testData.createDomainTransaction(account, user, -700, Instant.now().minusSeconds(60));
        testData.createDomainTransaction(account, user, 1200, Instant.now().minusSeconds(30));

        given().cookie("access_token", accessToken)
                .get("/api/v1/accounts/{id}/summary", account.getId())
                .then().statusCode(200)
                .body("data.zone", is("Europe/London"))
                .body("data.today.inMinorUnits", is(1200))
                .body("data.today.outMinorUnits", is(700));
    }

    @Test
    @DisplayName("another user's account id is a 404, not a 403")
    void foreignAccount404() {
        User other = testData.createVerifiedUser();
        Account foreign = testData.createBankAccount(other);

        given().cookie("access_token", accessToken)
                .get("/api/v1/accounts/{id}/summary", foreign.getId())
                .then().statusCode(404);
    }

    @Test
    @DisplayName("unknown account id is a 404")
    void unknownAccount404() {
        given().cookie("access_token", accessToken)
                .get("/api/v1/accounts/{id}/summary", UUID.randomUUID())
                .then().statusCode(404);
    }
}
