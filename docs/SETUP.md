# Development Setup Guide

This guide walks you through setting up the Budgeteer development environment.

## Prerequisites

### Required Software

| Tool | Version | Installation |
|------|---------|--------------|
| Java | 21+ | [SDKMAN](https://sdkman.io/) (recommended) |
| Maven | 3.9+ | [SDKMAN](https://sdkman.io/) or [manual](https://maven.apache.org/) |
| Docker | Latest | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| Docker Compose | Latest | Included with Docker Desktop |

### Installing with SDKMAN (Recommended)

```bash
# Install SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install Java 21
sdk install java 21-tem

# Install Maven
sdk install maven
```

### Monzo Developer Account

1. Go to [developers.monzo.com](https://developers.monzo.com/)
2. Sign in with your Monzo account
3. Create a new OAuth client:
   - **Name:** Budgeteer (dev)
   - **Redirect URLs:** `http://localhost:8080/auth/callback`
   - **Confidentiality:** Confidential
4. Note your **Client ID** and **Client Secret**

## Initial Setup

### 1. Clone the Repository

```bash
git clone https://github.com/yourusername/budgeteer.git
cd budgeteer
```

### 2. Configure Environment

```bash
# Copy the example environment file
cp .env.example .env

# Edit .env with your values
# Required:
# - MONZO_CLIENT_ID=your-client-id
# - MONZO_CLIENT_SECRET=your-client-secret
# - MONZO_REDIRECT_URI=http://localhost:8080/auth/callback
```

### 3. Start the Database

```bash
docker compose up -d
```

This starts PostgreSQL on `localhost:5432`.

### 4. Run the Backend

```bash
./scripts/dev.sh
```

Or manually:

```bash
cd backend
mvn spring-boot:run
```

### 5. Verify Setup

- Open http://localhost:8080/auth/connect
- You should see the Monzo OAuth page
- After authorizing, you'll be redirected back

## Testing OAuth with ngrok

For testing the full OAuth flow (including Monzo's callbacks), you need a public URL:

### Using ngrok

```bash
# Install ngrok (if not installed)
brew install ngrok

# Start ngrok tunnel
ngrok http 8080
```

### Update Configuration

1. Copy the ngrok URL (e.g., `https://abc123.ngrok-free.app`)
2. Update `.env`:
   ```
   MONZO_REDIRECT_URI=https://abc123.ngrok-free.app/auth/callback
   ```
3. Add the same URL to your Monzo OAuth client's redirect URLs
4. Restart the backend

See [MONZO-AUTH-FLOW.md](MONZO-AUTH-FLOW.md) for detailed OAuth testing instructions.

## Project Structure

```
budgeteer/
├── backend/                 # Spring Boot API
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/        # Java source code
│   │   │   └── resources/
│   │   │       ├── application.properties
│   │   │       └── db/migration/   # Flyway migrations
│   │   └── test/            # Tests
│   └── pom.xml
├── frontend/                # Web UI (coming soon)
├── docs/                    # Documentation
├── scripts/                 # Dev scripts
├── compose.yaml             # Docker services
├── .env                     # Local environment (git-ignored)
└── .env.example             # Environment template
```

## Common Tasks

### Running Tests

```bash
cd backend
mvn test
```

### Rebuilding

```bash
cd backend
mvn clean install -DskipTests
```

### Database Reset

```bash
# Stop and remove the database container
docker compose down -v

# Start fresh
docker compose up -d
```

### Viewing Logs

```bash
# Backend logs (shown in terminal)
./scripts/dev.sh

# Database logs
docker compose logs -f postgres
```

## IDE Setup

### IntelliJ IDEA

1. Open the project root folder
2. IntelliJ will detect the Maven project in `backend/`
3. Set Project SDK to Java 21
4. Mark `backend/src/main/java` as Sources Root
5. Enable annotation processing (for Lombok, if used)

### VS Code

1. Install Java Extension Pack
2. Open the project root folder
3. VS Code will detect the Maven project

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `MONZO_CLIENT_ID` | OAuth client ID | `oauth2client_xxxxx` |
| `MONZO_CLIENT_SECRET` | OAuth client secret | `mnzconf.xxxxx` |
| `MONZO_REDIRECT_URI` | OAuth callback URL | `http://localhost:8080/auth/callback` |

## Troubleshooting

### Database Connection Failed

```
Connection refused to localhost:5432
```

**Solution:** Ensure Docker is running and the database is up:
```bash
docker compose up -d
docker compose ps  # Should show "running"
```

### Maven Not Found

```
mvn: command not found
```

**Solution:** Install Maven or ensure SDKMAN is loaded:
```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

### OAuth Redirect Mismatch

```
redirect_uri mismatch
```

**Solution:** Ensure the redirect URI in `.env` exactly matches what's configured in your Monzo developer portal.

### Port Already in Use

```
Port 8080 already in use
```

**Solution:** Kill the existing process or use a different port:
```bash
lsof -i :8080  # Find the process
kill -9 <PID>  # Kill it
```

## Next Steps

Once your environment is set up:

1. Test the OAuth flow with `/auth/connect`
2. Check the API endpoints in `AuthController`
3. Review [ARCHITECTURE.md](ARCHITECTURE.md) for design decisions
4. Check `.cline/tasks.md` for current work items

---

*Need help? Check the troubleshooting section or review the logs.*
