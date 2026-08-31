package dev.amfshr.budgeteer.repository;

import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.account.Provider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Query("SELECT a FROM Account a WHERE a.user.id = :userId ORDER BY a.displayOrder ASC, a.createdAt ASC")
    List<Account> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT a FROM Account a WHERE a.user.id = :userId AND a.archivedAt IS NULL "
            + "ORDER BY a.displayOrder ASC, a.createdAt ASC")
    List<Account> findActiveByUserId(@Param("userId") UUID userId);

    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.user.id = :userId")
    Optional<Account> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    Optional<Account> findByProviderAndProviderAccountId(Provider provider, String providerAccountId);
}
