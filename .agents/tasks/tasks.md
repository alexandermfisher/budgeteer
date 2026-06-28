# Budgeteer Task Board

> Kanban index for solo development. Detailed plans live in subfolders — link provided where one exists.
>
> **Legend:** 🚀 In Progress | 📋 Queue | 🗂️ Backlog | ✅ Done | 🧊 Icebox

---

## 🚀 In Progress

*None — pick from Queue.*

---

## 📋 Queue (Next Up)

> Ordered by execution sequence. The refactor track (rename ✅ #61 → multi-module #6 → versioning ✅ #62) is now down to the single phased effort #6 — old #6 (restructure) and #8 (source migration) are consolidated into it. Then Phase 5.

| # | Task | Priority | Estimate | Plan |
|---|------|----------|----------|------|
| 6 | 🏗️ Multi-Module Restructure + Monzo Client Extraction — 3 modules (`budgeteer-common` / `monzo-client` / `budgeteer-api`); `backend/` → `budgeteer-api/`; Monzo HTTP behind a neutral `BankClient` interface; persistence stays in the API. 4 compile-green phases | 🔴 P1 | ~3.5–4d | [plan](multi-module-refactor/plan.md) |
| 5 | 🪝 Phase 5: Webhooks | 🟢 P3 | TBD | [plan](webhooks/plan.md) |

---

## 🗂️ Backlog

*Pull into Queue (and create a subfolder) when ready to start.*

| Feature | Priority | Effort | Notes |
|---------|----------|--------|-------|
| 🧱 Domain Model Mapping (`monzo_transactions` → unified `transactions`) | P2 | 2–3d | Deferred out of Phase 4 (raw sync only). Add `user_accounts` / `transactions` domain tables, mapping layer, and the public `GET /api/transactions` (or `/v1`) endpoint. Now have real data to design against |
| 📣 Event-driven post-sync hooks | P3 | 0.5d | [plan](oauth-callback-events/plan.md) — `BackfillCompletedEvent` / `BackfillPausedEvent` + listeners (email, domain mapping, webhook registration). Phase: after source migration |
| 🏦 TrueLayer Integration (Lloyds/HSBC/Barclays) | P2 | 3–4d | [plan](truelayer-integration/plan.md) — After #6: add `truelayer-client` jar as a 2nd `BankClient` impl (+ capability interfaces for cards/standing-orders/direct-debits). Interface already validated against TrueLayer's Data API in the #6 plan |
| 🧯 Generalise bank error codes (`MONZO_*` → `BANK_*`) | P3 | 0.5d | After #6. Neutral exceptions (`BankConnectionRevokedException` / `BankReauthRequiredException` / `BankClientException`) currently map to `MONZO_*` `ErrorCode`s in `GlobalExceptionHandler`. Replace with provider-agnostic `BANK_*` codes so a TrueLayer revocation isn't labelled "Monzo". Land with or before TrueLayer |
| 🔌 REST Client Refactoring & Config Consolidation | P2 | 1.5–2d | [plan](rest-client-refactoring/plan.md) — Eliminate config sprawl, create `BankRestClient` base class, organize under `config/clients/` & `config/properties/`. Foundation for TrueLayer. Phase: after transaction sync + webhooks |
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

### June 2026
- [x] **API endpoint versioning** `/api/v1` URL-path versioning (PR #62) — was queued as #9; merged.
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
| Manual Testing Guide | `docs/MANUAL-TESTING.md` |
| Monzo OAuth Testing Plan | `docs/features/MONZO-OAUTH-TESTING-PLAN.md` |
| Security Architecture | `docs/SECURITY-ARCHITECTURE.md` |
| Setup Guide | `docs/SETUP.md` |

---

*Last updated: 2026-06-21 — Package rename `dev.amf` → `dev.amfshr` merged (#61); #6 multi-module Maven restructure is now the head of the Queue*
