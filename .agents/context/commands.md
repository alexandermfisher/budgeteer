# Commands

> Full docs: `docs/setup/SETUP.md` · `docs/setup/CI-CD.md` · `docs/testing/TESTING.md`

## Database

```bash
docker compose up -d          # Start PostgreSQL (detached)
docker compose down           # Stop and remove containers
docker compose ps             # Check container status
docker compose logs db        # View DB logs
```

## Running the App

```bash
./scripts/dev.sh              # Start app (loads .env, checks Java, starts DB if needed)
./scripts/dev.sh start        # Explicit start
./scripts/dev.sh stop         # Stop app
./scripts/dev.sh restart      # Restart
./scripts/dev.sh status       # Show app + DB status
./scripts/dev.sh db           # Start DB only
./scripts/dev.sh tunnel       # Start ngrok tunnel (for Monzo OAuth redirect URI)
```

## Testing

```bash
# From backend/ directory:
cd backend

mvn test                                    # All tests (unit + integration)
mvn test -DexcludedGroups=integration       # Unit tests only (no Docker needed)
mvn test -Dgroups=integration               # Integration tests only (needs Docker)
mvn test -Dtest=AuthServiceTest             # Single test class
mvn test -Dtest=AuthFlowIT                  # Single IT class
```

## Code Quality

```bash
cd backend
mvn checkstyle:check          # Verify code style
mvn checkstyle:checkstyle     # Generate checkstyle report
mvn compile                   # Compile only
mvn verify                    # Compile + test + checkstyle
```

## URLs (when running locally)

| Endpoint | URL |
|----------|-----|
| Health | http://localhost:8080/actuator/health |
| Auth — request magic link | POST http://localhost:8080/auth/login |
| Auth — verify magic link | GET http://localhost:8080/auth/verify?token=... |
| Monzo OAuth — initiate | GET http://localhost:8080/auth/connect |
| Dev login (dev profile only) | POST http://localhost:8080/dev/auth/login |

## Useful Scripts

```bash
# Postman collection + environment
scripts/postman/budgeteer-auth.postman_collection.json
scripts/postman/budgeteer-local.postman_environment.json

# Manual SQL queries for testing
scripts/sql/manual-testing.sql
scripts/sql/queries.sql
```

## Key Generation

```bash
openssl rand -base64 32       # Generate JWE_SECRET_KEY or MONZO_ENCRYPTION_KEY
```
