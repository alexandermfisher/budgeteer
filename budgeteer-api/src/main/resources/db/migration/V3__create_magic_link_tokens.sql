-- ============================================================================
-- V3__create_magic_link_tokens.sql
-- Creates the magic_link_tokens table for one-time passwordless login
-- ============================================================================

CREATE TABLE magic_link_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64) NOT NULL,  -- SHA-256 hash (64 hex chars)
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at         TIMESTAMP WITH TIME ZONE NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uk_magic_link_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_magic_link_tokens_user_id ON magic_link_tokens(user_id);
CREATE INDEX idx_magic_link_tokens_expires_at ON magic_link_tokens(expires_at);

COMMENT ON TABLE magic_link_tokens IS 'One-time magic link tokens for passwordless authentication';
COMMENT ON COLUMN magic_link_tokens.token_hash IS 'SHA-256 hash of token - plain token is never stored';
COMMENT ON COLUMN magic_link_tokens.used_at IS 'Set when token is verified - prevents replay attacks';
