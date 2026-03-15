# Encryption Design Documentation

> **Feature:** Monzo Token Encryption  
> **Status:** Implemented  
> **Last Updated:** January 2026

## Overview

This document explains the encryption implementation used to securely store Monzo OAuth tokens (access tokens and refresh tokens) in the database.

## Why Encrypt Tokens?

Monzo OAuth tokens are sensitive credentials that provide access to a user's bank account. If the database is compromised, unencrypted tokens would allow an attacker to:

- View account balances and transactions
- Transfer money from user accounts
- Access personal financial data

**Encryption ensures tokens are useless without the encryption key**, even if the database is breached.

---

## Algorithm: AES-256-GCM

We use **AES-256-GCM** (Advanced Encryption Standard in Galois/Counter Mode) for token encryption.

### Why AES-256-GCM?

| Property | Value | Why It Matters |
|----------|-------|----------------|
| **Algorithm** | AES (Advanced Encryption Standard) | Industry standard, NIST approved, hardware-accelerated |
| **Key Size** | 256 bits (32 bytes) | Maximum AES key strength, resistant to brute force |
| **Mode** | GCM (Galois/Counter Mode) | Authenticated encryption - detects tampering |
| **IV Size** | 96 bits (12 bytes) | NIST recommended for GCM |
| **Auth Tag** | 128 bits (16 bytes) | Strong integrity protection |

### What is Authenticated Encryption?

GCM provides **AEAD** (Authenticated Encryption with Associated Data):

```
┌──────────────────────────────────────────────────────────┐
│  Standard Encryption (AES-CBC, etc.)                     │
│  - Only provides CONFIDENTIALITY                         │
│  - Attacker can't READ the data                          │
│  - Attacker CAN MODIFY data without detection!           │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  Authenticated Encryption (AES-GCM)                      │
│  - Provides CONFIDENTIALITY + INTEGRITY                  │
│  - Attacker can't READ the data                          │
│  - Attacker can't MODIFY data without detection!         │
│  - Auth tag validates data hasn't been tampered with     │
└──────────────────────────────────────────────────────────┘
```

---

## How It Works

### Encryption Process

```
                    ┌─────────────────┐
                    │    Plaintext    │
                    │ "access_token"  │
                    └────────┬────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│  Step 1: Generate Random IV (12 bytes)                  │
│  ┌───────────────────────────────────────────┐          │
│  │ SecureRandom.nextBytes(iv)                │          │
│  │ iv = [random 12 bytes]                    │          │
│  └───────────────────────────────────────────┘          │
│                                                         │
│  Why? Each encryption needs a UNIQUE IV.                │
│  Reusing an IV with the same key breaks GCM security.   │
└─────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│  Step 2: Configure Cipher                               │
│  ┌───────────────────────────────────────────┐          │
│  │ Cipher cipher = Cipher.getInstance(       │          │
│  │     "AES/GCM/NoPadding"                   │          │
│  │ );                                        │          │
│  │ GCMParameterSpec spec = new GCMParameterSpec(        │
│  │     128,  // Auth tag size (bits)         │          │
│  │     iv    // The random IV                │          │
│  │ );                                        │          │
│  │ cipher.init(ENCRYPT_MODE, secretKey, spec);         │
│  └───────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│  Step 3: Encrypt                                        │
│  ┌───────────────────────────────────────────┐          │
│  │ ciphertextWithTag = cipher.doFinal(       │          │
│  │     plaintext.getBytes()                  │          │
│  │ );                                        │          │
│  └───────────────────────────────────────────┘          │
│                                                         │
│  Output: [encrypted bytes] + [16-byte auth tag]         │
└─────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│  Step 4: Package for Storage                            │
│  ┌───────────────────────────────────────────┐          │
│  │ combined = [IV (12)] + [ciphertext+tag]   │          │
│  │ result = Base64.encode(combined)          │          │
│  └───────────────────────────────────────────┘          │
│                                                         │
│  Why prepend IV? Decryption needs the exact same IV.    │
│  Storing them together is the standard approach.        │
└─────────────────────────────────────────────────────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │  Encrypted      │
                    │  (Base64 string)│
                    └─────────────────┘
```

### Decryption Process

