# Project Status

## What It Is

Budgeteer is a personal finance app that syncs with Monzo via OAuth, stores and encrypts tokens, and will provide transaction sync, categorisation, and budgeting features. Solo project by @amfshr.

## Current Phase (August 2026)

**Provider-contract naming is in place** (2026-08-24): 3-module reactor — `provider-api`
(contract) / `provider-monzo` (impl) / `budgeteer-server` (app). The Monzo HTTP client lives in
its own jar behind neutral per-capability contracts — `ProviderConnectionAuth`,
`AccountsCapability`, `BalanceCapability`, `TransactionsCapability` (PSD2 AIS vocabulary; see
the Provider-vs-Institution glossary in `architecture.md`). Provider-neutral exceptions are
`ProviderException` / `ProviderConnectionRevokedException` / `ProviderReauthRequiredException`,
surfaced as `PROVIDER_*` error codes. Data records stay `Bank*` — they describe the
institution's artifacts. The backend domain design (accounts, transactions, categories, budgets,
virtual pots, reports) is complete at `.agents/notes/domain-model-design.md`.
Next per the board: #11 domain model mapping (spec ready, branch `feature/domain-model-mapping`),
then Phase 5 (webhooks).

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

1. #11 Domain model mapping (P2) — raw → provider-agnostic `user_accounts`/`transactions`,
   ingest pipeline, first product endpoints (spec: `.agents/tasks/open/domain-model-mapping/plan.md`)
2. #5 Phase 5: Webhook ingestion (real-time transactions via Cloudflare Tunnel) — after #11
3. TrueLayer (multi-bank) integration — `provider-truelayer` as 2nd implementation of the provider capability contracts
5. Budgeting / analytics features (categories, budgets, pots, reports — designed, built in slices)
6. Frontend UI (React / Vue / HTMX — not decided)

## Key Docs

> Full docs index: `docs/README.md`

- `docs/architecture/` — tech design decisions
- `docs/features/` — per-feature documentation
- `docs/testing/` — test strategy and manual test scenarios
- `CHANGELOG.md` — version history
