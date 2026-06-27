# Monzo OAuth Flow

This document describes the complete OAuth flow for connecting a Monzo bank account to Budgeteer, including state management, token exchange, and connection persistence.

## Overview

Budgeteer uses **OAuth 2.0 Authorization Code Flow** to securely connect to Monzo. This provides:
- User explicitly approves access
- Tokens never exposed in browser URL
- CSRF protection via state parameter
- Secure token storage with encryption

## Prerequisites

Before connecting to Monzo, the user must be **authenticated with Budgeteer** (completed the magic link flow described in [User Authentication Flow](./user-authentication-flow.md)).

## Components Involved

| Component | Type | Responsibility |
|-----------|------|----------------|
| `MonzoController` | REST Controller | HTTP endpoints for Monzo integration |
| `MonzoOAuthService` | Service | OAuth flow orchestration, state management |
| `MonzoClient` | Service | Monzo API communication, 401 handling |
| `MonzoConnectionService` | Service | Connection CRUD, token encryption |
| `EncryptionService` | Service | AES-256-GCM encryption for tokens |
| `OAuthStateRepository` | Repository | OAuth state persistence |
| `MonzoConnectionRepository` | Repository | Connection persistence |
| `Monzo API` | External | Monzo's OAuth and banking APIs |

---

## 1. Initiate OAuth Flow

### Happy Path

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant JWF as JweAuthFilter
    participant MC as MonzoController
    participant MOS as MonzoOAuthService
    participant OSR as OAuthStateRepository
    participant MP as MonzoProperties

    U->>+JWF: POST /api/v1/monzo/connect<br/>Cookie: budgeteer_access_token
    JWF->>JWF: Validate JWE token
    JWF->>JWF: Set SecurityContext with userId
    JWF->>+MC: Continue (authenticated)
    
    Note over MC: @CurrentUser User injected
    MC->>+MOS: initiateOAuthFlow(user)
    
    MOS->>MOS: Generate secure random state (32 bytes)
    MOS->>MOS: URL-encode state
    MOS->>+OSR: save(OAuthState)<br/>user, state, expiresAt(+10min)
    OSR-->>-MOS: OAuthState saved
    
    MOS->>+MP: Get clientId, redirectUri
    MP-->>-MOS: Monzo OAuth config
    
    MOS->>MOS: Build authorization URL:<br/>https://auth.monzo.com/?<br/>client_id=xxx&<br/>redirect_uri=xxx&<br/>response_type=code&<br/>state={state}
    
    MOS-->>-MC: authorizationUrl
    MC-->>-U: 200 OK<br/>{authorizationUrl: "https://auth.monzo.com/..."}
    
    Note over U: Frontend redirects user<br/>to Monzo authorization page
