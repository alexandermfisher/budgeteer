# Security Headers & Hardening

> **Priority:** P2 | **Estimate:** 0.5 days | **Status:** Done | **Must complete before production**

## Goal

Harden the HTTP layer with standard security headers and tighten CORS/error handling for production readiness.

## Scope

- [x] HSTS (`Strict-Transport-Security`)
- [x] Content Security Policy (`Content-Security-Policy`)
- [x] Clickjacking protection (`X-Frame-Options: DENY`) — was already in place
- [x] MIME sniffing (`X-Content-Type-Options: nosniff`) — was already in place
- [x] CORS configuration — lock down allowed origins per environment
- [x] Hide error detail in prod (no stack traces in API responses) — also fixed silent bug: was using `spring.web.error.*` prefix (ignored); corrected to `server.error.*`
- [ ] `Referrer-Policy` — deferred to frontend phase
- [ ] `Permissions-Policy` — deferred to frontend phase

## Notes

- `style-src 'unsafe-inline'` in the CSP is temporary for Tailwind. Remove when switching to a build-time CSS output.
- When building the Vite frontend, configure the Vite proxy to forward `/api` → `http://localhost:8080` rather than relying on CORS for the dev loop.
- Educational reference: `.agents/notes/security-headers-explained.md`
