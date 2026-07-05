# Bank-Client Modules — Renames, Contract Additions & Monzo Jar Hardening

> **Status:** Queue (spec ready to build)
> **Priority:** P1 | **Estimate:** ~2–2.5d | **Branch:** `refactor/bank-client-modules`

## Goal

Three things, one pass over the client modules:

1. **Execute the module naming scheme** (decided 2026-07-05): `common` → `bank-client-api`,
   `monzo-client` → `bank-client-monzo`, `budgeteer-api` → `budgeteer-server`.
2. **Contract additions the domain layer needs** (from `.agents/notes/domain-model-design.md`):
   `getBalance` → `BankBalance`, and `rawJson` raw-capture on `BankTransaction` / `BankAccount`.
   These land now so the contract is touched exactly once and #11 (domain model mapping) starts
   unblocked.
3. **Harden `bank-client-monzo`** into a properly self-contained Spring Boot library — a single
   configured entry point the consumer wires up once, after which everything comes to life purely
   from being on the classpath. The finished jar is the template for `bank-client-truelayer`.

---

## 0. Module Renames

| Old | New | Why |
|-----|-----|-----|
| `common` | `bank-client-api` | `-api` is the standard Java suffix for a contract-only jar (`slf4j-api`, `jakarta.persistence-api`) |
| `monzo-client` | `bank-client-monzo` | family-prefix grouping (`spring-data-jpa` pattern) — implementations sort together and each new bank slots into the scheme |
| `budgeteer-api` | `budgeteer-server` | frees "api" for the contract jar; pairs with `frontend/` (client/server); the module is the whole orchestrator, not just a REST surface |

**Java packages do NOT move** — `dev.amfshr.budgeteer.bank`, `dev.amfshr.budgeteer.client.monzo`,
and the app root stay exactly as they are. Module names and package names are independent.

Do the renames as **one pure-mechanical commit** before any behaviour change:

- [ ] `git mv common bank-client-api && git mv monzo-client bank-client-monzo && git mv budgeteer-api budgeteer-server`
- [ ] Root `pom.xml` `<modules>` — three new names
- [ ] Each module pom: `artifactId` + `<name>` — `bank-client-api` / "Bank Client API",
      `bank-client-monzo` / "Bank Client — Monzo", `budgeteer-server` / "Budgeteer Server"
- [ ] Consumer poms: dependency `artifactId`s (`bank-client-monzo/pom.xml` → `bank-client-api`;
      `budgeteer-server/pom.xml` → both jars)
- [ ] Root `<dependencyManagement>`: managed `artifactId`s
- [ ] `.github/workflows/ci.yml` — `working-directory`, `cache-dependency-path`, artifact paths
- [ ] `.github/workflows/codeql.yml` — same
- [ ] `.github/dependabot.yml` — `directory: "/budgeteer-api"` → `"/budgeteer-server"`
- [ ] `.github/CODEOWNERS` — path update
- [ ] `scripts/dev.sh` — all `budgeteer-api` path refs, incl. any fat-jar filename refs
      (the boot jar becomes `budgeteer-server-*.jar`)
