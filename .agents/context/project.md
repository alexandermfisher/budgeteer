# Project Status

## What It Is

Budgeteer is a personal finance app that syncs with Monzo via OAuth, stores and encrypts tokens, and will provide transaction sync, categorisation, and budgeting features. Solo project by @alexandermfisher.

## Current Phase (July 2026)

**Multi-module restructure is merged** (PR #67): 3-module reactor with the Monzo HTTP client
extracted into its own jar behind the neutral `BankClient` contract. Module naming scheme decided
2026-07-05 — `bank-client-api` (contract) / `bank-client-monzo` (impl) / `budgeteer-server` (app);
renames execute as the first commit of task #10. The backend domain design (accounts, transactions,
categories, budgets, virtual pots, reports) is complete at `.agents/notes/domain-model-design.md`.
Next per the board: #10 bank-client modules (renames + contract additions + jar hardening), then
#11 domain model mapping, then Phase 5 (webhooks).

## Completed

- [x] Project setup, CI/CD (GitHub Actions), CodeQL, Checkstyle, Dependabot
- [x] Branch protection on `main` (PRs + CI required)
- [x] PostgreSQL + Flyway (V1–V10 migrations)
- [x] Magic-link passwordless auth
- [x] JWE session tokens (15m access / 7d refresh, HttpOnly cookies)
- [x] Multi-session support (concurrent logins across devices)
- [x] Monzo OAuth flow (state token CSRF protection, database-backed)
- [x] Monzo token persistence (AES-256-GCM encrypted in `monzo_connections`)
- [x] **Phase 3:** Monzo token auto-refresh (background job + eager inline guard, `tokenStatus` on status endpoint)
- [x] **Phase 4:** Transaction sync — async post-OAuth backfill, windowed historical sync (≤350-day windows, resumable across SCA re-auth via per-window commits), 60-min delta job, `GET /api/monzo/sync/progress`
- [x] **Package & groupId rename** `dev.amf` → `dev.amfshr` (PR #61) — 145 Java files, `pom.xml`, logback, 4 properties files; done before multi-module split
- [x] **Multi-module restructure + Monzo client extraction** (PR #67) — 3-module reactor; Monzo HTTP client in its own jar behind the neutral `BankClient` contract; API programs to the interface; behaviour-preserving
- [x] Structured request logging + LogSanitizer (no PII/tokens in logs)
- [x] Email service via Resend SMTP (magic link delivery)
- [x] Input validation hardening (Bean Validation on all user-input boundaries, IP sanitization)
- [x] Security hardening: production headers (HSTS/CSP/CORS), no default DB credentials, Postgres bound to localhost + connection audit logging
- [x] ~590 tests (unit + integration, Testcontainers + WireMock)

## Backlog

> The authoritative, prioritised board is `.agents/tasks/tasks.md`. Summary:

1. #10 Bank-client modules (P1) — module renames (`bank-client-api` / `bank-client-monzo` /
   `budgeteer-server`), contract additions (`getBalance`, `rawJson`), jar auto-config + hardening
2. #11 Domain model mapping (P2) — raw → provider-agnostic `user_accounts`/`transactions`,
   mapping pipeline, first product endpoints (design: `.agents/notes/domain-model-design.md`)
3. #5 Phase 5: Webhook ingestion (real-time transactions via Cloudflare Tunnel) — after #11
4. TrueLayer (multi-bank) integration — `bank-client-truelayer` as 2nd `BankClient` impl
5. Budgeting / analytics features (categories, budgets, pots, reports — designed, built in slices)
6. Frontend UI (React / Vue / HTMX — not decided)

## Key Docs

> Full docs index: `docs/README.md`

- `docs/architecture/` — tech design decisions
- `docs/features/` — per-feature documentation
- `docs/testing/` — test strategy and manual test scenarios
- `CHANGELOG.md` — version history
