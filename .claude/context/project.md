# Project Status

## What It Is

Budgeteer is a personal finance app that syncs with Monzo via OAuth, stores and encrypts tokens, and will provide transaction sync, categorisation, and budgeting features. Solo project by @alexandermfisher.

## Current Phase (March 2026)

**Phase 3 — Monzo Token Auto-Refresh** is next.

Phase 1 (Auth) and Phase 2 (Token Persistence) are fully complete and merged.

## Completed

- [x] Project setup, CI/CD (GitHub Actions), CodeQL, Checkstyle, Dependabot
- [x] Branch protection on `main` (PRs + CI required)
- [x] PostgreSQL + Flyway (6 migrations, V1–V6)
- [x] Magic-link passwordless auth
- [x] JWE session tokens (15m access / 7d refresh, HttpOnly cookies)
- [x] Multi-session support (concurrent logins across devices)
- [x] Monzo OAuth flow (state token CSRF protection, database-backed)
- [x] Monzo token persistence (AES-256-GCM encrypted in `monzo_connections`)
- [x] Structured request logging + LogSanitizer (no PII/tokens in logs)
- [x] Email service via Resend SMTP (magic link delivery)
- [x] 485+ unit tests, 40+ integration tests (Testcontainers)

## Backlog (in priority order)

1. Monzo token auto-refresh (`MonzoTokenRefreshService`)
2. Transaction sync — initial backfill (must run within 5 min of OAuth)
3. Transaction sync — delta / nightly batch job
4. Webhook ingestion (real-time transactions via Cloudflare Tunnel)
5. Budgeting / analytics features
6. Frontend UI (React or Vue — not decided)

## Key Docs

> Full docs index: `docs/README.md`

- `docs/architecture/` — tech design decisions
- `docs/features/` — per-feature documentation
- `docs/testing/` — test strategy and manual test scenarios
- `CHANGELOG.md` — version history
