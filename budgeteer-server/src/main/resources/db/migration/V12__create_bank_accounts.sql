-- Provider-agnostic domain accounts, mapped from the provider-shaped raw tables (monzo_*).
-- Deliberately no FK to monzo_connections: the domain layer outlives any one provider link.
create table bank_accounts (
    id                       uuid primary key,
    user_id                  uuid not null references users(id) on delete cascade,
    provider                 varchar(32)  not null,
    provider_account_id      varchar(255) not null,
    account_type             varchar(32)  not null,
    institution_name         varchar(100) not null,
    display_name             varchar(255),
    currency                 varchar(3)   not null,
    balance_minor_units      bigint,
    balance_as_of            timestamp with time zone,
    credit_limit_minor_units bigint,
    display_order            integer not null default 0,
    archived_at              timestamp with time zone,
    raw_synced_through       timestamp with time zone,
    created_at               timestamp with time zone not null default now(),
    updated_at               timestamp with time zone not null default now(),
    constraint uq_bank_accounts_provider_account unique (provider, provider_account_id)
);

create index idx_bank_accounts_user        on bank_accounts(user_id);
create index idx_bank_accounts_user_active on bank_accounts(user_id) where archived_at is null;
