# Budgeting App - Technical Architecture

## Architecture Overview

This document details the technical architecture for a personal budgeting application with Monzo API integration, designed to run on a home NUC server with secure public webhook access via Cloudflare Tunnel.

## High-Level Architecture

### Traffic Patterns

**Public Traffic (Webhooks)**
```
Internet → Cloudflare Edge → Cloudflare Tunnel (cloudflared) → (Optional Caddy) → Spring Boot
```

**Private Traffic (Dashboard)**
```
Desktop/Browser (LAN) → Spring Boot API → PostgreSQL
Desktop/Browser (Remote) → Cloudflare Tunnel → Spring Boot API → PostgreSQL
```

## Technology Stack

### Backend
- **Java 21** (LTS) or Java 17+
- **Spring Boot 3.x**
  - Spring Web (REST API)
  - Spring Security (Auth, CSRF, rate limiting)
  - Spring Data JPA (Database access)
  - Spring OAuth2 Client (Monzo OAuth)
- **PostgreSQL 16** (Primary database)
- **Flyway** (Database migrations)
- **Maven** (Build tool)

### Frontend
- **React 18+**
- **Vite** (Build tool & dev server)
- **React Router** (Client-side routing)
- **TanStack Query** (Data fetching & caching)
- **Recharts** or **Chart.js** (Data visualization)
- **Tailwind CSS** (Styling)
- **Electron** (Optional - for desktop app)

### Infrastructure
- **Intel NUC** - Home server (Linux + Docker)
- **Docker & Docker Compose** - Containerization
- **Cloudflare Tunnel** - Secure public access
- **Caddy** (Optional) - Reverse proxy for multiple services
- **GitHub Actions** (Optional) - CI/CD

## Backend Architecture (Spring Boot Modular Monolith)

### Why Monolith?

**Advantages for this use case:**
- Single deployable JAR - simple ops
- No service discovery overhead
- Easy testing & debugging
- Can split later if needed (extract sync worker, analytics service)
- Perfect for single-user/small team

### Module Structure

```
backend/src/main/java/dev/amf/budgeteer/
├── api/                           # REST API Layer
│   ├── common/                    # Shared API components
│   │   ├── ApiResponse.java       # Standard success wrapper
│   │   ├── ApiError.java          # Standard error format
│   │   ├── ErrorCode.java         # Error code enum
│   │   └── GlobalExceptionHandler.java
│   ├── auth/                      # Authentication endpoints
│   │   ├── AuthController.java    # /api/auth/*
│   │   └── dto/
│   │       ├── LoginRequest.java
│   │       ├── AuthResponse.java
│   │       └── UserResponse.java
│   └── monzo/                     # Monzo API integration
│       └── MonzoOAuthController.java  # /api/monzo/oauth/*
├── domain/                        # Domain Layer (entities + repositories)
│   ├── user/
│   │   ├── User.java
│   │   └── UserRepository.java
│   └── session/
│       ├── MagicLinkToken.java
│       ├── MagicLinkTokenRepository.java
│       ├── AppRefreshToken.java
│       └── AppRefreshTokenRepository.java
├── service/                       # Business Logic Layer
│   ├── AuthService.java           # Authentication flows
│   ├── SessionService.java        # Session/refresh token management
│   ├── JweTokenService.java       # JWE token creation/validation
│   └── EmailService.java          # Email sending
├── security/                      # Security filters
│   └── JweAuthenticationFilter.java
├── config/                        # Configuration classes
│   ├── AppProperties.java
│   ├── JweProperties.java
│   ├── MonzoProperties.java
│   └── SecurityConfig.java
└── exception/                     # Custom exceptions
    └── ApiException.java
```

**Package Conventions:**
- `api/` - REST controllers organized by feature, with nested `dto/` for request/response types
- `domain/` - JPA entities with their repositories (domain-driven grouping)
- `service/` - Business logic services (flat structure)
- `config/` - Spring configuration and properties classes
- `security/` - Security filters and auth-related components
- `exception/` - Custom exception types

**API Response Standards:**
- All endpoints return `ApiResponse<T>` wrapper: `{ success: true, data: {...}, timestamp: "..." }`
- Errors throw `ApiException` → caught by `GlobalExceptionHandler` → returns `ApiError`
- Standard error format: `{ success: false, error: { code: "...", message: "...", details: [...] }, timestamp: "..." }`

### Key Components

#### 1. OAuth Module (`app.monzo.oauth`)

**TokenService**
- Store/retrieve encrypted access & refresh tokens
- Automatic token rotation (refresh if `expires_at - 60s`)
- Atomic updates to prevent race conditions
- Handle token revocation gracefully

