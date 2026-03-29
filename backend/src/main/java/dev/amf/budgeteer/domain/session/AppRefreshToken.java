package dev.amf.budgeteer.domain.session;

import dev.amf.budgeteer.domain.user.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * App refresh token entity for session management.
 * Stores SHA-256 hash of the refresh token - the plain token is never stored.
 * Refresh tokens have a 7-day lifetime and can be revoked on logout.
 */
@Entity
@Table(name = "app_refresh_tokens")
public class AppRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotBlank
    @Size(min = 64, max = 64)
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Size(max = 500)
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Size(max = 45)
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * Default constructor for JPA.
     */
    protected AppRefreshToken() {
    }

    /**
     * Creates a new refresh token.
     *
     * @param user      the user this token is for
     * @param tokenHash SHA-256 hash of the plain token
     * @param expiresAt when this token expires
     */
    public AppRefreshToken(User user, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /**
     * Creates a new refresh token with device info.
     *
     * @param user      the user this token is for
     * @param tokenHash SHA-256 hash of the plain token
     * @param expiresAt when this token expires
     * @param userAgent the browser/device user agent
     * @param ipAddress the client IP address
     */
    public AppRefreshToken(User user, String tokenHash, Instant expiresAt, 
                           String userAgent, String ipAddress) {
        this(user, tokenHash, expiresAt);
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
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
     * Checks if this token has been revoked.
     *
     * @return true if the token has been revoked
     */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Checks if this token is valid (not expired and not revoked).
     *
     * @return true if the token is valid
     */
    public boolean isValid() {
        return !isExpired() && !isRevoked();
    }

    /**
     * Revokes this token (e.g., on logout).
     */
    public void revoke() {
        this.revokedAt = Instant.now();
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    @Override
    public String toString() {
        return "AppRefreshToken{" +
                "id=" + id +
                ", userId=" + (user != null ? user.getId() : null) +
                ", expiresAt=" + expiresAt +
                ", revokedAt=" + revokedAt +
                ", createdAt=" + createdAt +
                '}';
    }
}
