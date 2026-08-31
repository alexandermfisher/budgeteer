# Monzo Simulator - Design & Implementation Plan

> **Status:** Planning  
> **Purpose:** Mock Monzo API server for integration and acceptance testing  
> **Date:** January 2026

---

## 📋 Overview

The Monzo Simulator is a standalone Spring Boot application that mimics the Monzo API. It enables:

1. **Integration testing** - Test `MonzoClient` against realistic HTTP responses
2. **Acceptance testing** - Full E2E OAuth and transaction flows
3. **Manual testing** - Use Postman/curl without needing real Monzo credentials
4. **Webhook testing** - Trigger webhooks to test your webhook handlers

---

## 🏗️ Project Structure

```
budgeteer/
├── pom.xml                       # Parent POM - add monzo-simulator module
├── backend/
├── frontend/
├── monzo-simulator/              # NEW MODULE
│   ├── pom.xml                   # Spring Boot Web (minimal deps)
│   ├── Dockerfile                # For Docker Compose / Testcontainers
│   └── src/
│       ├── main/
│       │   ├── java/dev/amf/monzosim/
│       │   │   ├── MonzoSimulatorApplication.java
│       │   │   ├── config/
│       │   │   │   └── SimulatorConfig.java
│       │   │   ├── controller/
│       │   │   │   ├── OAuthController.java        # /oauth2/*
│       │   │   │   ├── AccountsController.java     # /accounts, /balance
│       │   │   │   ├── TransactionsController.java # /transactions
│       │   │   │   ├── PotsController.java         # /pots
│       │   │   │   ├── WebhooksController.java     # /webhooks (register)
│       │   │   │   └── AdminController.java        # /admin/* (test control)
│       │   │   ├── service/
│       │   │   │   ├── TokenService.java           # Token generation/validation
│       │   │   │   ├── DataService.java            # In-memory data store
│       │   │   │   └── WebhookService.java         # Send webhooks to callbacks
│       │   │   ├── model/
│       │   │   │   ├── Account.java
│       │   │   │   ├── Transaction.java
│       │   │   │   ├── Pot.java
│       │   │   │   ├── OAuthToken.java
│       │   │   │   └── Webhook.java
│       │   │   └── dto/
│       │   │       ├── TokenResponse.java
│       │   │       ├── AccountsResponse.java
│       │   │       ├── TransactionsResponse.java
│       │   │       └── WebhookPayload.java
│       │   └── resources/
│       │       ├── application.properties
│       │       └── data/
│       │           └── seed-data.json              # Default test data
│       └── test/
│           └── java/dev/amf/monzosim/
│               └── MonzoSimulatorApplicationTests.java
├── compose.yaml                  # Add monzo-simulator service
└── compose.test.yaml             # For integration test runs
```

---

## 🔧 Dependencies (monzo-simulator/pom.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.1</version>
    </parent>
    
    <groupId>dev.amf</groupId>
    <artifactId>monzo-simulator</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>Monzo API Simulator</name>
    <description>Mock Monzo API server for testing</description>
    
    <properties>
        <java.version>25</java.version>
    </properties>
    
    <dependencies>
        <!-- Web only - no JPA, no security -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <!-- Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        
        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
        
        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 🔌 API Endpoints

### OAuth Endpoints (matching Monzo's API)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/oauth2/authorize` | Redirect to login page (simulated) |
| `POST` | `/oauth2/token` | Exchange code for tokens |

**`POST /oauth2/token` Response:**
```json
{
    "access_token": "sim_access_abc123",
    "token_type": "Bearer",
    "expires_in": 21600,
    "refresh_token": "sim_refresh_xyz789",
    "scope": "read write",
    "user_id": "user_sim_001"
}
```

### Account Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/accounts` | List all accounts |
| `GET` | `/balance?account_id=xxx` | Get account balance |

**`GET /accounts` Response:**
```json
{
    "accounts": [
        {
            "id": "acc_sim_001",
            "description": "Simulator Current Account",
            "created": "2025-01-01T00:00:00Z",
            "type": "uk_retail",
            "currency": "GBP",
            "country_code": "GB",
            "owners": [{"user_id": "user_sim_001", "preferred_name": "Test User"}],
            "closed": false
        }
    ]
}
```

### Transaction Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/transactions?account_id=xxx` | List transactions |
| `GET` | `/transactions/{id}` | Get single transaction |

**Query Parameters:**
- `account_id` (required)
- `since` (optional, ISO 8601)
- `before` (optional, ISO 8601)
- `limit` (optional, default 100, max 100)

**`GET /transactions` Response:**
```json
{
    "transactions": [
        {
            "id": "tx_sim_001",
            "created": "2025-01-10T12:30:00Z",
            "description": "Tesco",
            "amount": -2500,
            "currency": "GBP",
            "merchant": {
                "id": "merch_sim_001",
                "name": "Tesco",
                "category": "groceries"
            },
            "notes": "",
            "metadata": {},
            "account_id": "acc_sim_001",
            "category": "groceries",
            "settled": "2025-01-10T12:30:00Z",
            "local_amount": -2500,
            "local_currency": "GBP"
        }
    ]
}
```

