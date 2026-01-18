# Testing Guide - Budgeteer

> Comprehensive testing standards, patterns, and architecture for the Budgeteer project.

---

## 📐 Testing Philosophy

We follow the **Testing Pyramid** approach, with the majority of tests being fast unit tests, supported by integration tests with real databases, and a small number of end-to-end acceptance tests.

**Core Principles:**
- **Fast feedback** - Unit tests run in milliseconds
- **Real database behaviour** - Integration tests use PostgreSQL via Testcontainers
- **Confidence in deployments** - Acceptance tests verify real API contracts
- **Maintainability** - Clear test organisation and naming conventions

---

## 🏗️ Test Pyramid

```
                    ┌─────────────────┐
                    │  Acceptance     │  REST Assured + Dev DB
                    │  (E2E)          │  ~5% of tests
                    ├─────────────────┤
                    │  Integration    │  Testcontainers (PostgreSQL)
                    │                 │  ~20% of tests
                    ├─────────────────┤
                    │  Unit           │  H2 / Mockito
                    │                 │  ~75% of tests
                    └─────────────────┘
```

| Level | Speed | Database | Purpose |
|-------|-------|----------|---------|
| Unit | ⚡ < 100ms | H2 / Mocked | Test logic in isolation |
| Integration | 🔸 1-5s | Testcontainers | Test real database behaviour |
| Acceptance | 🐢 5-30s | Dev DB | Verify E2E API contracts |

---

## 📁 Directory Structure

```
backend/src/test/
├── java/dev/amf/budgeteer/
│   ├── BudgeteerApplicationTests.java        # Context smoke test
│   │
│   ├── service/                              # Service unit tests
│   │   ├── AuthServiceTest.java              # Magic link flow
│   │   ├── CookieServiceTest.java            # Cookie operations
│   │   ├── DevAuthServiceTest.java           # Dev-only auth
│   │   ├── EmailServiceTest.java             # Email sending
│   │   ├── JweTokenServiceTest.java          # Token creation/validation
│   │   └── SessionServiceTest.java           # Session management
│   │
│   ├── api/                                  # Controller tests (@WebMvcTest)
│   │   ├── auth/
│   │   │   ├── AuthControllerTest.java
│   │   │   └── dto/AuthResponseTest.java
│   │   ├── common/
│   │   │   ├── ApiErrorTest.java
│   │   │   ├── ApiResponseTest.java
│   │   │   ├── ErrorCodeTest.java
│   │   │   └── GlobalExceptionHandlerTest.java
│   │   ├── dev/DevAuthControllerTest.java
│   │   ├── health/HealthControllerTest.java
│   │   └── monzo/MonzoOAuthControllerTest.java
│   │
│   ├── domain/                               # Entity unit tests
│   │   ├── session/
│   │   │   ├── AppRefreshTokenTest.java
│   │   │   └── MagicLinkTokenTest.java
│   │   └── user/UserTest.java
│   │
│   ├── exception/ApiExceptionTest.java       # Exception tests
│   │
│   ├── security/JweAuthenticationFilterTest.java
│   │
│   └── integration/                          # Integration tests
│       ├── AbstractPostgresIntegrationTest.java  # Testcontainers base
│       ├── FlywayMigrationIT.java                # Migration verification
│       ├── TestDataFactory.java                  # Test entity factory
│       └── repository/                           # Repository ITs (H2)
│           ├── AppRefreshTokenRepositoryIT.java
│           ├── MagicLinkTokenRepositoryIT.java
│           └── UserRepositoryIT.java
│
└── resources/
    ├── application-test.properties           # H2/unit test config
    └── application-integration-test.properties  # Testcontainers config
```

### Naming Conventions

| Type | Pattern | Example |
|------|---------|---------|
| Unit Test | `{ClassName}Test.java` | `JweTokenServiceTest.java` |
| Integration Test | `{Feature}IT.java` | `AuthFlowIT.java` |
| Acceptance Test | `{Feature}AcceptanceTest.java` | `AuthAcceptanceTest.java` |
| Test Method | `should{ExpectedBehaviour}` | `shouldCreateValidToken()` |

