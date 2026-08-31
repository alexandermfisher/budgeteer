-- Domain transactions (read model). Rows are written only by the native upsert keyed on
-- (provider, provider_transaction_id); user-owned columns (notes, excluded_from_analytics)
-- are seeded on insert and never overwritten on re-map.
create table transactions (
    id                      uuid primary key,
    user_id                 uuid not null references users(id)         on delete cascade,
    account_id              uuid not null references bank_accounts(id) on delete cascade,
    provider                varchar(32)  not null,
    provider_transaction_id varchar(255) not null,
    amount_minor_units      bigint       not null,
    currency                varchar(3)   not null,
    status                  varchar(16)  not null,
    description             varchar(500),
    merchant_name           varchar(255),
    merchant_category       varchar(100),
    notes                   text,
    excluded_from_analytics boolean not null default false,
    occurred_at             timestamp with time zone not null,
    settled_at              timestamp with time zone,
    created_at              timestamp with time zone not null default now(),
    updated_at              timestamp with time zone not null default now(),
    constraint uq_transactions_provider_tx unique (provider, provider_transaction_id)
);

create index idx_transactions_user_occurred    on transactions(user_id, occurred_at desc);
create index idx_transactions_account_occurred on transactions(account_id, occurred_at desc);
