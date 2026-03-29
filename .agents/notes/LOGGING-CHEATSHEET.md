# Logging Cheat Sheet

> Quick reference for logging conventions, formatting, and best practices in Budgeteer.

---

## 📋 Quick Reference

### Log Levels - When to Use

| Level | Use Case | Example |
|-------|----------|---------|
| **ERROR** | Unexpected failures, system errors | Database connection failure, external API down |
| **WARN** | Recoverable issues, invalid input | Invalid token, rate limited, deprecated API usage |
| **INFO** | Business events, milestones | User login, payment processed, session created |
| **DEBUG** | Detailed flow info (dev only) | Method entry/exit, intermediate values |
| **TRACE** | Don't use | Too verbose |

### Log Format Pattern

```java
log.info("Event description [key1={}, key2={}, key3={}]", value1, value2, value3);
```

**Always use structured key-value pairs in brackets!**

---

## ✅ Do's and Don'ts

### ✅ DO

```java
// Structured key-value logging
log.info("User authenticated [userId={}, method={}, ipAddress={}]", 
    userId, "magic-link", ipAddress);

// Include context on errors
log.error("Payment failed [paymentId={}, error={}]", paymentId, e.getMessage(), e);

// Mask sensitive data
log.info("Magic link sent [email={}]", maskEmail(email));

// Use meaningful event names
log.info("Session created [sessionId={}, expiresAt={}]", sessionId, expiresAt);
```

### ❌ DON'T

```java
// String concatenation (slow, unparseable)
log.info("User " + userId + " logged in from " + ip);

// Sensitive data exposed
log.info("Token generated: {}", accessToken);
log.debug("Request body: {}", requestBody);  // May contain passwords!

// Lazy/vague messages
log.info("something happened");
log.debug("here");
log.error("error");

// Log then throw (duplicates the error)
log.error("Database error", e);
throw new DatabaseException("Database error", e);  // GlobalExceptionHandler logs this!
```

---

## 🎯 Where to Log

### Controller Layer
```java
@RestController
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    
    @PostMapping("/api/payments")
    public ApiResponse<PaymentResponse> create(@RequestBody PaymentRequest request) {
        // DEBUG: Incoming request details (don't log sensitive fields)
        log.debug("Payment request received [amount={}, currency={}]", 
            request.getAmount(), request.getCurrency());
        
        // Let service layer handle business event logging
        return ApiResponse.success(paymentService.process(request));
    }
}
```

### Service Layer (MOST IMPORTANT)
```java
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    
    public PaymentResponse process(PaymentRequest request) {
        // INFO: Business event START
        log.info("Processing payment [amount={}, currency={}, userId={}]", 
            request.getAmount(), request.getCurrency(), getCurrentUserId());
        
        try {
            // Business logic...
            
            // INFO: Business event SUCCESS
            log.info("Payment completed [paymentId={}, amount={}, duration={}ms]", 
                payment.getId(), payment.getAmount(), duration);
            
            return response;
        } catch (InsufficientFundsException e) {
            // WARN: Expected failure (user error)
            log.warn("Payment failed - insufficient funds [userId={}, amount={}]", 
                getCurrentUserId(), request.getAmount());
            throw e;
        }
        // NOTE: Unexpected exceptions caught by GlobalExceptionHandler - don't log here!
    }
}
```

### Repository Layer
```java
// Generally DON'T log in repositories
// Use DEBUG for complex queries only if needed
log.debug("Executing custom query [params={}]", params);
```

### Exception Handler (GlobalExceptionHandler)
```java
// ERROR: Unexpected exceptions (already handled!)
// WARN: Expected API exceptions (already handled!)
// Don't duplicate in services!
```

---

## 📝 Common Logging Patterns

### Authentication Events
```java
log.info("Magic link requested [email={}]", maskEmail(email));
log.info("Magic link sent [email={}, expiresAt={}]", maskEmail(email), expiresAt);
log.info("Magic link verified [userId={}, tokenAge={}s]", userId, tokenAge);
log.warn("Magic link expired [tokenId={}]", tokenId);
log.warn("Magic link already used [tokenId={}]", tokenId);
log.info("User authenticated [userId={}, ipAddress={}, userAgent={}]", 
    userId, ipAddress, truncate(userAgent, 50));
log.info("Session created [sessionId={}, userId={}, expiresAt={}]", 
    sessionId, userId, expiresAt);
log.info("Session refreshed [sessionId={}, userId={}]", sessionId, userId);
log.info("User logged out [userId={}, sessionId={}]", userId, sessionId);
```

