# Monzo-Client Jar — Auto-Configuration, Test Hardening & Structural Cleanup

> **Status:** Queue (ready to build — run `/start-task monzo-client-hardening` to begin)
> **Priority:** P2 | **Estimate:** ~1.5–2d | **Branch:** `refactor/monzo-client-autoconfig`

## Goal

Turn `monzo-client` from a jar that only works because it borrows the app's component-scan
into a **properly self-contained, decoupled, well-tested Spring Boot library** — a single
configured entry point the consumer app wires up once, after which everything "comes to life"
purely from being on the classpath. This becomes the template for `truelayer-client`.

---

## Current State — What's Wrong

### 1. Auto-config is missing entirely

- `MonzoBankClient` is `@Component` — only works if the app scans `client.monzo`. Wrong for a library.
- `MonzoProperties` javadoc says it's registered via `@ConfigurationPropertiesScan` in
  `BudgeteerApplication` — the jar should own its own registration.
- `MonzoClientConfig` creates the `RestClient` internally with a bare `baseUrl` and nothing else —
  no timeouts, no logging interceptors, no connection pool config. Consumer has no way to customise it.
- No `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

### 2. Dead code in `dto/`

`dto/TokenResponse.java` is an unused record. `MonzoBankClient` uses `Map<String,Object>` → 
`MonzoMapper.toBankTokens` instead. Delete it.

### 3. Flat package — won't scale

Everything lives in `client.monzo`. As the client grows (balance endpoint, pots, webhooks,
more DTOs, retry logic) one flat package becomes unreadable. Need clear sub-structure.

### 4. Test coverage is thin

One test class (`MonzoBankClientTest`) with inline JSON strings and zero fixture files.
Missing entirely:
- `getIdentity` — no tests at all
- `buildAuthorizationUrl` — no tests
- `getAccounts` 403 / 429 cases
- `getTransactions` 429 case
- Auto-config wiring (does the auto-config actually fire? does `@ConditionalOnMissingBean` work?)
- Mapper edge cases (`MonzoMapperTest`)

### 5. `getIdentity` uses raw `Map.class`

`restClient.get()...retrieve().body(Map.class)` suppresses unchecked. Should use a typed DTO
(`MonzoWhoAmIResponse`) consistent with how every other endpoint is handled.

---

## Target Package Structure

```
monzo-client/src/main/java/dev/amfshr/budgeteer/client/monzo/
  autoconfigure/
    MonzoAutoConfiguration.java        ← @AutoConfiguration — the single wiring point
  dto/
    MonzoAccountResponse.java          ← keep
    MonzoAccountsResponse.java         ← keep
    MonzoMerchantResponse.java         ← keep
    MonzoTransactionResponse.java      ← keep
    MonzoTransactionsResponse.java     ← keep
    MonzoWhoAmIResponse.java           ← NEW: typed DTO for /ping/whoami
    (delete TokenResponse.java)
  MonzoBankClient.java                 ← plain class, no @Component
  MonzoMapper.java                     ← package-private, keep
  MonzoProperties.java                 ← keep, fix javadoc
  package-info.java                    ← keep

monzo-client/src/main/resources/
  META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports  ← NEW

monzo-client/src/test/java/dev/amfshr/budgeteer/client/monzo/
  autoconfigure/
    MonzoAutoConfigurationTest.java    ← NEW: ApplicationContextRunner tests
  MonzoBankClientTest.java             ← expand: add missing cases, load from fixtures
  MonzoMapperTest.java                 ← NEW: edge cases for all three mapping methods

monzo-client/src/test/resources/
  wiremock/
    oauth/
      exchange-code-success.json
      refresh-tokens-success.json
      refresh-tokens-no-rotate.json
    identity/
      whoami-success.json
    accounts/
      accounts-success.json
      accounts-empty.json
    transactions/
      transactions-first-page.json
      transactions-full-page.json      (100 tx for cursor test)
      transactions-with-cursor.json
      transactions-declined.json
