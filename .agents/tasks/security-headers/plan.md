# Security Headers & Hardening

> **Priority:** P2 | **Estimate:** 0.5 days | **Status:** Queue | **Must complete before production**

## Goal

Harden the HTTP layer with standard security headers and tighten CORS/error handling for production readiness.

## Scope

- [ ] HSTS (`Strict-Transport-Security`)
- [ ] Content Security Policy (`Content-Security-Policy`)
- [ ] Clickjacking protection (`X-Frame-Options: DENY`)
- [ ] MIME sniffing (`X-Content-Type-Options: nosniff`)
- [ ] CORS configuration — lock down allowed origins per environment
- [ ] Hide error detail in prod (no stack traces in API responses)
