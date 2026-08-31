# Monzo API Authentication Flow & Token Management

## TL;DR - Can Your Server Do Batch Calls Without Manual Auth?

**YES!** ✅ Once the user completes the initial OAuth setup (one-time), your server can automatically refresh tokens and make API calls indefinitely without human interaction.

---

## Key Findings from Monzo API Documentation

### Client Types

| Client Type | Refresh Token? | Use Case |
|------------|----------------|----------|
| **Confidential** | ✅ YES | Server-side apps (your Spring Boot backend) |
| **Non-Confidential** | ❌ NO | Client-side apps (mobile, browser JS) |

**Your backend is a confidential client** → You get refresh tokens → Automated batch jobs work!

### Token Lifetimes

- **Access Token**: 6 hours (21,600 seconds)
- **Refresh Token**: Long-lived (until manually revoked or replaced)

### Strong Customer Authentication (SCA)

- Required **ONLY during initial OAuth** (user approves in Monzo app)
- **NOT required** for token refresh operations
- **NOT required** for API calls with valid access token

---

## Flow Diagrams

### 1. INITIAL OAUTH FLOW (One-Time Setup - Requires User Interaction)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          INITIAL OAUTH FLOW (ONE-TIME)                              │
│                     User must interact with Monzo app ONCE                          │
└─────────────────────────────────────────────────────────────────────────────────────┘

     ┌──────┐         ┌──────────────┐        ┌───────────────┐      ┌──────────┐
     │ User │         │ Your Server  │        │  Monzo Auth   │      │ Monzo App│
     └──┬───┘         └──────┬───────┘        └───────┬───────┘      └────┬─────┘
        │                    │                        │                   │
        │ 1. Click "Connect  │                        │                   │
        │    Monzo"          │                        │                   │
        │───────────────────>│                        │                   │
        │                    │                        │                   │
        │                    │ 2. Redirect to Monzo   │                   │
        │                    │    with client_id,     │                   │
        │                    │    redirect_uri, state │                   │
        │<───────────────────│───────────────────────>│                   │
        │                    │                        │                   │
        │                    │                        │ 3. User logs in   │
        │                    │                        │    with email     │
        │<───────────────────│────────────────────────│                   │
        │                    │                        │                   │
        │                    │                        │ 4. SCA: Push      │
        │                    │                        │    notification   │
        │                    │                        │───────────────────>│
        │                    │                        │                   │
        │ 5. Approve in app  │                        │                   │
        │    (PIN/Face ID/   │                        │                   │
        │    fingerprint)    │                        │                   │
        │───────────────────────────────────────────────────────────────>│
        │                    │                        │                   │
        │                    │ 6. Redirect back with  │<──────────────────│
        │                    │    authorization_code  │                   │
        │<───────────────────│<───────────────────────│                   │
        │                    │                        │                   │
        │                    │ 7. Exchange code for   │                   │
        │                    │    tokens (POST)       │                   │
        │                    │───────────────────────>│                   │
        │                    │                        │                   │
        │                    │ 8. Receive:            │                   │
        │                    │    - access_token      │                   │
        │                    │    - refresh_token     │                   │
        │                    │    - expires_in (6hrs) │                   │
        │                    │<───────────────────────│                   │
        │                    │                        │                   │
        │                    │ 9. ENCRYPT & STORE     │                   │
        │                    │    tokens in DB        │                   │
        │                    │    ┌─────────────────┐ │                   │
        │                    │    │ PostgreSQL      │ │                   │
        │                    │    │ - access_token  │ │                   │
        │                    │    │ - refresh_token │ │                   │
        │                    │    │ - expires_at    │ │                   │
        │                    │    └─────────────────┘ │                   │
        │                    │                        │                   │
        │ 10. Success!       │                        │                   │
        │    Redirect to     │                        │                   │
        │    dashboard       │                        │                   │
        │<───────────────────│                        │                   │
        │                    │                        │                   │
