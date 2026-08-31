# Session Memory - Budgeteer Project

> This file maintains context between sessions. Update this at the end of significant work sessions.

---

## Frontend Phase — Do This When Creating the Vite App

Configure a Vite dev proxy in `vite.config.ts` so all `/api` requests forward to the Spring backend:

```ts
server: {
  proxy: {
    '/api': 'http://localhost:8080'
  }
}
```

**Why:** `localhost:3000` (Vite) → `localhost:8080` (Spring) is cross-origin in the browser's eyes. Without the proxy, every API call triggers a CORS preflight and `SameSite=Lax` cookies won't be sent on `fetch()`. The proxy makes everything same-origin on `:3000`, matching production behaviour. The `app.cors.allowed-origins` config remains as a fallback but won't be actively relied on.

Also add `Referrer-Policy` and `Permissions-Policy` headers to `SecurityConfig` during this phase (tracked in backlog).

---

---

## 📅 Last Updated
**Date:** 2026-08-31
**Session Focus:** **Task #12 provider-contract-hardening executed end-to-end and merged (PR #85).** Java lessons parked (dip-in/dip-out at Alexander's pace — Lesson 1 exercises B & C and Lesson 2 remain whenever they pick it up; Lesson 2's exercise C is now a *review* of the shipped `Sourced<T>` rather than a from-scratch build). **Design pivot, Alexander's call:** instead of the planned `TransactionsSinceIdCapability`, the fetch start point became a sealed `SyncPosition` (`FromTime` | `AfterTransaction` | `NextPage`) passed to the single `TransactionsCapability.getTransactions(accessToken, accountId, position, to)` — rationale: Monzo's `since` param natively accepts all three shapes, and an exhaustive sealed switch forces future providers (TrueLayer) to decide at compile time what `AfterTransaction` does (throw loudly), which is stronger than a silently-unimplemented capability interface. deltaSync now seeds `AfterTransaction(lastTransactionId)` contractually (leak fixed); `Sourced<T>(payload, rawJson)` envelope (redacting `toString`, PECS `map`) carries provenance — `BankTransactionPage` holds `List<Sourced<BankTransaction>>`, `getAccounts` returns `List<Sourced<BankAccount>>`, domain records are pure values. Full gate green; squash-merged via auto-merge. `feature/domain-model-mapping` **rebased onto the new main** — its stale board-doc commits were resolved toward main's newer copies (main's board sync had absorbed them); unique content preserved (TrueLayer OpenAPI docs, provider/institution glossary in architecture.md, `LogSanitizer` handler fix); force-pushed with lease. #11 spec touched up (`sourced.rawJson()` / sealed `SyncPosition`). Board: #12 → Done/closed. **Next: hand #11's Implementer Kickoff Prompt (in its plan.md) to the implementing model, `/check`, PR.**

---

## 📅 Previous Session (2026-08-25)
**Session Focus:** Provider contract capability split + task #12 opened; board aligned to `main`. (1) Closed out all 6 open Dependabot PRs (5 merged serially against the strict check, #76 withdrawn by Dependabot — workflows track `@v5` which already resolves to 5.6.0); recreated the transfer-lost labels `dependencies`/`java`/`github-actions`/`docker`. (2) Old-account email leak plugged: `alexandermfisher` removed as collaborator; GH notifications go to `accounts@amfshr.dev` (correct); `budgeteer.amfshr.dev` reserved for the product; **Resend account deleted** — prod email dead, config intentionally left pending Resend-vs-Proton decision. (3) **PR #84 merged**: `AccountInformationProvider` split into `ProviderConnectionAuth` + `AccountsCapability` / `BalanceCapability` / `TransactionsCapability`; Monzo impl implements all four, consumers inject only what they use; `feature/domain-model-mapping` rebased on top, #11 spec/glossary/board repointed. (4) **Task #12 provider-contract-hardening opened** on `refactor/provider-delta-and-sourced` (off `main`, board+memory synced from the feature branch onto it): explicit `TransactionsSinceIdCapability` (legitimises deltaSync's lastTransactionId seeding — user call: id-based delta is fine, it just must be contractual, with time-window fallback deferred to TrueLayer) + `Sourced<T>` raw-JSON envelope (**pair session: Alexander writes the envelope for Java practice, Claude reviews**). Execution order now **#12 → #11 → #5**. **Next: pair on #12 (plan at `.agents/tasks/open/provider-contract-hardening/plan.md`), merge, rebase #11's branch, then hand off the #11 Implementer Kickoff Prompt.**

