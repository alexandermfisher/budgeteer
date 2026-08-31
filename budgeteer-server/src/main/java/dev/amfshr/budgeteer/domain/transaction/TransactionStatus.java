package dev.amfshr.budgeteer.domain.transaction;

/**
 * Settlement status. PENDING flips to SETTLED when the raw layer learns of settlement
 * (the raw upsert bumps {@code updated_at}, so the ingest cursor re-maps the row).
 */
public enum TransactionStatus {
    PENDING,
    SETTLED
}