```

---

### 2. AUTOMATED TOKEN REFRESH (No User Interaction!)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                    AUTOMATED TOKEN REFRESH (NO USER INTERACTION!)                   │
│           This happens automatically - user doesn't need to do anything             │
└─────────────────────────────────────────────────────────────────────────────────────┘

     ┌──────────────┐                      ┌───────────────┐      ┌─────────────────┐
     │ Your Server  │                      │  Monzo API    │      │   PostgreSQL    │
     │ (Scheduler)  │                      │               │      │                 │
     └──────┬───────┘                      └───────┬───────┘      └────────┬────────┘
            │                                      │                       │
            │ 1. Scheduled check (every 5 mins)    │                       │
            │      OR before any API call          │                       │
            │──────────────────────────────────────│──────────────────────>│
            │                                      │                       │
            │ 2. Read stored tokens                │                       │
            │<─────────────────────────────────────│───────────────────────│
            │                                      │                       │
            │ 3. Check: is_expired?                │                       │
            │    now >= expires_at - 60s           │                       │
            │                                      │                       │
            │ IF NOT EXPIRED:                      │                       │
            │    → Use existing access_token       │                       │
            │                                      │                       │
            │ IF EXPIRED OR EXPIRING SOON:         │                       │
            │                                      │                       │
            │ 4. POST /oauth2/token                │                       │
            │    grant_type=refresh_token          │                       │
            │    client_id=xxx                     │                       │
            │    client_secret=xxx                 │                       │
            │    refresh_token=xxx                 │                       │
            │─────────────────────────────────────>│                       │
            │                                      │                       │
            │ 5. Receive NEW tokens:               │                       │
            │    - access_token_2                  │                       │
            │    - refresh_token_2 (ROTATED!)      │                       │
            │    - expires_in (6hrs)               │                       │
            │<─────────────────────────────────────│                       │
            │                                      │                       │
            │ 6. ATOMICALLY update both            │                       │
            │    tokens in database                │                       │
            │    (old refresh_token is             │                       │
            │    now INVALID!)                     │                       │
            │──────────────────────────────────────│──────────────────────>│
            │                                      │                       │

⚠️  CRITICAL: Refresh tokens are ONE-TIME USE! 
    After refreshing, the OLD refresh_token is INVALIDATED.
    You MUST store the NEW refresh_token atomically.
```

---

### 3. BATCH JOB FLOW (End-of-Day Transaction Sync)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                    NIGHTLY BATCH JOB (COMPLETELY AUTOMATED!)                        │
│              No human interaction required - runs unattended                        │
└─────────────────────────────────────────────────────────────────────────────────────┘

     ┌──────────────┐    ┌─────────────────┐    ┌───────────────┐    ┌──────────────┐
     │   Scheduler  │    │  Token Service  │    │   Monzo API   │    │  PostgreSQL  │
     │  (CRON/etc)  │    │                 │    │               │    │              │
     └──────┬───────┘    └────────┬────────┘    └───────┬───────┘    └──────┬───────┘
            │                     │                     │                   │
            │ 1. 03:00 AM         │                     │                   │
            │    Trigger batch    │                     │                   │
            │    sync job         │                     │                   │
            │────────────────────>│                     │                   │
            │                     │                     │                   │
            │                     │ 2. Get valid token  │                   │
            │                     │    (auto-refresh    │                   │
            │                     │    if needed)       │                   │
            │                     │─────────────────────│──────────────────>│
            │                     │<────────────────────│───────────────────│
            │                     │                     │                   │
            │                     │ 3. (If expired)     │                   │
            │                     │    Refresh token    │                   │
            │                     │    automatically    │                   │
            │                     │────────────────────>│                   │
            │                     │<────────────────────│                   │
            │                     │                     │                   │
            │ 4. Valid access     │                     │                   │
            │    token returned   │                     │                   │
            │<────────────────────│                     │                   │
            │                     │                     │                   │
            │ 5. GET /transactions                      │                   │
            │    ?since={last_sync_time}               │                   │
            │    Authorization: Bearer {token}          │                   │
            │──────────────────────────────────────────>│                   │
            │                     │                     │                   │
            │ 6. Return transactions                    │                   │
            │<──────────────────────────────────────────│                   │
            │                     │                     │                   │
            │ 7. Upsert transactions                    │                   │
            │    (idempotent on transaction_id)         │                   │
            │──────────────────────────────────────────────────────────────>│
            │                     │                     │                   │
            │ 8. Update last_sync_time                  │                   │
            │──────────────────────────────────────────────────────────────>│
            │                     │                     │                   │

