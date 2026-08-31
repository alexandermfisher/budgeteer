package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.api.monzo.dto.MonzoSyncProgressResponse;
import dev.amfshr.budgeteer.provider.AccountsCapability;
import dev.amfshr.budgeteer.provider.TransactionsCapability;
import dev.amfshr.budgeteer.provider.model.BankAccount;
import dev.amfshr.budgeteer.provider.exception.ProviderException;
import dev.amfshr.budgeteer.provider.exception.ProviderReauthRequiredException;
import dev.amfshr.budgeteer.provider.model.BankTransaction;
import dev.amfshr.budgeteer.provider.model.BankTransactionPage;
import dev.amfshr.budgeteer.provider.model.Sourced;
import dev.amfshr.budgeteer.provider.model.SyncPosition;
import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.repository.MonzoTransactionRepository;
import dev.amfshr.budgeteer.service.common.EncryptionService;
import dev.amfshr.budgeteer.service.ingest.BalanceRefreshService;
import dev.amfshr.budgeteer.service.ingest.IngestService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionSyncService {

    private static final Logger log = LoggerFactory.getLogger(TransactionSyncService.class);
    private static final int PAGE_SIZE = 100;

    private static final Instant ABSOLUTE_BACKFILL_FLOOR = Instant.parse("2015-01-01T00:00:00Z");
    private static final Duration BACKFILL_WINDOW = Duration.ofDays(350);

    private final AccountsCapability accountsCapability;
    private final TransactionsCapability transactionsCapability;
    private final MonzoConnectionService connectionService;
    private final MonzoConnectionRepository connectionRepository;
    private final MonzoAccountRepository accountRepository;
    private final MonzoTransactionRepository transactionRepository;
    private final EncryptionService encryptionService;
    private final IngestService ingestService;
    private final BalanceRefreshService balanceRefreshService;
    private final TransactionTemplate txTemplate;

    public TransactionSyncService(
            AccountsCapability accountsCapability,
            TransactionsCapability transactionsCapability,
            MonzoConnectionService connectionService,
            MonzoConnectionRepository connectionRepository,
            MonzoAccountRepository accountRepository,
            MonzoTransactionRepository transactionRepository,
            EncryptionService encryptionService,
            IngestService ingestService,
            BalanceRefreshService balanceRefreshService,
            PlatformTransactionManager txManager
    ) {
        this.accountsCapability = accountsCapability;
        this.transactionsCapability = transactionsCapability;
        this.connectionService = connectionService;
        this.connectionRepository = connectionRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.encryptionService = encryptionService;
        this.ingestService = ingestService;
        this.balanceRefreshService = balanceRefreshService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Full backfill for a connection. Each 350-day window is committed independently so that
     * partial progress survives a restart, SCA expiry, or Ctrl+C.
     */
    @Async("backfillTaskExecutor")
    public void backfillAsync(UUID connectionId) {
        int maxRetries = 8;
        int retryDelayMs = 2000;

        try {
            Thread.sleep(retryDelayMs);
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
            } catch (ProviderException e) {
                if (!(e instanceof ProviderReauthRequiredException) && attempt < maxRetries) {
                    log.warn("Backfill attempt {}/{} failed — Monzo permissions not ready yet, retrying [connectionId={}]",
                            attempt, maxRetries, connectionId);
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Backfill retry interrupted [connectionId={}]", connectionId);
                        return;
                    }
                } else {
                    log.error("Backfill failed permanently [connectionId={}, attempt={}/{}]",
                            connectionId, attempt, maxRetries, e);
                    return;
                }
            }
        }
    }

    public void backfill(UUID connectionId) {
        MonzoConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ApiException(ErrorCode.PROVIDER_SYNC_ERROR,
                        "Connection not found for backfill: " + connectionId));

        UUID userId = connection.getUser().getId();
        log.info("Starting backfill [connectionId={}, userId={}]", connectionId, userId);

        String accessToken = connectionService.getDecryptedAccessToken(connectionId, userId);

        List<Sourced<BankAccount>> accountResponses = accountsCapability.getAccounts(accessToken);
        log.info("Found {} accounts [connectionId={}]", accountResponses.size(), connectionId);

        for (Sourced<BankAccount> sourced : accountResponses) {
            BankAccount ar = sourced.payload();
            MonzoAccount account = upsertAccount(sourced, connection, connection.getUser());

            if (ar.closed()) {
                log.info("Skipping closed account [account={}]", label(account));
                continue;
            }

            String latestTxId = backfillAccount(accessToken, account);
            if (account.getBackfillStatus() == MonzoAccount.BackfillStatus.COMPLETED
                    && latestTxId != null
                    && account.getLastTransactionId() == null) {
                txTemplate.execute(status -> {
                    account.recordSyncComplete(latestTxId);
                    accountRepository.save(account);
                    return null;
                });
            }
        }

        log.info("Backfill finished [connectionId={}, accounts={}]", connectionId, accountResponses.size());

        // One-shot ingest + balance stamp so first-connect data surfaces without waiting for the
        // hourly job (decision 7). Hooked here — not in backfillAsync — so every backfill entry
        // point (OAuth event listener, manual endpoint, dev trigger) gets it, including the
        // NEEDS_REAUTH pause path where partial data should still surface.
        try {
            ingestService.ingestAll();
        } catch (Exception e) {
            log.error("Ingest failed after backfill [connectionId={}]", connectionId, e);
        }
        try {
            balanceRefreshService.refreshAll();
        } catch (Exception e) {
            log.error("Balance refresh failed after backfill [connectionId={}]", connectionId, e);
        }
    }

    @Transactional
    public void deltaSync(String accountId) {
        MonzoAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.PROVIDER_SYNC_ERROR,
                        "Account not found for delta sync: " + accountId));

        UUID connectionId = account.getConnection().getId();
        UUID userId = account.getUserId();

        String accessToken = connectionService.getDecryptedAccessToken(connectionId, userId);

        // Monzo supports id-based deltas natively; with no stored id yet, fall back to a
        // full time-windowed fetch from the floor.
        String lastTxId = account.getLastTransactionId();
        SyncPosition start = lastTxId != null
                ? new SyncPosition.AfterTransaction(lastTxId)
                : new SyncPosition.FromTime(ABSOLUTE_BACKFILL_FLOOR);

        String latestTxId = paginateDelta(accessToken, account, start, Instant.now());
        account.recordSyncComplete(latestTxId);
        accountRepository.save(account);

        log.info("Delta sync complete [account={}]", label(account));
    }

    public MonzoSyncProgressResponse getSyncProgress(UUID userId) {
        List<MonzoAccount> accounts = accountRepository.findActiveByUserId(userId);
        List<MonzoSyncProgressResponse.AccountProgress> progress = accounts.stream()
                .map(this::toAccountProgress)
                .toList();
        return new MonzoSyncProgressResponse(progress);
    }

    // ============ Private Methods ============

    private MonzoAccount upsertAccount(Sourced<BankAccount> sourced, MonzoConnection connection, User user) {
        BankAccount ar = sourced.payload();
        Instant monzoCreatedAt = ar.createdAt();
        Optional<MonzoAccount> existing = accountRepository.findById(ar.externalId());
        if (existing.isPresent()) {
            MonzoAccount account = existing.get();
            account.setClosed(ar.closed());
            if (account.getMonzoCreatedAt() == null && monzoCreatedAt != null) {
                account.setMonzoCreatedAt(monzoCreatedAt);
            }
            account.setRawPayloadEncrypted(encryptRaw(sourced.rawJson()));
            return accountRepository.save(account);
        }
        MonzoAccount account = new MonzoAccount(
                ar.externalId(), connection, user, ar.type(), ar.description(), ar.currency(), ar.closed()
        );
        account.setMonzoCreatedAt(monzoCreatedAt);
        account.setRawPayloadEncrypted(encryptRaw(sourced.rawJson()));
        return accountRepository.save(account);
    }

    @Nullable
    private Instant parseMonzoCreatedAt(BankAccount ar) {
        return ar.createdAt();
    }

    /**
     * Backfills an account by walking ≤350-day windows backwards from now.
     * Each window is committed in its own transaction — partial progress survives interruption.
     */
    @Nullable
    private String backfillAccount(String accessToken, MonzoAccount account) {
        Instant floor = resolveBackfillFloor(account);
        boolean freshStart = account.getBackfillProgressAt() == null;
        Instant windowEnd = freshStart ? Instant.now() : account.getBackfillProgressAt();
        String resumeCursor = account.getBackfillProgressCursor();
        Instant startTime = Instant.now();
        String newestTxId = null;
        int windowCount = 0;

        long totalDays = Duration.between(floor, Instant.now()).toDays();
        log.info("Beginning backfill [account={}, from={}, totalRange=~{}d, status={}]",
                label(account), floor.toString().substring(0, 10), totalDays,
                freshStart ? "FRESH" : "RESUMING from " + windowEnd.toString().substring(0, 10));

        txTemplate.execute(status -> {
            account.setBackfillStatus(MonzoAccount.BackfillStatus.IN_PROGRESS);
            accountRepository.save(account);
            return null;
        });

        try {
            while (windowEnd.isAfter(floor)) {
                Instant windowStart = windowEnd.minus(BACKFILL_WINDOW);
                if (windowStart.isBefore(floor)) {
                    windowStart = floor;
                }

                int pct = progressPercent(floor, windowEnd);
                log.info("Fetching window [{} → {}] [account={}, ~{}% remaining]",
                        windowStart.toString().substring(0, 10),
                        windowEnd.toString().substring(0, 10),
                        label(account), 100 - pct);

                final Instant wStart = windowStart;
                final Instant wEnd = windowEnd;
                final String cursor = resumeCursor;
                final boolean isFirstWindow = (newestTxId == null);
                final boolean isFreshStart = freshStart;

                String latestInWindow = txTemplate.execute(status -> {
                    String latest = paginateWindow(accessToken, account, wStart, wEnd, cursor);

                    if (isFirstWindow && latest != null && isFreshStart) {
                        account.recordSyncComplete(latest);
                    }

                    account.setBackfillProgressAt(wStart);
                    account.setBackfillProgressCursor(null);
                    accountRepository.save(account);
                    return latest;
                });

                if (newestTxId == null && latestInWindow != null) {
                    newestTxId = latestInWindow;
                }

                resumeCursor = null;
                windowCount++;
                windowEnd = windowStart;
            }

            txTemplate.execute(status -> {
                account.setBackfillStatus(MonzoAccount.BackfillStatus.COMPLETED);
                accountRepository.save(account);
                return null;
            });

            log.info("Backfill complete [account={}, windows={}, elapsed={}s]",
                    label(account), windowCount, Duration.between(startTime, Instant.now()).toSeconds());

        } catch (ProviderReauthRequiredException e) {
            // SCA window expired — pause with progress saved
            int pct = progressPercent(floor, windowEnd);
            final Instant pausedAt = windowEnd;
            txTemplate.execute(status -> {
                account.setBackfillStatus(MonzoAccount.BackfillStatus.NEEDS_REAUTH);
                accountRepository.save(account);
                return null;
            });
            log.warn("Backfill paused — SCA window expired [account={}, ~{}% complete, resumeFrom={}]",
                    label(account), pct, pausedAt.toString().substring(0, 10));
        }

        return newestTxId;
    }

    private Instant resolveBackfillFloor(MonzoAccount account) {
        Instant created = account.getMonzoCreatedAt();
        if (created == null) {
            return ABSOLUTE_BACKFILL_FLOOR;
        }
        return created.isBefore(ABSOLUTE_BACKFILL_FLOOR) ? ABSOLUTE_BACKFILL_FLOOR : created;
    }

    private int progressPercent(Instant floor, Instant currentWindowEnd) {
        Instant now = Instant.now();
        long totalSeconds = Duration.between(floor, now).toSeconds();
        if (totalSeconds <= 0) return 100;
        long processedSeconds = Duration.between(currentWindowEnd, now).toSeconds();
        return (int) Math.min(99, Math.max(0, (processedSeconds * 100) / totalSeconds));
    }

    @Nullable
    private String paginateWindow(
            String accessToken,
            MonzoAccount account,
            Instant from,
            Instant to,
            @Nullable String resumeCursor
    ) {
        SyncPosition position = resumeCursor != null
                ? new SyncPosition.NextPage(resumeCursor)
                : new SyncPosition.FromTime(from);
        String latestTxId = null;
        int total = 0;

        while (true) {
            BankTransactionPage page = transactionsCapability.getTransactions(
                    accessToken, account.getId(), position, to
            );

            log.info("→ Monzo returned {} transactions [account={}, window={} → {}]",
                    page.transactions().size(), label(account),
                    from.toString().substring(0, 10), to.toString().substring(0, 10));

            for (Sourced<BankTransaction> sourced : page.transactions()) {
                upsertTransaction(sourced, account);
                latestTxId = sourced.payload().externalId();
            }

            total += page.transactions().size();

            String nextCursor = page.nextCursor();
            if (nextCursor == null) {
                break;
            }

            position = new SyncPosition.NextPage(nextCursor);
            account.setBackfillProgressCursor(nextCursor);
            accountRepository.save(account);
        }

        if (total > 0) {
            log.info("← Saved {} transactions to DB [window={} → {}, account={}]",
                    total, from.toString().substring(0, 10), to.toString().substring(0, 10), label(account));
        }
        return latestTxId;
    }

    @Nullable
    private String paginateDelta(
            String accessToken,
            MonzoAccount account,
            SyncPosition start,
            Instant to
    ) {
        SyncPosition position = start;
        String latestTxId = null;
        int total = 0;

        while (true) {
            BankTransactionPage page = transactionsCapability.getTransactions(
                    accessToken, account.getId(), position, to
            );

            for (Sourced<BankTransaction> sourced : page.transactions()) {
                upsertTransaction(sourced, account);
                latestTxId = sourced.payload().externalId();
            }

            total += page.transactions().size();

            String nextCursor = page.nextCursor();
            if (nextCursor == null) {
                break;
            }
            position = new SyncPosition.NextPage(nextCursor);
        }

        log.info("Delta sync: saved {} transactions [account={}]", total, label(account));
        return latestTxId;
    }

    private void upsertTransaction(Sourced<BankTransaction> sourced, MonzoAccount account) {
        BankTransaction tx = sourced.payload();
        transactionRepository.upsert(
                tx.externalId(),
                account.getId(),
                account.getUserId(),
                (int) tx.amountMinorUnits(),
                tx.currency(),
                tx.description(),
                tx.merchantName(),
                tx.merchantCategory(),
                tx.notes(),
                tx.declined(),
                tx.createdAt(),
                tx.settledAt(),
                encryptRaw(sourced.rawJson())
        );
    }

    /** EncryptionService.encrypt throws on null/empty — raw capture stores NULL instead. */
    @Nullable
    private String encryptRaw(@Nullable String rawJson) {
        return (rawJson == null || rawJson.isEmpty()) ? null : encryptionService.encrypt(rawJson);
    }

    private MonzoSyncProgressResponse.AccountProgress toAccountProgress(MonzoAccount account) {
        MonzoAccount.BackfillStatus status = account.getBackfillStatus();
        Instant floor = resolveBackfillFloor(account);

        int pct;
        if (status == MonzoAccount.BackfillStatus.COMPLETED) {
            pct = 100;
        } else if (account.getBackfillProgressAt() != null) {
            pct = progressPercent(floor, account.getBackfillProgressAt());
        } else {
            pct = 0;
        }

        long txCount = transactionRepository.countByAccountId(account.getId());

        return new MonzoSyncProgressResponse.AccountProgress(
                account.getId(),
                account.getDescription() != null ? account.getDescription() : account.getAccountType(),
                status != null ? status.name() : "NOT_STARTED",
                pct,
                account.getBackfillProgressAt() != null ? account.getBackfillProgressAt().toString() : null,
                txCount
        );
    }

    private String label(MonzoAccount account) {
        String name = account.getDescription() != null ? account.getDescription() : account.getAccountType();
        return name + " (" + account.getId() + ")";
    }
}
