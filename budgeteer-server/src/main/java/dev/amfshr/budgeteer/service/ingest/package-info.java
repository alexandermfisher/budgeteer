/**
 * Raw → domain ingest pipeline: maps provider-shaped raw tables ({@code monzo_*}) into the
 * provider-agnostic domain layer ({@code bank_accounts}, {@code transactions}), plus provider
 * balance snapshots.
 *
 * <p>Cursor-driven (raw {@code updated_at}), idempotent, and re-run safe: declined rows are
 * never mapped, user-owned fields are never overwritten, and closed/disconnected accounts are
 * archived rather than deleted.
 */
@NullMarked
package dev.amfshr.budgeteer.service.ingest;

import org.jspecify.annotations.NullMarked;
