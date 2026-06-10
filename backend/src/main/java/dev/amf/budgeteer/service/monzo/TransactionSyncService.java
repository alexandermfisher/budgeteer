package dev.amf.budgeteer.service.monzo;

import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.client.monzo.MonzoClient;
import dev.amf.budgeteer.client.monzo.dto.MonzoAccountResponse;
import dev.amf.budgeteer.client.monzo.dto.MonzoTransactionResponse;
import dev.amf.budgeteer.domain.monzo.MonzoAccount;
import dev.amf.budgeteer.domain.monzo.MonzoConnection;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.exception.ApiException;
import dev.amf.budgeteer.repository.MonzoAccountRepository;
import dev.amf.budgeteer.repository.MonzoConnectionRepository;
import dev.amf.budgeteer.repository.MonzoTransactionRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionSyncService {

    private static final Logger log = LoggerFactory.getLogger(TransactionSyncService.class);
    private static final int PAGE_SIZE = 100;

    private final MonzoClient monzoClient;
    private final MonzoConnectionService connectionService;
    private final MonzoConnectionRepository connectionRepository;
    private final MonzoAccountRepository accountRepository;
    private final MonzoTransactionRepository transactionRepository;

    public TransactionSyncService(
            MonzoClient monzoClient,
            MonzoConnectionService connectionService,
            MonzoConnectionRepository connectionRepository,
            MonzoAccountRepository accountRepository,
            MonzoTransactionRepository transactionRepository
    ) {
        this.monzoClient = monzoClient;
        this.connectionService = connectionService;
        this.connectionRepository = connectionRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Full backfill for a connection — fetches all accounts then paginates through all transactions.
     * Designed to run within Monzo's SCA window immediately after OAuth completes.
     */
    @Transactional
    public void backfill(UUID connectionId) {
        MonzoConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MONZO_SYNC_ERROR,
                        "Connection not found for backfill: " + connectionId));

        UUID userId = connection.getUser().getId();
        log.info("Starting backfill [connectionId={}, userId={}]", connectionId, userId);

        String accessToken = connectionService.getDecryptedAccessToken(connectionId, userId);

        List<MonzoAccountResponse> accountResponses = monzoClient.getAccounts(accessToken);
        log.debug("Fetched {} accounts for connection {}", accountResponses.size(), connectionId);

        for (MonzoAccountResponse ar : accountResponses) {
            MonzoAccount account = upsertAccount(ar, connection, connection.getUser());

            if (ar.closed()) {
                log.debug("Skipping closed account {}", ar.id());
                continue;
            }

            String latestTxId = paginateTransactions(accessToken, account, null);
            account.recordSyncComplete(latestTxId);
            accountRepository.save(account);
        }

        log.info("Backfill complete [connectionId={}, accounts={}]", connectionId, accountResponses.size());
    }

    /**
     * Async backfill with retries within the SCA window.
     * Called immediately after OAuth callback to fetch full transaction history
     * before the 5-minute SCA window closes. Retries if permissions aren't ready yet.
     */
    @Async
    @Transactional
    public void backfillAsync(UUID connectionId) {
        int maxRetries = 8;
        int initialDelayMs = 2000;
        int retryDelayMs = 2000;

        // Wait for user to confirm on their phone before first attempt
        try {
            Thread.sleep(initialDelayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("Backfill startup delay interrupted [connectionId={}]", connectionId);
            return;
        }

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Backfill attempt {}/{} [connectionId={}]", attempt, maxRetries, connectionId);
                backfill(connectionId);
                return;
            } catch (ApiException e) {
                if (e.getErrorCode() == ErrorCode.MONZO_API_ERROR && attempt < maxRetries) {
                    log.warn("Backfill failed with API error (permissions may not be ready yet) [connectionId={}, attempt={}/{}]",
                            connectionId, attempt, maxRetries);
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Backfill retry interrupted [connectionId={}]", connectionId, ie);
                        return;
                    }
                } else {
                    log.error("Backfill failed on final attempt [connectionId={}]", connectionId, e);
                    return;
                }
            }
        }
    }

    /**
     * Delta sync for a single account — fetches only transactions since the last sync cursor.
     */
    @Transactional
    public void deltaSync(String accountId) {
        MonzoAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.MONZO_SYNC_ERROR,
                        "Account not found for delta sync: " + accountId));

        UUID connectionId = account.getConnection().getId();
        UUID userId = account.getUserId();
        log.debug("Delta sync [accountId={}, connectionId={}]", accountId, connectionId);

        String accessToken = connectionService.getDecryptedAccessToken(connectionId, userId);

        String latestTxId = paginateTransactions(accessToken, account, account.getLastTransactionId());
        account.recordSyncComplete(latestTxId);
        accountRepository.save(account);

        log.debug("Delta sync complete [accountId={}]", accountId);
    }

    // ============ Private Methods ============

    private MonzoAccount upsertAccount(MonzoAccountResponse ar, MonzoConnection connection, User user) {
        Optional<MonzoAccount> existing = accountRepository.findById(ar.id());
        if (existing.isPresent()) {
            MonzoAccount account = existing.get();
            account.setClosed(ar.closed());
            return accountRepository.save(account);
        }
        MonzoAccount account = new MonzoAccount(
                ar.id(), connection, user, ar.type(), ar.description(), ar.currency(), ar.closed()
        );
        return accountRepository.save(account);
    }

    /**
     * Pages through transactions for an account and upserts each page.
     *
     * @param sinceId cursor — null for full history, non-null for delta
     * @return the ID of the last transaction seen, or null if no transactions
     */
    @Nullable
    private String paginateTransactions(String accessToken, MonzoAccount account, @Nullable String sinceId) {
        String cursor = sinceId;
        String latestTxId = null;
        int total = 0;

        while (true) {
            List<MonzoTransactionResponse> page = monzoClient.getTransactions(
                    accessToken, account.getId(), cursor, PAGE_SIZE
            );

            for (MonzoTransactionResponse tx : page) {
                upsertTransaction(tx, account);
                latestTxId = tx.id();
            }

            total += page.size();

            if (page.size() < PAGE_SIZE) {
                break;
            }
            cursor = page.get(page.size() - 1).id();
        }

        log.debug("Synced {} transactions for account {}", total, account.getId());
        return latestTxId;
    }

    private void upsertTransaction(MonzoTransactionResponse tx, MonzoAccount account) {
        Instant settledAt = (tx.settled() != null && !tx.settled().isBlank())
                ? Instant.parse(tx.settled())
                : null;
        Instant createdAt = (tx.created() != null && !tx.created().isBlank())
                ? Instant.parse(tx.created())
                : Instant.now();

        transactionRepository.upsert(
                tx.id(),
                account.getId(),
                account.getUserId(),
                tx.amount(),
                tx.currency(),
                tx.description(),
                tx.merchant() != null ? tx.merchant().name() : null,
                tx.merchant() != null ? tx.merchant().category() : null,
                tx.notes(),
                tx.declineReason() != null,
                createdAt,
                settledAt
        );
    }
}
