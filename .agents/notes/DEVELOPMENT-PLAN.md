# Budgeting App - Development Plan

## Overview

This document outlines the development roadmap for the Monzo-integrated budgeting application, broken down into logical phases with clear deliverables.

---

## Key Technical Insight: Automated Batch Jobs ARE Possible!

Before diving into tickets, here's the critical finding from the Monzo API analysis:

### ✅ No Manual Authentication Required for Batch Jobs!

The Monzo API uses **OAuth 2.0 with refresh tokens** for confidential clients (server-side apps):

1. **Initial Setup**: User authenticates ONCE via OAuth + approves in Monzo app (Strong Customer Authentication)
2. **You Receive**: Access token (6 hours) + **Refresh token** (long-lived)
3. **Automated Refresh**: Server can automatically refresh tokens using the refresh token - no human interaction!

### Important Timing Constraints

| Window | Transaction Access |
|--------|-------------------|
| First 5 minutes after OAuth | Can fetch ALL historical transactions |
| After 5 minutes | Can only fetch last 90 days |

**Action Required**: Immediately trigger a background job to fetch 6-12 months of transaction history RIGHT AFTER OAuth completes!

---

## Development Phases

### Phase 1: Foundation & Infrastructure
**Duration: ~1 week**
**Goal:** Get the basic project structure, database, and local dev environment running

#### Tasks

##### 1.1 Initialize Spring Boot Project
- Create Maven project with Spring Boot 3.x
- Dependencies: Web, JPA, Security, OAuth2 Client, Flyway
- Set up package structure:
  ```
  app/
  ├── web/        # REST controllers
  ├── security/   # Auth, encryption
  ├── monzo/      # OAuth, API client, sync
  ├── data/       # Entities, repositories
  └── analytics/  # Future: rollups, reports
  ```
- **Acceptance**: `mvn spring-boot:run` starts successfully

##### 1.2 Docker Compose Setup
- Create `docker-compose.yaml` with PostgreSQL 16
- Configure volumes for data persistence
- Add health checks
- Create `.env.example` with all required variables
- **Acceptance**: `docker-compose up` creates working PostgreSQL container

##### 1.3 Application Config Structure
- Set up `application.yaml` with profiles (dev, prod)
- Configure datasource with env vars
- Add Monzo OAuth property placeholders
- **Acceptance**: Config loads correctly per profile

##### 1.4 Health Check Endpoint
- Create `/healthz` endpoint
- Check database connectivity
- Return JSON status
- **Acceptance**: Endpoint returns `{"status": "healthy", "database": "ok"}`

---

### Phase 2: Database Schema & Migrations
**Duration: ~1 week**
**Goal:** Define and create all core database tables with proper indexing

#### Database Schema Diagram

```
┌─────────────────┐     ┌──────────────────┐     ┌────────────────────┐
│  oauth_tokens   │     │     accounts     │     │   transactions     │
├─────────────────┤     ├──────────────────┤     ├────────────────────┤
│ user_id (PK)    │────>│ user_id          │     │ id (PK)            │
│ encrypted_access│     │ id (PK)          │<────│ account_id (FK)    │
│ encrypted_refresh│    │ monzo_account_id │     │ monzo_tx_id (UQ)   │
│ expires_at      │     │ account_type     │     │ amount_cents       │
│ updated_at      │     │ currency         │     │ merchant_name      │
└─────────────────┘     │ created_at       │     │ category           │
                        └──────────────────┘     │ created_at         │
                                                 │ settled_at         │
┌──────────────────────┐                         │ metadata (JSONB)   │
│  webhook_deliveries  │                         └────────────────────┘
├──────────────────────┤
│ id (PK)              │
│ transaction_id (UQ)  │
│ payload_hash (UQ)    │
│ processed_at         │
│ raw_payload (JSONB)  │
└──────────────────────┘
```

#### Tasks

##### 2.1 Flyway Setup
- Configure Flyway in Spring Boot
- Create `src/main/resources/db/migration` folder
- **Acceptance**: Flyway runs on startup

