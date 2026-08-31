package dev.amfshr.budgeteer.repository;

import dev.amfshr.budgeteer.domain.transaction.Transaction;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Idempotent domain upsert. The update set DELIBERATELY omits notes and
     * excluded_from_analytics (user-owned — decision 3 / L2); slice 2 adds category fields
     * to the same omission list.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO transactions
                (id, user_id, account_id, provider, provider_transaction_id,
                 amount_minor_units, currency, status, description, merchant_name,
                 merchant_category, notes, excluded_from_analytics, occurred_at, settled_at,
                 created_at, updated_at)
            VALUES
                (gen_random_uuid(), :userId, :accountId, :provider, :providerTransactionId,
                 :amountMinorUnits, :currency, :status, :description, :merchantName,
                 :merchantCategory, :notes, false, :occurredAt, :settledAt, now(), now())
            ON CONFLICT (provider, provider_transaction_id) DO UPDATE SET
                amount_minor_units = EXCLUDED.amount_minor_units,
                status             = EXCLUDED.status,
                description        = EXCLUDED.description,
                merchant_name      = EXCLUDED.merchant_name,
                merchant_category  = EXCLUDED.merchant_category,
                settled_at         = EXCLUDED.settled_at,
                updated_at         = now()
            """)
    void upsert(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
            @Param("provider") String provider,
            @Param("providerTransactionId") String providerTransactionId,
            @Param("amountMinorUnits") long amountMinorUnits, @Param("currency") String currency,
            @Param("status") String status, @Nullable @Param("description") String description,
            @Nullable @Param("merchantName") String merchantName,
            @Nullable @Param("merchantCategory") String merchantCategory,
            @Nullable @Param("notes") String notes, @Param("occurredAt") Instant occurredAt,
            @Nullable @Param("settledAt") Instant settledAt);

    /**
     * Filtered page. Service defaults from/to (EPOCH / far-future) so no nullable-timestamp JPQL
     * issues; accountId stays a nullable typed param. Pass an UNSORTED Pageable — order lives in
     * the JPQL.
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE t.user.id = :userId
              AND (:accountId IS NULL OR t.account.id = :accountId)
              AND t.occurredAt >= :from AND t.occurredAt < :to
            ORDER BY t.occurredAt DESC, t.id DESC
            """)
    Page<Transaction> findFiltered(@Param("userId") UUID userId,
            @Nullable @Param("accountId") UUID accountId,
            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /**
     * Six sums in one round trip via FILTER; :floor = min(weekStart, monthStart) — a week can
     * start in the previous month. Signed sums; service converts out to positive magnitude.
     */
    @Query(nativeQuery = true, value = """
            SELECT
              coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units > 0 AND occurred_at >= :todayStart), 0) AS today_in,
              coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units < 0 AND occurred_at >= :todayStart), 0) AS today_out,
              coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units > 0 AND occurred_at >= :weekStart), 0)  AS week_in,
              coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units < 0 AND occurred_at >= :weekStart), 0)  AS week_out,
              coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units > 0 AND occurred_at >= :monthStart), 0) AS month_in,
              coalesce(sum(amount_minor_units) FILTER (WHERE amount_minor_units < 0 AND occurred_at >= :monthStart), 0) AS month_out
            FROM transactions
            WHERE account_id = :accountId AND user_id = :userId
              AND excluded_from_analytics = false
              AND occurred_at >= :floor
            """)
    WindowSumsProjection sumWindows(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
            @Param("todayStart") Instant todayStart, @Param("weekStart") Instant weekStart,
            @Param("monthStart") Instant monthStart, @Param("floor") Instant floor);

    /** Interface projection for the native sums (alias-matched getters). */
    interface WindowSumsProjection {
        long getTodayIn();

        long getTodayOut();

        long getWeekIn();

        long getWeekOut();

        long getMonthIn();

        long getMonthOut();
    }
}
