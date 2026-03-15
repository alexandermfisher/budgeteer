# Secrets Management Guide

> Best practices for managing secrets in development and production (NUC/Linux container deployment)

---

## Overview

Budgeteer needs to securely store these secrets:
- **Monzo Client ID** - Not super sensitive (publicly visible in OAuth URLs)
- **Monzo Client Secret** - 🔒 Very sensitive - never expose!
- **Database Password** - 🔒 Sensitive
- **Encryption Key** - 🔒 For encrypting OAuth tokens at rest (future)

---

## Development (Local Mac)

### Current Setup: `.env` file
```bash
# .env (gitignored)
MONZO_CLIENT_ID=oauth2client_xxx
MONZO_CLIENT_SECRET=your-secret
MONZO_REDIRECT_URI=https://xxx.ngrok-free.dev/auth/callback
```

**Pros:** Simple, easy to use
**Cons:** File sitting on disk, not encrypted

### Alternative: macOS Keychain (More Secure)
```bash
# Store secrets in Keychain
security add-generic-password -a "budgeteer" -s "monzo-client-secret" -w "your-secret"

# Retrieve in script
export MONZO_CLIENT_SECRET=$(security find-generic-password -a "budgeteer" -s "monzo-client-secret" -w)
```

---

## Production (NUC Linux + Docker)

### Option 1: Docker Secrets (Recommended for Docker Swarm)

If you're using Docker Swarm mode:

```bash
# Create secrets
echo "your-monzo-secret" | docker secret create monzo_client_secret -
echo "your-db-password" | docker secret create db_password -

# docker-compose.yml
services:
  spring:
    secrets:
      - monzo_client_secret
      - db_password
    environment:
      MONZO_CLIENT_SECRET_FILE: /run/secrets/monzo_client_secret

secrets:
  monzo_client_secret:
    external: true
  db_password:
    external: true
```

**Pros:** Encrypted at rest, never exposed in env vars
**Cons:** Requires Swarm mode

---

### Option 2: Environment File on Host (Simple)

Create a secure `.env` file on the NUC with restricted permissions:

```bash
# On NUC
sudo mkdir -p /opt/budgeteer
sudo nano /opt/budgeteer/.env
# Add your secrets...

# Restrict permissions (only root and docker can read)
sudo chmod 600 /opt/budgeteer/.env
sudo chown root:docker /opt/budgeteer/.env
```

```yaml
# docker-compose.yml
services:
  spring:
    env_file:
      - /opt/budgeteer/.env
```

**Pros:** Simple, works with basic Docker Compose
**Cons:** File on disk (but restricted permissions)

---

### Option 3: HashiCorp Vault (Enterprise-Grade)

For maximum security, use Vault:

```bash
# Install Vault on NUC
docker run -d --name vault -p 8200:8200 hashicorp/vault

# Store secrets
vault kv put secret/budgeteer \
  monzo_client_id=oauth2client_xxx \
  monzo_client_secret=your-secret

# Spring Boot integration
# Add spring-cloud-vault dependency
```

**Pros:** Centralized, audited, rotating secrets
**Cons:** Complex setup, overkill for personal project

---

### Option 4: systemd Credentials (Linux Native)

If running Spring Boot directly via systemd (no Docker):

```bash
# Create encrypted credential
sudo systemd-creds encrypt --name=monzo-secret plaintext.txt /etc/credstore/monzo-secret

# budgeteer.service
[Service]
LoadCredential=monzo-secret:/etc/credstore/monzo-secret
Environment=MONZO_CLIENT_SECRET=%d/monzo-secret
```

**Pros:** Encrypted at rest, native Linux security
**Cons:** Only works with systemd, not Docker

---

## 🎯 Recommended Approach for Your NUC

Given this is a personal project, I recommend **Option 2** (Environment File) with these enhancements:

### Setup Steps:

1. **Create secure directory on NUC:**
   ```bash
   sudo mkdir -p /opt/budgeteer/secrets
   sudo chmod 700 /opt/budgeteer/secrets
   ```

2. **Create production .env file:**
   ```bash
   sudo nano /opt/budgeteer/secrets/production.env
   ```
   ```env
   # Production secrets
   MONZO_CLIENT_ID=oauth2client_xxx
   MONZO_CLIENT_SECRET=your-actual-secret
   MONZO_REDIRECT_URI=https://your-domain.com/auth/callback
   
   # Database
   POSTGRES_PASSWORD=strong-random-password
   
   # Token encryption key (generate with: openssl rand -base64 32)
   ENCRYPTION_KEY=your-32-byte-base64-key
   ```

3. **Lock down permissions:**
   ```bash
   sudo chmod 600 /opt/budgeteer/secrets/production.env
   sudo chown root:root /opt/budgeteer/secrets/production.env
   ```

4. **Docker Compose for production:**
   ```yaml
   # /opt/budgeteer/docker-compose.prod.yml
   services:
     postgres:
       image: postgres:16-alpine
       environment:
         POSTGRES_PASSWORD_FILE: /run/secrets/db_password
       # ... or just use env_file
       
     spring:
       image: budgeteer:latest
       env_file:
         - /opt/budgeteer/secrets/production.env
       # ...
   ```

5. **Run as non-root user in container:**
   ```dockerfile
   # Dockerfile
   FROM eclipse-temurin:21-jre-alpine
   RUN addgroup -g 1000 app && adduser -u 1000 -G app -D app
   USER app
   COPY target/*.jar app.jar
   ENTRYPOINT ["java", "-jar", "app.jar"]
   ```

---

## Security Checklist

### Development
- [ ] `.env` is in `.gitignore`
- [ ] Never commit secrets to git
- [ ] Use different credentials for dev/prod

### Production
- [ ] Secrets file has restricted permissions (600)
- [ ] Database password is strong and unique
- [ ] Cloudflare Tunnel is configured (no open ports)
- [ ] Container runs as non-root user
- [ ] Regular backups of encrypted token database

---

## Token Encryption (Future)

When storing Monzo OAuth tokens in the database, encrypt them:

```java
// TokenEncryptionService.java
@Service
public class TokenEncryptionService {
    
    @Value("${encryption.key}")
    private String encryptionKey;
    
    public String encrypt(String plaintext) {
        // AES-256-GCM encryption
        SecretKey key = deriveKey(encryptionKey);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // ...
    }
    
    public String decrypt(String ciphertext) {
        // Decrypt and return
    }
}
```

The `ENCRYPTION_KEY` environment variable should be:
- Generated once: `openssl rand -base64 32`
- Stored securely (same as other secrets)
- Never changed (or you lose access to existing tokens)

---

## Quick Reference

| Environment | Method | Location |
|-------------|--------|----------|
| Dev (Mac) | `.env` file | Project root (gitignored) |
| Prod (NUC) | Secured `.env` | `/opt/budgeteer/secrets/` |
| Enterprise | HashiCorp Vault | Vault server |

---

**Last Updated**: December 2024
