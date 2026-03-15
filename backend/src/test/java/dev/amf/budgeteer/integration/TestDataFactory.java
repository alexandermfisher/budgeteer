package dev.amf.budgeteer.integration;

import dev.amf.budgeteer.domain.monzo.MonzoConnection;
import dev.amf.budgeteer.domain.monzo.MonzoConnectionRepository;
import dev.amf.budgeteer.domain.oauth.OAuthState;
import dev.amf.budgeteer.domain.oauth.OAuthStateRepository;
import dev.amf.budgeteer.domain.session.AppRefreshToken;
import dev.amf.budgeteer.domain.session.AppRefreshTokenRepository;
import dev.amf.budgeteer.domain.session.MagicLinkToken;
import dev.amf.budgeteer.domain.session.MagicLinkTokenRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.domain.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Factory for creating test data in integration tests.
 * 
 * <p>Provides convenient methods to create entities with sensible defaults,
 * while allowing
 * customisation where needed. All data is created in the
 * database and ready for use in tests.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>{@code
 * @Autowired
 * private TestDataFactory testData;
 * 
 * @Test
 * void testSomething() {
 *     User user = testData.createUser();
 *     MagicLinkToken token = testData.createValidMagicLinkFor(user);
 *     // ...
 * }
 * }</pre>
 * 
 * <p>Use this for dynamic test data that needs unique values (emails, tokens).
 * For static reference data, consider SQL scripts loaded via {@code @Sql}.</p>
 */
@Component
@Transactional
public class TestDataFactory {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MagicLinkTokenRepository magicLinkTokenRepository;

    @Autowired
    private AppRefreshTokenRepository refreshTokenRepository;

    @Autowired
    private OAuthStateRepository oAuthStateRepository;

    @Autowired
    private MonzoConnectionRepository monzoConnectionRepository;

    // ========================================================================
    // User creation
    // ========================================================================

    /**
     * Creates a user with a random email address.
     */
    public User createUser() {
        return createUser("test-" + UUID.randomUUID() + "@example.com");
    }

    /**
     * Creates a user with the specified email address.
     */
    public User createUser(String email) {
        User user = new User(email);
        return userRepository.save(user);
    }

    /**
     * Creates a user with a verified email address.
     */
    public User createVerifiedUser() {
        return createVerifiedUser("verified-" + UUID.randomUUID() + "@example.com");
    }

    /**
     * Creates a user with a verified email address.
     */
    public User createVerifiedUser(String email) {
        User user = new User(email);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    // ========================================================================
    // Magic Link Token creation
    // ========================================================================

    /**
     * Creates a valid (unexpired, unused) magic link token for the user.
     * Returns both the raw token (for testing verification) and the persisted entity.
     */
    public MagicLinkTokenResult createValidMagicLinkFor(User user) {
        String rawToken = generateRandomToken();
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);

        MagicLinkToken token = new MagicLinkToken(user, tokenHash, expiresAt);
        MagicLinkToken saved = magicLinkTokenRepository.save(token);

        return new MagicLinkTokenResult(rawToken, saved);
    }

    /**
     * Creates an expired magic link token for the user.
     * Returns both the raw token (for testing verification) and the persisted entity.
     */
    public MagicLinkTokenResult createExpiredMagicLinkFor(User user) {
        String rawToken = generateRandomToken();
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now().minus(1, ChronoUnit.HOURS);

        MagicLinkToken token = new MagicLinkToken(user, tokenHash, expiresAt);
        MagicLinkToken saved = magicLinkTokenRepository.save(token);

        return new MagicLinkTokenResult(rawToken, saved);
    }

    /**
     * Creates a used magic link token for the user.
     */
    public MagicLinkToken createUsedMagicLinkFor(User user) {
        String tokenHash = hashToken(generateRandomToken());
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);

