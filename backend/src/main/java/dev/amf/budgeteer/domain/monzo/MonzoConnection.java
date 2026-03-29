package dev.amf.budgeteer.domain.monzo;

import dev.amf.budgeteer.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a user's connection to their Monzo bank account.
 *
 * <p>Stores encrypted OAuth tokens for accessing the Monzo API on behalf of the user.
 * A user can have multiple Monzo connections (e.g. personal and joint accounts).
 *
 * <h2>Security</h2>
 * <ul>
 *   <li>Tokens are stored encrypted using AES-256-GCM</li>
 *   <li>Encryption/decryption is handled by {@link dev.amf.budgeteer.service.common.EncryptionService}</li>
 *   <li>This entity stores the ENCRYPTED tokens, not plaintext</li>
 *   <li>Soft delete preserves encrypted tokens for audit trail</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>Created when user completes Monzo OAuth flow</li>
 *   <li>Updated when tokens are refreshed</li>
 *   <li>Soft-deleted when user disconnects (disconnectedAt is set)</li>
 * </ul>
 *
 * @see dev.amf.budgeteer.service.common.EncryptionService
 */
@Entity
@Table(name = "monzo_connections")
public class MonzoConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The app user who owns this Monzo connection.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Monzo user identifier from /ping/whoami endpoint.
     * Format: "user_xxxxx"
     */
    @NotBlank
    @Pattern(regexp = "user_[a-z0-9]+", message = "Invalid Monzo user ID format")
    @Column(name = "monzo_user_id", nullable = false, length = 255)
    private String monzoUserId;

    /**
     * AES-256-GCM encrypted access token.
     * Format: base64(IV + ciphertext + authTag)
     */
    @NotBlank
    @Column(name = "access_token_enc", nullable = false, columnDefinition = "TEXT")
    private String accessTokenEncrypted;

    /**
     * AES-256-GCM encrypted refresh token.
     * Format: base64(IV + ciphertext + authTag)
     */
    @NotBlank
    @Column(name = "refresh_token_enc", nullable = false, columnDefinition = "TEXT")
    private String refreshTokenEncrypted;

    /**
     * When the access token expires.
     * Used to determine if token refresh is needed before API calls.
     */
    @Column(name = "token_expires_at", nullable = false)
    private Instant tokenExpiresAt;

    /**
     * When this connection was first established.
     */
    @Column(name = "connected_at", nullable = false, updatable = false)
    private Instant connectedAt;

    /**
     * When tokens were last updated (e.g., after refresh).
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft delete timestamp. NULL means connection is active.
     * Set when user disconnects their Monzo account.
     */
    @Nullable
    @Column(name = "disconnected_at")
    private Instant disconnectedAt;

    /**
     * Default constructor for JPA.
     */
    protected MonzoConnection() {
    }

    /**
     * Creates a new Monzo connection.
     *
     * @param user                    the app user who owns this connection
     * @param monzoUserId             the Monzo user ID from /ping/whoami
     * @param accessTokenEncrypted    the AES-256-GCM encrypted access token
     * @param refreshTokenEncrypted   the AES-256-GCM encrypted refresh token
     * @param tokenExpiresAt          when the access token expires
     */
    public MonzoConnection(
            User user,
            String monzoUserId,
            String accessTokenEncrypted,
            String refreshTokenEncrypted,
            Instant tokenExpiresAt
    ) {
        this.user = user;
        this.monzoUserId = monzoUserId;
        this.accessTokenEncrypted = accessTokenEncrypted;
        this.refreshTokenEncrypted = refreshTokenEncrypted;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    @PrePersist
    protected void onCreate() {
        connectedAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // =========================================================================
    // Business Methods
    // =========================================================================

    /**
     * Checks if this connection is active (not disconnected).
     *
     * @return true if active, false if soft-deleted
     */
    public boolean isActive() {
        return disconnectedAt == null;
    }

    /**
     * Checks if the access token has expired.
     *
     * @return true if expired, false if still valid
     */
    public boolean isTokenExpired() {
        return Instant.now().isAfter(tokenExpiresAt);
    }

    /**
     * Soft-deletes this connection by setting disconnectedAt.
     */
    public void disconnect() {
        this.disconnectedAt = Instant.now();
    }

    /**
     * Reactivates a soft-deleted connection by clearing disconnectedAt.
     * Used when a user reconnects a previously disconnected Monzo account.
     */
    public void reactivate() {
        this.disconnectedAt = null;
    }

    /**
     * Updates the encrypted tokens after a token refresh.
     *
     * @param accessTokenEncrypted  the new encrypted access token
     * @param refreshTokenEncrypted the new encrypted refresh token
     * @param tokenExpiresAt        the new expiration time
     */
    public void updateTokens(
            String accessTokenEncrypted,
            String refreshTokenEncrypted,
            Instant tokenExpiresAt
    ) {
        this.accessTokenEncrypted = accessTokenEncrypted;
        this.refreshTokenEncrypted = refreshTokenEncrypted;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getMonzoUserId() {
        return monzoUserId;
    }

    public String getAccessTokenEncrypted() {
        return accessTokenEncrypted;
    }

    public String getRefreshTokenEncrypted() {
        return refreshTokenEncrypted;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Nullable
    public Instant getDisconnectedAt() {
        return disconnectedAt;
    }

    // =========================================================================
    // Setters (limited - prefer business methods)
    // =========================================================================

    /**
     * Sets the ID. Used for testing only.
     *
     * @param id the ID to set
     */
    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public String toString() {
        // Never log encrypted tokens!
        return "MonzoConnection{" +
                "id=" + id +
                ", userId=" + (user != null ? user.getId() : null) +
                ", monzoUserId='" + monzoUserId + '\'' +
                ", tokenExpiresAt=" + tokenExpiresAt +
                ", connectedAt=" + connectedAt +
                ", isActive=" + isActive() +
                '}';
    }
}
