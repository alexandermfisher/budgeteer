package dev.amf.budgeteer.repository;

import dev.amf.budgeteer.domain.monzo.MonzoAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MonzoAccountRepository extends JpaRepository<MonzoAccount, String> {

    @Query("SELECT a FROM MonzoAccount a WHERE a.connection.id = :connectionId")
    List<MonzoAccount> findByConnectionId(@Param("connectionId") UUID connectionId);

    @Query("SELECT a FROM MonzoAccount a WHERE a.user.id = :userId")
    List<MonzoAccount> findByUserId(@Param("userId") UUID userId);

    /**
     * Returns all non-closed accounts for a user — used for backfill status aggregation.
     */
    @Query("SELECT a FROM MonzoAccount a WHERE a.user.id = :userId AND a.closed = false")
    List<MonzoAccount> findActiveByUserId(@Param("userId") UUID userId);

    /**
     * Returns all non-closed accounts across all connections — used by the delta sync job.
     */
    @Query("SELECT a FROM MonzoAccount a WHERE a.closed = false")
    List<MonzoAccount> findAllSyncable();
}
