-- =============================================================================
-- BUDGETEER MANUAL TESTING SQL QUERIES
-- =============================================================================
-- Connect to the database:
--   docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer
-- 
-- Or using psql directly:
--   psql -h localhost -p 5432 -U budgeteer -d budgeteer
-- =============================================================================


-- =============================================================================
-- 📊 OVERVIEW QUERIES
-- =============================================================================

-- Count all records in each table
SELECT 'users' AS table_name, COUNT(*) AS count FROM users
UNION ALL SELECT 'magic_link_tokens', COUNT(*) FROM magic_link_tokens
UNION ALL SELECT 'app_refresh_tokens', COUNT(*) FROM app_refresh_tokens
UNION ALL SELECT 'oauth_states', COUNT(*) FROM oauth_states
UNION ALL SELECT 'monzo_connections', COUNT(*) FROM monzo_connections
ORDER BY table_name;

-- Schema info for all tables
SELECT table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('users', 'magic_link_tokens', 'app_refresh_tokens', 'oauth_states', 'monzo_connections')
ORDER BY table_name, ordinal_position;


-- =============================================================================
-- 👤 USERS
-- =============================================================================

-- List all users
SELECT id, email, email_verified, created_at, updated_at
FROM users
ORDER BY created_at DESC;

-- Find user by email
SELECT * FROM users WHERE email = 'test@example.com';

-- Find user by ID (replace with actual UUID)
-- SELECT * FROM users WHERE id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx';


-- =============================================================================
-- 🔗 MAGIC LINK TOKENS
-- =============================================================================

-- All magic link tokens with user email
SELECT 
    m.id,
    u.email,
    SUBSTRING(m.token_hash, 1, 16) || '...' AS token_hash,
    m.used_at,
    m.expires_at,
    m.created_at,
    CASE 
        WHEN m.expires_at < NOW() THEN 'EXPIRED'
        WHEN m.used_at IS NOT NULL THEN 'USED'
        ELSE 'VALID'
    END AS status
FROM magic_link_tokens m
JOIN users u ON m.user_id = u.id
ORDER BY m.created_at DESC;

-- Find pending (valid, unused) magic links
SELECT m.*, u.email
FROM magic_link_tokens m
JOIN users u ON m.user_id = u.id
WHERE m.used_at IS NULL
  AND m.expires_at > NOW()
ORDER BY m.created_at DESC;


-- =============================================================================
-- 🔑 APP REFRESH TOKENS (Sessions)
-- =============================================================================

-- All refresh tokens with user info
SELECT 
    r.id,
    u.email,
    SUBSTRING(r.token_hash, 1, 16) || '...' AS token_hash,
    r.revoked_at,
    r.expires_at,
    r.created_at,
    CASE 
        WHEN r.expires_at < NOW() THEN 'EXPIRED'
        WHEN r.revoked_at IS NOT NULL THEN 'REVOKED'
        ELSE 'ACTIVE'
    END AS status
FROM app_refresh_tokens r
JOIN users u ON r.user_id = u.id
ORDER BY r.created_at DESC;

-- Active sessions per user
SELECT u.email, COUNT(*) AS active_sessions
FROM app_refresh_tokens r
JOIN users u ON r.user_id = u.id
WHERE r.revoked_at IS NULL
  AND r.expires_at > NOW()
GROUP BY u.email
ORDER BY active_sessions DESC;

-- Find a user's active sessions
-- SELECT * FROM app_refresh_tokens 
-- WHERE user_id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx'
--   AND revoked_at IS NULL
--   AND expires_at > NOW();


-- =============================================================================
-- 🏦 OAUTH STATES (Monzo OAuth Flow)
-- =============================================================================

-- All OAuth states with user info
SELECT 
    o.id,
    u.email,
    SUBSTRING(o.state, 1, 20) || '...' AS state_preview,
    o.used,
    o.expires_at,
    o.created_at,
    CASE 
        WHEN o.expires_at < NOW() THEN 'EXPIRED'
        WHEN o.used THEN 'USED'
        ELSE 'PENDING'
    END AS status
