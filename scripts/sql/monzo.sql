-- =============================================================================
-- BUDGETEER — MONZO INTEGRATION QUERIES
-- =============================================================================
-- Connect:  docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer
-- =============================================================================


-- =============================================================================
-- 🔗 OAUTH STATES
-- =============================================================================

-- All OAuth states with status
SELECT
    o.id,
    u.email,
    SUBSTRING(o.state, 1, 20) || '...' AS state_preview,
    o.created_at,
    o.expires_at,
    CASE
        WHEN o.used THEN 'USED'
        WHEN o.expires_at < NOW() THEN 'EXPIRED'
        ELSE 'PENDING'
    END AS status
FROM oauth_states o
JOIN users u ON o.user_id = u.id
ORDER BY o.created_at DESC;

-- Currently pending (awaiting Monzo approval)
SELECT o.*, u.email
FROM oauth_states o
JOIN users u ON o.user_id = u.id
WHERE o.used = FALSE AND o.expires_at > NOW()
ORDER BY o.created_at DESC;

-- Expired state count (should be cleaned periodically)
SELECT COUNT(*) AS expired_count FROM oauth_states WHERE expires_at < NOW();


-- =============================================================================
-- 💳 MONZO CONNECTIONS
-- =============================================================================

-- All connections with status
SELECT
    c.id,
    u.email,
    c.monzo_user_id,
    c.connected_at,
    c.disconnected_at,
    c.token_expires_at,
    CASE
        WHEN c.disconnected_at IS NOT NULL THEN 'DISCONNECTED'
        WHEN c.token_expires_at < NOW() THEN 'TOKEN_EXPIRED'
        ELSE 'ACTIVE'
    END AS status
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
ORDER BY c.connected_at DESC;

-- Active connections only
SELECT c.id, u.email, c.monzo_user_id, c.token_expires_at, c.connected_at
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
WHERE c.disconnected_at IS NULL
ORDER BY c.connected_at DESC;

-- Connections needing token refresh soon (within 1 hour)
SELECT c.id, u.email, c.monzo_user_id, c.token_expires_at
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
WHERE c.disconnected_at IS NULL
  AND c.token_expires_at > NOW()
  AND c.token_expires_at < NOW() + INTERVAL '1 hour';

-- Connection count by user
SELECT u.email, COUNT(*) AS connections
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
WHERE c.disconnected_at IS NULL
GROUP BY u.email
ORDER BY connections DESC;

-- Verify tokens are encrypted (should show base64, never plain JSON)
SELECT
    c.id,
    u.email,
    SUBSTRING(c.access_token_enc, 1, 30) || '...' AS access_preview,
    SUBSTRING(c.refresh_token_enc, 1, 30) || '...' AS refresh_preview,
    CASE
        WHEN c.access_token_enc LIKE '%{%' OR c.access_token_enc LIKE '%:%' THEN '⚠️ PLAINTEXT'
        ELSE '✅ ENCRYPTED'
    END AS check
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
WHERE c.disconnected_at IS NULL;


-- =============================================================================
-- 🏦 ACCOUNTS
-- =============================================================================

-- All accounts with connection info
SELECT
    a.id,
    u.email,
    c.monzo_user_id,
    a.description,
    a.account_type,
    a.currency,
    a.closed,
    a.last_synced_at,
    a.backfill_status,
    a.last_transaction_id
FROM monzo_accounts a
JOIN monzo_connections c ON a.connection_id = c.id
JOIN users u ON a.user_id = u.id
ORDER BY a.created_at DESC;

-- Accounts + backfill progress (active only)
SELECT
    u.email,
    a.description,
    a.account_type,
    a.backfill_status,
    a.last_transaction_id,
    a.last_synced_at,
    a.backfill_progress_at AS progress_window_date
FROM monzo_accounts a
JOIN users u ON a.user_id = u.id
WHERE a.closed = FALSE
ORDER BY u.email, a.description;

