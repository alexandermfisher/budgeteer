# User Authentication Flow

This document describes the complete authentication flow for Budgeteer, including magic link signup/login, session management, and token refresh.

## Overview

Budgeteer uses **passwordless authentication** via magic links sent to email. This provides:
- No passwords to remember or manage
- Email verification built into signup
- Secure, single-use tokens

## Components Involved

| Component | Type | Responsibility |
|-----------|------|----------------|
| `AuthController` | REST Controller | HTTP endpoints for auth |
| `AuthService` | Service | Orchestrates auth flow |
| `SessionService` | Service | Token creation & validation |
| `JweTokenService` | Service | JWE token encryption/decryption |
| `EmailService` | Service | Sends magic link emails |
| `CookieService` | Service | HTTP cookie management |
| `UserRepository` | Repository | User persistence |
| `MagicLinkTokenRepository` | Repository | Magic link persistence |
| `AppRefreshTokenRepository` | Repository | Refresh token persistence |

---

## 1. Magic Link Request (Signup/Login)

### Happy Path

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant AC as AuthController
    participant AS as AuthService
    participant UR as UserRepository
    participant MLR as MagicLinkTokenRepository
    participant ES as EmailService

    U->>+AC: POST /api/v1/auth/login<br/>{email: "user@example.com"}
    AC->>AC: Validate email format
    AC->>+AS: requestMagicLink(email)
    
    AS->>AS: Normalize email (lowercase, trim)
    AS->>+UR: findByEmailIgnoreCase(email)
    
    alt User exists
        UR-->>AS: User
    else New user (signup)
        AS->>UR: save(new User(email))
        UR-->>AS: User
    end
    
    AS->>AS: Generate secure random token (32 bytes)
    AS->>AS: Hash token with SHA-256
    AS->>+MLR: save(MagicLinkToken)<br/>user, tokenHash, expiresAt(+15min)
    MLR-->>-AS: MagicLinkToken
    
    AS->>+ES: sendMagicLink(email, rawToken)
    ES->>ES: Build email with link:<br/>/api/v1/auth/verify?token={rawToken}
    ES-->>-AS: Email sent
    
    AS-->>-AC: void (success)
    AC-->>-U: 200 OK<br/>{success: true, message: "Magic link sent"}
```

### Unhappy Paths

```mermaid
flowchart TD
    A[POST /api/v1/auth/login] --> B{Email valid?}
    B -->|No| C[400 Bad Request<br/>VALIDATION_ERROR]
    B -->|Yes| D[Normalize email]
    D --> E{Find or create user}
    E --> F[Generate magic link token]
    F --> G{Email service available?}
    G -->|No| H[500 Internal Error<br/>EMAIL_SEND_FAILED]
    G -->|Yes| I[Send email]
    I --> J{Email sent successfully?}
    J -->|No| H
    J -->|Yes| K[200 OK<br/>Magic link sent]
    
    style C fill:#f66,color:#fff
    style H fill:#f66,color:#fff
    style K fill:#6f6,color:#fff
```

---

## 2. Magic Link Verification

### Happy Path

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant AC as AuthController
    participant AS as AuthService
    participant SS as SessionService
    participant JTS as JweTokenService
    participant MLR as MagicLinkTokenRepository
    participant RTR as AppRefreshTokenRepository
    participant CS as CookieService

    U->>+AC: GET /api/v1/auth/verify?token={rawToken}
    AC->>+AS: verifyMagicLink(rawToken, userAgent, ipAddress)
    
    AS->>AS: Hash rawToken with SHA-256
    AS->>+MLR: findByTokenHashAndUsedFalse(tokenHash)
    MLR-->>-AS: Optional<MagicLinkToken>
    
    alt Token not found
        AS-->>AC: Optional.empty()
        AC-->>U: 401 Unauthorized<br/>INVALID_TOKEN
    else Token found
        AS->>AS: Check token.isExpired()
        
        alt Token expired
            AS-->>AC: Optional.empty()
            AC-->>U: 401 Unauthorized<br/>TOKEN_EXPIRED
        else Token valid
            AS->>MLR: markAsUsed(token)
            AS->>MLR: invalidateOtherTokens(user)
            AS->>AS: user.setEmailVerified(true)
            
            AS->>+SS: createSession(user, userAgent, ipAddress)
            SS->>+JTS: createAccessToken(user)
            JTS->>JTS: Create JWE with userId, exp(+15min)
            JTS-->>-SS: accessToken (encrypted JWE)
            
            SS->>SS: Generate refresh token (32 bytes)
            SS->>SS: Hash refresh token
            SS->>+RTR: save(AppRefreshToken)<br/>user, tokenHash, expiresAt(+7days)
            RTR-->>-SS: AppRefreshToken
            
            SS-->>-AS: SessionTokens(accessToken, refreshToken)
            AS-->>-AC: Optional<SessionTokens>
            
            AC->>+CS: setAccessTokenCookie(response, accessToken)
            CS-->>-AC: Cookie set
            AC->>+CS: setRefreshTokenCookie(response, refreshToken)
            CS-->>-AC: Cookie set
            
            AC-->>-U: 200 OK + HttpOnly Cookies<br/>{success: true, user: {...}}
        end
    end
```

