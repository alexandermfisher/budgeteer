package dev.amfshr.budgeteer.provider.model;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

/**
 * Provenance envelope: a domain value plus the verbatim provider JSON it was mapped from.
 * Keeps raw payloads out of domain records ({@code equals}/{@code hashCode} stay pure) while
 * making provenance visible in type signatures.
 *
 * @param payload the mapped domain value, never null
 * @param rawJson verbatim provider JSON for this element; null if unavailable. Never log it —
 *                {@link #toString()} redacts it.
 */
public record Sourced<T>(T payload, @Nullable String rawJson) {

    public Sourced {
        Objects.requireNonNull(payload, "payload must not be null");
    }

    /** Transform the payload; keep the provenance. */
    public <R> Sourced<R> map(Function<? super T, ? extends R> fn) {
        return new Sourced<>(fn.apply(payload), rawJson);
    }

    @Override
    public String toString() {
        return "Sourced[" + payload + ", rawJson=" + (rawJson == null ? "null" : "<redacted>") + "]";
    }
}
