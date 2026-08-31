# Domain Model Mapping — Raw → Domain Ingest + First Product Endpoints

> **Priority:** P2 | **Estimate:** 2–3d | **Status:** Spec ready (grilled 2026-08-22) | **Branch:** `feature/domain-model-mapping` (exists)

## Goal

Build the provider-agnostic domain layer above the raw `monzo_*` tables: unified
`user_accounts` / `transactions` fed by an idempotent raw→domain ingest pipeline chained onto the
existing hourly sync, balance snapshots via `BalanceCapability.getBalance`, encrypted raw-payload capture,
and the first three product-facing read endpoints. This unblocks every budgeting feature
(categories, budgets, virtual pots, reports) and gives a frontend real data to render.

**Deployment note (user-confirmed 2026-08-22):** no production data exists. After this lands, the
dev DB is wiped and Monzo is re-synced from scratch (backfill + SCA re-auth), which also populates
`raw_payload_encrypted` from day one. **No data-backfill scripts for existing rows** — do not write
any.

## Acceptance Criteria

- [ ] After OAuth + backfill + one ingest run, `GET /api/v1/accounts`, `GET /api/v1/accounts/{id}/summary`
      and `GET /api/v1/transactions` return real synced data, user-scoped
- [ ] Re-running the ingest creates zero duplicates, flips `PENDING → SETTLED` when raw learns of
      settlement, and never overwrites `notes` or `excluded_from_analytics` on existing domain rows
- [ ] Declined raw transactions are never mapped
- [ ] `raw_payload_encrypted` is populated (AES-256-GCM) on both raw tables during sync; NULL when
      the provider gave no `rawJson`; never logged
- [ ] Balance snapshots land on `user_accounts` with `balance_as_of` stamped, refreshed hourly and
      after backfill
- [ ] Closed raw accounts / disconnected connections surface as `archived_at` set; reopened
      accounts un-archive
- [ ] User A cannot read user B's accounts or transactions (asserted by an IT)
- [ ] `/check` green (checkstyle + unit + integration)

## Out of Scope

- Categories, categorisation, budgets, reports, virtual pots, category rules, user settings —
  slices 2–5 (see `.agents/notes/domain-model-design.md` build order). **Consequence:** the
  `transactions` table is created *without* `category_id`/`categorisation_source`; slice 2 adds
  them via an additive ALTER alongside `categories`
- `PATCH /api/v1/accounts/{id}` (rename/reorder/archive) and `POST /api/v1/accounts/{id}/balance-refresh` —
  additive later, no schema impact (grill decision 2026-08-22)
