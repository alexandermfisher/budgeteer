package dev.amf.budgeteer.service;

import dev.amf.budgeteer.config.EncryptionProperties;
import dev.amf.budgeteer.exception.EncryptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EncryptionService}.
 */
@DisplayName("EncryptionService")
class EncryptionServiceTest {

    // Valid 32-byte test key (base64 encoded) - "12345678901234567890123456789012" (32 bytes)
    private static final String VALID_TEST_KEY = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=";
    // Different valid 32-byte test key for wrong-key tests - "abcdefghijklmnopqrstuvwxyz123456" (32 bytes)
    private static final String DIFFERENT_TEST_KEY = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=";

    private EncryptionService encryptionService;
    private EncryptionProperties encryptionProperties;

    @BeforeEach
    void setUp() {
        encryptionProperties = new EncryptionProperties();
        encryptionProperties.setSecretKey(VALID_TEST_KEY);
        encryptionService = new EncryptionService(encryptionProperties);
        encryptionService.init();
    }

    @Nested
    @DisplayName("init()")
    class InitTests {

        @Test
        @DisplayName("should initialize successfully with valid key")
        void shouldInitializeWithValidKey() {
            // Given
            EncryptionProperties props = new EncryptionProperties();
            props.setSecretKey(VALID_TEST_KEY);
            EncryptionService service = new EncryptionService(props);

            // When/Then - no exception
            service.init();
        }

