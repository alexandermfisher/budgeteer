# Architecture

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.4, Java 25 |
| Database | PostgreSQL 16 (Alpine via Docker) |
| Migrations | Flyway — `backend/src/main/resources/db/migration/` |
| Auth | Magic links + JWE tokens (JOSE library) |
| Encryption | AES-256-GCM (Monzo OAuth tokens at rest) |
| Email | Resend SMTP (`spring-boot-starter-mail`) |
| Testing | JUnit 5, Mockito, Testcontainers (real PG), WireMock |
| CI/CD | GitHub Actions (ci.yml, codeql.yml) |
| External API | Monzo Banking API (OAuth 2.0 confidential client) |

## Package Structure

```
dev.amf.budgeteer/
├── api/              # REST controllers + DTOs, organised by feature
│   ├── auth/         #   magic-link + session endpoints
│   ├── dev/          #   dev-only shortcuts (not in prod)
│   ├── health/       #   /actuator/health wrapper
│   ├── monzo/        #   Monzo OAuth endpoints
│   └── common/       #   ApiResponse, ApiError, GlobalExceptionHandler
├── config/           # Spring @Configuration classes, properties bindings
├── domain/           # JPA entities + repositories, organised by aggregate
│   ├── user/         #   User entity + UserRepository
│   └── session/      #   MagicLinkToken, AppRefreshToken + repos
├── service/          # Business logic
├── security/         # JweAuthenticationFilter, SecurityConfig
├── exception/        # ApiException + error codes
└── util/             # LogSanitizer, etc.
```

## Database Schema (6 migrations)

| Migration | Table | Purpose |
|-----------|-------|---------|
| V1 | — | Baseline placeholder |
| V2 | `users` | UUID PK, email (unique), email_verified |
| V3 | `magic_link_tokens` | SHA-256 hash, expires_at, used_at (replay prevention) |
| V4 | `app_refresh_tokens` | SHA-256 hash, revoked_at, user_agent, ip_address |
| V5 | `monzo_connections` | Encrypted access/refresh tokens, soft-delete via disconnected_at |
| V6 | `oauth_states` | CSRF state tokens for Monzo OAuth, 10-min expiry, used flag |

Next migration will be **V7**.

## Key Architectural Decisions

- **Multi-session**: Users can be logged in on multiple devices simultaneously (like Google/Facebook). `app_refresh_tokens` is a table, not a single column.
- **Hash-only token storage**: Plain tokens are never stored. SHA-256 hashes only. Tokens sent to clients are unhashed.
- **AES-256-GCM for Monzo tokens**: Provides both confidentiality and integrity. Key loaded from `MONZO_ENCRYPTION_KEY` env var.
- **JWE for session tokens**: Stateless access tokens, refresh tokens backed by DB for revocation.
- **Testcontainers for ITs**: Integration tests spin up a real PostgreSQL — no mocking the DB.
- **Database-backed OAuth state**: State tokens linked to `user_id` for CSRF protection + user binding.
- **Soft delete on `monzo_connections`**: `disconnected_at` instead of hard delete, preserves audit trail.

## Environment Profiles

- `dev` — DEBUG logging, show SQL, hot reload via `./scripts/dev.sh`
- `prod` — INFO logging, connection pooling (max 10), batch size 20

Config files: `backend/src/main/resources/application*.properties`
