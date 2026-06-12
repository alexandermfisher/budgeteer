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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionSyncService {

    private static final Logger log = LoggerFactory.getLogger(TransactionSyncService.class);
    private static final int PAGE_SIZE = 100;

    /**
     * Absolute floor for backfill — Monzo was founded in 2015, so anything earlier is guaranteed
     * empty. Used as a fallback when the per-account creation date is unknown.
     *
     * <p>Note: the 5-minute SCA window after OAuth grants access to all transactions; outside that
     * window Monzo caps responses to the last 90 days regardless of this floor.
     */
    private static final Instant ABSOLUTE_BACKFILL_FLOOR = Instant.parse("2015-01-01T00:00:00Z");

    /**
     * Duration of each backfill window. Monzo enforces a 365-day maximum per request when both
     * {@code since} and {@code before} are supplied; 350 days leaves comfortable headroom.
     */
    private static final Duration BACKFILL_WINDOW = Duration.ofDays(350);

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
     *
     * <p>Backfill is resumable: if a previous attempt reached NEEDS_REAUTH the account's
     * {@code backfillProgressAt} and {@code backfillProgressCursor} are used as the starting
     * point so no already-fetched data is re-requested.
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

            String latestTxId = backfillAccount(accessToken, account);
            if (account.getBackfillStatus() == MonzoAccount.BackfillStatus.COMPLETED) {
                account.recordSyncComplete(latestTxId);
            }
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

        String latestTxId = paginateTransactions(accessToken, account, null, null, account.getLastTransactionId());
        account.recordSyncComplete(latestTxId);
        accountRepository.save(account);

        log.debug("Delta sync complete [accountId={}]", accountId);
    }

    // ============ Private Methods ============

    private MonzoAccount upsertAccount(MonzoAccountResponse ar, MonzoConnection connection, User user) {
        Instant monzoCreatedAt = parseMonzoCreatedAt(ar);
        Optional<MonzoAccount> existing = accountRepository.findById(ar.id());
        if (existing.isPresent()) {
            MonzoAccount account = existing.get();
            account.setClosed(ar.closed());
            // Backfill the created-at on existing rows the first time we see it.
            if (account.getMonzoCreatedAt() == null && monzoCreatedAt != null) {
                account.setMonzoCreatedAt(monzoCreatedAt);
            }
            return accountRepository.save(account);
        }
        MonzoAccount account = new MonzoAccount(
                ar.id(), connection, user, ar.type(), ar.description(), ar.currency(), ar.closed()
        );
        account.setMonzoCreatedAt(monzoCreatedAt);
        return accountRepository.save(account);
    }

    @Nullable
    private Instant parseMonzoCreatedAt(MonzoAccountResponse ar) {
        if (ar.created() == null || ar.created().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(ar.created());
        } catch (Exception e) {
            log.warn("Could not parse Monzo account created timestamp '{}' for {}", ar.created(), ar.id());
            return null;
        }
    }

    /**
     * Backfills an account's full history by walking ≤350-day windows backwards from now until
     * reaching the account's Monzo-reported creation date (or {@link #ABSOLUTE_BACKFILL_FLOOR}).
     *
     * <p>Progress is persisted per page and per window so that:
     * <ul>
     *   <li>If Monzo returns {@code 403 verification_required} mid-pagination, the account is
     *       marked {@link MonzoAccount.BackfillStatus#NEEDS_REAUTH} and the exact page cursor
     *       is saved. Re-OAuth resumes from that cursor within the same window.</li>
     *   <li>If a previous backfill left the account in NEEDS_REAUTH, this call continues from
     *       {@code backfillProgressAt} / {@code backfillProgressCursor}.</li>
     * </ul>
     *
     * @return the ID of the newest transaction seen, or null if none
     */
    @Nullable
    private String backfillAccount(String accessToken, MonzoAccount account) {
        Instant floor = resolveBackfillFloor(account);

        // Resume from persisted window position, or start at now for a fresh backfill.
        Instant windowEnd = account.getBackfillProgressAt() != null
                ? account.getBackfillProgressAt()
                : Instant.now();

        // Resume mid-window cursor if present (from a previous NEEDS_REAUTH interruption).
        String resumeCursor = account.getBackfillProgressCursor();

        String newestTxId = null;
        int windowCount = 0;

        account.setBackfillStatus(MonzoAccount.BackfillStatus.IN_PROGRESS);
        accountRepository.save(account);

        try {
            while (windowEnd.isAfter(floor)) {
                Instant windowStart = windowEnd.minus(BACKFILL_WINDOW);
                if (windowStart.isBefore(floor)) {
                    windowStart = floor;
                }

                String latestInWindow = paginateWindow(
                        accessToken, account, windowStart.toString(), windowEnd.toString(), resumeCursor
                );

                // Only the first (most recent) non-empty window gives us the delta-sync cursor.
                if (newestTxId == null && latestInWindow != null) {
                    newestTxId = latestInWindow;
                }

                // Window fully drained — advance to the next one and clear the mid-window cursor.
                account.setBackfillProgressAt(windowStart);
                account.setBackfillProgressCursor(null);
                accountRepository.save(account);

                resumeCursor = null;
                windowCount++;
                windowEnd = windowStart;
            }

            account.setBackfillStatus(MonzoAccount.BackfillStatus.COMPLETED);
            accountRepository.save(account);
            log.info("Backfill COMPLETED [accountId={}, floor={}, windows={}]",
                    account.getId(), floor, windowCount);

        } catch (ApiException e) {
            if (e.getErrorCode() == ErrorCode.MONZO_VERIFICATION_REQUIRED) {
                // SCA window expired — progress is already persisted per-page inside paginateWindow.
                account.setBackfillStatus(MonzoAccount.BackfillStatus.NEEDS_REAUTH);
                accountRepository.save(account);
                log.info("Backfill paused — SCA verification required [accountId={}, progressAt={}, cursor={}]",
                        account.getId(), account.getBackfillProgressAt(), account.getBackfillProgressCursor());
            } else {
                throw e;
            }
        }

        return newestTxId;
    }

    /**
     * Lower bound for the backfill window walk. Prefer the account's Monzo-reported creation
     * date so we don't probe windows from before the account existed. Falls back to the
     * absolute floor if Monzo didn't supply (or we failed to parse) a creation timestamp.
     */
    private Instant resolveBackfillFloor(MonzoAccount account) {
        Instant created = account.getMonzoCreatedAt();
        if (created == null) {
            return ABSOLUTE_BACKFILL_FLOOR;
        }
        return created.isBefore(ABSOLUTE_BACKFILL_FLOOR) ? ABSOLUTE_BACKFILL_FLOOR : created;
    }

    /**
     * Pages through all transactions within a time window and upserts each page.
     * Persists {@code backfillProgressCursor} on the account after each full page so
     * that a mid-window interruption (e.g. SCA expiry) can resume without re-fetching.
     *
     * @param resumeCursor a mid-window cursor to resume from, or null to start from the window start
     * @return the ID of the last transaction seen, or null if none
     */
    @Nullable
    private String paginateWindow(
            String accessToken,
            MonzoAccount account,
            String since,
            String before,
            @Nullable String resumeCursor
    ) {
        String windowSince = resumeCursor != null ? null : since;
        String cursor = resumeCursor;
        String latestTxId = null;
        int total = 0;

        while (true) {
            List<MonzoTransactionResponse> page = monzoClient.getTransactions(
                    accessToken, account.getId(), windowSince, before, cursor, PAGE_SIZE
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
            account.setBackfillProgressCursor(cursor);
            accountRepository.save(account);
            // Once we have a cursor, drop `since` to avoid overlapping/inconsistent pages.
            windowSince = null;
        }

        log.debug("Synced {} transactions for account {} [since={}, before={}]",
                total, account.getId(), since, before);
        return latestTxId;
    }

    /**
     * Pages through transactions for delta sync (no windowing — cursor only).
     *
     * @return the ID of the last transaction seen, or null if none
     */
    @Nullable
    private String paginateTransactions(
            String accessToken,
            MonzoAccount account,
            @Nullable String since,
            @Nullable String before,
            @Nullable String sinceId
    ) {
        String cursor = sinceId;
        String latestTxId = null;
        int total = 0;

        while (true) {
            List<MonzoTransactionResponse> page = monzoClient.getTransactions(
                    accessToken, account.getId(), since, before, cursor, PAGE_SIZE
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

        log.debug("Synced {} transactions for account {} [since={}, before={}]",
                total, account.getId(), since, before);
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
