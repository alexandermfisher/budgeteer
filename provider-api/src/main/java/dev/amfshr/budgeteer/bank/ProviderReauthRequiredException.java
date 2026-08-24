package dev.amfshr.budgeteer.bank;

/**
 * Thrown when the bank requires re-authentication (403 SCA / consent expired).
 */
public class ProviderReauthRequiredException extends ProviderException {

    public ProviderReauthRequiredException(String message) {
        super(message);
    }
}
