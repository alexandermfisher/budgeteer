package dev.amfshr.budgeteer.api.transaction;

import dev.amfshr.budgeteer.api.common.ApiResponse;
import dev.amfshr.budgeteer.api.common.PageResponse;
import dev.amfshr.budgeteer.api.transaction.dto.TransactionResponse;
import dev.amfshr.budgeteer.security.CurrentUserId;
import dev.amfshr.budgeteer.service.transaction.TransactionQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Filtered, paged transaction reads. {@code from}/{@code to} are ISO-8601 instants, half-open
 * {@code [from, to)}; page/size use the house {@link PageResponse} envelope.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Validated
public class TransactionController {

    private final TransactionQueryService transactionQueryService;

    public TransactionController(TransactionQueryService transactionQueryService) {
        this.transactionQueryService = transactionQueryService;
    }

    /** GET /api/v1/transactions — the user's transactions, newest first. */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<TransactionResponse>>> list(
            @CurrentUserId UUID userId,
            @RequestParam(required = false) @Nullable UUID accountId,
            @RequestParam(required = false) @Nullable Instant from,
            @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size
    ) {
        return ResponseEntity.ok(ApiResponse.of(
                transactionQueryService.list(userId, accountId, from, to, page, size)));
    }
}
