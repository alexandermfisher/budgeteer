# Phase 4: Transaction Sync

> **Priority:** P2 | **Estimate:** 2–3 days | **Status:** ✅ Complete & merged (PR #55) | **Branch:** `feature/transaction-sync`
>
> ⚠️ **Correction (2026-06-20):** any reference to a `since_id` query parameter below is historical and incorrect. Monzo has **no** `since_id` param — the cursor is passed via `since` (which accepts a timestamp *or* a transaction id). Sending it as `since_id` caused an infinite backfill loop, fixed in PR #55. See `docs/features/MONZO-TRANSACTION-SYNC.md`.

## Goal

Fetch and store raw Monzo data locally — accounts and transactions exactly as Monzo returns them. This branch is **raw sync only**: `monzo_accounts` and `monzo_transactions` are a faithful mirror of the Monzo API, nothing more.

Domain modelling (mapping to a unified `transactions` table that covers Lloyds, credit cards, manual entries, etc.) is a separate follow-on feature. That feature will add the mapping layer from `monzo_transactions` → the domain model once we have real data to design against.

---

## Out of Scope (deferred to domain model feature)

- `user_accounts` / `transactions` domain tables
- Mapping layer: Monzo → domain
- `GET /api/transactions` public endpoint (will query the domain table, not `monzo_transactions`)
- Manual transaction entry
- Categories, custom fields

---

## Scope

- [x] DTO refactor: move `TokenResponse` out of `MonzoClient` → `client/monzo/dto/`; move `ConnectionStatus` out of `MonzoController` → `api/monzo/dto/MonzoStatusResponse`
- [x] V7 migration: `monzo_accounts`
- [x] V8 migration: `monzo_transactions`
- [x] `MonzoAccount` + `MonzoTransaction` JPA entities
- [x] `MonzoAccountRepository` + `MonzoTransactionRepository` (incl. native upsert)
- [x] `client/monzo/dto/` — new Monzo API response records
- [x] `MonzoClient` — add `getAccounts()` and `getTransactions()`
- [x] `AsyncConfig` — `@EnableAsync` + `backfillTaskExecutor` thread pool
- [x] `MonzoConnectionCreatedEvent` + `TransactionSyncEventListener` (async post-OAuth backfill)
- [x] `TransactionSyncService` — `backfill()` and `deltaSync()`
- [x] `TransactionSyncJob` — `@Scheduled` 60-minute delta sync
- [x] Publish `MonzoConnectionCreatedEvent` in `MonzoController.handleCallback()`
- [x] Dev retrigger endpoint: `POST /api/test/auth/sync/trigger` (dev profile only)
- [x] `ErrorCode` addition: `MONZO_SYNC_ERROR` (502)
- [x] Unit + integration tests

---

## Database Schema

### V7 — `monzo_accounts`

```sql
CREATE TABLE monzo_accounts (
    id                  VARCHAR(255) PRIMARY KEY,           -- Monzo acc_xxx (natural PK)
    connection_id       UUID NOT NULL REFERENCES monzo_connections(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id)  ON DELETE CASCADE,
    account_type        VARCHAR(50)  NOT NULL,              -- uk_retail, uk_retail_joint, etc.
    description         VARCHAR(500),
    currency            VARCHAR(3)   NOT NULL,
    last_synced_at      TIMESTAMP WITH TIME ZONE NULL,      -- NULL = never synced
    last_transaction_id VARCHAR(255) NULL,                  -- since_id cursor for next delta sync
    closed              BOOLEAN NOT NULL DEFAULT false,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_monzo_accounts_connection_id ON monzo_accounts(connection_id);
CREATE INDEX idx_monzo_accounts_user_id       ON monzo_accounts(user_id);
CREATE INDEX idx_monzo_accounts_active        ON monzo_accounts(connection_id) WHERE closed = false;
```

### V8 — `monzo_transactions`

Raw Monzo data only — no custom fields. Those belong in the domain model.

```sql
CREATE TABLE monzo_transactions (
    id                  VARCHAR(255) PRIMARY KEY,           -- Monzo tx_xxx (natural PK)
    account_id          VARCHAR(255) NOT NULL REFERENCES monzo_accounts(id) ON DELETE CASCADE,
    user_id             UUID         NOT NULL REFERENCES users(id)          ON DELETE CASCADE,
    amount              INTEGER      NOT NULL,              -- pence; negative = debit
    currency            VARCHAR(3)   NOT NULL,
    description         VARCHAR(500),
    merchant_name       VARCHAR(255),
    merchant_category   VARCHAR(100),
    notes               TEXT,
    is_declined         BOOLEAN NOT NULL DEFAULT false,
    monzo_created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    monzo_settled_at    TIMESTAMP WITH TIME ZONE,           -- NULL = pending
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_monzo_txn_user_account    ON monzo_transactions(user_id, account_id);
CREATE INDEX idx_monzo_txn_user_created    ON monzo_transactions(user_id, monzo_created_at DESC);
CREATE INDEX idx_monzo_txn_account_created ON monzo_transactions(account_id, monzo_created_at DESC);
```

---

## DTO Structure

All records in dedicated files — no inline records inside classes.

```
client/monzo/dto/
  TokenResponse.java              ← MOVE out of MonzoClient (existing)
  MonzoAccountResponse.java       ← new: single account from GET /accounts
  MonzoAccountsResponse.java      ← new: wrapper { List<MonzoAccountResponse> }
  MonzoMerchantResponse.java      ← new: merchant sub-object on a transaction
  MonzoTransactionResponse.java   ← new: single transaction from GET /transactions
  MonzoTransactionsResponse.java  ← new: wrapper { List<MonzoTransactionResponse> }

api/monzo/dto/
  MonzoConnectInitResponse.java   ← existing
  MonzoConnectionResponse.java    ← existing
  MonzoStatusResponse.java        ← MOVE out of MonzoController (existing inline ConnectionStatus)
```

`Monzo` prefix on client DTOs avoids import ambiguity in `TransactionSyncService`. No `api/transaction/dto/` in this branch — that belongs in the domain model feature.

---

## New Files

| Path | Purpose |
|------|---------|
| `db/migration/V7__create_monzo_accounts.sql` | Raw Monzo accounts |
| `db/migration/V8__create_monzo_transactions.sql` | Raw Monzo transactions |
| `domain/monzo/MonzoAccount.java` | Account entity; String PK |
| `domain/monzo/MonzoTransaction.java` | Transaction entity; String PK |
| `repository/MonzoAccountRepository.java` | Queries: by connection, by user, `findAllSyncable()` |
| `repository/MonzoTransactionRepository.java` | JPQL queries + native `upsert()` |
| `config/AsyncConfig.java` | `@EnableAsync`, `backfillTaskExecutor`, uncaught exception handler |
| `config/TransactionSyncProperties.java` | `@ConfigurationProperties(prefix="monzo.transaction-sync")` |
| `service/monzo/MonzoConnectionCreatedEvent.java` | `record MonzoConnectionCreatedEvent(UUID connectionId, UUID userId)` |
| `service/monzo/TransactionSyncEventListener.java` | `@Async("backfillTaskExecutor") @EventListener` → `backfill()` |
| `service/monzo/TransactionSyncService.java` | `backfill(UUID connectionId)` + `deltaSync(String accountId)` |
| `service/monzo/TransactionSyncJob.java` | `@Scheduled` cron → `deltaSync` for all syncable accounts |
| `client/monzo/dto/MonzoAccountResponse.java` | Single account from Monzo API |
| `client/monzo/dto/MonzoAccountsResponse.java` | `GET /accounts` wrapper |
| `client/monzo/dto/MonzoMerchantResponse.java` | Merchant sub-object |
| `client/monzo/dto/MonzoTransactionResponse.java` | Single transaction from Monzo API |
| `client/monzo/dto/MonzoTransactionsResponse.java` | `GET /transactions` wrapper |
| `api/monzo/dto/MonzoStatusResponse.java` | Moved from inline `ConnectionStatus` in `MonzoController` |

## Modified Files

| Path | Change |
|------|--------|
| `client/monzo/MonzoClient.java` | Add `getAccounts()`, `getTransactions()`; remove inline `TokenResponse` (→ `client/monzo/dto/`) |
| `api/monzo/MonzoController.java` | Remove inline `ConnectionStatus` (→ `MonzoStatusResponse`); inject `ApplicationEventPublisher`; publish event post-callback |
| `api/dev/DevAuthController.java` | Add `POST /api/dev/sync/trigger` endpoint |
| `api/common/ErrorCode.java` | Add `MONZO_SYNC_ERROR` (502) |
| `application.properties` | Add `monzo.transaction-sync.*` properties |

---

## MonzoClient New Methods

```java
// GET /accounts
List<MonzoAccountResponse> getAccounts(String accessToken)

// GET /transactions?account_id=x&expand[]=merchant&limit=x[&since_id=x]
List<MonzoTransactionResponse> getTransactions(String accessToken, String accountId,
        @Nullable String sinceId, int limit)
```

New records in `client/monzo/dto/` (one per file):

```java
record MonzoAccountResponse(String id, String type, @Nullable String description,
        String currency, boolean closed) {}

record MonzoAccountsResponse(List<MonzoAccountResponse> accounts) {}

record MonzoMerchantResponse(@Nullable String name, @Nullable String category) {}

record MonzoTransactionResponse(String id, int amount, String currency,
        @Nullable String description, @Nullable MonzoMerchantResponse merchant,
        @Nullable String notes, @Nullable String declineReason,
        String created, @Nullable String settled) {}

record MonzoTransactionsResponse(List<MonzoTransactionResponse> transactions) {}
```

Both methods follow the existing `handleMonzoError()` pattern: 401 → `MONZO_CONNECTION_REVOKED`.

---

## Async Backfill Architecture

Spring Events chosen over direct `@Async` — keeps `TransactionSyncService` out of `MonzoController`'s dependencies, and allows future listeners (e.g. notifications) without touching OAuth code.

```
MonzoController.handleCallback()
  └─ eventPublisher.publishEvent(new MonzoConnectionCreatedEvent(connectionId, userId))

TransactionSyncEventListener
  └─ @Async("backfillTaskExecutor") @EventListener
     onConnectionCreated() → syncService.backfill(connectionId)
     catches all exceptions and logs — never lets a backfill failure surface to the caller
```

`AsyncConfig`:
- `@EnableAsync`
- `backfillTaskExecutor`: `ThreadPoolTaskExecutor` (core=2, max=5, queue=10, prefix=`backfill-`, `CallerRunsPolicy`)
- `AsyncUncaughtExceptionHandler` → logs method + exception

---

## TransactionSyncService Logic

### `backfill(UUID connectionId)`

```
1. Load MonzoConnection (system-scoped, no userId check needed)
2. token = connectionService.getDecryptedAccessToken(connectionId, userId)
3. accounts = monzoClient.getAccounts(token)
4. Upsert each account into monzo_accounts
5. For each non-closed account:
     sinceId = null
     loop:
       page = monzoClient.getTransactions(token, account.id, sinceId, 100)
       for each tx: transactionRepository.upsert(...)
       if page.size() < 100: break
       sinceId = page.last().id()
     account.recordSyncComplete(latestTxId)
     accountRepository.save(account)
```

### `deltaSync(String accountId)`

Same loop but `sinceId = account.getLastTransactionId()`. Uses plain `save()` (new rows dominate). If `lastTransactionId` is null, fetches up to 100 most recent (90-day fallback for connections outside the SCA window).

### Upsert (backfill)

Native SQL in `MonzoTransactionRepository` — handles re-runs and pending → settled transitions without SELECT + UPDATE per row:

```sql
INSERT INTO monzo_transactions (id, account_id, user_id, amount, ...)
VALUES (:id, ...)
ON CONFLICT (id) DO UPDATE SET
    amount           = EXCLUDED.amount,
    monzo_settled_at = EXCLUDED.monzo_settled_at,
    notes            = EXCLUDED.notes,
    is_declined      = EXCLUDED.is_declined,
    updated_at       = now()
```

---

## Dev Retrigger Workflow

During development you'll need to reset the DB and re-run the full sync repeatedly without going through the real Monzo OAuth every time.

**Reset + full retrigger (dev profile):**
```
1. app.database.clean-on-startup=true already wipes tables on restart
2. Restart the app → DB is clean
3. Hit POST /api/dev/sync/trigger with a valid session cookie
   → looks up the active MonzoConnection for your user
   → calls transactionSyncService.backfill(connectionId) directly
   → sync runs against WireMock or real Monzo
```

`DevAuthController` gets a new endpoint:
```java
// Dev profile only — triggers full backfill for the authenticated user's active connection
@PostMapping("/sync/trigger")
public ResponseEntity<ApiResponse<Void>> triggerSync(@CurrentUserId UUID userId) { ... }
```

This bypasses the OAuth event entirely — useful because you won't want to redo the real Monzo OAuth on every dev iteration. The endpoint is gated by `@Profile("dev")` on the controller.

**WireMock for testing:** Integration tests stub `GET /accounts` and `GET /transactions` via WireMock, so the full backfill + delta cycle can run without hitting real Monzo at all. The `triggerSync` dev endpoint also works against a WireMocked Monzo in integration tests.

---

## Properties

```properties
# application.properties
monzo.transaction-sync.job-cron=0 0 */1 * * *
monzo.transaction-sync.backfill-core-pool-size=2
monzo.transaction-sync.backfill-max-pool-size=5
monzo.transaction-sync.backfill-queue-capacity=10
```

---

## Test Strategy

**Unit tests:**
- `MonzoClientTest` — `getAccounts()` happy path + 401; `getTransactions()` with/without `sinceId`; verify `expand[]=merchant` always present
- `TransactionSyncServiceTest` — backfill with 2 accounts; pagination (100 then 5 items); deltaSync with existing cursor; deltaSync with null cursor (first run)
- `TransactionSyncJobTest` — 3 accounts, 1 throws → other 2 complete
- `TransactionSyncEventListenerTest` — event published → `backfill()` called

**Integration tests (all WireMocked):**
- `TransactionSyncIT` — full backfill: WireMock stubs accounts + paginated transactions; assert `monzo_accounts` rows, `monzo_transactions` rows, cursor updated
- `DeltaSyncIT` — seed account with `lastTransactionId`; stub `?since_id=x` → 5 new txns; assert cursor advances
- `TransactionSyncJobIT` — 2 accounts across 2 connections; job runs → both synced
- `DevSyncTriggerIT` — `POST /api/dev/sync/trigger` → backfill fires; assert rows inserted

**WireMock stubs needed:**
- `GET /accounts` → `MonzoAccountsResponse` with 1–2 accounts
- `GET /transactions?account_id=acc_test&expand[]=merchant&limit=100` → 100 items (first page)
- `GET /transactions?account_id=acc_test&...&since_id=tx_100` → 5 items (last page)
- `GET /transactions?account_id=acc_test&...&since_id=tx_existing` → delta page

---

## Implementation Order

1. DTO refactor: `TokenResponse` → `client/monzo/dto/`; `ConnectionStatus` → `api/monzo/dto/MonzoStatusResponse`; compile + full test run
2. V7/V8 migrations + local verify (`mvn test -Dgroups=integration -Dtest=FlywayMigrationIT`)
3. `MonzoAccount` + `MonzoTransaction` entities (compile check)
4. `MonzoAccountRepository` + `MonzoTransactionRepository` (incl. native upsert query)
5. `ErrorCode.MONZO_SYNC_ERROR` addition
6. New `client/monzo/dto/` records + `MonzoClient` new methods + `MonzoClientTest` (WireMock)
7. `AsyncConfig` + `TransactionSyncProperties` + add properties
8. `TransactionSyncService` + `TransactionSyncServiceTest`
9. `MonzoConnectionCreatedEvent` + `TransactionSyncEventListener`
10. Publish event in `MonzoController` + update `MonzoControllerTest`
11. `TransactionSyncJob` + `TransactionSyncJobTest`
12. Dev retrigger endpoint in `DevAuthController`
13. Integration tests: `TransactionSyncIT`, `DeltaSyncIT`, `TransactionSyncJobIT`, `DevSyncTriggerIT`

---

## Deferred: Domain Model Feature

The follow-on branch will design the unified domain model with real Monzo data available to inform decisions. It will cover:

- `user_accounts` table — user-defined accounts: "Monzo", "Lloyds", "Amex", etc.
- `transactions` table — source-agnostic, enrichable: category, custom fields, multi-source
- Mapping layer: `monzo_transactions` → `transactions` (run on existing data + live)
- Manual entry API (Lloyds salary, rent, credit card spend, direct debits)
- `GET /api/transactions` endpoint (queries `transactions`, not `monzo_transactions`)
- `TransactionResponse` / `TransactionPageResponse` DTOs

---

## Key Files to Read Before Implementing

| File | Why |
|------|-----|
| `client/monzo/MonzoClient.java` | Match existing method structure and error handling |
| `service/monzo/MonzoTokenRefreshJob.java` | Exact pattern for the scheduled job |
| `api/monzo/MonzoController.java` | Where to publish the event; inline `ConnectionStatus` to remove |
| `api/dev/DevAuthController.java` | Where the retrigger endpoint goes |
| `test/integration/TestDataFactory.java` | Add `createMonzoAccount()` + `createMonzoTransaction()` helpers |
| `test/integration/MonzoOAuthFlowIT.java` | WireMock pattern to follow for IT tests |
