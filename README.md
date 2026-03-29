# Budgeteer

[![Build & Test](https://github.com/alexandermfisher/budgeteer/actions/workflows/ci.yml/badge.svg)](https://github.com/alexandermfisher/budgeteer/actions/workflows/ci.yml)
[![CodeQL](https://github.com/alexandermfisher/budgeteer/actions/workflows/codeql.yml/badge.svg)](https://github.com/alexandermfisher/budgeteer/actions/workflows/codeql.yml)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Private-red)](LICENSE)

A personal budgeting application integrated with the Monzo API to automatically track expenses, categorize transactions, and provide financial insights.

## Project Structure

This is a **mono-repo** containing:

```
budgeteer/
├── backend/          # Spring Boot API (Java 25)
├── frontend/         # Web UI (coming soon)
├── docs/             # Documentation
├── scripts/          # Development scripts
└── compose.yaml      # Docker services
```

## Quick Start

### Prerequisites

- Java 25+ ([SDKMAN](https://sdkman.io/) recommended)
- Maven 3.9+
- Docker & Docker Compose
- [Monzo Developer Account](https://developers.monzo.com/)

### Setup

1. **Clone and configure:**
   ```bash
   git clone https://github.com/alexandermfisher/budgeteer.git
   cd budgeteer
   cp .env.example .env
   # Edit .env with your credentials
   ```

2. **Generate secret keys:**
   ```bash
   openssl rand -base64 32   # JWE_SECRET_KEY
   openssl rand -base64 32   # MONZO_ENCRYPTION_KEY
   # Add both to .env
   ```

3. **Start the database:**
   ```bash
   docker compose up -d
   ```

4. **Run the backend:**
   ```bash
   ./scripts/dev.sh start
   ```

5. **Access the app:**
   - API: http://localhost:8080
   - Health: http://localhost:8080/actuator/health

See [docs/setup/SETUP.md](docs/setup/SETUP.md) for detailed setup instructions.

## Tech Stack

| Component | Technology |
|-----------|------------|
| **Backend** | Spring Boot 4.0.5, Java 25 |
| **Database** | PostgreSQL 16 |
| **Migrations** | Flyway |
| **Authentication** | Passwordless magic links + JWE tokens |
| **Monzo integration** | OAuth 2.0, AES-256-GCM encrypted token storage |
| **Email** | Resend SMTP |
| **Testing** | JUnit 5, Testcontainers 2.x, WireMock |
| **CI/CD** | GitHub Actions |
| **Security scanning** | CodeQL |
| **Frontend** | TBD (React / Vue / HTMX) |

## Authentication

Budgeteer uses a **passwordless authentication** system:

1. User enters email → receives a magic link
2. Magic link validates → JWE access token (15 min) + refresh token (7 days) issued as HttpOnly cookies
3. Multi-session supported — users can be logged in on multiple devices simultaneously
4. Monzo connection is user-scoped and shared across all sessions

See [docs/features/USER-AUTHENTICATION.md](docs/features/USER-AUTHENTICATION.md) for details.

## Documentation

- [Architecture](docs/architecture/ARCHITECTURE.md) — Technical design decisions
- [CI/CD Setup](docs/setup/CI-CD.md) — GitHub Actions pipeline
- [Monzo Auth Flow](docs/architecture/MONZO-AUTH-FLOW.md) — OAuth implementation
- [Security Architecture](docs/architecture/SECURITY-ARCHITECTURE.md) — Security model
- [Setup Guide](docs/setup/SETUP.md) — Development environment
- [Testing Guide](docs/testing/TESTING.md) — Test strategy and conventions
- [Secrets Management](docs/setup/SECRETS-MANAGEMENT.md) — Handling credentials

## Testing

```bash
# Run all tests
cd backend && mvn test

# Unit tests only
mvn test -DexcludedGroups=integration

# Integration tests (requires Docker)
mvn test -Dgroups=integration
```

485+ unit tests, 40+ integration tests.

## CI/CD

Every push and PR runs:

- **Build & Test** — compile, unit tests, integration tests
- **Code Style** — Checkstyle (Google Java Style)
- **Security Scanning** — CodeQL
- **Dependency Updates** — Dependabot (monthly)

## Current Status

**Phases 1 & 2 complete. Phase 3 (token auto-refresh) is next.**

- [x] Project setup & mono-repo structure
- [x] CI/CD pipeline (GitHub Actions + CodeQL)
- [x] Phase 1: User authentication — magic links, JWE tokens, multi-session
- [x] Phase 2: Monzo integration — OAuth 2.0, encrypted token storage, MonzoClient
- [x] Email service (Resend SMTP)
- [x] 485+ tests (unit + integration with Testcontainers + WireMock)
- [ ] Phase 3: Token auto-refresh
- [ ] Phase 4: Transaction sync (backfill + delta)
- [ ] Phase 5: Webhooks
- [ ] Frontend UI

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for git workflow, branch naming, and commit conventions.

## License

Private project — not for distribution.

---

*Built with coffee and frustration at where my money goes*
