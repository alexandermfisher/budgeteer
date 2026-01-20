# Logging Strategy

This document describes the logging architecture for the Budgeteer application.

## Overview

The Budgeteer backend uses a **production-ready logging system** with:

- **Structured logging** with JSON format in production
- **Human-readable** console logging in development
- **Centralized log aggregation** ready (JSON logs can be shipped to ELK, Splunk, etc.)
- **Request tracing** via correlation IDs
- **Performance metrics** via Spring Boot Actuator

## Dependencies

### Logback (SLF4J Implementation)
Spring Boot uses Logback by default. No additional logging framework needed.

### Logstash Logback Encoder
```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.0</version>
</dependency>
```

Provides JSON structured logging for production environments.

### Spring Boot Actuator
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

Provides metrics, health checks, and monitoring endpoints.

## Log Formats

### Development (Console - Human Readable)
```
2026-01-20 20:00:00.123  INFO --- [nio-8080-exec-1] d.a.budgeteer.service.AuthService        : User authenticated successfully via magic link [userId=123e4567-e89b-12d3-a456-426614174000, ipAddress=192.168.1.1, userAgent=Mozilla/5.0...]
```

### Production (JSON - Machine Parseable)
```json
{
  "@timestamp": "2026-01-20T20:00:00.123Z",
  "level": "INFO",
  "thread": "http-nio-8080-exec-1",
  "logger": "dev.amf.budgeteer.service.AuthService",
  "message": "User authenticated successfully via magic link [userId=123e4567-e89b-12d3-a456-426614174000, ipAddress=192.168.1.1, userAgent=Mozilla/5.0...]",
  "context": "production",
  "requestId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "ipAddress": "192.168.1.1",
  "application": "budgeteer",
  "version": "0.0.1-SNAPSHOT"
}
```

## Configuration

### Files

- `backend/src/main/resources/logback-spring.xml` - Main logging configuration
- `backend/src/main/resources/application.properties` - Spring Boot logging settings
- `backend/src/main/resources/application-dev.properties` - Development overrides
- `backend/src/main/resources/application-prod.properties` - Production overrides

### Profile-Specific Logging

#### Development Profile
- **Format**: Console (human-readable with colors)
- **Level**: DEBUG for application code, INFO for frameworks
- **Output**: Console only
- **File logging**: Disabled

#### Production Profile
- **Format**: JSON (structured, machine-parseable)
- **Level**: INFO for everything
- **Output**: Console (captured by Docker/K8s) + File
- **File location**: `/var/log/budgeteer/budgeteer.log`
- **Rolling policy**: Daily rotation, keep 30 days

### Environment Variables

```bash
# Override log file location
LOG_FILE=/custom/path/budgeteer.log
LOG_PATH=/custom/path

# Change log levels
LOGGING_LEVEL_ROOT=INFO
LOGGING_LEVEL_DEV_AMF_BUDGETEER=DEBUG
LOGGING_LEVEL_ORG_SPRINGFRAMEWORK=WARN
```

## Log Levels

### Application Code (`dev.amf.budgeteer`)
- **TRACE**: Not used (too verbose)
- **DEBUG**: Detailed flow information for debugging
- **INFO**: Important business events (logins, transactions, etc.)
- **WARN**: Recoverable errors, invalid input, failed validations
- **ERROR**: Unexpected exceptions, system failures

### Framework Code (`org.springframework`, `org.hibernate`, etc.)
- **Production**: INFO level
- **Development**: DEBUG level for Spring, INFO for others

## Structured Logging Best Practices

### Use Structured Key-Value Pairs
```java
// ✅ GOOD - Structured and parseable
log.info("User authenticated successfully [userId={}, ipAddress={}, method={}]", 
    userId, ipAddress, "magic-link");

// ❌ BAD - Unstructured, hard to parse
log.info("User " + userId + " authenticated from " + ipAddress);
```

### Mask Sensitive Data
```java
// ✅ GOOD - Email masked
log.info("Magic link sent [email={}]", maskEmail(email)); // j***@example.com

// ❌ BAD - Full email logged (PII leak)
log.info("Magic link sent to {}", email); // john.doe@example.com
```

### Use MDC for Request Context
```java
// Add to MDC early in request lifecycle
MDC.put("requestId", requestId);
MDC.put("userId", userId);

// All subsequent logs automatically include these fields
log.info("Processing payment"); // Includes requestId and userId

// Clean up at end of request
MDC.clear();
```

## Request Logging Filter

The `RequestLoggingFilter` automatically logs all HTTP requests with:

- **Request ID** (generated or from `X-Request-ID` header)
- **HTTP method** and **URI**
- **Response status** and **duration**
- **IP address** (from `X-Forwarded-For` or `X-Real-IP`)
- **User agent** (truncated)

Example output:
```
INFO  Incoming request: POST /api/auth/login  [requestId=a1b2c3d4, userAgent=Mozilla/5.0...]
INFO  Request completed: POST /api/auth/login -> 200 in 45ms [requestId=a1b2c3d4]
```

Health check endpoints (`/actuator/health`, `/api/health/live`) are excluded from logging to reduce noise.

## Monitoring Endpoints (Actuator)

### Available Endpoints

