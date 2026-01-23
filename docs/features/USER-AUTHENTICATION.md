# Feature: User Authentication (Magic Links)

> Passwordless authentication via email magic links with JWE encrypted session tokens.

---

## 📋 Feature Summary

| Field | Value |
|-------|-------|
| **Feature Branch** | `feature/user-authentication` |
| **Status** | 🟢 Implemented |
| **Priority** | P0 - Required before other features |
| **Estimated Effort** | 2-3 days |
| **Dependencies** | None (foundational feature) |
| **Blocks** | Monzo Token Persistence, all authenticated features |

---

## 🎯 Scope

### In Scope
- [ ] User registration (auto-create on first login)
- [ ] Magic link generation and email delivery
- [ ] Magic link verification with one-time use enforcement
- [ ] JWE encrypted access tokens (15 min expiry)
- [ ] Refresh token management (7 day expiry)
- [ ] HttpOnly secure cookie configuration
- [ ] Session logout (refresh token revocation)
- [ ] Current user endpoint (`/api/auth/me`)
- [ ] Spring Security filter chain integration

### Out of Scope (Deferred)
- Password-based authentication
- Social OAuth login (Google, GitHub, etc.)
- Multi-factor authentication (MFA)
- Email change flow
- Account deletion
- Rate limiting (separate feature)
- Admin user roles

---

## 🏗️ Components

### New Files to Create

| Component | Path | Description |
|-----------|------|-------------|
| **Migrations** | | |
| V2 Migration | `backend/src/main/resources/db/migration/V2__create_users.sql` | Users table |
| V3 Migration | `backend/src/main/resources/db/migration/V3__create_magic_link_tokens.sql` | Magic link tokens table |
| V4 Migration | `backend/src/main/resources/db/migration/V4__create_app_refresh_tokens.sql` | App refresh tokens table |
| **Entities** | | |
| User Entity | `backend/src/main/java/dev/amf/budgeteer/model/User.java` | JPA entity |
| MagicLinkToken Entity | `backend/src/main/java/dev/amf/budgeteer/model/MagicLinkToken.java` | JPA entity |
| AppRefreshToken Entity | `backend/src/main/java/dev/amf/budgeteer/model/AppRefreshToken.java` | JPA entity |
| **Repositories** | | |
| UserRepository | `backend/src/main/java/dev/amf/budgeteer/repository/UserRepository.java` | Spring Data JPA |
| MagicLinkTokenRepository | `backend/src/main/java/dev/amf/budgeteer/repository/MagicLinkTokenRepository.java` | Spring Data JPA |
| AppRefreshTokenRepository | `backend/src/main/java/dev/amf/budgeteer/repository/AppRefreshTokenRepository.java` | Spring Data JPA |
| **Services** | | |
| JweTokenService | `backend/src/main/java/dev/amf/budgeteer/service/JweTokenService.java` | JWE create/validate |
| EmailService | `backend/src/main/java/dev/amf/budgeteer/service/EmailService.java` | Send magic links |
| AuthService | `backend/src/main/java/dev/amf/budgeteer/service/AuthService.java` | Auth orchestration |
| SessionService | `backend/src/main/java/dev/amf/budgeteer/service/SessionService.java` | Token management |
| **Config** | | |
| JweProperties | `backend/src/main/java/dev/amf/budgeteer/config/JweProperties.java` | JWE key config |
| MailProperties | `backend/src/main/java/dev/amf/budgeteer/config/MailProperties.java` | Email config (if custom) |
| SecurityConfig | `backend/src/main/java/dev/amf/budgeteer/config/SecurityConfig.java` | **MODIFY** existing |
| **Controllers** | | |
| AppAuthController | `backend/src/main/java/dev/amf/budgeteer/controller/AppAuthController.java` | Auth endpoints |
| **Filters** | | |
| JweAuthenticationFilter | `backend/src/main/java/dev/amf/budgeteer/security/JweAuthenticationFilter.java` | Extract & validate JWE |
| **DTOs** | | |
| LoginRequest | `backend/src/main/java/dev/amf/budgeteer/dto/LoginRequest.java` | Login request body |
| AuthResponse | `backend/src/main/java/dev/amf/budgeteer/dto/AuthResponse.java` | Auth response |
| UserResponse | `backend/src/main/java/dev/amf/budgeteer/dto/UserResponse.java` | Current user response |
| **Exceptions** | | |
| TokenExpiredException | `backend/src/main/java/dev/amf/budgeteer/exception/TokenExpiredException.java` | Custom exception |
| TokenNotFoundException | `backend/src/main/java/dev/amf/budgeteer/exception/TokenNotFoundException.java` | Custom exception |

### Modified Files

