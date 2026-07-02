# Multi-Module Maven Restructure + Monzo Client Extraction

> **Priority:** P1 | **Estimate:** ~3.5–4d (4 phases) | **Status:** Spec ready | **Branch:** `refactor/multi-module-maven`

## Goal

Split the single-module app into **three Maven modules** — `budgeteer-common`, `monzo-client`,
`budgeteer-api` (renamed from `backend/`) — and extract Monzo's HTTP integration into the
`monzo-client` jar behind a provider-neutral **`BankClient` interface** defined in
`budgeteer-common`. The API programs to the interface and never imports a Monzo type. All
persistence (entities, repositories, OAuth state, encryption, jobs, controllers, neutral→domain
mapping) **stays in `budgeteer-api`**. This is the structural foundation for adding TrueLayer later
as a second `BankClient` implementation, without touching the app's orchestration or persistence.

This plan supersedes the older "4-module scaffolding-only" plan. It folds the board's **#6
(restructure)** and **#8 (source migration)** into one phased effort.

## Acceptance Criteria

- [ ] Repo builds as a 3-module reactor: `mvn clean verify` from root is green.
- [ ] `budgeteer-common` is a dependency-light jar (JSpecify only) holding `BankClient` + neutral
      records + neutral exceptions. No Spring dependency.
- [ ] `monzo-client` holds **only** the HTTP client, its Monzo JSON DTOs, the Monzo→neutral mapper,
      `MonzoProperties`, and `MonzoClientConfig`. It depends on `budgeteer-common` + `spring-web`.
- [ ] `budgeteer-api` (was `backend/`) depends on both jars; **no class under
      `dev.amfshr.budgeteer` (api) imports a `dev.amfshr.budgeteer.client.monzo.*` type** except the
      one `MonzoBankClient` bean wiring (which is by-interface, so effectively zero direct imports).
- [ ] `MonzoBankClient implements BankClient`; the API services inject `BankClient`, not the impl.
- [ ] Behaviour is **unchanged**: all existing tests pass, the OAuth flow, token refresh, backfill
      (per-window commits, SCA-pause/resume, retry, idempotent upsert) and delta sync all work
      exactly as before.
- [ ] CI (`Build & Test`), checkstyle, scripts (`dev.sh`), CODEOWNERS, dependabot, codeql all
      reference `budgeteer-api/` instead of `backend/` and pass.
- [ ] **No schema change, no new migration** — `backfill_progress_cursor` stays (it's intra-window
      paging state, kept exactly as today).

## Out of Scope

- **`truelayer-client` module** — deferred until TrueLayer work starts (scaffold then, against the
  proven interface). Tracked by the TrueLayer Integration backlog item.
- **Domain model mapping** (unified `transactions` table, raw `jsonb` capture, serving DTOs, custom
  categories, savings pots, analytics queries) — separate **"Domain Model Mapping"** backlog ticket.
  Run `/grill-me domain-model-mapping` for that. This refactor keeps the existing
  `monzo_transactions`/`monzo_accounts` tables and mapping as-is.
- **Capability interfaces** (`SupportsCards`, `SupportsRecurringPayments` for TrueLayer
  cards/standing-orders/direct-debits) — add when those features land.
- **Generic error codes** (`BANK_*` replacing `MONZO_*` in `ErrorCode`) — the API keeps mapping
  neutral exceptions to the existing `MONZO_*` codes for now (behaviour-preserving). Generalising to
  `BANK_*` is its own backlog task (see `tasks.md`), to land with or before TrueLayer.
- **MonzoClient resilience** (pooling, retries, circuit breaker) — existing backlog item, unchanged.

## Codebase Findings

Verified against the repo (the context docs are stale — do not trust them for these):

- **Spring Boot `4.0.6`, Java `25`.** `backend/pom.xml` parents directly from
  `spring-boot-starter-parent:4.0.6`. (`architecture.md` wrongly says "3.4".)
