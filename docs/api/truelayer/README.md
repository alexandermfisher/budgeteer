# TrueLayer API Reference

OpenAPI specs downloaded from `https://docs.truelayer.com/openapi/*.json` on **2026-08-17**.
Source portal: <https://docs.truelayer.com/reference/welcome-api-reference>

## Specs (`openapi/`)

| File | API | Hosts (sandbox / live) | Relevance to Budgeteer |
|------|-----|------------------------|------------------------|
| `authentication-server.json` | Authentication Server | `auth.truelayer-sandbox.com` / `auth.truelayer.com` | **High** — token issuance for every other API (`/connect/token`, auth-code + refresh + client-credentials grants) |
| `data-api-v1.json` | Data API **v1** | `api.truelayer[-sandbox].com/data/v1` | **High** — full data surface: accounts, balance, transactions (+pending), standing orders, direct debits, cards, `/me`, `/info`, `/connections/extend`, batch |
| `data-api-v3.json` | Data API **v3** | `api.truelayer[-sandbox].com/v3` | **High** — the version TrueLayer recommends for new integrations, but a much narrower surface (see below) |
| `payments-api-v3.json` | Payments API v3 | `api.truelayer[-sandbox].com` | Low — outbound payments/payouts; not needed for budgeting |
| `verification-api.json` | Verification API | `api.truelayer[-sandbox].com` | Low — account-ownership verification |
| `signup.json` | Signup+ | `api.truelayer[-sandbox].com/signup-plus` | Low — onboarding via a payment |
| `client-tracking-api.json` | Client Tracking API | `client-tracking.truelayer[-sandbox].com` | Low — analytics events |

## Data API v1 vs v3 — the integration decision

TrueLayer's guides (e.g. [Enable your users to connect their bank account](https://docs.truelayer.com/docs/enable-your-users-to-connect-their-bank-account))
say **"Data API v3 is the latest version of the Data product"** and recommend it for new
integrations. The v3 OpenAPI spec is **not linked from the docs navigation** (which is why it
looks like it doesn't exist) but lives at the predictable URL
`https://docs.truelayer.com/openapi/data-api-v3.json`.

The two versions differ fundamentally:

| | **v1** | **v3** |
|---|---|---|
| Auth model | Classic per-user OAuth: auth-code exchange → user access token + refresh token; app manages 90-day consent + refresh | Client-credentials access token (`data` scope) + per-user **`Connection-Id` header**; user consents via a TrueLayer-hosted journey that yields a Connection ID |
| Endpoints | `/accounts`, `/accounts/{id}/balance`, `/transactions` (+`/pending`), `/standing_orders`, `/direct_debits`, `/cards/**`, `/me`, `/info`, `/connections/extend`, `/batch/**` | `/v3/data-connections`, `/v3/data-connections/{id}/user-info`, `/v3/connected-accounts`, `/v3/connected-accounts/{id}/transactions/requests` (async request/poll pattern) |
| Transactions | Sync GET with `from`/`to` date window (optional async mode); rich fields inline (`merchant_name`, `transaction_category`, `running_balance`) | Async only: create a *request* (`from`/`to` dates, **cursor pagination**, `page_size` ≤500), then poll by `request_id`. Lean fields (`amount_in_minor`, `currency`, `description`, `id`, `status`, `timestamp`); merchant + category only via opt-in `enrichment` |
| Balance | ✅ `/accounts/{id}/balance`, `/cards/{id}/balance` | ❌ **No balance data anywhere in the spec** — a `balance` scope is defined but no endpoint or response field exists yet |
| Standing orders / direct debits | ✅ Dedicated endpoints | ❌ Absent |
| Cards | ✅ Full card endpoints | 🚧 `Card` schema is an explicit stub: *"card-specific fields will be added when card support is introduced in a future release"* |
| Coverage | UK + IE (+ EU beta) | UK only (as of 2026-08-17) |

**Implication for `bank-client-truelayer`:** as of 2026-08-17, **v1 is the only version that
can serve Budgeteer's data requirements** — v3 lacks balance entirely and standing orders /
direct debits / cards (the Card stub shows the surface is still being ported). The existing
`BankClient` contract (per-user tokens, `exchangeCode`/`refreshTokens`, windowed
`getTransactions`) also maps naturally onto v1 and reuses the token-encryption + refresh
infrastructure built for Monzo. v3 is where TrueLayer is heading, and its cursor-paginated
transactions actually fit `BankTransactionPage` well — so re-check v3's surface when the
TrueLayer task is picked up; if it has reached parity by then, v3 may be the better target.
Either way a later v1→v3 move is a new impl behind the same `BankClient` contract, invisible
to the domain layer.

## Refreshing the specs

```bash
cd docs/api/truelayer/openapi
for f in authentication-server payments-api-v3 data-api-v1 data-api-v3 verification-api client-tracking-api signup; do
  curl -fsSL "https://docs.truelayer.com/openapi/$f.json" -o "$f.json"
done
```