- [ ] `.idea/runConfigurations/*.xml` — module/path refs (PR #67 touched these; check)
- [ ] Repo-wide grep for stragglers:
      `grep -rn 'budgeteer-api\|monzo-client' --include='*.md' --include='*.yml' --include='*.yaml' --include='*.sh' --include='*.xml' . | grep -v target/`
      — repoint **living** docs/config only (`.agents/context/*`, `docs/`, README, compose).
      Historical plans under `.agents/tasks/closed/` describe the past — leave them.
- [ ] `./mvnw clean verify` green from root

---

## Current State — What's Wrong (post-#67)

### 1. Auto-config is missing entirely

- `MonzoBankClient` is `@Component` — only works because the app scans `client.monzo`. Wrong for a library.
- `MonzoProperties` javadoc says it's registered via `@ConfigurationPropertiesScan` in
  `BudgeteerApplication` — the jar should own its own registration.
- `MonzoClientConfig` creates the `RestClient` internally with a bare `baseUrl` and nothing else —
  no timeouts, no logging interceptors, no connection pool config. Consumer has no way to customise it.
- No `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

### 2. The contract is missing what the domain layer needs

- No balance method — the domain design stores account balance as a provider-fetched snapshot,
  and raw `monzo_accounts` has no balance column. Nothing can serve the Connected Accounts page.
- No raw-payload capture — Monzo history >90 days is behind a 5-minute SCA window and effectively
  unrecoverable; fields not mapped at sync time are lost forever without a raw blob.

### 3. Dead code in `dto/`

`dto/TokenResponse.java` is an unused record. `MonzoBankClient` uses `Map<String,Object>` →
`MonzoMapper.toBankTokens` instead. Delete it.

### 4. Flat package — won't scale

Everything lives in `client.monzo`. As the client grows (balance endpoint, pots, webhooks,
more DTOs, retry logic) one flat package becomes unreadable. Need clear sub-structure.

### 5. Test coverage is thin

One test class (`MonzoBankClientTest`) with inline JSON strings and zero fixture files.
Missing entirely: `getIdentity`, `buildAuthorizationUrl`, `getAccounts` 403/429,
`getTransactions` 429, auto-config wiring, mapper edge cases.

### 6. `getIdentity` uses raw `Map.class`

`restClient.get()...retrieve().body(Map.class)` suppresses unchecked. Should use a typed DTO
(`MonzoWhoAmIResponse`) consistent with how every other endpoint is handled.

---

## Target Package Structure

```
bank-client-monzo/src/main/java/dev/amfshr/budgeteer/client/monzo/
  autoconfigure/
    MonzoAutoConfiguration.java        ← @AutoConfiguration — the single wiring point
  dto/
    MonzoAccountResponse.java          ← keep
    MonzoAccountsResponse.java         ← keep
    MonzoMerchantResponse.java         ← keep
    MonzoTransactionResponse.java      ← keep
    MonzoTransactionsResponse.java     ← keep
    MonzoWhoAmIResponse.java           ← NEW: typed DTO for /ping/whoami
    MonzoBalanceResponse.java          ← NEW: typed DTO for /balance
    (delete TokenResponse.java)
  MonzoBankClient.java                 ← plain class, no @Component
  MonzoMapper.java                     ← package-private, keep
  MonzoProperties.java                 ← keep, fix javadoc
  package-info.java                    ← keep

bank-client-monzo/src/main/resources/
  META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports  ← NEW

bank-client-monzo/src/test/java/dev/amfshr/budgeteer/client/monzo/
  autoconfigure/
    MonzoAutoConfigurationTest.java    ← NEW: ApplicationContextRunner tests
  MonzoBankClientTest.java             ← expand: add missing cases, load from fixtures
  MonzoMapperTest.java                 ← NEW: edge cases for all mapping methods

bank-client-monzo/src/test/resources/
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
      accounts-unknown-fields.json     (raw-capture test: fields NOT in the DTO)
    balance/
      balance-success.json
    transactions/
      transactions-first-page.json
      transactions-full-page.json      (100 tx for cursor test)
      transactions-with-cursor.json
      transactions-declined.json
      transactions-unknown-fields.json (raw-capture test)
```

---

## 1. Contract Additions (`bank-client-api`)

### 1a. `getBalance`

```java
// BankClient — new method
/**
 * Current balance for one account, as reported by the provider.
 * @throws BankConnectionRevokedException if the connection is revoked (401)
 * @throws BankClientException on any other upstream failure
 */
BankBalance getBalance(String accessToken, String accountId);

// new neutral record — dev.amfshr.budgeteer.bank.BankBalance
public record BankBalance(long balanceMinorUnits, String currency) {}
```

Lean by policy (Decision #5 of the #6 spec): only what the app will persist (the domain design's
`Account.balance_minor_units` snapshot; `balance_as_of` is stamped by the caller at fetch time).
TrueLayer extras (available balance, credit limit) arrive later as `@Nullable` additions.

Monzo impl: `GET /balance?account_id={id}` → new `dto/MonzoBalanceResponse(balance, total_balance,
currency, spend_today)`; map `balance` + `currency`, ignore the rest. Neutral exceptions exactly as
the other endpoints (401 → revoked, other → `BankClientException`).

### 1b. `rawJson` raw-capture

`@Nullable String rawJson` becomes the **last component** of `BankTransaction` and `BankAccount`.
Purpose: audit trail + field backfill without re-fetching (unmapped fields are otherwise lost —
see Current State #2).

**Correctness requirement — true raw, not DTO re-serialisation.** Jackson DTOs drop unknown JSON
properties, so `objectMapper.writeValueAsString(dto)` would silently lose exactly the fields raw
capture exists to keep. `MonzoBankClient` must instead:

1. Retrieve the accounts / transactions response body as `JsonNode`.
2. Iterate the array node; for each element take **both**
   `objectMapper.treeToValue(node, MonzoTransactionResponse.class)` (typed mapping) **and**
   `node.toString()` (the raw capture).
3. `MonzoMapper.toBankTransaction` / `toBankAccount` take `(dto, rawJson)` and pass it through.

The `ObjectMapper` comes via the auto-config — `ObjectProvider<ObjectMapper>` with
`getIfAvailable(ObjectMapper::new)` fallback — and into `MonzoBankClient`'s constructor
(`RestClient`, `MonzoProperties`, `ObjectMapper`).

**Ripple:** both record constructors gain a component — update `MonzoMapper` and every
`budgeteer-server` test fixture that constructs neutral records (`TransactionSyncServiceTest`
etc. — mechanical). Production `budgeteer-server` code **ignores** `rawJson` and `getBalance`
until #11 persists them; consuming them here is out of scope.

---

## 2. Auto-Configuration

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
            MonzoProperties props,
            ObjectProvider<ObjectMapper> objectMapper
    ) {
        return new MonzoBankClient(restClient, props, objectMapper.getIfAvailable(ObjectMapper::new));
    }
}
```

```
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
dev.amfshr.budgeteer.client.monzo.autoconfigure.MonzoAutoConfiguration
```

```java
// budgeteer-server: MonzoClientConfig.java (consumer owns RestClient creation)
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
| `MonzoBankClient.java` | Remove `@Component`; constructor gains `ObjectMapper` |
| `MonzoClientConfig.java` (jar) | **Delete** — consumer app owns `RestClient` creation |
| `autoconfigure/MonzoAutoConfiguration.java` | **New** |
| `META-INF/.../AutoConfiguration.imports` | **New** |
| `MonzoProperties.java` | Fix javadoc (remove "registered via @ConfigurationPropertiesScan") |
| `BudgeteerApplication.java` | Narrow `@ConfigurationPropertiesScan` back to app-only properties once auto-config handles `MonzoProperties` registration |
| `budgeteer-server/.../MonzoClientConfig.java` | **New** — replaces the deleted jar-side one |

---

## 3. Dead Code & DTO Cleanup

- **Delete `dto/TokenResponse.java`** — unused; `MonzoBankClient` uses `Map<String,Object>` →
  `MonzoMapper.toBankTokens`.
- **Add `dto/MonzoWhoAmIResponse.java`** — replace raw `Map.class` in `getIdentity`; eliminates
  `@SuppressWarnings("unchecked")`:

```java
public record MonzoWhoAmIResponse(
        @JsonProperty("user_id") String userId,
        @JsonProperty("authenticated") boolean authenticated
) {}
```

- **Add `dto/MonzoBalanceResponse.java`** — see §1a.

---

## 4. Test Hardening

### Fixture-driven approach

Extract all inline JSON strings to `src/test/resources/wiremock/` files. Tests load them with a
helper (see the `TestMonzoData` / fixture pattern used in the server module). Inline JSON is fine
for trivial cases; fixtures are required for any JSON with more than ~5 fields.

### `MonzoBankClientTest` — add missing cases

| Method | Missing tests |
|--------|--------------|
| `getIdentity` | happy path, 401 → `BankConnectionRevokedException`, 403 → `BankClientException`, empty/null `user_id` → `BankClientException` |
| `buildAuthorizationUrl` | verify all required query params present (client_id, redirect_uri, response_type, state) |
| `getAccounts` | 403 → `BankClientException`, 429 → `BankClientException`, empty list returns empty `List` |
| `getTransactions` | 429 → `BankClientException` |
| `getBalance` | happy path, 401, 403/429, malformed body |
| `exchangeCode` | missing access_token in response → `BankClientException` |
| `refreshTokens` | 403 case |
| **raw capture** | fixture containing fields **not** in the DTO (`*-unknown-fields.json`) → assert those fields appear verbatim in `rawJson` (proves no unknown-field loss); assert `rawJson` populated on accounts + transactions happy paths |

### `MonzoMapperTest` — new

Unit-test all mapper methods in isolation (no WireMock/HTTP):
- `toBankTokens`: `expiresIn` present, `expiresIn` null, `refreshToken` null
- `toBankAccount`: normal, `description` null, `created` null / blank / malformed, `rawJson` passthrough
- `toBankTransaction`: settled null/blank, `declined` true/false, merchant null vs present,
  `notes` null, `rawJson` passthrough
- `toBankBalance`: normal, currency present

### `MonzoAutoConfigurationTest` — new

Use `ApplicationContextRunner` (no server boot):

```java
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

## 5. Token Injection — Confirmed: Keep Parameter Passing

`BankClient` methods take `accessToken` as a plain `String` parameter. One bean, many users —
token varies per call. Interceptor would need thread-local/request-scoped token passing — more
complexity, no benefit. Token storage, decryption (AES-256-GCM), and refresh stay in
`budgeteer-server`. The jar receives plaintext only. **No change.**

---

## 6. Shared OAuth → `bank-client-api`? (Resolved: No)

The contract lives in `bank-client-api` (`BankClient.buildAuthorizationUrl / exchangeCode / refreshTokens`).
A shared *impl* in `bank-client-api` would pull `spring-web`/HTTP into a dependency-light jar. Each
client jar implements its own (Monzo vs TrueLayer OAuth differ). OAuth state orchestration
(CSRF, DB-backed `OAuthState`, `User` link) stays in `budgeteer-server`. Revisit only if real
duplication appears once `bank-client-truelayer` exists (rule of three).

---

## 7. Multi-Bank Context

Once `bank-client-monzo` auto-configures correctly, `bank-client-truelayer` copies the same
pattern. With both jars on the classpath there will be two `BankClient` beans. The auto-config
should use a named qualifier (`@Bean("monzo")`) so the app can select by provider — likely a
`Map<Provider, BankClient>` registry in `budgeteer-server`. Out of scope here; note for TrueLayer.

---

## Out of Scope

- Consuming `rawJson` / `getBalance` in `budgeteer-server` (persistence, jobs, endpoints) — that's
  #11 domain model mapping. This task only updates server test fixtures for the record changes.
- Capability interfaces (`SupportsCards`, …), `BANK_*` error codes, client resilience — existing
  backlog items, unchanged.
- `bank-client-truelayer` scaffolding.

---

## Implementation Checklist

**Commit 1 — module renames (pure mechanical)**
- [ ] Everything in §0, ending `./mvnw clean verify` green

**Commit 2 — contract additions**
- [ ] `BankBalance` record + `BankClient.getBalance` javadoc'd method
- [ ] `rawJson` component on `BankTransaction` + `BankAccount`
- [ ] `dto/MonzoBalanceResponse.java`; `getBalance` impl in `MonzoBankClient`
- [ ] `JsonNode`-based fetch in `getAccounts`/`getTransactions`; `MonzoMapper` takes `(dto, rawJson)`
- [ ] Update `budgeteer-server` test fixtures for the new record components (mechanical)

**Commit 3 — auto-config + structural cleanup**
- [ ] Delete `dto/TokenResponse.java`; add `dto/MonzoWhoAmIResponse.java`; retype `getIdentity`
- [ ] Remove `@Component` from `MonzoBankClient`; delete jar-side `MonzoClientConfig`
- [ ] `autoconfigure/MonzoAutoConfiguration.java` + `META-INF/.../AutoConfiguration.imports`
- [ ] `budgeteer-server` `MonzoClientConfig` (consumer-owned `RestClient`)
- [ ] Fix `MonzoProperties` javadoc; narrow `@ConfigurationPropertiesScan` in `BudgeteerApplication`

**Commit 4 — tests**
- [ ] `wiremock/` fixture tree (incl. `*-unknown-fields.json` raw-capture fixtures)
- [ ] `MonzoBankClientTest` — fixtures + all missing cases (incl. `getBalance`, raw capture)
- [ ] `MonzoMapperTest`, `MonzoAutoConfigurationTest`

**Verify**
- [ ] `./mvnw test` — all green
- [ ] Boot `budgeteer-server` locally — Monzo OAuth flow end-to-end still works
- [ ] `/check` (checkstyle + unit + integration) green before raising the PR
