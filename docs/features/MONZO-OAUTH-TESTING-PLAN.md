# Monzo OAuth Manual Testing Plan

## Overview

This document outlines the manual testing plan for the Monzo OAuth flow, specifically focusing on the **user denial scenario** and frontend handling requirements.

---

## The Scenario: User Denies Monzo Access

### Current State
When a user denies access in the Monzo app:
1. Monzo redirects to `/api/monzo/callback?error=access_denied&error_description=...&state=...`
2. Server returns `400 Bad Request` with `OAUTH_ACCESS_DENIED` error
3. **Problem**: Browser shows raw JSON error - no frontend redirect

### Expected Behavior
1. User denies in Monzo app
2. Server catches the denial
3. **Redirect to frontend** error page (e.g., `/connect/failed?reason=access_denied`)
4. Frontend shows user-friendly message

---

## Testing Prerequisites

### One-Time Setup (To Test Denial)
Since Monzo caches OAuth approvals, you must revoke access first:

1. Open **Monzo app** on phone
2. Go to **Settings → Privacy & Security → Third party apps**
3. Find your OAuth client and **Remove** it
4. Now Monzo will prompt for approval again

### Environment Setup
```bash
# Start ngrok tunnel (for public callback URL)
./scripts/dev.sh tunnel

# Update .env with ngrok URL
MONZO_REDIRECT_URI=https://YOUR-NGROK-URL.ngrok-free.app/api/monzo/callback

# Update Monzo Developer Portal redirect URI to match

# Start the app
./scripts/dev.sh start
```

---

## Manual Test: User Denial Flow

### Steps

1. **Clear existing data** (optional but recommended):
   ```sql
   DELETE FROM monzo_connections WHERE TRUE;
   DELETE FROM oauth_states WHERE TRUE;
   ```

2. **Authenticate** in Postman:
   - Run "Quick Login" → saves `access_token`

3. **Initiate OAuth**:
   - Run "Initiate OAuth (JSON)" → get `authorizationUrl`

4. **Open URL in browser**:
   - Paste the authorization URL
   - Monzo login page opens

5. **Deny in Monzo app**:
   - When push notification arrives, tap **Deny**
   - OR on the web page, click "Don't allow" if shown

6. **Observe result**:
   - Browser redirects to callback with `error=access_denied`
   - Server returns JSON error:
     ```json
     {
       "success": false,
       "error": {
         "code": "OAUTH_ACCESS_DENIED",
         "message": "User denied access to Monzo account"
       }
     }
     ```

7. **Verify no connection created**:
   - Run "List Connections" → should be empty

### Expected Server Log Output
```
WARN  OAuth denied by user xxx [error=access_denied, description=The user denied access]
```

---

## Frontend Implementation Plan

### Current State
- Callback returns JSON → browser shows raw JSON (bad UX)
- No redirect to frontend

### Required Changes

#### Option A: Server-Side Redirect (Recommended)
Modify `MonzoController.handleCallback()` to redirect instead of returning JSON:

```java
@GetMapping("/callback")
public Object handleCallback(
    @RequestParam(value = "code", required = false) @Nullable String code,
    @RequestParam("state") String state,
    @RequestParam(value = "error", required = false) @Nullable String error,
    @RequestParam(value = "error_description", required = false) @Nullable String errorDescription
) {
    User user = oauthService.verifyStateAndGetUser(state);
    
    // Handle denial - redirect to frontend error page
    if (error != null) {
        log.warn("OAuth denied by user {}", user.getId());
        String redirectUrl = frontendBaseUrl + "/connect/error?code=access_denied";
        return new RedirectView(redirectUrl);
    }
    
    // Handle success - redirect to frontend success page
    MonzoConnection connection = // ... create connection
    String redirectUrl = frontendBaseUrl + "/connect/success?connectionId=" + connection.getId();
    return new RedirectView(redirectUrl);
}
```

#### Option B: Frontend Polling
1. Frontend opens OAuth in popup/new tab
2. Frontend polls `/api/monzo/status` for connection
3. When connection appears → show success
4. If popup closes without connection → show error

### Frontend Routes Needed
| Route | Purpose |
|-------|---------|
| `/connect` | Initiate connection flow |
| `/connect/success` | OAuth completed successfully |
| `/connect/error` | OAuth failed/denied |
| `/connect/callback` | (Optional) Intermediate page for popup flows |

### Frontend Error Handling
```typescript
// Example React component
function ConnectError() {
  const { searchParams } = useSearchParams();
  const errorCode = searchParams.get('code');
  
  const errorMessages = {
    'access_denied': 'You declined to connect your Monzo account.',
    'expired': 'The connection request expired. Please try again.',
    'invalid': 'Something went wrong. Please try again.',
  };
  
  return (
    <div>
      <h1>Connection Failed</h1>
      <p>{errorMessages[errorCode] || 'An error occurred.'}</p>
      <button onClick={() => navigate('/connect')}>Try Again</button>
    </div>
  );
}
```

---

## Configuration Changes Required

### Add Frontend Base URL Property
```properties
# application.properties
app.frontend.base-url=http://localhost:3000

# application-prod.properties
app.frontend.base-url=https://budgeteer.app
```

### Add to AppProperties
```java
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    // ... existing
    Frontend frontend
) {
    public record Frontend(String baseUrl) {}
}
```

---

## Testing Checklist

### Before Frontend Exists
- [ ] Verify `OAUTH_ACCESS_DENIED` error returns `400` status
- [ ] Verify server logs show denial warning
- [ ] Verify no connection is created on denial
- [ ] Verify state is marked as used (prevents replay)

### After Frontend Implementation
- [ ] Denial redirects to `/connect/error?code=access_denied`
- [ ] Success redirects to `/connect/success?connectionId=xxx`
- [ ] Error page shows user-friendly message
- [ ] "Try Again" button works
- [ ] Connection appears in list after success

### Other Error Scenarios
- [ ] Expired state (wait 10+ min) → `OAUTH_STATE_EXPIRED`
- [ ] Invalid state → `OAUTH_STATE_INVALID`
- [ ] Monzo API error → `MONZO_API_ERROR`

---

## Notes

### Monzo Caches Approvals
If you've approved the OAuth client before, Monzo may auto-approve without prompting. To test denial:
1. Revoke app access in Monzo app settings
2. OR create a new OAuth client in developer portal

### State Expiry
OAuth states expire after **10 minutes**. If you take too long to approve/deny, you'll get `OAUTH_STATE_EXPIRED` instead.

### Integration Tests
All error scenarios are covered in `MonzoOAuthFlowIT.java`:
- `shouldReturnAccessDeniedError()`
- `shouldReturnInvalidStateError()`
- `shouldReturnMissingCodeError()`
- etc.

These use WireMock and don't require real Monzo credentials.
