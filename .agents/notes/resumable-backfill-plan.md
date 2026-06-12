# Resumable Backfill with SCA Re-Auth Recovery — Plan

**Status:** Planned, not yet implemented. Next session — carry on from here.
**Branch:** `feature/transaction-sync` (current, contains uncommitted windowed-backfill fix)
**Created:** 2026-06-12

## Context — what brought us here

### Where the branch is now (uncommitted)

The branch contains a working **windowed backfill** fix:

- `MonzoClient.getTransactions()` accepts `since` (RFC3339) and `before` (RFC3339) params in addition to the existing `since_id` cursor.
- `TransactionSyncService.backfillAccount()` walks ≤350-day windows backwards from `now()` to the account's `monzo_created_at` (or a 2015 absolute floor). Within each window, the existing cursor pagination drains pages until <100 returned.
- `MonzoAccountResponse` DTO gained a `created` field; `monzo_accounts.monzo_created_at` column added via Flyway V9.
- All 154 Monzo tests are green.

**Verified manual test (2026-06-12):** 572 transactions persisted across multiple historical windows (vs. the prior 81 cap). Significant improvement.

### What we found that the current fix does NOT handle

Looking at the per-month tx count from the verified run:

```
2026-06: 32, 2026-05: 55, 2025-07: 87, 2025-06: 84,
2025-05: 14, 2024-08: 33, 2024-07: 92, 2024-06: 75,
2023-08: 95, 2023-07: 5
```

There are **gaps within expected windows** (e.g. 2025-08 → 2026-04 has nothing, 2024-09 → 2025-04 has nothing). This is because:

1. Monzo's **5-minute SCA window** closes while the backfill is still mid-flight.
2. Once closed, requests with `since` older than 90 days return `403 forbidden.verification_required`.
3. Our 403 hits **mid-pagination within a window** — we lose the rest of that window, and the next windows entirely.
4. The existing `@Async` retry restarts the backfill from scratch — which keeps failing because W1 itself is >90 days.
5. The user has no resume path; older history is silently dropped.

## Outcome we want

- Backfill saves progress per page (not just per window).
- On 403 verification_required: mark account `NEEDS_REAUTH`, exit cleanly, no retry storm.
- Re-OAuth resumes from the last persisted page — new SCA window unlocks older history.
- Status flag surfaced to frontend (e.g. `MonzoStatusResponse.backfillStatus`) so the UI can show a "Reconnect to import older history" CTA.

## Approach

### Storage — Flyway V10

Add three columns to `monzo_accounts`:

| Column | Type | Purpose |
|--------|------|---------|
| `backfill_status` | `VARCHAR(32)` nullable | Enum string: `IN_PROGRESS`, `COMPLETED`, `NEEDS_REAUTH`. Null = never started. |
| `backfill_progress_at` | `TIMESTAMP WITH TIME ZONE` nullable | `windowEnd` of the **next** window to process. Null = start fresh from `now()`. Decreases as windows complete. |
| `backfill_progress_cursor` | `VARCHAR(255)` nullable | Cursor (`tx_id`) within the current in-flight window. Null at window boundary. Lets us resume mid-window after a 403. |