### Unhappy Paths

```mermaid
flowchart TD
    A[GET /api/v1/auth/verify?token=...] --> B{Token provided?}
    B -->|No| C[400 Bad Request<br/>MISSING_TOKEN]
    B -->|Yes| D[Hash token]
    D --> E{Token exists in DB?}
    E -->|No| F[401 Unauthorized<br/>INVALID_TOKEN]
    E -->|Yes| G{Token already used?}
    G -->|Yes| F
    G -->|No| H{Token expired?}
    H -->|Yes| I[401 Unauthorized<br/>TOKEN_EXPIRED]
    H -->|No| J[Mark token used]
    J --> K[Create session]
    K --> L[Set cookies]
    L --> M[200 OK<br/>Authenticated]
    
    style C fill:#f66,color:#fff
    style F fill:#f66,color:#fff
    style I fill:#f66,color:#fff
    style M fill:#6f6,color:#fff
```

---

## 3. Authenticated Request (Using Access Token)

### Happy Path

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant JWF as JweAuthenticationFilter
    participant JTS as JweTokenService
    participant SC as SecurityContext
    participant C as Any Controller

    U->>+JWF: Any request with Cookie:<br/>budgeteer_access_token={JWE}
    JWF->>JWF: Extract token from cookie
    JWF->>+JTS: validateAccessToken(token)
    JTS->>JTS: Decrypt JWE
    JTS->>JTS: Verify signature
    JTS->>JTS: Check expiration
    JTS-->>-JWF: Optional<TokenClaims>
    
    alt Token valid
        JWF->>+SC: Set Authentication<br/>with userId from claims
        SC-->>-JWF: Context set
        JWF->>+C: Continue filter chain
        C-->>-JWF: Response
        JWF-->>-U: Response
    else Token invalid/expired
        JWF-->>U: 401 Unauthorized
    end
```

### Token States

```mermaid
flowchart TD
    A[Request with Access Token] --> B{Token present?}
    B -->|No| C{Protected endpoint?}
    C -->|Yes| D[401 Unauthorized]
    C -->|No| E[Allow anonymous]
    B -->|Yes| F{Decrypt successful?}
    F -->|No| D
    F -->|Yes| G{Signature valid?}
    G -->|No| D
    G -->|Yes| H{Token expired?}
    H -->|Yes| I[Need refresh]
    H -->|No| J[✓ Authenticated]
    
    style D fill:#f66,color:#fff
    style I fill:#fa0,color:#fff
    style J fill:#6f6,color:#fff
    style E fill:#6f6,color:#fff
```

---

## 4. Token Refresh Flow

### Happy Path

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant AC as AuthController
    participant SS as SessionService
    participant JTS as JweTokenService
    participant RTR as AppRefreshTokenRepository
    participant CS as CookieService

    Note over U: Access token expired (15min)<br/>Refresh token still valid (7 days)
    
    U->>+AC: POST /api/v1/auth/refresh<br/>Cookie: budgeteer_refresh_token={token}
    AC->>AC: Extract refresh token from cookie
    AC->>+SS: refreshSession(refreshToken, userAgent, ipAddress)
    
    SS->>SS: Hash refresh token
    SS->>+RTR: findByTokenHashAndRevokedFalse(hash)
    RTR-->>-SS: Optional<AppRefreshToken>
    
    alt Token not found or revoked
        SS-->>AC: Optional.empty()
        AC-->>U: 401 Unauthorized<br/>INVALID_REFRESH_TOKEN
    else Token found
        SS->>SS: Check token.isExpired()
        
        alt Token expired
            SS-->>AC: Optional.empty()
            AC-->>U: 401 Unauthorized<br/>REFRESH_TOKEN_EXPIRED
        else Token valid
            Note over SS: Token Rotation (security)
            SS->>RTR: revoke(oldToken)
            
            SS->>+JTS: createAccessToken(user)
            JTS-->>-SS: newAccessToken
            
            SS->>SS: Generate new refresh token
            SS->>RTR: save(newRefreshToken)
            
            SS-->>-AC: SessionTokens(newAccess, newRefresh)
            
            AC->>+CS: setAccessTokenCookie(newAccessToken)
            CS-->>-AC: Done
            AC->>+CS: setRefreshTokenCookie(newRefreshToken)
            CS-->>-AC: Done
            
            AC-->>-U: 200 OK + New Cookies<br/>{success: true}
        end
    end
```

### Refresh Token States

