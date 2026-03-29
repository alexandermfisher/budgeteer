# Phase 3: Token Auto-Refresh

> **Priority:** P1 | **Estimate:** 1 day | **Status:** Queue | **Depends on:** Monzo Token Persistence ✅

## Goal

Automatically refresh Monzo access tokens before they expire so the app never hits a 401 mid-session. Surface a "reconnect needed" status when refresh fails.

## Scope

- [ ] Create `MonzoTokenRefreshService`
- [ ] Proactive refresh logic — refresh before expiry window (e.g. 5 min before)
- [ ] Scheduled background job (Spring `@Scheduled`)
- [ ] Handle refresh failures gracefully — mark connection as requiring reconnect
- [ ] Expose "reconnect needed" status in API response
