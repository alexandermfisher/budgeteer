# Testing

## Test types and naming

| Pattern | Type | When to use |
|---------|------|-------------|
| `*Test.java` | Unit | Single class, all dependencies mocked |
| `*IT.java` | Integration | Real DB, real HTTP (WireMock), full Spring context |

Both patterns are picked up by Surefire — no separate failsafe run.

---

## Unit tests

Controller unit tests use `@WebMvcTest(MyController.class)` with `@Import` to bring in
the security config and any other required components:

```java
@WebMvcTest(DevMonzoController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebMvcConfig.class,
        CurrentUserArgumentResolver.class})
@ActiveProfiles("dev")
class DevMonzoControllerTest { ... }
```

`SecurityConfig` pulls in `JweAuthenticationFilter`, which requires `JweTokenService` and
`CookieService` as constructor args — mock both even if the controller under test doesn't
use them directly, otherwise the context fails to load.

Authenticate requests in controller tests using `JweAuthentication` directly:

```java
private RequestPostProcessor authenticatedAs(UUID uid) {
    JweTokenService.TokenClaims claims = new JweTokenService.TokenClaims(
            uid, "test@example.com", Instant.now(),
            Instant.now().plusSeconds(3600), UUID.randomUUID().toString());
    return authentication(new JweAuthentication(claims));
}
```

---

## Integration test base classes

```
AbstractPostgresIntegrationTest        — Postgres via Testcontainers
    └── AbstractMonzoWireMockIT        — Postgres + WireMock for Monzo API
```

### AbstractPostgresIntegrationTest

- Starts a singleton `PostgreSQLContainer` in a `static {}` block
- `@DynamicPropertySource` configures the datasource and disables docker-compose
- Use for: repository tests, migration tests, any IT that needs a real DB but no HTTP mocking

### AbstractMonzoWireMockIT

- Extends `AbstractPostgresIntegrationTest` — you get Postgres automatically
- Starts a singleton `WireMockServer` in a `static {}` block (same pattern as Postgres)
- `@DynamicPropertySource` registers `monzo.token-url` and `monzo.api-base-url`
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` — inherited by all subclasses
- `@BeforeEach resetWireMock()` calls `wm.resetAll()` — clears stubs AND request journal before every test
- Use for: any IT that exercises code calling the Monzo API

```java
class MyMonzoIT extends AbstractMonzoWireMockIT {

    @LocalServerPort private int port;   // if you need to hit the app over HTTP

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        myRepository.deleteAll();        // wm.resetAll() is already called by base class
    }

    @Test
    void myTest() {
        wm.stubFor(get(urlPathEqualTo("/accounts"))
                .willReturn(okJson("...")));
        // ...
    }
}
```

---

## WireMock stub files vs inline stubs

File stubs live in `src/test/resources/wiremock/mappings/monzo/`. They are loaded at
server start but **immediately cleared** by `wm.resetAll()` before the first test runs —
so they are never served automatically.

Two options for stubbing:

**1. Inline (most common)** — define the stub directly in the test:
```java
wm.stubFor(get(urlPathEqualTo("/accounts"))
        .willReturn(okJson("{\"accounts\":[]}")));
```

**2. Load from file** — use `loadStubFromFile` (defined on `AbstractMonzoWireMockIT`)
to register a mapping JSON file at runtime:
```java
loadStubFromFile("wiremock/mappings/monzo/accounts/accounts-list.json");
loadStubFromFile("wiremock/mappings/monzo/transactions/transactions-list.json");
```

Use file stubs for generic/reusable responses; use inline stubs when the test needs
specific IDs, counts, or shapes that differ from the default.

File stub structure:
```
wiremock/mappings/monzo/
├── oauth/          token exchange + refresh
├── ping/           whoami
├── accounts/       GET /accounts default response
└── transactions/   GET /transactions default response
```

---

## TestDataFactory

`TestDataFactory` (in the `integration` package) creates domain objects with real DB
persistence. Use it in ITs instead of constructing entities manually:

```java
User user = testData.createVerifiedUser();
MonzoConnection conn = testData.createMonzoConnectionWithRealTokens(
        user, "refresh-token", Instant.now().plusSeconds(3600));
MonzoAccount account = testData.createMonzoAccount(conn, user, "acc_001");
MonzoTransaction tx = testData.createMonzoTransaction(account, user);
```

---

## Dev-profile-only controllers

Controllers annotated `@Profile("dev")` (e.g. `DevAuthController`, `DevMonzoController`)
are only loaded when the `dev` Spring profile is active. Integration tests that exercise
these endpoints must include `"dev"` in `@ActiveProfiles`:

```java
@ActiveProfiles({"integration-test", "dev"})
class DevSyncTriggerIT extends AbstractMonzoWireMockIT { ... }
```

Unit tests use `@ActiveProfiles("dev")` on the `@WebMvcTest` class.

---

## Mockito agent

Surefire is configured to pass Mockito as an explicit `-javaagent` to avoid the
"self-attaching" JVM warning (will become an error in a future JDK). The
`maven-dependency-plugin:properties` goal resolves the jar path into
`${org.mockito:mockito-core:jar}` which Surefire uses in `<argLine>`. This is already
wired in `pom.xml` — do not remove it.
