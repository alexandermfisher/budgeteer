package dev.amfshr.budgeteer.bank;

/**
 * Base exception for provider-neutral errors.
 */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
