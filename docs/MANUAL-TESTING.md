# Manual Testing Guide

This guide covers how to manually test the Budgeteer application, specifically the Monzo OAuth integration.

## Prerequisites

1. **Docker Desktop** running
2. **Postman** installed
3. **Monzo Developer Account** with:
   - Client ID
   - Client Secret  
   - Redirect URI set to `http://localhost:8080/api/monzo/callback`

## Quick Start

### 1. Start the Application

```bash
# Start everything (PostgreSQL + Spring Boot)
./scripts/dev.sh start

# Or manually:
docker compose up -d                    # Start PostgreSQL
cd backend && mvn spring-boot:run       # Start Spring Boot
```

### 2. Import Postman Files

Import both files into Postman:
- `scripts/postman/budgeteer-auth.postman_collection.json`
- `scripts/postman/budgeteer-local.postman_environment.json`

### 3. Select the Environment

In Postman, select **"Budgeteer Local"** as the active environment.

---

## Testing Auth Flow

### Quick Login (Dev Only)

1. Open **Dev Tools > ⚡ Quick Login**
2. Click **Send**
3. ✅ Tokens are automatically saved to environment variables

### Full Magic Link Flow (Production-like)

1. Run **Auth Flow (Cookies) > 1. Login (Request Magic Link)**
2. Check the server console for the magic link token
3. Copy the token and set `magic_link_token` in environment
4. Run **Auth Flow (Cookies) > 2. Verify Magic Link**
5. ✅ You're now logged in with cookies

---

## Testing Monzo OAuth Integration

### Prerequisites

Make sure you have:
- ✅ Logged in (Quick Login works great)
- ✅ Monzo credentials in `.env` file:
  ```
  MONZO_CLIENT_ID=your_client_id
  MONZO_CLIENT_SECRET=your_client_secret
  ```

### OAuth Flow Steps

#### Step 1: Check Current Status

Run **Monzo Integration > 1. Check Connection Status**

Expected response:
```json
{
  "success": true,
  "data": {
    "connected": false,
    "connectionCount": 0
  }
}
```

#### Step 2: Initiate OAuth

Run **Monzo Integration > 2. Initiate OAuth (JSON)**

Expected response:
```json
{
  "success": true,
  "data": {
    "message": "Redirect to authorization URL to connect your Monzo account",
    "authorizationUrl": "https://auth.monzo.com/?client_id=...&redirect_uri=...&response_type=code&state=..."
  }
}
```

The `monzo_oauth_state` is automatically saved.

#### Step 3: Authorize in Browser

1. **Copy** the `authorizationUrl` from the response
2. **Open** it in your browser
3. **Log in** to Monzo (if needed)
4. **Approve** the request in the Monzo app
5. You'll be redirected to: `http://localhost:8080/api/monzo/callback?code=...&state=...`

#### Step 4: Extract the Authorization Code

From the callback URL, copy the `code` parameter value.

Example URL:
```
http://localhost:8080/api/monzo/callback?code=eyJhbGciOiJFUzI1...&state=abc123
```

Copy: `eyJhbGciOiJFUzI1...`

#### Step 5: Set the Code in Postman

In Postman environment, set:
- `monzo_auth_code` = the code you copied

#### Step 6: Complete the Connection

Run **Monzo Integration > 3. Process OAuth Callback**

Expected response:
```json
{
  "success": true,
  "data": {
    "id": "uuid-here",
    "monzoUserId": "user_abc123",
    "status": "ACTIVE",
    "connectedAt": "2026-02-15T13:00:00Z",
    "tokenExpiresAt": "2026-02-15T14:00:00Z"
  }
}
```

✅ **Monzo is now connected!**

### After Connection

#### List All Connections

Run **Monzo Integration > 4. List Connections**

#### Get Specific Connection

Run **Monzo Integration > 5. Get Connection Details**

#### Disconnect

Run **Monzo Integration > 6. Disconnect Monzo**

---

## Database Inspection

### Connect to PostgreSQL

```bash
# Using docker
docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer

# Or using psql directly
psql -h localhost -p 5432 -U budgeteer -d budgeteer
```

### Useful SQL Queries

All queries are in `scripts/sql/manual-testing.sql`. Here are the most useful ones:

#### Overview - Record Counts

```sql
SELECT 'users' AS table_name, COUNT(*) AS count FROM users
UNION ALL SELECT 'magic_link_tokens', COUNT(*) FROM magic_link_tokens
UNION ALL SELECT 'app_refresh_tokens', COUNT(*) FROM app_refresh_tokens
UNION ALL SELECT 'oauth_states', COUNT(*) FROM oauth_states
UNION ALL SELECT 'monzo_connections', COUNT(*) FROM monzo_connections
ORDER BY table_name;
```

#### All Users

```sql
SELECT id, email, email_verified, created_at FROM users ORDER BY created_at DESC;
```

#### OAuth States (Pending Monzo Authorizations)

```sql
SELECT 
    o.id,
    u.email,
    SUBSTRING(o.state, 1, 20) || '...' AS state_preview,
    o.used,
    o.expires_at,
    CASE 
        WHEN o.expires_at < NOW() THEN 'EXPIRED'
        WHEN o.used THEN 'USED'
        ELSE 'PENDING'
    END AS status
FROM oauth_states o
JOIN users u ON o.user_id = u.id
ORDER BY o.created_at DESC;
```

#### Monzo Connections