---

## 🔬 Unit Tests

### Purpose
Test individual classes in isolation with mocked dependencies. These form the bulk of the test suite and provide fast feedback.

### Configuration
- **Database:** H2 in-memory (or no database - pure mocking)
- **Spring Context:** Not loaded (for service tests) or `@WebMvcTest` (for controllers)
- **Dependencies:** Mocked with Mockito

### Patterns Used

#### 1. Pure Unit Tests (No Spring Context)
```java
@DisplayName("JweTokenService")
class JweTokenServiceTest {

    private JweTokenService jweTokenService;

    @BeforeEach
    void setUp() {
        JweProperties props = new JweProperties();
        props.setSecretKey(TEST_SECRET_KEY);
        props.setAccessTokenExpiry(Duration.ofMinutes(15));
        
        jweTokenService = new JweTokenService(props);
        jweTokenService.init();
    }

    @Nested
    @DisplayName("createAccessToken")
    class CreateAccessToken {
        @Test
        @DisplayName("should create a valid JWE token for a user")
        void shouldCreateValidToken() {
            // Given
            User user = createTestUser();

            // When
            String token = jweTokenService.createAccessToken(user);

            // Then
            assertThat(token).isNotNull();
            assertThat(token.split("\\.")).hasSize(5); // JWE format
        }
    }
}
```

#### 2. Mockito-Based Unit Tests
```java
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionService")
class SessionServiceTest {

    @Mock
    private AppRefreshTokenRepository refreshTokenRepository;

    @Mock
    private JweTokenService jweTokenService;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        JweProperties props = new JweProperties();
        props.setRefreshTokenExpiry(Duration.ofDays(7));
        
        sessionService = new SessionService(refreshTokenRepository, jweTokenService, props);
    }

    @Test
    @DisplayName("should create access and refresh tokens")
    void shouldCreateBothTokens() {
        // Given
        User user = createTestUser();
        when(jweTokenService.createAccessToken(user)).thenReturn("mocked-access-token");
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        SessionService.SessionTokens tokens = sessionService.createSession(user, "Agent", "127.0.0.1");

        // Then
        assertThat(tokens.accessToken()).isEqualTo("mocked-access-token");
        assertThat(tokens.refreshToken()).isNotNull();
        verify(refreshTokenRepository).save(any(AppRefreshToken.class));
    }
}
```

#### 3. Controller Unit Tests
```java
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private SessionService sessionService;

    @MockBean
    private CookieService cookieService;

    @Test
    void shouldReturnSuccessOnLogin() throws Exception {
        // Given
        doNothing().when(authService).requestMagicLink(anyString());

        // When/Then
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"test@example.com\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
```

### Nested Test Classes
Group related tests by method being tested:

```java
@DisplayName("CookieService")
class CookieServiceTest {

    @Nested
    @DisplayName("setAccessTokenCookie")
    class SetAccessTokenCookie {
        @Test void shouldSetCookieWithCorrectAttributes() { }
        @Test void shouldSetMaxAgeFromConfig() { }
        @Test void shouldNotSetSecureFlagInDevMode() { }
    }

    @Nested
    @DisplayName("extractAccessToken")
    class ExtractAccessToken {
        @Test void shouldExtractTokenFromCookie() { }
        @Test void shouldReturnEmptyForMissingCookie() { }
    }
}
```

---

## 🧪 Integration Tests

### Purpose
Test real database behaviour using PostgreSQL via Testcontainers. Verifies that:
- Flyway migrations run correctly
- JPA mappings work with PostgreSQL
- Repository queries return expected results
- Transactions behave correctly

### Configuration
- **Database:** PostgreSQL via Testcontainers
- **Spring Context:** Full or sliced (`@DataJpaTest`)
- **Flyway:** Enabled (tests real migrations)

