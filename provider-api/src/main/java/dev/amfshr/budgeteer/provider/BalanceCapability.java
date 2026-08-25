package dev.amfshr.budgeteer.provider;

import dev.amfshr.budgeteer.provider.exception.ProviderConnectionRevokedException;
import dev.amfshr.budgeteer.provider.exception.ProviderException;
import dev.amfshr.budgeteer.provider.model.BankBalance;

/**
 * Balance capability: point-in-time balance for a single account.
 */
public interface BalanceCapability {

    /**
     * Current balance for one account, as reported by the provider. The caller stamps the
     * fetch time; this record carries no timestamp.
     *
     * @throws ProviderConnectionRevokedException if the connection is revoked (401)
     * @throws ProviderException on any other upstream failure
     */
    BankBalance getBalance(String accessToken, String accountId);
}