✅ This entire flow runs WITHOUT any user interaction!
   The refresh token mechanism allows indefinite automated access.
```

---

### 4. WEBHOOK FLOW (Real-Time Notifications)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          WEBHOOK FLOW (REAL-TIME)                                   │
│         Monzo pushes transactions to your server as they happen                     │
└─────────────────────────────────────────────────────────────────────────────────────┘

     ┌───────────┐    ┌────────────────┐    ┌──────────────┐    ┌──────────────┐
     │  Monzo    │    │  Cloudflare    │    │ Your Server  │    │  PostgreSQL  │
     │  Backend  │    │  Tunnel        │    │              │    │              │
     └─────┬─────┘    └────────┬───────┘    └──────┬───────┘    └──────┬───────┘
           │                   │                   │                   │
           │ 1. Transaction    │                   │                   │
           │    occurs         │                   │                   │
           │                   │                   │                   │
           │ 2. POST /webhook/monzo                │                   │
           │    X-Monzo-Signature: {sig}           │                   │
           │    Body: {type, data}                 │                   │
           │──────────────────>│                   │                   │
           │                   │                   │                   │
           │                   │ 3. Forward to     │                   │
           │                   │    Spring Boot    │                   │
           │                   │──────────────────>│                   │
           │                   │                   │                   │
           │                   │                   │ 4. Validate       │
           │                   │                   │    signature      │
           │                   │                   │                   │
           │                   │                   │ 5. Check          │
           │                   │                   │    idempotency    │
           │                   │                   │    (tx_id exists?)│
           │                   │                   │──────────────────>│
           │                   │                   │<──────────────────│
           │                   │                   │                   │
           │                   │ 6. Return 200 OK  │                   │
           │                   │    (FAST! <200ms) │                   │
           │<──────────────────│<──────────────────│                   │
           │                   │                   │                   │
           │                   │                   │ 7. Async: Upsert  │
           │                   │                   │    transaction    │
           │                   │                   │──────────────────>│
           │                   │                   │                   │
           │                   │                   │ 8. Async: Update  │
           │                   │                   │    monthly rollups│
           │                   │                   │──────────────────>│
           │                   │                   │                   │

📝 Note: Webhooks are complementary to batch sync.
   - Webhooks: Real-time, but might miss some (network issues, etc.)
   - Batch sync: Catches any missed webhooks, runs at night
```

---

### 5. RECOMMENDED ARCHITECTURE: DUAL SYNC STRATEGY

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                        DUAL SYNC STRATEGY (BELT & BRACES)                           │
└─────────────────────────────────────────────────────────────────────────────────────┘

                           ┌─────────────────────────┐
                           │      YOUR SERVER        │
                           │                         │
    REAL-TIME PATH         │   ┌─────────────────┐   │        BATCH PATH
                           │   │   Transaction   │   │
┌───────────────┐          │   │    Database     │   │         ┌───────────────┐
│    Webhooks   │──────────│───│   (PostgreSQL)  │───│─────────│  Nightly Job  │
│  (instant)    │          │   │                 │   │         │   (03:00 AM)  │
└───────────────┘          │   └─────────────────┘   │         └───────────────┘
       │                   │           │             │                │
       │                   │           │             │                │
       ▼                   │           ▼             │                ▼
┌───────────────┐          │   ┌─────────────────┐   │         ┌───────────────┐
│ Catches 99%   │          │   │   Idempotent    │   │         │ Catches any   │
│ of transactions│         │   │    Upserts      │   │         │ missed by     │
│ in real-time  │          │   │ (no duplicates) │   │         │ webhooks      │
└───────────────┘          │   └─────────────────┘   │         └───────────────┘
                           │                         │
                           └─────────────────────────┘

