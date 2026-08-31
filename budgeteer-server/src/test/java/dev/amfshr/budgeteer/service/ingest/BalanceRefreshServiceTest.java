package dev.amfshr.budgeteer.service.ingest;

import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.account.AccountType;
import dev.amfshr.budgeteer.domain.account.Provider;
import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.provider.BalanceCapability;
import dev.amfshr.budgeteer.provider.exception.ProviderConnectionRevokedException;
import dev.amfshr.budgeteer.provider.model.BankBalance;
import dev.amfshr.budgeteer.repository.AccountRepository;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.service.monzo.MonzoConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BalanceRefreshService")
class BalanceRefreshServiceTest {

    private static final String TOKEN = "test-token";

    @Mock private MonzoAccountRepository monzoAccountRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private MonzoConnectionService connectionService;
    @Mock private BalanceCapability balanceCapability;

    @InjectMocks
    private BalanceRefreshService service;

    private User user;
    private UUID userId;
    private UUID connectionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        connectionId = UUID.randomUUID();
        user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(connectionService.getDecryptedAccessToken(any(), any())).thenReturn(TOKEN);
    }

    @Test
    @DisplayName("refreshes and stamps the balance snapshot")
    void refreshesAndStampsBalance() {
        MonzoAccount raw = rawAccount("acc_1", connectionId);
        Account domain = domainAccount("acc_1");
        when(monzoAccountRepository.findAllSyncable()).thenReturn(List.of(raw));
        when(accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, "acc_1"))
                .thenReturn(Optional.of(domain));
        when(balanceCapability.getBalance(TOKEN, "acc_1")).thenReturn(new BankBalance(5000, "GBP"));

        service.refreshAll();

        assertThat(domain.getBalanceMinorUnits()).isEqualTo(5000);
        assertThat(domain.getBalanceAsOf()).isNotNull();
        verify(accountRepository).save(domain);
    }

    @Test
    @DisplayName("skips raw accounts the mapping has not created yet")
    void skipsUnmappedRawAccounts() {
        MonzoAccount raw = rawAccount("acc_1", connectionId);
        when(monzoAccountRepository.findAllSyncable()).thenReturn(List.of(raw));
        when(accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, "acc_1"))
                .thenReturn(Optional.empty());

        service.refreshAll();

        verifyNoInteractions(balanceCapability);
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("revoked connection abandons that connection only")
    void revokedConnection_abandonsThatConnectionOnly() {
        UUID otherConnectionId = UUID.randomUUID();
        MonzoAccount raw1a = rawAccount("acc_1a", connectionId);
        MonzoAccount raw1b = rawAccount("acc_1b", connectionId);
        MonzoAccount raw2 = rawAccount("acc_2", otherConnectionId);
        Account domain2 = domainAccount("acc_2");
        when(monzoAccountRepository.findAllSyncable()).thenReturn(List.of(raw1a, raw1b, raw2));
        when(accountRepository.findByProviderAndProviderAccountId(any(), any()))
                .thenAnswer(inv -> Optional.of(
                        inv.getArgument(1).equals("acc_2") ? domain2 : domainAccount(inv.getArgument(1))));
        when(balanceCapability.getBalance(TOKEN, "acc_1a"))
                .thenThrow(new ProviderConnectionRevokedException("revoked"));
        when(balanceCapability.getBalance(TOKEN, "acc_2")).thenReturn(new BankBalance(7000, "GBP"));

        service.refreshAll();

        verify(balanceCapability, never()).getBalance(TOKEN, "acc_1b");
        assertThat(domain2.getBalanceMinorUnits()).isEqualTo(7000);
    }

    @Test
    @DisplayName("per-account errors continue with the rest of the connection")
    void perAccountErrorContinues() {
        MonzoAccount raw1 = rawAccount("acc_1", connectionId);
        MonzoAccount raw2 = rawAccount("acc_2", connectionId);
        Account domain2 = domainAccount("acc_2");
        when(monzoAccountRepository.findAllSyncable()).thenReturn(List.of(raw1, raw2));
        when(accountRepository.findByProviderAndProviderAccountId(any(), any()))
                .thenAnswer(inv -> Optional.of(
                        inv.getArgument(1).equals("acc_2") ? domain2 : domainAccount(inv.getArgument(1))));
        when(balanceCapability.getBalance(TOKEN, "acc_1")).thenThrow(new RuntimeException("boom"));
        when(balanceCapability.getBalance(TOKEN, "acc_2")).thenReturn(new BankBalance(7000, "GBP"));

        service.refreshAll();

        assertThat(domain2.getBalanceMinorUnits()).isEqualTo(7000);
        verify(accountRepository).save(domain2);
    }

    @Test
    @DisplayName("currency mismatch warns but stores anyway (provider is truth)")
    void currencyMismatch_warnsButStores() {
        MonzoAccount raw = rawAccount("acc_1", connectionId);
        Account domain = domainAccount("acc_1");
        when(monzoAccountRepository.findAllSyncable()).thenReturn(List.of(raw));
        when(accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, "acc_1"))
                .thenReturn(Optional.of(domain));
        when(balanceCapability.getBalance(TOKEN, "acc_1")).thenReturn(new BankBalance(9000, "USD"));

        service.refreshAll();

        assertThat(domain.getBalanceMinorUnits()).isEqualTo(9000);
        verify(accountRepository).save(domain);
    }

    // ============ Helpers ============

    private MonzoAccount rawAccount(String id, UUID connId) {
        MonzoAccount raw = mock(MonzoAccount.class);
        MonzoConnection connection = mock(MonzoConnection.class);
        when(connection.getId()).thenReturn(connId);
        when(raw.getId()).thenReturn(id);
        when(raw.getUserId()).thenReturn(userId);
        when(raw.getConnection()).thenReturn(connection);
        return raw;
    }

    private Account domainAccount(String providerAccountId) {
        return new Account(user, Provider.MONZO, providerAccountId, AccountType.CURRENT,
                "Monzo", null, "GBP");
    }
}
