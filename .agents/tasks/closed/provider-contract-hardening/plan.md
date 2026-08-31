# Provider Contract Hardening — explicit delta capability + `Sourced<T>` envelope

> **Priority:** 🟡 P2 | **Estimate:** 0.5–1d | **Status:** ✅ Done — PR #85 merged 2026-08-31
> **Branch:** `refactor/provider-delta-and-sourced` (off `main`) | **Landed before #11**

## Goal

Two contract refactors that #11's ingest pipeline should build on top of, not be rewritten for:

1. **Explicit delta position.** `deltaSync` currently seeds `pageCursor` with the stored
   `lastTransactionId` — legitimate *behaviour* (Monzo's API genuinely supports id-based
   deltas via `since`), but smuggled through a parameter the contract declares opaque. Make it
   contractual — **design revised 2026-08-31 (Alexander's proposal)**: instead of a second
   capability interface, model the fetch start point as a sealed `SyncPosition` hierarchy
   (`FromTime` | `AfterTransaction` | `NextPage`) passed to the single
   `TransactionsCapability.getTransactions`. Rationale: Monzo's `since` param natively accepts
   all three shapes, so the type models the API; and a sealed switch is *stronger* than
   capability discovery — when TrueLayer arrives, its impl won't compile until it explicitly
   decides what `AfterTransaction` does (throw loudly). The opaque page cursor folds into the
   same hierarchy because it is just another answer to "fetch since where?".
2. **`Sourced<T>` envelope.** Move `rawJson` off the domain records into a generic
   provenance wrapper, so `BankTransaction`/`BankAccount` become pure domain values
   (no JSON blob in `equals`/`hashCode`, no raw payload reachable from code that only wants
   domain data) and provenance is visible in type signatures.

**Working mode:** pair session. Alexander writes the `Sourced<T>` envelope (Java practice:
records, generic wrappers, `Function<? super T, ? extends R>` PECS bounds); Claude reviews.
Delta capability: either side drives, other reviews.

## Design sketch (as implemented)

```java
// provider-api/model — sealed fetch-start position
public sealed interface SyncPosition {
    record FromTime(Instant from) implements SyncPosition {}              // window start / time delta
    record AfterTransaction(String transactionId) implements SyncPosition {} // id-based delta
    record NextPage(String cursor) implements SyncPosition {}             // opaque page resume
}

// provider-api — single transactions contract, no second interface
BankTransactionPage getTransactions(String accessToken, String accountId,
                                    SyncPosition position, Instant to);

// provider-api/model — provenance envelope
public record Sourced<T>(T payload, @Nullable String rawJson) {
    public <R> Sourced<R> map(Function<? super T, ? extends R> fn) { … }
    // toString redacts rawJson
}
```

- Monzo impl: exhaustive switch — all three kinds map onto the `since` query param.
- `BankTransactionPage` becomes `(List<Sourced<BankTransaction>> transactions, @Nullable String nextCursor)`.
- `AccountsCapability.getAccounts` returns `List<Sourced<BankAccount>>`.
- `BankTransaction` / `BankAccount` drop their `rawJson` field (and their redacting `toString` overrides).
- `deltaSync` routes explicitly: stored `lastTransactionId` → `AfterTransaction`; none stored →
  `FromTime(floor)` full fetch. Providers without id-based deltas (TrueLayer: date-windowed)
  must throw on `AfterTransaction` — the sealed switch forces that decision at compile time.
  Time-window delta with persisted last-synced timestamp — **defer to TrueLayer** (no consumer
  until then).

## Scope

- [x] Sealed `SyncPosition` in `provider-api` `.model`; `TransactionsCapability.getTransactions`
      takes `(position, to)`; Monzo switches exhaustively (all kinds → `since`)
- [x] `TransactionSyncService.deltaSync` seeds `AfterTransaction(lastTransactionId)` explicitly
      (no more id-as-`pageCursor`); backfill windows open with `FromTime`, resume with `NextPage`
- [x] `Sourced<T>` record in `provider-api` `.model` (Claude drove — lessons parked; Lesson 2
      exercise C now reads as a review exercise)
- [x] `rawJson` off `BankTransaction`/`BankAccount`; `BankTransactionPage` + `getAccounts`
      carry `Sourced<…>`; `MonzoAccountInformationProvider.mapArray` returns envelopes
- [x] Update provider + server tests; `mvn verify` green (587 server tests + provider modules;
      redaction coverage moved to `SourcedTest`, new `AfterTransaction → since` WireMock test)
- [x] PR → auto-merge to `main` (PR #85, squash-merged 2026-08-31); `feature/domain-model-mapping`
      rebased (board-doc commits resolved toward main's newer copies; unique content — TrueLayer
      OpenAPI docs, glossary, log-sanitize fix — preserved); #11 spec touched up
      (`rawJson` consumption is `sourced.rawJson()`, fetch start is sealed `SyncPosition`)