### Pot Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/pots?current_account_id=xxx` | List pots |
| `PUT` | `/pots/{id}/deposit` | Deposit to pot |
| `PUT` | `/pots/{id}/withdraw` | Withdraw from pot |

### Webhook Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/webhooks` | Register a webhook |
| `GET` | `/webhooks?account_id=xxx` | List webhooks |
| `DELETE` | `/webhooks/{id}` | Delete webhook |

---

## 🎮 Admin Endpoints (Test Control)

These endpoints control the simulator's behaviour for testing.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/admin/reset` | Reset all data to seed state |
| `POST` | `/admin/transactions` | Create a transaction |
| `POST` | `/admin/trigger-webhook` | Send webhook to registered URL |
| `POST` | `/admin/config/delay` | Set response delay (ms) |
| `POST` | `/admin/config/error-rate` | Set error rate (0-100%) |
| `POST` | `/admin/tokens/expire` | Force expire a token |

### Trigger Webhook Example

```bash
POST /admin/trigger-webhook
{
    "type": "transaction.created",
    "account_id": "acc_sim_001",
    "transaction_id": "tx_sim_001"
}
```

This will send a webhook payload to all registered webhook URLs for that account.

### Create Transaction Example

```bash
POST /admin/transactions
{
    "account_id": "acc_sim_001",
    "amount": -1500,
    "description": "Costa Coffee",
    "category": "eating_out",
    "merchant_name": "Costa"
}
```

Returns the created transaction and optionally triggers a webhook.

---

## 💾 In-Memory Data Store

The simulator uses an in-memory data store (ConcurrentHashMap) for simplicity.

```java
@Service
public class DataService {
    
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, List<Transaction>> transactions = new ConcurrentHashMap<>();
    private final Map<String, OAuthToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, List<Webhook>> webhooks = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void loadSeedData() {
        // Load from seed-data.json
    }
    
    public void reset() {
        accounts.clear();
        transactions.clear();
        tokens.clear();
        webhooks.clear();
        loadSeedData();
    }
    
    // CRUD methods...
}
```

### Seed Data (seed-data.json)

```json
{
    "users": [
        {"id": "user_sim_001", "name": "Test User", "email": "test@example.com"}
    ],
    "accounts": [
        {
            "id": "acc_sim_001",
            "user_id": "user_sim_001",
            "type": "uk_retail",
            "description": "Personal Account",
            "balance": 150000,
            "currency": "GBP"
        }
    ],
    "transactions": [
        {
            "id": "tx_sim_001",
            "account_id": "acc_sim_001",
            "amount": -2500,
            "description": "Tesco",
            "category": "groceries",
            "created": "2025-01-10T12:30:00Z"
        },
        {
            "id": "tx_sim_002",
            "account_id": "acc_sim_001",
            "amount": 300000,
            "description": "Salary",
            "category": "income",
            "created": "2025-01-01T09:00:00Z"
        }
    ],
    "pots": [
        {
            "id": "pot_sim_001",
            "account_id": "acc_sim_001",
            "name": "Savings",
            "balance": 50000,
            "currency": "GBP"
        }
    ]
}
```

---

## 🔐 Token Handling

### Token Format

Simulator tokens are prefixed for easy identification:
- Access tokens: `sim_access_<random>`
- Refresh tokens: `sim_refresh_<random>`

### Token Validation

```java
@Service
public class TokenService {
    
    private final DataService dataService;
    
    public boolean validateAccessToken(String token) {
        if (!token.startsWith("sim_access_")) {
            return false;
        }
        OAuthToken stored = dataService.getToken(token);
        return stored != null && stored.getExpiresAt().isAfter(Instant.now());
    }
    
    public OAuthToken exchangeCodeForTokens(String code, String clientId, String clientSecret) {
        // Validate client credentials (configurable)
        // Generate new access + refresh tokens
        // Store and return
    }
    
    public OAuthToken refreshToken(String refreshToken) {
        // Validate refresh token
        // Generate new access token
        // Optionally rotate refresh token
    }
}
```

### Token Filter

```java
@Component
public class TokenValidationFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(...) {
        String path = request.getRequestURI();
        
        // Skip auth for OAuth and admin endpoints
        if (path.startsWith("/oauth2") || path.startsWith("/admin")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            return;
        }
        
        String token = authHeader.substring(7);
        if (!tokenService.validateAccessToken(token)) {
            response.setStatus(401);
            return;
        }
        
        filterChain.doFilter(request, response);
    }
}
```

---

## 🐳 Docker Configuration

### Dockerfile

```dockerfile
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY target/monzo-simulator-*.jar app.jar

EXPOSE 8089

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### compose.yaml (updated)

```yaml
services:
  postgres:
    # ... existing config
    
  backend:
    # ... existing config
    environment:
      # Point to simulator instead of real Monzo
      MONZO_API_BASE_URL: http://monzo-simulator:8089
      
  monzo-simulator:
    build: ./monzo-simulator
    ports:
      - "8089:8089"
    environment:
      - SIMULATOR_DELAY_MS=0
      - SIMULATOR_ERROR_RATE=0
