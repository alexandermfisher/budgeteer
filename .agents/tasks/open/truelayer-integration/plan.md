# TrueLayer Integration — Multi-Bank Support (Lloyds, HSBC, Barclays, etc.)

**Status:** 🗂️ Backlog  
**Priority:** P2  
**Effort:** 3–4 days  
**Blocked by:** Monzo direct integration must be stable + tested

---

## Goal

Add support for UK banks via TrueLayer API (Lloyds, HSBC, Barclays, Santander, NatWest, Co-op, etc.) through a unified `BankAdapter` abstraction pattern. Users can connect multiple bank types to a single Budgeteer account.

---

## Why TrueLayer?

- **No regulatory overhead** — TrueLayer is FCA-authorized; you don't manage certificates/OBL registration
- **70+ banks covered** — One integration supports all major UK + EU banks
- **Simple OAuth** — Standard redirect flow, similar to Monzo
- **Pay-as-you-go pricing** — ~£0.01–0.05 per transaction at small scale
- **Better than Yapily for startups** — More developer-friendly, faster integration

---

## Architecture Overview

### Current (Monzo-only)

```
TransactionSyncService
  └── MonzoClient → Monzo API
      └── getAccounts(), getTransactions()
```

### After TrueLayer Integration

```
TransactionSyncService (unchanged)
  └── BankAdapterFactory
      ├── MonzoBankAdapter → MonzoClient → Monzo API
      └── TrueLayerBankAdapter → TrueLayerClient → TrueLayer API
```

**Key principle:** TransactionSyncService doesn't know which bank it's talking to.

---

## Tasks

### Phase 1: Define the Abstraction (0.5d)

- [ ] Create `BankAdapter` interface with methods:
  - `String getOAuthAuthorizationUrl(String state)`
  - `BankConnection exchangeCode(UUID userId, String authCode)`
  - `List<Account> getAccounts(BankConnection connection)`
  - `List<Transaction> getTransactions(BankConnection connection, String accountId, Instant from, Instant to)`
  - `void refreshToken(BankConnection connection)`

- [ ] Create generic domain entities (replaces Monzo-specific ones):
  - `BankConnection` (has `bankProvider: enum { MONZO, TRUELAYER }`)
  - `Account` (id, type, displayName, currency)
  - `Transaction` (id, date, amount, description, merchant, currency)
  - `BankProvider` enum

- [ ] Create `BankAdapterFactory` service

### Phase 2: Wrap Monzo in an Adapter (1d)

- [ ] Create `MonzoBankAdapter implements BankAdapter`
  - Wraps existing `MonzoClient`, `MonzoConnectionService`, `MonzoOAuthService`
  - Converts Monzo DTOs to generic `Account` / `Transaction`
  - Returns Monzo as the bank provider
  - **Keep MonzoClient untouched** — this is a wrapper, not a refactor

- [ ] Create `BankConnectionRepository` (generic, replaces MonzoConnectionRepository in this layer)
  - Still persists to the same `monzo_connections` table initially
  - Add `bank_provider` column (V7 migration)

- [ ] Update `TransactionSyncService` to use `BankAdapterFactory`
  - Change: `monzoClient.getAccounts()` → `adapter.getAccounts(connection)`
  - No logic changes, just swapping the provider

### Phase 3: Implement TrueLayer Adapter (1.5d)

- [ ] Create `TrueLayerClient`
  - OAuth endpoints: `/oauth/token`, `/connect/authorize`
  - Data endpoints: `/accounts`, `/accounts/{id}/transactions`, `/accounts/{id}/balance`
  - Auth: Bearer token in Authorization header
  - Handle pagination, date ranges, error responses

- [ ] Create `TrueLayerBankAdapter implements BankAdapter`
  - OAuth flow (redirect to TrueLayer, handle callback)
  - Convert TrueLayer responses to generic `Account` / `Transaction`
  - Encrypt/decrypt access tokens (reuse `EncryptionService`)

- [ ] Add TrueLayer environment properties
  - `truelayer.client-id`, `truelayer.client-secret`, `truelayer.redirect-uri`
  - Load from env vars or `application.properties`

- [ ] Create integration tests (WireMock)
  - Mock TrueLayer OAuth token endpoint
  - Mock `/accounts` and `/accounts/{id}/transactions` endpoints
  - Test pagination, date range handling, error scenarios

### Phase 4: Plumb into Controllers (0.5d)

