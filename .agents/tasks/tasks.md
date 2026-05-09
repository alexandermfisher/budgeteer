# Budgeteer Task Board

> Kanban index for solo development. Detailed plans live in subfolders — link provided where one exists.
>
> **Legend:** 🚀 In Progress | 📋 Queue | 🗂️ Backlog | ✅ Done | 🧊 Icebox

---

## 🚀 In Progress

| # | Task | Priority | Estimate | Plan |
|---|------|----------|----------|------|
| 4 | 📊 Phase 4: Transaction Sync | 🟡 P2 | 2–3 days | [plan](transaction-sync/plan.md) |

---

## 📋 Queue (Next Up)

| # | Task | Priority | Estimate | Plan |
|---|------|----------|----------|------|
| 5 | 🪝 Phase 5: Webhooks | 🟢 P3 | TBD | [plan](webhooks/plan.md) |

---

## 🗂️ Backlog

*Pull into Queue (and create a subfolder) when ready to start.*

| Feature | Priority | Effort | Notes |
|---------|----------|--------|-------|
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

### May 2026
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

*Last updated: 2026-05-09 — Transaction Sync in progress*
