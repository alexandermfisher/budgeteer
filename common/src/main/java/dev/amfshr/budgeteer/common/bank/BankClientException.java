package dev.amfshr.budgeteer.common.bank;

/**
 * Base exception for provider-neutral bank client errors.
 */
public class BankClientException extends RuntimeException {

    public BankClientException(String message) {
        super(message);
    }

    public BankClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
