package dev.amfshr.budgeteer.domain.transaction;

import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.account.Provider;
import dev.amfshr.budgeteer.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain transaction — read model only. All writes go through the native upsert in
 * {@code TransactionRepository} (id generated in SQL), so this entity has no public
 * constructor and no setters.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private Provider provider;

    @Column(name = "provider_transaction_id", nullable = false, length = 255)
    private String providerTransactionId;

    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransactionStatus status;

    @Nullable
    @Column(name = "description", length = 500)
    private String description;

    @Nullable
    @Column(name = "merchant_name", length = 255)
    private String merchantName;

    @Nullable
    @Column(name = "merchant_category", length = 100)
    private String merchantCategory;

    /** User-owned: seeded on insert, never overwritten by re-maps (decision 3 / L2). */
    @Nullable
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** User-owned: never touched by re-maps. */
    @Column(name = "excluded_from_analytics", nullable = false)
    private boolean excludedFromAnalytics;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Nullable
    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transaction() {
    }

    public UUID getId() { return id; }

    public User getUser() { return user; }

    public Account getAccount() { return account; }

    public Provider getProvider() { return provider; }

    public String getProviderTransactionId() { return providerTransactionId; }

    public long getAmountMinorUnits() { return amountMinorUnits; }

    public String getCurrency() { return currency; }

    public TransactionStatus getStatus() { return status; }

    @Nullable
    public String getDescription() { return description; }

    @Nullable
    public String getMerchantName() { return merchantName; }

    @Nullable
    public String getMerchantCategory() { return merchantCategory; }

    @Nullable
    public String getNotes() { return notes; }

    public boolean isExcludedFromAnalytics() { return excludedFromAnalytics; }

    public Instant getOccurredAt() { return occurredAt; }

    @Nullable
    public Instant getSettledAt() { return settledAt; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Transaction{id=" + id + ", provider=" + provider
                + ", providerTransactionId='" + providerTransactionId
                + "', amountMinorUnits=" + amountMinorUnits + ", status=" + status + "}";
    }
}
