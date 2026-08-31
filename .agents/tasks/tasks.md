# Budgeteer Task Board

> Kanban index for solo development. Detailed plans live in subfolders — link provided where one exists.
>
> **Legend:** 🚀 In Progress | 📋 Queue | 🗂️ Backlog | ✅ Done | 🧊 Icebox

---

## 🚀 In Progress

| # | Task | Priority | Estimate | Plan |
|---|------|----------|----------|------|
| 11 | 🧱 Domain Model Mapping — raw→domain ingest + first product endpoints. **Spec ready** (grilled 2026-08-22, implementation-ready `plan.md`): V11–V13 migrations, encrypted raw capture, `MonzoIngestor` + `IngestService` (cursor on raw `updated_at`), chained sync→ingest→balance run, `GET /api/v1/accounts` + `/accounts/{id}/summary` + `/transactions` (paged). Hand the plan's Implementer Kickoff Prompt to the implementing model. Unblocks all budgeting features. Branch: `feature/domain-model-mapping` | 🟡 P2 | 2–3d | [plan](open/domain-model-mapping/plan.md) |

---

## 📋 Queue (Next Up)

> Execution order: **#11 → #5 → TrueLayer smoke test** (#12 done 2026-08-31, PR #85 — the
> contract #11 builds on is final: `Sourced<T>` envelopes + sealed `SyncPosition`).
> #5 sits behind #11 so webhook payloads
> drop into an existing raw→domain ingest pipeline instead of being wired to the raw-only world
> and reworked later. The TrueLayer Console signup + own-account smoke test is deliberately
> deferred until #11 is done (decided 2026-08-17).

| # | Task | Priority | Estimate | Plan |
|---|------|----------|----------|------|
| 5 | 🪝 Phase 5: Webhooks — after #11: a webhook becomes a second trigger into the existing raw→domain pipeline + near-real-time balance refresh | 🟢 P3 | TBD | [plan](open/webhooks/plan.md) |

---

## 🗂️ Backlog

*Pull into Queue (and create a subfolder) when ready to start.*

