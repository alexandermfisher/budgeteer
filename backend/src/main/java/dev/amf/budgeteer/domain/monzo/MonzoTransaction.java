package dev.amf.budgeteer.domain.monzo;

import dev.amf.budgeteer.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

@Entity
@Table(name = "monzo_transactions")
public class MonzoTransaction {

    @Id
    @Column(name = "id", nullable = false, length = 255)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private MonzoAccount account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Nullable
    @Column(name = "description", length = 500)
    private String description;

    @Nullable
    @Column(name = "merchant_name", length = 255)
    private String merchantName;

    @Nullable
    @Column(name = "merchant_category", length = 100)
    private String merchantCategory;

    @Nullable
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "is_declined", nullable = false)
    private boolean isDeclined;

    @Column(name = "monzo_created_at", nullable = false)
    private Instant monzoCreatedAt;

    @Nullable
    @Column(name = "monzo_settled_at")
    private Instant monzoSettledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonzoTransaction() {
    }

    public MonzoTransaction(
            String id,
            MonzoAccount account,
            User user,
            int amount,
            String currency,
            @Nullable String description,
            @Nullable String merchantName,
            @Nullable String merchantCategory,
            @Nullable String notes,
            boolean isDeclined,
            Instant monzoCreatedAt,
            @Nullable Instant monzoSettledAt
    ) {
        this.id = id;
        this.account = account;
        this.user = user;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.merchantName = merchantName;
        this.merchantCategory = merchantCategory;
        this.notes = notes;
        this.isDeclined = isDeclined;
        this.monzoCreatedAt = monzoCreatedAt;
        this.monzoSettledAt = monzoSettledAt;
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

    public String getId() { return id; }

    public MonzoAccount getAccount() { return account; }

    public User getUser() { return user; }

    public int getAmount() { return amount; }

    public String getCurrency() { return currency; }

    @Nullable
    public String getDescription() { return description; }

    @Nullable
    public String getMerchantName() { return merchantName; }

    @Nullable
    public String getMerchantCategory() { return merchantCategory; }

    @Nullable
    public String getNotes() { return notes; }

    public boolean isDeclined() { return isDeclined; }

    public Instant getMonzoCreatedAt() { return monzoCreatedAt; }

    @Nullable
    public Instant getMonzoSettledAt() { return monzoSettledAt; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public void updateFromMonzo(
            int amount,
            @Nullable Instant monzoSettledAt,
            @Nullable String notes,
            boolean isDeclined
    ) {
        this.amount = amount;
        this.monzoSettledAt = monzoSettledAt;
        this.notes = notes;
        this.isDeclined = isDeclined;
    }

    @Override
    public String toString() {
        return "MonzoTransaction{id='" + id + "', amount=" + amount + ", currency='" + currency + "'}";
    }
}
