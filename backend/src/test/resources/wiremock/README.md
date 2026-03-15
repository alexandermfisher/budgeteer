# WireMock Stub Files

This directory contains WireMock stub files for mocking third-party API responses in integration tests.

## Directory Structure

```
wiremock/
├── mappings/                    # Request/response mappings (JSON)
│   └── monzo/                   # Monzo API stubs
│       ├── oauth/               # OAuth endpoints
│       │   ├── token-exchange-success.json
│       │   ├── token-exchange-invalid-code.json
│       │   └── token-refresh-success.json
│       ├── ping/                # Health/identity endpoints
│       │   ├── whoami-authenticated.json
│       │   └── whoami-unauthenticated.json
│       └── accounts/            # Account endpoints (Phase 4)
│           └── (future stubs)
└── __files/                     # Response body files (for large responses)
    └── monzo/
        └── (future large response bodies)
```

## Usage in Tests

### Automatic Loading (Recommended)

WireMock automatically loads all JSON files from `mappings/` when using `@EnableWireMock`:

```java
@SpringBootTest
@EnableWireMock(
    @ConfigureWireMock(
        name = "monzo-api",
        filesUnderDirectory = "wiremock",
        property = "monzo.api-base-url"
    )
)
class MonzoOAuthFlowIT {
    // Stubs are automatically loaded from mappings/
}
```

### Test-Specific Overrides

For error scenarios or test-specific responses, use the Java DSL:

```java
@Test
void shouldHandleServerError() {
    stubFor(post("/oauth2/token")
        .willReturn(serverError()
            .withBody("{\"error\": \"server_error\"}")));
    
    // Test error handling...
}
```

## Stub File Format

Each stub file follows the WireMock JSON format:

```json
{
  "name": "Human-readable description",
  "request": {
    "method": "POST",
    "urlPath": "/oauth2/token",
    "bodyPatterns": [
      { "contains": "grant_type=authorization_code" }
    ]
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "access_token": "test-token",
      "expires_in": 3600
    }
  },
  "priority": 5
}
```

### Priority

Lower priority number = higher precedence. Use priorities to layer stubs:
- `1-4`: Specific error cases (e.g., `code=invalid`)
- `5`: Default happy-path responses
- `10`: Fallback/catch-all responses

## Adding New Stubs

1. Create a JSON file in the appropriate subdirectory
2. Use descriptive filenames: `{endpoint}-{scenario}.json`
3. Set appropriate priority
4. Document the stub in this README

## Monzo API Reference

See `docs/api/monzo-api.pdf` for the full Monzo API documentation.

### Common Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/oauth2/token` | POST | Exchange auth code or refresh token |
| `/ping/whoami` | GET | Verify token and get user ID |
| `/accounts` | GET | List user's accounts |
| `/balance` | GET | Get account balance |
| `/transactions` | GET | List transactions |
