package dev.amfshr.budgeteer.service.ingest;

import dev.amfshr.budgeteer.domain.account.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IngestService")
class IngestServiceTest {

    @Mock private ProviderIngestor ingestor;
    @Mock private PlatformTransactionManager txManager;

    private IngestService service;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new IngestService(List.of(ingestor), txManager);
    }

    @Test
    @DisplayName("ingests accounts first, then transactions per account")
    void ingestsAccountsThenTransactionsPerIngestor() {
        Account a1 = mock(Account.class);
        Account a2 = mock(Account.class);
        when(ingestor.ingestAccounts()).thenReturn(List.of(a1, a2));

        service.ingestAll();

        InOrder inOrder = inOrder(ingestor);
        inOrder.verify(ingestor).ingestAccounts();
        inOrder.verify(ingestor).ingestTransactions(a1);
        inOrder.verify(ingestor).ingestTransactions(a2);
    }

    @Test
    @DisplayName("one account's failure never loses another account's ingest")
    void accountFailureIsolated_othersStillIngested() {
        Account a1 = mock(Account.class);
        Account a2 = mock(Account.class);
        when(ingestor.ingestAccounts()).thenReturn(List.of(a1, a2));
        doThrow(new RuntimeException("boom")).when(ingestor).ingestTransactions(a1);

        service.ingestAll();

        verify(ingestor).ingestTransactions(a2);
    }
}
