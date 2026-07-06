package dev.amfshr.budgeteer.service.monzo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class TransactionSyncEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionSyncEventListener.class);

    private final TransactionSyncService syncService;

    public TransactionSyncEventListener(TransactionSyncService syncService) {
        this.syncService = syncService;
    }

    @Async("backfillTaskExecutor")
    @EventListener
    public void onConnectionCreated(MonzoConnectionCreatedEvent event) {
        log.info("Backfill triggered [connectionId={}]", event.connectionId());
        try {
            syncService.backfill(event.connectionId());
        } catch (Exception e) {
            log.error("Backfill failed [connectionId={}]: {}", event.connectionId(), e.getMessage(), e);
        }
    }
}
