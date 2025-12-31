# Budgeteer 💰

A personal budgeting application integrated with the Monzo API to automatically track expenses, categorize transactions, and provide financial insights.

## 🏗️ Project Structure

This is a **mono-repo** containing:

```
budgeteer/
├── backend/          # Spring Boot API (Java 21)
├── frontend/         # Web UI (coming soon)
├── docs/             # Documentation
├── scripts/          # Development scripts
└── compose.yaml      # Docker services
```

## ⚡ Quick Start

### Prerequisites

- Java 21+ ([SDKMAN](https://sdkman.io/) recommended)
- Maven 3.9+
- Docker & Docker Compose
- [Monzo Developer Account](https://developers.monzo.com/)

### Setup

1. **Clone and configure:**
   ```bash
   git clone https://github.com/yourusername/budgeteer.git
   cd budgeteer
   cp .env.example .env
   # Edit .env with your Monzo credentials
   ```

2. **Start the database:**
   ```bash
   docker compose up -d
   ```

3. **Run the backend:**
   ```bash
   ./scripts/dev.sh
   ```

4. **Access the app:**
   - API: http://localhost:8080
   - OAuth: http://localhost:8080/auth/connect

See [docs/SETUP.md](docs/SETUP.md) for detailed setup instructions.

## 🔧 Tech Stack

| Component | Technology |
|-----------|------------|
| **Backend** | Spring Boot 3.4, Java 21 |
| **Database** | PostgreSQL 16 |
| **Migrations** | Flyway |
| **External API** | Monzo Banking API |
| **Frontend** | TBD (React/Vue) |
| **Infrastructure** | Docker, Cloudflare Tunnel |

## 📚 Documentation

- [Architecture](docs/ARCHITECTURE.md) - Technical design decisions
- [Monzo Auth Flow](docs/MONZO-AUTH-FLOW.md) - OAuth implementation details
- [Setup Guide](docs/SETUP.md) - Development environment setup
- [Secrets Management](docs/SECRETS-MANAGEMENT.md) - Handling credentials

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for git workflow, branch naming, and commit conventions.

## 📋 Current Status

**Phase:** Foundation & Monzo Integration

- [x] Project setup & configuration
- [x] Monzo OAuth flow (tested & working)
- [ ] Token persistence (in progress)
- [ ] Transaction sync
- [ ] Budgeting features
- [ ] Frontend UI

## 📄 License

Private project - not for distribution.

---

*Built with ☕ and frustration at where my money goes*
