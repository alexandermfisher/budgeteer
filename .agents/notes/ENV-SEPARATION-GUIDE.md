# Environment Separation Guide

> How to separate dev/prod configs using Docker, Spring profiles, and .env files

## 🧅 The Three Layers (All Work Together)

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 3: .env files (SECRETS & DEPLOYMENT CONFIG)          │
│  - API keys, passwords, URLs                                │
│  - Different file per environment                           │
│  - Never committed to git                                   │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: Docker Compose (INFRASTRUCTURE)                   │
│  - Which services to run                                    │
│  - Ports, volumes, networks                                 │
│  - Can have dev/prod variants                               │
├─────────────────────────────────────────────────────────────┤
│  Layer 1: Spring Profiles (APPLICATION BEHAVIOR)            │
│  - Logging levels, feature flags                            │
│  - Database settings (pooling, etc.)                        │
│  - application-{profile}.properties                         │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔧 Layer 1: Spring Profiles

**What it controls:** Application behavior, logging, internal settings

### File Structure

```
backend/src/main/resources/
├── application.properties           # Base config (shared)
├── application-dev.properties       # Dev overrides
└── application-prod.properties      # Prod overrides
```

### application.properties (Base)

```properties
spring.application.name=budgeteer

# Profile selection (from env var, default to dev)
spring.profiles.active=${SPRING_PROFILE:dev}

# Database (values come from environment)
spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:budgeteer}
spring.datasource.username=${DB_USER:budgeteer}
spring.datasource.password=${DB_PASSWORD:budgeteer}

# JPA - let Flyway manage schema
spring.jpa.hibernate.ddl-auto=none

# Flyway
spring.flyway.enabled=true
spring.flyway.baseline-on-migrate=true

# Monzo (from environment)
monzo.client-id=${MONZO_CLIENT_ID}
monzo.client-secret=${MONZO_CLIENT_SECRET}
monzo.redirect-uri=${MONZO_REDIRECT_URI}
monzo.auth-url=https://auth.monzo.com/
monzo.token-url=https://api.monzo.com/oauth2/token
monzo.api-base-url=https://api.monzo.com
```

### application-dev.properties

```properties
# Dev-specific settings
logging.level.dev.amf.budgeteer=DEBUG
logging.level.org.springframework.web=DEBUG
logging.level.org.hibernate.SQL=DEBUG

# Show SQL in dev
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# Dev server config
server.port=8080
```

### application-prod.properties

```properties
# Prod-specific settings
logging.level.root=INFO
logging.level.dev.amf.budgeteer=INFO

# Don't show SQL in prod
spring.jpa.show-sql=false

# Production optimizations
spring.jpa.properties.hibernate.jdbc.batch_size=20
spring.datasource.hikari.maximum-pool-size=10

# Prod server config
server.port=8080
```

### How to activate:

```bash
# Dev (default)
java -jar budgeteer.jar

# Prod
java -jar budgeteer.jar --spring.profiles.active=prod

# Or via environment variable
SPRING_PROFILE=prod java -jar budgeteer.jar
```

---

## 🐳 Layer 2: Docker Compose

**What it controls:** Infrastructure, services, networking

### Option A: Single compose.yaml with profiles

```yaml
# compose.yaml
services:
  # PostgreSQL - same for dev and prod
  postgres:
    image: postgres:16
    container_name: budgeteer-postgres
    environment:
      POSTGRES_DB: ${DB_NAME:-budgeteer}
      POSTGRES_USER: ${DB_USER:-budgeteer}
      POSTGRES_PASSWORD: ${DB_PASSWORD:-budgeteer}
    ports:
      - "${DB_PORT:-5432}:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER:-budgeteer}"]
      interval: 5s
      timeout: 5s
      retries: 5

  # Backend - different configs for dev vs prod
  backend:
    profiles: ["prod"]  # Only runs in prod profile
    image: ${REGISTRY:-ghcr.io/yourusername}/budgeteer-backend:${VERSION:-latest}
    container_name: budgeteer-backend
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_PROFILE: prod
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: ${DB_NAME:-budgeteer}
      DB_USER: ${DB_USER:-budgeteer}
      DB_PASSWORD: ${DB_PASSWORD}
      MONZO_CLIENT_ID: ${MONZO_CLIENT_ID}
      MONZO_CLIENT_SECRET: ${MONZO_CLIENT_SECRET}
      MONZO_REDIRECT_URI: ${MONZO_REDIRECT_URI}
    ports:
      - "8080:8080"

volumes:
  postgres_data:
```