##### 2.2 OAuth Tokens Table
```sql
-- V1__create_oauth_tokens.sql
CREATE TABLE oauth_tokens (
    user_id VARCHAR(255) PRIMARY KEY,
    encrypted_access_token TEXT NOT NULL,
    encrypted_refresh_token TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```
- **Acceptance**: Migration runs successfully

##### 2.3 Accounts Table
```sql
-- V2__create_accounts.sql
CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    monzo_account_id VARCHAR(255) UNIQUE NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    account_type VARCHAR(50),
    currency VARCHAR(3),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```
- **Acceptance**: Migration runs successfully

##### 2.4 Transactions Table
```sql
-- V3__create_transactions.sql
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
    metadata JSONB
);

CREATE INDEX idx_tx_account_created ON transactions(account_id, created_at DESC);
CREATE INDEX idx_tx_category ON transactions(normalized_category);
CREATE INDEX idx_tx_settled_month ON transactions(DATE_TRUNC('month', settled_at));
```
- **Acceptance**: Migration runs with indexes

##### 2.5 Webhook Deliveries Table
```sql
-- V4__create_webhook_deliveries.sql
CREATE TABLE webhook_deliveries (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(255) UNIQUE,
    payload_hash VARCHAR(64) UNIQUE,
    first_seen_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    raw_payload JSONB
);
```
- **Acceptance**: Migration runs successfully

##### 2.6 JPA Entities
- Create `@Entity` classes for each table
- Add proper relationships (`@ManyToOne`, etc.)
- Add validation annotations
- **Acceptance**: Entities compile and map correctly

##### 2.7 Repositories
- Create Spring Data JPA repositories
- Add custom query methods as needed
- **Acceptance**: CRUD operations work

---

### Phase 3: Encryption & Security Foundation
**Duration: ~3-4 days**
**Goal:** Set up encryption service for secure token storage

#### Tasks

##### 3.1 Encryption Service
- Create `EncryptionService` with AES-256-GCM
- Methods: `encrypt(String plaintext)`, `decrypt(String ciphertext)`
- Key loaded from environment variable
- **Acceptance**: Can round-trip encrypt/decrypt strings

##### 3.2 Key Configuration
- Document key generation: `openssl rand -base64 32`
- Add `ENCRYPTION_KEY` to `.env.example`
- Load in Spring config
- **Acceptance**: Key loads correctly from env

##### 3.3 Security Config Base
- Create `SecurityConfig` class
- Configure public paths: `/healthz`, `/webhook/**`, `/auth/**`
- Configure secured paths: `/api/**`
- Set up CORS for frontend origin
- **Acceptance**: Security rules applied correctly

---

### Phase 4: Monzo OAuth Flow
**Duration: ~1 week**
**Goal:** Implement the one-time OAuth flow that requires user approval in Monzo app

#### OAuth Flow Diagram

```
User clicks "Connect Monzo"
         │
         ▼
┌─────────────────────────────────────────────┐
│ GET /auth/monzo/connect                     │
│  - Generate random state token              │
│  - Store state in session                   │
│  - Redirect to:                             │
│    https://auth.monzo.com/?                 │
│      client_id={id}&                        │
│      redirect_uri={uri}&                    │
│      response_type=code&                    │
│      state={state}                          │
└─────────────────────────────────────────────┘
         │
         ▼
    [User approves in Monzo app - SCA]
         │
         ▼
┌─────────────────────────────────────────────┐
│ GET /auth/callback?code={code}&state={state}│
│  - Validate state matches session           │
│  - POST to Monzo /oauth2/token              │
│  - Receive access_token + refresh_token     │
│  - Encrypt & store in database              │
│  - Trigger backfill job                     │
│  - Redirect to dashboard                    │
└─────────────────────────────────────────────┘
```

#### Tasks

