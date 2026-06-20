# Refactor OAuth Callback to Event-Driven

> **Priority:** P3 | **Estimate:** 0.5d | **Status:** Backlog
> **Created:** 2026-06-13 (during resumable backfill work)

## Goal

Replace the direct `syncService.backfillAsync(connection.getId())` call in `MonzoController.handleCallback` with an `ApplicationEventPublisher` publishing a `MonzoConnectionCreatedEvent`. Side effects (currently just transaction backfill) move into `@TransactionalEventListener @Async` listeners.

## Why now (well — why later)

Today the controller has ONE post-OAuth side effect (backfill), so a direct `@Async` call is simpler and clearer. Pulling this trigger is justified once a second side effect lands. **Likely triggers:**

1. **Webhook registration** — register a Monzo webhook for `transaction.created` events on the new connection so we can do real-time sync instead of polling. (Almost certainly happening — already mentioned multiple times.)
2. **Welcome email** — send a "Monzo connected!" confirmation to the user. (Plausible if/when notification UX lands.)
3. **Audit log** — emit a structured "connection.created" event for compliance / observability. (Plausible when monitoring infrastructure ticket lands.)

Once we have two of these, the controller starts to grow a `// after OAuth` list of synchronous-looking calls that are actually fire-and-forget. That's the moment to refactor.

## Approach

### Event

```java
public record MonzoConnectionCreatedEvent(UUID connectionId, UUID userId) {}
```

### Controller change

```java
// Before
syncService.backfillAsync(connection.getId());

// After
eventPublisher.publishEvent(
    new MonzoConnectionCreatedEvent(connection.getId(), user.getId())
);
```

### Listener (replaces what backfillAsync used to do)

```java
@Component
public class TransactionSyncEventListener {

    private final TransactionSyncService syncService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onConnectionCreated(MonzoConnectionCreatedEvent event) {
        // Existing 2s startup delay + 8 retries on MONZO_API_ERROR stays here
        syncService.backfillWithRetries(event.connectionId());
    }
}
```

The retry-loop body of the current `backfillAsync` becomes either a new public method on `TransactionSyncService` (`backfillWithRetries`) or stays as-is and we just rename the listener method. The `@Async` annotation moves from the service method to the listener.

### Other listeners (added when needed)

```java
@Component
public class MonzoWebhookEventListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onConnectionCreated(MonzoConnectionCreatedEvent event) {
        webhookService.registerWebhook(event.connectionId());
    }
}

@Component
public class MonzoNotificationEventListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onConnectionCreated(MonzoConnectionCreatedEvent event) {
        emailService.sendConnectedNotification(event.userId());
    }
}
```

## Wins from the refactor

1. **Transaction safety** — `@TransactionalEventListener(AFTER_COMMIT)` guarantees the connection row is committed before any listener fires. Today this works by accident (the `@Transactional` on `createConnection` commits before `backfillAsync` is invoked on its async thread), but it's brittle if we ever wrap `handleCallback` itself in `@Transactional`.
2. **Decoupling** — controller doesn't need to know about every subsystem that reacts to OAuth completion.
3. **Test isolation** — controller tests assert the event was published; listener tests test side effects independently.
4. **Future-proof** — adding the 3rd, 4th, Nth listener is one new class, zero controller changes.

## Caveats

1. **Async listener exceptions silently swallowed** — set up `AsyncUncaughtExceptionHandler` or use `Future<?>` return + structured logging. (We already have the retry loop, so this is partly handled.)
2. **Indirection cost** — `Cmd-click` from controller to "what happens next" no longer works; you have to grep. Mitigate with a comment on the `publishEvent` line listing known listeners.
3. **One transaction per listener** — each `@Async @TransactionalEventListener` runs in its own transaction. Backfill currently runs in a single transaction; the new behaviour is the same.

## Files to touch

| File | Change |
|------|--------|
| `backend/src/main/java/dev/amf/budgeteer/event/MonzoConnectionCreatedEvent.java` | NEW record |
| `backend/src/main/java/dev/amf/budgeteer/event/TransactionSyncEventListener.java` | NEW — wraps current `backfillAsync` retry loop |
| `backend/src/main/java/dev/amf/budgeteer/api/monzo/MonzoController.java` | Inject `ApplicationEventPublisher`, replace `syncService.backfillAsync(...)` with `eventPublisher.publishEvent(...)` |
| `backend/src/main/java/dev/amf/budgeteer/service/monzo/TransactionSyncService.java` | Move retry loop to a new public method (or keep `backfillAsync` and just stop the `@Async`); drop `@Async` from there |
| `backend/src/test/java/.../MonzoControllerTest.java` | Replace "verify backfillAsync called" with "verify event published" |
| New listener test | Test the listener calls backfill on event |
| `docs/testing/MONZO-TRANSACTION-SYNC-TESTING.md` | Update §4 expected log lines (thread names will change) |

## Verification

- Existing `MonzoTransactionSyncIT` should still pass — it exercises `syncService.backfill()` directly, doesn't care how it's triggered.
- Add a small Spring Boot test verifying: `MonzoController.handleCallback` publishes `MonzoConnectionCreatedEvent` → `TransactionSyncEventListener` invokes `syncService` after commit.
- Manual: re-run the §3 OAuth flow in `MONZO-TRANSACTION-SYNC-TESTING.md` and confirm backfill still fires (log lines move from `taskExecutor-N` to whatever the listener's executor is named).

## Reference

See the chat discussion on 2026-06-13 — "@Async vs MonzoConnectionCreatedEvent / TransactionSyncEventListener — what is better here". The decision was: stay direct for now since only one side effect exists, refactor when the second one lands.
