# Feature: Monzo Token Persistence

> Secure storage of Monzo OAuth tokens associated with authenticated app users.

---

## 📋 Feature Summary

| Field | Value |
|-------|-------|
| **Feature Branch** | `feature/monzo-token-persistence` |
| **Status** | ✅ **Implemented** |
| **Priority** | P1 - Required for core functionality |
| **Completed** | March 2026 |
| **Dependencies** | User Authentication ✅ |
| **Blocks** | Transaction sync, all Monzo data features |

---

## 🎯 Scope

### Implemented ✅
- [x] AES-256-GCM encryption service for tokens
- [x] Monzo connection entity with encrypted token storage
- [x] Associate Monzo accounts with authenticated app users
- [x] Support multiple Monzo accounts per user
- [x] Secure token retrieval for API calls
- [x] Endpoint to list user's Monzo connections
- [x] Endpoint to disconnect (soft delete) a Monzo account
- [x] Refactored OAuth flow to require authentication
- [x] OAuth state management (CSRF protection)
- [x] Reconnection support (replaces existing connection)
- [x] MonzoClient with 401 handling (revoked token detection)

### Out of Scope (Deferred)
- Automatic token refresh (separate feature - Queue #2)
- Token refresh scheduling/jobs
- Webhook registration for connections
- Connection health monitoring
- Key rotation automation
- Connection pooling, retries, circuit breaker (MonzoClient Resilience - Backlog)

---

## 🏗️ Components

### Files Created

| Component | Path | Description |
|-----------|------|-------------|
| **Migrations** | | |
| V5 Migration | `db/migration/V5__create_monzo_connections.sql` | Monzo connections table |
| V6 Migration | `db/migration/V6__create_oauth_states.sql` | OAuth state table |
| **Entities** | | |
| MonzoConnection | `domain/monzo/MonzoConnection.java` | JPA entity with encrypted tokens |
| OAuthState | `domain/oauth/OAuthState.java` | CSRF state tracking |
| **Repositories** | | |
| MonzoConnectionRepository | `domain/monzo/MonzoConnectionRepository.java` | Connection CRUD |
| OAuthStateRepository | `domain/oauth/OAuthStateRepository.java` | State management |
| **Services** | | |
| EncryptionService | `service/EncryptionService.java` | AES-256-GCM encrypt/decrypt |
| MonzoConnectionService | `service/MonzoConnectionService.java` | Connection CRUD operations |
| MonzoOAuthService | `service/MonzoOAuthService.java` | OAuth flow orchestration |
| MonzoClient | `service/MonzoClient.java` | Monzo API communication |
| **Config** | | |
| EncryptionProperties | `config/EncryptionProperties.java` | Encryption key config |
| MonzoProperties | `config/MonzoProperties.java` | Monzo API config |
| **Controllers** | | |
| MonzoController | `api/monzo/MonzoController.java` | REST endpoints |
| **DTOs** | | |
| MonzoConnectionResponse | `api/monzo/dto/MonzoConnectionResponse.java` | Connection details (no tokens) |
| MonzoConnectResponse | `api/monzo/dto/MonzoConnectResponse.java` | OAuth URL response |
| ConnectionStatusResponse | `api/monzo/dto/ConnectionStatusResponse.java` | Connection status |

---

## 🔌 API Endpoints

### POST `/api/monzo/connect`

Initiate Monzo OAuth flow.

**Request:** Requires valid `access_token` cookie

**Response (200):**
```json
{
  "success": true,
  "data": {
    "authorizationUrl": "https://auth.monzo.com/?..."
  }
}
```

---

### GET `/api/monzo/callback`

Handle Monzo OAuth callback (public endpoint - state validates user).

**Query Params:**
- `code` - Authorization code from Monzo
- `state` - State parameter for CSRF validation

**Response (200):**
```json
{
  "success": true,
  "data": {
    "message": "Monzo account connected successfully",
    "connectionId": "uuid"
  }
}
```

**Error Responses:**
- `400 OAUTH_STATE_INVALID` - State not found or already used
- `400 OAUTH_STATE_EXPIRED` - State expired (>10 min)
- `400 OAUTH_CODE_MISSING` - No authorization code provided
- `400 OAUTH_ACCESS_DENIED` - User denied access in Monzo app

---

### GET `/api/monzo/connections`

List user's connected Monzo accounts.

**Response (200):**
```json
{
  "success": true,
  "data": {
    "connections": [
      {
        "id": "uuid",
        "monzoUserId": "user_abc123",
        "connectedAt": "2026-03-15T12:00:00Z",
        "tokenExpiresAt": "2026-03-15T18:00:00Z"
      }
    ]
  }
}
```

---

### GET `/api/monzo/status`

Get connection status summary.

**Response (200):**
```json
{
  "success": true,
  "data": {
    "connected": true,
    "connectionCount": 1
  }
}
```

---

### DELETE `/api/monzo/connections/{id}`

Disconnect a Monzo account (soft delete).

**Response (204):** No content

---

## 🗃️ Database Schema

### monzo_connections
```sql
CREATE TABLE monzo_connections (
    id                      UUID PRIMARY KEY,
    user_id                 UUID NOT NULL REFERENCES users(id),
    monzo_user_id           VARCHAR(255) NOT NULL,
    access_token_encrypted  TEXT NOT NULL,
    refresh_token_encrypted TEXT,
    token_expires_at        TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    disconnected_at         TIMESTAMP WITH TIME ZONE,
    
    CONSTRAINT uk_active_connection UNIQUE (user_id, monzo_user_id)
        WHERE (disconnected_at IS NULL)
);
```

### oauth_states
```sql
CREATE TABLE oauth_states (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id),
    state      VARCHAR(255) NOT NULL UNIQUE,
    used       BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

---

## 🔒 Security Features

| Feature | Implementation |
|---------|----------------|
| Token encryption | AES-256-GCM with unique IV per token |
| CSRF protection | Random 32-byte state, 10-min expiry, single use |
| Replay prevention | State marked used immediately on callback |
| User isolation | All queries scoped to authenticated user |
| 401 detection | MonzoClient throws `PROVIDER_CONNECTION_REVOKED` |
| No token exposure | Tokens never in API responses or logs |

---

## 🧪 Test Coverage

### Unit Tests ✅
- `EncryptionServiceTest` - 23 tests
- `MonzoConnectionServiceTest` - 18 tests
- `MonzoOAuthServiceTest` - 10 tests

### Integration Tests ✅
- `MonzoOAuthFlowIT` - 8 tests (full OAuth flow with WireMock)
- `MonzoConnectionRepositoryIT` - 12 tests
- `OAuthStateRepositoryIT` - 14 tests

---

## 📝 Implementation Notes

### Decisions Made
1. **MonzoClient extracted from MonzoOAuthService** (March 2026)
   - Cleaner separation: OAuth orchestration vs API communication
   - Centralized 401 handling for token revocation
   - Prepares for future resilience features (retries, circuit breaker)

2. **Reconnection replaces existing connection** (March 2026)
   - Same Monzo user ID = soft delete old, create new
   - Handles token revocation scenarios gracefully
   - Avoids duplicate connection issues

3. **OAuth state is separate from connection**
   - State is short-lived (10 min) for CSRF only
   - Connection is long-lived with encrypted tokens
   - Allows cleanup without affecting connections

### Error Handling
- `PROVIDER_CONNECTION_REVOKED` - Token was revoked in Monzo app
- `OAUTH_STATE_INVALID` - Replay attack or tampered state
- `OAUTH_STATE_EXPIRED` - User took >10 min at Monzo
- `OAUTH_ACCESS_DENIED` - User clicked "Deny" in Monzo app

---

## 📊 Definition of Done ✅

- [x] Database migrations created and tested
- [x] EncryptionService implemented with unit tests
- [x] MonzoConnection entity and repository created
- [x] MonzoConnectionService implemented with unit tests
- [x] OAuth flow requires authentication
- [x] MonzoClient with 401 handling
- [x] All endpoints implemented and documented
- [x] Tokens never exposed in API responses
- [x] End-to-end OAuth flow tested manually
- [x] 485 tests passing (unit + integration)
- [x] Feature doc updated with implementation notes

---

## 🔗 Related Documents

- [Security Architecture](../SECURITY-ARCHITECTURE.md)
- [Monzo OAuth Flow Diagram](../diagrams/monzo-oauth-flow.md)
- [Monzo Auth Flow](../MONZO-AUTH-FLOW.md)
- [Testing Guide](../TESTING.md)
- [Tasks Board](../../.cline/tasks.md)

---

**Created:** December 2024  
**Completed:** March 2026  
**Last Updated:** March 2026
