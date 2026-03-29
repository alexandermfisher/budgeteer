# Phase 4: Transaction Sync

> **Priority:** P2 | **Estimate:** 2–3 days | **Status:** Queue | **Depends on:** Token Auto-Refresh (recommended)

## Goal

Fetch and store Monzo transactions locally — both a full historical backfill (available for 5 minutes post-OAuth) and ongoing delta sync.

## Important Timing Constraint

Monzo only allows fetching **all historical transactions within the first 5 minutes** of the OAuth flow completing. After that, the API is limited to the last 90 days. Backfill must be triggered immediately post-OAuth.

## Scope

- [ ] Design transaction schema and Flyway migration
- [ ] `MonzoClient` — transaction fetch endpoints (already exists, extend as needed)
- [ ] `TransactionSyncService` — backfill on connect, delta sync on subsequent calls
- [ ] Delta sync strategy — track `since_id` or `from` timestamp per account
- [ ] API endpoint to expose stored transactions
- [ ] Handle pagination (Monzo uses cursor-based)
