# Feature: Monzo Token Auto-Refresh

> Proactive background refresh of Monzo OAuth tokens so the app never hits a 401,
> whether or not the user is actively logged in.

---

## 📋 Feature Summary

| Field | Value |
|-------|-------|
| **Feature Branch** | `feature/monzo-token-refresh` |
| **Status** | 🚀 **In Progress** |
| **Priority** | P1 - Required for all Monzo data features |
| **Dependencies** | Monzo Token Persistence ✅ |
| **Blocks** | Transaction Sync, Webhooks |

---

## 🎯 Scope

### To Implement
- [ ] `MonzoTokenRefreshService` — core refresh logic (system-scoped, no user context)
- [ ] `MonzoTokenRefreshJob` — `@Scheduled` entry point, runs every 30 minutes
- [ ] `@EnableScheduling` wired into the application
- [ ] Proactive buffer query — find tokens expiring within the next 60 minutes
- [ ] Revocation handling — soft-delete connection on 401 from Monzo
- [ ] Eager on-demand refresh — in `MonzoConnectionService.getDecryptedAccessToken()`, refresh inline if expiry is within 5 minutes
- [ ] Config properties for cron expression and refresh windows (overridable per environment)
- [ ] Status endpoint enhancement — expose token health (`ACTIVE`, `EXPIRING_SOON`, `RECONNECT_REQUIRED`)
- [ ] Unit tests — `MonzoTokenRefreshServiceTest`, `MonzoTokenRefreshJobTest`
- [ ] Integration test — `MonzoTokenRefreshIT` (WireMock + Testcontainers)

### Out of Scope
- DB migration (no new columns needed — all required fields exist in `monzo_connections`)
- Retry tracking / backoff columns (deferred, overkill for single-user app)
- Push notification to user on revocation (no frontend yet)
- MonzoClient resilience (timeouts, circuit breaker — separate backlog item)

---

## 🏗️ Design

### Why polling, not events

Monzo does not push a notification when a token expires. The only signal is a 401 when
you try to use an expired token. Proactive polling — querying the DB for tokens about to
expire and refreshing them ahead of time — is the standard approach and the only one
that keeps tokens alive for users who are not actively using the app.

### Two-layer approach (Option B)

```
Layer 1 — Background job (inactive users)
  MonzoTokenRefreshJob  (@Scheduled, every 30 min)
    └─ MonzoTokenRefreshService.refreshAllExpiringSoon(now + JOB_REFRESH_WINDOW)
         └─ find tokens with tokenExpiresAt < threshold
         └─ for each: decrypt refresh token → call Monzo → re-encrypt → save
         └─ on 401: soft-delete connection

Layer 2 — On-demand guard (active users, belt-and-suspenders)
  MonzoConnectionService.getDecryptedAccessToken()
    └─ if connection.isTokenExpiringSoon(EAGER_REFRESH_WINDOW)
         └─ MonzoTokenRefreshService.refresh(connection)
    └─ return fresh access token
```

Layer 1 guarantees tokens never expire for inactive users.
Layer 2 handles the edge case where a user makes an API call in the window between
a token expiring and the next job run.

### Timing parameters

| Parameter | Default | Meaning |
|-----------|---------|---------|
| `job-cron` | `0 */30 * * * *` | Job runs every 30 minutes |
| `job-refresh-window-minutes` | `60` | Refresh tokens expiring within 60 min |
| `eager-refresh-window-minutes` | `5` | Refresh inline if expiring within 5 min |

The invariant is: `job-refresh-window > job-interval`. With a 30-minute interval and a
60-minute window, every token is caught by at least one job run before expiry.

### Why the eager refresh window exists

Under normal steady-state operation the eager refresh (Layer 2) never fires — the job
always stays well ahead of the 5-minute mark. It exists purely as a safety net for two
edge cases:

1. **App startup / restart race** — the first job run happens at the next cron tick, up
   to 30 minutes after the app starts. If the app was down for an extended period and a
   user makes a request before the first job run, the job has not had a chance to refresh
   a near-expiry token. The eager guard catches it inline.

2. **Per-connection job failure** — the job isolates failures per connection and skips to
   the next one on error. If a specific connection hits a transient error (e.g. a network
   blip to Monzo), it is skipped and must wait up to 30 minutes for the next run. If that
   token is close to expiry when the user next makes a request, the eager guard refreshes
   it silently rather than propagating a 401.