```java
@Service
public class TokenService {
    public TokenResponse getValidToken(String userId);
    public void refreshToken(String userId);
    public void storeTokens(String userId, TokenResponse tokens);
    public void revokeTokens(String userId);
}
```

**Token Storage**
- Encrypt tokens at rest (AES-256-GCM)
- Key stored in OS keyring or secure env var
- Database table: `oauth_tokens(user_id, encrypted_access, encrypted_refresh, expires_at)`

#### 2. Sync Module (`app.monzo.sync`)

**TransactionSyncService**
- Paginated fetching from Monzo API
- Idempotent upserts (unique on `transaction.id`)
- Delta sync (only fetch since `last_sync_at`)
- Error handling & retry logic

**BackfillService**
- Initial historical sync (6-12 months)
- Nightly guard job (catches missed webhooks)
- Progress tracking & resume capability

**WebhookProcessor**
- Fast validation & 200 OK response (<200ms)
- Queue for async processing
- Idempotency via `transaction.id` or `payload_hash`
- Update materialized views after upsert

```java
@Service
public class WebhookProcessor {
    public void handleWebhook(WebhookPayload payload);
    private void validateSignature(String signature, String body);
    private void enqueueProcessing(Transaction transaction);
    private void updateRollups(Transaction transaction);
}
```

#### 3. Data Layer

**Database Schema (PostgreSQL)**

```sql
-- Accounts
CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    monzo_account_id VARCHAR(255) UNIQUE NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    account_type VARCHAR(50),
    currency VARCHAR(3),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Transactions
CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    monzo_transaction_id VARCHAR(255) UNIQUE NOT NULL,
    account_id BIGINT REFERENCES accounts(id),
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description TEXT,
    merchant_name VARCHAR(255),
    category VARCHAR(100),
    normalized_category VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    settled_at TIMESTAMP,
    metadata JSONB,
    INDEX idx_account_created (account_id, created_at DESC),
    INDEX idx_category (normalized_category),
    INDEX idx_settled_month (DATE_TRUNC('month', settled_at))
);

-- Webhook Delivery (Idempotency)
CREATE TABLE webhook_deliveries (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(255) UNIQUE,
    payload_hash VARCHAR(64) UNIQUE,
    first_seen_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    raw_payload JSONB
);

-- OAuth Tokens
CREATE TABLE oauth_tokens (
    user_id VARCHAR(255) PRIMARY KEY,
    encrypted_access_token TEXT NOT NULL,
    encrypted_refresh_token TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Monthly Rollups (Materialized View)
CREATE MATERIALIZED VIEW monthly_summary AS
SELECT 
    account_id,
    DATE_TRUNC('month', settled_at) AS month,
    normalized_category,
    COUNT(*) AS transaction_count,
    SUM(amount_cents) AS total_amount_cents,
    AVG(amount_cents) AS avg_amount_cents
FROM transactions
WHERE settled_at IS NOT NULL
GROUP BY account_id, month, normalized_category;

CREATE INDEX ON monthly_summary(account_id, month);
```

**Flyway Migrations**
- Versioned SQL files in `src/main/resources/db/migration/`
- Naming: `V1__create_accounts.sql`, `V2__create_transactions.sql`
- Run automatically on Spring Boot startup

#### 4. Analytics Module

**CategoryRuleEngine**
- User-defined rules: regex patterns → normalized categories
- Default rules for common merchants
- Priority ordering (user rules override defaults)

**MonthlyRollupService**
- Refresh materialized view after transaction changes
- Can be incremental (update only affected months)
- Scheduled refresh (e.g., hourly or on-demand)

## API Design

### REST Endpoints

#### Authentication
```
GET  /auth/monzo/connect
     → Redirect to Monzo OAuth with state parameter
     
GET  /auth/callback?code=...&state=...
     → Exchange code for tokens, store encrypted, redirect to dashboard
```

#### Dashboard
```
GET  /api/dashboard?month=YYYY-MM&category=...&minAmount=...
     Response: {
       summary: { income, expenses, net },
       transactions: [...],
       categoryBreakdown: [...],
       comparisonToPrevious: { ... }
     }
```

#### Transactions
```
GET  /api/transactions?since=YYYY-MM-DD&limit=100
     → Paginated transaction list
     
GET  /api/transactions/{id}
     → Single transaction details
     
POST /api/transactions/sync
     → Trigger manual sync (returns job ID)
```

#### Webhooks
```
POST /webhook/monzo
     Headers: X-Monzo-Signature
     Body: { type: "transaction.created", data: {...} }
     → 200 OK (fast validation + enqueue)
```

