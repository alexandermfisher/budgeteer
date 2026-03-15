package dev.amf.budgeteer.exception;

/**
 * Exception thrown when encryption or decryption operations fail.
 *
 * <p>This exception indicates a failure in the cryptographic operation,
 * which could be due to:
 * <ul>
 *   <li>Invalid or corrupted ciphertext</li>
 *   <li>Tampered data (authentication tag mismatch)</li>
 *   <li>Wrong encryption key</li>
 *   <li>Cryptographic algorithm issues</li>
 * </ul>
 *
 * <p>This is an unchecked exception as encryption failures typically
 * indicate a programming error or data integrity issue that cannot
 * be recovered from at runtime.
 */
public class EncryptionException extends RuntimeException {

    /**
     * Constructs a new EncryptionException with the specified message.
     *
     * @param message the detail message
     */
    public EncryptionException(String message) {
        super(message);
    }

    /**
     * Constructs a new EncryptionException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
