-- =============================================================================
-- BUDGETEER SQL QUERIES
-- =============================================================================
-- A collection of useful SQL queries for development and debugging.
-- Add new queries as the project grows!
--
-- Usage: Run these in IntelliJ Database Console or any SQL client
-- Connection: localhost:5432/budgeteer (user: budgeteer, pass: budgeteer)
-- =============================================================================


-- =============================================================================
-- 📊 DATABASE OVERVIEW
-- =============================================================================

-- List all tables
SELECT table_name, table_type 
FROM information_schema.tables 
WHERE table_schema = 'public'
ORDER BY table_name;

-- Table sizes
SELECT 
    relname as table_name,
    pg_size_pretty(pg_total_relation_size(relid)) as total_size,
    pg_size_pretty(pg_relation_size(relid)) as data_size
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC;

-- Row counts for all tables
SELECT 
    schemaname,
    relname as table_name,
    n_live_tup as row_count
FROM pg_stat_user_tables
ORDER BY n_live_tup DESC;


-- =============================================================================
-- 👤 USERS
-- =============================================================================

-- All users
SELECT * FROM users ORDER BY created_at DESC;

-- User count
SELECT COUNT(*) as total_users FROM users;

-- Verified vs unverified users
SELECT 
    email_verified,
    COUNT(*) as count
FROM users
GROUP BY email_verified;

-- Find user by email
SELECT * FROM users WHERE email ILIKE '%example%';

-- Recently created users (last 7 days)
SELECT * FROM users 
WHERE created_at > NOW() - INTERVAL '7 days'
ORDER BY created_at DESC;


-- =============================================================================
-- 🔐 AUTHENTICATION - Magic Links
-- =============================================================================

-- All magic link tokens
SELECT 
    mlt.id,
    u.email,
    mlt.created_at,
    mlt.expires_at,
    mlt.used_at,
    CASE 
        WHEN mlt.used_at IS NOT NULL THEN 'USED'
        WHEN mlt.expires_at < NOW() THEN 'EXPIRED'
        ELSE 'VALID'
    END as status
FROM magic_link_tokens mlt
JOIN users u ON mlt.user_id = u.id
ORDER BY mlt.created_at DESC;

-- Pending (unused, not expired) magic links
SELECT 
    u.email,
    mlt.created_at,
    mlt.expires_at
FROM magic_link_tokens mlt
JOIN users u ON mlt.user_id = u.id
WHERE mlt.used_at IS NULL 
  AND mlt.expires_at > NOW()
ORDER BY mlt.created_at DESC;

-- Magic links per user (to detect abuse)
SELECT 
    u.email,
    COUNT(*) as total_links,
    COUNT(*) FILTER (WHERE mlt.used_at IS NOT NULL) as used_links,
    MAX(mlt.created_at) as last_requested
FROM magic_link_tokens mlt
JOIN users u ON mlt.user_id = u.id
GROUP BY u.email
ORDER BY total_links DESC;


-- =============================================================================
-- 🔑 AUTHENTICATION - Refresh Tokens (Sessions)
-- =============================================================================

-- All refresh tokens (sessions)
SELECT 
    art.id,
    u.email,
    art.created_at,
    art.expires_at,
    art.revoked_at,
    art.ip_address,
    art.user_agent,
    CASE 
        WHEN art.revoked_at IS NOT NULL THEN 'REVOKED'
        WHEN art.expires_at < NOW() THEN 'EXPIRED'
        ELSE 'ACTIVE'
    END as status
FROM app_refresh_tokens art
JOIN users u ON art.user_id = u.id
ORDER BY art.created_at DESC;

-- Active sessions only
SELECT 
    u.email,
    art.created_at as session_started,
    art.expires_at,
    art.ip_address
FROM app_refresh_tokens art
JOIN users u ON art.user_id = u.id
WHERE art.revoked_at IS NULL 
  AND art.expires_at > NOW()
ORDER BY art.created_at DESC;

-- Sessions per user
SELECT 
    u.email,
    COUNT(*) as total_sessions,
    COUNT(*) FILTER (WHERE art.revoked_at IS NULL AND art.expires_at > NOW()) as active_sessions
FROM app_refresh_tokens art
JOIN users u ON art.user_id = u.id
GROUP BY u.email
ORDER BY active_sessions DESC;


-- =============================================================================
-- 🧹 CLEANUP QUERIES (USE WITH CAUTION!)
-- =============================================================================

-- Delete expired magic links (safe cleanup)
-- DELETE FROM magic_link_tokens WHERE expires_at < NOW();

-- Delete used magic links older than 30 days
-- DELETE FROM magic_link_tokens 
-- WHERE used_at IS NOT NULL 
--   AND created_at < NOW() - INTERVAL '30 days';

-- Revoke all sessions for a user (force logout)
-- UPDATE app_refresh_tokens 
-- SET revoked_at = NOW() 
-- WHERE user_id = 'USER_UUID_HERE' 
--   AND revoked_at IS NULL;

-- Delete a test user (cascades to tokens)
-- DELETE FROM users WHERE email = 'test@example.com';


-- =============================================================================
-- 🔧 FLYWAY MIGRATIONS
-- =============================================================================

-- Migration history
SELECT 
    installed_rank,
    version,
    description,
    type,
    script,
    installed_on,
    execution_time,
    success
FROM flyway_schema_history
ORDER BY installed_rank;

-- Check if all migrations succeeded
SELECT 
    COUNT(*) as total_migrations,
    COUNT(*) FILTER (WHERE success = true) as successful,
    COUNT(*) FILTER (WHERE success = false) as failed
FROM flyway_schema_history;


-- =============================================================================
-- 🚀 QUICK STATUS CHECK
-- =============================================================================

-- Run this to get a quick overview of the database state
SELECT 
    (SELECT COUNT(*) FROM users) as total_users,
    (SELECT COUNT(*) FROM users WHERE email_verified = true) as verified_users,
    (SELECT COUNT(*) FROM magic_link_tokens WHERE used_at IS NULL AND expires_at > NOW()) as pending_magic_links,
    (SELECT COUNT(*) FROM app_refresh_tokens WHERE revoked_at IS NULL AND expires_at > NOW()) as active_sessions;