**Usage:**
```bash
# Dev (just database, you run Spring Boot locally)
docker compose up -d postgres

# Prod (full stack)
docker compose --profile prod up -d
```

### Option B: Separate compose files (Recommended)

```yaml
# compose.yaml (Dev - just database)
services:
  postgres:
    image: postgres:16
    container_name: budgeteer-postgres
    environment:
      POSTGRES_DB: budgeteer
      POSTGRES_USER: budgeteer
      POSTGRES_PASSWORD: budgeteer
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:
```

```yaml
# compose.prod.yaml (Production - full stack)
services:
  postgres:
    image: postgres:16
    container_name: budgeteer-postgres
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER}"]
      interval: 5s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  backend:
    image: ${REGISTRY}/budgeteer-backend:${VERSION:-latest}
    container_name: budgeteer-backend
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_PROFILE: prod
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: ${DB_NAME}
      DB_USER: ${DB_USER}
      DB_PASSWORD: ${DB_PASSWORD}
      MONZO_CLIENT_ID: ${MONZO_CLIENT_ID}
      MONZO_CLIENT_SECRET: ${MONZO_CLIENT_SECRET}
      MONZO_REDIRECT_URI: ${MONZO_REDIRECT_URI}
    ports:
      - "8080:8080"
    restart: unless-stopped

volumes:
  postgres_data:
```

**Usage:**
```bash
# Dev
docker compose up -d

# Prod (on NUC)
docker compose -f compose.prod.yaml --env-file .env.prod up -d
```

---

## 📄 Layer 3: .env Files

**What it controls:** Secrets, environment-specific values

### .env (Dev - on your Mac)

```bash
# Dev environment
SPRING_PROFILE=dev

# Database (using Docker defaults)
DB_HOST=localhost
DB_PORT=5432
DB_NAME=budgeteer
DB_USER=budgeteer
DB_PASSWORD=budgeteer

# Monzo OAuth (your dev credentials)
MONZO_CLIENT_ID=oauth2client_xxxxx
MONZO_CLIENT_SECRET=your-dev-secret
MONZO_REDIRECT_URI=http://localhost:8080/auth/callback
```

### .env.prod (Production - on NUC, never in git)

```bash
# Production environment
SPRING_PROFILE=prod

# Database (stronger password!)
DB_HOST=postgres
DB_PORT=5432
DB_NAME=budgeteer
DB_USER=budgeteer
DB_PASSWORD=super-secure-random-password-here

# Monzo OAuth (production redirect via Cloudflare Tunnel)
MONZO_CLIENT_ID=oauth2client_xxxxx
MONZO_CLIENT_SECRET=your-prod-secret
MONZO_REDIRECT_URI=https://budgeteer.yourdomain.com/auth/callback

# Docker registry (for pulling images)
REGISTRY=ghcr.io/yourusername
VERSION=v1.0.0
```

### .env.example (Template - committed to git)

```bash
# Copy this to .env or .env.prod and fill in values

# Environment
SPRING_PROFILE=dev

# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=budgeteer
DB_USER=budgeteer
DB_PASSWORD=change-me

# Monzo OAuth
MONZO_CLIENT_ID=oauth2client_xxxxx
MONZO_CLIENT_SECRET=your-secret-here
MONZO_REDIRECT_URI=http://localhost:8080/auth/callback

# Docker (prod only)
# REGISTRY=ghcr.io/yourusername
# VERSION=latest
```

---

## 📦 What is a Docker Registry?

A **registry** is a storage server for Docker images (like npm for Node packages).

### Types of Registries

