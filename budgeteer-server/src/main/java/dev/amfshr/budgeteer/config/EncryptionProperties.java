package dev.amfshr.budgeteer.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for encryption services.
 *
 * <p>Used for encrypting sensitive data at rest (e.g., Monzo OAuth tokens).
 * Uses AES-256-GCM encryption which requires a 256-bit (32 byte) key.
 *
 * <p>Configuration:
 * <pre>
 * encryption.secret-key=${MONZO_ENCRYPTION_KEY}
 * </pre>
 *
 * <p>Generate a key with: {@code openssl rand -base64 32}
 */
@Component
@ConfigurationProperties(prefix = "encryption")
public class EncryptionProperties {

    /**
     * Base64-encoded 256-bit secret key for AES-256-GCM encryption.
     * Must be exactly 32 bytes when decoded.
     *
     * <p>Generate with: {@code openssl rand -base64 32}
     */
    @Nullable
    private String secretKey;

    /**
     * Gets the base64-encoded encryption secret key.
     *
     * @return the secret key, or null if not configured
     */
    @Nullable
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * Sets the base64-encoded encryption secret key.
     *
     * @param secretKey the secret key (base64-encoded, 32 bytes when decoded)
     */
    public void setSecretKey(@Nullable String secretKey) {
        this.secretKey = secretKey;
    }
}
