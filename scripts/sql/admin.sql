-- =============================================================================
-- BUDGETEER — ADMIN & MAINTENANCE QUERIES
-- =============================================================================
-- Connect:  docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer
-- =============================================================================


-- =============================================================================
-- 📊 DATABASE OVERVIEW
-- =============================================================================

-- Row counts for all tables
SELECT 'users'                AS table_name, COUNT(*) AS rows FROM users
UNION ALL SELECT 'magic_link_tokens',   COUNT(*) FROM magic_link_tokens
UNION ALL SELECT 'app_refresh_tokens',  COUNT(*) FROM app_refresh_tokens
UNION ALL SELECT 'oauth_states',        COUNT(*) FROM oauth_states
UNION ALL SELECT 'monzo_connections',   COUNT(*) FROM monzo_connections
UNION ALL SELECT 'monzo_accounts',      COUNT(*) FROM monzo_accounts
UNION ALL SELECT 'monzo_transactions',  COUNT(*) FROM monzo_transactions
ORDER BY table_name;

-- Table sizes on disk
SELECT
    relname AS table_name,
    pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
    pg_size_pretty(pg_relation_size(relid)) AS data_size,
    n_live_tup AS approx_rows
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC;

-- Quick status snapshot
SELECT
    (SELECT COUNT(*) FROM users) AS users,
    (SELECT COUNT(*) FROM users WHERE email_verified) AS verified,
    (SELECT COUNT(*) FROM magic_link_tokens WHERE used_at IS NULL AND expires_at > NOW()) AS pending_links,
    (SELECT COUNT(*) FROM app_refresh_tokens WHERE revoked_at IS NULL AND expires_at > NOW()) AS active_sessions,
    (SELECT COUNT(*) FROM monzo_connections WHERE disconnected_at IS NULL) AS active_connections,
    (SELECT COUNT(*) FROM monzo_accounts WHERE closed = FALSE) AS open_accounts,
    (SELECT COUNT(*) FROM monzo_transactions) AS transactions;


-- =============================================================================
-- 🔧 FLYWAY MIGRATIONS
-- =============================================================================

-- Migration history
SELECT installed_rank, version, description, type, script, installed_on, execution_time, success
FROM flyway_schema_history
ORDER BY installed_rank;

-- Migration summary
SELECT
    COUNT(*) AS total,
    COUNT(*) FILTER (WHERE success = true) AS succeeded,
    COUNT(*) FILTER (WHERE success = false) AS failed
FROM flyway_schema_history;

-- Schema columns (all Budgeteer tables)
SELECT table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('users', 'magic_link_tokens', 'app_refresh_tokens',
                     'oauth_states', 'monzo_connections', 'monzo_accounts', 'monzo_transactions')
ORDER BY table_name, ordinal_position;


-- =============================================================================
-- 🧹 CLEANUP (uncomment with care)
-- =============================================================================

-- Delete expired OAuth states
-- DELETE FROM oauth_states WHERE expires_at < NOW();

-- Delete expired magic link tokens
-- DELETE FROM magic_link_tokens WHERE expires_at < NOW();

-- Revoke ALL refresh tokens (force re-login for everyone)
-- UPDATE app_refresh_tokens SET revoked_at = NOW() WHERE revoked_at IS NULL;

-- Disconnect all Monzo connections (soft-delete — tokens preserved, audit trail intact)
-- UPDATE monzo_connections SET disconnected_at = NOW() WHERE disconnected_at IS NULL;

-- Nuke all Monzo-related data for a user
-- DELETE FROM oauth_states WHERE user_id = 'xxx';
-- DELETE FROM monzo_transactions WHERE user_id = 'xxx';
-- DELETE FROM monzo_accounts WHERE user_id = 'xxx';
-- DELETE FROM monzo_connections WHERE user_id = 'xxx';
-- DELETE FROM app_refresh_tokens WHERE user_id = 'xxx';
-- DELETE FROM magic_link_tokens WHERE user_id = 'xxx';
-- DELETE FROM users WHERE id = 'xxx';

-- Delete ALL Monzo data (reset for fresh testing)
-- DELETE FROM monzo_transactions;
-- DELETE FROM monzo_accounts;
-- DELETE FROM monzo_connections;
-- DELETE FROM oauth_states;
