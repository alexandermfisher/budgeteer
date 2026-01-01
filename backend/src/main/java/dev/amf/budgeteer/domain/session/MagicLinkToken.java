package dev.amf.budgeteer.domain.session;

import dev.amf.budgeteer.domain.user.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Magic link token entity for passwordless authentication.
 * Stores SHA-256 hash of the token - the plain token is never stored.
 * Tokens are single-use and expire after 15 minutes.
 */
@Entity
@Table(name = "magic_link_tokens")
public class MagicLinkToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Default constructor for JPA.
     */
    protected MagicLinkToken() {
    }

    /**
     * Creates a new magic link token.
     *
     * @param user      the user this token is for
     * @param tokenHash SHA-256 hash of the plain token
     * @param expiresAt when this token expires
     */
    public MagicLinkToken(User user, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * Checks if this token has expired.
     *
     * @return true if the token has expired
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Checks if this token has already been used.
     *
     * @return true if the token has been used
     */
    public boolean isUsed() {
        return usedAt != null;
    }

    /**
     * Checks if this token is valid (not expired and not used).
     *
     * @return true if the token is valid
     */
    public boolean isValid() {
        return !isExpired() && !isUsed();
    }

    /**
     * Marks this token as used.
     */
    public void markAsUsed() {
        this.usedAt = Instant.now();
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "MagicLinkToken{" +
                "id=" + id +
                ", userId=" + (user != null ? user.getId() : null) +
                ", expiresAt=" + expiresAt +
                ", usedAt=" + usedAt +
                ", createdAt=" + createdAt +
                '}';
    }
}
