package dev.amfshr.budgeteer.service.ingest;

import dev.amfshr.budgeteer.domain.account.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Orchestrates all {@link ProviderIngestor}s: accounts first (transactions need the domain
 * account to exist), then transactions per account — each account in its own transaction so
 * one failure never loses another account's progress.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final List<ProviderIngestor> ingestors;
    private final TransactionTemplate txTemplate;

    public IngestService(List<ProviderIngestor> ingestors, PlatformTransactionManager txManager) {
        this.ingestors = ingestors;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /** One full raw → domain mapping pass across every provider. */
    public void ingestAll() {
        for (ProviderIngestor ingestor : ingestors) {
            List<Account> accounts = txTemplate.execute(s -> ingestor.ingestAccounts());
            if (accounts == null) {
                continue;
            }
            for (Account account : accounts) {
                try {
                    txTemplate.execute(s -> {
                        ingestor.ingestTransactions(account);
                        return null;
                    });
                } catch (Exception e) {
                    log.error("Ingest failed [provider={}, account={}] - {}",
                            ingestor.provider(), account.getId(), e.getMessage(), e);
                }
            }
        }
    }
}