```

---

## 1. Auto-Configuration

### Design: consumer-owned `RestClient`

The consumer app defines a `@Bean RestClient monzoRestClient(...)` — it owns baseUrl, timeouts,
logging interceptors, connection pool. The auto-config picks it up via `@Qualifier("monzoRestClient")`
and uses it to construct `MonzoBankClient`. The jar never creates its own `RestClient`.

```java
// MonzoAutoConfiguration.java
@AutoConfiguration
@EnableConfigurationProperties(MonzoProperties.class)
@ConditionalOnClass(MonzoBankClient.class)
public class MonzoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MonzoBankClient monzoBankClient(
            @Qualifier("monzoRestClient") RestClient restClient,
            MonzoProperties props
    ) {
        return new MonzoBankClient(restClient, props);
    }
}
```

```
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
dev.amfshr.budgeteer.client.monzo.autoconfigure.MonzoAutoConfiguration
```

```java
// budgeteer-api: MonzoClientConfig.java (consumer owns RestClient creation)
@Configuration
public class MonzoClientConfig {

    @Bean
    public RestClient monzoRestClient(MonzoProperties props, RestClient.Builder builder) {
        return builder
            .baseUrl(props.apiBaseUrl())
            // timeouts, interceptors, logging here — consumer in full control
            .build();
    }
}
```

### What to change

| File | Action |
|------|--------|
| `MonzoBankClient.java` | Remove `@Component` |
| `MonzoClientConfig.java` | **Delete** — consumer app owns `RestClient` creation |
| `autoconfigure/MonzoAutoConfiguration.java` | **New** |
| `META-INF/.../AutoConfiguration.imports` | **New** |
| `MonzoProperties.java` | Fix javadoc (remove "registered via @ConfigurationPropertiesScan") |
| `BudgeteerApplication.java` | Narrow `@ConfigurationPropertiesScan` back to app-only properties once auto-config handles `MonzoProperties` registration |
| `budgeteer-api/MonzoClientConfig.java` | **New** — replaces the deleted jar-side one |

---

## 2. Dead Code & DTO Cleanup

### Delete `dto/TokenResponse.java`

Not used anywhere. `MonzoBankClient` uses `Map<String,Object>` → `MonzoMapper.toBankTokens`.

### Add `dto/MonzoWhoAmIResponse.java`

Replace raw `Map.class` in `getIdentity`:

```java
// MonzoWhoAmIResponse.java
public record MonzoWhoAmIResponse(
        @JsonProperty("user_id") String userId,
        @JsonProperty("authenticated") boolean authenticated
) {}
```

Update `MonzoBankClient.getIdentity` to use it — eliminates `@SuppressWarnings("unchecked")`.

---

## 3. Test Hardening

### Fixture-driven approach

Extract all inline JSON strings to `src/test/resources/wiremock/` files. Tests load them with a
helper (see the `TestMonzoData` / fixture pattern used in `budgeteer-api`). Inline JSON is fine for
trivial cases; fixtures are required for any JSON with more than ~5 fields (transactions, accounts).

### `MonzoBankClientTest` — add missing cases

| Method | Missing tests |
|--------|--------------|
| `getIdentity` | happy path, 401 → `BankConnectionRevokedException`, 403 → `BankClientException`, empty/null `user_id` → `BankClientException` |
| `buildAuthorizationUrl` | verify all required query params are present (client_id, redirect_uri, response_type, state) |
| `getAccounts` | 403 → `BankClientException`, 429 → `BankClientException`, empty list returns empty `List` |
| `getTransactions` | 429 → `BankClientException` |
| `exchangeCode` | missing access_token in response → `BankClientException` |
| `refreshTokens` | 403 case |

### `MonzoMapperTest` — new

Unit-test all three mapper methods in isolation (no WireMock/HTTP):
- `toBankTokens`: `expiresIn` present, `expiresIn` null, `refreshToken` null
- `toBankAccount`: normal, `description` null, `created` null / blank / malformed
- `toBankTransaction`: settled null/blank, `declined` true/false, merchant null vs present,
  `notes` null

### `MonzoAutoConfigurationTest` — new

Use `ApplicationContextRunner` (no server boot):

```java
// MonzoAutoConfigurationTest.java
class MonzoAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MonzoAutoConfiguration.class))
            .withPropertyValues(
                "monzo.client-id=cid",
                "monzo.client-secret=secret",
                "monzo.redirect-uri=http://localhost/cb",
                "monzo.auth-url=http://auth",
                "monzo.token-url=http://token",
                "monzo.api-base-url=http://api"
            );

    @Test
    void registersMonzoBankClientWhenRestClientPresent() {
        runner
            .withBean("monzoRestClient", RestClient.class, () -> RestClient.create())
            .run(ctx -> assertThat(ctx).hasSingleBean(MonzoBankClient.class));
    }

    @Test
    void backOffWhenConsumerDefinesOwnMonzoBankClient() {
        runner
            .withBean("monzoRestClient", RestClient.class, () -> RestClient.create())
            .withBean(MonzoBankClient.class, () -> mock(MonzoBankClient.class))
            .run(ctx -> assertThat(ctx).hasSingleBean(MonzoBankClient.class));
    }
}
```

---

## 4. Token Injection — Confirmed: Keep Parameter Passing

`BankClient` methods take `accessToken` as a plain `String` parameter. One bean, many users —
token varies per call. Interceptor would need thread-local/request-scoped token passing — more
complexity, no benefit. Token storage, decryption (AES-256-GCM), and refresh stay in
`budgeteer-api`. The jar receives plaintext only. **No change.**

---

## 5. Shared OAuth → `common`? (Resolved: No)

The contract lives in `common` (`BankClient.buildAuthorizationUrl / exchangeCode / refreshTokens`).
A shared *impl* in `common` would pull `spring-web`/HTTP into a dependency-light jar. Each
client jar implements its own (Monzo vs TrueLayer OAuth differ). OAuth state orchestration
(CSRF, DB-backed `OAuthState`, `User` link) stays in `budgeteer-api`.

---

## 6. Multi-Bank Context

Once `monzo-client` auto-configures correctly, `truelayer-client` copies the same pattern.
With both jars on the classpath there will be two `BankClient` beans. The auto-config should
use a named qualifier (`@Bean("monzo") MonzoBankClient`) so the app can select by provider.
This is out of scope for this task — note it for TrueLayer.

---

## Implementation Checklist

**Structural cleanup**
- [ ] Delete `dto/TokenResponse.java`
- [ ] Add `dto/MonzoWhoAmIResponse.java`
- [ ] Update `MonzoBankClient.getIdentity` to use `MonzoWhoAmIResponse` (remove raw Map + `@SuppressWarnings`)

**Auto-config**
- [ ] Remove `@Component` from `MonzoBankClient`
- [ ] Delete `MonzoClientConfig.java` from `monzo-client`
- [ ] Create `autoconfigure/MonzoAutoConfiguration.java`
- [ ] Create `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- [ ] Add `MonzoClientConfig.java` to `budgeteer-api` (consumer owns `RestClient`)
- [ ] Fix `MonzoProperties` javadoc
- [ ] Narrow `@ConfigurationPropertiesScan` in `BudgeteerApplication` (or remove — auto-config now handles it via `@EnableConfigurationProperties`)

**Tests**
- [ ] Create `src/test/resources/wiremock/` fixture directory with subdirs and JSON files
- [ ] Refactor `MonzoBankClientTest` to load fixtures; add all missing test cases
- [ ] Create `MonzoMapperTest`
- [ ] Create `autoconfigure/MonzoAutoConfigurationTest`

**Verify**
- [ ] `./mvnw test` — all tests pass
- [ ] Boot `budgeteer-api` locally — Monzo OAuth flow end-to-end still works