**Why all three:** the data above proves 403s hit mid-pagination. Per-window-only progress would re-fetch the partial window on every retry (idempotent but wasteful, and could re-trigger 403 on the same window if SCA hasn't been refreshed). Per-page cursor persistence lets retries skip already-fetched pages directly via `since_id`.

### Resume logic — `TransactionSyncService.backfillAccount`

```
1. windowEnd = account.backfillProgressAt ?? Instant.now()
2. cursor    = account.backfillProgressCursor   // null on fresh / clean window
3. status    = IN_PROGRESS; save
4. try:
       while (windowEnd > floor):
           windowStart = max(windowEnd - 350d, floor)
           paginateWindow(account, windowStart, windowEnd, cursor)
           account.backfillProgressAt     = windowStart
           account.backfillProgressCursor = null
           save(account)
           cursor    = null
           windowEnd = windowStart
       status = COMPLETED; save
   catch ApiException(MONZO_VERIFICATION_REQUIRED):
       status = NEEDS_REAUTH; save
       // cursor + progress_at already persisted by paginateWindow
```

### `paginateWindow` — cursor-savvy inner pagination

```
windowSince = since
loop:
    page = monzoClient.getTransactions(token, accountId,
                                       since=windowSince, before=windowEnd,
                                       sinceId=cursor, limit=100)
    upsert each tx
    if page.size < 100: return    // window drained
    cursor = page.last.id
    account.backfillProgressCursor = cursor   // persist mid-window cursor
    save(account)
    windowSince = null    // drop `since` once we have a precise cursor
```

### Detecting `forbidden.verification_required` — `MonzoClient.handleMonzoError`

Currently all 403s become `MONZO_API_ERROR`. Change to:

- Parse `e.getResponseBodyAsString()` for `"code":"forbidden.verification_required"`
- If matched → throw `ApiException(MONZO_VERIFICATION_REQUIRED)`
- Otherwise → existing `MONZO_API_ERROR`

Add `MONZO_VERIFICATION_REQUIRED` (`HttpStatus.FORBIDDEN`) to `ErrorCode.java`.

### Retry guard in `backfillAsync` (existing — confirm semantics)

The current retry guard:
```java
if (e.getErrorCode() == ErrorCode.MONZO_API_ERROR && attempt < maxRetries) { ... }
```

No change needed. `MONZO_VERIFICATION_REQUIRED` falls through to the else-branch (logged, no retry). Plus `backfillAccount` will catch it internally so it normally won't propagate. The guard remains a safety net for transient API errors.

### Status to UI — `MonzoStatusResponse`

Mirror the `tokenStatus` pattern. New enum `BackfillStatus`:

- `NOT_STARTED` — no account / `backfill_status` is null
- `IN_PROGRESS`
- `NEEDS_REAUTH`
- `COMPLETED` — all active accounts done

Computed in `MonzoConnectionService.getStatus`. Frontend reads this via `GET /api/monzo/status`.

### Resume on re-OAuth — no code change

`backfillAsync` already runs after OAuth callback (`MonzoController:188`). Because `backfillAccount` reads `account.backfillProgressAt` / `backfillProgressCursor` as starting state, re-OAuth picks up exactly where we left off — and the new SCA window means older windows are now accessible.

### Dev reset endpoint

`POST /api/dev/monzo/reset-backfill/{accountId}` on `DevMonzoController`:
- Verify ownership
- Clear `backfill_status`, `backfill_progress_at`, `backfill_progress_cursor`
- Delete that account's transactions

For re-testing without recreating the DB.

### Dev DB persistence (testing aid, NOT a committed change)

`application-dev.properties:48` has `app.database.clean-on-startup=true`. While testing the resume flow we need to **temporarily** disable this so partial state survives app restarts:

```bash
export APP_DATABASE_CLEAN_ON_STARTUP=false
mvn spring-boot:run
```

Don't commit that change to the properties file.

## Files to Modify

| File | Change |
|------|--------|
| `backend/src/main/resources/db/migration/V10__add_monzo_account_backfill_state.sql` | NEW — three columns |
| `backend/src/main/java/dev/amf/budgeteer/domain/monzo/MonzoAccount.java` | Add `BackfillStatus` enum + 3 fields + accessors |
| `backend/src/main/java/dev/amf/budgeteer/api/common/ErrorCode.java` | Add `MONZO_VERIFICATION_REQUIRED` |
| `backend/src/main/java/dev/amf/budgeteer/client/monzo/MonzoClient.java` | Parse 403 body in `handleMonzoError` |
| `backend/src/main/java/dev/amf/budgeteer/service/monzo/TransactionSyncService.java` | Persist progress per page + catch verification → mark `NEEDS_REAUTH` |
| `backend/src/main/java/dev/amf/budgeteer/service/monzo/MonzoConnectionService.java` | New `getBackfillStatus()` mirroring `TokenStatus` pattern |
| `backend/src/main/java/dev/amf/budgeteer/api/monzo/dto/MonzoStatusResponse.java` | Add `backfillStatus` field |
| `backend/src/main/java/dev/amf/budgeteer/api/dev/DevMonzoController.java` | Add `POST /reset-backfill/{accountId}` |
| Tests | See below |

## Tests

- **`MonzoClientTest`**
  - 403 with body `{"code":"forbidden.verification_required"}` → `MONZO_VERIFICATION_REQUIRED`
  - 403 with other body / no body → keeps existing `MONZO_API_ERROR` behaviour
- **`TransactionSyncServiceTest`**
  - Resume — `account.backfillProgressAt` non-null → first request uses it as `before`
  - Resume mid-window — `backfillProgressCursor` non-null → first request uses it as `since_id`
  - After each page in a window, cursor is persisted
  - After each window completes, `backfill_progress_at = windowStart` and cursor cleared
  - 403 verification mid-flight → status `NEEDS_REAUTH`, progress preserved, no exception escapes
  - Reaching floor → status `COMPLETED`
- **`MonzoTransactionSyncIT`** — 2 successful windows then simulated 403 → persisted txs from those windows + status `NEEDS_REAUTH` + progress matches expected window 3 start
- **`DevMonzoControllerTest`** — reset endpoint clears state and txs

## Verification

### Tests
```bash
mvn test -Dtest='MonzoClientTest,TransactionSyncServiceTest,MonzoTransactionSyncIT,DevMonzoControllerTest'
```

### End-to-end manual

1. `export APP_DATABASE_CLEAN_ON_STARTUP=false; mvn spring-boot:run`
2. Fresh OAuth, approve on phone within 5 min.
3. Wait ~30s. Backfill runs, SCA expires, hits 403, exits cleanly.
4. Check DB:
   ```sql
   SELECT id, backfill_status, backfill_progress_at, backfill_progress_cursor,
          (SELECT COUNT(*) FROM monzo_transactions WHERE account_id = monzo_accounts.id) AS txs,
          (SELECT MIN(monzo_created_at) FROM monzo_transactions WHERE account_id = monzo_accounts.id) AS oldest_tx
   FROM monzo_accounts;
   ```
   Expect: `NEEDS_REAUTH`, progress_at non-null, oldest_tx well within last year.
5. Check status:
   ```bash
   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/monzo/status
   ```
   Expect: `"backfillStatus":"NEEDS_REAUTH"`.
6. **Restart the app** to verify state persists.
7. Re-OAuth: `GET /api/monzo/connect` → approve → wait ~30s.
8. DB: `oldest_tx` should be further back, `backfill_progress_at` reduced.
9. Repeat re-OAuth until `backfill_status = COMPLETED`.

### Reset for re-testing
```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/dev/monzo/reset-backfill/<accountId>
```

## Non-Goals (defer)

- Frontend UI for "Reconnect to import older history" — backend surfaces the flag, UI is separate work
- Multi-account concurrency guards (low risk in practice)
- 429 rate-limit handling
- Webhook real-time sync

## Resuming this work tomorrow

The current branch state:
- Windowed backfill fix in place and tested (572 txs verified)
- DTO `created` field + V9 migration in place
- Nothing committed yet — all changes are uncommitted on `feature/transaction-sync`

When you pick this back up:
1. `git status` to see the uncommitted windowed-backfill changes
2. Decide whether to **commit the windowing fix first** (clean intermediate state) or **bundle everything into one PR** (single coherent "transaction sync" feature)
3. Start with the V10 migration + entity changes
4. Then `MonzoClient.handleMonzoError` (so unit tests for paginate can rely on the new error code)
5. Then `TransactionSyncService` paginate-with-cursor-persistence
6. Then status surfacing + dev reset endpoint
7. Tests as you go
8. Manual E2E verification per the section above
