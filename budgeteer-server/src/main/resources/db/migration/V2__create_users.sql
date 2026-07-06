-- ============================================================================
-- V2__create_users.sql
-- Creates the users table for passwordless magic link authentication
-- ============================================================================

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL,
    email_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE INDEX idx_users_email ON users(email);

COMMENT ON TABLE users IS 'Application users (passwordless via magic links)';
COMMENT ON COLUMN users.email IS 'User email address - serves as identity';
COMMENT ON COLUMN users.email_verified IS 'True after first successful magic link verification';
