# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Logging & Observability Infrastructure** ✅ COMPLETE (January 2026)
  - RequestLoggingFilter with X-Request-ID correlation headers
  - Sensitive data masking (tokens, OAuth codes, state params)
  - Structured logging in all services and controllers (AuthController, AuthService, etc.)
  - Profile-specific Logback configuration (dev=console, prod=JSON file)
  - Spring Boot Actuator endpoints (/actuator/health, /actuator/info)
  - JSpecify @NullMarked null-safety annotations (12 packages)
  - RequestLoggingFilterTest unit tests (8 tests with LogCaptor)
  - docs/features/LOGGING.md documentation
- **New task board sections** for future work
  - Security Headers & Hardening task (P2)
  - Request Correlation & Tracing task (P3)
  - Email Service Implementation task (P2)
- Integration tests for authentication flow with Testcontainers PostgreSQL (35 tests total)
  - `AuthFlowIT` (19 tests) - Complete magic link authentication flow
  - `SessionManagementIT` (16 tests) - Session management edge cases, token rotation, multi-device handling
  - `TestDataFactory` - Test data builder for integration tests
  - Testcontainers singleton pattern for shared PostgreSQL container
  - HikariCP connection pooling configuration for tests
  - Global testcontainers configuration (`~/.testcontainers.properties`)

### Changed
- **Null-safety refactor**: Use Java 16+ pattern matching for `instanceof` in `AuthController.me()`
- Removed redundant null checks (instanceof already handles null safely)

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