| Feature | Priority | Effort | Notes |
|---------|----------|--------|-------|
| 📣 Event-driven post-sync hooks | P3 | 0.5d | [plan](closed/oauth-callback-events/plan.md) — `BackfillCompletedEvent` / `BackfillPausedEvent` + listeners (email, domain mapping, webhook registration). Natural fit alongside #11's `DomainMappingJob` |
| 🏦 TrueLayer Integration (Lloyds/HSBC/Barclays) | P2 | 3–4d | [plan](open/truelayer-integration/plan.md) — add `provider-truelayer` jar as a 2nd implementation of the capability contracts (`ProviderConnectionAuth` + `AccountsCapability`/`BalanceCapability`/`TransactionsCapability`, split in PR #84; cards/standing-orders/direct-debits land as further capability interfaces), copying the `provider-monzo` template from #10. Interface already validated against TrueLayer's Data API in the #6 plan. **Aug 2026 re-check:** Data API still active (now positioned as an "add-on" product — mild vendor-risk signal); Console signup is self-serve, sandbox free, live own-account testing looks viable for a solo dev — **first action when picked up: Console signup + live smoke test (deferred until after #11)**. Token model: 90-day consent, reconfirmation-of-consent renewal, refresh token must be used within a 30-day sliding window. Transactions are date-windowed (`from`/`to`), no page cursor → impl returns null `nextCursor`. ⚠️ plan.md predates the multi-module split (BankAdapter pattern, old paths, V7 migration) — rewrite around the provider contract before build. Provider-contract rename (PR #80) + capability split (PR #84) already executed: jar lands as `provider-truelayer` implementing the capability interfaces. ⚠️ Delta sync: after #12, the fetch start is the sealed `SyncPosition` (`FromTime` \| `AfterTransaction` \| `NextPage`) on `TransactionsCapability.getTransactions` — TrueLayer's impl must switch exhaustively and **throw on `AfterTransaction`** (no id-based deltas). This task must add the time-window delta fallback (`FromTime(last synced − overlap)`, idempotent upserts) incl. the persisted last-synced timestamp (column + migration) and the routing in `TransactionSyncService`. Future payments = separate `PaymentInitiationProvider` interface; one TrueLayer class implements both. ⚠️ Sync-layer generalisation (Alexander, 2026-08-31): everything in `service/monzo/` that isn't OAuth-specific — `TransactionSyncJob`, `TransactionSyncService`, `TransactionSyncEventListener` + `MonzoConnectionCreatedEvent` — is Monzo-shaped and must generalise here, following the `IngestService`/`ProviderIngestor` orchestrator-plus-strategy pattern from #11 (e.g. generic `ProviderConnectionCreatedEvent(provider, connectionId)` + per-provider sync strategy; listeners out of the monzo subpackage) |
| 🧩 Per-page backfill commits (true mid-window resume) | P3 | 0.5d | Backfill commits one `TransactionTemplate` tx per ≤350-day window; an SCA 403 mid-window rolls the whole window back, so resume re-fetches it. `backfill_progress_cursor` is written per page but rolled back with the window — it never actually resumes mid-window. Commit each page (`REQUIRES_NEW`) so partial progress in a large window survives re-auth. Only bites if a single >90-day-old window can't be pulled within one 5-min SCA budget — low urgency for personal accounts |
| 🔌 REST Client Refactoring & Config Consolidation | P2 | 1.5–2d | [plan](closed/rest-client-refactoring/plan.md) — Eliminate config sprawl, create `BankRestClient` base class, organize under `config/clients/` & `config/properties/`. Foundation for TrueLayer. Phase: after transaction sync + webhooks |
| 🔄 MonzoClient Resilience | P3 | 0.5d | Connection pooling, timeouts, retries, circuit breaker |
| 🔐 WebAuthn/Passkey Authentication | P2 | 2d | Touch ID / biometric login for fast re-auth |
| Monitoring Infrastructure | P3 | 0.5d | Prometheus/Grafana on NUC |
| Request Correlation | P3 | 0.25d | Trace IDs to external APIs |
| Frontend OAuth Redirects | P2 | 0.5d | When frontend exists |
| Architecture Diagrams | P3 | 0.5d | Mermaid diagrams for docs |
| Branch Protection | P2 | 0.5h | GitHub settings |
| Dockerfile | P2 | 0.5d | For deployment |
| NUC Deployment | P2 | 1–2d | Domain, Cloudflare, deploy |
| Frontend | P2 | TBD | Framework TBD (React/Vue/HTMX) |
| 🔒 Remaining Security Headers | P3 | 0.25d | `Referrer-Policy: strict-origin-when-cross-origin` + `Permissions-Policy: camera=(), microphone=(), geolocation=()` — defer until frontend build; also configure Vite proxy (`/api` → `localhost:8080`) to avoid CORS/SameSite issues in dev |

---

## 🧊 Icebox

*Maybe later — not prioritised.*

| Idea | Notes |
|------|-------|
| Session Management Enhancements | Device limits, named sessions |
| Race Condition Handling | Optimistic locking for token refresh |
| Mobile App | React Native |
| Budget Alerts | Notifications |
| Spending Predictions | ML |
| Multi-user Support | Admin dashboard |
| Export CSV/PDF | Reports |
| Recurring Payment Detection | Auto-categorisation |
| Custom Category Rules | Rules engine |
| Password Auth | Alternative to magic links |
| Social OAuth | Google, GitHub login |
| 2FA/MFA | Extra security |

---

## ✅ Done

### August 2026
- [x] **#12 Provider Contract Hardening** (PR #85, merged 2026-08-31) — sealed `SyncPosition`
      (`FromTime` | `AfterTransaction` | `NextPage`) replaces `getTransactions`' from/to+cursor
      params (design revised in-session from `TransactionsSinceIdCapability` to Alexander's
      polymorphic-position proposal — Monzo's `since` accepts all three shapes; exhaustive
      switch beats silent capability absence); deltaSync seeds `AfterTransaction(lastTransactionId)`
      contractually. `Sourced<T>` raw-JSON envelope (redacting `toString`, PECS `map`) carries
      provenance; `BankTransaction`/`BankAccount` are pure domain values again.
      `feature/domain-model-mapping` rebased on top; #11 spec repointed.
      [plan](closed/provider-contract-hardening/plan.md)
- [x] **Provider-contract rename** (PR #80, 2026-08-24) — pulled forward from "commit 1 of
      TrueLayer" so #11's ingest code is born with the final names. `BankClient` →
      `AccountInformationProvider`, `MonzoBankClient` → `MonzoAccountInformationProvider`,
      exceptions → `ProviderException` family, `MONZO_*` codes → `PROVIDER_*`
      (`MONZO_VERIFICATION_REQUIRED` → `PROVIDER_REAUTH_REQUIRED`), jars → `provider-api` /
      `provider-monzo` with packages aligned (`dev.amfshr.budgeteer.provider[.monzo]`) and
      `provider-api` structured into `model/` + `exception/` subpackages (contracts at root).
      Data records stay `Bank*`. Zero behaviour change; full gate green.

### July 2026
- [x] **#10 Bank-Client Modules** (PR #72, merged 2026-07-06) — module renames
      (`bank-client-api` / `bank-client-monzo` / `budgeteer-server`), contract additions
      (`getBalance`→`BankBalance`, `rawJson` raw-capture on `BankTransaction`/`BankAccount`),
      jar auto-config + test hardening. Template for `bank-client-truelayer`.
      [plan](closed/bank-client-modules/plan.md)
- [x] **Repo tidy-up** (PRs #73/#74, 2026-07-08) — `frontend/` renamed to `budgeteer-web/`;
      `spring-boot:run` scripts fixed for the multi-module reactor
- [x] **Multi-Module Restructure + Monzo Client Extraction** (PR #67, merged) — 3-module reactor
      (`common`, `monzo-client`, `budgeteer-api`); Monzo HTTP client extracted into its own jar
      behind the neutral `BankClient` contract; API services program to the interface (zero Monzo
      type imports); behaviour-preserving (OAuth, refresh, backfill, delta sync all verified);
      Postman live-tested. [plan](closed/multi-module-refactor/plan.md)
- [x] **Module naming scheme decided** (2026-07-05) — `bank-client-api` (contract, `-api` per
      `slf4j-api` convention), `bank-client-monzo` (family-prefix per `spring-data-*`),
      `budgeteer-server` (frees "api" for the contract; pairs with `frontend/`). Renames execute
      as the first commit of #10.
- [x] **Domain model design** (2026-07-05) — full backend domain for the product features
      (accounts, transactions, categories, budgets, virtual pots, reports, settings) at
      `.agents/notes/domain-model-design.md`; drives #11 and the two #10 contract additions.

### June 2026
- [x] **API Endpoint Versioning (`/api/v1`)** (PR #62) — all product endpoints on `/api/v1/auth/...` and `/api/v1/monzo/...`; config, security matchers, cookie paths, magic-link URLs, tests, Postman, docs repointed
- [x] **Package & groupId rename** `dev.amf` → `dev.amfshr` (PR #61) — pure mechanical rename across 145 Java files, `pom.xml` groupId, logback, and 4 properties files. Zero behaviour change. Done before multi-module split so it's a single sweep.
- [x] **Transaction Sync — cursor fix** (PR #55): send the pagination cursor via Monzo's `since` param (Monzo has **no** `since_id`); keep `before` per window so the cursor advances. Fixes the backfill infinite loop. Verified end-to-end against real Monzo (fresh / SCA-resume / restart). *(An earlier attempt, 632cdec, misdiagnosed this as a `since_id`+`before` ordering issue — that's superseded by this fix.)*
- [x] Transaction Sync Hardening (632cdec) — per-window commits (survive stop/SCA), progress API (`GET /api/monzo/sync/progress`), readable logs, Postman updated
- [x] DB credential & Postgres hardening (PR #58); `MonzoConnectionRepositoryIT` flake fix (PR #59)
- [x] Docs/board refresh through Phase 4 (PR #60)

### May 2026
- [x] Phase 4: Transaction Sync — raw Monzo sync (accounts + transactions), backfill post-OAuth, 60-min delta job, 575 tests
- [x] Security Headers & Hardening — HSTS, CSP, CORS, error suppression; fixed `server.error.*` prefix bug; educational doc at `.agents/notes/security-headers-explained.md`

### April 2026
- [x] Phase 3: Token Auto-Refresh — background job + eager inline guard, WireMock IT, `tokenStatus` on status endpoint

### March 2026
- [x] Input Validation Hardening — Bean Validation on all user-input boundaries, IP sanitization (`IpAddressUtil`), `ConstraintViolationException` handler, 516 tests
- [x] Code Structure Refactoring — service subpackages, client/ layer, repository/ separation
- [x] Monzo Token Persistence — all phases complete (PR #25)
- [x] Email Service via Resend SMTP (PR #26)
- [x] Dependency updates: Spring Boot 4.0.2, checkstyle 13.0.0 (PR #28)
- [x] MonzoOAuthFlowIT integration tests with WireMock
- [x] MonzoClient with 401 handling
- [x] @CurrentUser/@CurrentUserId annotations

### January 2026
- [x] Infrastructure: Logging & Observability
- [x] DevOps: CI/CD Pipeline

### December 2025
- [x] Phase 1: User Authentication
- [x] Unit Testing (329 tests)
- [x] Integration Tests (35 tests)

### December 2024
- [x] Project setup
- [x] Mono-repo restructure
- [x] Documentation structure
- [x] Initial Monzo OAuth flow

---

## 📌 Quick Links

| Resource | Location |
|----------|----------|
| Session Memory | `.agents/memory.md` |
| Domain Model Design | `.agents/notes/domain-model-design.md` |
| Manual Testing Guide | `docs/MANUAL-TESTING.md` |
| Monzo OAuth Testing Plan | `docs/features/MONZO-OAUTH-TESTING-PLAN.md` |
| Security Architecture | `docs/SECURITY-ARCHITECTURE.md` |
| Setup Guide | `docs/SETUP.md` |

---

*Last updated: 2026-08-31 — #12 provider-contract hardening executed and merged (PR #85: sealed
`SyncPosition` + `Sourced<T>` envelope; design pivoted from a second capability interface to
Alexander's polymorphic-position proposal). `feature/domain-model-mapping` rebased onto the new
contract, #11 spec current — **next: hand #11's Implementer Kickoff Prompt to the implementing
model.***
