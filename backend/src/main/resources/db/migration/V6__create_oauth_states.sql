-- V6__create_oauth_states.sql
-- OAuth state storage for CSRF protection during Monzo OAuth flow.
-- Stores temporary state tokens that link OAuth callbacks to users.

CREATE TABLE oauth_states (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    state VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used BOOLEAN NOT NULL DEFAULT false
);

-- Index for fast state lookup during callback
CREATE INDEX idx_oauth_states_state ON oauth_states(state);

-- Index for cleanup queries (delete expired states)
CREATE INDEX idx_oauth_states_expires_at ON oauth_states(expires_at);

-- Index for user lookup
CREATE INDEX idx_oauth_states_user_id ON oauth_states(user_id);

COMMENT ON TABLE oauth_states IS 'Temporary OAuth state tokens for CSRF protection during Monzo OAuth flow';
COMMENT ON COLUMN oauth_states.state IS 'Random state token sent to Monzo and verified on callback';
COMMENT ON COLUMN oauth_states.expires_at IS 'States expire after 10 minutes - OAuth should complete quickly';
COMMENT ON COLUMN oauth_states.used IS 'Prevents replay attacks - state can only be used once';