- **Base package: `dev.amfshr.budgeteer`** (rename #61 landed; `dev.amf` is gone from source).
- **A root `pom.xml` already exists**: `budgeteer-parent`, `packaging pom`, `<module>backend</module>`
  — but its `groupId` is still the stale **`dev.amf`** (rename missed it) and it does **not** inherit
  from `spring-boot-starter-parent`. So step 1 is *fix + extend*, not *create*.
- **Migrations run through `V10`** — **no new migration** in this refactor (structural only).
- **`/api/v1` versioning already merged** (#62) — board item #9 is effectively done.
- **Monzo HTTP layer to move** (`backend/src/main/java/dev/amfshr/budgeteer/`):
  - `client/monzo/MonzoClient.java` — the `@Component` RestClient wrapper.
  - `client/monzo/dto/` — `MonzoAccountResponse`, `MonzoAccountsResponse`, `MonzoTransactionResponse`,
    `MonzoTransactionsResponse`, `MonzoMerchantResponse`, `TokenResponse`, `package-info.java`.
  - `config/MonzoProperties.java` — `@ConfigurationProperties(prefix="monzo")` record.
  - `config/MonzoClientConfig.java` — `@Bean RestClient monzoRestClient`.
- **Monzo code that STAYS in the API** (touches DB / Spring lifecycle / security): all of
  `domain/monzo/` (`MonzoConnection`, `MonzoAccount`, `MonzoTransaction` — each `@ManyToOne User`),
  `domain/oauth/OAuthState`, `repository/Monzo*Repository` + `OAuthStateRepository`,
  `service/monzo/*` (`MonzoOAuthService`, `MonzoConnectionService`, `MonzoTokenRefreshService`,
  `TransactionSyncService`, jobs, event listener), `service/common/EncryptionService`,
  `config/EncryptionProperties|MonzoTokenRefreshProperties|TransactionSyncProperties`, `api/monzo/*`,
  `security/*`.
- **`MonzoClient` consumers** (all in `service/monzo/`): `MonzoOAuthService` (`exchangeCode`,
  `whoAmI`), `MonzoTokenRefreshService` (`refreshTokens`), `TransactionSyncService` (`getAccounts`,
  `getTransactions`). `MonzoController` only touches services, never `MonzoClient`.
- **Two `TokenResponse` records exist** — `client/monzo/dto/TokenResponse` and a nested
  `MonzoOAuthService.TokenResponse`. Both collapse into the neutral `BankTokens`.
- **Pagination STAYS in the API**: `TransactionSyncService` keeps the outer ≤350-day windowing +
  the per-**window** commit (one `TransactionTemplate` tx wraps a whole window — `paginateWindow`
  runs inside it) + the intra-window paging cursor + SCA-pause — the hard-won, regression-sensitive
  logic (the cursor-fix saga), so it stays put. The neutral method is **paged with an opaque
  cursor**: `getTransactions(token, accountId, from, to, pageCursor) → BankTransactionPage(txns,
  nextCursor)`. `MonzoBankClient` maps the opaque cursor ↔ Monzo's `since`/`before` per page; the
  service drives the loop, commits per window, and uses the cursor exactly as today.
  `MonzoClient.getTransactions(..., since, before, sinceId, limit)` becomes the client's private
  per-page fetch behind that opaque cursor.
- **Resume is window-granular** (verified): `backfill_progress_at` (last committed window start) is
  the durable resume point. An SCA 403 mid-window rolls back the whole window transaction (incl. the
  per-page cursor writes) and the window is re-fetched on resume — idempotent via the native upsert.
  `backfill_progress_cursor` is reset to null at each window commit, so it does **not** carry
  progress across re-auth; it's purely intra-window paging state. (True mid-window resume = a
  separate per-page-commit enhancement, see `tasks.md`.)
- **Persisted fields** (so the neutral types don't regress data):
  - `MonzoTransaction`: `id, amount(int signed), currency, description, merchantName,
    merchantCategory, notes, isDeclined, monzoCreatedAt(Instant), monzoSettledAt(Instant?)`.
  - `MonzoAccount`: `id, accountType, description, currency, closed, monzoCreatedAt(Instant?)`
    (+ backfill state cols, set by the service not the client).
- **Tests**: `client/monzo/MonzoClientTest` (WireMock, no DB) → moves to `monzo-client`.
  `service/monzo/*Test`, `api/monzo/MonzoControllerTest`, `integration/MonzoOAuthFlowIT` → stay in
  the API. `integration/AbstractMonzoWireMockIT` + `AbstractPostgresIntegrationTest` stay in the API.
  WireMock mappings under `backend/src/test/resources/wiremock/`.

## Decisions Log

| # | Decision | Rationale | Rejected alternative |
|---|----------|-----------|----------------------|
| 1 | **3 modules** (`common`, `monzo-client`, `api`), not 4 | Only one bank exists today; defer `truelayer-client` until needed | 4-module up-front (empty truelayer stub = ceremony) |
| 2 | **All persistence stays in `budgeteer-api`** | Monzo entities `@ManyToOne User`; splitting JPA across jars (single persistence unit, entity scanning) is painful for zero gain | Entities in `monzo-client` (original plan) — JPA-across-jars pain |
| 3 | **`monzo-client` = thin HTTP client** (client + DTOs + mapper + props), no Spring-persistence coupling | Clean "library" boundary; isolated deps | Fat vertical slice (services/jobs in the jar) — needs executor/event/config-scan wiring across jars |
| 4 | **`BankClient` interface + neutral records in `common`; API programs to it** | True "program to an interface"; TrueLayer slots in as a 2nd impl | Client exposes Monzo DTOs, API maps them — API depends on Monzo types, not really an interface |
| 5 | **Neutral types model what the app persists/uses today** (not a provider superset) | Avoids a god-object; zero data regression | Union-of-all-provider-fields superset — bloats + rots |
| 6 | **Paged `getTransactions(from,to,pageCursor)` with an OPAQUE cursor; service keeps driving paging + the per-window commit** | Faithfully mirrors today's page-by-page fetch inside one per-window transaction (minimal diff, no schema churn) while staying provider-neutral; window=`from/to`, opaque cursor=within-window paging. Fits TrueLayer (`nextCursor=null` when a date range returns in one page) | (a) Monzo-shaped `(since,before,sinceId,limit)` — leaks Monzo's model; (b) whole-window fetch + drop cursor — behaviourally fine (resume is window-granular today regardless) but changes the memory profile + is gratuitous code/schema churn for a behaviour-preserving refactor |
| 7 | **Canonical amount = signed `long` minor units** (negative = money out) | Monzo is already signed minor units; TrueLayer maps `amount×100 × ±1` from DEBIT/CREDIT | Decimal + separate type field — invites rounding/sign bugs |
| 8 | **Same root package `dev.amfshr.budgeteer.*` across all modules** | `@ComponentScan`/`@ConfigurationPropertiesScan` on `BudgeteerApplication` auto-discover the jar's beans — **no Spring auto-configuration needed** | Distinct package per jar + `@AutoConfiguration` + `.imports` — ceremony |
| 9 | **Neutral exceptions in `common`; map to existing `MONZO_*` `ErrorCode`s at the API boundary** | Keeps the jar free of the app error model; behaviour-preserving | Move `ApiException`/`ErrorCode` to `common` — drags app-specific codes into the shared jar |

## Module Structure & POM Hierarchy

```
budgeteer/                 budgeteer-parent (root pom, packaging=pom)
│                          parent → spring-boot-starter-parent:4.0.6
├── budgeteer-common/      jar — dev.amfshr.budgeteer.bank.*        (deps: jspecify)
├── monzo-client/          jar — dev.amfshr.budgeteer.client.monzo.* (deps: common, spring-web)
└── budgeteer-api/         jar — everything else (was backend/)      (deps: common, monzo-client, …)
                           spring-boot-maven-plugin (fat jar) ONLY here
```

Dependency direction (acyclic): `api → monzo-client → common`, and `api → common`.

### Package layout

| Module | Package | Contents |
|--------|---------|----------|
| `budgeteer-common` | `dev.amfshr.budgeteer.bank` | `BankClient`, `BankTokens`, `BankAccount`, `BankTransaction`, `BankIdentity`, `BankClientException`, `BankConnectionRevokedException`, `BankReauthRequiredException`, `package-info.java` (`@NullMarked`) |
| `monzo-client` | `dev.amfshr.budgeteer.client.monzo` | `MonzoBankClient` (was `MonzoClient`), `MonzoMapper` (new), `MonzoProperties`, `MonzoClientConfig`, `package-info.java` |
| `monzo-client` | `dev.amfshr.budgeteer.client.monzo.dto` | the 6 Monzo JSON DTOs + `package-info.java` (moved unchanged) |
| `budgeteer-api` | `dev.amfshr.budgeteer.*` | unchanged except Monzo-client classes removed + services rewired |

> No package name appears in two jars → no split packages (the project does not use JPMS, but this
> keeps it clean anyway). `dev.amfshr.budgeteer.client.monzo[.dto]` lives **only** in `monzo-client`;
> `dev.amfshr.budgeteer.bank` **only** in `common`; the app root **only** in `budgeteer-api`.

## The Contract — `budgeteer-common`

```java
// dev.amfshr.budgeteer.bank.BankClient
public interface BankClient {

    /** Build the provider's OAuth authorization URL for the given CSRF state. */
    String buildAuthorizationUrl(String state);

    /** Exchange an authorization code for tokens (redirect URI etc. come from the impl's config). */
    BankTokens exchangeCode(String code);

    /** Refresh an access token. Implementations fall back to the old refresh token if not rotated. */
    BankTokens refreshTokens(String refreshToken);

    /** Identify the connection at the provider (Monzo user_id; TrueLayer credentials_id + consent). */
    BankIdentity getIdentity(String accessToken);

    /** All accounts for the authenticated connection. */
    List<BankAccount> getAccounts(String accessToken);

    /**
     * One page of transactions for an account in the half-open window [from, to). Pass a null
     * pageCursor for the first page; pass the returned nextCursor for each subsequent page until
     * nextCursor is null. The cursor is an OPAQUE provider token — the caller persists and replays
     * it (intra-window paging) but never interprets it. The caller drives windowing + commits.
     * @throws BankReauthRequiredException if the provider's SCA window has expired for this range
     * @throws BankConnectionRevokedException if the connection is revoked (401)
     * @throws BankClientException on any other upstream failure
     */
    BankTransactionPage getTransactions(String accessToken, String accountId,
                                        Instant from, Instant to, @Nullable String pageCursor);
}
```

```java
// neutral records — one file each, @NullMarked at package level
public record BankTokens(String accessToken, @Nullable String refreshToken, @Nullable Instant expiresAt) {}

public record BankIdentity(String providerUserId, @Nullable Instant consentExpiresAt) {}

public record BankAccount(
        String externalId,
        String type,                 // raw provider account-type string
        @Nullable String description,
        String currency,
        boolean closed,
        @Nullable Instant createdAt) {}

/** amountMinorUnits is signed: negative = money out. Monzo is already minor units;
 *  TrueLayer maps amount×100 with DEBIT→negative, CREDIT→positive. */
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
        @Nullable Instant settledAt) {}

/** One page of transactions plus an opaque cursor for the next page (null = last page). */
public record BankTransactionPage(List<BankTransaction> transactions, @Nullable String nextCursor) {}
```

```java
// neutral exceptions
public class BankClientException extends RuntimeException {
    public BankClientException(String message) { super(message); }
    public BankClientException(String message, Throwable cause) { super(message, cause); }
}
public class BankConnectionRevokedException extends BankClientException { /* 401 */ }
public class BankReauthRequiredException   extends BankClientException { /* 403 SCA / consent expired */ }
```

> `BankAccount`/`BankTransaction` carry exactly the fields the API persists today (verified against
> the entities) → zero regression. They are intentionally lean — extra provider richness goes to the
> raw `jsonb` blob in the *Domain Model Mapping* ticket, not into these records.

## External Integration — `monzo-client`

`MonzoBankClient` (renamed from `MonzoClient`) `implements BankClient`. Changes from today:

- **Returns neutral types**, not Monzo DTOs. A package-private `MonzoMapper` converts:
  - token `Map`/DTO → `BankTokens` (as today: `expiresAt = now + expires_in`).
  - `MonzoAccountResponse` → `BankAccount` (`type←type`, `description←description`,
    `closed←closed`, `createdAt←parse(created)`).
  - `MonzoTransactionResponse` → `BankTransaction`: `amountMinorUnits←(long) amount`,
    `merchantName←merchant?.name`, `merchantCategory←merchant?.category`, `notes←notes`,
    `declined←declineReason != null && !blank`, `createdAt←parse(created)`,
    `settledAt←(settled blank? null : parse(settled))`. (Mirrors `upsertTransaction` today.)
- **Owns `buildAuthorizationUrl(state)`** — moved verbatim from `MonzoOAuthService` (uses
  `MonzoProperties.authUrl/clientId/redirectUri`).
- **Single-page fetch behind an opaque cursor** — `getTransactions(token, accountId, from, to,
  pageCursor)` fetches ONE page and returns `BankTransactionPage(mapped, nextCursor)`. Maps the
  opaque `pageCursor` → Monzo's `since` (last-seen tx id; on the first page `since`=`from`),
  `before`=`to`, `limit`=page size; `nextCursor` = the last tx id, or `null` when the page is short
  (window exhausted). The **service keeps driving the page loop inside its per-window transaction +
  cursor persistence** — no DB side-effects in the client. This preserves today's
  `paginateWindow`/`paginateTransactions` behaviour; only the types + cursor opacity change.
- **`getIdentity`** wraps the old `whoAmI` (`/ping/whoami`) → `BankIdentity(user_id, null)`.
- **Throws neutral exceptions**: rewrite `handleMonzoError` to throw `BankConnectionRevokedException`
  (401), `BankReauthRequiredException` (403 `forbidden.verification_required`), `BankClientException`
  (403 other / 429 / empty body / missing token). No `ApiException`/`ErrorCode` import remains.
- Keeps the existing `RestClient` (`MonzoClientConfig` moves with it) and `MonzoProperties`.

`MonzoProperties` and `MonzoClientConfig` move from `config/` into
`dev.amfshr.budgeteer.client.monzo` (avoids splitting the `...config` package across jars). They are
still auto-discovered: `@ConfigurationPropertiesScan`/`@ComponentScan` on `BudgeteerApplication`
(package `dev.amfshr.budgeteer`) cover the `client.monzo` subpackage on the classpath.

## Service Rewiring — `budgeteer-api`

Inject `BankClient` (the single `MonzoBankClient` bean) instead of `MonzoClient`. Use neutral types.
Catch neutral exceptions where behaviour depends on them; let the rest propagate to the handler.

| Class | Change |
|-------|--------|
| `MonzoOAuthService` | Drop `MonzoProperties` + `buildAuthorizationUrl` (moved to client) + nested `TokenResponse` record. Inject `BankClient`. `initiateOAuthFlow` → `bankClient.buildAuthorizationUrl(state)`. `exchangeCodeForTokens(code)` returns `BankTokens` (was nested `TokenResponse`). `getMonzoUserId(token)` → `bankClient.getIdentity(token).providerUserId()`. |
| `MonzoTokenRefreshService` | `bankClient.refreshTokens(...)` → `BankTokens`. Change the revoke catch from `ApiException(MONZO_CONNECTION_REVOKED)` to `catch (BankConnectionRevokedException e)`. Same downstream behaviour (mark connection revoked). |
| `TransactionSyncService` | Inject `BankClient`. Keep `paginateWindow`/`paginateTransactions` **structurally as-is** — just swap `monzoClient.getTransactions(...)` for `bankClient.getTransactions(token, accountId, from, to, cursor)` returning `BankTransactionPage`; iterate `page.transactions()` via `upsertTransaction`; advance `cursor = page.nextCursor()` (loop until null); keep persisting `backfill_progress_cursor` per page (intra-window). Outer windowing + the per-window `TransactionTemplate` commit + window-granular resume all unchanged. Change SCA-pause catch from `ApiException(MONZO_VERIFICATION_REQUIRED)` to `catch (BankReauthRequiredException e)`, and the retry catch from `ApiException(MONZO_API_ERROR)` to `catch (BankClientException e)`. `MONZO_SYNC_ERROR` (own thrown `ApiException`) stays. `upsertAccount` maps `BankAccount` (was `MonzoAccountResponse`); `upsertTransaction` maps `BankTransaction`. |
| `MonzoConnectionService` | `createConnection` takes the access/refresh/expiresAt from `BankTokens` fields instead of the nested `TokenResponse`. `EncryptionService` usage unchanged. |
| `MonzoController` | Line ~171: `BankTokens tokens = oauthService.exchangeCodeForTokens(code);` (was `MonzoOAuthService.TokenResponse`). Otherwise unchanged. |
| `api/common/GlobalExceptionHandler` | Add `@ExceptionHandler(BankClientException.class)` mapping subclasses → existing `ErrorCode` + `ApiResponse` (mirror the `ApiException` handler): `BankConnectionRevokedException`→`MONZO_CONNECTION_REVOKED`, `BankReauthRequiredException`→`MONZO_VERIFICATION_REQUIRED`, else→`MONZO_API_ERROR`. Covers neutral exceptions that reach the controller (e.g. during OAuth callback). |

Delete the duplicated `client/monzo/dto/TokenResponse` (moved) usage and the nested
`MonzoOAuthService.TokenResponse` — both replaced by `BankTokens`.

## Configuration

No new keys. `monzo.*` properties (`client-id`, `client-secret`, `redirect-uri`, `auth-url`,
`token-url`, `api-base-url`) are unchanged and still bound by `MonzoProperties` (now in the
`monzo-client` jar). `application*.properties` stay in `budgeteer-api/src/main/resources/`.

## Edge Cases & Failure Modes

| Case | Handling |
|------|----------|
| Memory / streaming | **Unchanged** — the service fetches **page-by-page** (one page in memory at a time) within a single per-window transaction. No whole-window-in-memory. |
| SCA crap-out + resume | **Unchanged (window-granular).** A 403 mid-window rolls back that window's transaction → account `NEEDS_REAUTH`; the durable point is the last fully-committed window (`backfill_progress_at`). On re-auth, resume re-fetches the failed window from scratch — idempotent via `transactionRepository.upsert` (ON CONFLICT). `backfill_progress_cursor` is intra-window only (reset per committed window), so it does **not** carry mid-window progress across re-auth. Genuine per-page resume = a separate per-page-commit enhancement (backlog). |
| Delta sync semantics | Was sinceId-cursor; becomes `getTransactions(token, accountId, lastSyncedAt, now)`. Equivalent given idempotent upsert; any overlap is harmless. |
| SCA window expired mid-backfill | `MonzoBankClient` throws `BankReauthRequiredException`; `TransactionSyncService` catches it and pauses exactly as before (persist progress, mark paused). |
| Connection revoked (401) | `BankConnectionRevokedException` → caught by `MonzoTokenRefreshService` (mark revoked) or mapped to `MONZO_CONNECTION_REVOKED` by `GlobalExceptionHandler`. |
| `@ConfigurationProperties` in another jar not bound | Avoided: same root package + `@ConfigurationPropertiesScan` covers it. Verify `MonzoProperties` binds (an OAuth IT exercises it). |
| Checkstyle config path in multi-module build | Move `backend/config/checkstyle/` → repo-root `config/checkstyle/`; reference `${maven.multiModuleProjectDirectory}/config/checkstyle/checkstyle.xml` from root `<pluginManagement>`. |

## Test Strategy

**`monzo-client/src/test`** (WireMock, no DB, no full Spring context):
- Move `client/monzo/MonzoClientTest` → `MonzoBankClientTest`. Assert it now returns **neutral**
  types and throws **neutral** exceptions: token exchange/refresh → `BankTokens`; `getAccounts` →
  `List<BankAccount>`; `getTransactions(from,to,cursor)` returns one `BankTransactionPage`,
  mapping the opaque cursor ↔ Monzo's `since`/`before` (assert first-page vs next-page cursor
  handling, and `nextCursor=null` on a short page); 401 →
  `BankConnectionRevokedException`; 403 `forbidden.verification_required` →
  `BankReauthRequiredException`; other errors → `BankClientException`. Move any JSON fixtures it
  loads into `monzo-client/src/test/resources`.
- `monzo-client` test deps: `spring-boot-starter-test`, `wiremock-standalone`. **No** Testcontainers.

**`budgeteer-api/src/test`** (unchanged locations):
- `MonzoOAuthServiceTest`, `MonzoTokenRefreshServiceTest`, `TransactionSyncServiceTest`: mock
  `BankClient` (was `MonzoClient`); use neutral types; assert the neutral-exception catches
  (`BankReauthRequiredException` → pause, `BankConnectionRevokedException` → revoke,
  `BankClientException` → retry).
- `MonzoControllerTest`: unchanged except the token type (`BankTokens`).
- `MonzoOAuthFlowIT` (+ `AbstractMonzoWireMockIT`, `AbstractPostgresIntegrationTest`): unchanged —
  WireMock stubs Monzo HTTP and the real `MonzoBankClient` (on the classpath via the jar) handles it.
  This is the end-to-end safety net proving behaviour is preserved.

## New Files

| Path | Purpose |
|------|---------|
| `budgeteer-common/pom.xml` | jar module; parent `budgeteer-parent`; dep `jspecify` |
| `budgeteer-common/src/main/java/dev/amfshr/budgeteer/bank/BankClient.java` | the contract |
| `.../bank/BankTokens.java` `BankIdentity.java` `BankAccount.java` `BankTransaction.java` `BankTransactionPage.java` | neutral records |
| `.../bank/BankClientException.java` `BankConnectionRevokedException.java` `BankReauthRequiredException.java` | neutral exceptions |
| `.../bank/package-info.java` | `@NullMarked` |
| `monzo-client/pom.xml` | jar module; parent `budgeteer-parent`; deps `budgeteer-common`, `spring-boot-starter-web`, `spring-boot-configuration-processor` (optional), `jspecify`; test `spring-boot-starter-test`, `wiremock-standalone` |
| `monzo-client/.../client/monzo/MonzoMapper.java` | Monzo DTO → neutral records |
| `config/checkstyle/checkstyle.xml` (repo root) | moved from `backend/config/checkstyle/` for shared use |

## Modified / Moved Files

| Path | Change |
|------|--------|
| `pom.xml` (root) | `groupId` `dev.amf`→`dev.amfshr`; add `<parent>spring-boot-starter-parent:4.0.6`; modules `budgeteer-common, monzo-client, budgeteer-api`; move shared `<properties>` (`java.version=25`, `lombok.version=1.18.46`, `testcontainers.version=2.0.5`) + `<dependencyManagement>` (testcontainers-bom, commons-compress) here; add `<pluginManagement>` (compiler, surefire, checkstyle) for shared build config |
| `backend/` → `budgeteer-api/` | `git mv backend budgeteer-api` (preserves history) |
| `budgeteer-api/pom.xml` | parent → `budgeteer-parent` (drop direct spring-boot-starter-parent); `artifactId` `budgeteer-backend`→`budgeteer-api`; `<name>` → `Budgeteer API`; remove props/depMgmt now in root; add deps `budgeteer-common`, `monzo-client`; keep `spring-boot-maven-plugin` here only |
| `git mv` `client/monzo/MonzoClient.java` → `monzo-client/.../client/monzo/MonzoBankClient.java` | rename + implement `BankClient`, return neutral types, neutral exceptions, own `buildAuthorizationUrl` + pagination loop |
| `git mv` `client/monzo/dto/*` (6 files) → `monzo-client/.../client/monzo/dto/` | unchanged content |
| `git mv` `config/MonzoProperties.java`, `config/MonzoClientConfig.java` → `monzo-client/.../client/monzo/` | update package decl |
| `service/monzo/MonzoOAuthService.java` | per Service Rewiring table |
| `service/monzo/MonzoTokenRefreshService.java` | per table |
| `service/monzo/TransactionSyncService.java` | per table (most delicate, but a mechanical type/cursor swap — logic unchanged) |
| `service/monzo/MonzoConnectionService.java` | `createConnection` consumes `BankTokens` |
| `api/monzo/MonzoController.java` | token type → `BankTokens` |
| `api/common/GlobalExceptionHandler.java` | add neutral-exception handler |
| `service/monzo/*Test.java`, `api/monzo/MonzoControllerTest.java` | mock `BankClient`, neutral types |
| `git mv` `client/monzo/MonzoClientTest.java` → `monzo-client/.../client/monzo/MonzoBankClientTest.java` | assert neutral types/exceptions; add multi-page stub |
| `scripts/dev.sh` | 5× `$PROJECT_ROOT/backend` → `budgeteer-api` (lines ~185, 264, 271, 289, 301) |
| `.github/workflows/ci.yml` | `working-directory`/`cache-dependency-path`/artifact path `backend`→`budgeteer-api` (lines ~33, 48, 76, 88, 100) |
| `.github/workflows/codeql.yml` | lines ~44, 56 `backend`→`budgeteer-api` |
| `.github/dependabot.yml` | `directory: "/backend"` → `/budgeteer-api` (line ~15) |
| `.github/CODEOWNERS` | `/backend/` → `/budgeteer-api/` (line ~12) |

## Implementation Order

Build compile-green at each phase. **Suggested PRs:** PR1 = Phase 1; PR2 = Phases 2–4 (after Phase 3
moves the client out, the API won't compile until Phase 4 rewires it — so 3+4 land together).

**Phase 1 — Scaffolding (zero behaviour change).**
1. Fix + extend root `pom.xml` (parent, groupId, modules, shared props/depMgmt/pluginManagement).
   Add empty `budgeteer-common` + `monzo-client` modules (pom + `package-info.java` only).
2. `git mv backend budgeteer-api`; update its pom (parent, artifactId, name, prune props).
3. Move `config/checkstyle/` to repo root; fix `configLocation`.
4. Update `scripts/dev.sh`, `.github/*` (ci, codeql, dependabot, CODEOWNERS).
5. `mvn clean verify` from root green (all code still in `budgeteer-api`).

**Phase 2 — Contract in `budgeteer-common`.**
6. Add `BankClient` + neutral records + neutral exceptions + `package-info.java`. `budgeteer-api`
   already depends on `common`. Compiles (nothing uses it yet).

**Phase 3 — Move Monzo HTTP client into `monzo-client`.**
7. `git mv` `MonzoClient`→`MonzoBankClient`, the 6 DTOs, `MonzoProperties`, `MonzoClientConfig`.
8. Make `MonzoBankClient implements BankClient`: add `buildAuthorizationUrl`, internal pagination
   loop, `MonzoMapper`, neutral return types, neutral exceptions. Add `monzo-client` deps.
9. Move `MonzoClientTest`→`MonzoBankClientTest` + fixtures; update assertions.
   *(`budgeteer-api` does NOT compile yet — proceed straight to Phase 4.)*

**Phase 4 — Rewire `budgeteer-api` to the interface.**
10. Add `monzo-client` dep to `budgeteer-api`.
11. Rewire `MonzoOAuthService`, `MonzoConnectionService`, `MonzoTokenRefreshService`,
    `TransactionSyncService`, `MonzoController`, `GlobalExceptionHandler` per the table. Delete the
    nested `TokenResponse`. (No schema change — `backfill_progress_cursor` stays.)
12. Update the API unit tests to mock `BankClient` + neutral types/exceptions.
13. `mvn clean verify` from root green; manually sanity-check the OAuth + sync flow if convenient.

## Key Files to Read Before Implementing

| File | Why |
|------|-----|
| `backend/src/main/java/dev/amfshr/budgeteer/client/monzo/MonzoClient.java` | The class being extracted — error handling + method shapes to neutralise |
| `backend/src/main/java/dev/amfshr/budgeteer/service/monzo/TransactionSyncService.java` | Most delicate (but mechanical): windowing/commits/cursor/SCA-pause all STAY; only swap to `BankClient` + neutral types + opaque cursor + neutral-exception catches |
| `backend/src/main/java/dev/amfshr/budgeteer/service/monzo/MonzoOAuthService.java` | Loses `MonzoProperties` + `buildAuthorizationUrl` + nested `TokenResponse` |
| `backend/src/main/java/dev/amfshr/budgeteer/service/monzo/MonzoTokenRefreshService.java` | Revoke-catch switches to `BankConnectionRevokedException` |
| `backend/src/main/java/dev/amfshr/budgeteer/domain/monzo/MonzoTransaction.java` + `MonzoAccount.java` | Confirms neutral-type fields (no regression) |
| `backend/src/main/java/dev/amfshr/budgeteer/api/common/GlobalExceptionHandler.java` + `ErrorCode.java` | Mirror the `ApiException` handler for neutral exceptions; keep `MONZO_*` codes |
| `backend/src/test/java/dev/amfshr/budgeteer/integration/AbstractMonzoWireMockIT.java` + `MonzoOAuthFlowIT.java` | The end-to-end safety net; must stay green |
| root `pom.xml` + `backend/pom.xml` | Current parent/groupId/props to migrate |
| `scripts/dev.sh`, `.github/{workflows/ci.yml,workflows/codeql.yml,dependabot.yml,CODEOWNERS}` | Exact `backend/` references to repoint |

## Open Questions / Assumptions

- **Resolved:** transactions stay paged via an opaque cursor to mirror today's page-by-page fetch
  inside one per-window transaction (minimal diff, no schema change). Resume is window-granular as
  today — a 403 rolls back the in-flight window and it's re-fetched (idempotent upsert); the cursor
  is intra-window only. Genuine mid-window resume would need per-page commits — a separate
  enhancement (see backlog).
- **Assumption:** `BankIdentity.consentExpiresAt` is populated-but-unused until a feature consumes it
  (Monzo leaves it null). Kept as the documented TrueLayer seam; remove only if you'd rather return a
  bare `String`.
- **Assumption:** keep mapping neutral exceptions to `MONZO_*` `ErrorCode`s for now; generalise to
  `BANK_*` when TrueLayer lands (API-contract change, out of scope here).
- **Board (done):** old #6 + #8 consolidated into the single phased #6; #9 (`/api/v1`, merged #62)
  moved to Done; the `MONZO_* → BANK_*` error-code generalisation added to the Backlog.

---

## Implementer Kickoff Prompt

> Copy-paste this to the implementing model/tool.

You are implementing **Multi-Module Maven Restructure + Monzo Client Extraction** in the Budgeteer
repo (Spring Boot **4.0.6** / Java **25** / PostgreSQL 16; base package `dev.amfshr.budgeteer`).

**Before writing any code, read:** `.agents/context/architecture.md`,
`.agents/context/conventions.md`, `.agents/context/testing.md`, this spec, and every file in *Key
Files to Read Before Implementing*.

**Then:** implement strictly in the order in *Implementation Order*, phase by phase, **on the current
branch** (do not create a new branch), keeping the build compile-green at each phase boundary. Use
`git mv` for all moves/renames to preserve history. Follow the *Codebase ground rules*: constructor
injection, thin controllers, DTOs not entities, `@NullMarked`, checkstyle limits (≤120 cols,
≤500-line files, ≤50-line methods, no star/unused imports), never modify existing migrations, never
log tokens/secrets. Create exactly the files in *New Files*; change exactly those in
*Modified / Moved Files*.

**Critical:** this is a **behaviour-preserving** refactor. The Monzo OAuth flow, token refresh, and
the windowed backfill (per-window commits, SCA-pause/resume, retry, idempotent upsert) and delta
sync must work exactly as before — `MonzoOAuthFlowIT` and `TransactionSyncServiceTest` are your
safety net and must stay green. The `monzo-client` jar must not import `ApiException`/`ErrorCode` or
any JPA/persistence type; the `budgeteer-api` code must program to `BankClient`, not `MonzoBankClient`.

**Do not** redesign, add scope (no `truelayer-client`, no domain `transactions` table, no generic
`BANK_*` error codes, **no per-page-commit rework** — keep the existing per-window commit +
window-granular resume), or deviate from this spec. If something is genuinely underspecified, stop
and ask rather than guessing.

**Definition of Done:** every *Acceptance Criteria* box ticked, all tests in *Test Strategy* written
and passing, and `/check` (checkstyle + unit + integration) green from the repo root before opening
the PR.
