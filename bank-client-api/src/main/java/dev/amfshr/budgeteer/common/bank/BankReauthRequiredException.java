package dev.amfshr.budgeteer.common.bank;

/**
 * Thrown when the bank requires re-authentication (403 SCA / consent expired).
 */
public class BankReauthRequiredException extends BankClientException {

    public BankReauthRequiredException(String message) {
        super(message);
    }
}