##### 4.1 Monzo Developer Setup
- Register app in Monzo Developer Portal
- Get `client_id` and `client_secret`
- Configure redirect URI (e.g., `http://localhost:8080/auth/callback`)
- Add credentials to `.env`
- **Acceptance**: Credentials obtained and stored securely

##### 4.2 OAuth Controller - Initiate
- Create `AuthController` with `GET /auth/monzo/connect`
- Generate cryptographically secure state token
- Store state in session (or signed cookie)
- Build Monzo auth URL with params
- Return redirect
- **Acceptance**: Redirects to Monzo with correct params

##### 4.3 OAuth Controller - Callback
- `GET /auth/callback` endpoint
- Validate state token matches session
- Handle error params (user denied, etc.)
- Call token exchange service
- Redirect to dashboard on success
- **Acceptance**: Successfully handles callback

##### 4.4 Token Exchange Client
- Create `MonzoAuthClient` service
- Method: `exchangeCode(String code) -> TokenResponse`
- HTTP POST to `https://api.monzo.com/oauth2/token`
- Parse JSON response
- Handle errors (invalid code, etc.)
- **Acceptance**: Successfully exchanges code for tokens

##### 4.5 Token Storage Service
- Create `TokenService.storeTokens()`
- Encrypt access_token and refresh_token
- Calculate `expires_at` from `expires_in`
- Upsert to database atomically
- **Acceptance**: Tokens stored encrypted in database

##### 4.6 Simple Web UI - Connect Button
- Create minimal HTML page
- "Connect Monzo" button linking to `/auth/monzo/connect`
- Display connection status
- (Can be temporary dev UI, replaced by React later)
- **Acceptance**: Can initiate OAuth from browser

---

### Phase 5: Token Refresh & Management
**Duration: ~4-5 days**
**Goal:** Implement automatic token refresh so batch jobs work without user interaction

#### Token Refresh Flow

```
┌──────────────┐                      ┌───────────────┐      ┌─────────────────┐
│ Your Server  │                      │  Monzo API    │      │   PostgreSQL    │
└──────┬───────┘                      └───────┬───────┘      └────────┬────────┘
       │                                      │                       │
       │ 1. Need to make API call             │                       │
       │────────────────────────────────────────────────────────────>│
       │                                      │                       │
       │ 2. Read stored tokens                │                       │
       │<───────────────────────────────────────────────────────────│
       │                                      │                       │
       │ 3. Check: now >= expires_at - 60s?   │                       │
       │                                      │                       │
       │ IF YES: Refresh needed               │                       │
       │                                      │                       │
       │ 4. POST /oauth2/token                │                       │
       │    grant_type=refresh_token          │                       │
       │────────────────────────────────────>│                       │
       │                                      │                       │
       │ 5. Receive NEW tokens                │                       │
       │<────────────────────────────────────│                       │
       │                                      │                       │
       │ 6. Store both tokens atomically      │                       │
       │    (old refresh_token now invalid!)  │                       │
       │────────────────────────────────────────────────────────────>│
       │                                      │                       │
       │ 7. Return valid access_token         │                       │
```

#### Tasks

##### 5.1 Token Refresh Client
- Add to `MonzoAuthClient`: `refreshToken(String refreshToken) -> TokenResponse`
- HTTP POST to `https://api.monzo.com/oauth2/token` with `grant_type=refresh_token`
- **Acceptance**: Successfully refreshes tokens

##### 5.2 Get Valid Token Logic
- Create `TokenService.getValidAccessToken(String userId)`
- Load tokens from database
- Check if expired or expiring soon (within 60 seconds)
- If yes, call refresh and store new tokens
- Return valid access token
- **Acceptance**: Automatically refreshes expired tokens

##### 5.3 Atomic Token Update
- Ensure `storeTokens()` uses `@Transactional`
- Both access and refresh tokens updated together
- Handle concurrent requests (optimistic locking)
- **Acceptance**: No race conditions on token update

##### 5.4 Refresh Failure Handling
- Detect refresh failures (401, invalid_grant)
- Mark account as "disconnected"
- Surface to user: "Please reconnect your Monzo account"
- **Acceptance**: Graceful handling of revoked tokens

