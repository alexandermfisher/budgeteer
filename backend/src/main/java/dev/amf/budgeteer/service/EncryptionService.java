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
     * plaintext produces different ciphertext each time. This is critical for
     * security - reusing an IV with the same key completely breaks GCM security.
     *
     * <h3>How AES-256-GCM Encryption Works:</h3>
     * <ol>
     *   <li><strong>IV Generation:</strong> A random 12-byte IV (Initialization Vector)
     *       is generated using SecureRandom. This IV must be unique for each encryption
     *       operation with the same key. Using SecureRandom ensures cryptographically
     *       strong randomness.</li>
     *   <li><strong>Cipher Setup:</strong> The Cipher is configured with AES/GCM/NoPadding
     *       and initialized with the secret key and a GCMParameterSpec (which contains
     *       the IV and authentication tag length).</li>
     *   <li><strong>Encryption:</strong> The plaintext is encrypted. GCM mode produces
     *       ciphertext plus a 16-byte authentication tag that validates integrity.</li>
     *   <li><strong>Output Packaging:</strong> The IV is prepended to the ciphertext+tag
     *       because the decryption side needs the exact IV used during encryption.</li>
     * </ol>
     *
     * @param plaintext the string to encrypt (may be null for defensive null-check)
     * @return base64-encoded encrypted data (IV + ciphertext + auth tag)
     * @throws EncryptionException if encryption fails due to cryptographic error
     * @throws IllegalArgumentException if plaintext is null or empty
     */
    public String encrypt(@Nullable String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("Plaintext cannot be null or empty");
        }

        try {
            // Step 1: Generate a cryptographically secure random IV (Initialization Vector)
            // - SecureRandom provides cryptographically strong random bytes
            // - 12 bytes (96 bits) is the NIST-recommended IV length for GCM
            // - CRITICAL: Each encryption MUST use a unique IV with the same key
            // - Reusing an IV completely breaks GCM security (allows key recovery)
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            // Step 2: Create and configure the AES-GCM cipher
            // - Cipher.getInstance("AES/GCM/NoPadding") creates an AES cipher in GCM mode
            // - GCM = Galois/Counter Mode, an authenticated encryption mode
            // - NoPadding = GCM is a stream cipher mode, no padding needed
            Cipher cipher = Cipher.getInstance(ALGORITHM);

            // Step 3: Initialize cipher with encryption mode, key, and GCM parameters
            // - GCMParameterSpec defines: authentication tag size (128 bits) and the IV
            // - The 128-bit auth tag provides strong integrity/authenticity protection
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            // Step 4: Encrypt the plaintext
            // - doFinal() encrypts and returns ciphertext + authentication tag
            // - The auth tag is automatically appended to the ciphertext by GCM
            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertextWithTag = cipher.doFinal(plaintextBytes);

            // Step 5: Package IV + ciphertext + auth tag together
            // - We must store the IV with the ciphertext for decryption
            // - Format: [IV (12 bytes)][ciphertext][auth tag (16 bytes)]
            byte[] combined = ByteBuffer.allocate(IV_LENGTH_BYTES + ciphertextWithTag.length)
                    .put(iv)
                    .put(ciphertextWithTag)
                    .array();

            // Step 6: Base64 encode for safe string storage/transport
            return Base64.getEncoder().encodeToString(combined);

        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypts a base64-encoded encrypted string.
     *
     * <h3>How AES-256-GCM Decryption Works:</h3>
     * <ol>
     *   <li><strong>Base64 Decode:</strong> Convert the stored string back to bytes.</li>
     *   <li><strong>Extract IV:</strong> Read the first 12 bytes as the IV that was used
     *       during encryption. The IV must be identical to decrypt correctly.</li>
     *   <li><strong>Cipher Setup:</strong> Configure the cipher with the same parameters
     *       (AES/GCM/NoPadding, same key, same IV, same tag length).</li>
     *   <li><strong>Decrypt &amp; Verify:</strong> GCM automatically verifies the authentication
     *       tag during decryption. If the data was tampered with or the wrong key is used,
     *       doFinal() throws an AEADBadTagException.</li>
     * </ol>
     *
     * <h3>Security Note:</h3>
     * <p>GCM's authentication tag is verified automatically during decryption. If verification
     * fails (wrong key, corrupted data, tampered ciphertext), the method throws an
     * EncryptionException. This prevents returning potentially corrupted/malicious data.
     *
     * @param encryptedData base64-encoded encrypted data (IV + ciphertext + auth tag),
     *                      may be null for defensive null-check
     * @return the decrypted plaintext string
     * @throws EncryptionException if decryption fails (wrong key, tampered data, etc.)
     * @throws IllegalArgumentException if encryptedData is null, empty, or malformed
     */
    public String decrypt(@Nullable String encryptedData) {
        if (encryptedData == null || encryptedData.isEmpty()) {
            throw new IllegalArgumentException("Encrypted data cannot be null or empty");
        }

        // Step 1: Base64 decode the stored encrypted data
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(encryptedData);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Encrypted data is not valid base64", e);
        }

        // Step 2: Validate minimum length (must have IV + at least auth tag)
        // - IV: 12 bytes, Auth tag: 16 bytes, minimum total: 28 bytes
        if (combined.length < IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
            throw new IllegalArgumentException(
                    "Encrypted data is too short. Expected at least " +
                    (IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) + " bytes, got " + combined.length
            );
        }

        try {
            // Step 3: Extract IV (first 12 bytes) and ciphertext+tag (remaining bytes)
            // - ByteBuffer provides efficient byte array slicing
            // - The IV was stored at the beginning during encryption
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] ciphertextWithTag = new byte[buffer.remaining()];
            buffer.get(ciphertextWithTag);

            // Step 4: Configure cipher for decryption with same parameters as encryption
            // - Must use the exact same IV that was used to encrypt this data
            // - GCMParameterSpec tells the cipher about tag length and IV
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            // Step 5: Decrypt and verify authentication tag
            // - GCM verifies the auth tag automatically during doFinal()
            // - If verification fails: throws AEADBadTagException (subclass of GeneralSecurityException)
            // - This catches: wrong key, corrupted data, tampered ciphertext, tampered IV
            byte[] plaintextBytes = cipher.doFinal(ciphertextWithTag);
            return new String(plaintextBytes, StandardCharsets.UTF_8);

        } catch (GeneralSecurityException e) {
            // Decryption failed - could be:
            // - Wrong encryption key
            // - Corrupted ciphertext
            // - Tampered data (authentication tag mismatch)
            // - Truncated data
            throw new EncryptionException(
                    "Failed to decrypt data. The data may be corrupted or tampered with.",
                    e
            );
        }
    }
}