        MagicLinkToken token = new MagicLinkToken(user, tokenHash, expiresAt);
        token.markAsUsed();
        return magicLinkTokenRepository.save(token);
    }

    // ========================================================================
    // App Refresh Token creation
    // ========================================================================

    /**
     * Creates a valid (unexpired, not revoked) refresh token for the user.
     * Returns both the raw token and the persisted entity.
     */
    public RefreshTokenResult createValidRefreshTokenFor(User user) {
        String rawToken = generateRandomToken();
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        AppRefreshToken token = new AppRefreshToken(user, tokenHash, expiresAt);
        AppRefreshToken saved = refreshTokenRepository.save(token);

        return new RefreshTokenResult(rawToken, saved);
    }

    /**
     * Creates a valid refresh token with device info.
     */
    public RefreshTokenResult createValidRefreshTokenFor(User user, String userAgent, String ipAddress) {
        String rawToken = generateRandomToken();
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        AppRefreshToken token = new AppRefreshToken(user, tokenHash, expiresAt, userAgent, ipAddress);
        AppRefreshToken saved = refreshTokenRepository.save(token);

        return new RefreshTokenResult(rawToken, saved);
    }

    /**
     * Creates an expired refresh token for the user.
     */
    public AppRefreshToken createExpiredRefreshTokenFor(User user) {
        String tokenHash = hashToken(generateRandomToken());
        Instant expiresAt = Instant.now().minus(1, ChronoUnit.HOURS);

        AppRefreshToken token = new AppRefreshToken(user, tokenHash, expiresAt);
        return refreshTokenRepository.save(token);
    }

    /**
     * Creates a revoked refresh token for the user.
     */
    public AppRefreshToken createRevokedRefreshTokenFor(User user) {
        String tokenHash = hashToken(generateRandomToken());
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        AppRefreshToken token = new AppRefreshToken(user, tokenHash, expiresAt);
        token.revoke();
        return refreshTokenRepository.save(token);
    }

    // ========================================================================
    // OAuth State creation
    // ========================================================================

    /**
     * Creates a valid (unexpired, unused) OAuth state for the user.
     */
    public OAuthState createValidOAuthStateFor(User user) {
        String stateToken = generateRandomToken();
        OAuthState state = new OAuthState(user, stateToken);
        return oAuthStateRepository.save(state);
    }

    /**
     * Creates a valid OAuth state with a specific state token.
     */
    public OAuthState createValidOAuthStateFor(User user, String stateToken) {
        OAuthState state = new OAuthState(user, stateToken);
        return oAuthStateRepository.save(state);
    }

    /**
     * Creates an expired OAuth state for the user.
     */
    public OAuthState createExpiredOAuthStateFor(User user) {
        String stateToken = generateRandomToken();
        Instant expiredAt = Instant.now().minus(1, ChronoUnit.HOURS);
        OAuthState state = new OAuthState(user, stateToken, expiredAt);
        return oAuthStateRepository.save(state);
    }

    /**
     * Creates a used OAuth state for the user.
     */
    public OAuthState createUsedOAuthStateFor(User user) {
        String stateToken = generateRandomToken();
        OAuthState state = new OAuthState(user, stateToken);
        state.markUsed();
        return oAuthStateRepository.save(state);
    }

    // ========================================================================
    // Monzo Connection creation
    // ========================================================================

    /**
     * Creates an active Monzo connection for the user with random encrypted tokens.
     */
    public MonzoConnection createActiveMonzoConnectionFor(User user) {
        return createActiveMonzoConnectionFor(user, "user_" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Creates an active Monzo connection for the user with a specific Monzo user ID.
     */
    public MonzoConnection createActiveMonzoConnectionFor(User user, String monzoUserId) {
        // Simulate encrypted tokens (in real usage, these would be AES-256-GCM encrypted)
        String fakeEncryptedAccessToken = "encrypted_access_" + generateRandomToken();
        String fakeEncryptedRefreshToken = "encrypted_refresh_" + generateRandomToken();
        Instant tokenExpiresAt = Instant.now().plus(6, ChronoUnit.HOURS);

        MonzoConnection connection = new MonzoConnection(
                user,
                monzoUserId,
                fakeEncryptedAccessToken,
                fakeEncryptedRefreshToken,
                tokenExpiresAt
        );
        return monzoConnectionRepository.save(connection);
    }

    /**
     * Creates a Monzo connection with expired tokens.
     */
    public MonzoConnection createMonzoConnectionWithExpiredTokens(User user) {
        String fakeEncryptedAccessToken = "encrypted_access_" + generateRandomToken();
        String fakeEncryptedRefreshToken = "encrypted_refresh_" + generateRandomToken();
        Instant tokenExpiresAt = Instant.now().minus(1, ChronoUnit.HOURS);

        MonzoConnection connection = new MonzoConnection(
                user,
                "user_" + UUID.randomUUID().toString().substring(0, 8),
                fakeEncryptedAccessToken,
                fakeEncryptedRefreshToken,
                tokenExpiresAt
        );
        return monzoConnectionRepository.save(connection);
    }

    /**
     * Creates a disconnected (soft-deleted) Monzo connection.
     */
    public MonzoConnection createDisconnectedMonzoConnectionFor(User user) {
        MonzoConnection connection = createActiveMonzoConnectionFor(user);
        connection.disconnect();
        return monzoConnectionRepository.save(connection);
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    private String generateRandomToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            // Use hex format to match SessionService.hashToken()
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ========================================================================
    // Result classes (to return both raw token and persisted entity)
    // ========================================================================

    /**
     * Result of creating a magic link token - includes raw token for testing.
     */
    public record MagicLinkTokenResult(String rawToken, MagicLinkToken entity) {}

    /**
     * Result of creating a refresh token - includes raw token for testing.
     */
    public record RefreshTokenResult(String rawToken, AppRefreshToken entity) {}
}