##### 5.5 Scheduled Token Check (Optional)
- `@Scheduled(fixedRate = 3600000)` - every hour
- Find tokens expiring in next hour
- Proactively refresh them
- Reduces latency on first request
- **Acceptance**: Tokens refreshed before they expire

---

### Phase 6: Account & Transaction Fetching
**Duration: ~1 week**
**Goal:** Fetch and store user's Monzo accounts and transactions

#### Tasks

##### 6.1 Monzo API Client
- Create `MonzoApiClient` service
- Inject `TokenService` for automatic token management
- Add common headers (Authorization: Bearer)
- Handle rate limits (429 responses)
- Handle errors consistently
- **Acceptance**: Can make authenticated API calls

##### 6.2 List Accounts
- Method: `getAccounts() -> List<Account>`
- Call `GET https://api.monzo.com/accounts`
- Parse response, filter by `account_type` if needed
- Store in database
- **Acceptance**: Accounts fetched and stored

##### 6.3 List Transactions
- Method: `getTransactions(accountId, since, before, limit)`
- Call `GET https://api.monzo.com/transactions`
- Handle pagination (loop until no more results)
- **Acceptance**: Can fetch transactions with pagination

##### 6.4 Transaction Upsert
- Create `TransactionSyncService.upsertTransaction()`
- Check if `monzo_tx_id` exists
- Insert or update accordingly
- Handle settled status changes
- **Acceptance**: No duplicate transactions, updates work

##### 6.5 Initial Backfill Job
- Trigger after successful OAuth callback
- Run in background thread/async
- Fetch transactions from 6-12 months ago
- Must complete within 5-minute window!
- Track progress, handle failures
- **Acceptance**: Historical data captured immediately after OAuth

##### 6.6 Delta Sync Service
- Store `last_sync_at` timestamp per account
- On sync, fetch only `since=last_sync_at`
- Update timestamp after successful sync
- **Acceptance**: Incremental syncs are efficient

---

### Phase 7: Webhook Integration
**Duration: ~4-5 days**
**Goal:** Receive real-time transaction notifications from Monzo

#### Webhook Flow

```
Monzo Backend
      │
      │ Transaction occurs
      │
      ▼
POST /webhook/monzo (via Cloudflare Tunnel)
      │
      ▼
┌─────────────────────────────────────────────┐
│ 1. Validate signature (if provided)         │
│ 2. Check idempotency (tx_id in deliveries?) │
│ 3. Return 200 OK immediately (<200ms)       │
│ 4. Async: Process transaction               │
│ 5. Async: Update rollups                    │
└─────────────────────────────────────────────┘
```

#### Tasks

##### 7.1 Webhook Controller
- Create `WebhookController` with `POST /webhook/monzo`
- Parse `transaction.created` payload
- Return 200 OK quickly
- **Acceptance**: Endpoint receives webhooks

##### 7.2 Signature Validation
- Check `X-Monzo-Signature` header if present
- Validate HMAC signature
- Reject invalid requests
- **Acceptance**: Only valid webhooks accepted

##### 7.3 Idempotency Check
- Check `webhook_deliveries` table for `transaction_id`
- If exists, skip processing (return 200 OK)
- If new, record delivery and proceed
- **Acceptance**: Duplicate webhooks ignored

##### 7.4 Async Processing
- Use `@Async` or queue (BlockingQueue, Redis)
- Process transaction in background
- Don't block webhook response
- **Acceptance**: Response time < 200ms

##### 7.5 Webhook Registration
- After OAuth, call `POST https://api.monzo.com/webhooks`
- Register your webhook URL
- Store webhook ID for later cleanup
- **Acceptance**: Webhook auto-registered after OAuth

##### 7.6 Cloudflare Tunnel Setup
- Configure tunnel to expose `/webhook/monzo`
- Update Monzo developer portal with public URL
- Test end-to-end
- **Acceptance**: Webhooks received from Monzo via tunnel

