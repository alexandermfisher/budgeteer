package dev.amfshr.budgeteer.service.ingest;

import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.account.Provider;

import java.util.List;

/**
 * Per-provider raw → domain ingest strategy. Spring injects all implementations into
 * {@link IngestService}, which owns transaction boundaries — implementations assume they run
 * inside an active transaction.
 */
public interface ProviderIngestor {

    /** Which provider's raw tables this ingestor reads. */
    Provider provider();

    /**
     * Upsert domain accounts from this provider's raw tables (lifecycle mirroring included).
     * Returns ALL domain accounts for this provider, archived included.
     */
    List<Account> ingestAccounts();

    /** Map raw transactions re-touched since the account's cursor; advance the cursor. */
    void ingestTransactions(Account account);
}
