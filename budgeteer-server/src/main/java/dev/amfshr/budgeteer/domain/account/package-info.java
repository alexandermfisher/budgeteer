/**
 * Provider-agnostic domain accounts — the product layer above the provider-shaped raw tables.
 *
 * <p>Rows are created and refreshed by the ingest pipeline
 * ({@link dev.amfshr.budgeteer.service.ingest.MonzoIngestor}); balances are stored provider
 * snapshots, never derived from transactions.
 */
@NullMarked
package dev.amfshr.budgeteer.domain.account;

import org.jspecify.annotations.NullMarked;
