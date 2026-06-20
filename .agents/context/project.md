# Project Status

## What It Is

Budgeteer is a personal finance app that syncs with Monzo via OAuth, stores and encrypts tokens, and will provide transaction sync, categorisation, and budgeting features. Solo project by @alexandermfisher.

## Current Phase (June 2026)

**Phase 4 — Transaction Sync is complete and merged** (PR #55). Phases 1–4 are all
done and on `main`. Next up per the board is the multi-module Maven restructure (P1),
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
- [x] Structured request logging + LogSanitizer (no PII/tokens in logs)
- [x] Email service via Resend SMTP (magic link delivery)
- [x] Input validation hardening (Bean Validation on all user-input boundaries, IP sanitization)
- [x] Security hardening: production headers (HSTS/CSP/CORS), no default DB credentials, Postgres bound to localhost + connection audit logging
- [x] ~590 tests (unit + integration, Testcontainers + WireMock)

## Backlog

> The authoritative, prioritised board is `.agents/tasks/tasks.md`. Summary:

1. Multi-module Maven restructure (P1)
2. Phase 5: Webhook ingestion (real-time transactions via Cloudflare Tunnel)
3. OAuth abstraction + TrueLayer (multi-bank) integration
4. Budgeting / analytics features
5. Frontend UI (React / Vue / HTMX — not decided)

## Key Docs

> Full docs index: `docs/README.md`

- `docs/architecture/` — tech design decisions
- `docs/features/` — per-feature documentation
- `docs/testing/` — test strategy and manual test scenarios
- `CHANGELOG.md` — version history
