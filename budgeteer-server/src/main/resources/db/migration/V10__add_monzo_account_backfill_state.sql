-- Tracks resumable backfill progress so re-OAuth can continue where it left off.
--
-- backfill_status:          Lifecycle state — IN_PROGRESS, COMPLETED, NEEDS_REAUTH.
--                           Null means backfill has never started.
-- backfill_progress_at:     The upper bound (windowEnd) of the NEXT window to process.
--                           Decreases as windows complete. Null means start from now().
-- backfill_progress_cursor: The since_id cursor within the current in-flight window.
--                           Persisted after every 100-transaction page so a 403 mid-window
--                           can resume exactly where it left off rather than re-fetching
--                           the whole window.
ALTER TABLE monzo_accounts
    ADD COLUMN backfill_status          VARCHAR(32)                      NULL,
    ADD COLUMN backfill_progress_at     TIMESTAMP WITH TIME ZONE         NULL,
    ADD COLUMN backfill_progress_cursor VARCHAR(255)                     NULL;