#### Health
```
GET  /healthz
     → { status: "healthy", database: "ok", monzo: "connected" }
```

## Security

### 1. OAuth Security
- **Confidential client** (client_secret protected)
- **PKCE** (if supported by Monzo)
- **State parameter** for CSRF protection
- **Token encryption** at rest (AES-256-GCM)
- **Secure token storage** (database with encrypted fields)

### 2. Webhook Security
- **Signature validation** (X-Monzo-Signature header)
- **Idempotency** (prevent duplicate processing)
- **Rate limiting** (e.g., 100 req/min per IP)
- **HTTPS only** via Cloudflare Tunnel

### 3. API Security
- **CORS** configured for frontend origin only
- **CSRF protection** for state-changing operations
- **Rate limiting** per user/IP
- **Input validation** (JSR-303 Bean Validation)
- **SQL injection prevention** (JPA/Hibernate parameterized queries)

### 4. Access Control
- **Public routes**: `/webhook/monzo`, `/healthz`
- **Private routes**: Everything else (require authentication)
- **Optional**: Cloudflare Access for additional protection on private routes

### 5. Secrets Management
- **Environment variables** for sensitive config
- **OS keyring** for encryption keys (or secure .env)
- **Never commit** secrets to git (.gitignore `.env`)

## Infrastructure

### Docker Compose Setup

**Services:**
1. **postgres** - Database
2. **spring** - Backend application
3. **cloudflared** - Cloudflare Tunnel
4. **caddy** (optional) - Reverse proxy
5. **redis** (optional) - Job queue
6. **backup** (optional) - Automated backups

**Example `docker-compose.yaml`:**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: budget
      POSTGRES_USER: app
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "app"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  spring:
    build: ./backend
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/budget
      SPRING_DATASOURCE_USERNAME: app
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      MONZO_CLIENT_ID: ${MONZO_CLIENT_ID}
      MONZO_CLIENT_SECRET: ${MONZO_CLIENT_SECRET}
      OAUTH_REDIRECT_URI: ${OAUTH_REDIRECT_URI}
      ENCRYPTION_KEY: ${ENCRYPTION_KEY}
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "8080:8080"  # Expose on LAN
    restart: unless-stopped

  cloudflared:
    image: cloudflare/cloudflared:latest
    command: tunnel --no-autoupdate run
    environment:
      TUNNEL_TOKEN: ${TUNNEL_TOKEN}
    restart: unless-stopped

  # Optional: Caddy for reverse proxy
  caddy:
    image: caddy:2-alpine
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
    restart: unless-stopped

volumes:
  pgdata:
  caddy_data:
```

### Cloudflare Tunnel Configuration

**Routing Options:**

**Option 1: Direct (Simple)**
```yaml
# In Cloudflare dashboard:
hooks.yourdomain.com → http://spring:8080
```

**Option 2: Via Caddy (Multiple Services)**
```yaml
# In Cloudflare dashboard:
hooks.yourdomain.com → http://caddy:80

# Caddyfile:
:80 {
    handle /webhook/monzo {
        reverse_proxy spring:8080
    }
    handle /healthz {
        reverse_proxy spring:8080
    }
    respond 404
}
```

### Backups

**Strategy:**
- **Nightly pg_dump** to encrypted destination
- **Retention**: 7 daily + 4 weekly
- **Script** in cron or backup container

```bash
#!/bin/bash
# backup.sh
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
pg_dump -U app -h postgres budget | \
  gzip | \
  gpg --encrypt --recipient you@example.com > \
  /backups/budget_${TIMESTAMP}.sql.gz.gpg
```

## Data Flow Details

### 1. Initial Connection Flow

```
User → Frontend → Spring (/auth/monzo/connect)
       ↓
       Redirect to Monzo OAuth
       ↓
User authorizes → Monzo redirects to (/auth/callback)
       ↓
Spring exchanges code for tokens
       ↓
Store encrypted tokens in DB
       ↓
Trigger backfill job (last 6 months)
       ↓
Paginated fetch from Monzo API
       ↓
Upsert transactions to Postgres
       ↓
Return success → Redirect to dashboard
```

### 2. Dashboard View Flow

```
Frontend → GET /api/dashboard?month=2025-01
          ↓
Spring checks: is data fresh? (last_sync < 5 min ago)
          ↓ NO
          Trigger background sync
          ↓
Query Postgres (materialized view)
          ↓
Return aggregated data
          ↓
Frontend renders charts & tables
```

### 3. Webhook Flow

```
Monzo → POST /webhook/monzo (via Cloudflare)
       ↓
Spring validates signature (X-Monzo-Signature)
       ↓
