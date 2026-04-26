# Phase 3: Token Auto-Refresh

> **Priority:** P1 | **Estimate:** 1 day | **Status:** In Progress

## Goal

Automatically refresh Monzo access tokens before they expire so the app never hits a 401,
whether or not the user is actively using the application. Two-layer approach: a scheduled
background job (every 30 min) handles inactive users; an on-demand eager refresh guard in
`MonzoConnectionService` handles the edge case where an active user's token expires between
job runs. Revoked tokens (401 from Monzo) result in a soft-deleted connection and a
`RECONNECT_REQUIRED` status visible via the API.

See full design: `docs/features/MONZO-TOKEN-AUTO-REFRESH.md`

## Branch

`feature/monzo-token-refresh`

---

## Scope

### 1. Enable Spring Scheduling
- [ ] Add `@EnableScheduling` to `BudgeteerApplication.java`

### 2. Entity enhancement
- [ ] Add `isTokenExpiringSoon(Duration window)` to `MonzoConnection`
  - Returns `true` if `tokenExpiresAt < Instant.now().plus(window)`
  - Used by both the eager guard and for the `EXPIRING_SOON` status

### 3. Repository query
- [ ] Add `findActiveExpiringBefore(Instant threshold)` to `MonzoConnectionRepository`
  - JPQL: `WHERE mc.disconnectedAt IS NULL AND mc.tokenExpiresAt < :threshold`
  - The existing `findActiveWithExpiredTokens` can remain as-is (used in tests); add the new method with a clearer name

### 4. Config properties
- [ ] Add to `application.properties`:
  ```properties
  monzo.token-refresh.job-cron=0 */30 * * * *
  monzo.token-refresh.job-refresh-window-minutes=60
  monzo.token-refresh.eager-refresh-window-minutes=5
  ```
- [ ] Bind via a `@ConfigurationProperties` record or add to `MonzoProperties`

### 5. MonzoTokenRefreshService (new)
Path: `service/monzo/MonzoTokenRefreshService.java`

Dependencies: `MonzoConnectionRepository`, `MonzoClient`, `EncryptionService`

- [ ] `refresh(MonzoConnection connection)` — `@Transactional`
  1. Decrypt refresh token via `encryptionService.decrypt(connection.getRefreshTokenEncrypted())`
  2. Call `monzoClient.refreshTokens(plainTextRefreshToken)`
  3. On success: re-encrypt both tokens, call `connection.updateTokens(...)`, save
  4. On `ApiException(MONZO_CONNECTION_REVOKED)`: call `connection.disconnect()`, save, log WARN
  5. On any other exception: log ERROR, re-throw (caller decides whether to continue)
  6. Returns the updated `MonzoConnection`

- [ ] `refreshAllExpiringSoon(Instant threshold)` — NOT `@Transactional` at method level
  1. Query `findActiveExpiringBefore(threshold)`
  2. For each connection: call `refresh(connection)` in a try/catch
  3. On `MONZO_CONNECTION_REVOKED` (already handled inside `refresh`): continue
  4. On any other exception: log ERROR, skip to next connection
  5. Returns count of successfully refreshed connections

### 6. MonzoTokenRefreshJob (new)
Path: `service/monzo/MonzoTokenRefreshJob.java`

- [ ] `@Component` + `@Scheduled(cron = "${monzo.token-refresh.job-cron}")`
- [ ] Reads `job-refresh-window-minutes` from config
- [ ] Calls `tokenRefreshService.refreshAllExpiringSoon(Instant.now().plus(window))`
- [ ] Logs count of connections refreshed at INFO level

### 7. Eager refresh guard in MonzoConnectionService
- [ ] Inject `MonzoTokenRefreshService` into `MonzoConnectionService`
- [ ] In `getDecryptedAccessToken(UUID connectionId, UUID userId)`:
  - After loading connection, check `connection.isTokenExpiringSoon(eagerRefreshWindow)`
  - If true: call `tokenRefreshService.refresh(connection)` to get updated connection
  - Then decrypt and return the access token from the refreshed connection
- [ ] Same guard in `getDecryptedTokens(UUID connectionId, UUID userId)`

### 8. Status endpoint enhancement
- [ ] Add `tokenStatus` field to `ConnectionStatusResponse`
- [ ] Derive in `MonzoController` (or a service method) from the connection state:
  - No active connection → `RECONNECT_REQUIRED`
  - Active + `isTokenExpiringSoon(5 min)` → `EXPIRING_SOON`
  - Active + token valid → `ACTIVE`
- [ ] Update `GET /api/monzo/status` response

### 9. Unit tests
- [ ] `MonzoTokenRefreshServiceTest`
  - Happy path: refresh succeeds, both tokens updated
  - Monzo returns new refresh token — rotation handled
  - Monzo does NOT return new refresh token — old token kept
  - 401 → connection soft-deleted
  - 500 → exception propagated, connection NOT disconnected
  - `refreshAllExpiringSoon()`: multiple connections, one 401 mid-batch, others still refreshed
- [ ] `MonzoTokenRefreshJobTest`
  - Job invokes `refreshAllExpiringSoon` with the correct threshold
  - Verify `@Scheduled` cron annotation is wired

### 10. Integration tests
- [ ] `MonzoTokenRefreshIT` (WireMock + Testcontainers)
  - Expired token in DB + Monzo stub → tokens updated in DB
  - Expired token + Monzo 401 stub → connection disconnected
  - Expired token + Monzo 500 stub → connection NOT disconnected
  - Eager refresh via `getDecryptedAccessToken()` on near-expiry connection
  - `GET /api/monzo/status` returns `RECONNECT_REQUIRED` after soft-delete

### 11. Pre-PR checks
- [ ] Run `/check` — checkstyle + unit tests + ITs all green
- [ ] Verify no tokens appear in logs (log sanitizer already in place)
- [ ] Update `docs/features/MONZO-TOKEN-AUTO-REFRESH.md` Definition of Done checkboxes

---

## Key Files

| File | Action |
|------|--------|
| `BudgeteerApplication.java` | Add `@EnableScheduling` |
| `MonzoConnection.java` | Add `isTokenExpiringSoon(Duration)` |
| `MonzoConnectionRepository.java` | Add `findActiveExpiringBefore(Instant)` |
| `MonzoConnectionService.java` | Inject refresh service; eager guard |
| `MonzoTokenRefreshService.java` | **NEW** |
| `MonzoTokenRefreshJob.java` | **NEW** |
| `ConnectionStatusResponse.java` | Add `tokenStatus` field |
| `application.properties` | Add `monzo.token-refresh.*` |
| `docs/features/MONZO-TOKEN-AUTO-REFRESH.md` | **NEW** (done) |

## Notes

- No DB migration needed — all required columns exist in V5
- `MonzoTokenRefreshService` is system-scoped (no `userId` param) — operates directly
  on entities. `MonzoConnectionService` remains user-scoped for request handlers.
- Each connection refresh is its own `@Transactional` — failures are isolated
- `MonzoClient.refreshTokens()` already handles refresh token rotation (line 116)
- Cron `0 */30 * * * *` fires at :00 and :30 of every hour
