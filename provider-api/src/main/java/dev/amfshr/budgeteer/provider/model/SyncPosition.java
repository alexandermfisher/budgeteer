package dev.amfshr.budgeteer.provider.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Where a transaction fetch starts. Sealed so provider implementations pattern-match
 * exhaustively: adding a position kind is a compile error in every provider until it decides
 * how to serve it — or throws. A provider that cannot serve a given kind must fail loudly
 * (throw {@link dev.amfshr.budgeteer.provider.exception.ProviderException}), never reinterpret
 * it as something else.
 *
 * <p>A paged fetch opens with {@link FromTime} or {@link AfterTransaction} and continues by
 * replaying the returned {@link BankTransactionPage#nextCursor()} wrapped in {@link NextPage}
 * until the cursor is null.
 */
public sealed interface SyncPosition {

    /** Start of a time-windowed fetch: transactions at or after {@code from} (backfill window,
     * or time-based delta for providers without id-based deltas). */
    record FromTime(Instant from) implements SyncPosition {
        public FromTime {
            Objects.requireNonNull(from, "from must not be null");
        }
    }

    /** Id-based delta fetch: transactions strictly after a known transaction id. Only for
     * providers whose API natively supports it (Monzo does; date-windowed providers must throw). */
    record AfterTransaction(String transactionId) implements SyncPosition {
        public AfterTransaction {
            Objects.requireNonNull(transactionId, "transactionId must not be null");
        }
    }

    /** Resume within a paged fetch: the previous page's {@code nextCursor}, opaque to the
     * caller — persist and replay it, never interpret it. */
    record NextPage(String cursor) implements SyncPosition {
        public NextPage {
            Objects.requireNonNull(cursor, "cursor must not be null");
        }
    }
}
