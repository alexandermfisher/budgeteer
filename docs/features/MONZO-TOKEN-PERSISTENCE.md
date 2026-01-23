# Feature: Monzo Token Persistence

> Secure storage of Monzo OAuth tokens associated with authenticated app users.

---

## 📋 Feature Summary

| Field | Value |
|-------|-------|
| **Feature Branch** | `feature/monzo-token-persistence` |
| **Status** | 🔵 Planned |
| **Priority** | P1 - Required for core functionality |
| **Estimated Effort** | 1-2 days |
| **Dependencies** | User Authentication (must be complete) |
| **Blocks** | Transaction sync, all Monzo data features |

---

## 🎯 Scope

### In Scope
- [ ] AES-256-GCM encryption service for tokens
- [ ] Monzo connection entity with encrypted token storage
- [ ] Associate Monzo accounts with authenticated app users
- [ ] Support multiple Monzo accounts per user
- [ ] Secure token retrieval for API calls
- [ ] Endpoint to list user's Monzo connections
- [ ] Endpoint to disconnect (soft delete) a Monzo account
- [ ] Refactor existing OAuth flow to require authentication

### Out of Scope (Deferred)
- Automatic token refresh (separate feature)
- Token refresh scheduling/jobs
- Webhook registration for connections
- Connection health monitoring
- Key rotation automation

---

## 🏗️ Components

### New Files to Create

| Component | Path | Description |
|-----------|------|-------------|
| **Migrations** | | |
| V5 Migration | `backend/src/main/resources/db/migration/V5__create_monzo_connections.sql` | Monzo connections table |
| **Entities** | | |
| MonzoConnection Entity | `backend/src/main/java/dev/amf/budgeteer/model/MonzoConnection.java` | JPA entity |
| **Repositories** | | |
| MonzoConnectionRepository | `backend/src/main/java/dev/amf/budgeteer/repository/MonzoConnectionRepository.java` | Spring Data JPA |
| **Services** | | |
| EncryptionService | `backend/src/main/java/dev/amf/budgeteer/service/EncryptionService.java` | AES-256-GCM encrypt/decrypt |
| MonzoConnectionService | `backend/src/main/java/dev/amf/budgeteer/service/MonzoConnectionService.java` | CRUD operations |
| **Config** | | |
| EncryptionProperties | `backend/src/main/java/dev/amf/budgeteer/config/EncryptionProperties.java` | Encryption key config |
| **Controllers** | | |
| MonzoController | `backend/src/main/java/dev/amf/budgeteer/controller/MonzoController.java` | Monzo connection endpoints |
| **DTOs** | | |
| MonzoConnectionResponse | `backend/src/main/java/dev/amf/budgeteer/dto/MonzoConnectionResponse.java` | Connection details (no tokens!) |

### Modified Files

| File | Changes |
|------|---------|
| `backend/src/main/java/dev/amf/budgeteer/controller/AuthController.java` | Refactor: require auth, use MonzoConnectionService |
| `backend/src/main/resources/application.properties` | Add MONZO_ENCRYPTION_KEY |
| `.env.example` | Add encryption key placeholder |

---

## 🔌 API Endpoints

### GET `/api/monzo/connections`

List user's connected Monzo accounts.

**Request:** Requires valid `access_token` cookie

**Response (200):**
```json
{
  "connections": [
    {
      "id": "uuid-here",
      "monzo_user_id": "user_abc123",
      "connected_at": "2024-12-31T12:00:00Z",
      "token_expires_at": "2024-12-31T18:00:00Z",
      "is_token_expired": false
    }
  ]
}
```

**Errors:**
- `401` - Not authenticated

---

### POST `/api/monzo/connect`

Initiate Monzo OAuth flow (redirect to Monzo).

**Request:** Requires valid `access_token` cookie

**Response (302 Redirect):**
- Redirects to Monzo authorization URL
- Stores state + user_id association for callback

**Errors:**
- `401` - Not authenticated

---

### GET `/api/monzo/callback`

Handle Monzo OAuth callback.

**Query Params:**
- `code` - Authorization code from Monzo
- `state` - State parameter for CSRF validation

**Response (302 Redirect):**
- Exchanges code for tokens
- Encrypts and stores tokens
- Redirects to connections page or dashboard

**Errors:**
- `400` - State mismatch (CSRF)
- `400` - Token exchange failed

---

### DELETE `/api/monzo/connections/{id}`

Disconnect a Monzo account (soft delete).

**Request:** Requires valid `access_token` cookie

**Response (200):**
```json
{
  "message": "Monzo account disconnected"
}
```