#### `/actuator/health` (Public)
Basic health check - returns `UP` if app is running.

```bash
curl http://localhost:8080/actuator/health
```

Response:
```json
{
  "status": "UP"
}
```

#### `/actuator/info` (Public)
Application information.

```bash
curl http://localhost:8080/actuator/info
```

Response:
```json
{
  "app": {
    "name": "Budgeteer",
    "description": "Personal budgeting application with Monzo integration",
    "version": "0.0.1-SNAPSHOT",
    "profiles": ["dev"]
  }
}
```

#### `/actuator/metrics` (Protected - Requires Authentication)
Available metrics endpoints.

```bash
curl -H "Cookie: accessToken=..." http://localhost:8080/actuator/metrics
```

#### `/actuator/metrics/{metric}` (Protected)
Specific metric details (e.g., `http.server.requests`, `jvm.memory.used`).

#### `/actuator/prometheus` (Protected)
Prometheus-formatted metrics for scraping.

### Security

- **Public**: `/actuator/health`, `/actuator/info`
- **Authenticated**: `/actuator/metrics/**`, `/actuator/prometheus`

## Log Aggregation

### JSON Logs in Production

Production logs are output as JSON, making them easy to ship to log aggregation services:

- **ELK Stack** (Elasticsearch, Logstash, Kibana)
- **Splunk**
- **Datadog**
- **AWS CloudWatch**
- **GCP Cloud Logging**

### Docker Setup

Logs are written to stdout/stderr, which Docker captures:

```bash
# View logs
docker logs budgeteer-backend

# Follow logs
docker logs -f budgeteer-backend

# Last 100 lines
docker logs --tail=100 budgeteer-backend
```

### Kubernetes Setup

```yaml
# In deployment.yaml
spec:
  containers:
  - name: budgeteer-backend
    env:
    - name: SPRING_PROFILES_ACTIVE
      value: "prod"
    - name: LOG_FILE
      value: "/var/log/budgeteer/budgeteer.log"
    volumeMounts:
    - name: logs
      mountPath: /var/log/budgeteer
  volumes:
  - name: logs
    emptyDir: {}
```

## Performance Considerations

### Async Logging (Future Enhancement)

For high-throughput scenarios, consider async logging:

```xml
<!-- In logback-spring.xml -->
<appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
    <queueSize>512</queueSize>
    <discardingThreshold>0</discardingThreshold>
    <appender-ref ref="JSON" />
</appender>
```

### Log Sampling (Future Enhancement)

For very verbose endpoints, implement log sampling:

```java
if (random.nextDouble() < 0.1) { // Sample 10% of requests
    log.debug("Detailed debug info...");
}
```

## Troubleshooting

### No Logs Appearing

1. Check active profile: `echo $SPRING_PROFILES_ACTIVE`
2. Check log level: Add `logging.level.root=DEBUG` to application.properties
3. Check console output in Docker: `docker logs budgeteer-backend`

### Logs Too Verbose

1. Increase log level: `logging.level.dev.amf.budgeteer=INFO`
2. Reduce framework logging: `logging.level.org.springframework=WARN`
3. Exclude noisy classes: `logging.level.org.hibernate.SQL=WARN`

### JSON Logs in Development

If you accidentally see JSON in development:

1. Check profile: Should be `dev`, not `prod`
2. Restart application: `./scripts/dev.sh`

### Missing Context Fields

If `requestId` or `userId` are missing from logs:

1. Ensure `RequestLoggingFilter` is active (it's a `@Component`)
2. Check MDC is being populated correctly
3. Verify `MDC.clear()` is called in `finally` blocks

## Examples

### Service Layer Logging

```java
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    
    public void processPayment(Payment payment) {
        log.info("Processing payment [paymentId={}, amount={}, currency={}]", 
                payment.getId(), payment.getAmount(), payment.getCurrency());
        
        try {
            // Process payment
            log.info("Payment successful [paymentId={}, duration={}ms]", 
                    payment.getId(), duration);
        } catch (Exception e) {
            log.error("Payment failed [paymentId={}, error={}]", 
                    payment.getId(), e.getMessage(), e);
            throw new PaymentException("Payment processing failed", e);
        }
    }
}
```

### Controller Layer Logging

```java
@RestController
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    
    @PostMapping("/api/payments")
    public ResponseEntity<PaymentResponse> createPayment(@RequestBody PaymentRequest request) {
        log.debug("Payment request received [amount={}, method={}]", 
                request.getAmount(), request.getMethod());
        
        // Process...
        
        return ResponseEntity.ok(response);
    }
}
```

## Migration from Custom Health Endpoint

The custom `/api/health` endpoints are **deprecated** and will be removed in a future version.

### Migration Path

Replace:
```bash
# Old
curl http://localhost:8080/api/health
curl http://localhost:8080/api/health/ready
curl http://localhost:8080/api/health/live
```

With:
```bash
# New
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health?show-details=always
curl http://localhost:8080/actuator/health/liveness
```

## Further Reading

- [Spring Boot Logging Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.logging)
- [Logback Manual](https://logback.qos.ch/manual/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Logstash Logback Encoder](https://github.com/logfellow/logstash-logback-encoder)
