# Security Architecture - Budgeteer

> Comprehensive security design for multi-user authentication and third-party OAuth token management.

**Last Updated:** December 2024  
**Status:** Design Document (Pre-Implementation)

---

## Table of Contents

1. [Overview](#1-overview)
2. [Threat Model](#2-threat-model)
3. [Authentication Architecture](#3-authentication-architecture)
4. [Session Management](#4-session-management)
5. [Token Security (JWE)](#5-token-security-jwe)
6. [Monzo Token Storage](#6-monzo-token-storage)
7. [Frontend Security](#7-frontend-security)
8. [API Security](#8-api-security)
9. [Key Management](#9-key-management)
10. [Dependencies](#10-dependencies)
11. [Database Schema](#11-database-schema)
12. [Implementation Checklist](#12-implementation-checklist)
13. [Deployment & CORS Considerations](#13-deployment--cors-considerations)

---

## 1. Overview

### Two-Layer Security Model

Budgeteer implements a **two-layer security model** separating application authentication from third-party OAuth:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         LAYER 1: APPLICATION AUTH                               │
│              Users authenticate with Budgeteer via Magic Links                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│  • Passwordless authentication (magic links via email)                          │
│  • JWE encrypted session tokens (access + refresh)                              │
│  • HttpOnly secure cookies for browser clients                                  │
│  • Multi-user support with user isolation                                       │
└─────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ User authenticated
                                    │ (user_id established)
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                      LAYER 2: THIRD-PARTY OAUTH TOKENS                          │
│            Monzo OAuth tokens encrypted and associated with user_id             │
├─────────────────────────────────────────────────────────────────────────────────┤
│  • One user → multiple Monzo accounts supported                                 │
│  • AES-256-GCM encryption at rest                                               │
│  • Access controlled via Layer 1 authentication                                 │
│  • Token refresh handled server-side only                                       │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Design Principles

| Principle | Implementation |
|-----------|---------------|
| **Defense in Depth** | Multiple security layers, encryption at rest and in transit |
| **Least Privilege** | Tokens scoped to minimum required permissions |
| **Zero Trust** | Validate every request, never trust client input |
| **Secure by Default** | HttpOnly cookies, secure flags, CSRF protection |
| **Fail Secure** | Invalid tokens = deny access, not degrade gracefully |

---

## 2. Threat Model

### Assets to Protect

| Asset | Sensitivity | Impact if Compromised |
|-------|-------------|----------------------|
| User email addresses | Medium | Privacy breach, phishing target |
| Session tokens | High | Account takeover |
| Monzo access tokens | Critical | Full access to banking data |
| Monzo refresh tokens | Critical | Long-term banking access |
| Encryption keys | Critical | Mass token exposure |

### Threat Actors

| Actor | Capability | Motivation |
|-------|-----------|------------|
| Script kiddies | Automated tools, known exploits | Fun, reputation |
| Opportunistic hackers | Custom attacks, persistence | Financial gain |
| Sophisticated attackers | Advanced techniques, patience | Targeted theft |
| Insider (compromised dev machine) | Full code/DB access | Data exfiltration |

### Attack Vectors & Mitigations

| Attack Vector | Mitigation |
|--------------|------------|
| **Session hijacking** | HttpOnly cookies, secure flag, short expiry |
| **XSS (Cross-Site Scripting)** | CSP headers, HttpOnly (no JS token access) |
| **CSRF (Cross-Site Request Forgery)** | SameSite cookies, CSRF tokens for mutations |
| **Token theft from DB** | AES-256-GCM encryption at rest |
| **Token theft from logs** | Never log tokens, structured logging |
| **Brute force magic links** | Rate limiting, token expiry, token hashing |
| **Email interception** | HTTPS everywhere, short token expiry |
| **Replay attacks** | One-time use tokens, nonces |
| **Man-in-the-middle** | TLS everywhere, HSTS headers |

---

## 3. Authentication Architecture

### Magic Link Flow (Passwordless)

**Why Magic Links?**
- No passwords to leak or crack
- Familiar UX (Monzo, Slack, Notion use this)
- Email becomes the identity provider
- Simpler than OAuth for self-registration

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          MAGIC LINK AUTHENTICATION                              │
└─────────────────────────────────────────────────────────────────────────────────┘

    User                        Backend                        Email Service
      │                            │                                │
      │ 1. POST /api/auth/login    │                                │
      │    { "email": "..." }      │                                │
      │───────────────────────────>│                                │
      │                            │                                │
      │                            │ 2. Generate secure token       │
      │                            │    - 32 random bytes           │
      │                            │    - URL-safe base64           │
      │                            │    - Store SHA-256 hash in DB  │
      │                            │    - Set 15 min expiry         │
      │                            │                                │
      │                            │ 3. Create/lookup user record   │
      │                            │    (by email)                  │
      │                            │                                │
      │                            │ 4. Send email with link        │
      │                            │───────────────────────────────>│
      │                            │                                │
      │ 5. Response: "Check email" │                                │
      │<───────────────────────────│                                │
      │                            │                                │
      │ 6. User clicks link        │                                │
      │    GET /api/auth/verify    │                                │
      │    ?token=xxx              │                                │
      │───────────────────────────>│                                │
      │                            │                                │
      │                            │ 7. Hash received token         │
      │                            │    Compare with stored hash    │
      │                            │    Check not expired           │
      │                            │    Mark token as used          │
      │                            │                                │
      │                            │ 8. Generate JWE access token   │
      │                            │    Generate refresh token      │
      │                            │    Store refresh token hash    │
      │                            │                                │
      │ 9. Set HttpOnly cookies    │                                │
      │    Redirect to dashboard   │                                │
      │<───────────────────────────│                                │
      │                            │                                │
```

### Magic Link Token Security

```java
// Token generation (server-side)
byte[] randomBytes = new byte[32];
new SecureRandom().nextBytes(randomBytes);
String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

// Store HASH in database (never store plain token!)
String tokenHash = sha256(token);

// Email contains: https://app.budgeteer.dev/auth/verify?token={token}

// Verification: hash received token, compare with stored hash
```

| Property | Value | Rationale |
|----------|-------|-----------|
| Token length | 32 bytes (256 bits) | Cryptographically secure |
| Encoding | URL-safe Base64 | Safe in URLs |
| Storage | SHA-256 hash only | Plain token never stored |
| Expiry | 15 minutes | Balance security/usability |
| One-time use | Yes | Prevent replay attacks |

---

## 4. Session Management

### Token Types

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            SESSION TOKEN STRATEGY                               │
└─────────────────────────────────────────────────────────────────────────────────┘

ACCESS TOKEN (JWE)                          REFRESH TOKEN
├── Purpose: Authenticate API requests      ├── Purpose: Get new access token
├── Lifetime: 15 minutes                    ├── Lifetime: 7 days
├── Storage: HttpOnly cookie                ├── Storage: HttpOnly cookie + DB hash
├── Contents: user_id, email, exp, iat      ├── Contents: Opaque random string
├── Validation: Decrypt + verify claims     ├── Validation: Hash lookup in DB
└── On expiry: Use refresh token            └── On expiry: Re-authenticate

WHY TWO TOKENS?
─────────────────────────────────────────────────────────────────────────────────
• Access token: Short-lived, stateless validation (fast)
• Refresh token: Long-lived, enables revocation (DB lookup)
• Compromise of access token: Limited 15-min window
• Compromise of refresh token: Can be revoked immediately
```

### Session Lifecycle

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                           SESSION LIFECYCLE                                    │
└───────────────────────────────────────────────────────────────────────────────┘

  LOGIN SUCCESS
       │
       ▼
  ┌─────────────┐
  │ Access Token│ ─────────────────────────────────────────┐
  │ (15 min)    │                                          │
  └─────────────┘                                          │
       │                                                   │
       │ Token expires                                     │
       ▼                                                   │
  ┌─────────────┐    Valid     ┌─────────────┐            │
  │   Refresh   │ ───────────> │ New Access  │            │
  │   Token     │              │ Token       │            │
  └─────────────┘              └─────────────┘            │
       │                            │                     │
       │ Invalid/expired            │                     │
       ▼                            │                     │
  ┌─────────────┐                   │                     │
  │ Force       │ <─────────────────┴─────────────────────┘
  │ Re-login    │       Any auth failure
  └─────────────┘
```

### Cookie Configuration

```java
// Access Token Cookie
ResponseCookie.from("access_token", jweToken)
    .httpOnly(true)           // Not accessible via JavaScript
    .secure(true)             // HTTPS only (disable for localhost dev)
    .sameSite("Strict")       // CSRF protection
    .path("/api")             // Only sent to API routes
    .maxAge(Duration.ofMinutes(15))
    .build();

// Refresh Token Cookie
ResponseCookie.from("refresh_token", refreshToken)
    .httpOnly(true)
    .secure(true)
    .sameSite("Strict")
    .path("/api/auth/refresh") // Only sent to refresh endpoint
    .maxAge(Duration.ofDays(7))
    .build();
```

| Cookie Attribute | Access Token | Refresh Token | Purpose |
|-----------------|--------------|---------------|---------|
| `HttpOnly` | ✅ | ✅ | Prevent XSS token theft |
| `Secure` | ✅ (prod) | ✅ (prod) | HTTPS only |
| `SameSite` | Strict | Strict | CSRF protection |
| `Path` | `/api` | `/api/auth/refresh` | Minimize exposure |
| `Max-Age` | 15 min | 7 days | Align with token expiry |

---

## 5. Token Security (JWE)

### Why JWE over JWT (JWS)?

```
JWT (JWS) - JSON Web Signature
──────────────────────────────────────────────────────────
eyJhbGciOiJIUzI1NiJ9.                    ← Header (base64)
eyJ1c2VyX2lkIjoiMTIzIiwiZW1haWwiOi...   ← Payload (base64) ⚠️ READABLE!
SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV...   ← Signature

Problem: Anyone can decode the payload!
         Even though they can't modify it, they can READ it.


JWE - JSON Web Encryption
──────────────────────────────────────────────────────────
eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIn0.  ← Header
.                                           ← Encrypted Key (direct = empty)
iv_here.                                    ← Initialization Vector
ciphertext_here.                            ← Encrypted Payload 🔒 UNREADABLE!
auth_tag_here                               ← Authentication Tag

Benefit: Payload is ENCRYPTED, not just signed.
         Even if token is stolen, contents are hidden.
```

### JWE Configuration

```java
// Algorithm choices:
// - Key Management: dir (direct encryption with symmetric key)
// - Content Encryption: A256GCM (AES-256 in GCM mode)

// Why these choices?
// - dir: Simpler, symmetric key shared only on server
// - A256GCM: Fast, provides both encryption and integrity

// Token Claims (encrypted payload)
{
    "sub": "user-uuid-here",           // Subject (user ID)
    "email": "user@example.com",       // For display purposes
    "iat": 1704067200,                 // Issued at
    "exp": 1704068100,                 // Expiry (15 min later)
    "jti": "unique-token-id"           // JWT ID (for revocation)
}
```

### Key Management for JWE

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         JWE KEY MANAGEMENT                                      │
└─────────────────────────────────────────────────────────────────────────────────┘

                    ┌─────────────────────────────┐
                    │      Environment Var        │
                    │   JWE_SECRET_KEY (base64)   │
                    │   256 bits / 32 bytes       │
                    └──────────────┬──────────────┘
                                   │
                                   │ Load at startup
                                   ▼
                    ┌─────────────────────────────┐
                    │    JweTokenService          │
                    │  (Spring @Service bean)     │
                    └─────────────┬───────────────┘
                                  │
              ┌───────────────────┴───────────────────┐
              │                                       │
              ▼                                       ▼
     ┌─────────────────┐                    ┌─────────────────┐
     │  encrypt(claims)│                    │ decrypt(token)  │
     │  → JWE string   │                    │ → claims or null│
     └─────────────────┘                    └─────────────────┘
```

```bash
# Generate key (run once, store securely)
openssl rand -base64 32

# .env file
JWE_SECRET_KEY=your-32-byte-base64-encoded-key-here
```

---

## 6. Monzo Token Storage

### Encryption Strategy (AES-256-GCM)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    MONZO TOKEN ENCRYPTION (AES-256-GCM)                         │
└─────────────────────────────────────────────────────────────────────────────────┘

                     ┌─────────────────────────────┐
                     │   MONZO_ENCRYPTION_KEY      │
                     │   (256-bit / 32 bytes)      │
                     │   Environment variable      │
                     └──────────────┬──────────────┘
                                    │
              ┌─────────────────────┴─────────────────────┐
              │                                           │
              ▼                                           ▼
     ┌─────────────────────────┐              ┌─────────────────────────┐
     │      ENCRYPT            │              │      DECRYPT            │
     │                         │              │                         │
     │  Input: plain token     │              │  Input: stored string   │
     │                         │              │                         │
     │  1. Generate random IV  │              │  1. Decode base64       │
     │     (12 bytes)          │              │  2. Extract IV (first 12)│
     │  2. AES-256-GCM encrypt │              │  3. Extract ciphertext  │
     │  3. Prepend IV          │              │  4. AES-256-GCM decrypt │
     │  4. Base64 encode all   │              │  5. Return plaintext    │
     │                         │              │                         │
     │  Output: base64 string  │              │  Output: plain token    │
     └─────────────────────────┘              └─────────────────────────┘

        Stored in DB:                             Retrieved from DB:
        IV + Ciphertext + AuthTag                 → Original token
        (all base64 encoded)
```

### Why AES-256-GCM?

| Feature | Benefit |
|---------|---------|
| **Authenticated** | Detects tampering (integrity) |
| **256-bit key** | Quantum-resistant key size |
| **GCM mode** | Fast, parallelizable |
| **Random IV** | Same plaintext → different ciphertext |
| **Industry standard** | Well-tested, widely supported |

### Key Separation

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         KEY SEPARATION STRATEGY                                 │
└─────────────────────────────────────────────────────────────────────────────────┘

  JWE_SECRET_KEY                              MONZO_ENCRYPTION_KEY
  (for session tokens)                        (for Monzo OAuth tokens)
        │                                            │
        │                                            │
        ▼                                            ▼
  ┌─────────────────┐                        ┌─────────────────┐
  │ Access tokens   │                        │ Access token    │
  │ (encrypted JWE) │                        │ (encrypted AES) │
  └─────────────────┘                        │                 │
                                             │ Refresh token   │
                                             │ (encrypted AES) │
                                             └─────────────────┘

WHY SEPARATE KEYS?
─────────────────────────────────────────────────────────────────
• Principle of least privilege
• Compromise of one doesn't expose the other
• Different rotation schedules possible
• Different threat models (session vs. banking)
```

---

## 7. Frontend Security

### Security Headers

```java
// Configure in SecurityConfig or via response headers
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.headers(headers -> headers
        // Prevent clickjacking
        .frameOptions(frame -> frame.deny())
        
        // Prevent MIME sniffing
        .contentTypeOptions(content -> {})
        
        // XSS protection (legacy browsers)
        .xssProtection(xss -> xss.headerValue(
            XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
        
        // Content Security Policy
        .contentSecurityPolicy(csp -> csp
            .policyDirectives("default-src 'self'; " +
                "script-src 'self'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; " +
                "connect-src 'self'"))
        
        // HSTS (HTTPS only - enable in production)
        // .httpStrictTransportSecurity(hsts -> hsts
        //     .includeSubDomains(true)
        //     .maxAgeInSeconds(31536000))
    );
}
```

### CSRF Protection

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           CSRF PROTECTION STRATEGY                              │
└─────────────────────────────────────────────────────────────────────────────────┘

For cookie-based auth with SPA frontend:

1. SameSite=Strict cookies → Blocks most CSRF automatically
   
2. For extra protection (state-changing operations):
   - Generate CSRF token on login
   - Store in HttpOnly cookie + return in response body
   - Frontend sends token in X-CSRF-TOKEN header
   - Backend validates header matches cookie

3. API-only routes:
   - If using only HttpOnly cookies with SameSite=Strict
   - CSRF risk is minimal (no cross-origin requests with cookies)
```

### XSS Prevention

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           XSS PREVENTION                                        │
└─────────────────────────────────────────────────────────────────────────────────┘

1. HttpOnly cookies
   └── JavaScript cannot access tokens (primary defense)

2. Content Security Policy
   └── Restricts inline scripts, external sources

3. Input validation
   └── Sanitize all user input on backend

4. Output encoding
   └── Frontend framework auto-escapes (React, Vue)

5. No token in:
   - localStorage ❌
   - sessionStorage ❌
   - URL parameters ❌
   - JavaScript variables ❌
```

---

## 8. API Security

### Authentication Filter

```java
// JweAuthenticationFilter - runs on every /api/** request
@Component
public class JweAuthenticationFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) {
        
        // 1. Extract access_token from cookie
        String token = extractTokenFromCookie(request, "access_token");
        
        if (token != null) {
            // 2. Decrypt and validate JWE
            Optional<Claims> claims = jweTokenService.validateToken(token);
            
            if (claims.isPresent()) {
                // 3. Set SecurityContext
                Authentication auth = new JweAuthentication(claims.get());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        
        chain.doFilter(request, response);
    }
}
```

### Rate Limiting

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           RATE LIMITING                                         │
└─────────────────────────────────────────────────────────────────────────────────┘

Endpoint                          Limit              Window
────────────────────────────────────────────────────────────────────
POST /api/auth/login              5 requests         per minute per IP
POST /api/auth/verify             10 requests        per minute per IP
POST /api/auth/refresh            30 requests        per minute per user
GET  /api/**                      100 requests       per minute per user
POST /api/**                      50 requests        per minute per user

Implementation: Spring Boot + Bucket4j or Resilience4j
```

### Input Validation

```java
// Use Jakarta Validation annotations
public record LoginRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email too long")
    String email
) {}

// Controller validates automatically
@PostMapping("/login")
public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
    // request.email() is guaranteed valid here
}
```

### Error Handling

```java
// Never leak sensitive info in errors
@ExceptionHandler(AuthenticationException.class)
public ResponseEntity<?> handleAuthError(AuthenticationException e) {
    // Log detailed error internally
    log.error("Authentication failed", e);
    
    // Return generic message to client
    return ResponseEntity.status(401).body(Map.of(
        "error", "authentication_failed",
        "message", "Invalid or expired credentials"
        // DO NOT include: stack trace, token details, user info
    ));
}
```

---

## 9. Key Management

### Environment Variables

```bash
# .env file (git-ignored, never commit!)

# Session token encryption (JWE)
# Generate: openssl rand -base64 32
JWE_SECRET_KEY=base64-encoded-32-byte-key

# Monzo token encryption (AES-256-GCM)
# Generate: openssl rand -base64 32
MONZO_ENCRYPTION_KEY=different-base64-encoded-32-byte-key

# Email service (for magic links)
MAIL_HOST=smtp.example.com
MAIL_PORT=587
MAIL_USERNAME=your-email
MAIL_PASSWORD=your-password

# Existing Monzo OAuth (from previous setup)
MONZO_CLIENT_ID=...
MONZO_CLIENT_SECRET=...
MONZO_REDIRECT_URI=...
```

### Key Rotation Strategy

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         KEY ROTATION STRATEGY                                   │
└─────────────────────────────────────────────────────────────────────────────────┘

JWE_SECRET_KEY Rotation:
─────────────────────────
1. Generate new key
2. Update environment variable
3. Restart application
4. All existing access tokens invalidated (15 min max disruption)
5. Users get new tokens on next refresh

MONZO_ENCRYPTION_KEY Rotation:
──────────────────────────────
1. Add new key as MONZO_ENCRYPTION_KEY_NEW
2. Update EncryptionService to:
   - Decrypt: Try new key first, fall back to old
   - Encrypt: Always use new key
3. Run migration job to re-encrypt all tokens with new key
4. Remove old key after migration complete

Future Enhancement:
───────────────────
- Store key version with each encrypted token
- Support multiple active keys during rotation
- Automated rotation via secrets manager
```

### Production Key Storage Options

| Environment | Recommended Storage |
|-------------|-------------------|
| **Local Dev** | `.env` file (git-ignored) |
| **Self-hosted (NUC)** | Systemd credentials, Docker secrets |
| **AWS** | AWS Secrets Manager, Parameter Store |
| **GCP** | Secret Manager |
| **Azure** | Key Vault |

---

## 10. Dependencies

### Required Maven Dependencies

```xml
<!-- pom.xml additions -->

<!-- Spring Security (likely already included) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- JWE/JWT - Nimbus JOSE+JWT (industry standard) -->
<dependency>
    <groupId>com.nimbusds</groupId>
    <artifactId>nimbus-jose-jwt</artifactId>
    <version>9.37.3</version>
</dependency>

<!-- Email sending (for magic links) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>

<!-- Validation (likely already included) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

### Library Choices Rationale

| Library | Why This Choice |
|---------|----------------|
| **Nimbus JOSE+JWT** | Most complete JWE/JWT library for Java, actively maintained, used by Spring Security OAuth |
| **Spring Security** | Standard for Spring Boot, excellent filter chain, integrates with everything |
| **Spring Mail** | Simple SMTP abstraction, works with any email provider |
| **BCrypt (in Spring Security)** | N/A for magic links, but useful if adding password option later |

---

## 11. Database Schema

### Complete Schema Overview

```sql
-- ============================================================================
-- V2__create_users.sql
-- ============================================================================

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL,
    email_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE INDEX idx_users_email ON users(email);

COMMENT ON TABLE users IS 'Application users (passwordless via magic links)';


-- ============================================================================
-- V3__create_magic_link_tokens.sql
-- ============================================================================

CREATE TABLE magic_link_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64) NOT NULL,  -- SHA-256 hash
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at         TIMESTAMP WITH TIME ZONE NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uk_magic_link_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_magic_link_tokens_user_id ON magic_link_tokens(user_id);
CREATE INDEX idx_magic_link_tokens_expires_at ON magic_link_tokens(expires_at);

COMMENT ON TABLE magic_link_tokens IS 'One-time magic link tokens for passwordless auth';


-- ============================================================================
-- V4__create_app_refresh_tokens.sql
-- ============================================================================

CREATE TABLE app_refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64) NOT NULL,  -- SHA-256 hash
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    revoked_at      TIMESTAMP WITH TIME ZONE NULL,
    
    -- Device/session tracking (optional, for "logged in devices" feature)
    user_agent      VARCHAR(500) NULL,
    ip_address      VARCHAR(45) NULL,  -- IPv6 max length
    
    CONSTRAINT uk_app_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_app_refresh_tokens_user_id ON app_refresh_tokens(user_id);
CREATE INDEX idx_app_refresh_tokens_expires_at ON app_refresh_tokens(expires_at);

COMMENT ON TABLE app_refresh_tokens IS 'Long-lived refresh tokens for session management';


-- ============================================================================
-- V5__create_monzo_connections.sql
-- ============================================================================

CREATE TABLE monzo_connections (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    monzo_user_id       VARCHAR(255) NOT NULL,
    access_token_enc    TEXT NOT NULL,       -- AES-256-GCM encrypted
    refresh_token_enc   TEXT NOT NULL,       -- AES-256-GCM encrypted
    token_expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    connected_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    disconnected_at     TIMESTAMP WITH TIME ZONE NULL,
    
    -- One user can have multiple Monzo accounts
    -- But each Monzo account only linked once per user
    CONSTRAINT uk_monzo_connections_user_monzo UNIQUE (user_id, monzo_user_id)
);

CREATE INDEX idx_monzo_connections_user_id ON monzo_connections(user_id);
CREATE INDEX idx_monzo_connections_expires_at ON monzo_connections(token_expires_at);

COMMENT ON TABLE monzo_connections IS 'Encrypted Monzo OAuth tokens linked to app users';
COMMENT ON COLUMN monzo_connections.access_token_enc IS 'AES-256-GCM encrypted access token';
COMMENT ON COLUMN monzo_connections.refresh_token_enc IS 'AES-256-GCM encrypted refresh token';
```

### Entity Relationship Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         ENTITY RELATIONSHIPS                                    │
└─────────────────────────────────────────────────────────────────────────────────┘

    ┌─────────────────┐
    │     users       │
    │─────────────────│
    │ id (PK)         │
    │ email           │
    │ email_verified  │
    │ created_at      │
    │ updated_at      │
    └────────┬────────┘
             │
             │ 1:N
             │
    ┌────────┴────────────────────────────────────────┐
    │                    │                            │
    ▼                    ▼                            ▼
┌─────────────┐   ┌───────────────────┐   ┌──────────────────────┐
│magic_link_  │   │app_refresh_tokens │   │ monzo_connections    │
│tokens       │   │                   │   │                      │
│─────────────│   │───────────────────│   │──────────────────────│
│ id (PK)     │   │ id (PK)           │   │ id (PK)              │
│ user_id(FK) │   │ user_id (FK)      │   │ user_id (FK)         │
│ token_hash  │   │ token_hash        │   │ monzo_user_id        │
│ expires_at  │   │ expires_at        │   │ access_token_enc     │
│ used_at     │   │ revoked_at        │   │ refresh_token_enc    │
│ created_at  │   │ user_agent        │   │ token_expires_at     │
└─────────────┘   │ ip_address        │   │ connected_at         │
                  │ created_at        │   │ updated_at           │
                  └───────────────────┘   │ disconnected_at      │
                                          └──────────────────────┘
```

---

## 12. Implementation Checklist

### Phase 1: User Authentication

- [ ] Add Maven dependencies (nimbus-jose-jwt, spring-mail)
- [ ] Create Flyway migrations (V2, V3, V4)
- [ ] Create entities: `User`, `MagicLinkToken`, `AppRefreshToken`
- [ ] Create repositories
- [ ] Create `JweTokenService`
- [ ] Create `EmailService` (with dev mode console logging)
- [ ] Create `AuthService`
- [ ] Create `SessionService`
- [ ] Configure `SecurityConfig` filter chain
- [ ] Create auth endpoints:
  - `POST /api/auth/login` - Request magic link
  - `GET /api/auth/verify` - Verify magic link, create session
  - `POST /api/auth/refresh` - Refresh access token
  - `POST /api/auth/logout` - Revoke refresh token
  - `GET /api/auth/me` - Get current user
- [ ] Write unit tests
- [ ] Test end-to-end flow

### Phase 2: Monzo Token Persistence

- [ ] Create Flyway migration (V5)
- [ ] Create `MonzoConnection` entity
- [ ] Create `MonzoConnectionRepository`
- [ ] Create `EncryptionService` (AES-256-GCM)
- [ ] Create `MonzoConnectionService`
- [ ] Refactor existing OAuth flow:
  - Require authenticated user
  - Store tokens associated with user
- [ ] Create endpoints:
  - `GET /api/monzo/connections` - List user's Monzo accounts
  - `POST /api/monzo/connect` - Initiate OAuth
  - `DELETE /api/monzo/connections/{id}` - Disconnect account
- [ ] Write unit tests
- [ ] Test end-to-end flow

---

## 13. Deployment & CORS Considerations

### Understanding Same-Origin Policy

**Origin = Protocol + Domain + Port**

```
https://budgeteer.example.com:443
  │              │             │
  │              │             └── Port (443 default for HTTPS)
  │              └── Domain
  └── Protocol

SAME ORIGIN (cookies shared automatically):
✅ https://budgeteer.example.com/app  → https://budgeteer.example.com/api
   (same protocol, same domain, same port)

CROSS-ORIGIN (requires CORS config):
❌ https://budgeteer.example.com → https://api.budgeteer.example.com  (different subdomain)
❌ http://budgeteer.example.com  → https://budgeteer.example.com      (different protocol)
❌ https://budgeteer.example.com:3000 → https://budgeteer.example.com:8080 (different port)
```

### Why Same-Origin Matters for HttpOnly Cookies

HttpOnly cookies are automatically sent with requests to the **same origin**. Cross-origin requests require:
- CORS headers (`Access-Control-Allow-Origin`, `Access-Control-Allow-Credentials`)
- Explicit cookie domain configuration (`.domain(".example.com")`)
- Frontend `credentials: 'include'` on every fetch

**Recommendation:** Keep frontend and backend on same origin to avoid complexity.

### Recommended Deployment Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         NUC (Self-Hosted)                       │
│                                                                 │
│   Spring Boot (single process serves everything)                │
│   ├── /              → React app (static files from /static)    │
│   ├── /dashboard     → React SPA routing (falls back to index)  │
│   ├── /transactions  → React SPA routing                        │
│   ├── /api/auth/*    → REST endpoints                           │
│   └── /api/monzo/*   → REST endpoints                           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
         │
         │ Cloudflare Tunnel (for public OAuth callbacks)
         ▼
    https://budgeteer.yourdomain.com  ← Monzo OAuth redirect URI
    
    Also accessible on LAN:
    http://192.168.1.x:8080 or http://nuc.local:8080
```

### URL Structure

| Type | Example URL | Served By |
|------|-------------|-----------|
| **Home/Login** | `https://budgeteer.example.com/` | React (static) |
| **Dashboard** | `https://budgeteer.example.com/dashboard` | React (SPA route) |
| **Settings** | `https://budgeteer.example.com/settings` | React (SPA route) |
| **Auth API** | `https://budgeteer.example.com/api/auth/login` | Spring Boot |
| **Monzo API** | `https://budgeteer.example.com/api/monzo/accounts` | Spring Boot |

All same origin → HttpOnly cookies work automatically ✅

### Alternative: Separate Frontend/Backend (Not Recommended)

If you must deploy frontend and backend separately:

```
┌──────────────────────┐     ┌──────────────────────┐
│   app.example.com    │ ──► │   api.example.com    │
│   (React/Nginx)      │     │   (Spring Boot)      │
└──────────────────────┘     └──────────────────────┘
```

**Required Configuration:**

```java
// Backend CORS config
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("https://app.example.com")
            .allowedMethods("GET", "POST", "PUT", "DELETE")
            .allowCredentials(true);  // Required for cookies
    }
}

// Cookie must specify domain
ResponseCookie.from("access_token", jweToken)
    .domain(".example.com")  // Dot prefix = shared across subdomains
    .httpOnly(true)
    .secure(true)
    .sameSite("Lax")  // Note: Strict won't work cross-subdomain
    .build();
```

**Frontend must include credentials:**
```javascript
fetch('https://api.example.com/api/data', {
    credentials: 'include'  // Required for cross-origin cookies
});
```

### Local Development Considerations

| Environment | URL | Secure Cookie? |
|-------------|-----|----------------|
| **Local dev** | `http://localhost:8080` | ❌ No (use `secure=false`) |
| **LAN access** | `http://192.168.1.x:8080` | ❌ No |
| **Cloudflare tunnel** | `https://budgeteer.example.com` | ✅ Yes |

```properties
# application-dev.properties
app.cookie.secure=false

# application-prod.properties
app.cookie.secure=true
```

---

## References

- [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
- [RFC 7516 - JSON Web Encryption (JWE)](https://datatracker.ietf.org/doc/html/rfc7516)
- [Nimbus JOSE+JWT Documentation](https://connect2id.com/products/nimbus-jose-jwt)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)

---

**Document Status:** Draft - Pending Implementation  
**Next Steps:** Create feature branches and begin Phase 1 implementation
