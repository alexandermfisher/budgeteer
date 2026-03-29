# Testing Monzo OAuth Flow with Cloud Tunnel (No Frontend)

> A step-by-step guide to test the complete Monzo OAuth flow using a temporary cloud tunnel, without needing a frontend application.

---

## 📋 Overview

Since Monzo requires a **publicly accessible callback URL** for OAuth, and we don't have a frontend yet, we'll use:
1. A **cloud tunnel** (ngrok, Cloudflare Tunnel, or localtunnel) to expose our local Spring Boot server
2. **Browser + curl/httpie** to manually trigger and complete the OAuth flow
3. **Simple REST endpoints** to handle the OAuth dance

---

## 🛠️ Prerequisites

### 1. Monzo Developer Account
- Go to [https://developers.monzo.com/](https://developers.monzo.com/)
- Sign in with your Monzo account
- Create a new OAuth client (we'll configure the redirect URI later)

### 2. Cloud Tunnel Tool (Choose ONE)

#### Option A: ngrok (Recommended for quick testing)
```bash
# Install via Homebrew
brew install ngrok

# Or download from https://ngrok.com/download
# Free tier works fine for testing
```

#### Option B: Cloudflare Tunnel (Free, more permanent)
```bash
# Install cloudflared
brew install cloudflared

# Or download from https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/
```

#### Option C: localtunnel (No signup required)
```bash
# Install globally
npm install -g localtunnel
```

### 3. Local Environment
- Java 21+ installed
- Maven installed
- Docker running (for PostgreSQL)

---

## 🚀 Step-by-Step Guide

### Step 1: Start the Database

```bash
# From project root
docker compose up -d

# Verify it's running
docker ps
```

### Step 2: Start the Cloud Tunnel

Choose your preferred tunnel and start it pointing to port 8080:

#### ngrok
```bash
ngrok http 8080

# You'll see something like:
# Forwarding: https://abc123.ngrok-free.app -> http://localhost:8080
```

#### Cloudflare Tunnel (Quick tunnel - no account needed)
```bash
cloudflared tunnel --url http://localhost:8080

# You'll see something like:
# Your quick tunnel: https://random-words.trycloudflare.com
```

#### localtunnel
```bash
lt --port 8080

# You'll see something like:
# your url is: https://wild-rabbit-42.loca.lt
```

**📝 Write down your tunnel URL!** You'll need it for the next step.
Example: `https://abc123.ngrok-free.app`

### Step 3: Configure Monzo OAuth Client

1. Go to [https://developers.monzo.com/](https://developers.monzo.com/)
2. Click on your client (or create a new one)
3. Set these values:
   - **Name**: Budgeteer (Dev)
   - **Redirect URLs**: `https://YOUR-TUNNEL-URL/auth/callback`
     - Example: `https://abc123.ngrok-free.app/auth/callback`
   - **Confidentiality**: Confidential (important!)
   - **Description**: Personal budgeting app

4. **Copy these values** (you'll need them):
   - Client ID
   - Client Secret

### Step 4: Configure Spring Boot Application

Create/update `src/main/resources/application.properties`:

```properties
spring.application.name=budgeteer

# Server
server.port=8080

# Database (Docker Compose will provide these)
spring.datasource.url=jdbc:postgresql://localhost:5432/budgeteer
spring.datasource.username=budgeteer
spring.datasource.password=budgeteer

# Flyway
spring.flyway.enabled=true
spring.flyway.baseline-on-migrate=true

# Monzo OAuth Configuration
monzo.client-id=${MONZO_CLIENT_ID:your-client-id-here}
monzo.client-secret=${MONZO_CLIENT_SECRET:your-client-secret-here}
monzo.redirect-uri=${MONZO_REDIRECT_URI:https://your-tunnel-url/auth/callback}

# OAuth URLs (Monzo)
monzo.auth-url=https://auth.monzo.com/
monzo.token-url=https://api.monzo.com/oauth2/token
monzo.api-base-url=https://api.monzo.com

# Logging (helpful for debugging)
logging.level.dev.amf.budgeteer=DEBUG
logging.level.org.springframework.security=DEBUG
```

**Or use environment variables** (more secure):
```bash
export MONZO_CLIENT_ID="your-client-id"
export MONZO_CLIENT_SECRET="your-client-secret"
export MONZO_REDIRECT_URI="https://abc123.ngrok-free.app/auth/callback"
```

### Step 5: Create Minimal OAuth Endpoints

You'll need to create these files to handle the OAuth flow:

#### 5.1 Configuration Properties Class

Create `src/main/java/dev/amf/budgeteer/config/MonzoProperties.java`:

```java
package dev.amf.budgeteer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "monzo")
public record MonzoProperties(
    String clientId,
    String clientSecret,
    String redirectUri,
    String authUrl,
    String tokenUrl,
    String apiBaseUrl
) {}
```

#### 5.2 Enable Configuration Properties

Update `src/main/java/dev/amf/budgeteer/BudgeteerApplication.java`:

```java
package dev.amf.budgeteer;

import dev.amf.budgeteer.config.MonzoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MonzoProperties.class)
public class BudgeteerApplication {
    public static void main(String[] args) {
        SpringApplication.run(BudgeteerApplication.class, args);
    }
}
```

#### 5.3 Security Configuration (Allow OAuth endpoints)

Create `src/main/java/dev/amf/budgeteer/config/SecurityConfig.java`:

```java
package dev.amf.budgeteer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Allow OAuth endpoints without authentication
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers("/api/test/**").permitAll()
                // Everything else requires authentication (for later)
                .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/auth/**")
            );
        
        return http.build();
    }
}
```

#### 5.4 OAuth Controller

Create `src/main/java/dev/amf/budgeteer/controller/AuthController.java`:

```java
package dev.amf.budgeteer.controller;

import dev.amf.budgeteer.config.MonzoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    
    private final MonzoProperties monzoProperties;
    private final RestClient restClient;
    
    // In production, store this in session or database
    private String storedState;
    // Store tokens in memory for testing (use database in production!)
    private String accessToken;
    private String refreshToken;

    public AuthController(MonzoProperties monzoProperties) {
        this.monzoProperties = monzoProperties;
        this.restClient = RestClient.create();
    }

    /**
     * Step 1: Initiate OAuth flow - redirects to Monzo
     * Visit: https://your-tunnel-url/auth/monzo/connect
     */
    @GetMapping("/monzo/connect")
    public RedirectView initiateOAuth() {
        // Generate random state for CSRF protection
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        storedState = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        
        String authorizationUrl = UriComponentsBuilder
            .fromUriString(monzoProperties.authUrl())
            .queryParam("client_id", monzoProperties.clientId())
            .queryParam("redirect_uri", monzoProperties.redirectUri())
            .queryParam("response_type", "code")
            .queryParam("state", storedState)
            .build()
            .toUriString();
        
        log.info("Redirecting to Monzo OAuth: {}", authorizationUrl);
        return new RedirectView(authorizationUrl);
    }

    /**
     * Step 2: Handle OAuth callback from Monzo
     * Monzo redirects here after user authorizes
     */
    @GetMapping("/callback")
    public ResponseEntity<?> handleCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        
        log.info("Received callback with code: {}... and state: {}", 
                code.substring(0, Math.min(10, code.length())), state);
        
        // Verify state to prevent CSRF
        if (!state.equals(storedState)) {
            log.error("State mismatch! Expected: {}, Got: {}", storedState, state);
            return ResponseEntity.badRequest().body(Map.of(
                "error", "state_mismatch",
                "message", "State parameter doesn't match. Possible CSRF attack."
            ));
        }
        
        // Exchange code for tokens
        try {
            Map<String, Object> tokens = exchangeCodeForTokens(code);
            
            // Store tokens (in memory for testing)
            this.accessToken = (String) tokens.get("access_token");
            this.refreshToken = (String) tokens.get("refresh_token");
            
            log.info("✅ Successfully obtained tokens!");
            log.info("Access token expires in: {} seconds", tokens.get("expires_in"));
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "OAuth flow completed successfully!",
                "access_token_preview", accessToken.substring(0, Math.min(20, accessToken.length())) + "...",
                "has_refresh_token", refreshToken != null,
                "expires_in", tokens.get("expires_in"),
                "token_type", tokens.get("token_type"),
                "next_steps", Map.of(
                    "test_api", "GET /auth/test-api",
                    "whoami", "GET /auth/whoami",
                    "accounts", "GET /auth/accounts"
                )
            ));
        } catch (Exception e) {
            log.error("Failed to exchange code for tokens", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "token_exchange_failed",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Exchange authorization code for access & refresh tokens
     */
    private Map<String, Object> exchangeCodeForTokens(String code) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "authorization_code");
        formData.add("client_id", monzoProperties.clientId());
        formData.add("client_secret", monzoProperties.clientSecret());
        formData.add("redirect_uri", monzoProperties.redirectUri());
        formData.add("code", code);

        log.debug("Exchanging code for tokens at: {}", monzoProperties.tokenUrl());
        
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
            .uri(monzoProperties.tokenUrl())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(formData)
            .retrieve()
            .body(Map.class);
        
        return response;
    }

    /**
     * Test endpoint: Get current user info (whoami)
     */
    @GetMapping("/whoami")
    public ResponseEntity<?> whoAmI() {
        if (accessToken == null) {
            return ResponseEntity.status(401).body(Map.of(
                "error", "not_authenticated",
                "message", "No access token. Visit /auth/monzo/connect first."
            ));
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                .uri(monzoProperties.apiBaseUrl() + "/ping/whoami")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("API call failed", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "api_call_failed",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Test endpoint: Get accounts
     */
    @GetMapping("/accounts")
    public ResponseEntity<?> getAccounts() {
        if (accessToken == null) {
            return ResponseEntity.status(401).body(Map.of(
                "error", "not_authenticated",
                "message", "No access token. Visit /auth/monzo/connect first."
            ));
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                .uri(monzoProperties.apiBaseUrl() + "/accounts")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("API call failed", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "api_call_failed",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Test endpoint: Get balance (requires account_id)
     */
    @GetMapping("/balance")
    public ResponseEntity<?> getBalance(@RequestParam("account_id") String accountId) {
        if (accessToken == null) {
            return ResponseEntity.status(401).body(Map.of(
                "error", "not_authenticated",
                "message", "No access token. Visit /auth/monzo/connect first."
            ));
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                .uri(monzoProperties.apiBaseUrl() + "/balance?account_id=" + accountId)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("API call failed", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "api_call_failed",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Status endpoint: Check current authentication status
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        return ResponseEntity.ok(Map.of(
            "authenticated", accessToken != null,
            "has_refresh_token", refreshToken != null,
            "connect_url", "/auth/monzo/connect"
        ));
    }
}
```

### Step 6: Create Flyway Baseline Migration

Create `src/main/resources/db/migration/V1__baseline.sql`:

```sql
-- Baseline migration
-- This is a placeholder for the initial schema
-- Real tables will be added in subsequent migrations

SELECT 1;
```

### Step 7: Start the Application

```bash
# Set environment variables
export MONZO_CLIENT_ID="your-client-id"
export MONZO_CLIENT_SECRET="your-client-secret"  
export MONZO_REDIRECT_URI="https://your-tunnel-url/auth/callback"

# Run the application
mvn spring-boot:run
```

---

## 🧪 Testing the Full OAuth Flow

### Test 1: Check Application is Running

```bash
# In a new terminal
curl https://your-tunnel-url/auth/status

# Expected response:
# {"authenticated":false,"has_refresh_token":false,"connect_url":"/auth/monzo/connect"}
```

### Test 2: Initiate OAuth Flow

1. **Open your browser** and navigate to:
   ```
   https://your-tunnel-url/auth/monzo/connect
   ```

2. **You'll be redirected to Monzo** - log in with your email

3. **Check your Monzo app** - you'll get a push notification to approve the connection
   - Open the Monzo app
   - Approve the access request (may require PIN/Face ID)

4. **You'll be redirected back** to your callback URL
   - You should see a JSON response with:
     - `status: "success"`
     - `access_token_preview: "..."` 
     - `has_refresh_token: true`
     - `next_steps: {...}`

### Test 3: Verify Access Token Works

```bash
# Test whoami endpoint
curl https://your-tunnel-url/auth/whoami

# Expected response:
# {"authenticated":true,"client_id":"...","user_id":"..."}
```

### Test 4: Get Accounts

```bash
# Get your accounts
curl https://your-tunnel-url/auth/accounts

# Expected response:
# {"accounts":[{"id":"acc_xxx","type":"uk_retail",...}]}
```

### Test 5: Get Balance

```bash
# Use the account ID from the previous response
curl "https://your-tunnel-url/auth/balance?account_id=acc_xxx"

# Expected response:
# {"balance":12345,"total_balance":12345,"currency":"GBP",...}
```

---

## 🔍 Troubleshooting

### "State mismatch" Error
- The session state is stored in memory - if you restart the app between initiating and completing OAuth, it will fail
- Solution: Start OAuth flow again from `/auth/monzo/connect`

### "Invalid redirect_uri" from Monzo
- Ensure the redirect URI in Monzo Developer Portal **exactly matches** what you're using
- Include the full path: `https://your-tunnel-url/auth/callback`
- No trailing slashes!

### "Invalid client_secret" 
- Double-check your environment variables are set correctly
- Ensure no extra spaces or newlines in the secret

### Tunnel URL Changed
- ngrok free tier gives you a new URL each time
- Update Monzo Developer Portal with the new redirect URI
- Update your `MONZO_REDIRECT_URI` environment variable
- Restart the Spring Boot application

### SCA Timeout
- After entering your email on Monzo, you have ~5 minutes to approve in the app
- If you miss it, start over from `/auth/monzo/connect`

### "Insufficient permissions"
- Some endpoints require extra permissions
- Check the Monzo API docs for required scopes
- You may need to request elevated access from Monzo

---

## ⚡ Quick Reference: Tunnel Commands

### Start Tunnels
```bash
# ngrok
ngrok http 8080

# cloudflare quick tunnel
cloudflared tunnel --url http://localhost:8080

# localtunnel
lt --port 8080
```

### Get Tunnel URL
- **ngrok**: Look for `Forwarding` line, use the `https://` URL
- **cloudflare**: Look for `Your quick tunnel:` line
- **localtunnel**: Look for `your url is:` line

---

## 📝 Important Notes

### About Token Storage (Current Implementation)
⚠️ **The current implementation stores tokens in memory** - they will be lost when the app restarts. This is fine for testing but **must be replaced with database storage** for real use.

### About the 5-Minute Window
🕐 After completing OAuth, you have **5 minutes** to fetch historical transactions (beyond 90 days). The current test implementation doesn't do this automatically - this is a future enhancement.

### About Refresh Tokens
🔄 Access tokens expire in **6 hours**. The current test implementation doesn't auto-refresh. For production, implement the `TokenService` as shown in `MONZO-AUTH-FLOW.md`.

---

## ✅ Success Checklist

After completing this guide, you should have:

- [ ] Cloud tunnel running and exposing localhost:8080
- [ ] Monzo Developer client configured with correct redirect URI
- [ ] Spring Boot app running with OAuth endpoints
- [ ] Successfully completed OAuth flow (got access token)
- [ ] Successfully called `/auth/whoami` 
- [ ] Successfully called `/auth/accounts`
- [ ] Successfully called `/auth/balance`

---

## 🔜 Next Steps

Once you've verified the OAuth flow works:

1. **Implement proper token storage** - Database with encryption
2. **Add token refresh logic** - Auto-refresh before expiry
3. **Build transaction sync** - Fetch and store transactions
4. **Add webhook endpoint** - Real-time transaction updates
5. **Build the frontend** - Or continue using API directly

---

**Last Updated**: December 2024  
**Author**: Cline/Claude  
**Status**: Testing Guide
