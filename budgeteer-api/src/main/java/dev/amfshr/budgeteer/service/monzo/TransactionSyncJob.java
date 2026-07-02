package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TransactionSyncJob {

    private static final Logger log = LoggerFactory.getLogger(TransactionSyncJob.class);

    private final TransactionSyncService syncService;
    private final MonzoAccountRepository accountRepository;

    public TransactionSyncJob(TransactionSyncService syncService, MonzoAccountRepository accountRepository) {
        this.syncService = syncService;
        this.accountRepository = accountRepository;
    }

    @Scheduled(cron = "${monzo.transaction-sync.job-cron}")
    public void syncAllAccounts() {
        List<MonzoAccount> accounts = accountRepository.findAllSyncable();

        if (accounts.isEmpty()) {
            log.debug("Transaction sync job: no syncable accounts");
            return;
        }

        log.info("Transaction sync job: syncing {} account(s)", accounts.size());

        int synced = 0;
        int failed = 0;

        for (MonzoAccount account : accounts) {
            try {
                syncService.deltaSync(account.getId());
                synced++;
            } catch (Exception e) {
                failed++;
                log.error("Transaction sync job: failed for account {} - {}", account.getId(), e.getMessage(), e);
            }
        }

        log.info("Transaction sync job complete: {}/{} synced, {} failed", synced, accounts.size(), failed);
    }
}