FROM oauth_states o
JOIN users u ON o.user_id = u.id
ORDER BY o.created_at DESC;

-- Pending OAuth states (waiting for Monzo approval)
SELECT o.*, u.email
FROM oauth_states o
JOIN users u ON o.user_id = u.id
WHERE o.used = FALSE
  AND o.expires_at > NOW()
ORDER BY o.created_at DESC;

-- Expired states (should be cleaned up)
SELECT COUNT(*) AS expired_count
FROM oauth_states
WHERE expires_at < NOW();


-- =============================================================================
-- 💳 MONZO CONNECTIONS
-- =============================================================================

-- All connections with status
SELECT 
    c.id,
    u.email,
    c.monzo_user_id,
    c.token_expires_at,
    c.connected_at,
    c.disconnected_at,
    CASE 
        WHEN c.disconnected_at IS NOT NULL THEN 'DISCONNECTED'
        WHEN c.token_expires_at IS NOT NULL AND c.token_expires_at < NOW() THEN 'TOKEN_EXPIRED'
        ELSE 'ACTIVE'
    END AS status
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
ORDER BY c.connected_at DESC;

-- Active connections only (not disconnected)
SELECT c.*, u.email
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
WHERE c.disconnected_at IS NULL
ORDER BY c.connected_at DESC;

-- Connections needing token refresh (expires within 1 hour)
SELECT c.id, u.email, c.monzo_user_id, c.token_expires_at
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
WHERE c.disconnected_at IS NULL
  AND c.token_expires_at IS NOT NULL
  AND c.token_expires_at < NOW() + INTERVAL '1 hour';

-- Connection count by user
SELECT u.email, COUNT(*) AS connection_count
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
WHERE c.disconnected_at IS NULL
GROUP BY u.email
ORDER BY connection_count DESC;

-- Verify tokens are encrypted (should NOT see plaintext tokens!)
SELECT 
    id,
    SUBSTRING(access_token_enc, 1, 30) || '...' AS access_token_preview,
    SUBSTRING(refresh_token_enc, 1, 30) || '...' AS refresh_token_preview,
    CASE 
        WHEN access_token_enc LIKE '%{%' OR access_token_enc LIKE '%:%' THEN '⚠️ MIGHT BE PLAINTEXT!'
        ELSE '✅ Encrypted'
    END AS encryption_check
FROM monzo_connections
WHERE disconnected_at IS NULL;


-- =============================================================================
-- 🧹 CLEANUP QUERIES (USE WITH CAUTION!)
-- =============================================================================

-- Delete expired magic link tokens
DELETE FROM magic_link_tokens WHERE expires_at < NOW();

-- Delete expired OAuth states
DELETE FROM oauth_states WHERE expires_at < NOW();

-- Revoke all refresh tokens (force re-login for everyone)
UPDATE app_refresh_tokens SET revoked_at = NOW() WHERE revoked_at IS NULL;

-- Hard delete a specific user's data (DANGER!)
-- This order respects foreign key constraints:
DELETE FROM oauth_states WHERE user_id = 'xxx';
DELETE FROM monzo_connections WHERE user_id = 'xxx';
DELETE FROM app_refresh_tokens WHERE user_id = 'xxx';
DELETE FROM magic_link_tokens WHERE user_id = 'xxx';
DELETE FROM users WHERE id = 'xxx';

-- Delete all Monzo connections
DELETE FROM monzo_connections;

-- Also clean up oauth states
DELETE FROM oauth_states;


-- =============================================================================
-- 🧪 QUICK CHECKS (After Manual Testing)
-- =============================================================================

-- After login with dev/quick-login, check user was created
SELECT id, email, email_verified, created_at 
FROM users 
ORDER BY created_at DESC 
LIMIT 5;