### Base Class Setup
```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("budgeteer_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
```

### Repository Integration Test
```java
class UserRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldFindUserByEmail() {
        // Given
        User user = new User("test@example.com");
        userRepository.save(user);

        // When
        Optional<User> found = userRepository.findByEmail("test@example.com");

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void shouldSupportCaseInsensitiveEmailLookup() {
        // Given
        User user = new User("Test@Example.com");
        userRepository.save(user);

        // When
        Optional<User> found = userRepository.findByEmailIgnoreCase("TEST@EXAMPLE.COM");

        // Then
        assertThat(found).isPresent();
    }
}
```

### Service Integration Test
```java
class AuthFlowIT extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MagicLinkTokenRepository magicLinkTokenRepository;

    @Test
    void shouldCreateUserAndMagicLinkOnFirstLogin() {
        // Given
        String email = "newuser@example.com";

        // When
        authService.requestMagicLink(email);

        // Then
        assertThat(userRepository.findByEmail(email)).isPresent();
        assertThat(magicLinkTokenRepository.findAll()).hasSize(1);
    }
}
```

### When to Use Testcontainers vs H2

| Scenario | Use |
|----------|-----|
| Testing service logic | H2 / Mocking |
| Simple CRUD repository tests | H2 (`@DataJpaTest`) |
| PostgreSQL-specific features (JSONB, arrays) | Testcontainers |
| Testing Flyway migrations | Testcontainers |
| Complex queries with joins | Testcontainers |
| Performance testing | Testcontainers |

---

## 🎯 Acceptance Tests

### Purpose
End-to-end tests that verify the API behaves correctly from an external client's perspective. Tests run against a real running server with a real database.

### Configuration
- **Server:** Running locally or in test environment
- **Database:** Dev database (seeded with test data)
- **HTTP Client:** REST Assured

### Base Class Setup
```java
public abstract class AbstractAcceptanceTest {

    protected static final String BASE_URL = System.getProperty("test.base-url", "http://localhost:8080");

    @BeforeAll
    static void setupRestAssured() {
        RestAssured.baseURI = BASE_URL;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    protected RequestSpecification givenRequest() {
        return given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }
}
```

### Acceptance Test Example
```java
class AuthAcceptanceTest extends AbstractAcceptanceTest {

    @Test
    @DisplayName("Full authentication flow - login to logout")
    void shouldCompleteFullAuthFlow() {
        String email = "acceptance-test@example.com";

        // 1. Request magic link
        givenRequest()
            .body(Map.of("email", email))
        .when()
            .post("/api/auth/login")
        .then()
            .statusCode(200)
            .body("success", equalTo(true))
            .body("data.message", containsString("magic link"));

        // 2. Get magic link token from database or console
        String magicLinkToken = getMagicLinkTokenForEmail(email);

        // 3. Verify magic link
        Response verifyResponse = givenRequest()
        .when()
            .get("/api/auth/verify?token=" + magicLinkToken)
        .then()
            .statusCode(200)
            .body("success", equalTo(true))
            .extract().response();

        // Extract cookies or tokens from response
        String accessToken = verifyResponse.jsonPath().getString("data.accessToken");

        // 4. Access protected endpoint
        givenRequest()
            .header("Authorization", "Bearer " + accessToken)
        .when()
            .get("/api/auth/me")
        .then()
            .statusCode(200)
            .body("data.email", equalTo(email));

        // 5. Logout
        givenRequest()
            .header("Authorization", "Bearer " + accessToken)
        .when()
            .post("/api/auth/logout")
        .then()
            .statusCode(200)
            .body("success", equalTo(true));
    }

    @Test
    @DisplayName("Should reject invalid magic link token")
    void shouldRejectInvalidMagicLinkToken() {
        givenRequest()
        .when()
            .get("/api/auth/verify?token=invalid-token")
        .then()
            .statusCode(401)
            .body("success", equalTo(false))
            .body("error.code", equalTo("INVALID_TOKEN"));
    }
}
```

