package dev.amf.budgeteer.api.dev;

import dev.amf.budgeteer.api.common.ApiResponse;
import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.domain.monzo.MonzoAccount;
import dev.amf.budgeteer.exception.ApiException;
import dev.amf.budgeteer.repository.MonzoAccountRepository;
import dev.amf.budgeteer.repository.MonzoConnectionRepository;
import dev.amf.budgeteer.repository.MonzoTransactionRepository;
import dev.amf.budgeteer.security.CurrentUserId;
import dev.amf.budgeteer.service.monzo.TransactionSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Development-only Monzo endpoints.
 *
 * <p><strong>⚠️ WARNING: This controller only exists in the 'dev' profile!</strong>
 * It will NOT be available in production.
 */
@RestController
@RequestMapping("/api/dev/monzo")
@Profile("dev")
public class DevMonzoController {

    private static final Logger log = LoggerFactory.getLogger(DevMonzoController.class);

    private final TransactionSyncService transactionSyncService;
    private final MonzoConnectionRepository connectionRepository;
    private final MonzoAccountRepository accountRepository;
    private final MonzoTransactionRepository transactionRepository;

    public DevMonzoController(TransactionSyncService transactionSyncService,
                              MonzoConnectionRepository connectionRepository,
                              MonzoAccountRepository accountRepository,
                              MonzoTransactionRepository transactionRepository) {
        this.transactionSyncService = transactionSyncService;
        this.connectionRepository = connectionRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Triggers a full transaction backfill for the authenticated user's active Monzo connection.
     * Useful during development to re-sync without re-doing the real OAuth flow.
     *
     * <p>POST /api/dev/monzo/backfill
     */
    @PostMapping("/backfill")
    public ResponseEntity<ApiResponse<Void>> triggerBackfill(@CurrentUserId UUID userId) {
        log.warn("DEV: Triggering manual transaction backfill for user {}", userId);

        var connection = connectionRepository
                .findActiveByUserId(userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No active Monzo connection found for user " + userId));

        transactionSyncService.backfill(connection.getId());

        return ResponseEntity.ok(ApiResponse.of(null));
    }

    /**
     * Resets backfill progress for a specific account and deletes all its transactions.
     * Useful for re-testing the full backfill flow without recreating the database.
     *
     * <p>POST /api/dev/monzo/reset-backfill/{accountId}
     */
    @PostMapping("/reset-backfill/{accountId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> resetBackfill(
            @CurrentUserId UUID userId,
            @PathVariable String accountId
    ) {
        log.warn("DEV: Resetting backfill for account {} (user {})", accountId, userId);

        MonzoAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Account not found: " + accountId));

        if (!account.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Account does not belong to this user");
        }

        transactionRepository.deleteByAccountId(accountId);

        account.setBackfillStatus(null);
        account.setBackfillProgressAt(null);
        account.setBackfillProgressCursor(null);
        accountRepository.save(account);

        log.warn("DEV: Backfill reset complete for account {} — {} transactions deleted", accountId, accountId);
        return ResponseEntity.ok(ApiResponse.of(null));
    }
}