If `job-refresh-window` were ever reduced below `job-interval` (breaking the invariant),
the eager refresh would become load-bearing for all connections, not just these edge cases.

### Dependency graph (no circular dependencies)

```
MonzoTokenRefreshJob
  └─ MonzoTokenRefreshService
       ├─ MonzoConnectionRepository   (load + save connections directly)
       ├─ MonzoClient                 (call Monzo /oauth2/token)
       └─ EncryptionService           (decrypt refresh token, re-encrypt new tokens)

MonzoConnectionService  (user-scoped, for request handlers)
  ├─ MonzoConnectionRepository
  ├─ EncryptionService
  └─ MonzoTokenRefreshService         (injected for eager refresh guard only)
```

`MonzoTokenRefreshService` is **system-scoped** — it works directly with entities and
the repository, bypassing the user-ownership checks in `MonzoConnectionService`. This
is intentional: background jobs run as the system, not as a specific user.

### Refresh flow (per connection)

```
1. Decrypt refresh token using EncryptionService
2. Call MonzoClient.refreshTokens(plainTextRefreshToken)
3a. Success → re-encrypt both tokens → connection.updateTokens(...) → save
3b. 401 (MONZO_CONNECTION_REVOKED) → connection.disconnect() → save → log WARN
3c. Other exception → log ERROR, skip this connection, continue with others
```

Each connection is refreshed in its own `@Transactional` boundary so one failure
does not roll back the others.

### Revocation handling

When Monzo returns 401, the refresh token has been revoked (user revoked access in the
Monzo app, or the token was invalidated). The connection is soft-deleted (`disconnected_at`
set). The user will see `connected: false` on the status endpoint, which is the signal
to re-initiate OAuth.

### Token status on the API

The status endpoint (`GET /api/monzo/status`) is enhanced to expose token health:

```json
{
  "connected": true,
  "connectionCount": 1,
  "tokenStatus": "ACTIVE"
}
```

`tokenStatus` values:
| Value | Meaning |
|-------|---------|
| `ACTIVE` | Connected, token valid for > 5 minutes |
| `EXPIRING_SOON` | Connected, token valid for ≤ 5 minutes (refresh imminent) |
| `RECONNECT_REQUIRED` | No active connection — re-OAuth needed |

---

## 🏗️ Components

### Files to Create

| Component | Path | Description |
|-----------|------|-------------|
| `MonzoTokenRefreshService` | `service/monzo/MonzoTokenRefreshService.java` | Core refresh logic — system-scoped |
| `MonzoTokenRefreshJob` | `service/monzo/MonzoTokenRefreshJob.java` | `@Scheduled` entry point |
| `MonzoTokenRefreshServiceTest` | `test/.../MonzoTokenRefreshServiceTest.java` | Unit tests |
| `MonzoTokenRefreshJobTest` | `test/.../MonzoTokenRefreshJobTest.java` | Scheduler wiring tests |
| `MonzoTokenRefreshIT` | `test/.../MonzoTokenRefreshIT.java` | Integration test (WireMock + PG) |

### Files to Modify

| Component | Path | Change |
|-----------|------|--------|
| `BudgeteerApplication` | `BudgeteerApplication.java` | Add `@EnableScheduling` |
| `MonzoConnection` | `domain/monzo/MonzoConnection.java` | Add `isTokenExpiringSoon(Duration)` |
| `MonzoConnectionRepository` | `repository/MonzoConnectionRepository.java` | Add `findActiveExpiringBefore(Instant)` |
| `MonzoConnectionService` | `service/monzo/MonzoConnectionService.java` | Inject `MonzoTokenRefreshService`; add eager guard in `getDecryptedAccessToken()` |
| `ConnectionStatusResponse` | `api/monzo/dto/ConnectionStatusResponse.java` | Add `tokenStatus` field |
| `application.properties` | `resources/application.properties` | Add `monzo.token-refresh.*` config |

---

## ⚙️ Configuration

```properties
# Cron expression for the token refresh job (default: every 30 minutes)
monzo.token-refresh.job-cron=0 */30 * * * *

# Refresh tokens expiring within this many minutes (must be > job interval)
monzo.token-refresh.job-refresh-window-minutes=60

# Eager refresh: refresh inline if token expires within this many minutes
monzo.token-refresh.eager-refresh-window-minutes=5
```

