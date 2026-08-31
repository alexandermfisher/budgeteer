/**
 * Domain transactions — the product read model above the raw provider tables.
 *
 * <p>All writes go through {@code TransactionRepository.upsert} (native, idempotent, keyed on
 * {@code (provider, provider_transaction_id)}); the entity exists for reads only. User-owned
 * fields ({@code notes}, {@code excludedFromAnalytics}) are never overwritten on re-map.
 */
@NullMarked
package dev.amfshr.budgeteer.domain.transaction;

import org.jspecify.annotations.NullMarked;