```
                    ┌─────────────────┐
                    │  Encrypted      │
                    │  (Base64 string)│
                    └────────┬────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│  Step 1: Base64 Decode                                  │
│  ┌───────────────────────────────────────────┐          │
│  │ bytes = Base64.decode(encryptedString)    │          │
│  └───────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│  Step 2: Extract IV and Ciphertext                      │
│  ┌───────────────────────────────────────────┐          │
│  │ iv = bytes[0:12]           // First 12    │          │
│  │ ciphertextWithTag = bytes[12:]  // Rest   │          │
│  └───────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│  Step 3: Configure Cipher (same as encryption)          │
│  ┌───────────────────────────────────────────┐          │
│  │ cipher.init(DECRYPT_MODE, secretKey,      │          │
│  │     new GCMParameterSpec(128, iv)         │          │
│  │ );                                        │          │
│  └───────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│  Step 4: Decrypt & Verify                               │
│  ┌───────────────────────────────────────────┐          │
│  │ plaintext = cipher.doFinal(ciphertextWithTag)        │
│  └───────────────────────────────────────────┘          │
│                                                         │
│  GCM automatically verifies the auth tag.               │
│  If tampered: throws AEADBadTagException                │
│  If wrong key: throws AEADBadTagException               │
└─────────────────────────────────────────────────────────┘
                             │
                  ┌──────────┴──────────┐
                  │                     │
           Success │               Failure │
                  ▼                     ▼
         ┌─────────────┐       ┌─────────────────┐
         │  Plaintext  │       │ EncryptionException │
         │ "access_token" │    │ "tampered/wrong key"│
         └─────────────┘       └─────────────────┘
```

---

## Key Components Explained

### SecureRandom

```java
SecureRandom secureRandom = new SecureRandom();
secureRandom.nextBytes(iv);
```

**What it does:** Generates cryptographically secure random bytes.

**Why use it?**
- `java.util.Random` is predictable (uses system time as seed)
- `SecureRandom` uses OS-level entropy sources (e.g., `/dev/urandom`)
- Essential for IV generation - predictable IVs break GCM security

### Cipher

```java
Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
```

**What it does:** Creates an encryption/decryption cipher instance.

**The transformation string explained:**
- `AES` - The encryption algorithm (Advanced Encryption Standard)
- `GCM` - The mode of operation (Galois/Counter Mode)
- `NoPadding` - GCM doesn't need padding (it's a stream cipher mode)

### GCMParameterSpec

```java
GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
```

**What it does:** Specifies GCM parameters - auth tag size and IV.

**Parameters:**
- `128` - Authentication tag length in bits (16 bytes)
- `iv` - The initialization vector (must be unique per encryption)

**Why 128-bit auth tag?** Maximum strength, prevents forgery attacks.

### SecretKeySpec

```java
SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
```

**What it does:** Wraps raw key bytes into a usable SecretKey object.

**Why?** Java's Cipher API requires a `SecretKey` object, not raw bytes.

---

## Storage Format

Encrypted data is stored as a Base64 string with this internal structure:

```
┌────────────────┬─────────────────────────────────────────────┐
│    Bytes       │    Content                                  │
├────────────────┼─────────────────────────────────────────────┤
│    0-11        │    IV (Initialization Vector) - 12 bytes    │
│    12-N        │    Ciphertext + Auth Tag                    │
│    (last 16)   │    Authentication Tag - 16 bytes            │
└────────────────┴─────────────────────────────────────────────┘
```

**Example sizes:**
- Token: `"eyJ...abc"` (100 bytes)
- Encrypted: IV (12) + ciphertext (100) + tag (16) = 128 bytes
- Base64 encoded: ~171 characters

---

## Security Properties

### What This Protects Against

| Attack | Protected? | How |
|--------|------------|-----|
| Database breach | ✅ Yes | Tokens are encrypted, useless without key |
| Data tampering | ✅ Yes | Auth tag detects modifications |
| Ciphertext manipulation | ✅ Yes | Decryption fails if data modified |
| Replay attacks | ✅ Yes | Each token encrypted with unique IV |
| Key guessing | ✅ Yes | 256-bit key = 2^256 possibilities |

### What This Doesn't Protect Against

| Attack | Protected? | Mitigation |
|--------|------------|------------|
| Encryption key theft | ❌ No | Store key in secure key management (e.g., HashiCorp Vault) |
| Memory dump attacks | ❌ No | Use secure memory handling, minimize key lifetime |
| Side-channel attacks | ❌ No | Use constant-time operations (Java handles this) |

---

## Configuration

### Environment Variable

```bash
# Generate a new key
openssl rand -base64 32

# Set in .env
MONZO_ENCRYPTION_KEY=your-generated-key-here
```

### Application Properties

```properties
# application.properties
encryption.secret-key=${MONZO_ENCRYPTION_KEY:}
```

### Key Rotation (Future)

Currently, key rotation requires:
1. Generate new key
2. Decrypt all tokens with old key
3. Re-encrypt all tokens with new key
4. Update environment variable

Future enhancement: Support multiple keys with key versioning.

---

## Code Location

| File | Purpose |
|------|---------|
| `EncryptionProperties.java` | Configuration binding |
| `EncryptionService.java` | Encrypt/decrypt implementation |
| `EncryptionServiceTest.java` | Unit tests (24 tests) |
| `EncryptionException.java` | Custom exception |

---

## References

- [NIST SP 800-38D: GCM Mode](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38d.pdf)
- [Java Cryptography Architecture](https://docs.oracle.com/en/java/javase/17/security/java-cryptography-architecture-jca-reference-guide.html)
- [AES-GCM in Java](https://www.baeldung.com/java-aes-encryption-decryption)