```mermaid
stateDiagram-v2
    [*] --> Active: Created on login
    Active --> Used: Token used for refresh
    Used --> [*]: Old token invalidated
    
    Active --> Revoked: Logout called
    Revoked --> [*]
    
    Active --> Expired: 7 days passed
    Expired --> [*]
```

> **Note:** When a token is used for refresh, a new token is issued and the old one is revoked (token rotation).

---

## 5. Logout Flow

### Happy Path

```mermaid
sequenceDiagram
    autonumber
    participant U as User/Browser
    participant AC as AuthController
    participant SS as SessionService
    participant RTR as AppRefreshTokenRepository
    participant CS as CookieService

    U->>+AC: POST /api/v1/auth/logout<br/>Cookie: budgeteer_refresh_token
    AC->>AC: Extract refresh token
    AC->>+SS: revokeSession(refreshToken)
    
    SS->>SS: Hash refresh token
    SS->>+RTR: findByTokenHash(hash)
    RTR-->>-SS: Optional<AppRefreshToken>
    
    alt Token found
        SS->>RTR: token.revoke()
        SS->>RTR: save(token)
        SS-->>-AC: true
    else Token not found
        SS-->>AC: false (already logged out)
    end
    
    AC->>+CS: clearAccessTokenCookie()
    CS-->>-AC: Done
    AC->>+CS: clearRefreshTokenCookie()
    CS-->>-AC: Done
    
    AC-->>-U: 200 OK<br/>Cookies cleared
```

---

## Complete User Journey: Signup to Authenticated

```mermaid
sequenceDiagram
    autonumber
    participant U as User
    participant B as Browser
    participant API as Budgeteer API
    participant DB as PostgreSQL
    participant E as Email Service

    rect rgb(240, 248, 255)
        Note over U,E: Step 1: Initial Signup
        U->>B: Enter email address
        B->>API: POST /api/v1/auth/login
        API->>DB: Create User (if new)
        API->>DB: Save MagicLinkToken
        API->>E: Send magic link email
        API-->>B: 200 OK "Check email"
        B-->>U: Show "check email" message
    end

    rect rgb(255, 248, 240)
        Note over U,E: Step 2: Click Magic Link
        E-->>U: Email arrives with link
        U->>B: Click magic link
        B->>API: GET /api/v1/auth/verify?token=xxx
        API->>DB: Validate & consume token
        API->>DB: Mark email verified
        API->>DB: Save RefreshToken
        API-->>B: 200 OK + Set HttpOnly Cookies
        B-->>U: Redirected to dashboard
    end

    rect rgb(240, 255, 240)
        Note over U,E: Step 3: Use Application
        U->>B: Navigate app
        B->>API: GET /api/some-data<br/>(with access token cookie)
        API->>API: Validate JWE token
        API->>DB: Fetch data
        API-->>B: 200 OK + data
        B-->>U: Display data
    end

    rect rgb(255, 255, 240)
        Note over U,E: Step 4: Token Refresh (automatic)
        Note over B: Access token expires (15min)
        B->>API: POST /api/v1/auth/refresh<br/>(with refresh token cookie)
        API->>DB: Validate refresh token
        API->>DB: Revoke old, create new
        API-->>B: 200 OK + New cookies
        Note over B: Continue using app
    end

    rect rgb(255, 240, 240)
        Note over U,E: Step 5: Logout
        U->>B: Click logout
        B->>API: POST /api/v1/auth/logout
        API->>DB: Revoke refresh token
        API-->>B: 200 OK + Clear cookies
        B-->>U: Redirected to login
    end
```

---

## Token Lifetimes

| Token Type | Lifetime | Storage | Purpose |
|------------|----------|---------|---------|
| Magic Link | 15 minutes | DB (hashed) | One-time email verification |
| Access Token (JWE) | 15 minutes | HttpOnly Cookie | API authentication |
| Refresh Token | 7 days | DB (hashed) + HttpOnly Cookie | Session continuity |

---

## Security Considerations

1. **Magic Link Tokens**
   - Single-use (marked as used after verification)
   - Short expiry (15 minutes)
   - All pending tokens invalidated on successful verification
   - Stored as SHA-256 hash (raw token never stored)

2. **Access Tokens (JWE)**
   - Encrypted with AES-256-GCM
   - Short-lived (15 minutes)
   - Stateless (no DB lookup needed)
   - HttpOnly, Secure, SameSite cookies

3. **Refresh Tokens**
   - Longer-lived (7 days) for UX
   - Token rotation on each refresh (old token invalidated)
   - Stored as hash (prevents DB breach from being useful)
   - Can be explicitly revoked (logout)

4. **Single Session Policy**
   - New magic link verification invalidates all pending links
   - Ensures user controls their sessions

---

## Related Documentation

- [Security Architecture](../SECURITY-ARCHITECTURE.md)
- [User Authentication Feature](../features/USER-AUTHENTICATION.md)
- [Testing Guide](../TESTING.md)