| Registry | Cost | Good For |
|----------|------|----------|
| **Docker Hub** | Free (public), Paid (private) | Public images |
| **GitHub Container Registry (ghcr.io)** | Free for public repos | GitHub projects |
| **Self-hosted** | Free (you host it) | Private, on your NUC |
| **AWS ECR / GCP GCR** | Paid | Cloud deployments |

### How it Works

```
┌─────────────┐        ┌──────────────┐        ┌─────────────┐
│   Mac       │        │   Registry   │        │   NUC       │
│  (build)    │───────►│ (stores      │───────►│  (deploy)   │
│             │  push  │  images)     │  pull  │             │
└─────────────┘        └──────────────┘        └─────────────┘
```

### Versioning with Tags

```bash
# Build with version tag
docker build -t budgeteer-backend:v1.0.0 ./backend

# Also tag as 'latest'
docker tag budgeteer-backend:v1.0.0 budgeteer-backend:latest

# Push to registry
docker push ghcr.io/yourusername/budgeteer-backend:v1.0.0
docker push ghcr.io/yourusername/budgeteer-backend:latest

# On NUC, pull specific version
docker pull ghcr.io/yourusername/budgeteer-backend:v1.0.0

# Or always get latest
docker pull ghcr.io/yourusername/budgeteer-backend:latest
```

### Self-Hosted Registry (Simple Option)

```bash
# On NUC - run your own registry
docker run -d -p 5000:5000 --restart always --name registry registry:2

# From Mac - push to NUC registry
docker tag budgeteer-backend nuc-ip:5000/budgeteer-backend:v1.0.0
docker push nuc-ip:5000/budgeteer-backend:v1.0.0

# On NUC - pull from local registry
docker pull localhost:5000/budgeteer-backend:v1.0.0
```

---

## 🎯 Complete Example: Dev to Prod Flow

### 1. Development (Mac)

```bash
# Start database
docker compose up -d

# Run Spring Boot (picks up .env automatically via scripts/dev.sh)
./scripts/dev.sh
```

### 2. Build Release

```bash
# Build Docker image
cd backend
docker build -t budgeteer-backend:v1.0.0 .

# Tag for registry
docker tag budgeteer-backend:v1.0.0 ghcr.io/yourusername/budgeteer-backend:v1.0.0

# Push to registry
docker push ghcr.io/yourusername/budgeteer-backend:v1.0.0

# Also update latest
docker tag budgeteer-backend:v1.0.0 ghcr.io/yourusername/budgeteer-backend:latest
docker push ghcr.io/yourusername/budgeteer-backend:latest

# Tag in git
git tag v1.0.0
git push origin v1.0.0
```

### 3. Deploy to NUC

```bash
# SSH to NUC
ssh nuc

# Navigate to project (you'll have compose.prod.yaml and .env.prod there)
cd ~/budgeteer

# Pull latest version (or specific tag)
docker compose -f compose.prod.yaml pull

# Deploy
docker compose -f compose.prod.yaml --env-file .env.prod up -d

# Check logs
docker compose -f compose.prod.yaml logs -f backend
```

---

## 📁 Final File Structure

```
budgeteer/
├── backend/
│   ├── src/main/resources/
│   │   ├── application.properties        # Base config
│   │   ├── application-dev.properties    # Dev overrides
│   │   └── application-prod.properties   # Prod overrides
│   ├── Dockerfile                        # Build the app
│   └── pom.xml
├── compose.yaml                          # Dev (just DB)
├── compose.prod.yaml                     # Prod (full stack)
├── .env                                  # Dev secrets (git-ignored)
├── .env.example                          # Template (committed)
└── .gitignore
```

**On NUC:**
```
~/budgeteer/
├── compose.prod.yaml      # Copy from repo
└── .env.prod              # Create manually, never in git
```

---

## ✅ Summary

| Layer | What | Where | Git? |
|-------|------|-------|------|
| Spring Profiles | App behavior | `application-{profile}.properties` | ✅ Yes |
| Docker Compose | Infrastructure | `compose.yaml`, `compose.prod.yaml` | ✅ Yes |
| .env Files | Secrets | `.env`, `.env.prod` | ❌ No |

**Yes, you use ALL THREE together!** Each handles a different concern.

---

*This is your personal reference - update as you learn!*