        @Test
        @DisplayName("should throw when key is null")
        void shouldThrowWhenKeyIsNull() {
            // Given
            EncryptionProperties props = new EncryptionProperties();
            props.setSecretKey(null);
            EncryptionService service = new EncryptionService(props);

            // When/Then
            assertThatThrownBy(service::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MONZO_ENCRYPTION_KEY is not configured");
        }

        @Test
        @DisplayName("should throw when key is blank")
        void shouldThrowWhenKeyIsBlank() {
            // Given
            EncryptionProperties props = new EncryptionProperties();
            props.setSecretKey("   ");
            EncryptionService service = new EncryptionService(props);

            // When/Then
            assertThatThrownBy(service::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MONZO_ENCRYPTION_KEY is not configured");
        }

        @Test
        @DisplayName("should throw when key is not valid base64")
        void shouldThrowWhenKeyIsNotBase64() {
            // Given
            EncryptionProperties props = new EncryptionProperties();
            props.setSecretKey("not-valid-base64!!!");
            EncryptionService service = new EncryptionService(props);

            // When/Then
            assertThatThrownBy(service::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not valid base64");
        }

        @Test
        @DisplayName("should throw when key is too short")
        void shouldThrowWhenKeyIsTooShort() {
            // Given - 16 bytes instead of 32
            EncryptionProperties props = new EncryptionProperties();
            props.setSecretKey(Base64.getEncoder().encodeToString("short-key-16byte".getBytes()));
            EncryptionService service = new EncryptionService(props);

            // When/Then
            assertThatThrownBy(service::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be 32 bytes");
        }

        @Test
        @DisplayName("should throw when key is too long")
        void shouldThrowWhenKeyIsTooLong() {
            // Given - 64 bytes instead of 32
            EncryptionProperties props = new EncryptionProperties();
            props.setSecretKey(Base64.getEncoder().encodeToString(
                    "this-is-a-very-long-key-that-is-64-bytes-in-total-for-this-test".getBytes()
            ));
            EncryptionService service = new EncryptionService(props);

            // When/Then
            assertThatThrownBy(service::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be 32 bytes");
        }
    }

    @Nested
    @DisplayName("encrypt()")
    class EncryptTests {

        @Test
        @DisplayName("should encrypt plaintext successfully")
        void shouldEncryptPlaintext() {
            // Given
            String plaintext = "my-secret-token";

            // When
            String encrypted = encryptionService.encrypt(plaintext);

            // Then
            assertThat(encrypted).isNotNull();
            assertThat(encrypted).isNotEqualTo(plaintext);
            assertThat(encrypted).isBase64();
        }

        @Test
        @DisplayName("should produce different ciphertext for same plaintext (unique IV)")
        void shouldProduceDifferentCiphertextForSamePlaintext() {
            // Given
            String plaintext = "same-plaintext";

            // When
            String encrypted1 = encryptionService.encrypt(plaintext);
            String encrypted2 = encryptionService.encrypt(plaintext);

            // Then - different ciphertext due to random IV
            assertThat(encrypted1).isNotEqualTo(encrypted2);
        }

        @Test
        @DisplayName("should throw when plaintext is null")
        void shouldThrowWhenPlaintextIsNull() {
            assertThatThrownBy(() -> encryptionService.encrypt(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("should throw when plaintext is empty")
        void shouldThrowWhenPlaintextIsEmpty() {
            assertThatThrownBy(() -> encryptionService.encrypt(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("should handle long plaintext")
        void shouldHandleLongPlaintext() {
            // Given - a long token like a real Monzo access token
            String longPlaintext = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9." +
                    "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsImlhdCI6MTUxNjIzOTAyMn0." +
                    "POstGetfAytaZS82wHcjoTyoqhMyxXiWdR7Nn7A28cN";

            // When
            String encrypted = encryptionService.encrypt(longPlaintext);

            // Then
            assertThat(encrypted).isNotNull();
            assertThat(encrypted).isBase64();
        }

        @Test
        @DisplayName("should handle unicode characters")
        void shouldHandleUnicodeCharacters() {
            // Given
            String unicode = "token-with-emoji-🔐-and-日本語";

            // When
            String encrypted = encryptionService.encrypt(unicode);

            // Then
            assertThat(encrypted).isNotNull();
            assertThat(encrypted).isBase64();
        }
    }

    @Nested
    @DisplayName("decrypt()")
    class DecryptTests {

        @Test
        @DisplayName("should decrypt to original plaintext")
        void shouldDecryptToOriginalPlaintext() {
            // Given
            String plaintext = "my-secret-token";
            String encrypted = encryptionService.encrypt(plaintext);

            // When
            String decrypted = encryptionService.decrypt(encrypted);

            // Then
            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("should decrypt long plaintext correctly")
        void shouldDecryptLongPlaintextCorrectly() {
            // Given
            String longPlaintext = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9." +
                    "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsImlhdCI6MTUxNjIzOTAyMn0." +
                    "POstGetfAytaZS82wHcjoTyoqhMyxXiWdR7Nn7A28cN";
            String encrypted = encryptionService.encrypt(longPlaintext);

            // When
            String decrypted = encryptionService.decrypt(encrypted);

            // Then
            assertThat(decrypted).isEqualTo(longPlaintext);
        }

        @Test
        @DisplayName("should decrypt unicode characters correctly")
        void shouldDecryptUnicodeCorrectly() {
            // Given
            String unicode = "token-with-emoji-🔐-and-日本語";
            String encrypted = encryptionService.encrypt(unicode);

            // When
            String decrypted = encryptionService.decrypt(encrypted);

            // Then
            assertThat(decrypted).isEqualTo(unicode);
        }

        @Test
        @DisplayName("should throw when encrypted data is null")
        void shouldThrowWhenEncryptedDataIsNull() {
            assertThatThrownBy(() -> encryptionService.decrypt(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("should throw when encrypted data is empty")
        void shouldThrowWhenEncryptedDataIsEmpty() {
            assertThatThrownBy(() -> encryptionService.decrypt(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("should throw when encrypted data is not base64")
        void shouldThrowWhenEncryptedDataIsNotBase64() {
            assertThatThrownBy(() -> encryptionService.decrypt("not-valid-base64!!!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not valid base64");
        }

        @Test
        @DisplayName("should throw when encrypted data is too short")
        void shouldThrowWhenEncryptedDataIsTooShort() {
            // Given - base64 of just a few bytes
            String tooShort = Base64.getEncoder().encodeToString(new byte[10]);

            // When/Then
            assertThatThrownBy(() -> encryptionService.decrypt(tooShort))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too short");
        }

        @Test
        @DisplayName("should throw when decrypting with wrong key")
        void shouldThrowWhenDecryptingWithWrongKey() {
            // Given - encrypt with one key
            String plaintext = "secret-data";
            String encrypted = encryptionService.encrypt(plaintext);

            // Create service with different key
            EncryptionProperties differentProps = new EncryptionProperties();
            differentProps.setSecretKey(DIFFERENT_TEST_KEY);
            EncryptionService differentService = new EncryptionService(differentProps);
            differentService.init();

            // When/Then - decrypt with different key should fail
            assertThatThrownBy(() -> differentService.decrypt(encrypted))
                    .isInstanceOf(EncryptionException.class)
                    .hasMessageContaining("Failed to decrypt");
        }

        @Test
        @DisplayName("should detect tampered ciphertext")
        void shouldDetectTamperedCiphertext() {
            // Given
            String plaintext = "original-data";
            String encrypted = encryptionService.encrypt(plaintext);

            // Tamper with the ciphertext (flip a byte in the middle)
            byte[] bytes = Base64.getDecoder().decode(encrypted);
            bytes[bytes.length / 2] ^= 0xFF;
            String tampered = Base64.getEncoder().encodeToString(bytes);

            // When/Then - should fail authentication
            assertThatThrownBy(() -> encryptionService.decrypt(tampered))
                    .isInstanceOf(EncryptionException.class)
                    .hasMessageContaining("corrupted or tampered");
        }

        @Test
        @DisplayName("should detect tampered IV")
        void shouldDetectTamperedIv() {
            // Given
            String plaintext = "original-data";
            String encrypted = encryptionService.encrypt(plaintext);

            // Tamper with the IV (first 12 bytes)
            byte[] bytes = Base64.getDecoder().decode(encrypted);
            bytes[0] ^= 0xFF;
            String tampered = Base64.getEncoder().encodeToString(bytes);

            // When/Then - should fail authentication
            assertThatThrownBy(() -> encryptionService.decrypt(tampered))
                    .isInstanceOf(EncryptionException.class)
                    .hasMessageContaining("corrupted or tampered");
        }
    }

    @Nested
    @DisplayName("Round-trip encryption/decryption")
    class RoundTripTests {

        @Test
        @DisplayName("should handle multiple encrypt/decrypt cycles")
        void shouldHandleMultipleCycles() {
            // Given
            String original = "test-data";

            // When - encrypt and decrypt multiple times
            for (int i = 0; i < 10; i++) {
                String encrypted = encryptionService.encrypt(original);
                String decrypted = encryptionService.decrypt(encrypted);
                assertThat(decrypted).isEqualTo(original);
            }
        }

        @Test
        @DisplayName("should handle various token formats")
        void shouldHandleVariousTokenFormats() {
            String[] tokens = {
                    "simple-token",
                    "token_with_underscores",
                    "token-with-dashes",
                    "Token123WithNumbers",
                    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
                    "a", // Single character
                    "ab", // Two characters
                    " ", // Whitespace
                    "\n\t\r", // Control characters
            };

            for (String token : tokens) {
                String encrypted = encryptionService.encrypt(token);
                String decrypted = encryptionService.decrypt(encrypted);
                assertThat(decrypted)
                        .as("Failed for token: " + token)
                        .isEqualTo(token);
            }
        }
    }
}