- [ ] Update `MonzoController` to be bank-agnostic
  - Rename to `BankConnectionController` (or keep MonzoController and add `/api/banks/connect`)
  - Accept `?provider=MONZO|TRUELAYER` query param in `/connect` endpoint
  - Route to appropriate adapter's OAuth flow

- [ ] Update OAuth callback handler
  - Determine which adapter to use based on state token
  - Call `adapter.exchangeCode()`
  - Trigger transaction backfill (reuses existing `TransactionSyncJob`)

### Phase 5: Testing & Hardening (0.5d)

- [ ] Integration tests for end-to-end flow
  - Monzo OAuth → create connection → sync transactions
  - TrueLayer OAuth → create connection → sync transactions

- [ ] Load testing with multiple banks
  - Ensure backfill handles multiple `BankConnection` types

- [ ] Error scenarios
  - Token revocation
  - Failed refreshes
  - Bank API downtime

---

## Database Migration (V7)

```sql
-- Add bank_provider column to monzo_connections
ALTER TABLE monzo_connections
ADD COLUMN bank_provider VARCHAR(50) NOT NULL DEFAULT 'MONZO';

-- Future: rename table to bank_connections when fully generalized
-- ALTER TABLE monzo_connections RENAME TO bank_connections;
```

> **Note:** Keep table name as `monzo_connections` for now (no disruption). Rename in a later refactor if scope justifies it.

---

## Files to Create

```
backend/src/main/java/dev/amf/budgeteer/
├── domain/bank/                      # New
│   ├── BankConnection.java           # Generic (replaces MonzoConnection at service layer)
│   ├── Account.java
│   ├── Transaction.java
│   └── BankProvider.java (enum)
├── adapter/                          # New
│   └── bank/
│       ├── BankAdapter.java (interface)
│       ├── MonzoBankAdapter.java
│       ├── TrueLayerBankAdapter.java
│       └── BankAdapterFactory.java
├── client/truelayer/                 # New
│   ├── TrueLayerClient.java
│   ├── dto/
│   │   ├── TrueLayerAccountResponse.java
│   │   ├── TrueLayerTransactionResponse.java
│   │   └── TrueLayerTokenResponse.java
│   └── exception/TrueLayerException.java
└── repository/
    └── BankConnectionRepository.java  # New generic repo

backend/src/test/java/...
├── adapter/TrueLayerBankAdapterIT.java
├── client/truelayer/TrueLayerClientIT.java
└── integration/TrueLayerOAuthFlowIT.java

backend/src/main/resources/db/migration/
└── V7__add_bank_provider_column.sql
```

---

## Files to Modify

- `TransactionSyncService.java` — Use `BankAdapterFactory` instead of `MonzoClient`
- `MonzoController.java` — Accept `provider` param, route to appropriate adapter
- `application.properties` — Add TrueLayer config
- `.agents/tasks/tasks.md` — Move from Backlog → Queue when ready

---

## Research / Setup Required (Before Starting)

- [ ] Sign up for TrueLayer Console
- [ ] Get `client_id` and `client_secret`
- [ ] Test OAuth flow in sandbox
- [ ] Review TrueLayer API docs: https://docs.truelayer.com/

---

## Acceptance Criteria

- [ ] Can connect a Lloyds account via TrueLayer
- [ ] Can fetch Lloyds accounts & transactions
- [ ] TransactionSyncService works with both Monzo and TrueLayer accounts without code changes
- [ ] Both adapter types coexist in the same Budgeteer user account
- [ ] Integration tests pass (WireMock mocked TrueLayer API)
- [ ] Token refresh works for both providers
- [ ] No breaking changes to existing Monzo users

---

## Defer (Out of Scope)

- Refactoring `MonzoConnectionService` → generic `BankConnectionService` (complexity vs. benefit trade-off)
- Supporting other aggregators (Yapily, Tink) — TrueLayer covers 70+ banks; add others only if TrueLayer's coverage gaps emerge
- Frontend changes — will be handled when frontend is built

---

## Dependencies

- Monzo integration must be stable (Phase 4 complete)
- `EncryptionService` for token storage (already exists)
- WireMock for IT testing (already in use)

---

## Notes

- **Keep MonzoClient untouched.** The adapter wraps it; no refactoring of existing Monzo code needed for MVP.
- **Incremental rollout:** Monzo users keep working as-is. New users can choose Monzo or TrueLayer banks.
- **Naming:** Could rename `MonzoController` → `BankConnectionController` now, or defer; either works.
- **Research memo:** See `.agents/notes/open-banking-aggregators-2026.md` for TrueLayer vs. Yapily comparison.