---

## 📅 Previous Session (2026-08-24)
**Session Focus:** Provider-contract rename, pulled forward from "commit 1 of TrueLayer" (user call: naming debt was irking, and #11 was about to write provider-facing code that would otherwise need re-touching). **PR #80** on `chore/provider-contract-rename` off `main`, three commits: (1) the leftover `getLast()` tweak, (2) the glossary rename — `BankClient` → `AccountInformationProvider`, `MonzoBankClient` → `MonzoAccountInformationProvider`, exceptions → `ProviderException` / `ProviderConnectionRevokedException` / `ProviderReauthRequiredException`, `MONZO_*` codes → `PROVIDER_*` (incl. `MONZO_VERIFICATION_REQUIRED` → `PROVIDER_REAUTH_REQUIRED` to match the exception), jars → `provider-api` / `provider-monzo`, (3+) package alignment `dev.amfshr.budgeteer.bank` → `.provider` and `.client.monzo` → `.provider.monzo`, then `provider-api` structured into contracts-at-root + `model/` + `exception/` subpackages (user picked role-based over capability-based ais/pis). `Bank*` records unchanged. Full gate green at every step (checkstyle, 475+53+4 unit, 112 IT). **Gotchas learned:** merge to `main` was blocked by the ruleset's CodeQL code-scanning gate — touching a line makes it "new code", so a pre-existing latent alert (java/log-injection in `GlobalExceptionHandler`) blocked the pure-rename PR; user dismissed the alert to unblock, and the sanitize fix raced the squash-merge and missed it (re-applied on the feature branch, both handler lines). Auto-merge then fired with NO human approval — `require_extra_approval_for_unattributed_changes` never actually bit for these user-authored commits. `gh` CLI has two accounts (`amfshr`, `alexandermfisher`) — must be switched to `alexandermfisher` for PR ops. #11 spec (`plan.md`), glossary, board, and context docs all repointed to the final names. PR #80 MERGED (squash, 1c34221); `feature/domain-model-mapping` rebased onto it and pushed. **Next: hand the spec's Implementer Kickoff Prompt to the implementing model, `/check`, PR.**

---

## 🎯 Current Project Status

### Phase
- [x] Project initialization
- [x] Monzo OAuth integration (Phase 0 - Basic testing) ✅
- [x] Project restructure to mono-repo ✅
- [x] Security Architecture Planning ✅
- [x] **Phase 1: User Authentication** ✅ COMPLETE!
- [x] **Unit Tests** ✅ 485 tests passing
- [x] **Integration Tests** ✅ 40+ tests passing
- [x] **CI/CD Pipeline** ✅ GitHub Actions
- [x] **Security Scanning** ✅ CodeQL
- [x] **Branch Protection** ✅ Configured
- [x] **Phase 2: Monzo Token Persistence** ✅ COMPLETE (all phases A-E)
- [x] **Email Service** ✅ COMPLETE (Resend SMTP)
- [x] **Input Validation Hardening** ✅ COMPLETE (516 tests)
- [x] **Phase 3: Token Auto-Refresh** ✅ COMPLETE (April 2026)
- [x] **Phase 4: Transaction Sync** ✅ COMPLETE & MERGED (#55, June 2026) — windowed backfill, delta job, SCA-resume, cursor fix
- [x] **Package & groupId rename** `dev.amf` → `dev.amfshr` ✅ MERGED (#61, June 2026)
- [x] **Multi-module Maven restructure** ✅ MERGED (#67, July 2026) — `BankClient` contract + `monzo-client` jar extraction
- [ ] #10 Bank-client modules (renames + contract additions + hardening) — next
- [ ] #11 Domain model mapping (design done — `.agents/notes/domain-model-design.md`)
- [ ] Phase 5: Webhooks, then budgeting features

### What's Working
- ✅ Mono-repo structure (`backend/`, `frontend/`, `docs/`)
- ✅ Spring Boot 4.0.2 with Java 25
- ✅ Database setup (via compose.yaml)
- ✅ Monzo OAuth flow - tested with ngrok tunnel
- ✅ User Authentication (Magic Links + JWE)
- ✅ Session management (multi-session policy)
- ✅ Structured logging with LogSanitizer
- ✅ **CI/CD Pipeline (GitHub Actions)**
- ✅ **CodeQL Security Scanning**
- ✅ **Branch Protection Rules**
- ✅ **Code Style (Checkstyle 13.0.0)**
- ✅ **EncryptionService (AES-256-GCM)**
- ✅ **MonzoConnection entity & repository**
- ✅ **MonzoConnectionService**
- ✅ **MonzoController & MonzoOAuthService**
- ✅ **MonzoClient with 401 handling**
- ✅ **OAuth state persistence in database**
- ✅ **Email Service via Resend SMTP**
- ✅ **MonzoOAuthFlowIT integration tests (WireMock)**

### Blocking Items
- None currently

---

## 🧠 Key Decisions Made (Recent)

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-02-23 | **Multi-session support (allow concurrent logins)** | Users can be logged in on multiple devices simultaneously, like Facebook/Google |
| 2026-02-08 | **Database-backed OAuth state** | Store state in DB with user association for CSRF + user linking |
| 2026-02-08 | **Consolidated MonzoController** | Single controller for all Monzo endpoints instead of separate OAuth controller |
| 2026-02-08 | **OAuth callback is public** | State token links to user, no need for JWT auth on callback |
| 2026-01-24 | **AES-256-GCM for token encryption** | AEAD provides confidentiality + integrity, NIST standard |
| 2026-01-24 | **Soft delete for MonzoConnection** | Keep encrypted tokens for audit trail |

---

## 📝 Latest Session Notes (2026-03-15 - Final Cleanup)

**Session Summary:**
Merged all features to main, completed dependency updates, cleaned up PRs.

**What Was Merged Today:**
- ✅ PR #25: `feature/monzo-token-persistence` - Complete Monzo OAuth with encrypted tokens
- ✅ PR #26: `feature/email-service` - Real email via Resend SMTP
- ✅ PR #24: `actions/upload-artifact` v6 → v7
- ✅ PR #27, #28: Dependency updates (Spring Boot 4.0.2, checkstyle 13.0.0, nimbus-jose-jwt 10.7, logcaptor 2.12.2)

**Open PRs Remaining:** None

**Skipped:**
- PR #21: testcontainers 2.0.3 (breaking artifact structure changes)

---

## 📝 Previous Session Notes (2026-03-15 - MonzoClient & Documentation)

**Session Summary:**
Created MonzoClient with centralized 401 handling, refactored MonzoOAuthService to delegate to MonzoClient, updated documentation.

**What Was Accomplished:**

### MonzoClient Implementation
Extracted Monzo API communication from MonzoOAuthService into dedicated MonzoClient:

| Method | Purpose |
|--------|---------|
| `exchangeCode(code)` | Exchange OAuth code for tokens |
| `refreshTokens(refreshToken)` | Refresh access token |
| `whoAmI(accessToken)` | Get Monzo user ID |

**401 Handling:** All Monzo API calls now detect 401 responses and throw:
```
MONZO_CONNECTION_REVOKED: "Your Monzo connection has been revoked. Please reconnect."
```

### Refactored MonzoOAuthService
- Now delegates to MonzoClient for API calls
- Cleaner separation: OAuth orchestration vs API communication
- Prepares for future resilience features (connection pooling, retries)

### Updated tasks.md
- Expanded "Repository Layer Refactoring" → "Code Structure Refactoring"
- Added "MonzoClient Resilience" to Backlog (connection pooling, timeouts, retries)

### Updated Documentation
- `docs/features/MONZO-TOKEN-PERSISTENCE.md` - Marked as COMPLETE ✅
- `docs/diagrams/monzo-oauth-flow.md` - Added MonzoClient to components table

### Test Results
- **485 tests pass** (no changes needed to integration tests)
- MonzoOAuthServiceTest updated to mock MonzoClient

**Files Created/Modified:**
```
NEW:
backend/src/main/java/dev/amf/budgeteer/service/MonzoClient.java

MODIFIED:
backend/src/main/java/dev/amf/budgeteer/service/MonzoOAuthService.java
backend/src/main/java/dev/amf/budgeteer/api/common/ErrorCode.java (MONZO_CONNECTION_REVOKED)
backend/src/test/java/dev/amf/budgeteer/service/MonzoOAuthServiceTest.java
docs/features/MONZO-TOKEN-PERSISTENCE.md
docs/diagrams/monzo-oauth-flow.md
.cline/tasks.md
```

---

## 📝 Previous Session Notes (2026-03-08 - SQL Fixes & OAuth Testing Plan)

**Session Summary:**
Fixed SQL query column mismatches, clarified OAuth testing strategy, created comprehensive testing plan for user denial scenarios, and updated project documentation.

**What Was Accomplished:**

### SQL Script Fixes
Fixed column name mismatches in `scripts/sql/manual-testing.sql`:

| Table | Wrong Column | Correct Column |
|-------|--------------|----------------|
| `magic_link_tokens` | `used` (BOOLEAN) | `used_at` (TIMESTAMP) |
| `app_refresh_tokens` | `revoked` (BOOLEAN) | `revoked_at` (TIMESTAMP) |
| `oauth_states` | `used` (BOOLEAN) | Correct - no change needed |

### OAuth Testing Strategy Clarified
- **Integration tests (WireMock)**: Cover all error scenarios automatically
- **Manual testing**: Only for happy path + scenarios requiring real Monzo
- **Postman collection**: Focus on testable flows only

### Created OAuth Testing Plan Document
**Location:** `docs/features/MONZO-OAUTH-TESTING-PLAN.md`

Comprehensive guide covering:
- Monzo caches OAuth approvals (must revoke first to test denial)
- State expires after 10 minutes
- Frontend redirect requirements for proper UX
- Testing checklists before/after frontend

### Added Future Work Item
**New Icebox Task:** `feature/oauth-frontend-redirect` (P2)
- Add `app.frontend.base-url` property
- Modify callback to redirect instead of returning JSON
- Handle success/error/expired/invalid redirects

### Files Created/Modified

**New Files:**
```
docs/features/MONZO-OAUTH-TESTING-PLAN.md   # Comprehensive OAuth testing guide
```

**Modified Files:**
```
scripts/sql/manual-testing.sql   # Fixed column names
.cline/tasks.md                  # Updated testing status, added future work
.cline/memory.md                 # Session notes
```

---

## 📝 Previous Session Notes (2026-02-23 - Documentation & Backlog Planning)

**Session Summary:**
Reviewed project status, explained session/Monzo architecture, added future tickets to backlog, and created comprehensive manual testing walkthrough guide.

**What Was Accomplished:**

### Session & Multi-Device Architecture Clarification
Documented how sessions and Monzo connections work:

| Aspect | Implementation |
|--------|----------------|
| **Session Model** | Multi-session (user can be logged in on multiple devices) |
| **Session Storage** | `AppRefreshToken` in database per device |
| **Monzo Connection** | User-scoped, not session-scoped (shared across all user's sessions) |
| **Token Rotation** | On refresh, old token revoked, new token issued |

**Key Architecture Points:**
- Each login creates a new `AppRefreshToken` - no limit enforced
- All sessions for a user share the same `MonzoConnection`
- `revokeAllSessions(user)` exists for "logout everywhere"
- No race condition issues for Monzo since tokens are user-level

### New Tickets Added to Icebox

| Ticket | Priority | Purpose |
|--------|----------|---------|
| 🔒 Session Management Enhancements | P3 | Device limits (default: 2), auto-revoke oldest |
| 🛡️ Input Validation & Robustness | P3 | IP/email/user-agent validation value objects |
| ⚡ Race Condition Management | P2 | Optimistic locking, token refresh concurrency |
| 📦 Repository Layer Refactoring | P3 | Move repositories out of `domain/` to `repository/` package |

### Created Manual Testing Walkthrough Guide
**Location:** `.notes/MANUAL-TESTING-WALKTHROUGH.md`

Comprehensive guide covering:
- 5 test suites (Health, Auth, Sessions, Monzo OAuth, Error Handling)
- Multi-session testing with curl/Postman
- Database verification queries
- Troubleshooting section
- Quick smoke test checklist (~2 min)
- Full regression test checklist (~15 min)

### Files Created/Modified

**New Files:**
```
.notes/MANUAL-TESTING-WALKTHROUGH.md   # Comprehensive testing guide
```

**Modified Files:**
```
.cline/tasks.md                        # Added 4 new Icebox tickets
.cline/memory.md                       # Session notes
```

---

## 📝 Previous Session Notes (2026-02-15 - Phase E Progress)

**Session Summary:**
Completed integration tests, manual testing of OAuth flow, and created @CurrentUser annotation system for clean controller injection.

**What Was Accomplished:**

### @CurrentUser Annotation System
Created a clean pattern for injecting authenticated user into controller methods:

| Component | File | Purpose |
|-----------|------|---------|
| `@CurrentUser` | `CurrentUser.java` | Inject full `User` entity |
| `@CurrentUserId` | `CurrentUserId.java` | Inject just `UUID` (more efficient) |
| `CurrentUserArgumentResolver` | Resolves both annotations from `JweAuthentication` |
| `WebMvcConfig` | Registers the resolver with Spring MVC |

**Usage:**
```java
@GetMapping("/status")
public ResponseEntity<...> getStatus(@CurrentUser User user) {
    // user guaranteed non-null, loaded from DB
}

@GetMapping("/connections")
public ResponseEntity<...> list(@CurrentUserId UUID userId) {
    // just the ID, no DB lookup
}
```

### Integration Tests Completed
- `OAuthStateRepositoryIT.java` - 15 tests
- `MonzoConnectionRepositoryIT.java` - 25 tests

### Manual Testing ✅ COMPLETE
Successfully tested full OAuth flow:
1. Login via magic link → JWT token
2. POST /api/monzo/connect → Auth URL
3. Browser auth on Monzo → Connection created
4. Verified connection stored in database

**Connection stored:**
| Field | Value |
|-------|-------|
| Connection ID | `7f7d8b1e-9caa-45fa-8cc6-6177d430c6a6` |
| Monzo User | `user_00009mgUQx8bws3oH7Eooz` |
| Token Expires | ~30 hours from connection |

### Test Fixes
Added `@MockitoBean AuthService authService` to:
- `DevAuthControllerTest.java`
- `HealthControllerTest.java`

Required because `CurrentUserArgumentResolver` is a `@Component` that needs `AuthService`.

### Files Created/Modified

**New Files:**
```
backend/src/main/java/dev/amf/budgeteer/security/
├── CurrentUser.java
├── CurrentUserId.java
└── CurrentUserArgumentResolver.java
backend/src/main/java/dev/amf/budgeteer/config/
└── WebMvcConfig.java
backend/src/test/java/dev/amf/budgeteer/integration/repository/
├── OAuthStateRepositoryIT.java
└── MonzoConnectionRepositoryIT.java
```

**Modified Files:**
- `MonzoController.java` - Refactored to use `@CurrentUser`
- `MonzoControllerTest.java` - Updated for new injection
- `DevAuthControllerTest.java` - Added AuthService mock
- `HealthControllerTest.java` - Added AuthService mock
- `.env` - Fixed redirect URI path

---

## 📝 Previous Session Notes (2026-02-08 - Phase D Complete)

**Session Summary:**
Completed Phase D (API Layer) of Monzo Token Persistence. Created MonzoController, MonzoOAuthService, and OAuth state persistence. All 38 new tests pass.

**What Was Accomplished:**

### Phase D: API Layer ✅ COMPLETE

| Component | File | Tests |
|-----------|------|-------|
| Migration | `V6__create_oauth_states.sql` | - |
| Entity | `OAuthState.java` | - |
| Repository | `OAuthStateRepository.java` | - |
| Service | `MonzoOAuthService.java` | 16 tests |
| Controller | `MonzoController.java` | 22 tests |
| DTO | `MonzoConnectionResponse.java` | - |
| DTO | `MonzoConnectInitResponse.java` | - |
| Updated | `ErrorCode.java` (new OAuth codes) | - |
| Updated | `SecurityConfig.java` | - |

**New Endpoints:**

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/api/monzo/connect` | ✅ Required | Redirect to Monzo OAuth |
| POST | `/api/monzo/connect` | ✅ Required | Get auth URL as JSON (for SPAs) |
| GET | `/api/monzo/callback` | ❌ Public | OAuth callback (state validates user) |
| GET | `/api/monzo/connections` | ✅ Required | List user's connections |
| GET | `/api/monzo/connections/{id}` | ✅ Required | Get connection details |
| DELETE | `/api/monzo/connections/{id}` | ✅ Required | Disconnect (soft delete) |
| GET | `/api/monzo/status` | ✅ Required | Quick connection status check |

**Key Implementation Details:**

1. **OAuth State in Database**: Instead of in-memory state, OAuth state tokens are stored in `oauth_states` table with:
   - User association (links callback to authenticated user)
   - Expiration (10 minutes default)
   - Used flag (prevents replay attacks)
   - CSRF protection via unique random token

2. **Flow**:
   - User (authenticated) → `POST /api/monzo/connect` → generates state, saves to DB, returns Monzo auth URL
   - User approves on Monzo → redirected to `GET /api/monzo/callback?code=...&state=...`
   - Callback: verify state from DB → get user → exchange code → encrypt & store tokens → return connection details

3. **Deleted Files**:
   - `MonzoOAuthController.java` (replaced by consolidated `MonzoController`)
   - `MonzoOAuthControllerTest.java` (replaced by `MonzoControllerTest`)

**Files Created:**
```
backend/src/main/java/dev/amf/budgeteer/
├── api/monzo/
│   ├── MonzoController.java
│   └── dto/
│       ├── package-info.java
│       ├── MonzoConnectionResponse.java
│       └── MonzoConnectInitResponse.java
├── domain/oauth/
│   ├── package-info.java
│   ├── OAuthState.java
│   └── OAuthStateRepository.java
├── service/
│   └── MonzoOAuthService.java
backend/src/main/resources/db/migration/
└── V6__create_oauth_states.sql
backend/src/test/java/dev/amf/budgeteer/
├── api/monzo/
│   └── MonzoControllerTest.java (22 tests)
└── service/
    └── MonzoOAuthServiceTest.java (16 tests)
```

---

## 🔜 What's Next: Phase E (Testing & Polish)

### Unit Tests ✅ DONE
All Phase D unit tests complete and passing:
- `MonzoControllerTest.java` - 22 tests
- `MonzoOAuthServiceTest.java` - 16 tests

### Integration Tests ⏳ NEEDED

| Test Class | Purpose | Status |
|------------|---------|--------|
| `OAuthStateRepositoryIT.java` | Test OAuthState persistence | ❌ To Do |
| `MonzoConnectionRepositoryIT.java` | Test MonzoConnection persistence | ❌ To Do |
| `MonzoOAuthFlowIT.java` | Test full OAuth flow (mocked Monzo) | ❌ To Do |

**Integration Test Scenarios:**
1. **OAuthState Repository:**
   - Save and retrieve state
   - Find by state token
   - Delete expired states
   - State expiration works correctly

2. **MonzoConnection Repository:**
   - Create connection with encrypted tokens
   - Find active connections by user
   - Soft delete (disconnect)
   - Token update for refresh

3. **OAuth Flow (End-to-End):**
   - Authenticated user initiates OAuth → state saved
   - Callback with valid state → tokens stored, connection created
   - Callback with invalid/expired/used state → error returned
   - List connections returns user's connections only
   - Disconnect soft-deletes connection

### Manual Testing ⏳ NEEDED

**Pre-requisites:**
- [ ] ngrok tunnel running
- [ ] Monzo app credentials configured
- [ ] Local database running
- [ ] `MONZO_ENCRYPTION_KEY` set

**Test Scenarios:**

| # | Scenario | Steps | Expected Result |
|---|----------|-------|-----------------|
| 1 | **Login & Get Token** | Login via magic link, get access token | JWT access token in cookie |
| 2 | **Initiate OAuth** | `POST /api/monzo/connect` with auth | Returns Monzo auth URL |
| 3 | **Complete OAuth** | Follow URL, approve in Monzo | Redirected to callback, connection created |
| 4 | **List Connections** | `GET /api/monzo/connections` | Returns 1 connection |
| 5 | **Check Status** | `GET /api/monzo/status` | `connected: true, count: 1` |
| 6 | **Disconnect** | `DELETE /api/monzo/connections/{id}` | 204, connection soft-deleted |
| 7 | **Verify Disconnected** | `GET /api/monzo/status` | `connected: false, count: 0` |
| 8 | **Reconnect** | Repeat OAuth flow | New connection created |
| 9 | **Invalid State** | Tamper with state param | 400 OAUTH_STATE_INVALID |
| 10 | **Expired State** | Wait 10+ mins, use callback | 400 OAUTH_STATE_EXPIRED |

---

## 📊 Project Metrics

| Metric | Value |
|--------|-------|
| Total Unit Tests | 350+ |
| Phase D Tests | 38 |
| Integration Tests | 35+ |
| Flyway Migrations | 6 |
| Security Findings | 0 |

---

## 💡 Quick Commands

```bash
# Run all tests
cd backend && mvn test

# Run Phase D tests only
mvn test -Dtest="MonzoOAuthServiceTest,MonzoControllerTest"

# Run integration tests
mvn test -Dtest="*IT"

# Start dev environment
./scripts/dev.sh start

# Check status
./scripts/dev.sh status
```

---

## 📚 Quick References

| Resource | Location |
|----------|----------|
| **📋 Task Board** | `.cline/tasks.md` |
| **🔐 Encryption Design** | `docs/features/ENCRYPTION.md` |
| **🔄 Token Persistence** | `docs/features/MONZO-TOKEN-PERSISTENCE.md` |
| **🔐 Security Design** | `docs/SECURITY-ARCHITECTURE.md` |
| **🔄 CI/CD Docs** | `docs/CI-CD.md` |
| **🧪 Testing Guide** | `docs/TESTING.md` |

---

## 🔜 NEXT SESSION: Outstanding Items

### 1. Create `MonzoOAuthFlowIT.java` Integration Test
**Location:** `backend/src/test/java/dev/amf/budgeteer/integration/MonzoOAuthFlowIT.java`

This tests the **full HTTP flow** (not just repository persistence):

| Test Case | Description |
|-----------|-------------|
| `initiateOAuth_savesState` | POST /api/monzo/connect → state saved to DB |
| `validCallback_createsConnection` | Callback with valid state + mocked Monzo → connection created |
| `invalidState_returns400` | Callback with wrong state → OAUTH_STATE_INVALID |
| `expiredState_returns400` | Callback with expired state (>10 min) → OAUTH_STATE_EXPIRED |
| `usedState_returns400` | Callback with already-used state → OAUTH_STATE_INVALID |
| `listConnections_returnsOnlyUsersConnections` | User A can't see User B's connections |

**Implementation Notes:**
- Use WireMock or MockServer to mock Monzo API responses
- Extend `AbstractPostgresIntegrationTest` for real DB
- Mock the token exchange endpoint to return fake tokens
- Mock /ping/whoami to return fake Monzo user ID

### 2. Additional Manual Testing
| # | Scenario | Status |
|---|----------|--------|
| 6 | DELETE /api/monzo/connections/{id} | ❌ |
| 7 | GET /api/monzo/status (after disconnect) | ❌ |
| 8 | Reconnect (repeat OAuth) | ❌ |
| 10 | Use state after 10+ mins (expiry) | ❌ |

### 3. Documentation
- [ ] Update `docs/features/MONZO-TOKEN-PERSISTENCE.md` with implementation notes

### 4. Git Actions
- [ ] Push `feature/monzo-token-persistence` branch to GitHub
- [ ] Create PR to merge to `main`
- [ ] CI should pass

---

*Remember: Update this file at the end of each significant work session!*
