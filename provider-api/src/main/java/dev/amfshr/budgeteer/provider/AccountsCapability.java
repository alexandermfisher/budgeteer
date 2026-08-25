package dev.amfshr.budgeteer.provider;

import dev.amfshr.budgeteer.provider.model.BankAccount;

import java.util.List;

/**
 * Account-listing capability: enumerate the accounts visible to an authenticated connection.
 */
public interface AccountsCapability {

    /** All accounts for the authenticated connection. */
    List<BankAccount> getAccounts(String accessToken);
}