Check idempotency (transaction.id or payload_hash)
       ↓ NEW
       Return 200 OK immediately (<200ms)
       ↓
Enqueue for async processing
       ↓
Worker: Upsert transaction
       ↓
Refresh monthly_summary for affected month
       ↓
(Optional) Push update to connected clients (SSE/WebSocket)
```

### 4. Token Rotation Flow

```
API call needs token
       ↓
Check: now >= expires_at - 60s?
       ↓ YES
       Call Monzo refresh endpoint
       ↓
Receive new access_token + new refresh_token
       ↓
Atomically update both tokens in DB
       ↓
Proceed with API call
       
(If refresh fails → surface "Reconnect Monzo" to user)
```

## Performance Considerations

### Database Optimization
- **Indexes** on common query patterns (account_id, created_at, category)
- **Materialized views** for monthly rollups (refresh after changes)
- **Partitioning** (optional, if data grows large) on transactions by month

### Caching
- **Spring Cache** for dashboard aggregations (5 min TTL)
- **TanStack Query** on frontend for API responses
- **ETag/If-None-Match** for unchanged data

### Background Jobs
- **Webhook processing** async via queue (Redis or in-JVM BlockingQueue)
- **Token refresh** scheduled task (every hour, refreshes expiring tokens)
- **Nightly backfill** guard job (catches missed webhooks)

## Monitoring & Observability

### Logging
- **Structured logging** (JSON format)
- **Log levels**: ERROR (always), INFO (important events), DEBUG (dev only)
- **Key events**: OAuth flows, webhook receipts, sync jobs, errors

### Metrics (Optional)
- **Spring Actuator** endpoints (`/actuator/metrics`, `/actuator/health`)
- **Prometheus** scraping + **Grafana** dashboards
- **Key metrics**: API latency, webhook processing time, DB query time, token refresh success rate

### Alerts
- **Webhook processing failures** (> 5% error rate)
- **Token refresh failures**
- **Database connection issues**
- **Disk space warnings**

## Scaling Considerations

### Current Scale
- **Single user** or small household
- **~1000 transactions/month**
- **Webhook volume**: ~30/day average
- **NUC capacity**: More than sufficient

### Future Scaling Options

**If needed (multi-user or high volume):**
1. **Horizontal scaling**: Multiple Spring instances behind load balancer
2. **Separate sync worker**: Extract to its own service with Redis queue
3. **Read replicas**: For analytics queries (if dashboard gets heavy use)
4. **CDN**: Cache frontend static assets via Cloudflare

**For now: Vertical scaling is fine** (more RAM/CPU on NUC if needed)

## Deployment Strategy

### Development
```bash
# Backend
cd backend && mvn spring-boot:run

# Frontend
cd frontend && npm run dev

# Database
docker-compose up postgres
```

### Production (NUC)
```bash
# Build
cd backend && mvn clean package
cd frontend && npm run build

# Deploy
cd deploy && docker-compose up -d

