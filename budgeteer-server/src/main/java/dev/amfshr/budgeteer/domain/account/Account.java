package dev.amfshr.budgeteer.domain.account;

import dev.amfshr.budgeteer.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Provider-agnostic domain account. Identity is stable across disconnect/reconnect via the
 * {@code (provider, provider_account_id)} unique key — reconnecting revives the same row
 * (and its transaction history) rather than creating a new one.
 */
@Entity
@Table(name = "bank_accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private Provider provider;

    @Column(name = "provider_account_id", nullable = false, length = 255)
    private String providerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private AccountType accountType;

    @Column(name = "institution_name", nullable = false, length = 100)
    private String institutionName;

    @Nullable
    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Stored provider snapshot (L3) — null until the first balance refresh. */
    @Nullable
    @Column(name = "balance_minor_units")
    private Long balanceMinorUnits;

    @Nullable
    @Column(name = "balance_as_of")
    private Instant balanceAsOf;

    @Nullable
    @Column(name = "credit_limit_minor_units")
    private Long creditLimitMinorUnits;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Nullable
    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Mapping cursor: max raw {@code updated_at} ingested so far (decision 2). */
    @Nullable
    @Column(name = "raw_synced_through")
    private Instant rawSyncedThrough;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public Account(
            User user,
            Provider provider,
            String providerAccountId,
            AccountType accountType,
            String institutionName,
            @Nullable String displayName,
            String currency
    ) {
        this.user = user;
        this.provider = provider;
        this.providerAccountId = providerAccountId;
        this.accountType = accountType;
        this.institutionName = institutionName;
        this.displayName = displayName;
        this.currency = currency;
        this.displayOrder = 0;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isArchived() { return archivedAt != null; }

    public void archive() {
        if (archivedAt == null) {
            archivedAt = Instant.now();
        }
    }

    public void unarchive() { archivedAt = null; }

    /** Records a provider balance snapshot (both columns together — value without a time lies). */
    public void recordBalance(long balanceMinorUnits, Instant asOf) {
        this.balanceMinorUnits = balanceMinorUnits;
        this.balanceAsOf = asOf;
    }

    public UUID getId() { return id; }

    public User getUser() { return user; }

    public Provider getProvider() { return provider; }

    public String getProviderAccountId() { return providerAccountId; }

    public AccountType getAccountType() { return accountType; }

    public void setAccountType(AccountType accountType) { this.accountType = accountType; }

    public String getInstitutionName() { return institutionName; }

    @Nullable
    public String getDisplayName() { return displayName; }

    public String getCurrency() { return currency; }

    @Nullable
    public Long getBalanceMinorUnits() { return balanceMinorUnits; }

    @Nullable
    public Instant getBalanceAsOf() { return balanceAsOf; }

    @Nullable
    public Long getCreditLimitMinorUnits() { return creditLimitMinorUnits; }

    public int getDisplayOrder() { return displayOrder; }

    @Nullable
    public Instant getArchivedAt() { return archivedAt; }

    @Nullable
    public Instant getRawSyncedThrough() { return rawSyncedThrough; }

    public void setRawSyncedThrough(@Nullable Instant rawSyncedThrough) {
        this.rawSyncedThrough = rawSyncedThrough;
    }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Account{id=" + id + ", provider=" + provider
                + ", providerAccountId='" + providerAccountId + "', type=" + accountType + "}";
    }
}
