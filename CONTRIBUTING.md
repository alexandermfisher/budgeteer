# Contributing to Budgeteer

This document outlines the development workflow, git conventions, and code standards for this project.

## 🌿 Git Workflow

### Branch Strategy

We use a simplified Git Flow:

| Branch | Purpose |
|--------|---------|
| `main` | Production-ready code, always deployable |
| `develop` | Integration branch (optional, for larger features) |
| `feature/*` | New features |
| `fix/*` | Bug fixes |
| `refactor/*` | Code improvements (no behavior change) |
| `docs/*` | Documentation updates |
| `chore/*` | Maintenance tasks (deps, config, etc.) |

### Branch Naming

```
<type>/<short-description>

Examples:
feature/monzo-token-persistence
fix/null-balance-handling
refactor/extract-monzo-client
docs/update-setup-guide
chore/upgrade-spring-boot
```

### Creating a Branch

```bash
# From main (or develop if using)
git checkout main
git pull
git checkout -b feature/your-feature-name
```

## 📝 Commit Conventions

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Types

| Type | Description |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `test` | Adding or updating tests |
| `chore` | Maintenance (deps, build, config) |
| `style` | Code style (formatting, whitespace) |
| `perf` | Performance improvement |

### Scope (Optional)

The scope indicates what part of the codebase is affected:
- `auth` - Authentication/OAuth
- `monzo` - Monzo API integration
- `db` - Database/migrations
- `api` - REST API endpoints
- `config` - Configuration

### Examples

```bash
# Features
feat(auth): add token persistence to database
feat(monzo): implement transaction sync endpoint

# Fixes
fix(auth): handle expired refresh token gracefully
fix(api): return 404 for unknown account

# Documentation
docs: update README with mono-repo structure
docs(setup): add Cloudflare tunnel instructions

# Refactoring
refactor(monzo): extract API client to separate class
refactor: rename services for consistency

# Maintenance
chore: upgrade Spring Boot to 3.4.1
chore(deps): update PostgreSQL driver

# Tests
test(auth): add unit tests for TokenService
test: increase coverage for MonzoClient
```

## 🔀 Pull Request Process

1. **Create a feature branch** from `main`
2. **Make your changes** with clear, atomic commits
3. **Test locally** - ensure `mvn test` passes
4. **Push and create PR** against `main`
5. **Self-review** (or request review if collaborating)
6. **Squash and merge** (preferred) or rebase merge

### PR Title

Use the same format as commits:
```
feat(auth): add token persistence to database
```

## 💻 Code Standards

### Java/Spring Boot

- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- Use meaningful, descriptive names
- Add Javadoc for public classes and methods
- Prefer constructor injection over field injection
- Keep controllers thin - logic belongs in services
- Use DTOs for API, not entities directly

### Package Structure

```
dev.amf.budgeteer/
├── config/        # Configuration classes
├── controller/    # REST controllers
├── service/       # Business logic
├── repository/    # Data access
├── model/         # Entities and DTOs
├── client/        # External API clients (Monzo)
└── exception/     # Custom exceptions
```

### Testing

- Write unit tests for service methods
- Use `@WebMvcTest` for controller tests
- Use `@DataJpaTest` for repository tests
- Mock external services (Monzo API)
- Aim for meaningful coverage, not 100%

## 🗃️ Database Migrations

We use Flyway for database migrations:

```
backend/src/main/resources/db/migration/
├── V1__baseline.sql
├── V2__create_oauth_tokens.sql
├── V3__create_accounts.sql
└── ...
```

### Rules

1. **Never modify existing migrations** - create new ones
2. **Version sequentially** - `V1`, `V2`, `V3`...
3. **Descriptive names** - `V2__create_oauth_tokens.sql`
4. **Test locally** before committing
5. **Keep migrations small** and focused

## 📁 Project Structure

```
budgeteer/
├── backend/           # Spring Boot API
│   ├── src/
│   └── pom.xml
├── frontend/          # Web UI (future)
├── docs/              # Public documentation
├── scripts/           # Development scripts
├── .cline/            # Cline AI context (git-ignored)
├── compose.yaml       # Docker services
├── .env.example       # Environment template
└── README.md
```

## 🚀 Development Workflow

1. **Start services:**
   ```bash
   docker compose up -d      # Database
   ./scripts/dev.sh          # Backend
   ```

2. **Make changes** following the standards above

3. **Test:**
   ```bash
   cd backend && mvn test
   ```

4. **Commit** using conventional commits

5. **Push** and create PR if needed

---

*Questions? Check the docs or open an issue.*