```

### compose.test.yaml (for tests)

```yaml
services:
  monzo-simulator:
    build: ./monzo-simulator
    ports:
      - "8089:8089"
```

---

## 🧪 Usage in Tests

### Integration Tests (Testcontainers)

```java
@SpringBootTest
@Testcontainers
class MonzoClientIT {
    
    @Container
    static GenericContainer<?> monzoSimulator = new GenericContainer<>("budgeteer/monzo-simulator:latest")
            .withExposedPorts(8089);
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("monzo.api.base-url", 
            () -> "http://" + monzoSimulator.getHost() + ":" + monzoSimulator.getMappedPort(8089));
    }
    
    @Autowired
    private MonzoClient monzoClient;
    
    @Test
    void shouldFetchTransactions() {
        // Given - simulator has seed data
        
        // When
        List<Transaction> transactions = monzoClient.getTransactions("acc_sim_001");
        
        // Then
        assertThat(transactions).isNotEmpty();
    }
}
```

### Acceptance Tests (REST Assured)

```java
class MonzoOAuthAcceptanceTest extends AbstractAcceptanceTest {
    
    @Test
    void shouldCompleteFullOAuthFlow() {
        // 1. Start OAuth flow
        String authUrl = given()
            .when()
                .get("/api/monzo/oauth/connect")
            .then()
                .statusCode(302)
                .extract().header("Location");
        
        // 2. Simulate user clicking "Allow" in Monzo (via simulator)
        String callbackUrl = simulateMonzoAuthorization(authUrl);
        
        // 3. Handle callback
        given()
            .when()
                .get(callbackUrl)
            .then()
                .statusCode(200)
                .body("success", equalTo(true));
        
        // 4. Verify Monzo connection stored
        given()
            .header("Authorization", "Bearer " + accessToken)
            .when()
                .get("/api/monzo/connections")
            .then()
                .statusCode(200)
                .body("data[0].account_id", equalTo("acc_sim_001"));
    }
}
```

---

## 📝 Configuration Options

### application.properties (monzo-simulator)

```properties
server.port=8089

# Simulator behaviour
simulator.delay-ms=0
simulator.error-rate=0
simulator.token-expiry-seconds=21600

# OAuth settings (validate client credentials)
simulator.oauth.client-id=test_client_id
simulator.oauth.client-secret=test_client_secret

# Logging
logging.level.dev.amf.monzosim=DEBUG
```

---

## 🚀 Implementation Order

### Phase 1: Core Structure
1. Create `monzo-simulator/pom.xml`
2. Update root `pom.xml` with new module
3. Create `MonzoSimulatorApplication.java`
4. Create basic `DataService` with seed data

### Phase 2: OAuth Endpoints
1. `OAuthController` - `/oauth2/token`
2. `TokenService` - Token generation/validation
3. Token validation filter

### Phase 3: API Endpoints
1. `AccountsController` - `/accounts`, `/balance`
2. `TransactionsController` - `/transactions`
3. `PotsController` - `/pots`

### Phase 4: Admin & Webhooks
1. `AdminController` - Reset, create data, config
2. `WebhooksController` - Register webhooks
3. `WebhookService` - Send webhooks to callbacks

### Phase 5: Docker & Integration
1. Create `Dockerfile`
2. Update `compose.yaml`
3. Test with Testcontainers
4. Document usage

---

## 📚 Reference: Monzo API Structure

Key differences from real Monzo API to keep in mind:

| Feature | Real Monzo | Simulator |
|---------|------------|-----------|
| Strong Customer Authentication | Required for some endpoints | Skipped |
| Rate limiting | 1000 req/day | Unlimited (configurable) |
| Webhook signatures | HMAC-SHA256 | Simplified or configurable |
| Account types | Multiple | Simplified to uk_retail |
| Pagination | Cursor-based | Simplified offset/limit |

See `docs/api/monzo/monzo-api.pdf` for full Monzo API reference.

---

## ❓ Open Questions

1. **Should the simulator persist data to disk?**
   - Current plan: No, in-memory only. Tests reset state.
   - Alternative: Optional H2 for longer-running manual testing.

2. **Should webhooks be asynchronous?**
   - Current plan: Yes, fire-and-forget with configurable delay.

3. **How realistic should OAuth flow be?**
   - Current plan: Simplified - no real login page, just code exchange.
   - Alternative: Serve a simple HTML form for more realistic E2E tests.

---

## 🔗 Related Documents

- `docs/api/monzo/monzo-api.pdf` - Monzo API reference
- `docs/MONZO-AUTH-FLOW.md` - OAuth flow documentation
- `docs/features/MONZO-TOKEN-PERSISTENCE.md` - Token storage design
- `.cline/tasks.md` - Task board

---

*Created: January 2026*  
*Status: Planning - Not yet implemented*
