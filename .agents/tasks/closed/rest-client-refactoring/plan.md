# REST Client Refactoring & Config Consolidation

**Status:** 🗂️ Backlog  
**Priority:** P2  
**Effort:** 1.5–2 days  
**Blocked by:** Monzo transaction sync + webhooks (Phase 4 + Phase 5 complete)  
**Related:** [[truelayer-integration/plan.md]]

---

## Goal

Refactor REST client architecture to:
1. **Eliminate config sprawl** — Move all client configs to `config/clients/` and `config/properties/`
2. **Reduce duplication** — Create `BankRestClient` base class for common OAuth/error handling
3. **Enable easy bank addition** — New bank = `+Properties` + `+ClientConfig` + `+Client`
4. **Prepare for TrueLayer** — Clean foundation for multi-bank adapter pattern

**Why now?** After Monzo transaction sync is stable, before TrueLayer integration begins.

---

## Background: Current Issues

### Config Sprawl
```
config/
├── MonzoClientConfig.java
├── MonzoProperties.java
├── MonzoTokenRefreshProperties.java
├── JweProperties.java
├── EncryptionProperties.java
├── AsyncConfig.java
├── SecurityConfig.java
├── WebMvcConfig.java
├── RequestLoggingFilter.java
└── ... (13 files total, hard to navigate)
```

Adding TrueLayer = `+TrueLayerClientConfig`, `+TrueLayerProperties`, `+TrueLayerOAuthProperties`, etc. Unmaintainable.

### Code Duplication

Both Monzo and TrueLayer (when added) need:
- OAuth token exchange: `POST /token` with client credentials + code
- Token refresh: `POST /token` with refresh token
- Error handling: 401 → token revoked, 403 → SCA expired, 429 → rate limit
- Bearer token in Authorization header

**Current:** Duplicated in MonzoClient. **Desired:** Shared in `BankRestClient` base class.

---

## Solution Architecture

### New Directory Structure

```
config/
├── properties/                 # ← NEW: All property records
│   ├── MonzoProperties.java    (moved from config/)
│   └── TrueLayerProperties.java (future)
├── clients/                    # ← NEW: All client configs
│   ├── RestClientFactory.java  (new generic factory)
│   ├── MonzoClientConfig.java  (moved from config/)
│   └── TrueLayerClientConfig.java (future)
├── security/                   # ← NEW: Group auth/encryption configs
│   ├── JweProperties.java      (moved from config/)
│   ├── EncryptionProperties.java (moved from config/)
│   └── SecurityConfig.java
├── AppProperties.java
├── AsyncConfig.java
└── ... (other non-client configs)

client/
├── base/
│   └── BankRestClient.java     # ← NEW: Abstract base for token exchange + error handling
├── monzo/
│   ├── MonzoClient.java        (extends BankRestClient)
│   └── dto/
└── truelayer/                  # ← FUTURE
    ├── TrueLayerClient.java
    └── dto/
```

### Key Components

#### 1. RestClientFactory (Generic)
- Creates `RestClient` beans with standard configuration
- Timeouts: 5s connect, 10s read
- Centralized place to add interceptors later (logging, metrics, retries)
- Usage: Both Monzo and TrueLayer clients use this

#### 2. BankRestClient (Abstract Base)
- Shared OAuth logic: `executeTokenRequest()` for all banks
- Shared error handling: `handleTokenError()` detects 400, 401, etc.
- Shared bearer token logic: All subclasses call REST with `Authorization: Bearer <token>`
- Subclasses only implement: bank-specific URLs, bank name for logging

#### 3. Client Configs (Spring @Configuration)
- `MonzoClientConfig` — Creates RestClient + MonzoClient beans
- `TrueLayerClientConfig` — (future) Creates RestClient + TrueLayerClient beans
- Injection point: Properties → RestClientFactory → RestClient → Client

#### 4. Property Records (ConfigurationProperties)
- `MonzoProperties` — `client_id`, `client_secret`, `auth_url`, `token_url`, `api_base_url`, etc.
- `TrueLayerProperties` — (future) Same fields, loaded from `truelayer.*` config

---

## Implementation Tasks

### Phase 1: Create Generic Infrastructure (0.5d)

- [ ] Create `RestClientFactory` with static factory methods
  - `createClient(baseUrl)` — basic RestClient
  - `createClientWithInterceptor(baseUrl, interceptor)` — for future logging/metrics
  - Request factory with configurable timeouts

- [ ] Create `BankRestClient` abstract base class
  - `executeTokenRequest(tokenUrl, formData)` — shared logic
  - `handleTokenError(RestClientResponseException)` — shared error handling
  - `abstract String getBankName()` — subclasses implement

### Phase 2: Organize Configs (0.5d)

- [ ] Create `config/properties/` subdirectory
  - Move `MonzoProperties.java` → `config/properties/MonzoProperties.java`
  - Create placeholder `TrueLayerProperties.java` (for forward compatibility)

- [ ] Create `config/clients/` subdirectory
  - Move `MonzoClientConfig.java` → `config/clients/MonzoClientConfig.java`
  - Move `RestClientFactory.java` → `config/clients/RestClientFactory.java`
  - Create placeholder `TrueLayerClientConfig.java` (for forward compatibility)

- [ ] Create `config/security/` subdirectory
  - Move `JweProperties.java`, `EncryptionProperties.java`, `SecurityConfig.java`

- [ ] Update `@ConfigurationPropertiesScan` in main app class to include new locations

### Phase 3: Refactor MonzoClient (0.5d)