✅ BOTH paths write to the same database using idempotent upserts
✅ Transaction IDs ensure no duplicates even if both paths catch the same transaction
✅ Belt & braces approach ensures 100% transaction capture
```

---

## Secure Token Storage Recommendations

### Server-Side (Your Spring Boot Backend)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         SERVER-SIDE TOKEN STORAGE                                   │
└─────────────────────────────────────────────────────────────────────────────────────┘

                    ┌─────────────────────────────────────┐
                    │           PostgreSQL                │
                    │  ┌───────────────────────────────┐  │
                    │  │      oauth_tokens table       │  │
                    │  ├───────────────────────────────┤  │
                    │  │ user_id          VARCHAR(255) │  │
                    │  │ encrypted_access  TEXT        │◄─┼── AES-256-GCM encrypted
                    │  │ encrypted_refresh TEXT        │◄─┼── AES-256-GCM encrypted
                    │  │ expires_at       TIMESTAMP    │  │
                    │  │ updated_at       TIMESTAMP    │  │
                    │  └───────────────────────────────┘  │
                    └──────────────────┬──────────────────┘
                                       │
                                       │ Encryption key
                                       │
              ┌────────────────────────┼────────────────────────┐
              │                        │                        │
              ▼                        ▼                        ▼
    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────────┐
    │ Environment Var │    │   OS Keyring    │    │ Secrets Manager     │
    │  ENCRYPTION_KEY │    │ (systemd-creds) │    │ (AWS/GCP/Azure/etc) │
    │                 │    │                 │    │                     │
    │ ⚠️ Good for dev │    │ ✅ Better       │    │ ✅ Best for prod    │
    │   Not ideal for │    │    for self-    │    │    (if using cloud) │
    │   production    │    │    hosted NUC   │    │                     │
    └─────────────────┘    └─────────────────┘    └─────────────────────┘
```

#### Example Implementation (Java/Spring)

```java
@Service
public class TokenService {
    
    private final TokenRepository tokenRepository;
    private final EncryptionService encryptionService;
    
    @Transactional
    public void storeTokens(String userId, String accessToken, 
                           String refreshToken, Instant expiresAt) {
        OAuthToken token = new OAuthToken();
        token.setUserId(userId);
        token.setEncryptedAccess(encryptionService.encrypt(accessToken));
        token.setEncryptedRefresh(encryptionService.encrypt(refreshToken));
        token.setExpiresAt(expiresAt);
        token.setUpdatedAt(Instant.now());
        
        // Upsert - atomic operation
        tokenRepository.save(token);
    }
    
    public String getValidAccessToken(String userId) {
        OAuthToken token = tokenRepository.findById(userId)
            .orElseThrow(() -> new TokenNotFoundException("User not connected"));
        
        // Refresh if expiring within 60 seconds
        if (Instant.now().isAfter(token.getExpiresAt().minusSeconds(60))) {
            refreshToken(userId, token);
        }
        
        return encryptionService.decrypt(token.getEncryptedAccess());
    }
    
    @Transactional
    private void refreshToken(String userId, OAuthToken currentToken) {
        String refreshToken = encryptionService.decrypt(
            currentToken.getEncryptedRefresh());
        
        // Call Monzo API to refresh
        TokenResponse newTokens = monzoClient.refreshToken(refreshToken);
        
        // CRITICAL: Store BOTH new tokens atomically
        // The old refresh token is now INVALID!
        storeTokens(userId, 
                   newTokens.getAccessToken(),
                   newTokens.getRefreshToken(),
                   Instant.now().plusSeconds(newTokens.getExpiresIn()));
    }
}
```

