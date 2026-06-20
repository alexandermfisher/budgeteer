package dev.amf.budgeteer.service.monzo;

import dev.amf.budgeteer.domain.monzo.MonzoAccount;
import dev.amf.budgeteer.repository.MonzoAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionSyncJob")
class TransactionSyncJobTest {

    @Mock private TransactionSyncService syncService;
    @Mock private MonzoAccountRepository accountRepository;

    @InjectMocks
    private TransactionSyncJob job;

    @Test
    @DisplayName("syncs all syncable accounts")
    void syncsAllAccounts() {
        MonzoAccount a1 = mockAccount("acc_001");
        MonzoAccount a2 = mockAccount("acc_002");
        MonzoAccount a3 = mockAccount("acc_003");
        when(accountRepository.findAllSyncable()).thenReturn(List.of(a1, a2, a3));

        job.syncAllAccounts();

        verify(syncService).deltaSync("acc_001");
        verify(syncService).deltaSync("acc_002");
        verify(syncService).deltaSync("acc_003");
    }

    @Test
    @DisplayName("continues when one account throws")
    void continuesOnFailure() {
        MonzoAccount a1 = mockAccount("acc_001");
        MonzoAccount a2 = mockAccount("acc_002");
        MonzoAccount a3 = mockAccount("acc_003");
        when(accountRepository.findAllSyncable()).thenReturn(List.of(a1, a2, a3));
        doThrow(new RuntimeException("sync failed")).when(syncService).deltaSync("acc_002");

        job.syncAllAccounts();

        verify(syncService).deltaSync("acc_001");
        verify(syncService).deltaSync("acc_002");
        verify(syncService).deltaSync("acc_003");
    }

    @Test
    @DisplayName("does nothing when no syncable accounts")
    void noOpWhenEmpty() {
        when(accountRepository.findAllSyncable()).thenReturn(List.of());

        job.syncAllAccounts();

        verifyNoInteractions(syncService);
    }

    private MonzoAccount mockAccount(String id) {
        MonzoAccount account = mock(MonzoAccount.class);
        when(account.getId()).thenReturn(id);
        return account;
    }
}
