# Phase 4: Transaction Sync

> **Priority:** P2 | **Estimate:** 2–3 days | **Status:** In Progress | **Branch:** `feature/transaction-sync`

## Goal

Fetch and store Monzo transactions locally to enable budgeting features. Two sync modes:
- **Backfill** — full transaction history fetched immediately post-OAuth (within Monzo's 5-minute SCA window)
- **Delta sync** — scheduled cron job every 60 minutes fetching new transactions since the last sync

The Monzo API requires an account ID to list transactions, so accounts are discovered and stored first. All active accounts are synced (personal, joint, etc.).

---

## Scope

- [ ] V7 migration: `monzo_accounts` table
- [ ] V8 migration: `monzo_transactions` table
- [ ] `MonzoAccount` + `MonzoTransaction` domain entities
- [ ] `MonzoAccountRepository` + `MonzoTransactionRepository` (incl. native upsert)
- [ ] `MonzoClient` — add `getAccounts()` and `getTransactions()` methods
- [ ] `AsyncConfig` — `@EnableAsync` + `backfillTaskExecutor` thread pool
- [ ] `MonzoConnectionCreatedEvent` + `TransactionSyncEventListener` (async backfill trigger)
- [ ] `TransactionSyncService` — `backfill()` and `deltaSync()` logic
- [ ] `TransactionSyncJob` — `@Scheduled` delta sync
- [ ] Publish event in `MonzoController.handleCallback()` post-OAuth
- [ ] `GET /api/transactions` endpoint with cursor pagination
- [ ] `ErrorCode` additions: `ACCOUNT_NOT_FOUND`, `MONZO_SYNC_ERROR`
- [ ] Unit + integration tests

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

## New Files

| Path | Purpose |
|------|---------|
| `db/migration/V7__create_monzo_accounts.sql` | Accounts table |
| `db/migration/V8__create_monzo_transactions.sql` | Transactions table |
| `domain/monzo/MonzoAccount.java` | Account entity; natural String PK (`acc_xxx`) |
| `domain/monzo/MonzoTransaction.java` | Transaction entity; natural String PK (`tx_xxx`) |
| `repository/MonzoAccountRepository.java` | Queries: by connection, by user, `findAllSyncable()` |
| `repository/MonzoTransactionRepository.java` | Queries + native `upsert()` for backfill |
| `config/AsyncConfig.java` | `@EnableAsync`, `backfillTaskExecutor` bean, uncaught exception handler |
| `config/TransactionSyncProperties.java` | `@ConfigurationProperties(prefix="monzo.transaction-sync")` |
| `service/monzo/MonzoConnectionCreatedEvent.java` | `record MonzoConnectionCreatedEvent(UUID connectionId, UUID userId)` |
| `service/monzo/TransactionSyncEventListener.java` | `@Async("backfillTaskExecutor") @EventListener` → `syncService.backfill()` |
| `service/monzo/TransactionSyncService.java` | `backfill(UUID connectionId)` + `deltaSync(String accountId)` |
| `service/monzo/TransactionSyncJob.java` | `@Scheduled` cron → `deltaSync` for all syncable accounts |
| `service/transaction/TransactionQueryService.java` | Cursor-paginated query logic |
| `api/transaction/TransactionController.java` | `GET /api/transactions` |
| `api/transaction/dto/TransactionResponse.java` | Single transaction DTO |
| `api/transaction/dto/TransactionPageResponse.java` | Paginated wrapper with `nextCursor` + `hasMore` |

## Modified Files

| Path | Change |
|------|--------|
| `client/monzo/MonzoClient.java` | Add `getAccounts()`, `getTransactions()`, 5 new response records |
| `api/monzo/MonzoController.java` | Inject `ApplicationEventPublisher`; publish `MonzoConnectionCreatedEvent` in `handleCallback()` |
| `api/common/ErrorCode.java` | Add `ACCOUNT_NOT_FOUND` (404), `MONZO_SYNC_ERROR` (502) |
| `application.properties` | Add `monzo.transaction-sync.*` properties |

---

## MonzoClient New Methods

```java
// GET /accounts
List<AccountResponse> getAccounts(String accessToken)

// GET /transactions?account_id=x&expand[]=merchant&limit=x[&since_id=x]
List<TransactionResponse> getTransactions(String accessToken, String accountId,
        @Nullable String sinceId, int limit)
```

New response records (added at end of `MonzoClient.java`):
```java
record AccountResponse(String id, String type, @Nullable String description, String currency, boolean closed) {}
record AccountsResponse(List<AccountResponse> accounts) {}
record MerchantResponse(@Nullable String name, @Nullable String category) {}
record TransactionResponse(String id, int amount, String currency, @Nullable String description,
        @Nullable MerchantResponse merchant, @Nullable String notes, @Nullable String declineReason,
        String created, @Nullable String settled) {}
record TransactionsResponse(List<TransactionResponse> transactions) {}
```

Both methods follow the existing `handleMonzoError()` pattern: 401 → `MONZO_CONNECTION_REVOKED`.

---

## Async Backfill Architecture

Spring Events chosen over direct `@Async` to avoid coupling `TransactionSyncService` into `MonzoController`.

```
MonzoController.handleCallback()
  └─ eventPublisher.publishEvent(new MonzoConnectionCreatedEvent(id, userId))

TransactionSyncEventListener              (@Component)
  └─ @Async("backfillTaskExecutor")
     @EventListener(MonzoConnectionCreatedEvent.class)
     onConnectionCreated() → syncService.backfill(connectionId)
```

`AsyncConfig`:
- `@EnableAsync`
- `backfillTaskExecutor`: `ThreadPoolTaskExecutor` (core=2, max=5, queue=10, prefix=`backfill-`, `CallerRunsPolicy`)
- `AsyncUncaughtExceptionHandler` that logs method name + exception

---

## TransactionSyncService Logic

### `backfill(UUID connectionId)`

```
1. Load MonzoConnection (system-scoped)
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

Same loop but `sinceId = account.getLastTransactionId()`. Uses plain `save()` (new rows dominate in delta). If `lastTransactionId` is null, fetches up to 100 most recent (90-day fallback).

### Upsert (backfill)

Native SQL in `MonzoTransactionRepository`:
```sql
INSERT INTO monzo_transactions (id, account_id, user_id, amount, ...)
VALUES (:id, ...)
ON CONFLICT (id) DO UPDATE SET
    amount = EXCLUDED.amount,
    monzo_settled_at = EXCLUDED.monzo_settled_at,
    notes = EXCLUDED.notes,
    is_declined = EXCLUDED.is_declined,
    updated_at = now()
```

---

## API: `GET /api/transactions`

| Param | Type | Default | Constraint |
|-------|------|---------|------------|
| `limit` | int | 50 | `@Min(1) @Max(100)` |
| `before` | String | null | cursor (Monzo tx ID) |
| `accountId` | String | null | optional account filter |

Response: `ApiResponse<TransactionPageResponse>` with `transactions`, `nextCursor` (id of last item or null), `hasMore`.

Cursor: resolve `before` ID → `monzoCreatedAt` → `WHERE monzo_created_at < :before ORDER BY monzo_created_at DESC`.

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

**Unit tests:** `MonzoClientTest` (getAccounts, getTransactions, 401 handling), `TransactionSyncServiceTest` (backfill pagination, deltaSync cursor), `TransactionSyncJobTest` (failure isolation), `TransactionSyncEventListenerTest`, `TransactionControllerTest` (@WebMvcTest, validation).

**Integration tests:** `TransactionSyncIT` (full backfill via WireMock), `DeltaSyncIT` (cursor advance), `TransactionSyncJobIT` (multi-account), `TransactionApiIT` (user isolation, pagination, filters).

**WireMock stubs needed:** `GET /accounts`, `GET /transactions` (full page of 100, then partial page), `GET /transactions?since_id=x` (delta).

---

## Implementation Order

1. V7/V8 migrations + local verify
2. `MonzoAccount` + `MonzoTransaction` entities
3. `MonzoAccountRepository` + `MonzoTransactionRepository` (incl. native upsert)
4. `ErrorCode` additions
5. `MonzoClient` extensions + unit tests
6. `AsyncConfig` + `TransactionSyncProperties` + properties
7. `TransactionSyncService` + `TransactionSyncServiceTest`
8. `MonzoConnectionCreatedEvent` + `TransactionSyncEventListener`
9. Publish event in `MonzoController` + update `MonzoControllerTest`
10. `TransactionSyncJob` + `TransactionSyncJobTest`
11. API layer + `TransactionControllerTest`
12. Integration tests

---

## Key Files to Read Before Implementing

| File | Why |
|------|-----|
| `client/monzo/MonzoClient.java` | Match existing method structure and error handling |
| `service/monzo/MonzoTokenRefreshJob.java` | Exact pattern for the scheduled job |
| `api/monzo/MonzoController.java` | Where to publish the event |
| `test/integration/TestDataFactory.java` | Add `createMonzoAccount()` + `createMonzoTransaction()` helpers |
| `test/integration/MonzoOAuthFlowIT.java` | WireMock pattern to follow for IT tests |