### Error Handling
```java
// Expected business errors - WARN
log.warn("Validation failed [field={}, reason={}]", field, reason);
log.warn("Access denied [userId={}, resource={}]", userId, resource);
log.warn("Rate limited [userId={}, endpoint={}]", userId, endpoint);

// Unexpected system errors - ERROR (with stack trace)
log.error("Database connection failed [host={}, error={}]", host, e.getMessage(), e);
log.error("External API error [service={}, status={}, error={}]", 
    service, status, e.getMessage(), e);
```

### External API Calls
```java
log.info("Calling Monzo API [endpoint={}, accountId={}]", endpoint, accountId);
log.info("Monzo API response [endpoint={}, status={}, duration={}ms]", 
    endpoint, status, duration);
log.warn("Monzo API rate limited [endpoint={}, retryAfter={}s]", endpoint, retryAfter);
log.error("Monzo API failed [endpoint={}, status={}, error={}]", 
    endpoint, status, errorMessage);
```

---

## 🔒 Sensitive Data Handling

### Never Log These

- Access tokens / Refresh tokens / JWE tokens
- Passwords / Secret keys / API keys
- Credit card numbers
- Full IP addresses in production (use last octet: `192.168.1.x`)
- Request/Response bodies (may contain sensitive data)

### Masking Helpers

```java
// Email masking
private String maskEmail(String email) {
    int atIndex = email.indexOf('@');
    if (atIndex <= 1) return "***" + email.substring(atIndex);
    return email.charAt(0) + "***" + email.substring(atIndex);
}
// j***@example.com

// Token masking (show prefix only)
private String maskToken(String token) {
    if (token == null || token.length() < 8) return "***";
    return token.substring(0, 8) + "...";
}
// eyJhbGci...

// ID masking (last 4 chars only)
private String maskId(UUID id) {
    String str = id.toString();
    return "***" + str.substring(str.length() - 4);
}
// ***4000
```

---

## 🔧 MDC (Mapped Diagnostic Context)

### Set Context Early
```java
// In RequestLoggingFilter (already done!)
MDC.put("requestId", requestId);
MDC.put("userId", userId);
MDC.put("ipAddress", ipAddress);

// All subsequent logs include these automatically!
log.info("Processing request...");  // Includes requestId, userId, ipAddress in JSON

// Clean up at end
MDC.clear();
```

### In Services (if needed)
```java
public void processWithContext(String orderId) {
    MDC.put("orderId", orderId);
    try {
        // All logs here include orderId
        log.info("Starting order processing");
        // ...
    } finally {
        MDC.remove("orderId");
    }
}
```

---

## 🌍 Environment Profiles

| Profile | Format | Level | Output |
|---------|--------|-------|--------|
| `dev` | Console (colored) | DEBUG | Console only |
| `prod` | JSON (structured) | INFO | Console + File |
| `test` | Console | WARN | Console only |

### Override Log Levels

```bash
# In .env or environment
LOGGING_LEVEL_DEV_AMF_BUDGETEER=DEBUG
LOGGING_LEVEL_ORG_SPRINGFRAMEWORK=WARN
LOGGING_LEVEL_ORG_HIBERNATE=WARN
```

---

## 📊 Actuator Endpoints

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `/actuator/health` | Public | Basic health check |
| `/actuator/info` | Public | App info |
| `/actuator/metrics` | Auth | All metrics |
| `/actuator/metrics/{name}` | Auth | Specific metric |
| `/actuator/prometheus` | Auth | Prometheus scrape |

### Quick Commands
```bash
# Health check
curl http://localhost:8080/actuator/health

# App info
curl http://localhost:8080/actuator/info

# Metrics (requires auth)
curl -H "Cookie: accessToken=..." http://localhost:8080/actuator/metrics
```

---

## 🐳 Docker/K8s Log Access

```bash
# Docker
docker logs budgeteer-backend
docker logs -f budgeteer-backend        # Follow
docker logs --tail=100 budgeteer-backend # Last 100 lines

# Kubernetes
kubectl logs deploy/budgeteer-backend
kubectl logs -f deploy/budgeteer-backend
kubectl logs --tail=100 deploy/budgeteer-backend
```

---

## 📌 Quick Snippets

### Add Logger to Class
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyService {
    private static final Logger log = LoggerFactory.getLogger(MyService.class);
    // ...
}
```

### With Lombok (alternative)
```java
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MyService {
    // log field auto-generated
}
```

### Import Statement
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
```

---

## 🚨 Common Mistakes

1. **Logging then throwing** - GlobalExceptionHandler already logs exceptions!
2. **Forgetting exception parameter** - `log.error("msg", e)` not `log.error("msg: " + e)`
3. **String concatenation** - Use `{}` placeholders, not `+`
4. **Logging PII** - Always mask emails, tokens, IPs
5. **DEBUG in production** - Check your active profile!
6. **Duplicate logging** - Service logs + Controller logs = noise
7. **Missing context** - Always include IDs (userId, sessionId, etc.)

---

*Last updated: January 2026*