```

### State in Database

```mermaid
erDiagram
    OAUTH_STATES {
        uuid id PK
        uuid user_id FK
        string state "Unique, URL-safe"
        timestamp expires_at "10 minutes"
        boolean used "Default false"
        timestamp created_at
    }
    USERS ||--o{ OAUTH_STATES : has
```

---

## 2. User Approves at Monzo

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant M as Monzo Auth Page
    participant MA as Monzo App

    U->>+M: Redirected to auth.monzo.com
    M->>M: Show login (if needed)
    M-->>U: Monzo login page
    
    U->>M: Login with email
    M->>MA: Send push notification
    MA-->>U: "Approve in Monzo app"
    
    Note over U: User opens Monzo app
    U->>MA: Tap "Approve"
    MA->>M: Authorization approved
    
    M-->>-U: Redirect to:<br/>budgeteer.dev/api/v1/monzo/callback?<br/>code={auth_code}&state={state}
```

---

## 3. OAuth Callback (Token Exchange)

### Happy Path

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant MC as MonzoController
    participant MOS as MonzoOAuthService
    participant OSR as OAuthStateRepository
    participant MZ as Monzo API
    participant MCS as MonzoConnectionService
    participant ES as EncryptionService
    participant MCR as MonzoConnectionRepository

    U->>+MC: GET /api/v1/monzo/callback<br/>?code={code}&state={state}
    
    Note over MC: No auth needed (public endpoint)<br/>but state validates user
    
    MC->>+MOS: verifyStateAndGetUser(state)
    MOS->>+OSR: findByState(state)
    OSR-->>-MOS: Optional<OAuthState>
    
    alt State not found
        MOS-->>MC: throw ApiException<br/>OAUTH_STATE_INVALID
        MC-->>U: 400 Bad Request
    else State found
        MOS->>MOS: Check state.isUsed()
        alt Already used (replay attack)
            MOS-->>MC: throw ApiException<br/>OAUTH_STATE_INVALID
            MC-->>U: 400 Bad Request
        else Not used
            MOS->>MOS: Check state.isExpired()
            alt Expired (>10 min)
                MOS-->>MC: throw ApiException<br/>OAUTH_STATE_EXPIRED
                MC-->>U: 400 Bad Request
            else Valid
                MOS->>OSR: state.markUsed()
                MOS-->>-MC: User
            end
        end
    end
    
    MC->>+MOS: exchangeCodeForTokens(code)
    MOS->>+MZ: POST /oauth2/token<br/>grant_type=authorization_code<br/>client_id, client_secret<br/>code={code}<br/>redirect_uri
    MZ-->>-MOS: {access_token, refresh_token,<br/>expires_in, user_id}
    MOS->>MOS: Calculate expiresAt from expires_in
    MOS-->>-MC: TokenResponse
    
    MC->>+MOS: getMonzoUserId(accessToken)
    MOS->>+MZ: GET /ping/whoami<br/>Authorization: Bearer {token}
    MZ-->>-MOS: {authenticated: true, user_id: "..."}
    MOS-->>-MC: monzoUserId
    
    MC->>+MCS: createConnection(userId, monzoUserId,<br/>accessToken, refreshToken, expiresAt)
    MCS->>+ES: encrypt(accessToken)
    ES->>ES: AES-256-GCM encrypt
    ES-->>-MCS: encryptedAccessToken
    MCS->>+ES: encrypt(refreshToken)
    ES-->>-MCS: encryptedRefreshToken
    
    MCS->>+MCR: save(MonzoConnection)<br/>user, monzoUserId,<br/>encryptedTokens, expiresAt
    MCR-->>-MCS: MonzoConnection
    MCS-->>-MC: MonzoConnection
    
    MC-->>-U: 200 OK<br/>{success: true, connection: {...}}
```

### Unhappy Paths - OAuth Callback

```mermaid
flowchart TD
    A[GET /api/v1/monzo/callback] --> B{State parameter?}
    B -->|Missing| C[400 Bad Request<br/>MISSING_STATE]
    B -->|Present| D{State in database?}
    D -->|No| E[400 Bad Request<br/>OAUTH_STATE_INVALID]
    D -->|Yes| F{State already used?}
    F -->|Yes| E
    F -->|No| G{State expired?}
    G -->|Yes| H[400 Bad Request<br/>OAUTH_STATE_EXPIRED]
    G -->|No| I[Mark state as used]
    I --> J{Code parameter?}
    J -->|Missing| K[400 Bad Request<br/>MISSING_CODE]
    J -->|Present| L[Exchange code for tokens]
    L --> M{Token exchange successful?}
    M -->|No| N[400 Bad Request<br/>TOKEN_EXCHANGE_FAILED]
    M -->|Yes| O[Get Monzo user ID]
    O --> P{Whoami successful?}
    P -->|No| Q[500 Internal Error<br/>MONZO_API_ERROR]
    P -->|Yes| R[Create connection]
    R --> S[200 OK<br/>Connection created]
    
    style C fill:#f66,color:#fff
    style E fill:#f66,color:#fff
    style H fill:#f66,color:#fff
    style K fill:#f66,color:#fff
    style N fill:#f66,color:#fff
    style Q fill:#f66,color:#fff
    style S fill:#6f6,color:#fff
```

### Security: Replay Attack Prevention

```mermaid
sequenceDiagram
    participant A as Attacker
    participant B as Budgeteer API
    participant DB as Database

    Note over A: Attacker captured state from<br/>user's OAuth redirect

    A->>+B: GET /api/v1/monzo/callback<br/>?code=valid&state=captured
    B->>+DB: Find state
    DB-->>-B: State found
    
    alt First use (legitimate user)
        B->>DB: Mark state as USED
        B-->>A: 200 OK (connection created)
    else Second use (attacker)
        B->>DB: Check state.isUsed()
        Note over B: State already used!
        B-->>-A: 400 Bad Request<br/>OAUTH_STATE_INVALID
    end
    
    Note over A: Attack prevented:<br/>State can only be used once
```

---

## 4. Token Storage & Encryption

```mermaid
flowchart LR
    subgraph Input["Plain Tokens (in memory only)"]
        A[access_token]
        B[refresh_token]
    end
    
    subgraph Encryption["EncryptionService"]
        C["AES-256-GCM + Random IV"]
    end
    
    subgraph Output["Database (encrypted)"]
        E["encrypted_access_token"]
        F["encrypted_refresh_token"]
    end
    
    A --> C
    B --> C
    C --> E
    C --> F
```

> **Encryption Format:** Each encrypted token contains: `IV (12 bytes) || Ciphertext || AuthTag (16 bytes)`

### MonzoConnection Entity

```mermaid
erDiagram
    MONZO_CONNECTIONS {
        uuid id PK
        uuid user_id FK
        string monzo_user_id "From Monzo API"
        text encrypted_access_token "AES-256-GCM encrypted"
        text encrypted_refresh_token "AES-256-GCM encrypted"
        timestamp token_expires_at "When access token expires"
        timestamp disconnected_at "Soft delete"
        timestamp created_at
        timestamp updated_at
    }
    USERS ||--o{ MONZO_CONNECTIONS : has
```

---

## 5. Connection Management

### List Connections

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant MC as MonzoController
    participant MCS as MonzoConnectionService
    participant MCR as MonzoConnectionRepository

    U->>+MC: GET /api/v1/monzo/connections<br/>Cookie: budgeteer_access_token
    
    Note over MC: @CurrentUser User injected<br/>from JWE token
    
    MC->>+MCS: listActiveConnections(userId)
    MCS->>+MCR: findByUserIdAndDisconnectedAtIsNull(userId)
    MCR-->>-MCS: List<MonzoConnection>
    
    Note over MCS: Tokens NOT included in response<br/>(security - no decryption needed)
    
    MCS-->>-MC: List<MonzoConnection>
    MC->>MC: Map to MonzoConnectionResponse DTOs
    MC-->>-U: 200 OK<br/>[{id, monzoUserId, createdAt, tokenExpiresAt}]
```

### Get Connection Status

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant MC as MonzoController
    participant MCS as MonzoConnectionService
    participant MCR as MonzoConnectionRepository

    U->>+MC: GET /api/v1/monzo/status<br/>Cookie: budgeteer_access_token
    
    MC->>+MCS: hasActiveConnection(userId)
    MCS->>+MCR: existsByUserIdAndDisconnectedAtIsNull(userId)
    MCR-->>-MCS: boolean
    MCS-->>-MC: boolean
    
    MC->>+MCS: countActiveConnections(userId)
    MCS->>+MCR: countByUserIdAndDisconnectedAtIsNull(userId)
    MCR-->>-MCS: long
    MCS-->>-MC: long
    
    MC-->>-U: 200 OK<br/>{connected: true/false, connectionCount: N}
```

### Disconnect (Soft Delete)

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant MC as MonzoController
    participant MCS as MonzoConnectionService
    participant MCR as MonzoConnectionRepository

    U->>+MC: DELETE /api/v1/monzo/connections/{id}<br/>Cookie: budgeteer_access_token
    
    MC->>+MCS: disconnectConnection(connectionId, userId)
    MCS->>+MCR: findById(connectionId)
    MCR-->>-MCS: Optional<MonzoConnection>
    
    alt Not found
        MCS-->>MC: throw ApiException<br/>CONNECTION_NOT_FOUND
        MC-->>U: 404 Not Found
    else Found but wrong user (security)
        MCS->>MCS: Check connection.userId == requestUserId
        MCS-->>MC: throw ApiException<br/>CONNECTION_NOT_FOUND
        MC-->>U: 404 Not Found
        Note over MCS: Don't reveal existence<br/>of other users' connections
    else Found and belongs to user
        MCS->>MCS: connection.disconnect()
        Note over MCS: Sets disconnectedAt = now()
        MCS->>+MCR: save(connection)
        MCR-->>-MCS: Connection updated
        MCS-->>-MC: void
        MC-->>-U: 204 No Content
    end
```

---

## 6. User Isolation

```mermaid
flowchart TD
    subgraph "User A"
        A1[Connection 1]
        A2[Connection 2]
    end
    
    subgraph "User B"
        B1[Connection 3]
    end
    
    subgraph "API Calls"
        C[GET /api/v1/monzo/connections]
        D[DELETE /api/v1/monzo/connections/3]
    end
    
    C -->|User A| A1
    C -->|User A| A2
    C -.->|Cannot see| B1
    
    D -->|User A tries| B1
    B1 -->|404 Not Found| D
    
    style B1 fill:#f99
```

---

## Complete User Journey: Login to Connected

```mermaid
sequenceDiagram
    autonumber
    participant U as User
    participant B as Browser
    participant API as Budgeteer API
    participant M as Monzo
    participant DB as PostgreSQL

    rect rgb(240, 248, 255)
        Note over U,DB: Step 1: Already Authenticated
        Note over B: User has valid session<br/>(access token in cookie)
    end

    rect rgb(255, 248, 240)
        Note over U,DB: Step 2: Initiate Connection
        U->>B: Click "Connect Monzo"
        B->>API: POST /api/v1/monzo/connect
        API->>DB: Save OAuthState
        API-->>B: {authorizationUrl}
        B->>M: Redirect to auth.monzo.com
    end

    rect rgb(240, 255, 240)
        Note over U,DB: Step 3: Approve in Monzo
        M-->>U: Show authorization page
        U->>M: Approve access
        M-->>B: Redirect to callback<br/>with code + state
    end

    rect rgb(255, 255, 240)
        Note over U,DB: Step 4: Complete Connection
        B->>API: GET /api/v1/monzo/callback?code=...&state=...
        API->>DB: Validate & consume state
        API->>M: Exchange code for tokens
        M-->>API: access_token, refresh_token
        API->>M: GET /ping/whoami
        M-->>API: monzoUserId
        API->>API: Encrypt tokens
        API->>DB: Save MonzoConnection
        API-->>B: 200 OK {connection}
        B-->>U: "Monzo connected!"
    end

    rect rgb(240, 248, 255)
        Note over U,DB: Step 5: Use Connected Account
        U->>B: View accounts
        B->>API: GET /api/v1/monzo/accounts
        API->>DB: Get encrypted tokens
        API->>API: Decrypt access token
        API->>M: GET /accounts<br/>Authorization: Bearer {token}
        M-->>API: Account data
        API-->>B: Account list
        B-->>U: Display accounts
    end
```

---

## OAuth State Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Created: initiateOAuthFlow()
    
    Created --> Valid: Before expiry (10 min)
    Created --> Expired: After 10 minutes
    
    Valid --> Used: Callback received
    Used --> [*]: Connection created
    
    Expired --> [*]: Cleaned up by scheduler
```

**State Details:**
- **Created**: State stored in DB with user reference
- **Valid**: User at Monzo auth page (within 10 min window)
- **Used**: One-time use prevents replay attacks

---

## Token Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Fresh: OAuth callback complete
    
    Fresh --> Valid: Within expires_in period
    Valid --> Expiring: Near expiration
    Expiring --> Expired: Past expiration
    
    Valid --> Refreshed: Token refresh (Phase 3)
    Refreshed --> Valid: New tokens received
    
    Expired --> NeedsReauth: Refresh also expired
    NeedsReauth --> [*]: User must reconnect
```

**Token Details:**
- **Fresh**: Tokens encrypted and stored immediately after OAuth
- **Valid**: Token can be used to call Monzo API
- **Expiring**: Near expiration - proactive refresh recommended (future Phase 3)
- **Expired**: Must use refresh token or re-authenticate
- **NeedsReauth**: Both access and refresh tokens expired

---

## Security Considerations

### 1. State Parameter (CSRF Protection)
- **Random**: 32 bytes of cryptographically secure random data
- **URL-safe**: Base64 URL encoded
- **Short-lived**: Expires after 10 minutes
- **Single-use**: Marked as used immediately on callback
- **User-bound**: Associated with specific user in database

### 2. Token Storage
- **Never in browser**: Tokens only exist server-side
- **Encrypted at rest**: AES-256-GCM with unique IV
- **Key rotation ready**: Can re-encrypt if key compromised

### 3. User Isolation
- **Authorization check**: Every connection operation verifies ownership
- **No information leakage**: 404 for other users' connections (not 403)

### 4. Callback Security
- **Public endpoint**: No auth needed (state provides user binding)
- **Rate limited**: Prevent brute-force state guessing (future)
- **State entropy**: 256 bits makes guessing infeasible

---

## Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `OAUTH_STATE_INVALID` | 400 | State not found or already used |
| `OAUTH_STATE_EXPIRED` | 400 | State older than 10 minutes |
| `TOKEN_EXCHANGE_FAILED` | 400 | Monzo rejected code exchange |
| `MONZO_API_ERROR` | 500 | Error calling Monzo API |
| `CONNECTION_NOT_FOUND` | 404 | Connection doesn't exist or wrong user |
| `ENCRYPTION_ERROR` | 500 | Failed to encrypt/decrypt tokens |

---

## Related Documentation

- [User Authentication Flow](./user-authentication-flow.md)
- [Monzo Token Persistence Feature](../features/MONZO-TOKEN-PERSISTENCE.md)
- [Monzo Auth Flow](../MONZO-AUTH-FLOW.md)
- [Security Architecture](../SECURITY-ARCHITECTURE.md)
- [Testing Guide](../TESTING.md)
