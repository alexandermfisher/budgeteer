package dev.amfshr.budgeteer.domain.oauth;

import dev.amfshr.budgeteer.domain.user.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an OAuth state token for CSRF protection.
 *
 * <p>During the OAuth flow, we generate a random state parameter that is sent to Monzo
 * and returned in the callback. This entity stores that state along with the associated
 * user, allowing us to:
 * <ol>
 *   <li>Verify the callback is legitimate (CSRF protection)</li>
 *   <li>Associate the OAuth tokens with the correct user</li>
 *   <li>Prevent replay attacks (state can only be used once)</li>
 * </ol>
 *
 * <p>States are short-lived (10 minutes) and should be cleaned up after expiry.
 */
@Entity
@Table(name = "oauth_states")
public class OAuthState {

    /**
     * Default state expiry time in minutes.
     */
    public static final int STATE_EXPIRY_MINUTES = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotBlank
    @Size(min = 32, max = 64)
    @Column(name = "state", nullable = false, unique = true, length = 64)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used;

    /**
     * Default constructor for JPA.
     */
    protected OAuthState() {
    }

    /**
     * Creates a new OAuth state for a user.
     *
     * @param user  the user initiating the OAuth flow
     * @param state the random state token
     */
    public OAuthState(User user, String state) {
        this.user = user;
        this.state = state;
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plusSeconds(STATE_EXPIRY_MINUTES * 60L);
        this.used = false;
    }

    /**
     * Creates a new OAuth state with custom expiry.
     *
     * @param user      the user initiating the OAuth flow
     * @param state     the random state token
     * @param expiresAt when the state expires
     */
    public OAuthState(User user, String state, Instant expiresAt) {
        this.user = user;
        this.state = state;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.used = false;
    }

    // ============ Getters ============

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isUsed() {
        return used;
    }

    // ============ Business Methods ============

    /**
     * Checks if this state has expired.
     *
     * @return true if the current time is past the expiry time
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Checks if this state is valid (not expired and not used).
     *
     * @return true if the state can be used for OAuth callback verification
     */
    public boolean isValid() {
        return !isExpired() && !used;
    }

    /**
     * Marks this state as used to prevent replay attacks.
     *
     * <p>Once a state is used, it cannot be used again even if not expired.
     */
    public void markUsed() {
        this.used = true;
    }

    @Override
    public String toString() {
        return "OAuthState{"
                + "id=" + id
                + ", userId=" + user.getId()
                + ", state='" + state.substring(0, Math.min(8, state.length())) + "..." + '\''
                + ", createdAt=" + createdAt
                + ", expiresAt=" + expiresAt
                + ", used=" + used
                + ", expired=" + isExpired()
                + '}';
    }
}
