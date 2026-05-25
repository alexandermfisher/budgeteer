CREATE TABLE monzo_transactions (
    id                  VARCHAR(255) PRIMARY KEY,
    account_id          VARCHAR(255) NOT NULL REFERENCES monzo_accounts(id) ON DELETE CASCADE,
    user_id             UUID         NOT NULL REFERENCES users(id)          ON DELETE CASCADE,
    amount              INTEGER      NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    description         VARCHAR(500),
    merchant_name       VARCHAR(255),
    merchant_category   VARCHAR(100),
    notes               TEXT,
    is_declined         BOOLEAN NOT NULL DEFAULT false,
    monzo_created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    monzo_settled_at    TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_monzo_txn_user_account    ON monzo_transactions(user_id, account_id);
CREATE INDEX idx_monzo_txn_user_created    ON monzo_transactions(user_id, monzo_created_at DESC);
CREATE INDEX idx_monzo_txn_account_created ON monzo_transactions(account_id, monzo_created_at DESC);