**Errors:**
- `401` - Not authenticated
- `404` - Connection not found or not owned by user

---

### GET `/api/monzo/connections/{id}/accounts`

List Monzo bank accounts for a connection (calls Monzo API).

**Request:** Requires valid `access_token` cookie

**Response (200):**
```json
{
  "accounts": [
    {
      "id": "acc_abc123",
      "type": "uk_retail",
      "description": "Current Account",
      "created": "2018-01-01T00:00:00Z"
    }
  ]
}
```

**Errors:**
- `401` - Not authenticated
- `404` - Connection not found
- `502` - Monzo API error

---

## 🗃️ Database Schema

See `docs/SECURITY-ARCHITECTURE.md` Section 11 for full schema.

**Tables created:**
- `monzo_connections`

**Key fields:**
```sql
CREATE TABLE monzo_connections (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id),
    monzo_user_id       VARCHAR(255) NOT NULL,
    access_token_enc    TEXT NOT NULL,       -- AES-256-GCM encrypted
    refresh_token_enc   TEXT NOT NULL,       -- AES-256-GCM encrypted
    token_expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    connected_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    disconnected_at     TIMESTAMP WITH TIME ZONE NULL,
    
    CONSTRAINT uk_monzo_connections_user_monzo UNIQUE (user_id, monzo_user_id)
);
```

---

## 🔒 Security Considerations

| Concern | Mitigation |
|---------|------------|
| Token theft from DB | AES-256-GCM encryption with unique IV per token |
| Encryption key exposure | Stored in env var, never logged |
| Unauthorized token access | Tokens only accessible via authenticated user's connections |
| Token exposure in responses | Never return encrypted tokens in API responses |
| Token logging | Never log decrypted tokens |
| Key rotation | Design supports future key versioning |

### Encryption Implementation

```java
// AES-256-GCM encryption
// - 32-byte key (256 bits)
// - 12-byte IV (unique per encryption)
// - Authentication tag ensures integrity

// Storage format: base64(IV + ciphertext + authTag)

// Encryption steps:
// 1. Generate random 12-byte IV
// 2. Encrypt with AES-256-GCM
// 3. Concatenate: IV + ciphertext + authTag
// 4. Base64 encode result
// 5. Store in database

// Decryption steps:
// 1. Base64 decode
// 2. Extract IV (first 12 bytes)
// 3. Extract ciphertext + authTag (remainder)
// 4. Decrypt with AES-256-GCM
// 5. Return plaintext
```

---

## 🧪 Test Coverage

### Unit Tests

| Test Class | Coverage |
|------------|----------|
| `EncryptionServiceTest` | Encrypt/decrypt, different IVs, invalid key, tampered ciphertext |
| `MonzoConnectionServiceTest` | Create, retrieve, list, soft delete connections |

### Integration Tests

| Test Class | Coverage |
|------------|----------|
| `MonzoControllerIntegrationTest` | Full OAuth flow with authenticated user |

### Test Cases Checklist

- [ ] Encrypt and decrypt token successfully
- [ ] Same plaintext produces different ciphertext (IV uniqueness)
- [ ] Detect tampered ciphertext (authentication tag)
- [ ] Fail decryption with wrong key
- [ ] Create new Monzo connection
- [ ] Retrieve decrypted tokens for user
- [ ] List only current user's connections
- [ ] Cannot access other user's connections (isolation)
- [ ] Soft delete connection
- [ ] Prevent duplicate connections (same user + monzo_user_id)
- [ ] Full OAuth flow with authenticated user
- [ ] OAuth flow fails without authentication

---

## 📝 Implementation Notes

*This section is updated during/after implementation with decisions, gotchas, and learnings.*

### Decisions Made


### Issues Encountered


### Learnings


---

## 📊 Definition of Done

- [ ] Database migration created and tested
- [ ] EncryptionService implemented with unit tests
- [ ] MonzoConnection entity and repository created
- [ ] MonzoConnectionService implemented with unit tests
- [ ] Existing OAuth flow refactored to require auth
- [ ] All endpoints implemented and documented
- [ ] Tokens never exposed in API responses
- [ ] End-to-end OAuth flow tested manually
- [ ] Code reviewed and merged to main
- [ ] Feature doc updated with implementation notes

---

## 🔗 Related Documents

- [Security Architecture](../SECURITY-ARCHITECTURE.md)
- [Monzo Auth Flow](../MONZO-AUTH-FLOW.md)
- [User Authentication Feature](./USER-AUTHENTICATION.md)
- [Tasks Board](../../.cline/tasks.md)

---

**Created:** December 2024  
**Last Updated:** December 2024
