package dev.amf.budgeteer.api.dev;

import dev.amf.budgeteer.api.common.ApiResponse;
import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.exception.ApiException;
import dev.amf.budgeteer.repository.MonzoConnectionRepository;
import dev.amf.budgeteer.security.CurrentUserId;
import dev.amf.budgeteer.service.monzo.TransactionSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
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

    public DevMonzoController(TransactionSyncService transactionSyncService,
                              MonzoConnectionRepository connectionRepository) {
        this.transactionSyncService = transactionSyncService;
        this.connectionRepository = connectionRepository;
    }

    /**
     * Triggers a full transaction backfill for the authenticated user's active Monzo connection.
     * Useful during development to re-sync without re-doing the real OAuth flow.
     *
     * <p>The Monzo tokens are already stored encrypted in {@code monzo_connections} from the
     * original OAuth. This endpoint finds the active connection by {@code userId} and runs
     * {@code backfill()} synchronously, so the response waits for full completion.
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
}
