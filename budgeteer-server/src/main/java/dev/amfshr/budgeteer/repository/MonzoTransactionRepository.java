package dev.amfshr.budgeteer.repository;

import dev.amfshr.budgeteer.domain.monzo.MonzoTransaction;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MonzoTransactionRepository extends JpaRepository<MonzoTransaction, String> {

    @Query("SELECT t FROM MonzoTransaction t WHERE t.account.id = :accountId ORDER BY t.monzoCreatedAt DESC")
    List<MonzoTransaction> findByAccountId(@Param("accountId") String accountId);

    @Query("SELECT t FROM MonzoTransaction t WHERE t.user.id = :userId ORDER BY t.monzoCreatedAt DESC")
    List<MonzoTransaction> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT COUNT(t) FROM MonzoTransaction t WHERE t.account.id = :accountId")
    long countByAccountId(@Param("accountId") String accountId);

    /** Ingest cursor query: raw rows re-touched since the last mapping run (insert OR re-upsert). */
    @Query("SELECT t FROM MonzoTransaction t WHERE t.account.id = :accountId AND t.updatedAt > :after "
            + "ORDER BY t.updatedAt ASC")
    List<MonzoTransaction> findByAccountIdUpdatedAfter(@Param("accountId") String accountId,
            @Param("after") Instant after);

    @Modifying
    @Query("DELETE FROM MonzoTransaction t WHERE t.account.id = :accountId")
    void deleteByAccountId(@Param("accountId") String accountId);

    /**
     * Native upsert for backfill — handles re-runs and pending→settled transitions
     * without SELECT+UPDATE per row.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO monzo_transactions
                (id, account_id, user_id, amount, currency, description,
                 merchant_name, merchant_category, notes, is_declined,
                 monzo_created_at, monzo_settled_at, raw_payload_encrypted, created_at, updated_at)
            VALUES
                (:id, :accountId, :userId, :amount, :currency, :description,
                 :merchantName, :merchantCategory, :notes, :isDeclined,
                 :monzoCreatedAt, :monzoSettledAt, :rawPayloadEncrypted, now(), now())
            ON CONFLICT (id) DO UPDATE SET
                amount                = EXCLUDED.amount,
                monzo_settled_at      = EXCLUDED.monzo_settled_at,
                notes                 = EXCLUDED.notes,
                is_declined           = EXCLUDED.is_declined,
                raw_payload_encrypted = EXCLUDED.raw_payload_encrypted,
                updated_at            = now()
            """)
    void upsert(
            @Param("id") String id,
            @Param("accountId") String accountId,
            @Param("userId") UUID userId,
            @Param("amount") int amount,
            @Param("currency") String currency,
            @Nullable @Param("description") String description,
            @Nullable @Param("merchantName") String merchantName,
            @Nullable @Param("merchantCategory") String merchantCategory,
            @Nullable @Param("notes") String notes,
            @Param("isDeclined") boolean isDeclined,
            @Param("monzoCreatedAt") Instant monzoCreatedAt,
            @Nullable @Param("monzoSettledAt") Instant monzoSettledAt,
            @Nullable @Param("rawPayloadEncrypted") String rawPayloadEncrypted
    );
}