| File | Changes |
|------|---------|
| `backend/pom.xml` | Add nimbus-jose-jwt, spring-mail, spring-validation deps |
| `backend/src/main/resources/application.properties` | Add JWE_SECRET_KEY, mail properties |
| `.env.example` | Add new environment variables |

---

## 🔌 API Endpoints

### POST `/api/auth/login`

Request magic link email.

**Request:**
```json
{
  "email": "user@example.com"
}
```

**Response (200):**
```json
{
  "message": "Check your email for a login link",
  "email": "user@example.com"
}
```

**Errors:**
- `400` - Invalid email format
- `429` - Rate limited (too many requests)

---

### GET `/api/auth/verify?token={token}`

Verify magic link and create session.

**Response (302 Redirect):**
- Sets `access_token` HttpOnly cookie
- Sets `refresh_token` HttpOnly cookie
- Redirects to dashboard (configurable)

**Errors:**
- `400` - Invalid or expired token
- `400` - Token already used

---

### POST `/api/auth/refresh`

Refresh access token using refresh token cookie.

**Request:** No body (refresh token in cookie)

**Response (200):**
- Sets new `access_token` HttpOnly cookie
- Sets new `refresh_token` HttpOnly cookie (rotated)

```json
{
  "message": "Token refreshed"
}
```

**Errors:**
- `401` - Invalid or expired refresh token
- `401` - Refresh token revoked

---

### POST `/api/auth/logout`

Logout and revoke refresh token.

**Request:** No body (refresh token in cookie)

**Response (200):**
- Clears `access_token` cookie
- Clears `refresh_token` cookie
- Marks refresh token as revoked in DB

```json
{
  "message": "Logged out"
}
```

---

### GET `/api/auth/me`

Get current authenticated user.

**Request:** Requires valid `access_token` cookie

**Response (200):**
```json
{
  "id": "uuid-here",
  "email": "user@example.com",
  "email_verified": true,
  "created_at": "2024-12-31T12:00:00Z"
}
```

**Errors:**
- `401` - Not authenticated

---

## 🗃️ Database Schema

See `docs/SECURITY-ARCHITECTURE.md` Section 11 for full schema.

**Tables created:**
- `users`
- `magic_link_tokens`
- `app_refresh_tokens`

---

## 🔒 Security Considerations

| Concern | Mitigation |
|---------|------------|
| Magic link token theft | SHA-256 hash stored, not plain token |
| Magic link replay | One-time use (mark `used_at` on verification) |
| Session hijacking | HttpOnly cookies, short access token expiry |
| XSS token theft | No tokens in JS-accessible storage |
| CSRF | SameSite=Strict cookies |
| Email enumeration | Same response for existing/non-existing email |

---

## 🧪 Test Coverage

### Unit Tests

| Test Class | Coverage |
|------------|----------|
| `JweTokenServiceTest` | Create token, validate token, expired token, invalid token |
| `AuthServiceTest` | Request login, verify token, handle expired/used tokens |
| `SessionServiceTest` | Create session, refresh token, logout, token rotation |
| `EmailServiceTest` | Send email (mock SMTP) |

### Integration Tests

| Test Class | Coverage |
|------------|----------|
| `AuthControllerIntegrationTest` | Full login flow, refresh flow, logout flow |

### Test Cases Checklist

- [ ] Request magic link with valid email
- [ ] Request magic link with invalid email format
- [ ] Verify valid magic link token
- [ ] Verify expired magic link token
- [ ] Verify already-used magic link token
- [ ] Access protected endpoint with valid access token
- [ ] Access protected endpoint with expired access token
- [ ] Refresh with valid refresh token
- [ ] Refresh with expired refresh token
- [ ] Refresh with revoked refresh token
- [ ] Logout clears cookies and revokes token
- [ ] Get current user when authenticated
- [ ] Get current user when not authenticated (401)

---

## 📝 Implementation Notes

*This section is updated during/after implementation with decisions, gotchas, and learnings.*

### Decisions Made


### Issues Encountered


### Learnings


---

## 📊 Definition of Done

- [ ] All database migrations created and tested
- [ ] All entities and repositories created
- [ ] All services implemented with unit tests
- [ ] All endpoints implemented and documented
- [ ] Security filter chain configured
- [ ] HttpOnly cookies working in dev environment
- [ ] End-to-end login flow tested manually
- [ ] Code reviewed and merged to main
- [ ] Feature doc updated with implementation notes

---

## 🔗 Related Documents

- [Security Architecture](../SECURITY-ARCHITECTURE.md)
- [Monzo Auth Flow](../MONZO-AUTH-FLOW.md) (for comparison)
- [Tasks Board](../../.cline/tasks.md)

---

**Created:** December 2024  
**Last Updated:** December 2024
