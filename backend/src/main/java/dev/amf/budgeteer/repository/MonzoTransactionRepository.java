package dev.amf.budgeteer.repository;

import dev.amf.budgeteer.domain.monzo.MonzoTransaction;
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

    /**
     * Native upsert for backfill — handles re-runs and pending→settled transitions
     * without SELECT+UPDATE per row.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO monzo_transactions
                (id, account_id, user_id, amount, currency, description,
                 merchant_name, merchant_category, notes, is_declined,
                 monzo_created_at, monzo_settled_at, created_at, updated_at)
            VALUES
                (:id, :accountId, :userId, :amount, :currency, :description,
                 :merchantName, :merchantCategory, :notes, :isDeclined,
                 :monzoCreatedAt, :monzoSettledAt, now(), now())
            ON CONFLICT (id) DO UPDATE SET
                amount             = EXCLUDED.amount,
                monzo_settled_at   = EXCLUDED.monzo_settled_at,
                notes              = EXCLUDED.notes,
                is_declined        = EXCLUDED.is_declined,
                updated_at         = now()
            """)
    void upsert(
            @Param("id") String id,
            @Param("accountId") String accountId,
            @Param("userId") UUID userId,
            @Param("amount") int amount,
            @Param("currency") String currency,
            @Param("description") String description,
            @Param("merchantName") String merchantName,
            @Param("merchantCategory") String merchantCategory,
            @Param("notes") String notes,
            @Param("isDeclined") boolean isDeclined,
            @Param("monzoCreatedAt") Instant monzoCreatedAt,
            @Param("monzoSettledAt") Instant monzoSettledAt
    );
}
