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
| Transactions | Sync GET with `from`/`to` date window (optional async mode) | Async only: create a transactions *request*, then poll it by `request_id` |
| Standing orders / direct debits / cards | ✅ Dedicated endpoints | ❌ Not in the spec (as of 2026-08-17) |
| Coverage | UK + IE (+ EU beta) | UK only (as of 2026-08-17) |

**Implication for `bank-client-truelayer`:** the existing `BankClient` contract (per-user
tokens, `exchangeCode`/`refreshTokens`, windowed `getTransactions`) maps naturally onto **v1**.
v3's connection-based model would push token/consent management largely onto TrueLayer but
loses standing orders / direct debits / cards — which the June 2026 suitability assessment
counted as requirements. Decide v1 vs v3 (or v1-now, v3-later) during the TrueLayer task's
planning phase; re-check whether v3 has gained the missing endpoints by then.

## Refreshing the specs

```bash
cd docs/api/truelayer/openapi
for f in authentication-server payments-api-v3 data-api-v1 data-api-v3 verification-api client-tracking-api signup; do
  curl -fsSL "https://docs.truelayer.com/openapi/$f.json" -o "$f.json"
done
```
