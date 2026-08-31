package dev.amfshr.budgeteer.domain.monzo;

import dev.amfshr.budgeteer.domain.user.User;
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
import java.util.UUID;

@Entity
@Table(name = "monzo_accounts")
public class MonzoAccount {

    @Id
    @Column(name = "id", nullable = false, length = 255)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false)
    private MonzoConnection connection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "account_type", nullable = false, length = 50)
    private String accountType;

    @Nullable
    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Nullable
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Nullable
    @Column(name = "last_transaction_id", length = 255)
    private String lastTransactionId;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    /** Account creation timestamp as reported by Monzo — used as the lower bound for backfill. */
    @Nullable
    @Column(name = "monzo_created_at")
    private Instant monzoCreatedAt;

    @Nullable
    @Column(name = "backfill_status", length = 32)
    private String backfillStatus;

    /** Upper bound of the next window to process; decreases as windows complete. Null = start from now. */
    @Nullable
    @Column(name = "backfill_progress_at")
    private Instant backfillProgressAt;

    /** Cursor (tx id) within the current in-flight window; persisted per page so mid-window 403s can resume. */
    @Nullable
    @Column(name = "backfill_progress_cursor", length = 255)
    private String backfillProgressCursor;

    // Encrypted verbatim provider JSON (AES-256-GCM). Never log — plaintext carries bank identifiers.
    @Nullable
    @Column(name = "raw_payload_encrypted", columnDefinition = "TEXT")
    private String rawPayloadEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonzoAccount() {
    }

    public MonzoAccount(
            String id,
            MonzoConnection connection,
            User user,
            String accountType,
            @Nullable String description,
            String currency,
            boolean closed
    ) {
        this.id = id;
        this.connection = connection;
        this.user = user;
        this.accountType = accountType;
        this.description = description;
        this.currency = currency;
        this.closed = closed;
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

    public void recordSyncComplete(@Nullable String latestTransactionId) {
        this.lastSyncedAt = Instant.now();
        if (latestTransactionId != null) {
            this.lastTransactionId = latestTransactionId;
        }
    }

    public String getId() { return id; }

    public MonzoConnection getConnection() { return connection; }

    public UUID getUserId() { return user.getId(); }

    public User getUser() { return user; }

    public String getAccountType() { return accountType; }

    @Nullable
    public String getDescription() { return description; }

    public String getCurrency() { return currency; }

    @Nullable
    public Instant getLastSyncedAt() { return lastSyncedAt; }

    @Nullable
    public String getLastTransactionId() { return lastTransactionId; }

    @Nullable
    public String getRawPayloadEncrypted() { return rawPayloadEncrypted; }

    public void setRawPayloadEncrypted(@Nullable String rawPayloadEncrypted) {
        this.rawPayloadEncrypted = rawPayloadEncrypted;
    }

    public boolean isClosed() { return closed; }

    @Nullable
    public Instant getMonzoCreatedAt() { return monzoCreatedAt; }

    public void setMonzoCreatedAt(@Nullable Instant monzoCreatedAt) { this.monzoCreatedAt = monzoCreatedAt; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public void setClosed(boolean closed) { this.closed = closed; }

    @Nullable
    public BackfillStatus getBackfillStatus() {
        return backfillStatus == null ? null : BackfillStatus.valueOf(backfillStatus);
    }

    public void setBackfillStatus(@Nullable BackfillStatus status) {
        this.backfillStatus = status == null ? null : status.name();
    }

    @Nullable
    public Instant getBackfillProgressAt() { return backfillProgressAt; }

    public void setBackfillProgressAt(@Nullable Instant backfillProgressAt) {
        this.backfillProgressAt = backfillProgressAt;
    }

    @Nullable
    public String getBackfillProgressCursor() { return backfillProgressCursor; }

    public void setBackfillProgressCursor(@Nullable String backfillProgressCursor) {
        this.backfillProgressCursor = backfillProgressCursor;
    }

    /** Lifecycle state for the initial historical backfill. */
    public enum BackfillStatus {
        /** Backfill is actively running. */
        IN_PROGRESS,
        /** All historical windows successfully fetched down to account creation. */
        COMPLETED,
        /** Monzo returned verification_required (SCA expired) — re-OAuth will resume from progress_at. */
        NEEDS_REAUTH
    }

    @Override
    public String toString() {
        return "MonzoAccount{id='" + id + "', accountType='" + accountType + "', closed=" + closed + '}';
    }
}