-- Account counts by user
SELECT u.email, COUNT(*) AS accounts, COUNT(*) FILTER (WHERE a.closed) AS closed
FROM monzo_accounts a
JOIN users u ON a.user_id = u.id
GROUP BY u.email
ORDER BY accounts DESC;


-- =============================================================================
-- 💰 TRANSACTIONS
-- =============================================================================

-- Most recent transactions (latest 50)
SELECT
    t.id,
    u.email,
    a.description AS account,
    t.amount::float / 100 AS amount,
    t.currency,
    t.description,
    t.merchant_name,
    t.monzo_created_at,
    t.is_declined
FROM monzo_transactions t
JOIN monzo_accounts a ON t.account_id = a.id
JOIN users u ON t.user_id = u.id
ORDER BY t.monzo_created_at DESC
LIMIT 50;

-- Transactions for a specific user (latest 100)
-- SELECT
--     t.id, a.description AS account, t.amount::float / 100 AS amount,
--     t.currency, t.description, t.merchant_name, t.monzo_created_at
-- FROM monzo_transactions t
-- JOIN monzo_accounts a ON t.account_id = a.id
-- WHERE t.user_id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx'
-- ORDER BY t.monzo_created_at DESC
-- LIMIT 100;

-- Transaction counts by user
SELECT
    u.email,
    COUNT(*) AS total_txns,
    COUNT(*) FILTER (WHERE t.is_declined) AS declined,
    COUNT(*) FILTER (WHERE NOT t.is_declined) AS completed
FROM monzo_transactions t
JOIN users u ON t.user_id = u.id
GROUP BY u.email
ORDER BY total_txns DESC;

-- Spending per account (non-declined, in pounds)
SELECT
    u.email,
    a.description AS account,
    COUNT(*) AS txns,
    SUM(t.amount)::float / 100 AS total_spent
FROM monzo_transactions t
JOIN monzo_accounts a ON t.account_id = a.id
JOIN users u ON t.user_id = u.id
WHERE NOT t.is_declined
GROUP BY u.email, a.description
ORDER BY total_spent DESC;

-- Top merchants by spend (non-declined)
SELECT
    u.email,
    t.merchant_name,
    COUNT(*) AS txns,
    SUM(t.amount)::float / 100 AS total_spent
FROM monzo_transactions t
JOIN users u ON t.user_id = u.id
WHERE NOT t.is_declined AND t.merchant_name IS NOT NULL
GROUP BY u.email, t.merchant_name
ORDER BY total_spent DESC
LIMIT 30;

-- Spending by merchant category
SELECT
    u.email,
    t.merchant_category,
    COUNT(*) AS txns,
    SUM(t.amount)::float / 100 AS total_spent
FROM monzo_transactions t
JOIN users u ON t.user_id = u.id
WHERE NOT t.is_declined AND t.merchant_category IS NOT NULL
GROUP BY u.email, t.merchant_category
ORDER BY total_spent DESC
LIMIT 30;

-- Transactions within a date range
-- SELECT
--     t.id, a.description AS account, t.amount::float / 100 AS amount,
--     t.description, t.merchant_name, t.monzo_created_at
-- FROM monzo_transactions t
-- JOIN monzo_accounts a ON t.account_id = a.id
-- WHERE t.user_id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx'
--   AND t.monzo_created_at BETWEEN '2026-01-01' AND '2026-06-30'
--   AND NOT t.is_declined
-- ORDER BY t.monzo_created_at DESC;

-- Daily spending summary (last 30 days, non-declined)
SELECT
    DATE(t.monzo_created_at) AS date,
    COUNT(*) AS txns,
    SUM(t.amount)::float / 100 AS total_spent
FROM monzo_transactions t
JOIN users u ON t.user_id = u.id
WHERE NOT t.is_declined
  AND t.monzo_created_at > NOW() - INTERVAL '30 days'
GROUP BY DATE(t.monzo_created_at)
ORDER BY date DESC;
