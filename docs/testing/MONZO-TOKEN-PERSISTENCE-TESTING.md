# Monzo Token Persistence - Manual Testing Guide

> **One complete guide** for testing all 11 scenarios of the Monzo OAuth integration.  
> Follow these steps in order. Each scenario builds on the previous.

---

## 📋 Test Scenarios Checklist

| # | Scenario | Status |
|---|----------|--------|
| 1 | [Login via magic link](#scenario-1-login-via-magic-link) | ⏳ |
| 2 | [POST /api/monzo/connect](#scenario-2-initiate-oauth) | ⏳ |
| 3 | [Follow URL, approve in Monzo](#scenario-3-approve-in-monzo) | ⏳ |
| 4 | [GET /api/monzo/connections](#scenario-4-list-connections) | ⏳ |
| 5 | [GET /api/monzo/status](#scenario-5-check-status) | ⏳ |
| 6 | [DELETE /api/monzo/connections/{id}](#scenario-6-disconnect) | ⏳ |
| 7 | [GET /api/monzo/status (after delete)](#scenario-7-verify-disconnected) | ⏳ |
| 8 | [Reconnect (repeat OAuth)](#scenario-8-reconnect) | ⏳ |
| 9 | [Tamper with state](#scenario-9-invalid-state) | ⏳ |
| 10 | [Use after 10+ mins (expired)](#scenario-10-expired-state) | ⏳ |
| 11 | [Deny in Monzo app](#scenario-11-deny-access) | ⏳ |

---

## 🛠️ Prerequisites

Complete these steps before running any tests.

### 1. Start the Application

```bash
# Option A: Use dev script (recommended)
./scripts/dev.sh start

# Option B: Manual start
docker compose up -d                    # Start PostgreSQL
cd backend && mvn spring-boot:run       # Start Spring Boot
```

### 2. Verify Application is Running

```bash
curl http://localhost:8080/api/health
```

**Expected:**
```json
{"success":true,"data":{"status":"UP"}}
```

### 3. Required Environment Variables

Your `.env` file must contain:

```bash
# Database
POSTGRES_USER=budgeteer
POSTGRES_PASSWORD=budgeteer
POSTGRES_DB=budgeteer

# Auth (generate with: openssl rand -base64 32)
JWE_SECRET_KEY=your-32-byte-base64-key

# Monzo
MONZO_CLIENT_ID=your_client_id
MONZO_CLIENT_SECRET=your_client_secret
MONZO_REDIRECT_URI=http://localhost:8080/api/monzo/callback
MONZO_ENCRYPTION_KEY=your-32-byte-key
```

### 4. Postman Setup (Optional but Recommended)

1. Import: `scripts/postman/budgeteer-auth.postman_collection.json`
2. Import: `scripts/postman/budgeteer-local.postman_environment.json`
3. Select "Budgeteer Local" environment

### 5. Clean Database (Optional)

Start fresh:
```bash
docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer -c "
TRUNCATE monzo_connections, oauth_states, app_refresh_tokens, magic_link_tokens, users CASCADE;
"
```

---

## Scenario 1: Login via Magic Link

> **Goal:** Get authenticated so you can access protected Monzo endpoints

### Option A: Quick Login (Dev Mode - Fastest)

```bash
curl -X POST http://localhost:8080/api/dev/auth/quick-login \
  -H "Content-Type: application/json" \
  -d '{"email": "test@example.com"}' \
  -c cookies.txt
```

**Expected:** 200 OK with user details. Cookies saved to `cookies.txt`.

### Option B: Full Magic Link Flow

**Step 1: Request magic link**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "test@example.com"}'
```

**Expected:**
```json
{"success":true,"data":{"message":"Magic link sent to your email"}}
```

**Step 2: Get token from server logs**

Look in the Spring Boot console for:
```
INFO  - Magic link for test@example.com: abc123-def456-...
```

Copy that token.

**Step 3: Verify the magic link**
```bash
curl "http://localhost:8080/api/auth/verify?token=YOUR_TOKEN_HERE" \
  -c cookies.txt
```

**Expected:** 200 OK with user details and cookies saved.

### Verify Login Worked

```bash
curl http://localhost:8080/api/auth/me -b cookies.txt
```

**Expected:**
```json
{
  "success": true,
  "data": {
    "id": "uuid-here",
    "email": "test@example.com",
    "emailVerified": true
  }
}
```

✅ **Scenario 1 Complete** - You are now logged in.

---

## Scenario 2: Initiate OAuth

> **Goal:** Get the Monzo authorization URL

```bash
curl -X POST http://localhost:8080/api/monzo/connect \
  -b cookies.txt
```

**Expected:**
```json
{
  "success": true,
  "data": {
    "message": "Redirect to authorization URL to connect your Monzo account",
    "authorizationUrl": "https://auth.monzo.com/?client_id=...&redirect_uri=...&response_type=code&state=STATE_TOKEN"
  }
}
```

📝 **Save the `authorizationUrl`** - you'll need it for Scenario 3.

📝 **Save the `state` parameter** from the URL - you'll need it for Scenarios 9-10.

### Troubleshooting

| Problem | Solution |
|---------|----------|
| 401 Unauthorized | Re-run Scenario 1 to login |
| 500 Server Error | Check MONZO_CLIENT_ID is set in .env |

✅ **Scenario 2 Complete**

---

## Scenario 3: Approve in Monzo

> **Goal:** Complete the OAuth flow by approving in Monzo app

### Step 1: Open Authorization URL

Copy the `authorizationUrl` from Scenario 2 and paste it into your browser.

### Step 2: Login to Monzo (if needed)

Enter your phone number/email if prompted.

### Step 3: Approve in Monzo App

You'll receive a push notification on your phone. Tap **Allow** to approve.

### Step 4: Observe the Redirect

Your browser will redirect to:
```
http://localhost:8080/api/monzo/callback?code=eyJ...&state=STATE_TOKEN
```

**Expected Response:**
```json
{
  "success": true,
  "data": {
    "id": "uuid-here",
    "monzoUserId": "user_00009mg...",
    "status": "ACTIVE",
    "connectedAt": "2026-03-14T17:00:00Z",
    "tokenExpiresAt": "2026-03-15T05:00:00Z"
  }
}
```

📝 **Save the connection `id`** - you'll need it for Scenarios 6-7.

### Troubleshooting

| Problem | Solution |
|---------|----------|
| "OAuth state invalid" | State expired (10 min limit). Restart from Scenario 2 |
| "Invalid redirect URI" | Check MONZO_REDIRECT_URI in .env matches Monzo Developer Portal |
| No push notification | Check Monzo app is installed and logged in |

✅ **Scenario 3 Complete** - Monzo is now connected!

---

## Scenario 4: List Connections

> **Goal:** Verify the connection was created

```bash
curl http://localhost:8080/api/monzo/connections -b cookies.txt
```

**Expected:**
```json
{
  "success": true,
  "data": [
    {
      "id": "uuid-here",
      "monzoUserId": "user_00009mg...",
      "status": "ACTIVE",
      "connectedAt": "2026-03-14T17:00:00Z",
      "tokenExpiresAt": "2026-03-15T05:00:00Z"
    }
  ]
}
```

Should show **exactly 1 connection**.

✅ **Scenario 4 Complete**

---

## Scenario 5: Check Status

> **Goal:** Verify the quick status endpoint shows connected

```bash
curl http://localhost:8080/api/monzo/status -b cookies.txt
```

**Expected:**
```json
{
  "success": true,
  "data": {
    "connected": true,
    "connectionCount": 1
  }
}
```

✅ **Scenario 5 Complete**

---

## Scenario 6: Disconnect

> **Goal:** Soft-delete the Monzo connection

Use the connection `id` from Scenario 3 or 4:

```bash
curl -X DELETE http://localhost:8080/api/monzo/connections/YOUR_CONNECTION_ID \
  -b cookies.txt
```

**Expected:** `204 No Content` (empty response body)

### Verify in Database (Optional)

```bash
docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer -c "
SELECT id, disconnected_at FROM monzo_connections;
"
```

The `disconnected_at` should now have a timestamp (soft delete).

✅ **Scenario 6 Complete**

---

## Scenario 7: Verify Disconnected

> **Goal:** Confirm status shows disconnected

```bash
curl http://localhost:8080/api/monzo/status -b cookies.txt
```

**Expected:**
```json
{
  "success": true,
  "data": {
    "connected": false,
    "connectionCount": 0
  }
}
```

Also verify list is empty:
```bash
curl http://localhost:8080/api/monzo/connections -b cookies.txt
```

**Expected:**
```json
{
  "success": true,
  "data": []
}
```

✅ **Scenario 7 Complete**

---

## Scenario 8: Reconnect

> **Goal:** Create a new connection after disconnecting

Repeat Scenarios 2-5:

1. **Initiate OAuth:**
   ```bash
   curl -X POST http://localhost:8080/api/monzo/connect -b cookies.txt
   ```

2. **Open authorization URL in browser**

3. **Approve in Monzo app**

4. **Verify new connection:**
   ```bash
   curl http://localhost:8080/api/monzo/status -b cookies.txt
   ```

**Expected:** `connected: true, connectionCount: 1`

📝 The new connection will have a **different UUID** than the original.

### Verify in Database (Optional)

```bash
docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer -c "
SELECT id, connected_at, disconnected_at,
  CASE WHEN disconnected_at IS NULL THEN 'ACTIVE' ELSE 'DISCONNECTED' END AS status
FROM monzo_connections ORDER BY connected_at;
"
```

Should show 2 connections: 1 disconnected (old), 1 active (new).

✅ **Scenario 8 Complete**

---

## Scenario 9: Invalid State (Tampered)

> **Goal:** Verify tampering with the state parameter is rejected

### Step 1: Initiate OAuth (get a valid state)

```bash
curl -X POST http://localhost:8080/api/monzo/connect -b cookies.txt
```

Copy the `state` parameter from the authorization URL.

### Step 2: Try callback with wrong state

```bash
curl "http://localhost:8080/api/monzo/callback?code=test&state=WRONG_STATE_12345"
```

**Expected:** `400 Bad Request`
```json
{
  "success": false,
  "error": {
    "code": "OAUTH_STATE_INVALID",
    "message": "Invalid or expired OAuth state"
  }
}
```

✅ **Scenario 9 Complete**

---

## Scenario 10: Expired State

> **Goal:** Verify OAuth states expire after 10 minutes

### Step 1: Initiate OAuth

```bash
curl -X POST http://localhost:8080/api/monzo/connect -b cookies.txt
```

📝 Copy the `state` parameter from the URL.

### Step 2: Wait 10+ minutes ⏱️

Do something else for 10 minutes...

### Step 3: Try to use expired state

```bash
curl "http://localhost:8080/api/monzo/callback?code=test&state=YOUR_EXPIRED_STATE"
```

**Expected:** `400 Bad Request`
```json
{
  "success": false,
  "error": {
    "code": "OAUTH_STATE_EXPIRED",
    "message": "OAuth state has expired"
  }
}
```

### Alternative: Manually Expire in Database

If you don't want to wait 10 minutes:

```bash
docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer -c "
UPDATE oauth_states 
SET expires_at = NOW() - INTERVAL '1 minute'
WHERE used = false;
"
```

Then use any pending state - it will be expired.

✅ **Scenario 10 Complete**

---

## Scenario 11: Deny Access in Monzo

> **Goal:** Verify user denial is handled correctly

### ⚠️ Important: Revoke Existing Access First

Monzo caches OAuth approvals. To test denial, you must revoke first:

1. Open **Monzo app** on your phone
2. Go to **Settings → Privacy & Security → Third party apps**
3. Find "Budgeteer" (or your client name) and tap **Remove**
4. If you don't see any app, skip this step

### Step 1: Initiate OAuth

```bash
curl -X POST http://localhost:8080/api/monzo/connect -b cookies.txt
```

### Step 2: Open Authorization URL

Paste the URL into your browser.

### Step 3: Deny in Monzo App

When the push notification arrives, tap **Deny** (not Allow).

### Step 4: Observe the Redirect

Your browser will redirect to:
```
http://localhost:8080/api/monzo/callback?error=access_denied&error_description=The+user+denied+access&state=...
```

**Expected:** `400 Bad Request`
```json
{
  "success": false,
  "error": {
    "code": "OAUTH_ACCESS_DENIED",
    "message": "User denied access to Monzo account"
  }
}
```

### Verify No Connection Created

```bash
curl http://localhost:8080/api/monzo/status -b cookies.txt
```

**Expected:** `connected: false` (no new connection)

### Troubleshooting

| Problem | Solution |
|---------|----------|
| Monzo auto-approves (no deny option) | You need to revoke in Monzo app first (see above) |
| "access_denied" but in URL not JSON | That's the raw redirect - the JSON is the response body |

✅ **Scenario 11 Complete**

---

## 🗃️ Database Verification Queries

### Connect to Database

```bash
docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer
```

### Useful Queries

**Count all records:**
```sql
SELECT 'users' AS table_name, COUNT(*) AS count FROM users
UNION ALL SELECT 'magic_link_tokens', COUNT(*) FROM magic_link_tokens
UNION ALL SELECT 'app_refresh_tokens', COUNT(*) FROM app_refresh_tokens
UNION ALL SELECT 'oauth_states', COUNT(*) FROM oauth_states
UNION ALL SELECT 'monzo_connections', COUNT(*) FROM monzo_connections
ORDER BY table_name;
```

**All users:**
```sql
SELECT id, email, email_verified, created_at FROM users ORDER BY created_at DESC;
```

**Active sessions for a user:**
```sql
SELECT 
    r.id,
    u.email,
    r.created_at,
    r.expires_at,
    r.revoked_at,
    CASE 
        WHEN r.revoked_at IS NOT NULL THEN '❌ REVOKED'
        WHEN r.expires_at < NOW() THEN '⏰ EXPIRED'
        ELSE '✅ ACTIVE'
    END AS status
FROM app_refresh_tokens r
JOIN users u ON r.user_id = u.id
WHERE u.email = 'test@example.com'
ORDER BY r.created_at DESC;
```

**All Monzo connections with status:**
```sql
SELECT 
    c.id,
    u.email,
    c.monzo_user_id,
    c.token_expires_at,
    c.connected_at,
    c.disconnected_at,
    CASE 
        WHEN c.disconnected_at IS NOT NULL THEN '🔌 DISCONNECTED'
        WHEN c.token_expires_at < NOW() THEN '⏰ EXPIRED'
        ELSE '✅ ACTIVE'
    END AS status
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
ORDER BY c.connected_at DESC;
```

**OAuth states status:**
```sql
SELECT 
    o.id,
    u.email,
    LEFT(o.state, 20) || '...' AS state_preview,
    o.used,
    o.expires_at,
    CASE 
        WHEN o.expires_at < NOW() THEN '⏰ EXPIRED'
        WHEN o.used THEN '✅ USED'
        ELSE '⏳ PENDING'
    END AS status
FROM oauth_states o
JOIN users u ON o.user_id = u.id
ORDER BY o.created_at DESC;
```

**Verify tokens are encrypted:**
```sql
SELECT 
    id,
    LEFT(access_token_enc, 40) || '...' AS token_preview,
    CASE 
        WHEN access_token_enc LIKE 'eyJ%' THEN '⚠️ PLAINTEXT!'
        WHEN access_token_enc LIKE '%{%' THEN '⚠️ MIGHT BE PLAINTEXT!'
        ELSE '✅ Encrypted'
    END AS check
FROM monzo_connections
WHERE disconnected_at IS NULL;
```

**Find ALL data for a specific user:**
```sql
-- Replace 'test@example.com' with your test email
SELECT 
    'User' AS entity, u.id::text, u.email, u.email_verified::text AS info
FROM users u WHERE u.email = 'test@example.com'
UNION ALL
SELECT 
    'Session', r.id::text, 
    CASE WHEN r.revoked_at IS NOT NULL THEN 'REVOKED' ELSE 'ACTIVE' END,
    r.expires_at::text
FROM app_refresh_tokens r 
JOIN users u ON r.user_id = u.id 
WHERE u.email = 'test@example.com'
UNION ALL
SELECT 
    'Magic Link', m.id::text, 
    CASE WHEN m.used_at IS NOT NULL THEN 'USED' ELSE 'PENDING' END,
    m.expires_at::text
FROM magic_link_tokens m 
JOIN users u ON m.user_id = u.id 
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

## 🧹 Cleanup

### Reset for Fresh Testing

```sql
-- Delete all test data
TRUNCATE monzo_connections, oauth_states, app_refresh_tokens, magic_link_tokens, users CASCADE;
```

### Delete Just Monzo Data

```sql
DELETE FROM monzo_connections;
DELETE FROM oauth_states;
```

---

## ✅ Final Checklist

Mark each scenario as you complete it:

- [ ] Scenario 1: Login via magic link
- [ ] Scenario 2: POST /api/monzo/connect
- [ ] Scenario 3: Follow URL, approve in Monzo
- [ ] Scenario 4: GET /api/monzo/connections
- [ ] Scenario 5: GET /api/monzo/status
- [ ] Scenario 6: DELETE /api/monzo/connections/{id}
- [ ] Scenario 7: GET /api/monzo/status (after delete)
- [ ] Scenario 8: Reconnect (repeat OAuth)
- [ ] Scenario 9: Tamper with state
- [ ] Scenario 10: Use after 10+ mins (expired)
- [ ] Scenario 11: Deny in Monzo app

**All scenarios complete?** Update `.cline/tasks.md` and close the feature!

---

## 📚 API Endpoint Reference

### Health Endpoints
| Method | Endpoint | Auth | Purpose |
|--------|----------|------|---------|
| GET | `/api/health` | ❌ | Basic health check |
| GET | `/api/health/ready` | ❌ | Readiness with DB check |
| GET | `/api/health/live` | ❌ | Liveness check |

### Auth Endpoints
| Method | Endpoint | Auth | Purpose |
|--------|----------|------|---------|
| POST | `/api/auth/login` | ❌ | Request magic link |
| GET | `/api/auth/verify` | ❌ | Verify magic link token |
| GET | `/api/auth/me` | ✅ | Get current user |
| POST | `/api/auth/refresh` | 🔄 | Refresh tokens (uses refresh_token cookie) |
| POST | `/api/auth/logout` | ✅ | Logout |

### Dev Tools (dev profile only)
| Method | Endpoint | Auth | Purpose |
|--------|----------|------|---------|
| POST | `/api/dev/auth/quick-login` | ❌ | Instant login (no magic link) |
| POST | `/api/dev/auth/revoke-all` | ❌ | Revoke all sessions |
| POST | `/api/dev/auth/revoke-user` | ❌ | Revoke user's sessions |

### Monzo Endpoints
| Method | Endpoint | Auth | Purpose |
|--------|----------|------|---------|
| GET | `/api/monzo/status` | ✅ | Check connection status |
| POST | `/api/monzo/connect` | ✅ | Initiate OAuth (JSON response) |
| GET | `/api/monzo/connect` | ✅ | Initiate OAuth (redirect) |
| GET | `/api/monzo/callback` | ❌ | OAuth callback (state validates user) |
| GET | `/api/monzo/connections` | ✅ | List all connections |
| GET | `/api/monzo/connections/{id}` | ✅ | Get connection details |
| DELETE | `/api/monzo/connections/{id}` | ✅ | Disconnect (soft delete) |

---

## 📁 Files Reference

| File | Purpose |
|------|---------|
| `scripts/postman/budgeteer-auth.postman_collection.json` | Postman collection |
| `scripts/postman/budgeteer-local.postman_environment.json` | Postman environment |
| `scripts/sql/manual-testing.sql` | Additional SQL queries |
| `scripts/dev.sh` | Development startup script |
| `.env` | Local environment variables |
| `.env.example` | Template for .env |

---

*Last updated: 2026-03-14*