---

## 🔌 API Changes

### GET `/api/monzo/status` — enhanced response

**Before:**
```json
{
  "success": true,
  "data": {
    "connected": true,
    "connectionCount": 1
  }
}
```

**After:**
```json
{
  "success": true,
  "data": {
    "connected": true,
    "connectionCount": 1,
    "tokenStatus": "ACTIVE"
  }
}
```

No breaking change — additive field only.

---

## 🔒 Security Notes

| Concern | Handling |
|---------|---------|
| Plaintext tokens in memory | Short-lived — decrypted immediately before use, not stored in fields |
| Token logging | `MonzoClient` logs `[expiresIn=Xs]` only, never the token value |
| Background job trust | Job is internal, runs in the same JVM — no auth context needed |
| Revocation detection | 401 → immediate soft-delete, user must re-OAuth |
| Refresh token rotation | Monzo rotates refresh tokens — `MonzoClient` already handles this (falls back to old token if Monzo doesn't return a new one) |

---

## 🧪 Test Plan

### Unit Tests

**`MonzoTokenRefreshServiceTest`**
- Happy path: expired token is refreshed, new tokens persisted
- Monzo returns new refresh token (rotation) — both tokens updated
- Monzo does NOT return new refresh token — old refresh token kept
- 401 from Monzo → connection is soft-deleted
- Non-401 error from Monzo → exception logged, connection NOT disconnected
- `refreshAllExpiringSoon()` processes multiple connections independently

**`MonzoTokenRefreshJobTest`**
- Job calls `refreshAllExpiringSoon` with correct threshold
- Verify `@Scheduled` annotation is present with expected cron

### Integration Tests

**`MonzoTokenRefreshIT`** (WireMock + Testcontainers)
- Create connection with `tokenExpiresAt` in the past (expired)
- Call `refreshAllExpiringSoon(now + 60min)` — verify connection updated with new tokens
- Stub Monzo 401 — verify connection soft-deleted after `refreshAllExpiringSoon`
- Stub Monzo 500 — verify connection remains active (not disconnected on transient error)
- Eager refresh via `getDecryptedAccessToken()` on a near-expiry connection

---

## 📝 Implementation Notes

### Decision: system-scoped vs user-scoped

`MonzoTokenRefreshService` operates directly on `MonzoConnection` entities and the
repository, bypassing `MonzoConnectionService`'s user-ownership checks. This is a
deliberate design choice — background jobs run as the system, not as a specific user,
and adding fake `userId` parameters to work around ownership checks would be misleading.
`MonzoConnectionService` remains the user-facing API; `MonzoTokenRefreshService` is the
system-facing one.

### Decision: no DB migration needed

All columns required for refresh (`token_expires_at`, `refresh_token_enc`, `disconnected_at`,
`updated_at`) already exist from V5. The refresh job is purely application-layer logic.

### Decision: per-connection transactions

Each connection is refreshed in its own transaction (`@Transactional` on the individual
`refresh(connection)` method, not on `refreshAllExpiringSoon`). This ensures one failed
refresh does not roll back successful ones.

### Decision: soft-delete on revocation, not a separate status flag

Rather than adding a `requires_reconnect` column (which would need a migration), revoked
connections are soft-deleted. The existing `connected: false` status and the new
`RECONNECT_REQUIRED` token status communicate the same information to callers.

---

## 📊 Definition of Done

- [ ] `MonzoTokenRefreshService` implemented with unit tests
- [ ] `MonzoTokenRefreshJob` implemented with scheduler test
- [ ] Eager refresh guard wired into `MonzoConnectionService`
- [ ] Status endpoint returns `tokenStatus`
- [ ] Config properties documented and working
- [ ] Integration test covers happy path, 401 revocation, transient error
- [ ] No tokens logged anywhere
- [ ] `/check` passes (checkstyle + all tests green)
- [ ] Feature doc updated with implementation notes
- [ ] PR raised against `main`

---

## 🔗 Related Documents

- [Monzo Token Persistence](MONZO-TOKEN-PERSISTENCE.md)
- [Security Architecture](../SECURITY-ARCHITECTURE.md)
- [Task Plan](../../.agents/tasks/token-auto-refresh/plan.md)

---

**Created:** April 2026  
**Last Updated:** April 2026
