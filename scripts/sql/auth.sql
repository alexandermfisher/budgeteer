-- =============================================================================
-- BUDGETEER — AUTHENTICATION QUERIES
-- =============================================================================
-- Connect:  docker exec -it budgeteer-postgres psql -U budgeteer -d budgeteer
-- =============================================================================


-- =============================================================================
-- 👤 USERS
-- =============================================================================

-- List all users
SELECT id, email, email_verified, created_at
FROM users
ORDER BY created_at DESC;

-- Count users by verification status
SELECT email_verified, COUNT(*) AS count
FROM users
GROUP BY email_verified;

-- Find a specific user (replace email or use ILIKE for partial match)
-- SELECT * FROM users WHERE email = 'test@example.com';
SELECT * FROM users WHERE email ILIKE '%example%';

-- Recently created (last 7 days)
SELECT * FROM users
WHERE created_at > NOW() - INTERVAL '7 days'
ORDER BY created_at DESC;


-- =============================================================================
-- 🔐 MAGIC LINK TOKENS
-- =============================================================================

-- All magic links with status
SELECT
    m.id,
    u.email,
    SUBSTRING(m.token_hash, 1, 16) || '...' AS token_hash,
    m.created_at,
    m.expires_at,
    m.used_at,
    CASE
        WHEN m.used_at IS NOT NULL THEN 'USED'
        WHEN m.expires_at < NOW() THEN 'EXPIRED'
        ELSE 'VALID'
    END AS status
FROM magic_link_tokens m
JOIN users u ON m.user_id = u.id
ORDER BY m.created_at DESC;

-- Currently pending (unused + not expired)
SELECT u.email, m.created_at, m.expires_at
FROM magic_link_tokens m
JOIN users u ON m.user_id = u.id
WHERE m.used_at IS NULL AND m.expires_at > NOW()
ORDER BY m.created_at DESC;

-- Magic link volume per user (abuse detection)
SELECT
    u.email,
    COUNT(*) AS total,
    COUNT(*) FILTER (WHERE m.used_at IS NOT NULL) AS used,
    MAX(m.created_at) AS last_requested
FROM magic_link_tokens m
JOIN users u ON m.user_id = u.id
GROUP BY u.email
ORDER BY total DESC;


-- =============================================================================
-- 🔑 REFRESH TOKENS (Sessions)
-- =============================================================================

-- All sessions with status
SELECT
    r.id,
    u.email,
    SUBSTRING(r.token_hash, 1, 16) || '...' AS token_hash,
    r.ip_address,
    r.created_at AS session_started,
    r.expires_at,
    CASE
        WHEN r.revoked_at IS NOT NULL THEN 'REVOKED'
        WHEN r.expires_at < NOW() THEN 'EXPIRED'
        ELSE 'ACTIVE'
    END AS status
FROM app_refresh_tokens r
JOIN users u ON r.user_id = u.id
ORDER BY r.created_at DESC;

-- Active sessions only
SELECT u.email, r.created_at, r.expires_at, r.ip_address
FROM app_refresh_tokens r
JOIN users u ON r.user_id = u.id
WHERE r.revoked_at IS NULL AND r.expires_at > NOW()
ORDER BY r.created_at DESC;

-- Active session count per user
SELECT u.email, COUNT(*) AS active_sessions
FROM app_refresh_tokens r
JOIN users u ON r.user_id = u.id
WHERE r.revoked_at IS NULL AND r.expires_at > NOW()
GROUP BY u.email
ORDER BY active_sessions DESC;

-- Find sessions for a specific user
-- SELECT * FROM app_refresh_tokens
-- WHERE user_id = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx'
--   AND revoked_at IS NULL AND expires_at > NOW();


-- =============================================================================
-- 🧹 CLEANUP (uncomment with care)
-- =============================================================================

-- Delete expired magic links
-- DELETE FROM magic_link_tokens WHERE expires_at < NOW();

-- Delete used magic links older than 30 days
-- DELETE FROM magic_link_tokens WHERE used_at IS NOT NULL AND created_at < NOW() - INTERVAL '30 days';

-- Force-logout a specific user (revoke all sessions)
-- UPDATE app_refresh_tokens SET revoked_at = NOW()
-- WHERE user_id = 'USER_UUID_HERE' AND revoked_at IS NULL;

-- Delete a test user (cascades to tokens)
-- DELETE FROM users WHERE email = 'test@example.com';
