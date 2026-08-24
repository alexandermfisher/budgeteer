package dev.amfshr.budgeteer.bank;

/**
 * Thrown when the bank connection has been revoked (401). The user must re-authenticate.
 */
public class ProviderConnectionRevokedException extends ProviderException {

    public ProviderConnectionRevokedException(String message) {
        super(message);
    }
}