### Client-Side (Frontend Apps)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         CLIENT-SIDE TOKEN STORAGE                                   │
│              (Only if your frontend needs direct Monzo API access)                  │
└─────────────────────────────────────────────────────────────────────────────────────┘

    ⚠️ RECOMMENDED: Don't store tokens client-side!
       Your server should be the only thing talking to Monzo API.
       Frontend talks to YOUR server, which proxies to Monzo.

    IF you must store tokens client-side:

    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────────┐
    │    Web App      │    │  Electron App   │    │    Mobile App       │
    │                 │    │                 │    │                     │
    │ HttpOnly Cookie │    │  safeStorage    │    │ iOS: Keychain       │
    │ + Secure flag   │    │  API (built-in) │    │ Android: Keystore   │
    │ + SameSite      │    │                 │    │                     │
    │                 │    │                 │    │                     │
    │ ❌ Never use:   │    │ ✅ Encrypted    │    │ ✅ Encrypted        │
    │    localStorage │    │    by OS        │    │    by OS            │
    │    sessionStore │    │                 │    │                     │
    └─────────────────┘    └─────────────────┘    └─────────────────────┘
```

---

## Important Considerations

### ⚠️ Critical Timing: Transaction History Access

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                    TRANSACTION HISTORY ACCESS WINDOW                                │
└─────────────────────────────────────────────────────────────────────────────────────┘

                OAuth Complete
                      │
                      ▼
    ◄────────────────────────────────►◄──────────────────────────────────────────►
          FIRST 5 MINUTES                      AFTER 5 MINUTES
                                               
    ┌─────────────────────────┐        ┌─────────────────────────┐
    │  Can fetch ALL history  │        │  Can ONLY fetch last    │
    │  (years of data!)       │        │  90 days of history     │
    └─────────────────────────┘        └─────────────────────────┘
    
    ⚡ ACTION REQUIRED:
       Immediately trigger a background job to fetch 6-12 months
       of transaction history RIGHT AFTER OAuth completes!
```

### ⚠️ Refresh Token Rotation

```
    BEFORE REFRESH                    AFTER REFRESH
    
    ┌─────────────────┐              ┌─────────────────┐
    │ refresh_token_1 │ ──────────►  │ refresh_token_2 │
    │     VALID       │   Refresh    │     VALID       │
    └─────────────────┘   Request    └─────────────────┘
                                            │
                                            │
    ┌─────────────────┐                     │
    │ refresh_token_1 │ ◄───────────────────┘
    │    INVALID!     │   Old token invalidated
    └─────────────────┘
    
    ⚠️ CRITICAL: You MUST store the new refresh_token!
       If you lose it, user must re-authenticate from scratch.
```

### When Re-Authentication is Required

| Scenario | Action Needed |
|----------|---------------|
| Normal operation | ✅ Automatic refresh works |
| Token refresh fails (network error) | 🔄 Retry automatically |
| Refresh token revoked by user | ❌ User must re-authenticate |
| Monzo account closed | ❌ Can't fix - account gone |
| User revokes app access | ❌ User must re-authenticate |
| Refresh token expired (rare) | ❌ User must re-authenticate |

---

## Summary

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              SUMMARY                                                │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  ✅ Initial OAuth setup: Requires user interaction ONCE                            │
│                                                                                     │
│  ✅ Token refresh: Fully automated, no user interaction                            │
│                                                                                     │
│  ✅ Daily batch jobs: Will work unattended using refresh token mechanism           │
│                                                                                     │
│  ✅ Webhooks: Real-time updates, no authentication needed for receiving            │
│                                                                                     │
│  ⚠️ Edge case: If refresh fails, surface "Reconnect Monzo" to user in dashboard   │
│                                                                                     │
│  🔐 Store tokens encrypted at rest (AES-256-GCM) with key in env/keyring          │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## References

- [Monzo API Documentation](../api/monzo/monzo-api.pdf)
- [OAuth 2.0 RFC 6749](https://datatracker.ietf.org/doc/html/rfc6749)
- [AES-GCM Encryption](https://en.wikipedia.org/wiki/Galois/Counter_Mode)

---

**Last Updated**: December 2024
**Status**: Reference Documentation