- `PATCH /api/v1/transactions/{id}` (notes / excluded flag) — with slice 2
- Webhooks (#5) — become a second trigger into this pipeline afterwards
- Event-driven post-sync hooks (backlog) — v0 wiring is direct method calls, deliberately
- Fixing the raw-sync limitation that settled-flips on rows older than the delta cursor are never
  re-fetched from Monzo until webhooks land — the domain faithfully mirrors whatever raw knows
- ~~Provider-contract rename~~ — **executed 2026-08-24 (PR #80), before this slice**; contract since
  split by capability (PR #84, 2026-08-25): `ProviderConnectionAuth` / `AccountsCapability` /
  `BalanceCapability` / `TransactionsCapability` in `provider-api` (package `dev.amfshr.budgeteer.provider`, records
  in `.provider.model`, exceptions in `.provider.exception`), impl is
  `MonzoAccountInformationProvider` in `provider-monzo` (package
  `dev.amfshr.budgeteer.provider.monzo`), error codes are `PROVIDER_*`. This spec uses the final
  names throughout — nothing rename-related remains in scope here

## Codebase Findings

Verified against the repo 2026-08-22 (context docs are stale — trust these):

- Base package `dev.amfshr.budgeteer`; Spring Boot **4.1.0** parent; Java 25; modules
  `provider-api` / `provider-monzo` / `budgeteer-server`
- Migrations at `budgeteer-server/src/main/resources/db/migration/`, V1–V10 exist → **next is V11**
- Package convention: entities `domain/<aggregate>/`, repositories flat in `repository/`, services
  in `service/<feature>/`, controllers in `api/<feature>/` with `dto/` subpackage; `package-info.java`
  with `@NullMarked` per package
- Raw tables: `monzo_accounts` (VARCHAR Monzo-id PK, `closed`, `monzo_created_at`, backfill state)
  and `monzo_transactions` (VARCHAR PK, `amount INTEGER` signed minor units, `is_declined`,
  nullable `monzo_settled_at`, `updated_at` bumped to `now()` by every ON CONFLICT re-touch)
- Sync: `TransactionSyncJob` (`@Scheduled` cron `monzo.transaction-sync.job-cron` = hourly) →
  `TransactionSyncService.deltaSync` per account; backfill commits per window via
  `TransactionTemplate`; `MonzoAccountRepository.findAllSyncable()` = non-closed accounts
- `MonzoTransactionRepository.upsert` is a native `ON CONFLICT (id) DO UPDATE` query — the exact
  pattern the domain upsert copies
- Raw JSON travels on the `Sourced<T>` envelope since #12 (2026-08-31): `BankTransactionPage`
  carries `List<Sourced<BankTransaction>>`, `getAccounts` returns `List<Sourced<BankAccount>>`;
  `sourced.rawJson()` is `@Nullable`, `Sourced.toString` redacts. Domain records no longer have
  a `rawJson` field. Still **dropped on persistence** — no raw columns exist yet. #12 also
  replaced `getTransactions(from, to, pageCursor)` with a sealed
  `SyncPosition (FromTime | AfterTransaction | NextPage)` + `to`
- `EncryptionService` (`service/common`): `encrypt(String)` / `decrypt(String)`;
  **`encrypt` THROWS `IllegalArgumentException` on null/empty** — null-guard required
- `BalanceCapability.getBalance(accessToken, accountId)` → `BankBalance(balanceMinorUnits, currency)`;
  (inject `BalanceCapability` — the contract was split by capability in PR #84);
  caller stamps the fetch time. **No WireMock stub file for `/balance` exists** — must be added
- `MonzoConnection.isActive()` = `disconnectedAt == null`
- Entities use `@GeneratedValue(strategy = GenerationType.UUID)` (see `User`)
- API: `ApiResponse.of(data)` envelope; `ApiException(ErrorCode, msg)`; `@CurrentUserId` resolver;
  `SecurityConfig` already authenticates `/api/**` — **zero security-config changes needed**
- **No paged endpoint exists yet** — `GET /api/v1/transactions` sets the house pattern
- Tests: `AbstractPostgresIntegrationTest` (no web env) → `AbstractMonzoWireMockIT`
  (`RANDOM_PORT` + WireMock; endpoint ITs must extend this one); `TestDataFactory` in the
  `integration` test package has raw-side helpers; WireMock file stubs under
  `src/test/resources/wiremock/mappings/monzo/`
- Dev-only trigger pattern: `DevMonzoController` (`/api/dev/monzo`, `@Profile("dev")`) with
  `POST /backfill` — mirror it for a mapping trigger

## Decisions Log

Locked before the grill (do not reopen):

| # | Decision | Rationale |
|---|----------|-----------|
| L1 | Domain is a separate layer; `monzo_*` stays as the provider-shaped landing zone | Provider-agnostic product tables above raw |
| L2 | Idempotent upsert keyed `(provider, provider_transaction_id)`; declined never mapped; user-owned fields never overwritten on re-map | Re-sync / webhook-replay safe |
| L3 | Account balance = stored provider snapshot via `getBalance`, never derived from transactions | Windowed history + pending + credit-card semantics make derivation wrong |
| L4 | Raw capture encrypted at rest (`raw_payload_encrypted TEXT`, AES-256-GCM via existing `EncryptionService`), on the **raw** tables | Raw carries `account_number`/`sort_code`; only future field-backfill source (>90d SCA-locked) |
| L5 | No pre-aggregation; totals computed on read | Single user, low volume, high edit-volatility |
| L6 | Mapping code lives in `budgeteer-server`, not the client jars | Inseparable from JPA; jars stay dumb API clients |

Grilled 2026-08-22:

| # | Decision | Rationale | Rejected alternative |
|---|----------|-----------|----------------------|
| 1 | Slice-1 API = three reads only (`GET /accounts`, `GET /accounts/{id}/summary`, `GET /transactions`) | Smallest slice proving the pipeline end-to-end | PATCH + balance-refresh POST — additive later, no schema impact |
| 2 | Mapping cursor = `raw_synced_through` on `user_accounts`, tracking max raw `updated_at` mapped; query `updated_at > cursor` | Raw upsert bumps `updated_at` on every re-touch, so the cursor catches settlement flips and late-arriving backfill windows | `monzo_created_at` cursor (misses flips); full remap per run (wasteful, grows forever) |
| 3 | `notes` seeded on insert only — ON CONFLICT update set never includes it | Cheapest honest implementation of L2; future PATCH owns it | Dual provider/user columns; edited-flag |
| 4 | `AccountType` enum gains `OTHER` fallback; unknown raw types map to OTHER + WARN | Never lies to the UI, never blocks mapping | Default-to-CURRENT (mislabels); skip account (vanishes) |
| 5 | Pagination = explicit `page`/`size` params + owned `PageResponse<T>` record inside `ApiResponse` | Stable JSON we own; totals for the UI; house pattern for all future lists | Spring `PagedModel` (inherits Spring's wire format); keyset (YAGNI at this volume) |
| 6 | Date filters `from`/`to` are ISO-8601 `Instant`s, half-open `[from, to)`; frontend owns timezone | Zero TZ policy in the backend where the client can own it | LocalDate @ Europe/London or UTC (bakes in DST policy) |
| 7 | v0 job wiring = one chained hourly run **sync → ingest → balances** inside `TransactionSyncJob`, plus a one-shot ingest+balance after backfill completes | No new cron, no race, first-connect UX works; trivially replaced by the backlogged events later | Separate crons (racy, laggy); pulling event plumbing forward |
| 8 | Mapper mirrors lifecycle: `archived_at` set when raw `closed` OR connection inactive; cleared when account reappears open | Disconnect keeps history but hides staleness; reconnect revives the same row (stable UUID) | Hooking `MonzoConnectionService.disconnect` (extra touchpoint) |
| 9 | Summary windows (today / Mon-start week / month-to-date) computed server-side in an optional `?zone=` IANA param, default `Europe/London` | Summary needs boundaries somewhere; client can pass its zone, curl stays friendly | Boundary instants as params (ugly); hardcoded zone only |
| 10 | Cursor advances to max processed raw `updated_at` (never `now()`); declined rows advance the cursor without mapping | Clock-skew-proof; idempotency makes overlap harmless | `now()` stamp (can skip rows written mid-run) |
| 11 | Ingest vocabulary: `service/ingest/`, `IngestService.ingestAll()`, `ProviderIngestor` (per-provider strategy), `MonzoIngestor`. Provider-contract rename (`AccountInformationProvider`, `Provider*` exceptions, `PROVIDER_*` codes, `provider-*` jars) was **pulled forward and executed 2026-08-24 (PR #80)** — this slice codes against the final names | New code gets the right names free; renaming after this slice would churn freshly written ingest code | "DomainMappingService" (too generic); deferring the rename to TrueLayer time |

## Database Schema

Three new migrations. **Never modify V1–V10.** All lowercase keywords per house style.

### V11 — raw capture + mapping-cursor index

`V11__add_raw_payload_and_mapping_index.sql`

```sql
-- Encrypted verbatim provider JSON (AES-256-GCM via EncryptionService), populated by the sync
-- layer from Sourced<BankAccount>/Sourced<BankTransaction> rawJson(). NULL when the provider gave none.
-- Never stored plaintext: raw payloads carry bank identifiers (account_number, sort_code).
alter table monzo_accounts
    add column raw_payload_encrypted text null;

alter table monzo_transactions
    add column raw_payload_encrypted text null;

-- Supports the domain-mapping cursor: fetch raw rows re-touched since the last mapping run.
create index idx_monzo_txn_account_updated on monzo_transactions(account_id, updated_at);
```

### V12 — `user_accounts`

`V12__create_user_accounts.sql`

```sql
create table user_accounts (
    id                       uuid primary key,
    user_id                  uuid not null references users(id) on delete cascade,
    provider                 varchar(32)  not null,
    provider_account_id      varchar(255) not null,
    account_type             varchar(32)  not null,
    institution_name         varchar(100) not null,
    display_name             varchar(255),
    currency                 varchar(3)   not null,
    balance_minor_units      bigint,
    balance_as_of            timestamp with time zone,
    credit_limit_minor_units bigint,
    display_order            integer not null default 0,
    archived_at              timestamp with time zone,
    raw_synced_through       timestamp with time zone,
    created_at               timestamp with time zone not null default now(),
    updated_at               timestamp with time zone not null default now(),
    constraint uq_user_accounts_provider_account unique (provider, provider_account_id)
);

create index idx_user_accounts_user        on user_accounts(user_id);
create index idx_user_accounts_user_active on user_accounts(user_id) where archived_at is null;
```

Notes: id is app-generated (`GenerationType.UUID`, matching `User`). Balance columns nullable —
unknown until the first refresh. `raw_synced_through` = the mapping cursor (decision 2).
Deliberately **no FK to `monzo_connections`** (L-locked).

### V13 — `transactions`

`V13__create_transactions.sql`

```sql
create table transactions (
    id                      uuid primary key,
    user_id                 uuid not null references users(id)         on delete cascade,
    account_id              uuid not null references user_accounts(id) on delete cascade,
    provider                varchar(32)  not null,
    provider_transaction_id varchar(255) not null,
    amount_minor_units      bigint       not null,
    currency                varchar(3)   not null,
    status                  varchar(16)  not null,
    description             varchar(500),
    merchant_name           varchar(255),
    merchant_category       varchar(100),
    notes                   text,
    excluded_from_analytics boolean not null default false,
    occurred_at             timestamp with time zone not null,
    settled_at              timestamp with time zone,
    created_at              timestamp with time zone not null default now(),
    updated_at              timestamp with time zone not null default now(),
    constraint uq_transactions_provider_tx unique (provider, provider_transaction_id)
);

create index idx_transactions_user_occurred    on transactions(user_id, occurred_at desc);
create index idx_transactions_account_occurred on transactions(account_id, occurred_at desc);
```

Notes: rows are written only by the native upsert (id supplied as `gen_random_uuid()` in SQL —
built into PG 13+). No `category_id`/`categorisation_source` yet (slice 2 ALTER). The
`(user_id, category_id, occurred_at)` index also waits for slice 2.

## API Contract

All authenticated (`SecurityConfig` already covers `/api/**`), all wrapped in `ApiResponse`.
Unknown/other-user's account id → 404 `RESOURCE_NOT_FOUND` (never 403 — don't confirm existence).

| Method | Path | Auth | Query params | Response `data` | Errors |
|--------|------|------|--------------|-----------------|--------|
| GET | `/api/v1/accounts` | `@CurrentUserId` | `includeArchived` (bool, default false) | `List<AccountResponse>` ordered `display_order asc, created_at asc` | — |
| GET | `/api/v1/accounts/{id}/summary` | `@CurrentUserId` | `zone` (IANA id, default `Europe/London`) | `AccountSummaryResponse` | 404 RESOURCE_NOT_FOUND; 400 VALIDATION_ERROR (bad zone) |
| GET | `/api/v1/transactions` | `@CurrentUserId` | `accountId` (UUID, opt), `from`/`to` (ISO-8601 Instant, opt, half-open `[from,to)`), `page` (≥0, default 0), `size` (1–200, default 50) | `PageResponse<TransactionResponse>` sorted `occurred_at desc, id desc` | 400 VALIDATION_ERROR (`from >= to`, size/page out of range) |

No new `ErrorCode`s. Filtering by an `accountId` the user doesn't own returns an **empty page**,
not 404 (the query is user-scoped; no info leak, no extra lookup).

DTO records (one per file):

```java
// api/account/dto/AccountResponse.java
public record AccountResponse(
        UUID id, String provider, String accountType, String institutionName,
        @Nullable String displayName, String currency,
        @Nullable Long balanceMinorUnits, @Nullable Instant balanceAsOf,
        @Nullable Long creditLimitMinorUnits, int displayOrder, boolean archived) {}

// api/account/dto/AccountSummaryResponse.java  (out = positive magnitude; in ≥ 0)
public record AccountSummaryResponse(
        UUID accountId, String zone,
        WindowSums today, WindowSums thisWeek, WindowSums monthToDate) {
    public record WindowSums(long inMinorUnits, long outMinorUnits) {}
}

// api/transaction/dto/TransactionResponse.java
public record TransactionResponse(
        UUID id, UUID accountId, long amountMinorUnits, String currency, String status,
        @Nullable String description, @Nullable String merchantName,
        @Nullable String merchantCategory, @Nullable String notes,
        boolean excludedFromAnalytics, Instant occurredAt, @Nullable Instant settledAt) {}

// api/common/PageResponse.java — THE house paging shape from now on
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public static <T, R> PageResponse<R> from(org.springframework.data.domain.Page<T> page, List<R> items) {
        return new PageResponse<>(items, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
```

Money is always minor units (`long`) + `currency`; the frontend formats. Enums cross the wire as
their `name()` strings. Summary sums include PENDING transactions (they're real spend) and exclude
`excluded_from_analytics = true` rows.

## Domain Entities & Repositories

### Entities

`domain/account/Provider.java` — `enum Provider { MONZO, TRUELAYER }` (varchar-backed via
`@Enumerated(EnumType.STRING)` everywhere).

`domain/account/AccountType.java` — `enum AccountType { CURRENT, SAVINGS, CREDIT_CARD, OTHER }`.

`domain/transaction/TransactionStatus.java` — `enum TransactionStatus { PENDING, SETTLED }`.

`domain/account/Account.java` — JPA entity for `user_accounts`, modelled on `MonzoAccount`
(same timestamp handling), `@GeneratedValue(strategy = GenerationType.UUID)`, `@ManyToOne User`,
`@Enumerated(EnumType.STRING)` for `provider`/`accountType`. Helper methods:

```java
public boolean isArchived() { return archivedAt != null; }
public void archive()   { if (archivedAt == null) archivedAt = Instant.now(); }
public void unarchive() { archivedAt = null; }
public void recordBalance(long balanceMinorUnits, Instant asOf) { ... }  // sets both snapshot cols
```

`domain/transaction/Transaction.java` — read-model entity for `transactions` (all writes go
through the native upsert). `@ManyToOne` to `User` and `Account`.

Both new packages get `package-info.java` with `@NullMarked`.

### Repositories

`repository/AccountRepository.java` (house style: explicit `@Query` JPQL for nested paths):

```java
@Query("SELECT a FROM Account a WHERE a.user.id = :userId ORDER BY a.displayOrder ASC, a.createdAt ASC")
List<Account> findByUserId(@Param("userId") UUID userId);

@Query("SELECT a FROM Account a WHERE a.user.id = :userId AND a.archivedAt IS NULL "
     + "ORDER BY a.displayOrder ASC, a.createdAt ASC")
List<Account> findActiveByUserId(@Param("userId") UUID userId);

@Query("SELECT a FROM Account a WHERE a.id = :id AND a.user.id = :userId")
Optional<Account> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

Optional<Account> findByProviderAndProviderAccountId(Provider provider, String providerAccountId);
```

`repository/TransactionRepository.java`:

```java
/** Idempotent domain upsert. The update set DELIBERATELY omits notes and
 *  excluded_from_analytics (user-owned — decision 3 / L2); slice 2 adds category fields
 *  to the same omission list. */
@Modifying
@Query(nativeQuery = true, value = """
        INSERT INTO transactions
            (id, user_id, account_id, provider, provider_transaction_id,
             amount_minor_units, currency, status, description, merchant_name,
             merchant_category, notes, excluded_from_analytics, occurred_at, settled_at,
             created_at, updated_at)
        VALUES
            (gen_random_uuid(), :userId, :accountId, :provider, :providerTransactionId,
             :amountMinorUnits, :currency, :status, :description, :merchantName,
             :merchantCategory, :notes, false, :occurredAt, :settledAt, now(), now())
        ON CONFLICT (provider, provider_transaction_id) DO UPDATE SET
            amount_minor_units = EXCLUDED.amount_minor_units,
            status             = EXCLUDED.status,
            description        = EXCLUDED.description,
            merchant_name      = EXCLUDED.merchant_name,
            merchant_category  = EXCLUDED.merchant_category,
            settled_at         = EXCLUDED.settled_at,
            updated_at         = now()
        """)
void upsert(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
        @Param("provider") String provider, @Param("providerTransactionId") String providerTransactionId,
        @Param("amountMinorUnits") long amountMinorUnits, @Param("currency") String currency,
        @Param("status") String status, @Nullable @Param("description") String description,
        @Nullable @Param("merchantName") String merchantName,
        @Nullable @Param("merchantCategory") String merchantCategory,
        @Nullable @Param("notes") String notes, @Param("occurredAt") Instant occurredAt,
        @Nullable @Param("settledAt") Instant settledAt);

/** Filtered page. Service defaults from/to (EPOCH / +100y) so no nullable-timestamp JPQL issues;
 *  accountId stays a nullable typed param. Pass an UNSORTED Pageable — order lives in the JPQL. */
@Query("""
        SELECT t FROM Transaction t
        WHERE t.user.id = :userId
          AND (:accountId IS NULL OR t.account.id = :accountId)
          AND t.occurredAt >= :from AND t.occurredAt < :to
        ORDER BY t.occurredAt DESC, t.id DESC
        """)
Page<Transaction> findFiltered(@Param("userId") UUID userId, @Nullable @Param("accountId") UUID accountId,
        @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

/** Six sums in one round trip via FILTER; :floor = min(weekStart, monthStart) — a week can
 *  start in the previous month. Signed sums; service converts out to positive magnitude. */
@Query(nativeQuery = true, value = """
        SELECT
          coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units > 0 AND occurred_at >= :todayStart), 0) AS today_in,
          coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units < 0 AND occurred_at >= :todayStart), 0) AS today_out,
          coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units > 0 AND occurred_at >= :weekStart), 0)  AS week_in,
          coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units < 0 AND occurred_at >= :weekStart), 0)  AS week_out,
          coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units > 0 AND occurred_at >= :monthStart), 0) AS month_in,
          coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units < 0 AND occurred_at >= :monthStart), 0) AS month_out
        FROM transactions
        WHERE account_id = :accountId AND user_id = :userId
          AND excluded_from_analytics = false
          AND occurred_at >= :floor
        """)
WindowSumsProjection sumWindows(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
        @Param("todayStart") Instant todayStart, @Param("weekStart") Instant weekStart,
        @Param("monthStart") Instant monthStart, @Param("floor") Instant floor);

/** Interface projection for the native sums (alias-matched getters). */
interface WindowSumsProjection {
    long getTodayIn(); long getTodayOut();
    long getWeekIn();  long getWeekOut();
    long getMonthIn(); long getMonthOut();
}
```

`repository/MonzoTransactionRepository.java` — two changes:

1. `upsert` gains a final `@Nullable @Param("rawPayloadEncrypted") String rawPayloadEncrypted`
   param; add `raw_payload_encrypted` to the column list / VALUES, and to the update set
   (`raw_payload_encrypted = EXCLUDED.raw_payload_encrypted` — provider-owned, newest wins).
2. New cursor query:

```java
@Query("SELECT t FROM MonzoTransaction t WHERE t.account.id = :accountId AND t.updatedAt > :after "
     + "ORDER BY t.updatedAt ASC")
List<MonzoTransaction> findByAccountIdUpdatedAfter(@Param("accountId") String accountId,
        @Param("after") Instant after);
```

## Service Logic

New service packages: `service/ingest/`, `service/account/`, `service/transaction/`
(house convention: feature subpackages; each gets `package-info.java` `@NullMarked`).

### Raw capture (modifies the sync layer)

`TransactionSyncService` gains an `EncryptionService` constructor dep and a private helper:

```java
@Nullable
private String encryptRaw(@Nullable String rawJson) {
    return (rawJson == null || rawJson.isEmpty()) ? null : encryptionService.encrypt(rawJson);
}
```

(`EncryptionService.encrypt` throws on null/empty — the guard is mandatory.)
Since #12 the sync loops iterate `Sourced<BankTransaction>` / `Sourced<BankAccount>` envelopes:
`upsertTransaction` passes `encryptRaw(sourced.rawJson())` as the new upsert param (thread the
envelope, or the rawJson alongside the payload, into the helper). `upsertAccount` sets
`account.setRawPayloadEncrypted(encryptRaw(sourced.rawJson()))` on both the new and existing
branches (JPA-managed save). `MonzoAccount` and `MonzoTransaction` entities gain the nullable
`rawPayloadEncrypted` field (`@Column(name = "raw_payload_encrypted")`). Never log the value —
plaintext or ciphertext.

### `service/ingest/ProviderIngestor.java`

```java
public interface ProviderIngestor {
    Provider provider();
    /** Upsert domain accounts from this provider's raw tables (lifecycle mirroring included).
     *  Returns ALL domain accounts for this provider, archived included. */
    List<Account> ingestAccounts();
    /** Map raw transactions re-touched since the account's cursor; advance the cursor. */
    void ingestTransactions(Account account);
}
```

### `service/ingest/MonzoIngestor.java`

Deps: `MonzoAccountRepository`, `MonzoTransactionRepository`, `AccountRepository`,
`TransactionRepository`. Constants: `INSTITUTION_NAME = "Monzo"`, type map
`uk_retail → CURRENT`, `uk_retail_joint → CURRENT`, `uk_monzo_flex → CREDIT_CARD`,
anything else → `OTHER` + WARN (log the raw type string only — never the payload).

```
ingestAccounts():
  for raw in monzoAccountRepository.findAll():          // ALL, incl. closed — they must archive
      domain = accountRepository.findByProviderAndProviderAccountId(MONZO, raw.getId())
      if absent:
          domain = new Account(user=raw.getUser(), provider=MONZO,
                               providerAccountId=raw.getId(),
                               accountType=normalise(raw.getAccountType()),
                               institutionName="Monzo",
                               displayName=raw.getDescription(),   // may be null
                               currency=raw.getCurrency())
      else:
          domain.setAccountType(normalise(raw.getAccountType()))   // provider-owned, refresh ok
      if raw.isClosed() || !raw.getConnection().isActive(): domain.archive()
      else: domain.unarchive()
      accountRepository.save(domain); collect
  return collected

ingestTransactions(account):
  cursor = account.getRawSyncedThrough() ?? Instant.EPOCH
  rows = monzoTransactionRepository.findByAccountIdUpdatedAfter(account.getProviderAccountId(), cursor)
  maxUpdated = cursor
  for raw in rows:
      maxUpdated = max(maxUpdated, raw.getUpdatedAt())
      if raw.isDeclined(): continue                     // never mapped; cursor still advances
      transactionRepository.upsert(
          userId=account.user.id, accountId=account.id, provider="MONZO",
          providerTransactionId=raw.getId(),
          amountMinorUnits=(long) raw.getAmount(),      // widen INTEGER → BIGINT
          currency=raw.getCurrency(),
          status=(raw.getMonzoSettledAt() == null ? "PENDING" : "SETTLED"),
          description=raw.getDescription(), merchantName=raw.getMerchantName(),
          merchantCategory=raw.getMerchantCategory(),
          notes=raw.getNotes(),                         // seed-on-insert only (upsert omits from update set)
          occurredAt=raw.getMonzoCreatedAt(), settledAt=raw.getMonzoSettledAt())
  if maxUpdated > cursor:
      account.setRawSyncedThrough(maxUpdated); accountRepository.save(account)
```

### `service/ingest/IngestService.java`

Deps: `List<ProviderIngestor>` (Spring injects all impls), `PlatformTransactionManager`
(build a `TransactionTemplate`, same pattern as `TransactionSyncService`). Per-account transaction
boundary; per-account error isolation:

```java
public void ingestAll() {
    for (ProviderIngestor ingestor : ingestors) {
        List<Account> accounts = txTemplate.execute(s -> ingestor.ingestAccounts());
        for (Account account : accounts) {
            try {
                txTemplate.execute(s -> { ingestor.ingestTransactions(account); return null; });
            } catch (Exception e) {
                log.error("Ingest failed [provider={}, account={}] - {}",
                        ingestor.provider(), account.getId(), e.getMessage(), e);
            }
        }
    }
}
```

### `service/ingest/BalanceRefreshService.java`

Deps: `MonzoAccountRepository`, `AccountRepository`, `MonzoConnectionService`, `AccountInformationProvider`.
No class-level `@Transactional` — each `save` commits alone; a mid-run failure loses nothing.

```
refreshAll():
  byConnection = monzoAccountRepository.findAllSyncable().groupBy(a -> a.getConnection())
  for (connection, rawAccounts) in byConnection:
      try: token = connectionService.getDecryptedAccessToken(connection.getId(), connection.getUser().getId())
      catch (Exception e): WARN "skipping connection"; continue
      for raw in rawAccounts:
          domain = accountRepository.findByProviderAndProviderAccountId(MONZO, raw.getId())
          if absent: continue                            // mapping hasn't created it yet
          try:
              bal = bankClient.getBalance(token, raw.getId())
              if !bal.currency().equals(domain.getCurrency()): WARN (store anyway — provider is truth)
              domain.recordBalance(bal.balanceMinorUnits(), Instant.now())
              accountRepository.save(domain)
          catch (BankConnectionRevokedException e): WARN; break   // rest of this connection is dead
          catch (Exception e): WARN; continue                     // isolate per account
```

### Read services

`service/account/AccountService.java` — deps `AccountRepository`, `TransactionRepository`:

```java
public List<AccountResponse> listAccounts(UUID userId, boolean includeArchived)
public AccountSummaryResponse getSummary(UUID userId, UUID accountId, ZoneId zone)
```

`getSummary`: `findByIdAndUserId` → else `ApiException(RESOURCE_NOT_FOUND)`. Boundaries:
`today = LocalDate.now(zone).atStartOfDay(zone).toInstant()`,
`weekStart = LocalDate.now(zone).with(DayOfWeek.MONDAY)...`,
`monthStart = LocalDate.now(zone).withDayOfMonth(1)...`,
`floor = min(weekStart, monthStart)`; call `sumWindows`; out = `Math.abs(...)`.

`service/transaction/TransactionQueryService.java` — dep `TransactionRepository`:

```java
public PageResponse<TransactionResponse> list(UUID userId, @Nullable UUID accountId,
        @Nullable Instant from, @Nullable Instant to, int page, int size)
```

Validates `from < to` when both present → else `ApiException(VALIDATION_ERROR, "from must be before to")`.
Defaults: `from → Instant.EPOCH`, `to → Instant.parse("9999-12-31T00:00:00Z")`. Calls
`findFiltered(..., PageRequest.of(page, size))` (unsorted — order lives in the JPQL), maps to DTOs,
wraps via `PageResponse.from`.

### Controllers

`api/account/AccountController.java` and `api/transaction/TransactionController.java` — thin,
`@CurrentUserId UUID userId`, return `ApiResponse.of(...)`. Param validation via Bean Validation
(`@Validated` on the class): `@Min(0) page`, `@Min(1) @Max(200) size`; `zone` parsed with
`ZoneId.of` in the controller → catch `DateTimeException` → `ApiException(VALIDATION_ERROR,
"invalid zone")`. `Instant` params bind natively from ISO-8601 strings.

### Job wiring (decision 7)

`TransactionSyncJob` — constructor gains `IngestService` + `BalanceRefreshService`; after
the existing per-account sync loop:

```java
try { ingestService.ingestAll(); }      catch (Exception e) { log.error("Ingest failed after sync", e); }
try { balanceRefreshService.refreshAll(); } catch (Exception e) { log.error("Balance refresh failed after sync", e); }
```

`TransactionSyncService.backfillAsync` — same two deps; after `backfill(connectionId)` returns
(including the NEEDS_REAUTH pause path — partial data should still surface), run the same two
guarded calls. No dependency cycle: mapping/balance services never reference sync.

`DevMonzoController` — add `POST /api/dev/monzo/ingest` mirroring the `/backfill` pattern:
calls `ingestAll()` then `refreshAll()`, returns `ApiResponse.ok()`.

## External Integration

No new provider-contract methods — `getBalance` and `rawJson` landed in #10 (contract now split by
capability, PR #84: balance lives on `BalanceCapability`; reshaped by #12: rawJson on `Sourced<T>`
envelopes, fetch start as sealed `SyncPosition`). New WireMock file stub
required: `src/test/resources/wiremock/mappings/monzo/balance/balance-success.json` for
`GET /balance?account_id=...` returning Monzo's shape `{"balance": 12345, "total_balance": 12345,
"currency": "GBP", "spend_today": 0}` (check `MonzoBalanceResponse` for the exact fields the
client reads before writing the stub).

## Configuration

**None.** The chained wiring reuses `monzo.transaction-sync.job-cron`; no new properties, env vars,
or profile differences.

## Edge Cases & Failure Modes

| Case | Handling |
|------|----------|
| Raw account exists but domain account missing when transactions map | Impossible within a run: `ingestAccounts()` always runs first in `ingestAll()` |
| Backfill window lands late (older transactions arrive after newer ones) | Raw rows get fresh `updated_at` on insert → cursor on `updated_at` catches them regardless of `occurred_at` (this is *why* decision 2) |
| Settlement flip after a row was mapped | Raw upsert bumps `updated_at` → re-mapped; upsert flips `status`/`settled_at` |
| Settled flip Monzo never re-sends (older than raw delta cursor) | Accepted gap until webhooks (#5); domain mirrors raw faithfully — documented, not fixed here |
| Declined transaction | Never mapped; cursor still advances past it. Assumed terminal (Monzo semantics) — a declined-after-mapped flip is ignored |
| Re-map with user-edited notes / excluded flag (future PATCH) | Upsert's update set omits both columns — cannot clobber |
| Reconnect after disconnect | Same `(provider, provider_account_id)` → same domain UUID revives (`unarchive()`); history intact |
| Account closed mid-run / final rows synced then closed | `ingestTransactions` runs for archived accounts too (returns empty when no new raw rows) — nothing stranded |
| Unknown Monzo account type | `OTHER` + WARN (decision 4) |
| `rawJson` null/empty from provider | Store NULL — **`encrypt` throws on null/empty**, guard required |
| Balance currency ≠ account currency | WARN + store balance anyway (provider is source of truth) |
| Connection revoked during balance refresh | `BankConnectionRevokedException` → WARN + skip rest of that connection; other connections continue |
| Concurrent mapping runs (hourly job vs post-backfill trigger) | Upsert is idempotent; worst case the cursor regresses one write and re-maps a few rows next run — harmless. Default single-thread scheduler makes overlap rare; documented, not locked against |
| Two raw rows sharing one `updated_at` at the cursor boundary | `>` comparison could skip one in theory (µs precision makes it ~impossible); any later raw re-touch self-heals. Accepted |
| Empty raw tables / connection with zero accounts | `ingestAll()` and `refreshAll()` are clean no-ops |
| `amount INTEGER` (raw) vs `BIGINT` (domain) | Explicit widening cast in the mapper |

## Test Strategy

**Unit** (Mockito, same package as class under test):

- `MonzoIngestorTest` — `mapsNewAccountWithDefaults`, `refreshesAccountTypeOnRemap`,
  parameterised `normalisesAccountTypes` (uk_retail→CURRENT, uk_retail_joint→CURRENT,
  uk_monzo_flex→CREDIT_CARD, unknown→OTHER), `archivesClosedAccount`,
  `archivesWhenConnectionInactive`, `unarchivesReopenedAccount`, `skipsDeclined_cursorStillAdvances`,
  `mapsPendingAndSettledStatus`, `widensAmountToLong`, `advancesCursorToMaxUpdatedAt`,
  `noNewRows_cursorUntouched_noSave`
- `IngestServiceTest` — `ingestsAccountsThenTransactionsPerIngestor`,
  `accountFailureIsolated_othersStillIngested`
- `BalanceRefreshServiceTest` — `refreshesAndStampsBalance`, `skipsUnmappedRawAccounts`,
  `revokedConnection_abandonsThatConnectionOnly`, `perAccountErrorContinues`,
  `currencyMismatch_warnsButStores`
- `AccountServiceTest` — `listsActiveOrdered`, `includeArchivedReturnsAll`,
  `summaryComputesWindowBoundariesInZone` (fix a clock/zone; assert Monday week start and
  min(week,month) floor), `summaryConvertsOutToPositive`, `unknownOrForeignAccount_404`
- `TransactionQueryServiceTest` — `defaultsOpenEndedRange`, `rejectsFromAfterTo`,
  `mapsPageToPageResponse`
- `AccountControllerTest` / `TransactionControllerTest` — `@WebMvcTest` + the documented `@Import`
  set (see `.agents/context/testing.md` — mock `JweTokenService`, `CookieService`, `AuthService`):
  401 unauthenticated, 200 envelope shape, `size=201 → 400`, `page=-1 → 400`, bad `zone` → 400,
  ISO instant binding
- `TransactionSyncServiceTest` (existing — extend) — `persistsEncryptedRawPayload`
  (verify `encrypt` called with `rawJson`, upsert receives ciphertext), `nullRawJson_storesNull_noEncryptCall`;
  add the `EncryptionService` mock to existing constructions
- `TransactionSyncJobTest` (existing — extend) — `chainsIngestThenBalancesAfterSync`,
  `ingestFailureDoesNotBlockBalances`

**Integration** (`*IT`):

- `IngestIT` extends `AbstractPostgresIntegrationTest` — seed raw via `TestDataFactory`,
  call `IngestService.ingestAll()` directly: `ingestsRawToDomainEndToEnd`,
  `remapIsIdempotent_noDuplicateRows`, `settledFlipPropagates` (update raw row + bump its
  `updated_at`, re-map, assert domain SETTLED), `declinedNeverMapped`,
  `notesSurviveRemap` (edit domain notes in SQL, re-touch raw, re-map, assert notes unchanged),
  `closedRawAccountArchivesDomain`, `cursorPersistedAcrossRuns`
- `SyncPipelineIT` extends `AbstractMonzoWireMockIT` — stub accounts + transactions + balance
  (add the new balance stub file), invoke `TransactionSyncJob.syncAllAccounts()` once:
  `fullChain_rawCaptured_domainIngested_balanceStamped` — asserts `raw_payload_encrypted` is
  non-null, ≠ plaintext, and `EncryptionService.decrypt` round-trips it; domain rows exist;
  `balance_as_of` stamped
- `AccountEndpointsIT` / `TransactionEndpointsIT` extend `AbstractMonzoWireMockIT` (RANDOM_PORT) —
  RestAssured: paged/filtered reads, half-open `[from,to)` boundary assertion, user isolation
  (user B sees empty), 401 unauthenticated, foreign `accountId` filter → empty page

**TestDataFactory additions:** `createUserAccount(User user)` /
`createUserAccount(User user, String providerAccountId)` (active MONZO CURRENT account),
`createDomainTransaction(Account account, User user, long amountMinorUnits, Instant occurredAt)`
(+ settled/pending variants as needed). Keep raw-side helpers untouched.

## New Files

| Path (under `budgeteer-server/src/` unless noted) | Purpose |
|---|---|
| `main/resources/db/migration/V11__add_raw_payload_and_mapping_index.sql` | Raw capture columns + cursor index |
| `main/resources/db/migration/V12__create_user_accounts.sql` | Domain accounts table |
| `main/resources/db/migration/V13__create_transactions.sql` | Domain transactions table |
| `main/java/.../domain/account/Account.java` | Domain account entity |
| `main/java/.../domain/account/AccountType.java` | CURRENT/SAVINGS/CREDIT_CARD/OTHER |
| `main/java/.../domain/account/Provider.java` | MONZO/TRUELAYER |
| `main/java/.../domain/account/package-info.java` | `@NullMarked` |
| `main/java/.../domain/transaction/Transaction.java` | Domain transaction entity (read model) |
| `main/java/.../domain/transaction/TransactionStatus.java` | PENDING/SETTLED |
| `main/java/.../domain/transaction/package-info.java` | `@NullMarked` |
| `main/java/.../repository/AccountRepository.java` | Domain account queries |
| `main/java/.../repository/TransactionRepository.java` | Upsert + filtered page + window sums |
| `main/java/.../service/ingest/ProviderIngestor.java` | Per-provider ingest contract |
| `main/java/.../service/ingest/MonzoIngestor.java` | First impl |
| `main/java/.../service/ingest/IngestService.java` | Orchestrator (TransactionTemplate) |
| `main/java/.../service/ingest/BalanceRefreshService.java` | getBalance → snapshot columns |
| `main/java/.../service/ingest/package-info.java` | `@NullMarked` |
| `main/java/.../service/account/AccountService.java` | List + summary reads |
| `main/java/.../service/account/package-info.java` | `@NullMarked` |
| `main/java/.../service/transaction/TransactionQueryService.java` | Filtered/paged reads |
| `main/java/.../service/transaction/package-info.java` | `@NullMarked` |
| `main/java/.../api/account/AccountController.java` | GET /accounts, GET /accounts/{id}/summary |
| `main/java/.../api/account/dto/AccountResponse.java` | DTO |
| `main/java/.../api/account/dto/AccountSummaryResponse.java` | DTO (+ nested WindowSums) |
| `main/java/.../api/account/dto/package-info.java` + `api/account/package-info.java` | `@NullMarked` |
| `main/java/.../api/transaction/TransactionController.java` | GET /transactions |
| `main/java/.../api/transaction/dto/TransactionResponse.java` | DTO |
| `main/java/.../api/transaction/dto/package-info.java` + `api/transaction/package-info.java` | `@NullMarked` |
| `main/java/.../api/common/PageResponse.java` | House paging record |
| `test/java/.../service/ingest/MonzoIngestorTest.java` | Unit |
| `test/java/.../service/ingest/IngestServiceTest.java` | Unit |
| `test/java/.../service/ingest/BalanceRefreshServiceTest.java` | Unit |
| `test/java/.../service/account/AccountServiceTest.java` | Unit |
| `test/java/.../service/transaction/TransactionQueryServiceTest.java` | Unit |
| `test/java/.../api/account/AccountControllerTest.java` | `@WebMvcTest` |
| `test/java/.../api/transaction/TransactionControllerTest.java` | `@WebMvcTest` |
| `test/java/.../integration/IngestIT.java` | Raw→domain pipeline IT |
| `test/java/.../integration/SyncPipelineIT.java` | Full-chain IT (WireMock) |
| `test/java/.../integration/AccountEndpointsIT.java` | Endpoint IT |
| `test/java/.../integration/TransactionEndpointsIT.java` | Endpoint IT |
| `test/resources/wiremock/mappings/monzo/balance/balance-success.json` | Balance stub |

## Modified Files

| Path | Change |
|------|--------|
| `main/java/.../domain/monzo/MonzoAccount.java` | + nullable `rawPayloadEncrypted` field + accessor |
| `main/java/.../domain/monzo/MonzoTransaction.java` | + nullable `rawPayloadEncrypted` field |
| `main/java/.../repository/MonzoTransactionRepository.java` | upsert: + raw param/column/update-set; + `findByAccountIdUpdatedAfter` |
| `main/java/.../service/monzo/TransactionSyncService.java` | + `EncryptionService` dep, `encryptRaw` guard, wire raw into both upserts; + ingest/balance calls after backfill in `backfillAsync` |
| `main/java/.../service/monzo/TransactionSyncJob.java` | + chain `ingestAll()` + `refreshAll()` after sync loop (guarded) |
| `main/java/.../api/dev/DevMonzoController.java` | + `POST /ingest` dev trigger |
| `test/java/.../integration/TestDataFactory.java` | + domain-side helpers |
| `test/java/.../service/monzo/TransactionSyncServiceTest.java` | + EncryptionService mock, raw-capture cases |
| `test/java/.../service/monzo/TransactionSyncJobTest.java` (if present; else create) | + chaining cases |

## Implementation Order

Each step compiles green and is separately committable:

1. **V11 + raw capture** — migration; entity fields; repo upsert param; `TransactionSyncService`
   `EncryptionService` dep + `encryptRaw` + wiring; extend `TransactionSyncServiceTest`
2. **V12 + V13 + domain layer** — enums, `Account`, `Transaction`, `AccountRepository`,
   `TransactionRepository` (upsert, filtered page, window sums), package-infos
3. **Ingest pipeline** — `ProviderIngestor`, `MonzoIngestor`, `IngestService` +
   their unit tests
4. **Balance refresh** — `BalanceRefreshService` + unit tests + WireMock balance stub file
5. **Wiring** — `TransactionSyncJob` chaining, `backfillAsync` hook, `DevMonzoController` trigger
   + job unit tests
6. **Read path** — `PageResponse`, DTOs, `AccountService`, `TransactionQueryService`, both
   controllers + all their unit tests
7. **Integration tests** — `TestDataFactory` additions, `IngestIT`, `SyncPipelineIT`,
   `AccountEndpointsIT`, `TransactionEndpointsIT`
8. **Finish** — `/check`; update `.agents/tasks/tasks.md` and `.agents/memory.md`; PR

## Key Files to Read Before Implementing

| File | Why |
|------|-----|
| `budgeteer-server/.../service/monzo/TransactionSyncService.java` | TransactionTemplate pattern, upsert call sites you're modifying |
| `budgeteer-server/.../repository/MonzoTransactionRepository.java` | The native ON CONFLICT upsert pattern to copy (and modify) |
| `budgeteer-server/.../domain/monzo/MonzoAccount.java` | Entity conventions (timestamps, accessors) to mirror in `Account` |
| `budgeteer-server/.../domain/user/User.java` | `@GeneratedValue(strategy = GenerationType.UUID)` pattern |
| `budgeteer-server/.../service/common/EncryptionService.java` | Throws on null/empty — the guard contract |
| `budgeteer-server/.../api/monzo/MonzoController.java` | Controller + `ApiResponse` + `@CurrentUserId` idiom |
| `budgeteer-server/.../api/dev/DevMonzoController.java` | Dev-trigger pattern for `/ingest` |
| `provider-api/.../provider/BalanceCapability.java` + `TransactionsCapability.java` + `model/BankBalance.java` + `model/BankTransaction.java` + `model/Sourced.java` + `model/SyncPosition.java` | The contracts you consume (`getBalance`, `sourced.rawJson()`, sealed fetch positions) |
| `provider-monzo/.../dto/MonzoBalanceResponse.java` | Exact fields for the balance WireMock stub |
| `.agents/context/testing.md` | `@WebMvcTest` import set, IT base classes, stub-file conventions |
| `.agents/tasks/closed/transaction-sync/plan.md` | Spec-depth gold standard; background on sync semantics |

## Open Questions / Assumptions

- Summary `zone` param defaults to `Europe/London` (grill decision 9) — revisit if/when
  `UserSettings` gains a timezone
- Summary sums include PENDING transactions and exclude `excluded_from_analytics` rows — assumed
  the product-correct reading of "in/out"
- Declined is assumed terminal at Monzo — a mapped transaction later flagged declined in raw is
  left in the domain (documented, not handled)
- `display_order` defaults to 0 for all accounts until the PATCH endpoint exists — list order is
  then effectively `created_at`
- Filtering `GET /transactions` by a non-owned `accountId` returns an empty page rather than 404 —
  chosen to keep the query single-pass; flip to 404 later if the frontend needs the distinction

---

## Implementer Kickoff Prompt

> Copy-paste this to the implementing model/tool.

You are implementing **Domain Model Mapping (slice 1)** in the Budgeteer repo (Spring Boot 4.1 /
Java 25 / PostgreSQL 16, multi-module Maven: `provider-api`, `provider-monzo`,
`budgeteer-server` — all your changes are in `budgeteer-server`).

**Before writing any code, read:** `.agents/context/architecture.md`,
`.agents/context/conventions.md`, `.agents/context/testing.md`, this spec
(`.agents/tasks/open/domain-model-mapping/plan.md`), and every file in *Key Files to Read Before
Implementing*. Where a context doc contradicts this spec (migration numbers, package names,
Spring Boot version), **this spec wins** — it was verified against the repo on 2026-08-22.

**Then:** work on the existing branch `feature/domain-model-mapping` (branched from `main`) and
implement strictly in the order in *Implementation Order*. Follow the codebase ground rules:
constructor injection, thin controllers, DTOs not entities, `@NullMarked` package-infos,
checkstyle limits (120-char lines, 500-line files, 50-line methods), never modify existing
migrations V1–V10, never log tokens/PII/raw payloads (plaintext or ciphertext). Create exactly
the files in *New Files*; change exactly those in *Modified Files*.

**Do not** redesign, add scope, or deviate from this spec — the decisions in the Decisions Log
are final. If something is genuinely underspecified, stop and ask rather than guessing.

**Definition of Done:** every *Acceptance Criteria* box ticked, all tests in *Test Strategy*
written and passing, and `/check` (checkstyle + unit + integration) green before opening the PR.
