# Budgeteer Domain Model Design

> Output of the domain-design session (`fable-domain-design-prompt.md`), 2026-07-05.
> Scope: backend domain only. Auth, Monzo client, and module structure are fixed. No frontend decisions.
> Grounded against the real schema: raw sync tables are `monzo_accounts` (V7 — **no balance column**) and `monzo_transactions` (V8 — signed minor-unit amounts, `monzo_settled_at` nullable, `is_declined` flag). Next migration is **V11**.
>
> **Addendum (2026-07-05, same day):** two contract additions were folded into task #10 to support this design — `BankClient.getBalance` → `BankBalance` (feeds the `Account` balance snapshot) and `@Nullable String rawJson` on `BankTransaction`/`BankAccount` (true raw capture via `JsonNode`, persisted **encrypted** — AES-256-GCM via the existing `EncryptionService`, column `raw_payload_encrypted TEXT` — on the **raw** provider tables by the sync layer; raw payloads carry bank identifiers like `account_number`/`sort_code`, so they are never stored plaintext. The domain `transactions` table stays typed and blob-free). Module names below reflect the 2026-07-05 naming scheme: `bank-client-api` / `bank-client-monzo` / `budgeteer-server`.

Conventions used throughout:

- All money is **signed minor units** (`BIGINT`), matching the raw Monzo shape. Negative = money out.
- Every table carries `user_id UUID FK` even though there is one user today — multi-user is then non-breaking.
- All new entities use UUID PKs; provider identity lives in `(provider, provider_*_id)` natural-key columns with unique constraints.
- v0 assumes GBP everywhere; `currency` is stored but cross-currency aggregation is out of scope.

---

## Section A — Domain Entities

Five aggregates: **Account**, **Transaction**, **Category**, **Budget**, **VirtualPot** (+ a small `UserSettings` singleton). Raw provider tables (`monzo_*`) stay as-is; the domain is a separate, provider-agnostic layer fed by mapping.

### Aggregate: Account

**`Account`** (`user_accounts`) — one row per real bank account, any provider.

| Field | Kind | Notes |
|---|---|---|
| `id` UUID PK | stored | |
| `user_id` FK → users | stored | |
| `provider` enum `MONZO` \| `TRUELAYER` | stored | extensible varchar-backed enum |
| `provider_account_id` | stored | unique with `provider`; maps to `monzo_accounts.id` for Monzo |
| `account_type` enum `CURRENT` \| `SAVINGS` \| `CREDIT_CARD` | stored | normalised from provider values at mapping time |
| `institution_name` | stored | "Monzo", "Lloyds" — display label, set by mapper |
| `display_name` | stored | user-editable, defaults to provider description |
| `currency` (ISO 4217) | stored | |
| `balance_minor_units` | stored (snapshot) | see Section B — provider-fetched cache, not derived from transactions |
| `balance_as_of` timestamptz | stored (snapshot) | staleness shown to the UI |
| `credit_limit_minor_units` nullable | stored (snapshot) | credit cards only, from provider |
| `display_order` int | stored | user-controlled ordering on the Connected Accounts page |
| `archived_at` nullable | stored | soft-hide (account closed / disconnected); never hard-delete |
| `created_at` / `updated_at` | stored | |
| month-to-date in / out | **computed** | indexed SUM over `transactions` |
| this-week / today in / out | **computed** | same query, narrower window |

Relationships: 1→N `Transaction`. Deliberately **no FK to `monzo_connections`** — connection lifecycle is provider-specific; the mapper resolves connection → accounts. Disconnecting a bank archives its accounts but keeps history.

### Aggregate: Transaction

