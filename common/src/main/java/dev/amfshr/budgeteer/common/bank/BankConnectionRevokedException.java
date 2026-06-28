package dev.amfshr.budgeteer.common.bank;

/**
 * Thrown when the bank connection has been revoked (401). The user must re-authenticate.
 */
public class BankConnectionRevokedException extends BankClientException {

    public BankConnectionRevokedException(String message) {
        super(message);
    }
}