### Running Acceptance Tests

```bash
# Start the application first
./scripts/dev.sh

# In another terminal, run acceptance tests
cd backend
mvn test -Dtest="*AcceptanceTest" -Dtest.base-url=http://localhost:8080
```

---

## ⚙️ Test Configuration

### application-test.properties
```properties
# =============================================================================
# TEST PROFILE CONFIGURATION
# =============================================================================

# Database (H2 for unit tests)
spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop

# Disable Flyway for unit tests (Hibernate creates schema)
spring.flyway.enabled=false

# Disable Docker Compose
spring.docker.compose.enabled=false

# JWE Configuration (test key)
app.jwe.secret-key=dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtMzItYnl0ZXM=
app.jwe.access-token-expiry=15m
app.jwe.refresh-token-expiry=1d
app.jwe.magic-link-expiry=15m

# App Configuration
app.base-url=http://localhost:8080
app.email.enabled=false
app.cookies.secure=false

# Minimal logging
logging.level.root=WARN
logging.level.dev.amf.budgeteer=DEBUG
spring.jpa.show-sql=false
```

---

## 📦 Dependencies

### pom.xml Test Dependencies
```xml
<!-- Testing -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>

<!-- H2 for unit tests -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>

<!-- Testcontainers for integration tests -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>

<!-- REST Assured for acceptance tests -->
<dependency>
    <groupId>io.rest-assured</groupId>
    <artifactId>rest-assured</artifactId>
    <scope>test</scope>
</dependency>
```

---

## 🎨 Assertion Style Guide

We use **AssertJ** for all assertions. It provides:
- Fluent, readable syntax
- Rich assertion methods
- Better error messages

### Examples
```java
// Basic assertions
assertThat(token).isNotNull();
assertThat(token).isNotEmpty();
assertThat(token).hasSize(64);

// Collection assertions
assertThat(users).hasSize(3);
assertThat(users).contains(user1, user2);
assertThat(users).extracting("email").contains("test@example.com");

// Optional assertions
assertThat(result).isPresent();
assertThat(result).isEmpty();
assertThat(result.get()).isEqualTo(expected);

// Exception assertions
assertThatThrownBy(() -> service.doSomething())
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessageContaining("invalid");

// Time assertions
assertThat(expiresAt).isAfter(Instant.now());
assertThat(duration).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(16));
```

### Anti-Patterns to Avoid
```java
// ❌ Don't use JUnit assertions
assertTrue(result.isPresent());
assertEquals(expected, actual);

// ✅ Use AssertJ instead
assertThat(result).isPresent();
assertThat(actual).isEqualTo(expected);
```

---

## 🏃 Running Tests

### All Tests
```bash
cd backend
mvn test
```

### Specific Test Class
```bash
mvn test -Dtest=JweTokenServiceTest
```

### Tests by Pattern
```bash
# Unit tests only
mvn test -Dtest="*Test"

# Integration tests only
mvn test -Dtest="*IT"

# Acceptance tests only (requires running server)
mvn test -Dtest="*AcceptanceTest" -Dtest.base-url=http://localhost:8080
```

### Skip Tests
```bash
mvn clean install -DskipTests
```

### Run with Coverage
```bash
mvn test jacoco:report
# Report at: target/site/jacoco/index.html
```

---

## 📊 Test Coverage Guidelines

### What to Test

| Component | What to Test | Coverage Goal |
|-----------|--------------|---------------|
| **Services** | Business logic, edge cases, error handling | 80%+ |
| **Controllers** | Request/response mapping, validation | 70%+ |
| **Repositories** | Custom queries, complex operations | 60%+ |
| **Security** | Authentication, authorisation rules | 90%+ |
| **Configuration** | Property binding, bean creation | 50%+ |

### What NOT to Test

- Lombok-generated code (getters, setters, builders)
- Spring Framework internal behaviour
- Third-party library internals
- Simple pass-through methods

