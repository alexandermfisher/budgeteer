-- Encrypted verbatim provider JSON (AES-256-GCM via EncryptionService), populated by the sync
-- layer from Sourced<BankAccount>/Sourced<BankTransaction> rawJson(). NULL when the provider gave none.
-- Never stored plaintext: raw payloads carry bank identifiers (account_number, sort_code).
alter table monzo_accounts
    add column raw_payload_encrypted text null;

alter table monzo_transactions
    add column raw_payload_encrypted text null;

-- Supports the domain-mapping cursor: fetch raw rows re-touched since the last mapping run.
create index idx_monzo_txn_account_updated on monzo_transactions(account_id, updated_at);
