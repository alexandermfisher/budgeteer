-- =============================================================================
-- V5: Create Monzo Connections Table
-- =============================================================================
-- Stores encrypted Monzo OAuth tokens associated with authenticated app users.
-- Supports multiple Monzo accounts per user.
--
-- Security:
-- - access_token_enc and refresh_token_enc are AES-256-GCM encrypted
-- - Tokens are decrypted only when needed for Monzo API calls
-- - Soft delete via disconnected_at (tokens remain encrypted in case of audit)
-- =============================================================================

CREATE TABLE monzo_connections (
    -- Primary key
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Foreign key to users table (the app user who owns this connection)
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- Monzo user identifier (from Monzo API /ping/whoami)
    monzo_user_id       VARCHAR(255) NOT NULL,
    
    -- Encrypted tokens (AES-256-GCM)
    -- Format: base64(IV + ciphertext + authTag)
    -- Typical length: ~200-400 characters
    access_token_enc    TEXT NOT NULL,
    refresh_token_enc   TEXT NOT NULL,
    
    -- Token expiration (from Monzo token response)
    token_expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    
    -- Audit timestamps
    connected_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    
    -- Soft delete: NULL = active, timestamp = disconnected
    disconnected_at     TIMESTAMP WITH TIME ZONE NULL,
    
    -- Prevent duplicate connections for same user + Monzo account
    CONSTRAINT uk_monzo_connections_user_monzo UNIQUE (user_id, monzo_user_id)
);

-- =============================================================================
-- Indexes
-- =============================================================================

-- Fast lookup by user (list user's connections)
CREATE INDEX idx_monzo_connections_user_id ON monzo_connections(user_id);

-- Fast lookup of active connections
CREATE INDEX idx_monzo_connections_active ON monzo_connections(user_id) 
    WHERE disconnected_at IS NULL;

-- Find expired tokens (for future token refresh job)
CREATE INDEX idx_monzo_connections_expires ON monzo_connections(token_expires_at) 
    WHERE disconnected_at IS NULL;

-- =============================================================================
-- Comments
-- =============================================================================

COMMENT ON TABLE monzo_connections IS 'Stores encrypted Monzo OAuth tokens for authenticated users';
COMMENT ON COLUMN monzo_connections.user_id IS 'App user who owns this Monzo connection';
COMMENT ON COLUMN monzo_connections.monzo_user_id IS 'Monzo user ID from /ping/whoami endpoint';
COMMENT ON COLUMN monzo_connections.access_token_enc IS 'AES-256-GCM encrypted access token';
COMMENT ON COLUMN monzo_connections.refresh_token_enc IS 'AES-256-GCM encrypted refresh token';
COMMENT ON COLUMN monzo_connections.token_expires_at IS 'When the access token expires';
COMMENT ON COLUMN monzo_connections.disconnected_at IS 'Soft delete timestamp (NULL = active)';
