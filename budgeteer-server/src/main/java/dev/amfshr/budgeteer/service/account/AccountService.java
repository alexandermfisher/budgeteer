package dev.amfshr.budgeteer.service.account;

import dev.amfshr.budgeteer.api.account.dto.AccountResponse;
import dev.amfshr.budgeteer.api.account.dto.AccountSummaryResponse;
import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.repository.AccountRepository;
import dev.amfshr.budgeteer.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Read side for domain bank accounts. Balances are stored provider snapshots — this service
 * never derives them from transactions (L3).
 */
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /** All of a user's bank accounts, ordered display_order then created_at. */
    public List<AccountResponse> listAccounts(UUID userId, boolean includeArchived) {
        List<Account> accounts = includeArchived
                ? accountRepository.findByUserId(userId)
                : accountRepository.findActiveByUserId(userId);
        return accounts.stream().map(this::toResponse).toList();
    }

    /**
     * Spending summary for one account: today / Monday-start week / month-to-date, boundaries
     * computed in the given zone. Sums include PENDING rows (real spend) and exclude
     * excluded_from_analytics; out is returned as a positive magnitude.
     *
     * @throws ApiException RESOURCE_NOT_FOUND for an unknown or other-user's account
     */
    public AccountSummaryResponse getSummary(UUID userId, UUID accountId, ZoneId zone) {
        accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Account not found: " + accountId));

        LocalDate today = LocalDate.now(zone);
        Instant todayStart = today.atStartOfDay(zone).toInstant();
        Instant weekStart = today.with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant();
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        // A week can start in the previous month (and vice versa) — the query floor is the earlier.
        Instant floor = weekStart.isBefore(monthStart) ? weekStart : monthStart;

        TransactionRepository.WindowSumsProjection sums = transactionRepository.sumWindows(
                userId, accountId, todayStart, weekStart, monthStart, floor);

        return new AccountSummaryResponse(
                accountId, zone.getId(),
                new AccountSummaryResponse.WindowSums(sums.getTodayIn(), Math.abs(sums.getTodayOut())),
                new AccountSummaryResponse.WindowSums(sums.getWeekIn(), Math.abs(sums.getWeekOut())),
                new AccountSummaryResponse.WindowSums(sums.getMonthIn(), Math.abs(sums.getMonthOut())));
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getProvider().name(),
                account.getAccountType().name(),
                account.getInstitutionName(),
                account.getDisplayName(),
                account.getCurrency(),
                account.getBalanceMinorUnits(),
                account.getBalanceAsOf(),
                account.getCreditLimitMinorUnits(),
                account.getDisplayOrder(),
                account.isArchived());
    }
}
