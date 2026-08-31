package dev.amfshr.budgeteer.service.account;

import dev.amfshr.budgeteer.api.account.dto.AccountResponse;
import dev.amfshr.budgeteer.api.account.dto.AccountSummaryResponse;
import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.account.AccountType;
import dev.amfshr.budgeteer.domain.account.Provider;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.repository.AccountRepository;
import dev.amfshr.budgeteer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccountService")
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;

    @InjectMocks
    private AccountService service;

    private UUID userId;
    private UUID accountId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        user = mock(User.class);
        when(user.getId()).thenReturn(userId);
    }

    @Test
    @DisplayName("lists active accounts via the active-only ordered query")
    void listsActiveOrdered() {
        Account account = domainAccount("acc_1");
        when(accountRepository.findActiveByUserId(userId)).thenReturn(List.of(account));

        List<AccountResponse> result = service.listAccounts(userId, false);

        verify(accountRepository).findActiveByUserId(userId);
        verify(accountRepository, never()).findByUserId(any());
        assertThat(result).hasSize(1);
        AccountResponse response = result.getFirst();
        assertThat(response.provider()).isEqualTo("MONZO");
        assertThat(response.accountType()).isEqualTo("CURRENT");
        assertThat(response.institutionName()).isEqualTo("Monzo");
        assertThat(response.currency()).isEqualTo("GBP");
        assertThat(response.archived()).isFalse();
    }

    @Test
    @DisplayName("includeArchived returns all accounts")
    void includeArchivedReturnsAll() {
        Account archived = domainAccount("acc_1");
        archived.archive();
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(archived));

        List<AccountResponse> result = service.listAccounts(userId, true);

        verify(accountRepository).findByUserId(userId);
        assertThat(result.getFirst().archived()).isTrue();
    }

    @Test
    @DisplayName("summary computes window boundaries in the given zone")
    void summaryComputesWindowBoundariesInZone() {
        ZoneId zone = ZoneId.of("Pacific/Auckland");
        when(accountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(domainAccount("acc_1")));
        when(transactionRepository.sumWindows(any(), any(), any(), any(), any(), any()))
                .thenReturn(sums(0, 0, 0, 0, 0, 0));

        service.getSummary(userId, accountId, zone);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(transactionRepository).sumWindows(eq(userId), eq(accountId),
                captor.capture(), captor.capture(), captor.capture(), captor.capture());
        Instant todayStart = captor.getAllValues().get(0);
        Instant weekStart = captor.getAllValues().get(1);
        Instant monthStart = captor.getAllValues().get(2);
        Instant floor = captor.getAllValues().get(3);

        ZonedDateTime week = weekStart.atZone(zone);
        assertThat(week.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(week.toLocalTime().toSecondOfDay()).isZero();          // start of day in zone
        assertThat(monthStart.atZone(zone).getDayOfMonth()).isEqualTo(1);
        assertThat(todayStart.atZone(zone).toLocalTime().toSecondOfDay()).isZero();
        assertThat(floor).isEqualTo(weekStart.isBefore(monthStart) ? weekStart : monthStart);
    }

    @Test
    @DisplayName("summary converts out sums to positive magnitude")
    void summaryConvertsOutToPositive() {
        when(accountRepository.findByIdAndUserId(accountId, userId))
                .thenReturn(Optional.of(domainAccount("acc_1")));
        when(transactionRepository.sumWindows(any(), any(), any(), any(), any(), any()))
                .thenReturn(sums(1000, -250, 5000, -1250, 20000, -7500));

        AccountSummaryResponse summary = service.getSummary(userId, accountId, ZoneId.of("Europe/London"));

        assertThat(summary.today().inMinorUnits()).isEqualTo(1000);
        assertThat(summary.today().outMinorUnits()).isEqualTo(250);
        assertThat(summary.thisWeek().outMinorUnits()).isEqualTo(1250);
        assertThat(summary.monthToDate().outMinorUnits()).isEqualTo(7500);
    }

    @Test
    @DisplayName("unknown or foreign account id is a 404")
    void unknownOrForeignAccount_404() {
        when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSummary(userId, accountId, ZoneId.of("Europe/London")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ============ Helpers ============

    private Account domainAccount(String providerAccountId) {
        return new Account(user, Provider.MONZO, providerAccountId, AccountType.CURRENT,
                "Monzo", "Current", "GBP");
    }

    private TransactionRepository.WindowSumsProjection sums(
            long todayIn, long todayOut, long weekIn, long weekOut, long monthIn, long monthOut) {
        return new TransactionRepository.WindowSumsProjection() {
            @Override public long getTodayIn() { return todayIn; }

            @Override public long getTodayOut() { return todayOut; }

            @Override public long getWeekIn() { return weekIn; }

            @Override public long getWeekOut() { return weekOut; }

            @Override public long getMonthIn() { return monthIn; }

            @Override public long getMonthOut() { return monthOut; }
        };
    }
}