- [ ] Update `MonzoClient` to extend `BankRestClient`
  - Remove duplicated `executeTokenRequest()` — now inherited
  - Remove duplicated error handling — now inherited
  - Keep Monzo-specific methods: `whoAmI()`, `getAccounts()`, `getTransactions()`

- [ ] Update `MonzoClientConfig` to use `RestClientFactory`
  - `@Bean monzoRestClient()` calls `RestClientFactory.createClient(baseUrl)`

- [ ] Verify all tests still pass (unit + integration)

### Phase 4: Documentation (0.25d)

- [ ] Update `.agents/notes/architecture-notes.md` with new config structure
  - Diagram of RestClientFactory → MonzoClient flow
  - How to add a new bank (TrueLayer example)

- [ ] Add JavaDoc to `BankRestClient` explaining abstract methods + inheritance

---

## Database Changes

**None.** This is a pure refactoring (no schema changes).

---

## Files to Create

```
client/base/
└── BankRestClient.java           # Abstract base for all bank clients

config/properties/
├── MonzoProperties.java          # MOVE from config/
└── TrueLayerProperties.java       # NEW (placeholder, no code yet)

config/clients/
├── RestClientFactory.java         # NEW
├── MonzoClientConfig.java         # MOVE from config/
└── TrueLayerClientConfig.java      # NEW (placeholder, no code yet)

config/security/
├── JweProperties.java             # MOVE from config/
├── EncryptionProperties.java       # MOVE from config/
└── SecurityConfig.java            # (may already be in config/)
```

---

## Files to Modify

- `MonzoClient.java` — Extend `BankRestClient`, remove duplicated token/error logic
- `MonzoClientConfig.java` — Use `RestClientFactory`
- `BudgeteerApplication.java` — Update `@ConfigurationPropertiesScan` paths
- All imports in tests — Update package paths after moves

---

## Testing Strategy

### Unit Tests
- `BankRestClientTest` — Mock RestClient, verify `executeTokenRequest()` and `handleTokenError()` work
- `RestClientFactoryTest` — Verify RestClient is created with correct config

### Integration Tests
- Existing `MonzoClientIT` tests should still pass after refactoring
- No new IT tests needed (same behavior, just reorganized)

### Manual Testing
- Run `/api/monzo/connect` — OAuth flow should work unchanged
- Check transaction sync — Should still fetch data correctly

---

## Deferred (Out of Scope)

- Adding actual interceptors (logging, metrics) — Do this when you need it
- Rate limiting / retry logic — Phase it in when needed
- Caching REST responses — Future optimization

---

## Acceptance Criteria

- [ ] All configs under `config/properties/` or `config/clients/`
- [ ] No duplication of OAuth/error logic — all in `BankRestClient`
- [ ] `MonzoClient` extends `BankRestClient`
- [ ] All existing tests pass (unit + integration)
- [ ] `config/` directory is clean and organized
- [ ] `RestClientFactory` is used for all REST client creation
- [ ] Code review notes: Refactoring is transparent to API/service layers

---

## How to Add a New Bank After This Refactoring

**New bank = 3 files:**

```java
// 1. Create properties
@ConfigurationProperties(prefix = "newbank")
public record NewBankProperties(
    String clientId,
    String clientSecret,
    String redirectUri,
    String tokenUrl,
    String apiBaseUrl
) {}

// 2. Create client config
@Configuration
public class NewBankClientConfig {
    @Bean
    RestClient newBankRestClient(NewBankProperties props) {
        return RestClientFactory.createClient(props.apiBaseUrl());
    }
    
    @Bean
    NewBankClient newBankClient(NewBankProperties props, RestClient restClient) {
        return new NewBankClient(props, restClient);
    }
}

// 3. Create client (extends BankRestClient)
@Component
public class NewBankClient extends BankRestClient {
    private final NewBankProperties props;
    
    public TokenResponse exchangeCode(String code) {
        // Use inherited executeTokenRequest()
        Map<String, Object> response = executeTokenRequest(
            props.tokenUrl(),
            Map.of("client_id", props.clientId(), "code", code, ...)
        );
        return parseResponse(response);
    }
    
    @Override
    protected String getBankName() { return "NewBank"; }
}
```

**That's it.** Add to `application.properties`:
```properties
newbank.client-id=${NEWBANK_CLIENT_ID}
newbank.client-secret=${NEWBANK_CLIENT_SECRET}
...
```

Then when TrueLayer integration happens, just add `TrueLayerClient extends BankRestClient` with same pattern.

---

## Migration Path

1. **Now** (Phase 4 complete): Finish Monzo transaction sync + webhooks
2. **Then** (Pull into Queue): This refactoring (1.5–2d)
3. **After** (Pull into Queue): TrueLayer integration (3–4d)
   - All the OAuth abstraction is already done
   - Just implement TrueLayer-specific data endpoints

---

## Notes

- **No breaking changes** — API endpoints unchanged, service logic unchanged, database unchanged
- **Easy to review** — Pure reorganization + inheritance, no business logic changes
- **Safe to defer** — Can add TrueLayer without this refactoring, but will duplicate code
- **Foundation for future banks** — Barclays, HSBC, Santander, etc. all use same pattern after this

---

## Related Context

- **TrueLayer Integration Task:** `.agents/tasks/truelayer-integration/plan.md`
- **Monzo Direct Integration:** Currently in progress (Phase 4)
- **Architecture Diagram:** `.agents/notes/architecture-notes.md` (to be updated)

