# Phase 5: Webhooks

> **Priority:** P3 | **Estimate:** TBD | **Status:** Queue | **Depends on:** Transaction Sync

## Goal

Receive real-time transaction events from Monzo via webhook, removing the need to poll for new transactions.

## Scope

- [ ] Webhook receiver endpoint (`POST /webhooks/monzo`)
- [ ] Signature validation (verify requests are genuinely from Monzo)
- [ ] Async processing — acknowledge immediately, process in background
- [ ] Register webhook with Monzo API on connection
- [ ] Deregister on disconnect
- [ ] Handle duplicate events (idempotency)
