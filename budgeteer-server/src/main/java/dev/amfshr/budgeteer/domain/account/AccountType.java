package dev.amfshr.budgeteer.domain.account;

/**
 * Normalised account type. Unknown raw provider types map to {@link #OTHER} (with a WARN) —
 * never mislabelled, never dropped.
 */
public enum AccountType {
    CURRENT,
    SAVINGS,
    CREDIT_CARD,
    OTHER
}
