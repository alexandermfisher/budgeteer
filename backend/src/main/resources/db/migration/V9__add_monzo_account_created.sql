-- Monzo's per-account creation timestamp (from GET /accounts).
-- Distinct from `created_at` which is when WE first persisted the row locally.
-- Used as the lower bound for the backfill window walk — no need to probe windows
-- earlier than the account itself existed.
ALTER TABLE monzo_accounts
    ADD COLUMN monzo_created_at TIMESTAMP WITH TIME ZONE NULL;
