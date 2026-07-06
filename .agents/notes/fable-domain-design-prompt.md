# Fable Prompt — Budgeteer Domain Design

> **Session type:** Design & planning only. No code output.
> **Guardrails:** Stay within the scope defined below. Do not redesign auth, Monzo OAuth, or the multi-module build structure — those are done. Do not prescribe a frontend framework. Produce a concrete, structured deliverable as defined in the Output section.

---

## Project Overview

**Budgeteer** is a personal finance app (solo project). Spring Boot 3.4 / Java 25 backend, PostgreSQL 16, Flyway migrations. The app syncs bank data and will expose budgeting features via a web and/or desktop UI — framework not yet decided.

### What is already built and stable

- Passwordless auth: magic links → JWE session tokens (15 min access / 7 day refresh), multi-session, HttpOnly cookies
- Monzo OAuth 2.0 integration: token persistence (AES-256-GCM encrypted), auto-refresh, CSRF protection
- Transaction sync: async post-OAuth backfill, windowed historical pull (≤350-day windows, resumable), 60-min delta job
- Multi-module Maven restructure in progress (nearly merged): `budgeteer-common`, `monzo-client`, `budgeteer-api`
- Phase 5 (webhooks) is next in the queue — real-time transaction ingestion via Cloudflare Tunnel
- TrueLayer integration is planned (multi-bank: Lloyds, HSBC, etc.) but not yet started

### Current DB tables

`users`, `magic_link_tokens`, `app_refresh_tokens`, `monzo_connections`, `oauth_states` — all in the auth/session layer. There is a raw `monzo_transactions` table from the sync phase (raw Monzo payloads). No unified domain tables yet.

### The immediate gap

The domain layer — unified `user_accounts`, `transactions`, categorisation, budgets, pots — does not exist. The raw Monzo data is there but has not been mapped into a product-facing domain. **This session is about designing that domain.**

---

## User's Bank Accounts (Real Context)

The user (sole user of this app initially) has:

- **Lloyds**: current account, savings account, credit card
- **Monzo**: standard current account

Monzo data is live. Lloyds will come via TrueLayer (planned). The domain must support both from day one of design, even though only Monzo is live.

---

## Features to Design For

These are the pages/capabilities that need to exist. Use these to drive the domain model — don't design domain in isolation from the features.

### 1. Connected Accounts

A home screen showing all linked bank accounts. For each account:

- Current balance
- Recent ins / outs (today, this week, this month)
- Account type visible (current, savings, credit card)
- Which bank it belongs to

Users can connect/disconnect bank integrations from here.

### 2. Virtual Pots

The user wants "virtual pots" that are **not** tied to a real Monzo pot — they are internal constructs that allocate a portion of money to a named purpose, regardless of which real account the money sits in.

Examples:
- Holiday fund — money earmarked from across accounts
- Student loan repayment tracker — tracking how much has been spent on this goal
- Moneybox LISA — tracking contributions

A pot has: name, optional target amount, optional target date, current allocated/spent amount, optional icon/colour.

Some pots are **savings trackers** (money earmarked toward a goal). Others are **spend trackers** (how much has been paid toward a recurring obligation like a loan). The UI should let the user decide the type.

### 3. Spending Tracker

Users create **spend categories** (personalised, not a fixed taxonomy). Examples: Bills, Snooker, Food, Eat Out, Travel, Holiday.

For each category:
- Monthly spending target (optional)
- Transactions are tagged to a category (manual, with auto-suggest later)
- View: actual spend vs target for the current month

Transactions can belong to one category. A category can have sub-categories (optional, keep it simple).

### 4. Reports

A view that lets the user toggle between:
- **Monthly view**: this month's spending by category vs target, total in vs out
- **Yearly view**: same breakdown but aggregated by month across the year, with annual targets

The data driving reports is the same categorised transactions — just windowed differently. Reports must be fast — pre-aggregate where appropriate.

### 5. User Settings

- Manage connected bank integrations (link/unlink Monzo, TrueLayer)
- Manage categories (create, rename, archive)
- Manage virtual pots
- Notification preferences (email, later push)
- Profile (email only for now)

---

## What I Want From This Session

Produce a structured domain design with three sections:

### Section A — Domain Entities

For each entity: name, purpose, key fields (mark each field as `[stored]` or `[computed]`), and relationships to other entities. Group into aggregates where natural.

Think carefully about what **must** be stored vs what can be derived at query time. For example, monthly totals per category might be a computed view rather than a stored rollup — call that out explicitly with your reasoning.

Entities to address (at minimum): Account, Transaction (unified domain, mapped from raw bank data), Category, CategoryRule (for auto-suggest later), VirtualPot, VirtualPotAllocation (how a pot gets funded), Budget (monthly target per category), ReportSnapshot (if pre-aggregation is warranted), and any junction/linking tables needed.

### Section B — Computed vs Stored Tradeoffs

A short analysis table: for each piece of data that could go either way (e.g. category monthly total, account balance, pot balance), state whether you recommend stored or computed and why, considering: query frequency, data volatility, acceptable staleness.

### Section C — Backend Component Breakdown

Describe the Spring Boot layers needed to deliver these features:

- **Domain layer** (JPA entities, repos) — which aggregates, which repos
- **Service layer** — name each service, its single responsibility, and which entities it owns
- **Controller layer** — name each controller (by feature page) and the endpoints it will expose (method + path pattern only, no request/response bodies needed)
- **Mapper layer** — where raw Monzo/TrueLayer data is translated into domain entities (which service or dedicated mapper owns this?)
- **Scheduled jobs** — any new jobs needed beyond the existing 60-min delta sync

---

## Constraints & Scope

- **No frontend tech decisions** — the output is backend domain only
- **No auth redesign** — treat the existing session/user system as fixed
- **No Monzo client redesign** — the `monzo-client` module is being hardened separately
- **Multi-bank from the start** — domain entities must not be Monzo-specific. Use provider-agnostic field names
- **Single user initially** — but design entities with a `user_id` FK so multi-user is not a breaking change later
- **Keep it lean** — don't model features that aren't in the list above. No ML, no recurring-detection engine, no multi-user admin. Those are icebox items
- **Output format** — structured markdown with tables and lists. No prose essays. Be specific: name entities, name fields, name services and their methods where it aids clarity

---

## Output Length

Aim for a thorough but focused response. The three sections above should be self-contained and implementation-ready — a developer (or a code-generation model) should be able to read Section A and write the JPA entities without further clarification. Prefer tables over bullet lists where structure aids scanning.
