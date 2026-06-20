CREATE TABLE monzo_accounts (
    id                  VARCHAR(255) PRIMARY KEY,
    connection_id       UUID NOT NULL REFERENCES monzo_connections(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_type        VARCHAR(50)  NOT NULL,
    description         VARCHAR(500),
    currency            VARCHAR(3)   NOT NULL,
    last_synced_at      TIMESTAMP WITH TIME ZONE NULL,
    last_transaction_id VARCHAR(255) NULL,
    closed              BOOLEAN NOT NULL DEFAULT false,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_monzo_accounts_connection_id ON monzo_accounts(connection_id);
CREATE INDEX idx_monzo_accounts_user_id       ON monzo_accounts(user_id);
CREATE INDEX idx_monzo_accounts_active        ON monzo_accounts(connection_id) WHERE closed = false;