```sql
SELECT 
    c.id,
    u.email,
    c.monzo_user_id,
    c.token_expires_at,
    c.connected_at,
    c.disconnected_at,
    CASE 
        WHEN c.disconnected_at IS NOT NULL THEN 'DISCONNECTED'
        WHEN c.token_expires_at < NOW() THEN 'TOKEN_EXPIRED'
        ELSE 'ACTIVE'
    END AS status
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
ORDER BY c.connected_at DESC;
```

#### Verify Tokens Are Encrypted

```sql
SELECT 
    id,
    SUBSTRING(access_token_enc, 1, 30) || '...' AS access_token_preview,
    CASE 
        WHEN access_token_enc LIKE '%{%' THEN '⚠️ MIGHT BE PLAINTEXT!'
        ELSE '✅ Encrypted'
    END AS encryption_check
FROM monzo_connections
WHERE disconnected_at IS NULL;
```

#### Find All Data for a User

```sql
-- Replace 'test@example.com' with your test email
SELECT 
    'User' AS entity, u.id::text, u.email, u.email_verified::text
FROM users u WHERE u.email = 'test@example.com'
UNION ALL
SELECT 
    'Session', r.id::text, 
    CASE WHEN r.revoked THEN 'REVOKED' ELSE 'ACTIVE' END,
    r.expires_at::text
FROM app_refresh_tokens r 
JOIN users u ON r.user_id = u.id 
WHERE u.email = 'test@example.com'
UNION ALL
SELECT 
    'OAuth State', o.id::text, 
    CASE WHEN o.used THEN 'USED' ELSE 'PENDING' END,
    o.expires_at::text
FROM oauth_states o 
JOIN users u ON o.user_id = u.id 
WHERE u.email = 'test@example.com'
UNION ALL
SELECT 
    'Monzo Connection', c.id::text, 
    CASE WHEN c.disconnected_at IS NOT NULL THEN 'DISCONNECTED' ELSE 'ACTIVE' END,
    c.monzo_user_id
FROM monzo_connections c 
JOIN users u ON c.user_id = u.id 
WHERE u.email = 'test@example.com';
```

---

## Troubleshooting

### "No access token" error during OAuth callback

**Cause:** The authorization code has expired or was already used.

**Fix:** Start the OAuth flow again from Step 2.

### "OAuth state invalid" error

**Cause:** State mismatch or expired (states expire after 10 minutes).

**Fix:** Start the OAuth flow again from Step 2.

### Can't connect to database

```bash
# Check if container is running
docker ps

# Check container logs
docker logs budgeteer-postgres

# Restart container
docker compose down && docker compose up -d
```

### Application won't start

```bash
# Check logs
cd backend && mvn spring-boot:run

# Check for missing env vars
cat .env

# Make sure these are set:
# - MONZO_CLIENT_ID
# - MONZO_CLIENT_SECRET
# - JWE_SECRET_KEY
# - MONZO_ENCRYPTION_KEY
```

### Postman - Environment variables not updating

1. Make sure "Budgeteer Local" environment is selected
2. Click the eye icon to verify variables
3. Check that auto-save scripts are running (look at console)

---

## Available Postman Endpoints

### Health
- `GET /api/health` - Basic health check
- `GET /api/health/ready` - Readiness with DB check
- `GET /api/health/live` - Liveness check

### Dev Tools (dev profile only)
- `POST /api/test/auth/quick-login` - Instant login
- `POST /api/test/auth/revoke-all` - Revoke all sessions
- `POST /api/test/auth/revoke-user` - Revoke user's sessions

### Auth
- `POST /api/auth/login` - Request magic link
- `GET /api/auth/verify` - Verify magic link
- `GET /api/auth/me` - Get current user
- `POST /api/auth/refresh` - Refresh tokens
- `POST /api/auth/logout` - Logout

### Monzo Integration
- `GET /api/monzo/status` - Check connection status
- `POST /api/monzo/connect` - Initiate OAuth (JSON response)
- `GET /api/monzo/connect` - Initiate OAuth (redirect)
- `GET /api/monzo/callback` - OAuth callback
- `GET /api/monzo/connections` - List connections
- `GET /api/monzo/connections/{id}` - Get connection
- `DELETE /api/monzo/connections/{id}` - Disconnect

---

## Environment Variables Reference

### Postman Environment Variables

| Variable | Description | Auto-set? |
|----------|-------------|-----------|
| `base_url` | Server URL (http://localhost:8080) | No |
| `user_email` | Test user email | Yes (after login) |
| `access_token` | JWE access token | Yes (after login) |
| `refresh_token` | JWE refresh token | Yes (after login) |
| `user_id` | User UUID | Yes (after login) |
| `magic_link_token` | Magic link token | Manual |
| `monzo_oauth_state` | OAuth state | Yes (after initiate) |
| `monzo_auth_code` | OAuth code | Manual |
| `monzo_connection_id` | Connection UUID | Yes (after connect) |

### Application Environment Variables

See `.env.example` for required variables.

---

## Files Reference

| File | Purpose |
|------|---------|
| `scripts/postman/budgeteer-auth.postman_collection.json` | Postman collection |
| `scripts/postman/budgeteer-local.postman_environment.json` | Postman environment |
| `scripts/sql/manual-testing.sql` | SQL queries |
| `scripts/dev.sh` | Development startup script |
| `.env` | Local environment variables |
