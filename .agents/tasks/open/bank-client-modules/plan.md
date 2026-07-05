# Bank-Client Modules — Renames, Contract Additions & Monzo Jar Hardening

> **Status:** Queue (spec ready to build — deep-grounded against the repo on 2026-07-05)
> **Priority:** P1 | **Estimate:** ~2–2.5d | **Branch:** `refactor/bank-client-modules`
>
> This spec is written for handoff: every new file's full contents, every changed method's full
> target body, and exact file:line references for mechanical edits are included. If reality
> disagrees with a line reference, trust the description of the change, re-locate, and proceed;
> if reality disagrees with a *design statement*, stop and ask.

## Goal

Five commits, one pass over the client modules:

1. **Module renames**: `common` → `bank-client-api`, `monzo-client` → `bank-client-monzo`,
   `budgeteer-api` → `budgeteer-server`.
2. **Package rename**: `dev.amfshr.budgeteer.common.bank` → `dev.amfshr.budgeteer.bank`
   (restores the #6 spec's intended layout; "common" is meaningless once the module is renamed).
3. **Contract additions** the domain layer (#11) needs: `getBalance` → `BankBalance`, and
   `rawJson` raw-capture on `BankTransaction` / `BankAccount`.
4. **Auto-configuration + cleanup**: the jar self-wires from the classpath; consumer owns the
   `RestClient`; dead code deleted; typed DTOs throughout.
5. **Test hardening**: fixture-driven WireMock tests, mapper unit tests, auto-config tests,
   all missing error cases. The finished jar is the template for `bank-client-truelayer`.

---

## Ground Truth (verified against the repo, 2026-07-05)

Facts below were read from source — where they contradict older plans/docs, these win:

| Fact | Value |
|---|---|
| Parent | `spring-boot-starter-parent` **4.1.0** (root `pom.xml`) |
| Contract package | **`dev.amfshr.budgeteer.common.bank`** (NOT `.bank` — the #6 implementation deviated from its spec) |
| Contract types | `BankClient`, `BankTokens`, `BankIdentity`, `BankAccount`, `BankTransaction`, `BankTransactionPage`, 3 exceptions, `package-info.java` (`@NullMarked`) — 10 files |
| Files importing `common.bank` | **14**: 11 in `budgeteer-api` (5 main: `GlobalExceptionHandler`, `MonzoController`, `MonzoOAuthService`, `MonzoTokenRefreshService`, `TransactionSyncService`; 6 test: `MonzoControllerTest`, `MonzoOAuthFlowIT`, `MonzoTokenRefreshIT`, `MonzoOAuthServiceTest`, `MonzoTokenRefreshServiceTest`, `TransactionSyncServiceTest`) + 3 in `monzo-client` (`MonzoBankClient`, `MonzoMapper`, `MonzoBankClientTest`) |
| `MonzoBankClient` | `@Component`; ctor `(MonzoProperties, RestClient monzoRestClient)`; `PAGE_SIZE = 100`; token + whoami endpoints parse `Map.class`; accounts/transactions bind DTO classes directly |
| `MonzoMapper` | package-private **static utility** (private ctor); `toBankTokens(Map)`, `toBankAccount(dto)`, `toBankTransaction(dto)` |
| DTO style | records with **explicit canonical constructors carrying `@JsonProperty` on every param** — new DTOs must copy this pattern |
| Dead code | `dto/TokenResponse.java` — zero usages |
| Test state | one class, `MonzoBankClientTest` (336 lines, 7 inline JSON text blocks, constructs `new MonzoBankClient(props, restClient)` at line 48); **no fixture files, no mapper test, no auto-config test** |
| App properties classes | all 5 live in `dev.amfshr.budgeteer.config` (`MonzoTokenRefreshProperties`, `AppProperties`, `JweProperties`, `EncryptionProperties`, `TransactionSyncProperties`) — so `@ConfigurationPropertiesScan` can be narrowed safely |
| `BudgeteerApplication` | `@SpringBootApplication` + `@ConfigurationPropertiesScan("dev.amfshr.budgeteer")` + `@EnableScheduling` |
| CI | **builds from repo root** (`mvn install/test/checkstyle:check`, `cache-dependency-path: '**/pom.xml'`) — module renames touch only ci.yml:95 (a comment), dependabot.yml:15, CODEOWNERS:12 |
| `scripts/dev.sh` | 5 × `cd "$PROJECT_ROOT/budgeteer-api"` (lines 185, 264, 271, 289, 301); no fat-jar filename refs (uses `mvn spring-boot:run`) |
| `.idea/` | `encodings.xml` (lines 5–7), `compiler.xml` (module names ×4), `runConfigurations/Budgeteer___Local_Dev.xml` (lines 7, 10) reference old module names |
| Checkstyle | max line **120**; keep methods ≤50 lines (extract helpers where specified below) |
| neutral-record ctor call sites (tests) | `MonzoControllerTest` ×1, `MonzoOAuthServiceTest` ×2, `MonzoTokenRefreshServiceTest` ×3, `TransactionSyncServiceTest` ×18 — only `new BankTransaction(...)` / `new BankAccount(...)` calls are affected by `rawJson` |

---

## Commit 1 — Module renames (pure mechanical)

| Old | New | pom `<name>` |
|-----|-----|--------------|
| `common` | `bank-client-api` | Bank Client API |
| `monzo-client` | `bank-client-monzo` | Bank Client — Monzo |
| `budgeteer-api` | `budgeteer-server` | Budgeteer Server |

Steps (macOS; `sed -i ''`):

1. `git mv common bank-client-api && git mv monzo-client bank-client-monzo && git mv budgeteer-api budgeteer-server`
2. Root `pom.xml`:
   - `<modules>`: `common|monzo-client|budgeteer-api` → `bank-client-api|bank-client-monzo|budgeteer-server`
   - `<dependencyManagement>`: managed artifactIds `common` → `bank-client-api`, `monzo-client` → `bank-client-monzo`
3. `bank-client-api/pom.xml`: `<artifactId>common</artifactId>` → `bank-client-api`; `<name>Common</name>` → `Bank Client API`
4. `bank-client-monzo/pom.xml`: `<artifactId>monzo-client</artifactId>` → `bank-client-monzo`; `<name>Monzo Client</name>` → `Bank Client — Monzo`; dependency `<artifactId>common</artifactId>` → `bank-client-api`
5. `budgeteer-server/pom.xml`: own `<artifactId>` → `budgeteer-server`; `<name>` → `Budgeteer Server`; dependency artifactIds at lines ~19/~23 (`common`, `monzo-client`) → new names
6. `.github/dependabot.yml:15`: `directory: "/budgeteer-api"` → `"/budgeteer-server"`
7. `.github/CODEOWNERS:12`: `/budgeteer-api/` → `/budgeteer-server/`
8. `.github/workflows/ci.yml:95`: update the comment (`budgeteer-api` → `budgeteer-server`)
9. `scripts/dev.sh` lines 185, 264, 271, 289, 301: `$PROJECT_ROOT/budgeteer-api` → `$PROJECT_ROOT/budgeteer-server`
10. `.idea/encodings.xml`, `.idea/compiler.xml`, `.idea/runConfigurations/Budgeteer___Local_Dev.xml`: replace the three module names (skip `workspace.xml` — transient)
11. Straggler check (expect no hits in poms/workflows/scripts; hits in `.agents/tasks/closed/` and historical docs are fine — leave them):
    `grep -rn 'artifactId>common<\|artifactId>monzo-client<\|artifactId>budgeteer-api<' --include=pom.xml .`
    `grep -rn 'budgeteer-api' .github scripts compose.yaml README.md docs/README.md`
12. Update living docs that name modules: `.agents/context/architecture.md`, `.agents/context/commands.md`, `README.md` (module tree / paths, if present)

**Verify:** `./mvnw clean verify` green from root. `./scripts/dev.sh` still starts (optional smoke).

---

## Commit 2 — Package rename `common.bank` → `bank`

Java packages otherwise do NOT move; this is the single exception, restoring the #6 spec's
intended `dev.amfshr.budgeteer.bank` and removing the stale "common" segment.

1. `git mv bank-client-api/src/main/java/dev/amfshr/budgeteer/common/bank bank-client-api/src/main/java/dev/amfshr/budgeteer/bank`
   then delete the now-empty `.../budgeteer/common/` directory.
2. Sweep declarations + imports (24 edits across the 10 contract files + 14 importers):
   `grep -rl 'dev\.amfshr\.budgeteer\.common\.bank' --include='*.java' . | xargs sed -i '' 's/dev\.amfshr\.budgeteer\.common\.bank/dev.amfshr.budgeteer.bank/g'`
3. Straggler check: `grep -rn 'common\.bank' --include='*.java' .` → zero hits.

**Verify:** `./mvnw clean verify` green. No behaviour change possible — pure rename.

---

## Commit 3 — Contract additions (`bank-client-api`)

### 3a. New file `bank-client-api/src/main/java/dev/amfshr/budgeteer/bank/BankBalance.java`

```java
package dev.amfshr.budgeteer.bank;

/**
 * Provider-neutral account balance snapshot. {@code balanceMinorUnits} is signed
 * (a credit card in debt is negative). Lean by policy: only what the app persists —
 * provider extras (available balance, credit limit) arrive later as {@code @Nullable} additions.
 */
public record BankBalance(
        long balanceMinorUnits,
        String currency
) {}
```

### 3b. `BankClient.java` — add one method (after `getAccounts`, before `getTransactions`)

```java
    /**
     * Current balance for one account, as reported by the provider. The caller stamps the
     * fetch time; this record carries no timestamp.
     *
     * @throws BankConnectionRevokedException if the connection is revoked (401)
     * @throws BankClientException on any other upstream failure
     */
    BankBalance getBalance(String accessToken, String accountId);
```

### 3c. `rawJson` on `BankAccount` + `BankTransaction`

Add `@Nullable String rawJson` as the **last** component of each record, with this javadoc on
the component line: `// verbatim provider JSON for this element; null if unavailable`. Target
`BankTransaction` (unchanged components elided here — keep them exactly as they are):

```java
public record BankTransaction(
        String externalId,
        long amountMinorUnits,
        String currency,
        @Nullable String description,
        @Nullable String merchantName,
        @Nullable String merchantCategory,
        @Nullable String notes,
        boolean declined,
        Instant createdAt,
        @Nullable Instant settledAt,
        @Nullable String rawJson
) {}
```

`BankAccount` likewise gains `@Nullable String rawJson` after `createdAt`.

**Why raw, and why it must be true raw:** Monzo history >90 days is behind a 5-minute SCA
window — unmapped fields are unrecoverable after sync. Jackson DTOs drop unknown JSON
properties, so re-serialising a DTO would lose exactly the fields this exists to keep. The
capture must come from the raw `JsonNode`, per 3e.

**Sensitivity + `toString()` override (required).** `rawJson` can contain provider fields the
typed model deliberately omits — for Monzo accounts that includes `account_number` and
`sort_code`. Two rules:

1. Records auto-generate `toString()` including ALL components — a stray `log.debug("{}", tx)`
   would dump raw payloads (bank identifiers included) into logs. Override `toString()` on BOTH
   records to redact it. For `BankAccount`:

   ```java
       @Override
       public String toString() {
           return ("BankAccount[externalId=%s, type=%s, description=%s, currency=%s, "
                   + "closed=%s, createdAt=%s, rawJson=%s]")
                   .formatted(externalId, type, description, currency, closed, createdAt,
                           rawJson == null ? "null" : "<redacted>");
       }
   ```

   Equivalent for `BankTransaction` (keep every non-raw component in the output). Add a unit
   test per record asserting `toString()` does NOT contain a sentinel value placed in `rawJson`.
2. Never log `rawJson` anywhere. In memory it is plaintext — the same trust boundary as the
   plaintext access tokens this jar already handles. At rest it is persisted ONLY encrypted;
   that happens in `budgeteer-server` in #11 (AES-256-GCM via the existing `EncryptionService`),
   never in this jar.

### 3d. New DTO `bank-client-monzo/.../dto/MonzoBalanceResponse.java`

Copy the existing explicit-`@JsonProperty`-constructor style:

```java
package dev.amfshr.budgeteer.client.monzo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MonzoBalanceResponse(
        long balance,
        long totalBalance,
        String currency,
        long spendToday
) {
    public MonzoBalanceResponse(
            @JsonProperty("balance") long balance,
            @JsonProperty("total_balance") long totalBalance,
            @JsonProperty("currency") String currency,
            @JsonProperty("spend_today") long spendToday
    ) {
        this.balance = balance;
        this.totalBalance = totalBalance;
        this.currency = currency;
        this.spendToday = spendToday;
    }
}
```

### 3e. `MonzoBankClient` — constructor, `getBalance`, JsonNode-based fetch

**Constructor** gains an `ObjectMapper` (used by the JsonNode mapping):

```java
    private final MonzoProperties monzoProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public MonzoBankClient(MonzoProperties monzoProperties, RestClient monzoRestClient,
                           ObjectMapper objectMapper) {
        this.monzoProperties = monzoProperties;
        this.restClient = monzoRestClient;
        this.objectMapper = objectMapper;
    }
```

**New method `getBalance`** (after `getAccounts`; same error pattern as its siblings):

```java
    @Override
    public BankBalance getBalance(String accessToken, String accountId) {
        log.debug("Fetching Monzo balance [accountId={}]", accountId);
        try {
            MonzoBalanceResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/balance")
                            .queryParam("account_id", accountId).build())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(MonzoBalanceResponse.class);

            if (response == null) {
                throw new BankClientException("Empty response from Monzo balance endpoint");
            }
            return new BankBalance(response.balance(), response.currency());

        } catch (RestClientResponseException e) {
            handleMonzoError(e, "balance");
            throw new BankClientException("Failed to fetch Monzo balance: " + e.getMessage(), e);
        }
    }
```

**`getAccounts`** — retrieve `JsonNode` instead of the wrapper DTO; body of the `try` becomes:

```java
            JsonNode root = restClient.get()
                    .uri("/accounts")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null || !root.path("accounts").isArray()) {
                throw new BankClientException("Empty response from Monzo accounts endpoint");
            }

            List<BankAccount> accounts = mapArray(root.get("accounts"),
                    MonzoAccountResponse.class, MonzoMapper::toBankAccount);
            log.debug("Fetched {} Monzo accounts", accounts.size());
            return accounts;
```

**`getTransactions`** — same change; replace the DTO-bound retrieve + mapping block with:

```java
            JsonNode root = restClient.get()
                    .uri(uri.build().toUriString())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null || !root.path("transactions").isArray()) {
                throw new BankClientException("Empty response from Monzo transactions endpoint");
            }

            JsonNode array = root.get("transactions");
            List<BankTransaction> mapped = mapArray(array,
                    MonzoTransactionResponse.class, MonzoMapper::toBankTransaction);

            String nextCursor = array.size() >= PAGE_SIZE
                    ? mapped.get(mapped.size() - 1).externalId()
                    : null;
```

(cursor-label logging and the return stay as today; note the cursor now reads
`mapped.get(...).externalId()` instead of the raw DTO list — same value, the tx id.)

**New private helper** (keeps both methods under the 50-line checkstyle limit; place in the
Private Methods section):

```java
    /** Map each element of a JSON array via its DTO, capturing the element's verbatim JSON. */
    private <D, B> List<B> mapArray(JsonNode array, Class<D> dtoType,
                                    BiFunction<D, String, B> mapper) {
        List<B> result = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            try {
                D dto = objectMapper.treeToValue(node, dtoType);
                result.add(mapper.apply(dto, node.toString()));
            } catch (JsonProcessingException e) {
                throw new BankClientException("Failed to parse Monzo response element", e);
            }
        }
        return result;
    }
```

New imports in `MonzoBankClient`: `com.fasterxml.jackson.core.JsonProcessingException`,
`com.fasterxml.jackson.databind.JsonNode`, `com.fasterxml.jackson.databind.ObjectMapper`,
`dev.amfshr.budgeteer.bank.BankBalance`, `dev.amfshr.budgeteer.client.monzo.dto.MonzoBalanceResponse`,
`java.util.ArrayList`, `java.util.function.BiFunction`. The `MonzoAccountsResponse` /
`MonzoTransactionsResponse` wrapper imports become unused — remove the imports but **keep the
DTO files** (they document the wire shape and cost nothing; delete only if checkstyle objects).

### 3f. `MonzoMapper` — signatures gain `rawJson`

```java
    static BankAccount toBankAccount(MonzoAccountResponse ar, @Nullable String rawJson) {
        ...
        return new BankAccount(ar.id(), ar.type(), ar.description(), ar.currency(),
                ar.closed(), createdAt, rawJson);
    }

    static BankTransaction toBankTransaction(MonzoTransactionResponse tx, @Nullable String rawJson) {
        ...
        return new BankTransaction(tx.id(), tx.amount(), tx.currency(), tx.description(),
                tx.merchant() != null ? tx.merchant().name() : null,
                tx.merchant() != null ? tx.merchant().category() : null,
                tx.notes(), declined, createdAt, settledAt, rawJson);
    }
```

`toBankTokens` is unchanged. The method-reference call sites in 3e (`MonzoMapper::toBankAccount`
etc.) match the new two-arg signatures via `BiFunction`.

### 3g. Ripple — `budgeteer-server` tests (mechanical)

Every `new BankTransaction(...)` / `new BankAccount(...)` in these files gains a trailing
`null` argument (or a literal like `"{}"` where a test asserts passthrough):

| File | call sites |
|---|---|
| `service/monzo/TransactionSyncServiceTest.java` | up to 18 (only the `BankTransaction`/`BankAccount` ones — `BankTokens`/`BankIdentity` calls are untouched) |
| `service/monzo/MonzoTokenRefreshServiceTest.java` | check its 3 |
| `service/monzo/MonzoOAuthServiceTest.java` | check its 2 |
| `api/monzo/MonzoControllerTest.java` | check its 1 |

If `TransactionSyncServiceTest` has a fixture-builder helper, add the arg there once. Production
`budgeteer-server` code compiles unchanged (it only *reads* the records) and **must not** start
consuming `rawJson`/`getBalance` — that's #11.

**Verify:** `./mvnw clean verify` green. `MonzoOAuthFlowIT` + `MonzoTokenRefreshIT` (WireMock
against the real `MonzoBankClient`) are the behaviour-preservation safety net for the JsonNode
fetch rewrite — they must pass untouched apart from record-ctor updates.

---

## Commit 4 — Auto-configuration + structural cleanup

### 4a. Delete dead code; retype `getIdentity`

- **Delete** `dto/TokenResponse.java` (zero usages — verified).
- **New** `dto/MonzoWhoAmIResponse.java` (same explicit-ctor style):

```java
package dev.amfshr.budgeteer.client.monzo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record MonzoWhoAmIResponse(
        boolean authenticated,
        @Nullable String userId
) {
    public MonzoWhoAmIResponse(
            @JsonProperty("authenticated") boolean authenticated,
            @JsonProperty("user_id") @Nullable String userId
    ) {
        this.authenticated = authenticated;
        this.userId = userId;
    }
}
```

- `getIdentity`: replace the `Map.class` body (and its `@SuppressWarnings`) with
  `.body(MonzoWhoAmIResponse.class)`; null response → same "Empty response…" exception;
  `response.userId()` null **or blank** → `BankClientException("No user_id in Monzo whoami response")`;
  happy path `return new BankIdentity(response.userId(), null);`. (The token endpoints keep
  `Map.class` — OAuth token responses stay map-parsed via `MonzoMapper.toBankTokens`.)

### 4b. Auto-configuration

- Remove `@Component` from `MonzoBankClient` (and its now-unused import).
- **Delete** jar-side `MonzoClientConfig.java`.
- **New** `bank-client-monzo/src/main/java/dev/amfshr/budgeteer/client/monzo/autoconfigure/MonzoAutoConfiguration.java`:

```java
package dev.amfshr.budgeteer.client.monzo.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.amfshr.budgeteer.client.monzo.MonzoBankClient;
import dev.amfshr.budgeteer.client.monzo.MonzoProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Wires a {@link MonzoBankClient} from the consumer's {@code monzoRestClient} bean.
 * The consumer owns RestClient creation (baseUrl, timeouts, interceptors); this jar never
 * builds its own.
 */
@AutoConfiguration
@EnableConfigurationProperties(MonzoProperties.class)
public class MonzoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MonzoBankClient monzoBankClient(
            @Qualifier("monzoRestClient") RestClient restClient,
            MonzoProperties properties,
            ObjectProvider<ObjectMapper> objectMapper
    ) {
        return new MonzoBankClient(properties, restClient,
                objectMapper.getIfAvailable(ObjectMapper::new));
    }
}
```

- **New** `bank-client-monzo/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  containing exactly one line:

```
dev.amfshr.budgeteer.client.monzo.autoconfigure.MonzoAutoConfiguration
```

- **New** `budgeteer-server/src/main/java/dev/amfshr/budgeteer/config/MonzoClientConfig.java`
  (consumer-owned `RestClient`; same bean name the auto-config qualifies on):

```java
package dev.amfshr.budgeteer.config;

import dev.amfshr.budgeteer.client.monzo.MonzoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The app owns the Monzo RestClient — baseUrl, and later timeouts/interceptors/pooling.
 * MonzoAutoConfiguration (bank-client-monzo jar) picks this bean up by name.
 */
@Configuration
public class MonzoClientConfig {

    @Bean
    RestClient monzoRestClient(MonzoProperties monzoProperties) {
        return RestClient.builder()
                .baseUrl(monzoProperties.apiBaseUrl())
                .build();
    }
}
```

- `BudgeteerApplication`: narrow `@ConfigurationPropertiesScan("dev.amfshr.budgeteer")` →
  `@ConfigurationPropertiesScan("dev.amfshr.budgeteer.config")` (all 5 app properties classes
  live there — verified; `MonzoProperties` is now registered by the auto-config).
- `MonzoProperties` javadoc: replace the "Registered as a bean via @ConfigurationPropertiesScan
  in BudgeteerApplication" line with "Registered via {@code MonzoAutoConfiguration}".

**Safety note for the implementer:** the auto-config class lives under the app's component-scan
root (`dev.amfshr.budgeteer.**`), which is fine *only* because `@SpringBootApplication`'s default
`AutoConfigurationExcludeFilter` excludes registered auto-configurations from scanning. Do not
replace `@SpringBootApplication` with a custom `@ComponentScan`, and do not add `@Configuration`
to `MonzoAutoConfiguration`.

**Verify:** `./mvnw clean verify` green — `MonzoOAuthFlowIT` booting the full context proves the
auto-config actually wires (it would fail on a missing `MonzoBankClient`/`MonzoProperties` bean).

---

## Commit 5 — Test hardening

### 5a. Fixture files — `bank-client-monzo/src/test/resources/wiremock/`

Extract the 7 inline JSON text blocks in `MonzoBankClientTest` into files; add new ones:

```
wiremock/
  oauth/exchange-code-success.json        oauth/refresh-tokens-success.json
  oauth/refresh-tokens-no-rotate.json     identity/whoami-success.json
  accounts/accounts-success.json          accounts/accounts-empty.json
  accounts/accounts-unknown-fields.json   balance/balance-success.json
  transactions/transactions-first-page.json
  transactions/transactions-full-page.json      (PAGE_SIZE=100 items → cursor present)
  transactions/transactions-with-cursor.json
  transactions/transactions-declined.json
  transactions/transactions-unknown-fields.json
```

Loader helper in the test class (or a small `TestFixtures` util):

```java
    private static String fixture(String path) throws IOException {
        try (var in = MonzoBankClientTest.class.getResourceAsStream("/wiremock/" + path)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
```

`balance/balance-success.json`:

```json
{"balance": 5000, "total_balance": 6000, "currency": "GBP", "spend_today": -120}
```

`transactions/transactions-unknown-fields.json` — the raw-capture proof; `local_amount` and
`category` are deliberately NOT in `MonzoTransactionResponse`:

```json
{
  "transactions": [
    {
      "id": "tx_unknownfields00000001",
      "amount": -350,
      "currency": "GBP",
      "description": "TEST COFFEE",
      "merchant": {"name": "Test Coffee", "category": "eating_out"},
      "notes": "",
      "created": "2026-06-01T10:00:00.000Z",
      "settled": "2026-06-02T10:00:00.000Z",
      "local_amount": -350,
      "category": "eating_out"
    }
  ]
}
```

(`accounts/accounts-unknown-fields.json` analogous: one account element plus e.g.
`"sort_code": "040004"` not present in `MonzoAccountResponse`.)

### 5b. `MonzoBankClientTest` — cases to add

| Method | New tests |
|--------|-----------|
| `getIdentity` | happy path (fixture); 401 → `BankConnectionRevokedException`; 403 → `BankClientException`; missing/blank `user_id` → `BankClientException` |
| `buildAuthorizationUrl` | asserts `client_id`, `redirect_uri`, `response_type=code`, `state` all present |
| `getAccounts` | 403 → `BankClientException`; 429 → `BankClientException`; empty list → empty `List`; **rawJson populated**; **unknown-fields fixture → `rawJson` contains `"sort_code"`** |
| `getBalance` | happy path (5000/GBP); 401 → revoked; 429 → `BankClientException`; malformed body → `BankClientException` |
| `getTransactions` | 429 → `BankClientException`; **unknown-fields fixture → `rawJson` contains `"local_amount"` and `"category"`** (proves no unknown-field loss); full-page fixture → `nextCursor` = last tx id; short page → `nextCursor` null |
| `exchangeCode` | missing `access_token` → `BankClientException` |
| `refreshTokens` | 403 → `BankClientException` |

Construction updates: `new MonzoBankClient(props, restClient, new ObjectMapper())`.

### 5c. New `MonzoMapperTest` (plain JUnit, no HTTP)

- `toBankTokens`: `expires_in` present → `expiresAt ≈ now+n`; `expires_in` null → null;
  `refresh_token` null → null
- `toBankAccount`: normal; `description` null; `created` null / blank / malformed → `createdAt`
  null; `rawJson` passthrough
- `toBankTransaction`: `settled` null and blank → `settledAt` null; `decline_reason` present →
  `declined=true`, absent/blank → false; `merchant` null → null name/category; `notes` null;
  `rawJson` passthrough

### 5d. New `autoconfigure/MonzoAutoConfigurationTest` (ApplicationContextRunner, no server)

```java
class MonzoAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MonzoAutoConfiguration.class))
            .withPropertyValues(
                    "monzo.client-id=cid", "monzo.client-secret=secret",
                    "monzo.redirect-uri=http://localhost/cb", "monzo.auth-url=http://auth",
                    "monzo.token-url=http://token", "monzo.api-base-url=http://api");

    @Test
    void registersMonzoBankClientWhenRestClientPresent() {
        runner.withBean("monzoRestClient", RestClient.class, RestClient::create)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MonzoBankClient.class);
                    assertThat(ctx).hasSingleBean(MonzoProperties.class);
                });
    }

    @Test
    void backsOffWhenConsumerDefinesOwnMonzoBankClient() {
        MonzoBankClient custom = mock(MonzoBankClient.class);
        runner.withBean("monzoRestClient", RestClient.class, RestClient::create)
                .withBean("customClient", MonzoBankClient.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(MonzoBankClient.class)).isSameAs(custom));
    }

    @Test
    void failsWithoutMonzoRestClientBean() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }
}
```

**Verify (final):** `./mvnw clean verify` green from root; boot `budgeteer-server` locally and
run the Monzo OAuth flow end-to-end (Postman collection); `/check` green before raising the PR.

---

## Out of Scope — do NOT do these

- Consuming `rawJson` / `getBalance` in `budgeteer-server` (persistence, jobs, endpoints) → #11.
- Any DB migration. Any change to `TransactionSyncService` windowing/commit/cursor logic beyond
  the record-constructor ripple.
- Capability interfaces, `BANK_*` error codes, retries/pooling/circuit breaker, `bank-client-truelayer`.
- Renaming any Java package other than `common.bank` → `bank` (Commit 2).

---

## Implementer Kickoff Prompt

> Copy-paste to the implementing model/tool.

You are implementing **Bank-Client Modules** in the Budgeteer repo (Spring Boot **4.1.0** /
Java 25; contract package `dev.amfshr.budgeteer.bank` after Commit 2). Read
`.agents/context/conventions.md`, `.agents/context/testing.md`, and this spec fully before
writing code. Work on branch `refactor/bank-client-modules` (branched from `main`).

Implement **exactly five commits in the order specified**, running the stated verification after
each; never move to the next commit on a red build. Use `git mv` for all renames. Full target
code in the spec is authoritative — transcribe it, adjusting only imports/formatting to satisfy
checkstyle (≤120 cols, ≤50-line methods, no star/unused imports). The spec's Ground Truth section
tells you the current state of every file you will touch; if a file:line has drifted, re-locate
by description. `MonzoOAuthFlowIT` and `MonzoTokenRefreshIT` are the behaviour-preservation
safety net — they must pass without modification beyond the neutral-record constructor ripple.

Do not add scope (see *Out of Scope*), do not redesign, do not consume the new contract methods
in the server. If something is genuinely ambiguous after reading the spec, stop and ask.

Done = all five commits green, `/check` passes from the repo root, OAuth flow live-tested, PR
raised referencing this plan.
