# Monzo Transaction Sync — Manual Testing Guide

> End-to-end verification of the transaction sync feature: initial OAuth backfill, windowed historical sync, SCA expiry + resume, delta sync, idempotency, and the dev endpoints.

This is the single source of truth for testing transaction sync. The feature has several mechanics that interact, so the scenarios are grouped:

- **Path A — New-user first OAuth** (the canonical clean-slate flow)
- **Path B — Windowed historical sync with SCA expiry + resume**
- **Path C — Operational concerns** (delta sync, idempotency, error cases)

---

## 📋 Test Scenarios Checklist

| # | Scenario | Status |
|---|----------|--------|
| **Path A — New user first OAuth** | | |
| 1 | [Prerequisites — env, DB persistence, healthcheck](#1-prerequisites) | ⏳ |
| 2 | [Clean slate — wipe DB, create fresh user](#2-clean-slate) | ⏳ |
| 3 | [First OAuth — initiate and approve](#3-first-oauth) | ⏳ |
| 4 | [Verify backfill triggered + accounts created](#4-verify-backfill-triggered) | ⏳ |
| 5 | [Verify transactions persisted with `monzo_created_at`](#5-verify-transactions) | ⏳ |
| 6 | [Verify status endpoint shape](#6-verify-status-endpoint) | ⏳ |
| **Path B — Windowed sync + SCA resume** | | |
| 7 | [Backfill walks ≤350-day windows back to account creation](#7-windowed-backfill) | ⏳ |
| 8 | [SCA expiry → NEEDS_REAUTH checkpoint persisted](#8-sca-expiry-checkpoint) | ⏳ |
| 9 | [App restart — checkpoint survives](#9-restart-survival) | ⏳ |
| 10 | [Re-OAuth resumes from saved checkpoint](#10-re-oauth-resume) | ⏳ |
| 11 | [Reaching the floor marks COMPLETED](#11-backfill-completed) | ⏳ |
| **Path C — Operational** | | |
| 12 | [Idempotency — re-run backfill, no duplicates](#12-idempotency) | ⏳ |
| 13 | [Delta sync uses `last_transaction_id` cursor](#13-delta-sync) | ⏳ |
| 14 | [Dev endpoints — manual backfill + reset](#14-dev-endpoints) | ⏳ |
| 15 | [Error cases — no connection, unauthenticated, wrong user](#15-error-cases) | ⏳ |

---

# Path A — New User First OAuth

## 🛠️ 1. Prerequisites

### Disable DB clean-on-startup

The dev profile has `app.database.clean-on-startup=true` (`application-dev.properties:48`), which Flyway-cleans on every restart. **The resume scenarios in Path B require state to survive an app restart** — disable it for the full session:

```bash
export APP_DATABASE_CLEAN_ON_STARTUP=false
```

You can leave this off all the time during dev — it just means you control wipes manually via SQL.

### Start the app

```bash
cd /Users/alexanderfisher/development/budgeteer
docker compose up -d              # postgres
cd backend && mvn spring-boot:run
```

### Healthcheck

```bash
curl -s http://localhost:8080/api/health/ready | jq .
```

Expect:
```json
{ "success": true, "data": { "status": "UP", "database": { "status": "UP" } } }
```

### Confirm all Flyway migrations ran

```bash
docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;"
```

Expect to see rows for:
- `V10 | add monzo account backfill state | t`
- `V9 | add monzo account created | t`
- `V8 | create monzo transactions | t`
- `V7 | create monzo accounts | t`

### Confirm the schema is current

```sql
\d monzo_accounts
```

Expect columns including: `monzo_created_at`, `backfill_status`, `backfill_progress_at`, `backfill_progress_cursor`.

✅ **Scenario 1 Complete**

---

## 🧹 2. Clean Slate

This is the "new user signing up for the first time" path. We wipe **everything** Monzo-related and create a brand-new user.

### Wipe all Monzo data

```sql
TRUNCATE monzo_transactions, monzo_accounts, monzo_connections, oauth_states CASCADE;
```

### Create a fresh test user

The dev profile exposes `POST /api/dev/auth/quick-login` which creates the user if it doesn't exist and returns tokens directly (no magic link needed):

```bash
curl -s -X POST http://localhost:8080/api/dev/auth/quick-login \
  -H "Content-Type: application/json" \
  -d '{"email": "monzo-test@example.com"}' \
  -c /tmp/budgeteer-cookies.txt | jq .
```

Save the access token:

```bash
export ACCESS_TOKEN=$(curl -s -X POST http://localhost:8080/api/dev/auth/quick-login \
  -H "Content-Type: application/json" \
  -d '{"email": "monzo-test@example.com"}' | jq -r .data.accessToken)
echo "Token: $ACCESS_TOKEN"
```

### Baseline DB state

```sql
SELECT 'monzo_connections' AS table_name, COUNT(*) FROM monzo_connections
UNION ALL
SELECT 'monzo_accounts',     COUNT(*) FROM monzo_accounts
UNION ALL
SELECT 'monzo_transactions', COUNT(*) FROM monzo_transactions;
```

Expect zeros across the board.

### Verify status endpoint — pre-OAuth

```bash
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/monzo/status | jq .
```

Expect:
```json
{
  "success": true,
  "data": {
    "connected": false,
    "connectionCount": 0,
    "tokenStatus": "RECONNECT_REQUIRED",
    "backfillStatus": "NOT_STARTED"
  }
}
```

This is what a brand-new user sees: not connected, no backfill yet.

✅ **Scenario 2 Complete**

---

## 🔓 3. First OAuth

In your browser (logged in as the same user — the access token cookie is set):

```
http://localhost:8080/api/monzo/connect
```

This redirects to Monzo. Approve in the Monzo app on your phone **within 5 minutes**.

> 📱 **SCA Window:** Monzo only grants access to full transaction history within ~5 minutes of OAuth approval. After that, only the last 90 days are accessible. That's why backfill must run immediately, asynchronously, on the OAuth callback.

After approval, the browser is redirected back to `/api/monzo/callback?code=...`, and the server:
1. Exchanges the code for tokens (logged at INFO)
2. Encrypts the tokens and stores them in `monzo_connections`
3. Calls `syncService.backfillAsync(connection.getId())` (returns immediately — backfill runs on the `taskExecutor-N` thread)
4. Returns the connection response to the browser

✅ **Scenario 3 Complete**

---

## 🔍 4. Verify Backfill Triggered

### Watch the logs

Within ~2 seconds of OAuth approval, expect:

```
INFO  ... Successfully connected Monzo account for user ... [connectionId=...]
INFO  ... Triggering async transaction backfill within SCA window [connectionId=...]
INFO  [taskExecutor-N] ... Backfill attempt 1/8 [connectionId=...]
INFO  [taskExecutor-N] ... Starting backfill [connectionId=..., userId=...]
DEBUG [taskExecutor-N] ... Fetching Monzo accounts
DEBUG [taskExecutor-N] ... Fetched N Monzo accounts
DEBUG [taskExecutor-N] ... Fetching Monzo transactions [accountId=..., since=..., before=..., sinceId=null, limit=100]
```

Note the HTTP response to the browser returns first (within milliseconds), while the backfill runs in the background on the async thread.

### Inspect accounts

```sql
SELECT id,
       account_type,
       currency,
       description,
       closed,
       monzo_created_at,
       backfill_status
FROM monzo_accounts;
```

| Column | Expected |
|--------|----------|
| `id` | Real Monzo account ID (e.g. `acc_0000xxxxxxxx`) |
| `account_type` | `uk_retail`, `uk_retail_joint`, or `uk_prepaid` |
| `currency` | `GBP` |
| `closed` | `false` for active accounts; `true` for old ones |
| `monzo_created_at` | When the Monzo account was opened (used as backfill floor) |
| `backfill_status` | `IN_PROGRESS` (during) → `COMPLETED` or `NEEDS_REAUTH` (after) |

### Verify connection + FK integrity

```sql
SELECT
    u.email,
    c.id AS connection_id,
    c.monzo_user_id,
    c.token_expires_at,
    COUNT(a.id) AS accounts
FROM users u
JOIN monzo_connections c ON c.user_id = u.id
LEFT JOIN monzo_accounts a ON a.connection_id = c.id
WHERE c.disconnected_at IS NULL
GROUP BY u.email, c.id, c.monzo_user_id, c.token_expires_at;
```

✅ **Scenario 4 Complete**

---

## 💳 5. Verify Transactions

### Watch the transaction count grow

In a separate psql session (refresh every few seconds while backfill runs):

```sql
SELECT COUNT(*) AS total,
       MIN(monzo_created_at) AS oldest_tx,
       MAX(monzo_created_at) AS newest_tx
FROM monzo_transactions;
```

`total` grows as pages are fetched; `oldest_tx` decreases as the backfill walks back through windows.

### Sample of recent transactions

```sql
SELECT id,
       account_id,
       amount,
       ROUND(amount::numeric / 100, 2) AS amount_gbp,
       currency,
       description,
       merchant_name,
       merchant_category,
       is_declined,
       monzo_created_at,
       CASE WHEN monzo_settled_at IS NULL THEN '⏳ pending' ELSE '✅ settled' END AS status
FROM monzo_transactions
ORDER BY monzo_created_at DESC
LIMIT 10;
```

| Column | Expected |
|--------|----------|
| `id` | Real tx ID (e.g. `tx_0000xxxxxxxx`) |
| `amount` | Integer pence, negative = debit |
| `merchant_name` | Populated for card payments; null for transfers |
| `merchant_category` | Monzo category slug (e.g. `groceries`, `transport`) |
| `monzo_created_at` | When Monzo recorded the tx (NOT our local insert time) |
| `monzo_settled_at` | Null = pending; otherwise the settled timestamp |

### Per-month breakdown — see the historical reach

```sql
SELECT date_trunc('month', monzo_created_at)::date AS month, COUNT(*)
FROM monzo_transactions
GROUP BY month
ORDER BY month DESC;
```

You should see **continuous coverage** from now back to whatever point the SCA window allowed (or back to `monzo_created_at` if fully completed). **No gaps within fetched windows** — the cursor-per-page persistence fixes that.

✅ **Scenario 5 Complete**

---

## 📡 6. Verify Status Endpoint

```bash
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/monzo/status | jq .
```

Expect (during backfill):
```json
{
  "data": {
    "connected": true,
    "connectionCount": 1,
    "tokenStatus": "ACTIVE",
    "backfillStatus": "IN_PROGRESS"
  }
}
```

| Field | Possible values |
|-------|-----------------|
| `tokenStatus` | `ACTIVE` / `EXPIRING_SOON` / `RECONNECT_REQUIRED` |
| `backfillStatus` | `NOT_STARTED` / `IN_PROGRESS` / `NEEDS_REAUTH` / `COMPLETED` |

Re-run the curl a few seconds later — `backfillStatus` should transition to one of `COMPLETED` (if backfill finished before SCA expired) or `NEEDS_REAUTH` (if SCA expired mid-backfill).

✅ **Scenario 6 Complete**

---

# Path B — Windowed Sync + SCA Resume

> ### 📌 Testing Path B when your whole history downloads inside the SCA window
>
> If your first OAuth ended in `Backfill complete … COMPLETED` (common for **low-volume** accounts — even ones several years old; account *age* doesn't decide this, transaction **volume + API latency** does), you can't trigger SCA expiry naturally. Use one of these to exercise §8–§11:
>
> **Reliable — kill the server mid-backfill (recommended):**
> 1. Do a fresh OAuth and, ~5–10s into the backfill, run **`./scripts/dev.sh stop` from a second terminal** (NOT `Ctrl-C`/`Ctrl-Z` — see the warning in §9; those leave a frozen JVM holding row locks). `dev.sh stop` kills the port-8080 JVM; the `backfillTaskExecutor` doesn't wait for tasks on shutdown and each window commits independently, so completed windows persist while the in-flight one rolls back. Result: a few windows of data, `backfill_status='IN_PROGRESS'`, `backfill_progress_at` at the last completed window.
> 2. Restart the app — state survives (§9). A restart does **not** auto-resume; nothing fetches again until you re-OAuth or hit the dev endpoint.
> 3. Wait until **>5 minutes since the original approval** so the SCA window has closed.
> 4. `POST /api/dev/monzo/backfill` → resumes from the checkpoint, but Monzo now 403s on the older (>90-day) windows → `backfill_status='NEEDS_REAUTH'` (§8). This is the exact path the cursor-loop fix touched — confirm there's no retry storm or infinite loop.
> 5. Re-OAuth (opens a fresh SCA window) → async backfill resumes from the checkpoint → walks the remaining windows → `COMPLETED` (§10, §11).
>
> **Also works — natural 403 via delayed dev-trigger (skips the partial-data step):** after a COMPLETED backfill, `reset-backfill/{accountId}`, wait >5 min since approval, then `POST /api/dev/monzo/backfill`. SCA is closed → 403 on old windows → `NEEDS_REAUTH`. Then re-OAuth → `COMPLETED`.
>
> **Unreliable — delaying your Monzo approval:** the full-access window runs ~5 min from **approval**, not from when you start OAuth, and a ~20s backfill finishes inside even a 1-minute remainder. Don't rely on this to force a partial.

## 📦 7. Windowed Backfill

The backfill doesn't make a single huge API call — it walks **≤350-day windows** backwards from now to the account creation date. Monzo enforces a 365-day max time range per request, so chunking is mandatory.

Watch the logs while backfill runs. Each window looks like:

```
DEBUG ... Fetching Monzo transactions [accountId=..., since=<windowStart>, before=<windowEnd>, sinceId=null, limit=100]
DEBUG ... Fetched 100 transactions for account ...
DEBUG ... Fetching Monzo transactions [accountId=..., since=null, before=<windowEnd>, sinceId=<tx_xxx>, limit=100]
... (cursor-paginates until <100 returned)
DEBUG ... Fetching Monzo transactions [accountId=..., since=<earlierWindowStart>, before=<earlierWindowEnd>, sinceId=null, ...]
```

Key observations (the DEBUG line logs the internal **method args** — on the wire the cursor is sent as Monzo's `since` param; Monzo has **no** `since_id` param):
- A start-of-window request has a timestamp `since` AND `before` set, with `sinceId=null`
- Subsequent pages within the window log `sinceId=<cursor>` and `since=null` — on the wire that cursor goes out as `since=<tx_id>`, with `before` still pinned to the window end (this is the cursor-pagination path that the infinite-loop fix corrected)
- New windows start with `sinceId=null` again

### How many windows to expect

```sql
SELECT id, monzo_created_at, backfill_progress_at, backfill_status FROM monzo_accounts;
```

If `monzo_created_at` is 4 years ago, you'd see roughly `ceil(4 * 365 / 350) ≈ 5` windows. Each one is fetched until it returns <100 transactions (or hits SCA expiry).

✅ **Scenario 7 Complete**

---

## ⏸️ 8. SCA Expiry Checkpoint

If your backfill can't finish inside the ~5-minute SCA window (high transaction volume — **not** simply age; a multi-year low-volume account can finish in time), or you induced this via the Path B callout above, Monzo starts returning 403 on windows older than ~90 days. Watch for:

```
WARN  ... Monzo API returned 403 verification_required for transactions - SCA window has expired
INFO  ... Backfill paused — SCA verification required [accountId=..., progressAt=..., cursor=...]
```

> **Important:** the previous "8 retries with 2s delay storm" should NOT happen. `MONZO_VERIFICATION_REQUIRED` falls outside the `MONZO_API_ERROR` retry guard, and `backfillAccount` catches it cleanly on the first occurrence.

### Inspect the checkpoint

```sql
SELECT id,
       backfill_status,
       backfill_progress_at,
       backfill_progress_cursor,
       last_transaction_id,
       monzo_created_at,
       (SELECT COUNT(*) FROM monzo_transactions WHERE account_id = monzo_accounts.id) AS txs,
       (SELECT MIN(monzo_created_at) FROM monzo_transactions WHERE account_id = monzo_accounts.id) AS oldest_tx
FROM monzo_accounts;
```

Expect:
- `backfill_status` = `NEEDS_REAUTH`
- `backfill_progress_at` is non-null — the **windowEnd of the next window to resume from**. Should equal the `oldest_tx` (give or take a tx) you've fetched so far.
- `backfill_progress_cursor` is **null** — each window runs in a single committed transaction, so an interrupted window (403 or kill) rolls back wholesale and the durable checkpoint always lands on a window boundary.
- `last_transaction_id` is **set** — the newest tx ID from the first (most recent) window. This is the delta-sync cursor. Saved as soon as window 1 completed on the fresh attempt.
- `txs` > 0 — the transactions fetched up to the SCA expiry are persisted.

### Status endpoint reflects it

```bash
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/monzo/status | jq .data.backfillStatus
# "NEEDS_REAUTH"
```

✅ **Scenario 8 Complete**

---

## 🔄 9. Restart Survival

The whole point of persisting the checkpoint to the DB is that it survives a process restart. Verify:

```bash
# From a SECOND terminal — stops the JVM on port 8080 cleanly:
./scripts/dev.sh stop
# then start it again (foreground, in your run terminal):
./scripts/dev.sh start
```

> ⚠️ **Never `Ctrl-C` or `Ctrl-Z` the run terminal.** `mvn spring-boot:run` *forks* the app JVM, so `Ctrl-C` hits Maven and the forked app keeps running; `Ctrl-Z` *suspends* it mid-transaction, leaving a frozen Postgres transaction holding row locks on `monzo_transactions`/`monzo_accounts`. A later backfill then blocks on those locks and appears to "hang". Always stop via `./scripts/dev.sh stop` (it kills by port). If you ever do hang: `ps axo pid,stat,command | grep '[b]udgeteer'` and `kill -9` any `T`-state (suspended) PIDs, then terminate idle-in-transaction backends: `SELECT pid, pg_terminate_backend(pid) FROM pg_stat_activity WHERE state='idle in transaction';`

While the app is restarting:
- Confirm `APP_DATABASE_CLEAN_ON_STARTUP=false` is still exported (else Flyway will wipe everything on startup).
- Look for `INFO o.f.core.internal.command.DbMigrate : Current version of schema "public": 10` (no clean log line).

Once the app is back up, re-run the SQL from §8. **Identical state.**

✅ **Scenario 9 Complete**

---

## 🔓 10. Re-OAuth Resume

The cleanest case: user sees "Reconnect to import older history" CTA, clicks it, and backfill resumes from where it stopped.

### Initiate fresh OAuth

```
http://localhost:8080/api/monzo/connect
```

Approve in the Monzo app within 5 min.

### Watch the logs

Expect the first transaction request to use the saved checkpoint, **not** start from `now()`:

```
DEBUG ... Fetching Monzo transactions [accountId=..., since=<...>, before=<backfill_progress_at value>, sinceId=<saved cursor or null>, limit=100]
```

- The `before` parameter should match the `backfill_progress_at` you saw in §8.
- Resume is **per-window** (`backfill_progress_cursor` is null in committed state), so the first resumed request uses `since=<progressAt - 350d>`, `before=<progressAt>`, `sinceId=null`.

### Track progress in psql (while backfill runs)

```sql
SELECT backfill_status,
       backfill_progress_at,
       (SELECT COUNT(*) FROM monzo_transactions) AS txs,
       (SELECT MIN(monzo_created_at) FROM monzo_transactions) AS oldest_tx
FROM monzo_accounts;
```

`backfill_progress_at` **decreases** as older windows complete. `oldest_tx` decreases in lockstep.

Eventually one of two things happens:
- It reaches `monzo_created_at` → `backfill_status='COMPLETED'` (next scenario)
- SCA expires again → `backfill_status='NEEDS_REAUTH'` (repeat from §10 if you have a lot of history)

✅ **Scenario 10 Complete**

---

## ✅ 11. Backfill COMPLETED

Repeat §10 as many times as needed. Eventually:

```sql
SELECT backfill_status,
       backfill_progress_at,
       backfill_progress_cursor,
       monzo_created_at
FROM monzo_accounts;
```

Expect:
- `backfill_status = 'COMPLETED'`
- `backfill_progress_at` at or below `monzo_created_at`
- `backfill_progress_cursor` is null

```bash
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/monzo/status | jq .data.backfillStatus
# "COMPLETED"
```

### Verify the full historical reach

```sql
SELECT MIN(monzo_created_at) AS first_ever_tx,
       MAX(monzo_created_at) AS most_recent_tx,
       COUNT(*) AS total_transactions
FROM monzo_transactions;
```

`first_ever_tx` should be close to your Monzo account creation date — i.e. your very first Monzo transaction.

✅ **Scenario 11 Complete**

---

# Path C — Operational

## 🔁 12. Idempotency

Re-running backfill on a completed account should be a no-op. Trigger via the dev endpoint (cheaper than re-OAuth):

```bash
curl -s -X POST http://localhost:8080/api/dev/monzo/backfill \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```

```sql
SELECT COUNT(*) FROM monzo_transactions;
```

Identical count to before. The `while (windowEnd > floor)` guard in `backfillAccount` exits immediately when starting at or below the floor.

If you trigger backfill against an account that's still `IN_PROGRESS` (unusual but possible), upserts handle deduplication via `ON CONFLICT (id) DO UPDATE`.

✅ **Scenario 12 Complete**

---

## 🆕 13. Delta Sync

Delta sync fetches only transactions newer than the stored cursor (`last_transaction_id`).

There's no public endpoint to trigger delta sync manually — it's invoked by `MonzoTransactionDeltaSyncJob` (currently the only caller is internal scheduled work / future cron). For testing the logic in isolation, the integration test `MonzoTransactionSyncIT.usesCursorForDelta` covers it.

If you make a new transaction in Monzo while testing:
- Once that tx is settled (or visible via Monzo's API), the next delta sync would pick it up using `since=<last_transaction_id>` (Monzo's `since` param accepts a transaction id — there is no `since_id` param).
- The new tx's ID becomes the new `last_transaction_id`.

To verify the cursor itself:

```sql
SELECT id,
       last_transaction_id,
       last_synced_at
FROM monzo_accounts WHERE closed = false;

-- Confirm it points to a real tx
SELECT a.id AS account_id,
       a.last_transaction_id,
       t.monzo_created_at AS cursor_tx_created_at,
       t.description       AS cursor_tx_description
FROM monzo_accounts a
JOIN monzo_transactions t ON t.id = a.last_transaction_id
WHERE a.closed = false;
```

The cursor transaction should be the most recent tx for that account.

✅ **Scenario 13 Complete**

---

## 🧰 14. Dev Endpoints

### `POST /api/dev/monzo/backfill` — manually trigger backfill

Useful for re-running without OAuth (tokens persist in `monzo_connections`):

```bash
curl -s -X POST http://localhost:8080/api/dev/monzo/backfill \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```

Expect `{"success": true, "data": null}`. Note: this runs **synchronously** (HTTP response waits for completion). Not the same as the async-on-OAuth path.

### `POST /api/dev/monzo/reset-backfill/{accountId}` — clear state for re-testing

```bash
# Get the account ID
ACCOUNT_ID=$(docker exec budgeteer-postgres psql -U budgeteer -d budgeteer -At -c \
  "SELECT id FROM monzo_accounts LIMIT 1;")

curl -s -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/api/dev/monzo/reset-backfill/$ACCOUNT_ID | jq .
```

Verify the reset:

```sql
SELECT backfill_status,
       backfill_progress_at,
       backfill_progress_cursor,
       (SELECT COUNT(*) FROM monzo_transactions WHERE account_id = monzo_accounts.id) AS txs
FROM monzo_accounts WHERE id = '<ACCOUNT_ID>';
```

Expect all three `backfill_*` columns NULL and `txs = 0`. The account row itself and the connection remain — you can re-trigger backfill via the dev endpoint above without re-doing OAuth.

✅ **Scenario 14 Complete**

---

## ❌ 15. Error Cases

### 15a — Backfill without a Monzo connection

Create a second user that hasn't done OAuth:

```bash
NO_MONZO_TOKEN=$(curl -s -X POST http://localhost:8080/api/dev/auth/quick-login \
  -H "Content-Type: application/json" \
  -d '{"email": "no-monzo@example.com"}' | jq -r .data.accessToken)

curl -i -X POST http://localhost:8080/api/dev/monzo/backfill \
  -H "Authorization: Bearer $NO_MONZO_TOKEN"
```

Expect `HTTP/1.1 404` with `{"error":{"code":"RESOURCE_NOT_FOUND",...}}`.

### 15b — Reset wrong user's account

Try to reset User A's account using User B's token:

```bash
curl -i -X POST \
  -H "Authorization: Bearer $NO_MONZO_TOKEN" \
  http://localhost:8080/api/dev/monzo/reset-backfill/$ACCOUNT_ID
```

Expect `HTTP/1.1 403` with `{"error":{"code":"ACCESS_DENIED",...}}`.

### 15c — Reset unknown account

```bash
curl -i -X POST -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8080/api/dev/monzo/reset-backfill/acc_does_not_exist
```

Expect `HTTP/1.1 404`.

### 15d — Unauthenticated

```bash
curl -i -X POST http://localhost:8080/api/dev/monzo/backfill
curl -i -X POST http://localhost:8080/api/dev/monzo/reset-backfill/anything
```

Both: `HTTP/1.1 401`.

✅ **Scenario 15 Complete**

---

## 🗃️ Reference SQL Queries

### One-glance system state

```sql
SELECT u.email,
       c.id            AS connection_id,
       c.monzo_user_id,
       c.token_expires_at,
       COUNT(DISTINCT a.id) AS accounts,
       COUNT(t.id)          AS transactions
FROM users u
JOIN monzo_connections c ON c.user_id = u.id
LEFT JOIN monzo_accounts     a ON a.connection_id = c.id
LEFT JOIN monzo_transactions t ON t.account_id = a.id
WHERE c.disconnected_at IS NULL
GROUP BY u.email, c.id, c.monzo_user_id, c.token_expires_at;
```

### Per-account sync state

```sql
SELECT id,
       account_type,
       currency,
       closed,
       monzo_created_at,
       backfill_status,
       backfill_progress_at,
       backfill_progress_cursor,
       last_transaction_id,
       last_synced_at,
       CASE
           WHEN closed                                THEN '🔒 closed'
           WHEN backfill_status = 'COMPLETED'         THEN '✅ complete'
           WHEN backfill_status = 'NEEDS_REAUTH'      THEN '⚠️ needs reauth'
           WHEN backfill_status = 'IN_PROGRESS'       THEN '⏳ syncing'
           ELSE                                            '⏸️ not started'
       END AS state
FROM monzo_accounts
ORDER BY closed, account_type;
```

### Verify tokens are encrypted

```sql
SELECT id,
       LEFT(access_token_enc, 40) || '...' AS preview,
       CASE
           WHEN access_token_enc LIKE 'eyJ%' THEN '⚠️ PLAINTEXT JWT!'
           ELSE                                    '✅ encrypted'
       END AS encryption_check
FROM monzo_connections
WHERE disconnected_at IS NULL;
```

### FK integrity check (should return 0 rows)

```sql
SELECT t.id AS transaction_id, t.account_id, t.user_id
FROM monzo_transactions t
LEFT JOIN monzo_accounts a ON t.account_id = a.id
LEFT JOIN users u          ON t.user_id    = u.id
WHERE a.id IS NULL OR u.id IS NULL
LIMIT 5;
```

### Spending breakdown (sanity check on data quality)

```sql
SELECT COALESCE(merchant_category, 'transfer/other') AS category,
       COUNT(*)                                       AS count,
       SUM(amount)                                    AS total_pence,
       ROUND(SUM(amount)::numeric / 100, 2)           AS total_gbp
FROM monzo_transactions
WHERE amount < 0
GROUP BY COALESCE(merchant_category, 'transfer/other')
ORDER BY total_pence;
```

---

## 🧹 Reset utilities

### Wipe Monzo data but keep the connection (re-test backfill without re-OAuth)

```sql
TRUNCATE monzo_transactions, monzo_accounts CASCADE;
```

Then either re-trigger via dev endpoint, OR re-OAuth to get a fresh SCA window for the windowed backfill.

### Wipe everything (full re-OAuth required)

```sql
TRUNCATE monzo_transactions, monzo_accounts, monzo_connections, oauth_states CASCADE;
```

### Wipe ALL data including the user (truly fresh)

```sql
TRUNCATE monzo_transactions, monzo_accounts, monzo_connections, oauth_states, users CASCADE;
```

---

## ✅ Final Checklist

**Path A**
- [ ] Scenario 1: Prereqs — `APP_DATABASE_CLEAN_ON_STARTUP=false`, V10 migration ran
- [ ] Scenario 2: Clean DB + fresh user via quick-login; status shows `NOT_STARTED`
- [ ] Scenario 3: OAuth approved in Monzo app
- [ ] Scenario 4: Backfill triggered async on `taskExecutor-N`; accounts row inserted with `monzo_created_at`
- [ ] Scenario 5: Transactions persisted with proper `monzo_created_at`; no within-window gaps
- [ ] Scenario 6: `/api/monzo/status` returns `tokenStatus: ACTIVE`, `backfillStatus: IN_PROGRESS`/`COMPLETED`/`NEEDS_REAUTH`

**Path B**
- [ ] Scenario 7: Backfill walks ≤350-day windows; each request includes both `since` AND `before`
- [ ] Scenario 8: SCA expiry → ONE warn log, no retry storm; `backfill_status='NEEDS_REAUTH'`; checkpoint persisted
- [ ] Scenario 9: State survives app restart
- [ ] Scenario 10: Re-OAuth — first resumed request uses the saved `before` (= checkpoint); resume is per-window (`sinceId` null)
- [ ] Scenario 11: Eventually reaches `monzo_created_at` → `backfill_status='COMPLETED'`

**Path C**
- [ ] Scenario 12: Re-triggering completed backfill is a no-op (no duplicate rows)
- [ ] Scenario 13: `last_transaction_id` cursor points to the most recent tx
- [ ] Scenario 14: Dev backfill + reset endpoints work; reset clears all three `backfill_*` columns + deletes txs
- [ ] Scenario 15: 404 no-connection, 403 wrong-user, 404 unknown account, 401 unauthenticated

---

## 📁 Related Files

| Resource | Location |
|----------|----------|
| Postman Collection | `scripts/postman/budgeteer-transaction-sync.postman_collection.json` |
| Postman Environment | `scripts/postman/budgeteer-local.postman_environment.json` |
| Feature Documentation | `docs/features/MONZO-TRANSACTION-SYNC.md` |
| Plan / Design (resumability) | `.agents/notes/resumable-backfill-plan.md` |
| Service | `backend/src/main/java/dev/amf/budgeteer/service/monzo/TransactionSyncService.java` |
| Client (403 detection) | `backend/src/main/java/dev/amf/budgeteer/client/monzo/MonzoClient.java` |
| Status aggregation | `backend/src/main/java/dev/amf/budgeteer/service/monzo/MonzoConnectionService.java` |
| Dev controller | `backend/src/main/java/dev/amf/budgeteer/api/dev/DevMonzoController.java` |
| Entity | `backend/src/main/java/dev/amf/budgeteer/domain/monzo/MonzoAccount.java` |
| V9 Migration (`monzo_created_at`) | `backend/src/main/resources/db/migration/V9__add_monzo_account_created.sql` |
| V10 Migration (backfill state) | `backend/src/main/resources/db/migration/V10__add_monzo_account_backfill_state.sql` |
| Unit tests | `backend/src/test/java/dev/amf/budgeteer/service/monzo/TransactionSyncServiceTest.java` |
| Integration tests | `backend/src/test/java/dev/amf/budgeteer/integration/MonzoTransactionSyncIT.java` |

---

## 📝 Implementation notes worth knowing

### Progress checkpointing granularity

Durability is **per window**. Each ≤350-day window is fetched inside its own committed `txTemplate` transaction, so:
- A completed window is committed and survives a process kill, restart, or 403.
- The in-flight window when interrupted (kill or 403) rolls back wholesale.
- `backfill_progress_at` advances to the window start at each boundary; the durable resume point is always a window boundary.
- `backfill_progress_cursor` is written per-page *within* a window but is cleared at the boundary and rolled back on failure — so in committed state it is effectively always null, and resume restarts the last unfinished window from its start (not mid-page).

### `last_transaction_id` vs `backfill_progress_cursor`

- `last_transaction_id` — delta sync cursor (newest tx ever seen). Saved as soon as the first (most recent) window completes on a fresh backfill, so delta sync works even if the rest of backfill is paused in NEEDS_REAUTH.
- `backfill_progress_cursor` — intended as a mid-window resume cursor, but because each window is one committed transaction it is cleared at the boundary and rolled back on failure; committed state is effectively always null (resume is per-window).

### Backfill commits per window, not in one big transaction

`backfillAsync` / `backfill` are **not** `@Transactional`; each window commits independently via `txTemplate`. So completed windows are durable even on a hard `kill -9` — only the in-flight window is lost. On SCA expiry the catch block commits the `NEEDS_REAUTH` status. Re-OAuth (or `POST /api/dev/monzo/backfill`) restarts from the last committed window boundary — there is no auto-resume on app startup.

### `now()` drift between attempts

If the very first window of a fresh backfill is interrupted on its first request (before any cursor was saved), the next attempt's window walk uses the **new `now()`**, not the original one. This causes a few seconds to a few hours of drift, fully covered by the next window's natural overlap. No data is missed.

---

*Last updated: 2026-06-13 — Windowed backfill + resumable SCA recovery (V10).*
