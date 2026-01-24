package dev.amf.budgeteer.service;

import dev.amf.budgeteer.config.EncryptionProperties;
import dev.amf.budgeteer.exception.EncryptionException;
import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service for encrypting and decrypting sensitive data using AES-256-GCM.
 *
 * <p>This service provides authenticated encryption with associated data (AEAD),
 * ensuring both confidentiality and integrity of encrypted data.
 *
 * <h2>Security Properties:</h2>
 * <ul>
 *   <li><strong>Algorithm:</strong> AES-256-GCM (Galois/Counter Mode)</li>
 *   <li><strong>Key size:</strong> 256 bits (32 bytes)</li>
 *   <li><strong>IV size:</strong> 96 bits (12 bytes) - unique per encryption</li>
 *   <li><strong>Auth tag:</strong> 128 bits (16 bytes) - ensures integrity</li>
 * </ul>
 *
 * <h2>Storage Format:</h2>
 * <p>Encrypted data is stored as: {@code base64(IV || ciphertext || authTag)}
 * <ul>
 *   <li>First 12 bytes: IV (Initialization Vector)</li>
 *   <li>Remaining bytes: ciphertext + authentication tag</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * String encrypted = encryptionService.encrypt("sensitive-token");
 * String decrypted = encryptionService.decrypt(encrypted);
 * }</pre>
 *
 * @see <a href="https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38d.pdf">
 *      NIST SP 800-38D: GCM Mode</a>
 */
@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);

    /** AES-GCM transformation string. */
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    /** AES algorithm name for key spec. */
    private static final String KEY_ALGORITHM = "AES";

    /** Size of IV in bytes (96 bits as recommended by NIST for GCM). */
    private static final int IV_LENGTH_BYTES = 12;

    /** Size of authentication tag in bits. */
    private static final int TAG_LENGTH_BITS = 128;

    /** Expected key size in bytes (256 bits). */
    private static final int KEY_LENGTH_BYTES = 32;

    private final EncryptionProperties encryptionProperties;
    private final SecureRandom secureRandom;

    /** The AES secret key, initialized in {@link #init()}. */
    @Nullable
    private SecretKey secretKey;

    /**
     * Constructs a new EncryptionService.
     *
     * @param encryptionProperties the encryption configuration properties
     */
    public EncryptionService(EncryptionProperties encryptionProperties) {
        this.encryptionProperties = encryptionProperties;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Initializes the encryption service by loading and validating the secret key.
     *
     * @throws IllegalStateException if the secret key is not configured or invalid
     */
    @PostConstruct
    public void init() {
        String keyString = encryptionProperties.getSecretKey();
        if (keyString == null || keyString.isBlank()) {
            throw new IllegalStateException(
                    "MONZO_ENCRYPTION_KEY is not configured! " +
                    "Please set the MONZO_ENCRYPTION_KEY environment variable. " +
                    "Generate with: openssl rand -base64 32"
            );
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(keyString);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "MONZO_ENCRYPTION_KEY is not valid base64. " +
                    "Generate with: openssl rand -base64 32",
                    e
            );
        }

        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "MONZO_ENCRYPTION_KEY must be 32 bytes (256 bits) when base64 decoded. " +
                    "Current length: " + keyBytes.length + " bytes. " +
                    "Generate with: openssl rand -base64 32"
            );
        }

        this.secretKey = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
        log.info("Encryption service initialized successfully");
    }

    /**
     * Encrypts a plaintext string using AES-256-GCM.
     *
     * <p>Each encryption generates a unique random IV, ensuring that the same
     * plaintext produces different ciphertext each time.
     *
     * @param plaintext the string to encrypt
     * @return base64-encoded encrypted data (IV + ciphertext + auth tag)
     * @throws EncryptionException if encryption fails
     * @throws IllegalArgumentException if plaintext is null or empty
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("Plaintext cannot be null or empty");
        }

        try {
            // Generate random IV
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            // Encrypt
            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertextWithTag = cipher.doFinal(plaintextBytes);

            // Combine IV + ciphertext + tag
            byte[] combined = ByteBuffer.allocate(IV_LENGTH_BYTES + ciphertextWithTag.length)
                    .put(iv)
                    .put(ciphertextWithTag)
                    .array();

            // Return as base64
            return Base64.getEncoder().encodeToString(combined);

        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypts a base64-encoded encrypted string.
     *
     * @param encryptedData base64-encoded encrypted data (IV + ciphertext + auth tag)
     * @return the decrypted plaintext string
     * @throws EncryptionException if decryption fails (wrong key, tampered data, etc.)
     * @throws IllegalArgumentException if encryptedData is null, empty, or malformed
     */
    public String decrypt(String encryptedData) {
        if (encryptedData == null || encryptedData.isEmpty()) {
            throw new IllegalArgumentException("Encrypted data cannot be null or empty");
        }

        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(encryptedData);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Encrypted data is not valid base64", e);
        }

        if (combined.length < IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
            throw new IllegalArgumentException(
                    "Encrypted data is too short. Expected at least " +
                    (IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) + " bytes, got " + combined.length
            );
        }

        try {
            // Extract IV and ciphertext
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] ciphertextWithTag = new byte[buffer.remaining()];
            buffer.get(ciphertextWithTag);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            // Decrypt
            byte[] plaintextBytes = cipher.doFinal(ciphertextWithTag);
            return new String(plaintextBytes, StandardCharsets.UTF_8);

        } catch (GeneralSecurityException e) {
            throw new EncryptionException(
                    "Failed to decrypt data. The data may be corrupted or tampered with.",
                    e
            );
        }
    }
}