**`Transaction`** (`transactions`) — unified, product-facing. Raw rows are mapped in; declined transactions are **never mapped** (they don't affect balance or spend).

| Field | Kind | Notes |
|---|---|---|
| `id` UUID PK | stored | |
| `user_id` FK | stored | |
| `account_id` FK → user_accounts | stored | |
| `provider` + `provider_transaction_id` | stored | unique pair — idempotent upsert key for re-sync/webhook replays |
| `amount_minor_units` (signed) | stored | |
| `currency` | stored | |
| `status` enum `PENDING` \| `SETTLED` | stored | from `monzo_settled_at IS NULL`; updated on re-map |
| `description` | stored | provider description |
| `merchant_name` nullable | stored | |
| `merchant_category` nullable | stored | provider hint (Monzo category) — input to rules, never shown as *the* category |
| `notes` nullable | stored | user-editable, seeded from provider notes |
| `occurred_at` timestamptz | stored | provider created time — the reporting date |
| `settled_at` nullable | stored | |
| `category_id` nullable FK → categories | stored | one category per transaction (per requirements) |
| `categorisation_source` enum `MANUAL` \| `RULE` \| `NONE` | stored | lets rules re-run without clobbering manual choices |
| `excluded_from_analytics` bool default false | stored | for internal transfers (Monzo→Lloyds, credit-card payments) — manual toggle in v0, auto-detection is icebox |
| direction (in/out) | **computed** | sign of amount |
| `created_at` / `updated_at` | stored | |

Indexes: `(user_id, occurred_at DESC)`, `(user_id, category_id, occurred_at)`, `(account_id, occurred_at DESC)`, unique `(provider, provider_transaction_id)`.

**Double-counting rule (mapping doc, not schema):** credit-card spend counts as spend when it happens; the later current-account payment *to* the card is an internal transfer → `excluded_from_analytics = true`.

### Aggregate: Category

**`Category`** (`categories`) — user-defined taxonomy (Bills, Snooker, Food, Eat Out, Travel, Holiday…).

| Field | Kind | Notes |
|---|---|---|
| `id` UUID PK, `user_id` FK | stored | |
| `name` | stored | unique per (user, parent) |
| `parent_category_id` nullable self-FK | stored | **one level max** — enforce in service, not DB recursion |
| `icon`, `colour` nullable | stored | display hints only |
| `archived_at` nullable | stored | archiving keeps historical transactions/reports intact; never delete a category with transactions |

**`CategoryRule`** (`category_rules`) — auto-suggest input, evaluated in priority order, first match wins.

| Field | Kind | Notes |
|---|---|---|
| `id` UUID PK, `user_id` FK, `category_id` FK | stored | |
| `match_type` enum `MERCHANT_NAME_EQUALS` \| `DESCRIPTION_CONTAINS` \| `MERCHANT_CATEGORY_EQUALS` | stored | three types only in v0 |
| `match_value` | stored | case-insensitive compare |
| `priority` int | stored | |
| `enabled` bool | stored | |

v0 behaviour: rules run **on demand** ("suggest" at categorisation time + explicit "apply my rules to uncategorised transactions" action). Auto-apply-on-ingest only touches rows where `categorisation_source ≠ MANUAL` — safe to add later without schema change.

### Aggregate: Budget

**`Budget`** (`budgets`) — monthly target per category, **effective-dated** so past months report against the target that was in force then (this is what makes the yearly view honest when targets change mid-year).

| Field | Kind | Notes |
|---|---|---|
| `id` UUID PK, `user_id` FK, `category_id` FK | stored | |
| `monthly_target_minor_units` | stored | positive number |
| `effective_from` date (first of month) | stored | unique `(user_id, category_id, effective_from)`; a target persists until superseded by a later row |
| `created_at` | stored | |
| target for month M | **computed** | latest row with `effective_from ≤ M` |
| annual target | **computed** | sum of the 12 effective monthly targets — no separate yearly entity |
| actual vs target | **computed** | join against transaction sums |

### Aggregate: VirtualPot

**`VirtualPot`** (`virtual_pots`) — internal construct, deliberately decoupled from where money physically sits and from real Monzo pots.

| Field | Kind | Notes |
|---|---|---|
| `id` UUID PK, `user_id` FK | stored | |
| `name` | stored | "Holiday fund", "Student loan", "Moneybox LISA" |
| `pot_type` enum `SAVINGS_GOAL` \| `SPEND_TRACKER` | stored | goal = money earmarked toward a target; tracker = cumulative paid toward an obligation |
| `target_amount_minor_units` nullable | stored | |
| `target_date` nullable | stored | |
| `icon`, `colour` nullable | stored | |
| `archived_at` nullable | stored | |
| balance / cumulative total | **computed** | SUM over allocations |
| progress % | **computed** | balance ÷ target |

**`VirtualPotAllocation`** (`virtual_pot_allocations`) — the funding ledger. Append-mostly.

| Field | Kind | Notes |
|---|---|---|
| `id` UUID PK, `user_id` FK, `pot_id` FK | stored | |
| `amount_minor_units` (signed) | stored | + allocate / − withdraw (savings); + payment recorded (tracker) |
| `transaction_id` nullable FK → transactions | stored | optional link: "this student-loan direct debit feeds this pot"; unique per (pot, transaction) |
| `occurred_at` timestamptz | stored | client-supplied — allows backdating |
| `note` nullable | stored | |

Deliberately **no** pot↔account link and **no** enforcement that Σ(savings pots) ≤ Σ(real balances) in v0 — pots are a visualisation layer. A pot↔category auto-feed rule is a natural later extension (one nullable column on `VirtualPot`); not modelled now.

### `UserSettings` (`user_settings`) — one row per user

| Field | Kind |
|---|---|
| `user_id` PK/FK | stored |
| `email_notifications_enabled` bool | stored |
| `default_currency` | stored |
| `updated_at` | stored |

Bank connection management reuses the existing `monzo_connections` endpoints — no new entity.

### `ReportSnapshot` — **deliberately omitted.** See Section B.

---

## Section B — Computed vs Stored Tradeoffs

| Data | Verdict | Reasoning (frequency / volatility / staleness) |
|---|---|---|
| **Account balance** | **Stored snapshot** (provider-fetched) | Computing from transactions is *wrong*, not just slow: history is windowed (may be incomplete), pending transactions shift, credit-card balances don't derive from synced spend alone. Provider is the source of truth — cache it on `Account` with `balance_as_of`, refresh on sync/webhook/user tap. |
| **Account ins/outs (day/week/month)** | Computed | Indexed SUM over one user's transactions is sub-millisecond at personal-finance volume (~10³–10⁴ rows/yr). Volatile under recategorisation/exclusion edits — a stored rollup would need constant invalidation. |
| **Category monthly totals** | Computed | Same argument. Recategorising one transaction must instantly move totals between categories; snapshots make the common edit path the expensive one. |
| **Yearly report (12 × category grid)** | Computed | One `GROUP BY month, category` over ≤ ~10k rows. Measure before optimising; if it ever drags, add a materialised view keyed `(user, month, category)` — additive change, no schema debt now. Hence **no `ReportSnapshot` entity**: single user, low volume, high edit-volatility — pre-aggregation buys nothing and costs invalidation logic. |
| **Pot balance / progress** | Computed | Tens-to-hundreds of allocation rows per pot. SUM is trivial; a cached column would be pure risk. |
| **Budget target for month M** | Computed | Resolved from effective-dated rows; storing per-month copies would multiply rows and drift. |
| **Annual budget target** | Computed | Sum of effective monthly targets — keeps one source of truth when a target changes mid-year. |
| **Transaction direction** | Computed | Sign of `amount_minor_units`. Never store what a sign already says. |
| **`status` PENDING/SETTLED** | Stored | It's provider state, not derivable locally; updated idempotently on re-map. |

Rule of thumb applied: **store what only the provider knows or only the user decides; compute everything that is arithmetic over those facts.**

---

## Section C — Backend Component Breakdown

All in `budgeteer-server` (`dev.amfshr.budgeteer`; module renamed from `budgeteer-api` in task #10), following the existing feature-package convention. `bank-client-monzo` is untouched apart from the #10 contract additions.

### Domain layer (`domain/…`)

| Package | Entities | Repository highlights |
|---|---|---|
| `domain/account` | `Account` | `findByUserIdAndArchivedAtIsNull`, `findByProviderAndProviderAccountId` |
| `domain/transaction` | `Transaction` | paged filter query (account, category, date-range, uncategorised, exclude-flag); projection queries: `sumByAccountAndWindow`, `sumByCategoryAndMonth`, `monthlyCategoryMatrix(year)` |
| `domain/category` | `Category`, `CategoryRule` | `existsByCategoryId` guard for archive-vs-delete; rules ordered by priority |
| `domain/budget` | `Budget` | `findEffectiveTargets(userId, month)` — latest `effective_from ≤ month` per category |
| `domain/pot` | `VirtualPot`, `VirtualPotAllocation` | `sumAmountByPotId` |
| `domain/user` | + `UserSettings` | existing package |

### Mapper layer — where raw becomes domain

Dedicated ingest service, one adapter per provider:

- **`ProviderDomainMapper`** (interface): `mapAccounts(userId)`, `mapTransactions(userId, sinceCursor)`.
- **`MonzoDomainMapper`** (first impl, lives in `budgeteer-server`, *not* in `bank-client-monzo` — the jar stays a dumb API client): reads `monzo_accounts` / `monzo_transactions`, upserts domain rows keyed on `(provider, provider_transaction_id)`. Skips `is_declined`. Flips `PENDING → SETTLED` when `monzo_settled_at` appears. Never overwrites `category_id`, `notes` (if user-edited), or `excluded_from_analytics` on re-map.
- **`DomainMappingService`** orchestrates: iterates providers, tracks a mapping cursor (`mapped_through` timestamp per account — one column added to domain `Account`), publishes nothing in v0 (the backlogged post-sync events slot in here later).

`TrueLayerDomainMapper` is a second implementation when TrueLayer lands — the domain schema needs zero change.

### Service layer

| Service | Single responsibility | Owns |
|---|---|---|
| `AccountService` | List/rename/reorder/archive accounts; expose balance + computed in/out summaries; trigger on-demand balance refresh via provider client | `Account` |
| `DomainMappingService` (+ per-provider mappers) | Raw → domain ingest, idempotent | writes `Account`, `Transaction` |
| `TransactionQueryService` | Filtered/paged transaction reads and window sums | reads `Transaction` |
| `CategorisationService` | Assign/clear a transaction's category (sets `MANUAL`); evaluate rules; bulk "apply rules to uncategorised" | writes `Transaction.category_id`, `CategoryRule` |
| `CategoryService` | Category CRUD + archive (blocks delete when transactions exist) | `Category` |
| `BudgetService` | Set/supersede effective-dated targets; resolve effective targets for a month/year | `Budget` |
| `VirtualPotService` | Pot CRUD, allocations, computed balances | `VirtualPot`, `VirtualPotAllocation` |
| `ReportService` | Monthly + yearly aggregates: per-category actual vs effective target, totals in/out | reads only |
| `UserSettingsService` | Read/update the settings row | `UserSettings` |

### Controller layer (all under `/api/v1`, per page)

| Controller | Endpoints | Backing page |
|---|---|---|
| `AccountController` | `GET /accounts` · `GET /accounts/{id}/summary` · `PATCH /accounts/{id}` · `POST /accounts/{id}/balance-refresh` | Connected Accounts |
| `TransactionController` | `GET /transactions` (filters: `accountId`, `categoryId`, `from`, `to`, `uncategorised`) · `PATCH /transactions/{id}` (category, notes, excluded flag) | Spending Tracker (feed) |
| `CategoryController` | `GET/POST /categories` · `PATCH /categories/{id}` · `POST /categories/{id}/archive` · `GET/POST/PATCH/DELETE /categories/{id}/rules` · `POST /categorisation/apply-rules` | Settings + Spending Tracker |
| `BudgetController` | `GET /budgets?month=` (effective targets + actuals) · `PUT /budgets/{categoryId}` (new effective-dated target) | Spending Tracker |
| `VirtualPotController` | `GET/POST /pots` · `PATCH /pots/{id}` · `POST /pots/{id}/archive` · `GET/POST /pots/{id}/allocations` · `DELETE /pots/{id}/allocations/{allocId}` | Virtual Pots |
| `ReportController` | `GET /reports/monthly?month=YYYY-MM` · `GET /reports/yearly?year=YYYY` | Reports (month/year toggle = two endpoints, same underlying sums) |
| `UserSettingsController` | `GET/PATCH /settings` | Settings |

Connection link/unlink stays on the existing Monzo (later TrueLayer) controllers.

### Scheduled jobs

| Job | Cadence | Notes |
|---|---|---|
| `DomainMappingJob` | after each delta sync (chain off the existing 60-min job; becomes event-driven when the backlogged post-sync events land) | calls `DomainMappingService` |
| `BalanceRefreshJob` | with the delta sync | provider `/balance` → `Account` snapshot; webhooks later make both near-real-time |

No other new jobs — reports and pot balances are computed on read.

### Migration plan (new files from **V11**; never touch V1–V10)

1. `V11__create_user_accounts.sql`
2. `V12__create_transactions.sql`
3. `V13__create_categories_and_rules.sql`
4. `V14__create_budgets.sql`
5. `V15__create_virtual_pots.sql` (+ allocations)
6. `V16__create_user_settings.sql`

### Suggested build order (each a PR-sized slice)

1. Account + Transaction entities, `MonzoDomainMapper`, mapping job → `GET /transactions`, `GET /accounts` against real synced data
2. Categories + manual categorisation + budgets → Spending Tracker complete
3. Reports (monthly, then yearly)
4. Virtual pots + allocations
5. Category rules (suggest + bulk apply), settings row