### When Adding New Features

1. **Write unit tests** for service methods
2. **Write controller tests** for new endpoints
3. **Add integration test** if feature involves database
4. **Update acceptance tests** if user-facing behaviour changes

---

## 📋 Current Test Status

**Total: 321 tests passing** ✅

### Unit Tests (252)

| Category | Test Class | Tests | Notes |
|----------|------------|-------|-------|
| **Services** | `AuthServiceTest` | 19 | Magic link flow, verification |
|            | `SessionServiceTest` | 19 | Session management, refresh, revocation |
|            | `JweTokenServiceTest` | 14 | Token creation, validation, expiry |
|            | `CookieServiceTest` | 20 | Cookie operations |
|            | `EmailServiceTest` | 9 | Mock JavaMailSender |
|            | `DevAuthServiceTest` | 12 | Dev-only auth service |
| **Controllers** | `AuthControllerTest` | 19 | `@WebMvcTest` |
|                | `HealthControllerTest` | 5 | Health endpoints |
|                | `DevAuthControllerTest` | 9 | Dev auth endpoints |
|                | `MonzoOAuthControllerTest` | 5 | OAuth flow |
| **Security** | `JweAuthenticationFilterTest` | 7 | Filter chain |
| **Exception** | `ApiExceptionTest` | 6 | Custom exceptions |
|              | `GlobalExceptionHandlerTest` | 9 | Exception handling |
| **Common/API** | `ApiResponseTest` | 3 | Response wrapper |
|               | `ApiErrorTest` | 4 | Error wrapper |
|               | `ErrorCodeTest` | 64 | All error codes |
|               | `AuthResponseTest` | 4 | DTOs |
| **Domain** | `UserTest` | 3 | Entity tests |
|           | `MagicLinkTokenTest` | 10 | Token entity logic |
|           | `AppRefreshTokenTest` | 11 | Refresh token entity |
| **Other** | `BudgeteerApplicationTests` | 1 | Context loading |

### Repository Integration Tests - H2 (27)

| Test Class | Tests | Notes |
|------------|-------|-------|
| `UserRepositoryIT` | 9 | `@DataJpaTest` with H2 |
| `MagicLinkTokenRepositoryIT` | 9 | Custom queries, cleanup |
| `AppRefreshTokenRepositoryIT` | 9 | Active tokens, revocation |

### Flyway Migration Integration Tests - PostgreSQL (6)

| Test Class | Tests | Notes |
|------------|-------|-------|
| `FlywayMigrationIT` | 6 | Testcontainers + Flyway |

*Verifies: table creation, columns, foreign keys, unique constraints*

### Full Integration Tests - PostgreSQL Testcontainers (35)

| Test Class | Tests | Notes |
|------------|-------|-------|
| `AuthFlowIT` | 19 | Complete magic link flow, token validation, refresh, logout |
| `SessionManagementIT` | 16 | Single-session policy, token rotation, revocation, edge cases |

*Uses singleton Testcontainers PostgreSQL container shared across all test classes*

---

## 🔜 Remaining Work

### Integration Tests (Future)

| Test | Priority | Description |
|------|----------|-------------|
| Config tests | Medium | SecurityConfig, Properties classes |

### Acceptance Tests (Future Phase)

| Test | Approach | Description |
|------|----------|-------------|
| `AuthAcceptanceTest` | REST Assured + Dev DB | Full E2E auth flow |
| `MonzoOAuthFlowIT` | REST Assured + Monzo Simulator | OAuth integration |

**Plan:** Use REST Assured against a deployed dev database, with a Monzo simulator service for testing OAuth flows

---

## 🔗 Related Documentation

- [Architecture Overview](./ARCHITECTURE.md)
- [Security Architecture](./SECURITY-ARCHITECTURE.md)
- [Setup Guide](./SETUP.md)
- [Contributing Guidelines](../CONTRIBUTING.md)

---

*Last Updated: January 2026*
