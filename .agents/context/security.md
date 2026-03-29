# Security

> Full docs: `docs/architecture/SECURITY-ARCHITECTURE.md` · `docs/features/ENCRYPTION.md` · `docs/features/LOGGING.md`

## Auth Model

1. User submits email → magic link token generated (SHA-256 hash stored, plain token emailed)
2. User clicks link → token verified against hash, marked `used_at` (replay prevention), expires in 15m
3. On verify: JWE access token (15m) + refresh token (7d) issued as **HttpOnly cookies**
4. Single-session-per-login (multi-device supported — each login gets its own refresh token row)
5. New login does NOT revoke existing sessions

## Token Types

| Token | Storage | Lifetime | Purpose |
|-------|---------|----------|---------|
| Magic link | `magic_link_tokens.token_hash` (SHA-256) | 15 min | Passwordless login |
| JWE access token | HttpOnly cookie (stateless JWT) | 15 min | API auth |
| App refresh token | `app_refresh_tokens.token_hash` (SHA-256) | 7 days | Rotate access token |
| Monzo access token | `monzo_connections.access_token_enc` (AES-256-GCM) | ~6 hours | Monzo API calls |
| Monzo refresh token | `monzo_connections.refresh_token_enc` (AES-256-GCM) | Long-lived | Refresh Monzo access |
| OAuth state | `oauth_states.state` (plain, short-lived) | 10 min | CSRF for Monzo OAuth |

## Encryption

- **JWE tokens**: `JWE_SECRET_KEY` env var (32-byte base64)
- **Monzo tokens at rest**: `MONZO_ENCRYPTION_KEY` env var (32-byte base64), AES-256-GCM
- Plain tokens are **never stored** — only hashes or ciphertext

## What NEVER to Log

- Any token value (magic link, access, refresh, Monzo)
- Passwords or secrets
- Full email addresses in production (truncate or mask)
- IP addresses beyond INFO level
- Raw request/response bodies containing auth headers

`LogSanitizer` (`util/LogSanitizer.java`) handles request logging redaction. Always use it for incoming request logging.

## Logging Patterns

```java
// Good
log.info("Magic link requested [userId={}, ip={}]", userId, maskedIp);
log.warn("Token expired [tokenId={}, expiredAt={}]", id, expiredAt);

// Bad — never do this
log.info("Token: {}", rawToken);
log.debug("Request body: {}", requestBody); // may contain credentials
```

## Environment Variables (secrets — never commit)

```
JWE_SECRET_KEY           # Session token encryption
MONZO_ENCRYPTION_KEY     # Monzo OAuth token encryption at rest
MONZO_CLIENT_SECRET      # Monzo Developer Portal secret
MAIL_PASSWORD            # Resend API key
DB_PASSWORD              # Postgres password
```

All secrets live in `.env` (gitignored). Never hardcode or log these.

## CI Security

- CodeQL runs weekly + on every push/PR (SQL injection, XSS, path traversal)
- Custom CodeQL config at `.github/codeql/codeql-config.yml` excludes known-safe log sanitization patterns
- Dependabot updates Maven, GitHub Actions, and Docker Compose weekly
