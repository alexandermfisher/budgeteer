# API Endpoint Versioning (`/v1`)

> **Priority:** 🟡 P2 | **Estimate:** 0.5–1 day | **Status:** Queue | **Branch:** `feat/api-v1-versioning`

## Goal

Introduce URL-path versioning on the public API — `/api/auth/...` → `/api/v1/auth/...` —
so future breaking changes can ship as `/v2` without disrupting existing clients.

## Why now

Cheap to do while there's no frontend and no external consumers. Locks in the convention
before the surface grows. Sequence it **after** the multi-module restructure + source
migration so the controllers are in their final module first.

## Decisions to confirm

- **Versioning style:** URL path (`/api/v1/...`) — simplest, most visible, cache-friendly. (Alternatives: header/media-type versioning — rejected as overkill for a solo app.)
- **Scope:** version the public/product API. Decide whether `/api/dev/*` (dev-only) and `/actuator/*` stay unversioned (recommended: yes, leave them unversioned).
- **OAuth callback:** `/api/monzo/callback` is registered with Monzo as a redirect URI — if it moves under `/v1`, the Monzo developer portal redirect URI + `.env` `MONZO_REDIRECT_URI` must change too. Consider keeping the callback unversioned to avoid coupling external config to API versions.

## Implementation options

1. **Central prefix via config** — `spring.mvc.servlet.path` or a `PathMatchConfigurer`/`@RequestMapping` prefix applied to versioned controllers only. Cleanest if applied by annotation/package.
2. **Per-controller** — change each `@RequestMapping("/api/...")` → `/api/v1/...`. Explicit but repetitive.

Prefer a single configured prefix scoped to product controllers, leaving dev/actuator/callback paths alone.

## Checklist

- [ ] Decide callback handling (keep `/api/monzo/callback` unversioned — recommended)
- [ ] Apply `/api/v1` to product controllers (auth, monzo status/connections, sync progress, transactions)
- [ ] Update integration tests + Postman collections to the `/v1` paths
- [ ] Update `docs/` API references
- [ ] If callback moves: update Monzo portal redirect URI + `.env.example`

## Verification

- [ ] `mvn verify` green; ITs hit `/api/v1/...`
- [ ] Manual: full OAuth + sync flow works end-to-end on the new paths
