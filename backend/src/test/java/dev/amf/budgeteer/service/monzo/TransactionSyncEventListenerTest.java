package dev.amf.budgeteer.service.monzo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionSyncEventListener")
class TransactionSyncEventListenerTest {

    @Mock private TransactionSyncService syncService;

    @InjectMocks
    private TransactionSyncEventListener listener;

    @Test
    @DisplayName("triggers backfill on event")
    void triggersBackfill() {
        UUID connectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        listener.onConnectionCreated(new MonzoConnectionCreatedEvent(connectionId, userId));

        verify(syncService).backfill(connectionId);
    }

    @Test
    @DisplayName("swallows exception — backfill failure does not propagate")
    void swallowsException() {
        UUID connectionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        doThrow(new RuntimeException("network error")).when(syncService).backfill(connectionId);

        listener.onConnectionCreated(new MonzoConnectionCreatedEvent(connectionId, userId));

        verify(syncService).backfill(connectionId);
    }
}
