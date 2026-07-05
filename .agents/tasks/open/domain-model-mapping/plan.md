# Domain Model Mapping — Raw → Domain Ingest + First Product Endpoints

> **Status:** Queue (#11) — design complete, implementation spec pending
> **Priority:** P2 | **Estimate:** 2–3d (slice 1) | **Depends on:** #10 (contract `rawJson` + `getBalance`)
> **Next action:** `/grill-me domain-model-mapping` to turn the design into an implementation-ready spec

## Goal

Build the provider-agnostic domain layer above the raw `monzo_*` tables: unified
`user_accounts` / `transactions`, the raw→domain mapping pipeline, and the first product-facing
endpoints. This unblocks every budgeting feature (categories, budgets, virtual pots, reports).

## Inputs

- `.agents/notes/domain-model-design.md` — the full domain design: entities with stored/computed
  field annotations, stored-vs-computed tradeoff table, service/controller/mapper breakdown,
  migration list, five-slice build order
- `.agents/notes/fable-domain-design-prompt.md` — the design brief it answered

## Decisions already locked (do not re-litigate in /grill-me; deepen, don't reopen)

- **Domain is a separate layer**, not a rename of the raw tables — `monzo_*` stays as the
  provider-shaped landing zone; new provider-agnostic tables sit above it
- **Idempotent upsert** keyed on `(provider, provider_transaction_id)`; declined transactions are
  never mapped; re-maps never overwrite user-owned fields (`category_id`, edited `notes`,
  `excluded_from_analytics`)
- **Account balance = stored snapshot** fetched from the provider via `BankClient.getBalance`
  (lands in #10) — never derived from transactions (windowed history, pending, credit-card
  semantics make derivation wrong, not just slow)
- **Raw capture — encrypted at rest**: `raw_payload_encrypted TEXT` columns on the raw provider
  tables (NOT plaintext `jsonb`), populated from the contract's `rawJson` (true raw with unknown
  fields preserved — #10) and encrypted with the existing AES-256-GCM `EncryptionService` (same
  pattern as OAuth tokens) at persistence time in `budgeteer-server`. Raw payloads contain bank
  identifiers (`account_number`, `sort_code` on Monzo accounts) — strictly more sensitive than
  the typed columns. Consciously traded away: Postgres JSON operators / GIN indexes on the blob —
  acceptable because raw blobs are never queried for product features (domain tables serve
  those); they're read rarely by app code (audit, one-off field-backfill scripts) which decrypts
  anyway. Monzo history >90d is behind SCA and unrecoverable, so this is the only future
  field-backfill source
- **No pre-aggregation** (`ReportSnapshot` deliberately omitted) — totals are computed on read;
  revisit with a materialised view only if measured slow
- **Mapping code lives in `budgeteer-server`** (`DomainMappingService` + per-provider
  `ProviderDomainMapper` impls), NOT in the client jars — it's inseparable from JPA entities.
  The jars stay dumb API clients

## Scope — slice 1 (this task)

1. Migrations (take the next free `V*` numbers at build time): `raw_payload_encrypted TEXT` on
   `monzo_accounts` + `monzo_transactions`; create `user_accounts`; create `transactions`
   (indexes per the design doc)
2. `Account` / `Transaction` entities + repositories (`domain/account`, `domain/transaction`)
3. `MonzoDomainMapper` (implements `ProviderDomainMapper`) + `DomainMappingService` with a
   per-account mapping cursor
4. Jobs: `DomainMappingJob` chained off the 60-min delta sync; `BalanceRefreshJob` using
   `getBalance` → `Account` snapshot columns
5. Endpoints: `GET /api/v1/accounts`, `GET /api/v1/accounts/{id}/summary`,
   `GET /api/v1/transactions` (filtered/paged)

## Out of scope (later slices — see the design doc's build order)

Categories + categorisation, budgets, reports, virtual pots, category rules, user settings.
Webhooks (#5) land after this task and become a second trigger into this pipeline.
