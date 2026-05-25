# Monzo Transaction Sync — Manual Testing Guide

> Step-by-step guide for verifying the Phase 4 Transaction Sync feature end-to-end.
> Covers the post-OAuth backfill flow, the dev trigger, delta sync, and idempotency.

---

## 📋 Test Scenarios Checklist

| # | Scenario | Status |
|---|----------|--------|
| 1 | [Prerequisites — app running, OAuth done](#1-prerequisites) | ⏳ |
| 2 | [Inspect baseline DB state](#2-baseline-db-state) | ⏳ |
| 3 | [Trigger full backfill via dev endpoint](#3-trigger-backfill-via-dev-endpoint) | ⏳ |
| 4 | [Verify accounts were created](#4-verify-accounts) | ⏳ |
| 5 | [Verify transactions were created](#5-verify-transactions) | ⏳ |
| 6 | [Verify sync cursor is set](#6-verify-sync-cursor) | ⏳ |
| 7 | [Idempotency — run backfill twice, row count unchanged](#7-idempotency) | ⏳ |
| 8 | [Verify logs show backfill activity](#8-verify-logs) | ⏳ |
| 9 | [Error: trigger without a Monzo connection](#9-error-no-connection) | ⏳ |
| 10 | [Error: trigger unauthenticated](#10-error-unauthenticated) | ⏳ |
| 11 | [Post-OAuth backfill flow (async)](#11-post-oauth-async-backfill) | ⏳ |

---

## 🛠️ 1. Prerequisites

### Start the Application

```bash
# Recommended — starts PostgreSQL and Spring Boot with dev profile
./scripts/dev.sh start

# Or manually
docker compose up -d
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Verify the App is Running

```bash
curl http://localhost:8080/api/health/ready
```

Expected:
```json
{"success":true,"data":{"status":"UP","database":{"status":"UP"}}}
```

### Check the Transaction Sync Tables Exist

```bash
docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer -c "\dt monzo_*"
```

Expected output (all four tables present):
```
              List of relations
 Schema |        Name        | Type  |  Owner
--------+--------------------+-------+----------
 public | monzo_accounts     | table | budgeteer
 public | monzo_connections  | table | budgeteer
 public | monzo_transactions | table | budgeteer
```

If `monzo_accounts` or `monzo_transactions` are missing, Flyway migrations V7/V8 didn't run — check app startup logs.

### Ensure Monzo OAuth is Complete

```bash
curl -s http://localhost:8080/api/monzo/status \
  -H "Authorization: Bearer YOUR_TOKEN" | jq .
```

Expected:
```json
{"success":true,"data":{"connected":true,"connectionCount":1,"tokenStatus":"VALID"}}
```

If `connected: false` — complete the OAuth flow first:
1. `POST /api/monzo/connect` → get auth URL
2. Open URL in browser → approve in Monzo app
3. Monzo redirects back → connection created

### Import Postman Collection

1. Open Postman
2. Import `scripts/postman/budgeteer-transaction-sync.postman_collection.json`
3. Import `scripts/postman/budgeteer-local.postman_environment.json` (if not already imported)
4. Select **Budgeteer Local** environment
5. Run **0. Setup → ⚡ Quick Login** to authenticate

✅ **Scenario 1 Complete**

---

## 🗃️ 2. Baseline DB State

Connect to the database (keep this terminal open throughout testing):

```bash
docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer
```

Run this to see counts before sync:

```sql
SELECT
    'monzo_accounts'     AS table_name, COUNT(*) AS row_count FROM monzo_accounts
UNION ALL
SELECT
    'monzo_transactions' AS table_name, COUNT(*) AS row_count FROM monzo_transactions;
```

Expected before any sync:
```
     table_name      | row_count
---------------------+-----------
 monzo_accounts      |         0
 monzo_transactions  |         0
```

📝 Note down these numbers — you'll compare against them after sync.

✅ **Scenario 2 Complete**

---

## 🔄 3. Trigger Backfill via Dev Endpoint

### Why This Works Without Re-doing OAuth

When you completed Monzo OAuth, the app stored your access and refresh tokens **encrypted** in the `monzo_connections` table. The `MonzoTokenRefreshJob` keeps these tokens alive (refreshes before expiry). So at any point you can call backfill as long as:

- A row exists in `monzo_connections` with `disconnected_at IS NULL`
- The token is still valid (or can be refreshed)

The dev trigger endpoint (`POST /api/dev/monzo/backfill`) simply:
1. Finds your active connection by `userId`
2. Calls `transactionSyncService.backfill(connectionId)` directly
3. Returns 200 when backfill completes

No new OAuth needed. The tokens are already there.

### Using Postman

Run **2. Trigger Backfill → 🔄 Trigger Full Backfill (Dev Only)**

Expected:
```json
{"success":true,"data":null}
```

Response time will reflect however many transactions you have (typically 2–20 seconds for a real account with years of history).

### Using curl

```bash
curl -s -X POST http://localhost:8080/api/dev/monzo/backfill \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" | jq .
```

### Watching Logs While It Runs

In the Spring Boot console, you should see:

```
INFO  TransactionSyncService - Starting backfill [connectionId=...]
INFO  TransactionSyncService - Found 2 account(s) for connection ...
INFO  TransactionSyncService - Syncing account acc_xxxxxxx (type=uk_retail)
INFO  TransactionSyncService - Page 1: 100 transactions [accountId=acc_xxxxxxx]
INFO  TransactionSyncService - Page 2: 47 transactions [accountId=acc_xxxxxxx]
INFO  TransactionSyncService - Sync complete [accountId=acc_xxxxxxx, transactions=147, lastTxId=tx_yyy]
INFO  TransactionSyncService - Backfill complete [connectionId=..., accounts=2]
```

If a closed account is skipped:
```
INFO  TransactionSyncService - Skipping closed account acc_zzz
```

✅ **Scenario 3 Complete**

---

## ✅ 4. Verify Accounts

After backfill, check `monzo_accounts`:

```sql
SELECT
    id,
    account_type,
    currency,
    description,
    closed,
    last_synced_at,
    last_transaction_id
FROM monzo_accounts
ORDER BY created_at;
```

**What to look for:**

| Column | Expected |
|--------|----------|
| `id` | Real Monzo account ID (e.g. `acc_0000xxxxxxxx`) |
| `account_type` | `uk_retail`, `uk_retail_joint`, or `uk_prepaid` |
| `currency` | `GBP` |
| `closed` | `false` for active accounts; `true` for old accounts |
| `last_synced_at` | Timestamp from this sync (not null) |
| `last_transaction_id` | Latest transaction ID — used as cursor for delta sync |

**Verify connection FK is correct:**

```sql
SELECT
    a.id          AS account_id,
    a.account_type,
    c.monzo_user_id,
    u.email
FROM monzo_accounts a
JOIN monzo_connections c ON a.connection_id = c.id
JOIN users u ON a.user_id = u.id;
```

✅ **Scenario 4 Complete**

---

## ✅ 5. Verify Transactions

```sql
SELECT
    id,
    account_id,
    amount,
    currency,
    description,
    merchant_name,
    merchant_category,
    is_declined,
    monzo_created_at,
    monzo_settled_at
FROM monzo_transactions
ORDER BY monzo_created_at DESC
LIMIT 10;
```

**What to look for:**

| Column | Expected |
|--------|----------|
| `id` | Real Monzo transaction ID (e.g. `tx_0000xxxxxxxx`) |
| `amount` | Integer pence, negative = debit (e.g. `-500` = £5.00 debit) |
| `currency` | `GBP` |
| `description` | Raw Monzo description |
| `merchant_name` | Populated for card payments (e.g. `Starbucks`); null for bank transfers |
| `merchant_category` | Monzo category slug (e.g. `eating_out`, `transport`) |
| `is_declined` | `false` for successful; `true` for declined transactions |
| `monzo_created_at` | When Monzo processed it |
| `monzo_settled_at` | Null if pending; timestamp when settled |

**Count by account:**

```sql
SELECT
    a.id          AS account_id,
    a.account_type,
    COUNT(t.id)   AS transaction_count,
    MIN(t.monzo_created_at) AS oldest_transaction,
    MAX(t.monzo_created_at) AS newest_transaction
FROM monzo_accounts a
LEFT JOIN monzo_transactions t ON t.account_id = a.id
GROUP BY a.id, a.account_type
ORDER BY a.account_type;
```

**Verify a pending transaction (if you have any):**

```sql
SELECT id, description, amount, monzo_created_at, monzo_settled_at
FROM monzo_transactions
WHERE monzo_settled_at IS NULL
LIMIT 5;
```

✅ **Scenario 5 Complete**

---

## ✅ 6. Verify Sync Cursor

The `last_transaction_id` column on `monzo_accounts` is the delta sync cursor — it tells the next sync where to start from.

```sql
SELECT
    id,
    last_transaction_id,
    last_synced_at
FROM monzo_accounts
WHERE closed = false;
```

**Expected:**
- `last_transaction_id`: the ID of the most recent transaction fetched (`tx_0000...`)
- `last_synced_at`: timestamp of when this sync completed

**Confirm the cursor points to the latest transaction:**

```sql
SELECT
    a.id              AS account_id,
    a.last_transaction_id,
    t.monzo_created_at AS cursor_tx_created_at,
    t.description     AS cursor_tx_description
FROM monzo_accounts a
JOIN monzo_transactions t ON t.id = a.last_transaction_id
WHERE a.closed = false;
```

The cursor transaction should be the **most recent** transaction for that account.

✅ **Scenario 6 Complete**

---

## 🔁 7. Idempotency

Re-trigger backfill without resetting the database:

```bash
curl -s -X POST http://localhost:8080/api/dev/monzo/backfill \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" | jq .
```

Or use Postman: **4. Idempotency → 🔄 Re-trigger Backfill**

Now re-run the count query:

```sql
SELECT
    'monzo_accounts'     AS table_name, COUNT(*) AS row_count FROM monzo_accounts
UNION ALL
SELECT
    'monzo_transactions' AS table_name, COUNT(*) AS row_count FROM monzo_transactions;
```

**Expected:** Row counts are **identical** to after the first run. No duplicates.

**Verify upsert updated mutable fields only:**

```sql
SELECT
    id,
    amount,
    monzo_settled_at,
    updated_at,
    created_at
FROM monzo_transactions
ORDER BY updated_at DESC
LIMIT 5;
```

For transactions that were already settled: `updated_at` will reflect the second run, but the data itself is unchanged. For any pending transactions that have since settled, `monzo_settled_at` will now be populated.

✅ **Scenario 7 Complete**

---

## 📋 8. Verify Logs

The sync emits structured log lines. Search the Spring Boot console for:

```
# Backfill started
INFO  TransactionSyncService - Starting backfill [connectionId=...]

# Each account processed
INFO  TransactionSyncService - Syncing account acc_xxx (type=uk_retail)

# Pagination — each page
INFO  TransactionSyncService - Page 1: 100 transactions [accountId=acc_xxx]
INFO  TransactionSyncService - Page 2: 5 transactions [accountId=acc_xxx]

# Sync complete for account (cursor updated)
INFO  TransactionSyncService - Sync complete [accountId=acc_xxx, transactions=105, lastTxId=tx_yyy]

# Closed account skip
INFO  TransactionSyncService - Skipping closed account acc_zzz

# Full backfill done
INFO  TransactionSyncService - Backfill complete [connectionId=..., accounts=2]
```

**For the async post-OAuth path** (normal flow, not the dev trigger), you'll see the backfill log lines on a `backfill-N` thread:

```
INFO  [backfill-1] TransactionSyncService - Starting backfill ...
```

**Token usage:** The sync calls `GET /accounts` and `GET /transactions` using the stored encrypted token. Watch for token refresh activity if the token was near expiry:

```
INFO  MonzoTokenRefreshService - Refreshing token for connection ...
INFO  MonzoTokenRefreshService - Token refreshed successfully [connectionId=...]
```

✅ **Scenario 8 Complete**

---

## ❌ 9. Error: Trigger Without a Monzo Connection

Quick-login as a user who has never completed OAuth:

```bash
curl -s -X POST http://localhost:8080/api/dev/auth/quick-login \
  -H "Content-Type: application/json" \
  -d '{"email": "no-monzo@example.com"}' \
  -c /tmp/no-monzo-cookies.txt | jq .data.accessToken
```

Now trigger sync as that user (no Monzo connection):

```bash
curl -s -X POST http://localhost:8080/api/dev/monzo/backfill \
  -H "Authorization: Bearer NO_MONZO_USER_TOKEN" | jq .
```

**Expected:**
```json
{
  "success": false,
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "No active Monzo connection found for user ..."
  }
}
```

HTTP status: `404`

✅ **Scenario 9 Complete**

---

## ❌ 10. Error: Trigger Unauthenticated

```bash
curl -s -X POST http://localhost:8080/api/dev/monzo/backfill | jq .
```

**Expected:**
```json
{
  "success": false,
  "error": {
    "code": "NOT_AUTHENTICATED",
    "message": "Authentication required"
  }
}
```

HTTP status: `401`

✅ **Scenario 10 Complete**

---

## 🔗 11. Post-OAuth Async Backfill Flow

This scenario verifies the **normal** production flow (not the dev trigger) — where backfill fires automatically when OAuth completes.

### What Happens

```
Browser → POST /api/monzo/connect → get auth URL
Browser → open URL → approve in Monzo app
Monzo   → GET /api/monzo/callback?code=...
Server  → exchange code for tokens → create MonzoConnection
Server  → publish MonzoConnectionCreatedEvent (async)
                      └─ backfill-1 thread picks it up
                      └─ TransactionSyncEventListener.onConnectionCreated()
                      └─ syncService.backfill(connectionId)
HTTP response returns immediately (connection created)
Backfill runs in background on backfill-1 thread
```

### How to Test

1. **Delete all sync data and the connection:**

```sql
TRUNCATE monzo_transactions, monzo_accounts, monzo_connections CASCADE;
```

2. **Initiate OAuth:**

```bash
curl -s -X POST http://localhost:8080/api/monzo/connect \
  -H "Authorization: Bearer YOUR_TOKEN" | jq .data.authorizationUrl
```

3. **Open the auth URL in a browser and approve in the Monzo app.**

4. **Watch the logs immediately after approval:**

The OAuth callback returns almost instantly. Within 1–2 seconds you should see:

```
INFO  [http-nio-...] MonzoController       - Monzo OAuth callback received
INFO  [http-nio-...] MonzoController       - Connection created [userId=...]
INFO  [backfill-1]   TransactionSyncEventListener - Backfill triggered [connectionId=...]
INFO  [backfill-1]   TransactionSyncService - Starting backfill [connectionId=...]
```

Note that `http-nio-...` thread returned the HTTP response while `backfill-1` is still running in the background.

5. **Verify DB after backfill finishes:**

```sql
SELECT COUNT(*) FROM monzo_accounts;
SELECT COUNT(*) FROM monzo_transactions;
```

### The 5-Minute SCA Window

Monzo only grants access to full transaction history within ~5 minutes of OAuth approval (the SCA window). After that, only the last 90 days are accessible. This is why backfill must be triggered immediately and asynchronously — the HTTP response can't wait for potentially thousands of transactions to sync.

The `@Async("backfillTaskExecutor")` annotation ensures the backfill runs on a separate thread pool (`backfill-1`, `backfill-2`, etc.) so the OAuth response returns in milliseconds.

✅ **Scenario 11 Complete**

---

## 🗃️ Full DB Verification Queries

```bash
docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer
```

### All Transaction Sync Data at a Glance

```sql
SELECT
    u.email,
    c.id                     AS connection_id,
    c.monzo_user_id,
    c.token_expires_at,
    COUNT(DISTINCT a.id)     AS accounts,
    COUNT(t.id)              AS transactions
FROM users u
JOIN monzo_connections c ON c.user_id = u.id
LEFT JOIN monzo_accounts a ON a.connection_id = c.id
LEFT JOIN monzo_transactions t ON t.account_id = a.id
WHERE c.disconnected_at IS NULL
GROUP BY u.email, c.id, c.monzo_user_id, c.token_expires_at;
```

### Account Sync State

```sql
SELECT
    id,
    account_type,
    currency,
    description,
    closed,
    last_transaction_id,
    last_synced_at,
    CASE
        WHEN closed THEN '🔒 CLOSED'
        WHEN last_synced_at IS NULL THEN '⏳ NEVER SYNCED'
        ELSE '✅ SYNCED'
    END AS sync_status
FROM monzo_accounts
ORDER BY account_type;
```

### Recent Transactions (Most Recent 20)

```sql
SELECT
    t.id,
    t.account_id,
    t.amount                                AS amount_pence,
    ROUND(t.amount::numeric / 100, 2)       AS amount_gbp,
    t.description,
    t.merchant_name,
    t.merchant_category,
    t.is_declined,
    t.monzo_created_at,
    CASE WHEN t.monzo_settled_at IS NULL THEN '⏳ PENDING' ELSE '✅ SETTLED' END AS status
FROM monzo_transactions t
ORDER BY t.monzo_created_at DESC
LIMIT 20;
```

### Pending Transactions

```sql
SELECT id, account_id, amount, description, monzo_created_at
FROM monzo_transactions
WHERE monzo_settled_at IS NULL
ORDER BY monzo_created_at DESC;
```

### Category Breakdown

```sql
SELECT
    COALESCE(merchant_category, 'transfer/other') AS category,
    COUNT(*)                                       AS count,
    SUM(amount)                                    AS total_pence,
    ROUND(SUM(amount)::numeric / 100, 2)           AS total_gbp
FROM monzo_transactions
WHERE amount < 0
GROUP BY COALESCE(merchant_category, 'transfer/other')
ORDER BY total_pence;
```

### Verify Encryption (Tokens Should Not Be Readable)

```sql
SELECT
    id,
    LEFT(access_token_enc, 40) || '...'  AS token_preview,
    CASE
        WHEN access_token_enc LIKE 'eyJ%'  THEN '⚠️  PLAINTEXT JWT!'
        WHEN access_token_enc LIKE '%{%'   THEN '⚠️  MIGHT BE PLAINTEXT!'
        ELSE '✅ Encrypted'
    END AS encryption_check
FROM monzo_connections
WHERE disconnected_at IS NULL;
```

### Verify FK Integrity

```sql
SELECT
    t.id       AS transaction_id,
    t.account_id,
    t.user_id,
    a.id       AS account_exists,
    u.email    AS user_email
FROM monzo_transactions t
LEFT JOIN monzo_accounts a ON t.account_id = a.id
LEFT JOIN users u ON t.user_id = u.id
WHERE a.id IS NULL OR u.id IS NULL
LIMIT 5;
-- Expected: 0 rows (all FKs valid)
```

---

## 🧹 Reset for Fresh Testing

To clear all sync data and re-test from scratch (without losing your Monzo connection):

```sql
-- Clear only sync data — connection and tokens remain
TRUNCATE monzo_transactions, monzo_accounts CASCADE;
```

To clear everything including the connection (requires full re-OAuth):

```sql
TRUNCATE monzo_transactions, monzo_accounts, monzo_connections CASCADE;
```

After clearing, use the dev trigger to re-populate without re-doing OAuth (as long as the connection row still exists):

```bash
curl -s -X POST http://localhost:8080/api/dev/monzo/backfill \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" | jq .
```

---

## ✅ Final Checklist

- [ ] Scenario 1: App running, Flyway tables exist, OAuth complete
- [ ] Scenario 2: Baseline DB shows 0 accounts, 0 transactions
- [ ] Scenario 3: Dev trigger returns 200 `{"success":true,"data":null}`
- [ ] Scenario 4: `monzo_accounts` has real account rows with Monzo IDs
- [ ] Scenario 5: `monzo_transactions` has real transactions (pence amounts, categories)
- [ ] Scenario 6: `last_transaction_id` cursor set on each open account
- [ ] Scenario 7: Second backfill produces identical row count (idempotent)
- [ ] Scenario 8: Logs show correct thread names and backfill activity
- [ ] Scenario 9: No-connection user gets 404
- [ ] Scenario 10: Unauthenticated request gets 401
- [ ] Scenario 11: Post-OAuth async backfill fires on `backfill-N` thread, HTTP response returns immediately

---

## 📁 Related Files

| Resource | Location |
|----------|----------|
| Postman Collection | `scripts/postman/budgeteer-transaction-sync.postman_collection.json` |
| Postman Environment | `scripts/postman/budgeteer-local.postman_environment.json` |
| Feature Documentation | `docs/features/MONZO-TRANSACTION-SYNC.md` |
| Transaction Sync Service | `backend/.../service/monzo/TransactionSyncService.java` |
| Dev Trigger Endpoint | `backend/.../api/dev/DevAuthController.java` |
| Integration Tests | `backend/.../integration/TransactionSyncIT.java` |

---

*Last updated: 2026-05-25 — Phase 4 Transaction Sync complete*
