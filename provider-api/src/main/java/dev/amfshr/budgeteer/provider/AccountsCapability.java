package dev.amfshr.budgeteer.provider;

import dev.amfshr.budgeteer.provider.model.BankAccount;
import dev.amfshr.budgeteer.provider.model.Sourced;

import java.util.List;

/**
 * Account-listing capability: enumerate the accounts visible to an authenticated connection.
 */
public interface AccountsCapability {

    /** All accounts for the authenticated connection, each with its verbatim provider JSON. */
    List<Sourced<BankAccount>> getAccounts(String accessToken);
}
