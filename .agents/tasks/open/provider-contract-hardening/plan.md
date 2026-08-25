# Provider Contract Hardening — explicit delta capability + `Sourced<T>` envelope

> **Priority:** 🟡 P2 | **Estimate:** 0.5–1d | **Status:** In Progress
> **Branch:** `refactor/provider-delta-and-sourced` (off `main`) | **Lands before #11**

## Goal

Two contract refactors that #11's ingest pipeline should build on top of, not be rewritten for:

1. **Explicit delta capability.** `deltaSync` currently seeds `pageCursor` with the stored
   `lastTransactionId` — legitimate *behaviour* (Monzo's API genuinely supports id-based
   deltas via `since`), but smuggled through a parameter the contract declares opaque. Make it
   contractual: an opt-in capability interface for id-based deltas that Monzo implements;
   providers that can't (TrueLayer: date-windowed, no cursors) simply don't implement it and
   the server uses a time-window delta via the existing `TransactionsCapability` instead.
   Explicit pick-and-choose per provider — same philosophy as the PR #84 capability split.
2. **`Sourced<T>` envelope.** Move `rawJson` off the domain records into a generic
   provenance wrapper, so `BankTransaction`/`BankAccount` become pure domain values
   (no JSON blob in `equals`/`hashCode`, no raw payload reachable from code that only wants
   domain data) and provenance is visible in type signatures.

**Working mode:** pair session. Alexander writes the `Sourced<T>` envelope (Java practice:
records, generic wrappers, `Function<? super T, ? extends R>` PECS bounds); Claude reviews.
Delta capability: either side drives, other reviews.

## Design sketch

```java
// provider-api — new capability, Monzo-only for now
public interface TransactionsSinceIdCapability {
    /**
     * Delta fetch: transactions strictly after the given transaction id, paged like
     * TransactionsCapability (replay nextCursor until null). For providers whose API
     * natively supports id-based deltas.
     */
    BankTransactionPage getTransactionsSince(String accessToken, String accountId,
                                             String sinceTransactionId, @Nullable String pageCursor);
}

// provider-api/model — provenance envelope
public record Sourced<T>(T payload, @Nullable String rawJson) {
    public <R> Sourced<R> map(Function<? super T, ? extends R> fn) { … }
    // toString redacts rawJson
}
```

- `BankTransactionPage` becomes `(List<Sourced<BankTransaction>> transactions, @Nullable String nextCursor)`.
- `AccountsCapability.getAccounts` returns `List<Sourced<BankAccount>>`.
- `BankTransaction` / `BankAccount` drop their `rawJson` field (and their redacting `toString` overrides).
- `deltaSync` routes: provider implements `TransactionsSinceIdCapability` → seed with stored
  `lastTransactionId` (now contract-legal). Otherwise → time-window delta
  (`from = lastSyncedAt − overlap`, null cursor, idempotent upserts absorb the overlap).
  The fallback path needs a persisted last-synced timestamp — **defer its implementation to
  TrueLayer** (no consumer until then); this task only documents the routing and keeps the
  fallback branch honest (fail loudly, don't silently misuse ids).

## Scope

- [ ] `TransactionsSinceIdCapability` in `provider-api`; Monzo implements (maps id → `since`,
      same paging as `getTransactions`)
- [ ] `TransactionSyncService.deltaSync` calls the new capability explicitly (injected as
      `TransactionsSinceIdCapability`, no more id-as-`pageCursor`)
- [ ] `Sourced<T>` record in `provider-api` `.model` (Alexander writes, Claude reviews)
- [ ] `rawJson` off `BankTransaction`/`BankAccount`; `BankTransactionPage` + `getAccounts`
      carry `Sourced<…>`; `MonzoAccountInformationProvider.mapArray` returns envelopes
- [ ] Update provider + server tests; `mvn verify` green
- [ ] PR → auto-merge to `main`; rebase `feature/domain-model-mapping`; touch up #11 spec
      (`rawJson` consumption becomes `sourced.rawJson()`, `tx` becomes `sourced.payload()`)
