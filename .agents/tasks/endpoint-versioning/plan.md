# API Endpoint Versioning (`/v1`)

> **Priority:** 🟡 P2 | **Estimate:** 0.5–1 day | **Status:** Queue | **Branch:** `feat/api-v1-versioning`

## Goal

Introduce URL-path versioning on the public API — `/api/auth/...` → `/api/v1/auth/...` —
so future breaking changes can ship as `/v2` without disrupting existing clients.

## Why now

Cheap to do while there's no frontend and no external consumers. Locks in the convention
before the surface grows. Sequence it **after** the multi-module restructure + source
migration so the controllers are in their final module first.

## Decisions (confirmed 2026-06-20)

- **Versioning style:** URL path, `/api/v1/...` (version segment right after `/api`). Standard, visible, cache-friendly. Header/media-type versioning rejected as overkill for a solo app.
- **Scope:** version **all real endpoints under `/api/v1`, including the OAuth callback** (`/api/v1/monzo/callback`). Rationale: consistency — this is what it should have been from the start — and the Monzo redirect URI has to change for prod anyway (ngrok is dropped), so re-pointing it costs nothing extra.
- **Left unversioned:** `/actuator/**` (operational health/metrics — not a product contract; Spring convention).
- **`/api/dev/**`:** leave unversioned (throwaway dev/testing surface, not a real contract).
- **Knock-on config (must update together):** Monzo Developer Portal redirect URI → `https://<prod-domain>/api/v1/monzo/callback`, plus `MONZO_REDIRECT_URI` in `.env` / `.env.example`. Do this in the same change so the callback never breaks.

## Implementation options

1. **Central prefix via config** — `spring.mvc.servlet.path` or a `PathMatchConfigurer`/`@RequestMapping` prefix applied to versioned controllers only. Cleanest if applied by annotation/package.
2. **Per-controller** — change each `@RequestMapping("/api/...")` → `/api/v1/...`. Explicit but repetitive.

Prefer a single configured prefix scoped to product controllers, leaving dev/actuator/callback paths alone.

## Checklist

- [ ] Apply `/api/v1` to all product controllers (auth, monzo connect/status/connections, **callback**, sync progress, future transactions)
- [ ] Leave `/actuator/**` and `/api/dev/**` unversioned
- [ ] Update Monzo Developer Portal redirect URI → `…/api/v1/monzo/callback` + `MONZO_REDIRECT_URI` in `.env` / `.env.example` (same change)
- [ ] Update integration tests + Postman collections to the `/v1` paths
- [ ] Update `docs/` API references (and the OAuth flow docs that mention the callback path)

## Verification

- [ ] `mvn verify` green; ITs hit `/api/v1/...`
- [ ] Manual: full OAuth + sync flow works end-to-end on the new paths
