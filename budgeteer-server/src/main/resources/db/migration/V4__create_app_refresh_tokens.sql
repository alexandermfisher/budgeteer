-- ============================================================================
-- V4__create_app_refresh_tokens.sql
-- Creates the app_refresh_tokens table for session management
-- ============================================================================

CREATE TABLE app_refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64) NOT NULL,  -- SHA-256 hash (64 hex chars)
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    revoked_at      TIMESTAMP WITH TIME ZONE NULL,
    
    -- Device/session tracking (optional, for "logged in devices" feature)
    user_agent      VARCHAR(500) NULL,
    ip_address      VARCHAR(45) NULL,  -- IPv6 max length
    
    CONSTRAINT uk_app_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_app_refresh_tokens_user_id ON app_refresh_tokens(user_id);
CREATE INDEX idx_app_refresh_tokens_expires_at ON app_refresh_tokens(expires_at);

COMMENT ON TABLE app_refresh_tokens IS 'Long-lived refresh tokens for session management';
COMMENT ON COLUMN app_refresh_tokens.token_hash IS 'SHA-256 hash of token - plain token is never stored';
COMMENT ON COLUMN app_refresh_tokens.revoked_at IS 'Set when user logs out - invalidates the token';
COMMENT ON COLUMN app_refresh_tokens.user_agent IS 'Browser/device info for session management UI';
COMMENT ON COLUMN app_refresh_tokens.ip_address IS 'Client IP for session management UI';
