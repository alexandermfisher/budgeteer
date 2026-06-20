# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Phase 4 — Monzo Transaction Sync** (PR #55, June 2026)
  - Raw account + transaction mirror: `monzo_accounts`, `monzo_transactions` (migrations V7–V10)
  - Async post-OAuth backfill that runs within Monzo's ~5-minute SCA window
  - Windowed historical sync in ≤350-day windows, resumable across SCA re-auth via per-window commits (`backfill_status`, `backfill_progress_at`)
  - 60-minute scheduled delta sync via `last_transaction_id` cursor
  - `GET /api/monzo/sync/progress` — per-account progress + status
  - Dev endpoints: `POST /api/dev/monzo/backfill`, `POST /api/dev/monzo/reset-backfill/{accountId}`
  - `MONZO_SYNC_ERROR` error code
- **Phase 3 — Monzo Token Auto-Refresh** (April 2026) — background refresh job + eager inline guard before Monzo API calls; `tokenStatus` exposed on the status endpoint
- **Production security headers** — HSTS, CSP, CORS, error-response suppression

### Fixed
- **Monzo backfill infinite loop** (PR #55) — the pagination cursor was sent as a non-existent `since_id` query param. Monzo's `since` accepts *either* an RFC3339 timestamp *or* a transaction id; the cursor is now sent via `since`, with `before` retained per window so the cursor advances. Previously, any account with ≥100 transactions in a window looped forever.
- Integration-test flake in `MonzoConnectionRepositoryIT` — cross-class row leakage; now clears the table in `@BeforeEach` (PR #59)

### Security
- Removed default database credentials — `DB_USER` / `DB_PASSWORD` are now required (no `budgeteer:budgeteer` fallback); Postgres bound to `127.0.0.1` only; connection/disconnection audit logging enabled (PR #58)

### Changed
- **Dependency Updates** (March 2026)
  - nimbus-jose-jwt: 10.0.2 → 10.7
  - logcaptor: 2.9.3 → 2.12.2
  - Spring Boot: 4.0.1 → 4.0.2 (parent, testcontainers, config processor)
  - checkstyle: 10.26.1 → 13.0.0
  - actions/upload-artifact: v6 → v7

---

## [0.4.0] - 2026-03-15 (Email Service + Token Persistence Complete)

### Added
- **Email Service via Resend SMTP** (`feature/email-service`)
  - `EmailService` now sends real emails via Resend SMTP
  - `app.email.enabled=true/false` property to toggle
  - Fallback to console logging when disabled
  - Updated `.env.example` with Resend config
  - `scripts/dev.sh` enhancements for email testing

- **MonzoClient with 401 Handling**
  - Centralized Monzo API client extracted from MonzoOAuthService
  - Automatic detection of revoked tokens (401 responses)
  - `MONZO_CONNECTION_REVOKED` error code

- **MonzoOAuthFlowIT Integration Tests with WireMock**
  - Full OAuth flow testing with mocked Monzo API
  - WireMock JSON mappings for token exchange, refresh, whoami
  - 15+ integration test scenarios

- **Phase 2: Monzo Token Persistence - Phase E** (`feature/monzo-token-persistence`)
  - `@CurrentUser` annotation for injecting authenticated User into controllers
  - `@CurrentUserId` annotation for injecting just UUID (more efficient)
  - `CurrentUserArgumentResolver` to resolve annotations from JweAuthentication
  - `WebMvcConfig` to register the argument resolver
  - `OAuthStateRepositoryIT` integration tests (15 tests)
  - `MonzoConnectionRepositoryIT` integration tests (25 tests)
  - `MANUAL-TESTING.md` documentation for OAuth flow testing
  - `manual-testing.sql` helper queries for development

### Changed
- Refactored `MonzoController` to use `@CurrentUser` instead of manual authentication extraction
- Updated controller tests (`MonzoControllerTest`, `DevAuthControllerTest`, `HealthControllerTest`) with AuthService mock

### Fixed
- Redirect URI in `.env` corrected from `/auth/callback` to `/api/monzo/callback`

---

## [0.3.0] - 2026-02-08 (Phase D)

### Added
- **Phase 2: Monzo Token Persistence - API Layer** ✅ COMPLETE
  - `MonzoController` - Consolidated controller for all Monzo endpoints
  - `MonzoOAuthService` - OAuth flow orchestration with database state
  - `OAuthState` entity and `OAuthStateRepository` for CSRF protection
  - `MonzoConnectionResponse` and `MonzoConnectInitResponse` DTOs
  - Database-backed OAuth state with user association, expiration, single-use
  - New Monzo API endpoints:
    - `GET /api/monzo/connect` - Redirect to Monzo OAuth
    - `POST /api/monzo/connect` - Get auth URL as JSON
    - `GET /api/monzo/callback` - OAuth callback (public, state-validated)
    - `GET /api/monzo/connections` - List connections
    - `GET /api/monzo/connections/{id}` - Get connection details
    - `DELETE /api/monzo/connections/{id}` - Disconnect (soft delete)
    - `GET /api/monzo/status` - Quick status check
  - `V6__create_oauth_states.sql` Flyway migration
  - OAuth-specific error codes in `ErrorCode` enum
  - `MonzoControllerTest` (22 tests) and `MonzoOAuthServiceTest` (16 tests)

---

## [0.2.1] - 2026-01-24 (Phases A-C)

### Added
- **Phase 2: Monzo Token Persistence - Foundation** (`feature/monzo-token-persistence`)
  - `EncryptionService` (AES-256-GCM) for secure token storage
  - `EncryptionProperties` configuration
  - `MonzoConnection` entity with encrypted token fields
  - `MonzoConnectionRepository` with user-scoped queries
  - `MonzoConnectionService` for CRUD operations
  - `V5__create_monzo_connections.sql` Flyway migration
  - `docs/features/ENCRYPTION.md` design documentation
  - Unit tests: EncryptionService (24), MonzoConnection (12), MonzoConnectionService (22)

---

## [0.2.0] - 2026-01-23

### Added
- **CI/CD Pipeline Infrastructure** (`chore/ci-cd-setup`)
  - GitHub Actions CI workflow (`.github/workflows/ci.yml`)
    - Automatic build and test on every push
    - Unit tests and integration tests (Testcontainers)
    - Maven dependency caching
    - Test artifact upload on failure
  - CodeQL security scanning (`.github/workflows/codeql.yml`)
    - Weekly scheduled scans
    - Extended security queries
  - Dependabot configuration (`.github/dependabot.yml`)
    - Weekly dependency updates for Maven, GitHub Actions, Docker
    - Grouped updates for Spring and testing dependencies
  - CODEOWNERS file (`.github/CODEOWNERS`)
    - Require code owner approval for all changes
  - Checkstyle integration (`backend/config/checkstyle/checkstyle.xml`)
    - Google Java Style Guide (relaxed)
    - Maven plugin configuration
  - CI/CD documentation (`docs/CI-CD.md`)

## [0.2.0] - 2026-01-23

### Added
- **Logging & Observability Infrastructure** ✅ COMPLETE
  - RequestLoggingFilter with X-Request-ID correlation headers
  - Sensitive data masking (tokens, OAuth codes, state params)
  - Structured logging in all services and controllers (AuthController, AuthService, etc.)
  - Profile-specific Logback configuration (dev=console, prod=JSON file)
  - Spring Boot Actuator endpoints (/actuator/health, /actuator/info, /actuator/prometheus)
  - JSpecify @NullMarked null-safety annotations (12 packages)
  - RequestLoggingFilterTest unit tests (8 tests with LogCaptor)
  - docs/features/LOGGING.md documentation
- Integration tests for authentication flow with Testcontainers PostgreSQL (35 tests total)
  - `AuthFlowIT` (19 tests) - Complete magic link authentication flow
  - `SessionManagementIT` (16 tests) - Session management edge cases, token rotation, multi-device handling
  - `TestDataFactory` - Test data builder for integration tests
  - Testcontainers singleton pattern for shared PostgreSQL container
  - HikariCP connection pooling configuration for tests

### Changed
- **Null-safety refactor**: Use Java 16+ pattern matching for `instanceof` in `AuthController.me()`
- Removed redundant null checks (instanceof already handles null safely)

### Merged
- `feature/logging` branch merged to `main`

## [0.1.0] - 2026-01-10

### Added
- Bearer token and body-based refresh support ([03fafad](https://github.com/alexandermfisher/budgeteer/commit/03fafad))
- Dev tools and health check endpoints ([d1b0699](https://github.com/alexandermfisher/budgeteer/commit/d1b0699))
- Comprehensive testing guide and documentation ([f0be016](https://github.com/alexandermfisher/budgeteer/commit/f0be016))
- Unit test infrastructure and service tests ([9a1affc](https://github.com/alexandermfisher/budgeteer/commit/9a1affc))
- Shared IntelliJ run configuration for local development ([194ab5b](https://github.com/alexandermfisher/budgeteer/commit/194ab5b))
- Single-session policy implementation ([4355361](https://github.com/alexandermfisher/budgeteer/commit/4355361))
- User authentication with magic links and JWE tokens ([5c5a3e6](https://github.com/alexandermfisher/budgeteer/commit/5c5a3e6))
- Spring profiles for dev/prod environments ([217d469](https://github.com/alexandermfisher/budgeteer/commit/217d469))
- Postman collection and SQL queries for auth testing ([6b75fab](https://github.com/alexandermfisher/budgeteer/commit/6b75fab))

### Changed
- Standardized config properties and added SameSite cookies ([466175b](https://github.com/alexandermfisher/budgeteer/commit/466175b))
- Updated dev scripts and cline rules ([85cc19f](https://github.com/alexandermfisher/budgeteer/commit/85cc19f))

### Refactored
- Restructured project to mono-repo with backend, frontend, and docs ([1431a51](https://github.com/alexandermfisher/budgeteer/commit/1431a51))

### Fixed
- (None)

### Internal
- Added .notes/ folder for personal scratch work ([a53968c](https://github.com/alexandermfisher/budgeteer/commit/a53968c))
- Initial commit before mono-repo restructure ([2a7c3f2](https://github.com/alexandermfisher/budgeteer/commit/2a7c3f2))