---

### Phase 8: Scheduled Batch Sync
**Duration: ~3-4 days**
**Goal:** Nightly job to catch any missed transactions

#### Batch Sync Flow

```
┌──────────────┐    ┌─────────────────┐    ┌───────────────┐
│   Scheduler  │    │  Token Service  │    │   Monzo API   │
│  (3:00 AM)   │    │                 │    │               │
└──────┬───────┘    └────────┬────────┘    └───────┬───────┘
       │                     │                     │
       │ 1. Trigger sync     │                     │
       │────────────────────>│                     │
       │                     │                     │
       │                     │ 2. Get valid token  │
       │                     │    (auto-refresh)   │
       │                     │────────────────────>│
       │                     │<────────────────────│
       │                     │                     │
       │ 3. Fetch transactions since last_sync     │
       │─────────────────────────────────────────>│
       │                     │                     │
       │ 4. Upsert all (idempotent)               │
       │                     │                     │
       │ 5. Update last_sync_at                   │
       │                     │                     │

✅ Runs completely unattended - no user interaction!
```

#### Tasks

##### 8.1 Batch Sync Job
- Create `@Scheduled(cron = "0 0 3 * * *")` method
- Or use `@Scheduled(fixedDelay = 86400000)` for daily
- Call sync service for each connected account
- **Acceptance**: Job runs at 3 AM daily

##### 8.2 Sync Logic
- For each account:
  - Get valid token (auto-refresh if needed)
  - Fetch transactions since `last_sync_at`
  - Upsert all transactions
  - Update `last_sync_at`
- **Acceptance**: Catches missed webhooks

##### 8.3 Sync Status Tracking
- Add `sync_status` table or columns
- Track: `last_sync_at`, `last_sync_status`, `error_message`
- **Acceptance**: Can see sync history

##### 8.4 Error Alerting
- Log errors with appropriate level
- Optional: Send notification on repeated failures
- Surface disconnected accounts in dashboard
- **Acceptance**: Failures are visible

---

## MVP Path (Fastest to Validate Batch Works)

If you want to prove the core concept quickly (2-3 weeks):

```
MVP Checklist:
├── 1.1 Spring Boot project
├── 1.2 Docker Compose (PostgreSQL)
├── 2.2 OAuth tokens table + entity
├── 3.1 Encryption service
├── 4.2 OAuth initiate endpoint
├── 4.3 OAuth callback endpoint
├── 4.4 Token exchange client
├── 4.5 Token storage
├── 5.1 Token refresh client
├── 5.2 Get valid token logic
├── 6.1 Monzo API client
└── 6.3 List transactions (manual test)

Result: Can authenticate, store tokens, refresh automatically,
        and make API calls from server without user interaction!
```

---

## Security Checklist

### Token Storage
- [ ] Tokens encrypted at rest (AES-256-GCM)
- [ ] Encryption key in environment variable (not in code)
- [ ] Refresh tokens updated atomically
- [ ] Failed refreshes mark account as disconnected

### API Security
- [ ] HTTPS only (via Cloudflare Tunnel)
- [ ] CORS restricted to frontend origin
- [ ] Rate limiting on API endpoints
- [ ] Input validation on all endpoints

### Webhook Security
- [ ] Signature validation (if Monzo provides)
- [ ] Idempotency to prevent duplicate processing
- [ ] Fast response (< 200ms)
- [ ] Public endpoint only for `/webhook/monzo`

---

## Notes

- **Confidential Client**: Your server-side app qualifies as a confidential client, meaning you get refresh tokens!
- **Token Rotation**: Each refresh returns a NEW refresh token - you must store it!
- **5-Minute Window**: Critical! Fetch all historical data immediately after OAuth
- **Belt & Braces**: Use BOTH webhooks (real-time) AND batch sync (catch missed)

---

**Last Updated**: December 2024
**Author**: Development Planning
**Status**: Ready for Implementation
