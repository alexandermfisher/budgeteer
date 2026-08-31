package dev.amfshr.budgeteer.api.account;

import dev.amfshr.budgeteer.api.account.dto.AccountResponse;
import dev.amfshr.budgeteer.api.account.dto.AccountSummaryResponse;
import dev.amfshr.budgeteer.api.common.ApiResponse;
import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.security.CurrentUserId;
import dev.amfshr.budgeteer.service.account.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Bank-account reads. All responses are user-scoped; an unknown or other-user's account id is a
 * 404 (never a 403 — existence is not confirmed).
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Validated
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** GET /api/v1/accounts — the user's accounts, ordered display_order then created_at. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<AccountResponse>>> listAccounts(
            @CurrentUserId UUID userId,
            @RequestParam(defaultValue = "false") boolean includeArchived
    ) {
        return ResponseEntity.ok(ApiResponse.of(accountService.listAccounts(userId, includeArchived)));
    }

    /** GET /api/v1/accounts/{id}/summary — today / week / month-to-date sums in {@code zone}. */
    @GetMapping("/{id}/summary")
    public ResponseEntity<ApiResponse<AccountSummaryResponse>> getSummary(
            @CurrentUserId UUID userId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Europe/London") String zone
    ) {
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "invalid zone: " + zone);
        }
        return ResponseEntity.ok(ApiResponse.of(accountService.getSummary(userId, id, zoneId)));
    }
}
