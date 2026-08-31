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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@DisplayName("Transaction endpoints")
class TransactionEndpointsIT extends AbstractMonzoWireMockIT {

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-01-03T00:00:00Z");

    @LocalServerPort private int port;

    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private SessionService sessionService;
    @Autowired private TestDataFactory testData;

    private User user;
    private Account account;
    private String accessToken;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        user = testData.createVerifiedUser();
        account = testData.createBankAccount(user);
        accessToken = sessionService.createSession(user, "Test", "127.0.0.1").accessToken();
    }

    @Test
    @DisplayName("GET /api/v1/transactions rejects unauthenticated requests (403, house-wide default)")
    void unauthenticated403() {
        given().get("/api/v1/transactions").then().statusCode(403);
    }

    @Test
    @DisplayName("pages newest-first with the house paging envelope")
    void pagesNewestFirst() {
        testData.createDomainTransaction(account, user, -100, T1);
        testData.createDomainTransaction(account, user, -200, T2);
        testData.createDomainTransaction(account, user, -300, T3);

        given().cookie("access_token", accessToken)
                .queryParam("size", 2)
                .get("/api/v1/transactions")
                .then().statusCode(200)
                .body("success", is(true))
                .body("data.items", hasSize(2))
                .body("data.items[0].amountMinorUnits", is(-300))   // T3 first — newest
                .body("data.items[1].amountMinorUnits", is(-200))
                .body("data.page", is(0))
                .body("data.size", is(2))
                .body("data.totalElements", is(3))
                .body("data.totalPages", is(2));
    }

    @Test
    @DisplayName("from/to filter is half-open [from, to)")
    void halfOpenRange() {
        testData.createDomainTransaction(account, user, -100, T1);
        testData.createDomainTransaction(account, user, -200, T2);
        testData.createDomainTransaction(account, user, -300, T3);

        given().cookie("access_token", accessToken)
                .queryParam("from", T1.toString())
                .queryParam("to", T3.toString())
                .get("/api/v1/transactions")
                .then().statusCode(200)
                .body("data.items", hasSize(2))                     // T3 excluded, T1 included
                .body("data.items[0].amountMinorUnits", is(-200))
                .body("data.items[1].amountMinorUnits", is(-100));
    }

    @Test
    @DisplayName("user isolation: another user's transactions are invisible")
    void userIsolation() {
        User other = testData.createVerifiedUser();
        Account otherAccount = testData.createBankAccount(other);
        testData.createDomainTransaction(otherAccount, other, -999, T1);

        given().cookie("access_token", accessToken)
                .get("/api/v1/transactions")
                .then().statusCode(200)
                .body("data.items", hasSize(0));
    }

    @Test
    @DisplayName("filtering by a non-owned accountId returns an empty page, not 404")
    void foreignAccountFilterEmptyPage() {
        User other = testData.createVerifiedUser();
        Account otherAccount = testData.createBankAccount(other);
        testData.createDomainTransaction(otherAccount, other, -999, T1);
        testData.createDomainTransaction(account, user, -100, T1);

        given().cookie("access_token", accessToken)
                .queryParam("accountId", otherAccount.getId().toString())
                .get("/api/v1/transactions")
                .then().statusCode(200)
                .body("data.items", hasSize(0))
                .body("data.totalElements", is(0));
    }

    @Test
    @DisplayName("accountId filter scopes to that account")
    void accountIdFilter() {
        Account second = testData.createBankAccount(user);
        testData.createDomainTransaction(account, user, -100, T1);
        testData.createDomainTransaction(second, user, -200, T2);

        given().cookie("access_token", accessToken)
                .queryParam("accountId", second.getId().toString())
                .get("/api/v1/transactions")
                .then().statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].amountMinorUnits", is(-200));
    }
}
