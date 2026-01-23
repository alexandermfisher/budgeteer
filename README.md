# Budgeteer 💰

A personal budgeting application integrated with the Monzo API to automatically track expenses, categorize transactions, and provide financial insights.

## 🏗️ Project Structure

This is a **mono-repo** containing:

```
budgeteer/
├── backend/          # Spring Boot API (Java 25)
├── frontend/         # Web UI (coming soon)
├── docs/             # Documentation
├── scripts/          # Development scripts
└── compose.yaml      # Docker services
```

## ⚡ Quick Start

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

2. **Generate JWE secret key:**
   ```bash
   openssl rand -base64 32
   # Add to .env as JWE_SECRET_KEY=<generated-key>
   ```

3. **Start the database:**
   ```bash
   docker compose up -d
   ```

4. **Run the backend:**
   ```bash
   ./scripts/dev.sh
   ```

5. **Access the app:**
   - API: http://localhost:8080
   - Health: http://localhost:8080/actuator/health
   - Monzo OAuth: http://localhost:8080/auth/connect

See [docs/SETUP.md](docs/SETUP.md) for detailed setup instructions.

## 🔧 Tech Stack

| Component | Technology |
|-----------|------------|
| **Backend** | Spring Boot 3.4, Java 25 |
| **Database** | PostgreSQL 16 |
| **Migrations** | Flyway |
| **Authentication** | Magic Links + JWE Tokens |
| **External API** | Monzo Banking API |
| **CI/CD** | GitHub Actions |
| **Security Scanning** | CodeQL |
| **Frontend** | TBD (React/Vue) |

## 🔐 Authentication

Budgeteer uses a **passwordless authentication** system:

1. User enters email → receives magic link
2. Magic link validates → JWE access token + refresh token issued
3. Tokens stored in HttpOnly cookies
4. Single-session policy (new login revokes existing sessions)

See [docs/features/USER-AUTHENTICATION.md](docs/features/USER-AUTHENTICATION.md) for details.

## 📚 Documentation

- [Architecture](docs/ARCHITECTURE.md) - Technical design decisions
- [CI/CD Setup](docs/CI-CD.md) - GitHub Actions pipeline
- [Monzo Auth Flow](docs/MONZO-AUTH-FLOW.md) - OAuth implementation
- [Security Architecture](docs/SECURITY-ARCHITECTURE.md) - Security model
- [Setup Guide](docs/SETUP.md) - Development environment
- [Testing Guide](docs/TESTING.md) - Test strategy and conventions
- [Secrets Management](docs/SECRETS-MANAGEMENT.md) - Handling credentials

## 🧪 Testing

```bash
# Run all tests
cd backend && mvn test

# Run only unit tests
mvn test -DexcludedGroups=integration

# Run integration tests (requires Docker)
mvn test -Dgroups=integration
```

## 🔄 CI/CD

The project uses GitHub Actions for:

- ✅ **Build & Test** - Runs on every push/PR
- ✅ **Code Style** - Checkstyle enforcement
- ✅ **Security Scanning** - CodeQL analysis
- ✅ **Branch Protection** - Requires passing checks + code review

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for git workflow, branch naming, and commit conventions.

## 📋 Current Status

**Phase:** User Authentication & API Foundation

- [x] Project setup & configuration
- [x] CI/CD pipeline (GitHub Actions)
- [x] Monzo OAuth flow
- [x] User authentication (magic links)
- [x] Session management (JWE tokens)
- [x] Request logging & security
- [ ] Monzo token persistence
- [ ] Transaction sync
- [ ] Budgeting features
- [ ] Frontend UI

## 📄 License

Private project - not for distribution.

---

*Built with ☕ and frustration at where my money goes*