-- After initiating OAuth, check state was created
SELECT id, SUBSTRING(state, 1, 30) || '...' AS state, used, expires_at
FROM oauth_states
ORDER BY created_at DESC
LIMIT 5;

-- After completing OAuth, check connection was created
SELECT 
    c.id,
    u.email,
    c.monzo_user_id,
    CASE WHEN c.disconnected_at IS NULL THEN '✅ Active' ELSE '❌ Disconnected' END AS status,
    c.token_expires_at,
    c.connected_at
FROM monzo_connections c
JOIN users u ON c.user_id = u.id
ORDER BY c.connected_at DESC
LIMIT 5;

-- Full check: User with all their Monzo-related data
-- Replace with your test email
SELECT 
    u.id AS user_id,
    u.email,
    u.email_verified,
    (SELECT COUNT(*) FROM oauth_states WHERE user_id = u.id) AS oauth_states,
    (SELECT COUNT(*) FROM oauth_states WHERE user_id = u.id AND used = FALSE AND expires_at > NOW()) AS pending_oauth,
    (SELECT COUNT(*) FROM monzo_connections WHERE user_id = u.id AND disconnected_at IS NULL) AS active_connections,
    (SELECT COUNT(*) FROM app_refresh_tokens WHERE user_id = u.id AND revoked_at IS NULL AND expires_at > NOW()) AS active_sessions
FROM users u
WHERE u.email LIKE '%test%' OR u.email LIKE '%example%'
ORDER BY u.created_at DESC;


-- =============================================================================
-- 🔍 DEBUGGING QUERIES
-- =============================================================================

-- Find user with all related data
-- Replace 'test@example.com' with the email you're testing
SELECT 
    'User' AS entity,
    u.id::text,
    u.email,
    u.email_verified::text AS detail1,
    u.created_at::text AS detail2
FROM users u WHERE u.email = 'test@example.com'
UNION ALL
SELECT 
    'Magic Link',
    m.id::text,
    CASE WHEN m.used_at IS NOT NULL THEN 'USED' WHEN m.expires_at < NOW() THEN 'EXPIRED' ELSE 'VALID' END,
    m.expires_at::text,
    m.created_at::text
FROM magic_link_tokens m 
JOIN users u ON m.user_id = u.id 
WHERE u.email = 'test@example.com'
UNION ALL
SELECT 
    'Session',
    r.id::text,
    CASE WHEN r.revoked_at IS NOT NULL THEN 'REVOKED' WHEN r.expires_at < NOW() THEN 'EXPIRED' ELSE 'ACTIVE' END,
    r.expires_at::text,
    r.created_at::text
FROM app_refresh_tokens r 
JOIN users u ON r.user_id = u.id 
WHERE u.email = 'test@example.com'
UNION ALL
SELECT 
    'OAuth State',
    o.id::text,
    CASE WHEN o.used THEN 'USED' WHEN o.expires_at < NOW() THEN 'EXPIRED' ELSE 'PENDING' END,
    o.expires_at::text,
    o.created_at::text
FROM oauth_states o 
JOIN users u ON o.user_id = u.id 
WHERE u.email = 'test@example.com'
UNION ALL
SELECT 
    'Monzo Connection',
    c.id::text,
    CASE WHEN c.disconnected_at IS NOT NULL THEN 'DISCONNECTED' ELSE 'ACTIVE' END,
    c.monzo_user_id,
    c.connected_at::text
FROM monzo_connections c 
JOIN users u ON c.user_id = u.id 
WHERE u.email = 'test@example.com';


-- =============================================================================
-- 📈 FLYWAY MIGRATION HISTORY
-- =============================================================================

-- Check migration status
SELECT 
    installed_rank,
    version,
    description,
    type,
    script,
    installed_on,
    success
FROM flyway_schema_history
ORDER BY installed_rank;