# View logs
docker-compose logs -f spring
```

### CI/CD (Optional)
- **GitHub Actions** to build Docker images
- **Push to GitHub Container Registry**
- **SSH to NUC** to pull and restart containers

## Future Enhancements

### Phase 2
- **Category rules UI** (user-defined merchant mappings)
- **Budget tracking** (set monthly limits, alerts)
- **Recurring payments detection**
- **SSE/WebSocket** for real-time updates

### Phase 3
- **Multi-account support** (joint accounts, pots)
- **Custom reports** (PDF exports, CSV downloads)
- **Spending predictions** (ML-based forecasting)
- **Mobile app** (React Native or Flutter)

## References

- [Monzo API Docs](../api/monzo-api.pdf)
- [Spring Boot Reference](https://docs.spring.io/spring-boot/reference/)
- [Cloudflare Tunnel Docs](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
- [PostgreSQL Best Practices](https://www.postgresql.org/docs/current/performance-tips.html)

## Production Deployment Architecture

### Overview

The production architecture uses a **reverse proxy** for local routing and **Cloudflare Tunnel** for internet access. This achieves:

1. **Same Origin** - Frontend and backend share `budgeteer.dev` domain
2. **No CORS** - Same origin means no CORS configuration needed
3. **Secure Cookies** - `SameSite=Lax` cookies work without issues
4. **Internet Access** - Cloudflare Tunnel exposes local network for webhooks

### Architecture Diagram

```
                        INTERNET
                            │
                ┌───────────▼───────────┐
                │   Cloudflare Tunnel    │
                │   (budgeteer.dev)      │
                └───────────┬───────────┘
                            │
                            │ (Tunnel to local network)
                            │
    ════════════════════════╪════════════════════════════
                    LOCAL NETWORK (NUC)
    ════════════════════════╪════════════════════════════
                            │
                ┌───────────▼───────────┐
                │    Reverse Proxy       │
                │    (nginx/Caddy)       │
                │                        │
                │  /       → Frontend    │
                │  /api/*  → Backend     │
                └───────────┬───────────┘
                            │
         ┌──────────────────┼──────────────────┐
         │                  │                  │
         ▼                  ▼                  ▼
    ┌─────────┐       ┌──────────┐       ┌──────────┐
    │Frontend │       │ Backend  │       │PostgreSQL│
    │ :3000   │       │  :8080   │       │  :5432   │
    └─────────┘       └──────────┘       └──────────┘
```

### URL Structure

| URL | Routed To | Notes |
|-----|-----------|-------|
| `https://budgeteer.dev/` | Frontend (nginx → :3000) | React SPA |
| `https://budgeteer.dev/api/*` | Backend (nginx → :8080) | Spring Boot API |
| `https://budgeteer.dev/api/auth/*` | Backend | App authentication |
| `https://budgeteer.dev/api/monzo/*` | Backend | Monzo OAuth & webhooks |

### Why Both Components?

| Component | Purpose | Required For |
|-----------|---------|--------------|
| **Reverse Proxy (nginx)** | Route traffic locally based on path | Same-origin deployment |
| **Cloudflare Tunnel** | Expose local network to internet | Monzo webhooks, OAuth callbacks, remote access |

**Without the tunnel:** Monzo cannot send webhooks or OAuth callbacks to your local network.

**Without the proxy:** Frontend and backend would need different ports/domains (CORS issues).

### Cookie Security with Same Origin

Since frontend and backend share the same origin (`budgeteer.dev`):

```java
// CookieService.java - Secure cookie configuration
ResponseCookie cookie = ResponseCookie.from("access_token", token)
    .httpOnly(true)           // JS can't read it
    .secure(true)             // HTTPS only
    .sameSite("Lax")          // CSRF protection
    .path("/api")             // Only sent to /api endpoints
    .maxAge(duration)
    .build();
```

- ✅ `SameSite=Lax` provides CSRF protection
- ✅ `HttpOnly` prevents XSS token theft
- ✅ `Secure` ensures HTTPS-only
- ✅ No CORS needed (same origin)

### Production Docker Compose

```yaml
services:
  cloudflared:
    image: cloudflare/cloudflared:latest
    command: tunnel run
    environment:
      - TUNNEL_TOKEN=${CLOUDFLARE_TUNNEL_TOKEN}
    restart: unless-stopped
    
  nginx:
    image: nginx:alpine
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - frontend
      - backend
    restart: unless-stopped
    
  frontend:
    build: ./frontend
    restart: unless-stopped
    
  backend:
    build: ./backend
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/budgeteer
      # ... other env vars
    depends_on:
      - db
    restart: unless-stopped
    
  db:
    image: postgres:16-alpine
    volumes:
      - pgdata:/var/lib/postgresql/data
    environment:
      - POSTGRES_DB=budgeteer
      - POSTGRES_USER=${DB_USER}
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    restart: unless-stopped

volumes:
  pgdata:
```

### nginx Configuration

```nginx
# /etc/nginx/nginx.conf
events {}

http {
    server {
        listen 80;
        server_name localhost;

        # Frontend (default)
        location / {
            proxy_pass http://frontend:3000;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
        }

        # Backend API
        location /api/ {
            proxy_pass http://backend:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
    }
}
```

### Cloudflare Tunnel Configuration

Configure in Cloudflare Zero Trust dashboard:

| Hostname | Service |
|----------|---------|
| `budgeteer.dev` | `http://nginx:80` |

The tunnel connects to your nginx container, which then routes to frontend or backend.

---

## Decision Log

| Decision | Rationale | Date |
|----------|-----------|------|
| Modular monolith over microservices | Simpler ops, single user, easy to split later | 2025-01 |
| Cloudflare Tunnel over VPN | No router config, built-in DDoS protection | 2025-01 |
| PostgreSQL over MongoDB | ACID guarantees, better for financial data | 2025-01 |
| React over Vue/Angular | Larger ecosystem, better job market fit | 2025-01 |
| Flyway over Liquibase | Simpler, SQL-first approach | 2025-01 |
| Reverse proxy + tunnel | Same origin deployment, secure cookies | 2026-01 |
| `@ConfigurationPropertiesScan` | Consistent config class registration | 2026-01 |

---

**Last Updated**: 2026-01-03  
**Author**: Alex Fisher  
**Status**: Development Phase
