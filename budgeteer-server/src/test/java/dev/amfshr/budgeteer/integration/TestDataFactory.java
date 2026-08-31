package dev.amfshr.budgeteer.integration;

import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.monzo.MonzoTransaction;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoConnectionRepository;
import dev.amfshr.budgeteer.repository.MonzoTransactionRepository;
import dev.amfshr.budgeteer.domain.oauth.OAuthState;
import dev.amfshr.budgeteer.repository.OAuthStateRepository;
import dev.amfshr.budgeteer.domain.session.AppRefreshToken;
import dev.amfshr.budgeteer.repository.AppRefreshTokenRepository;
import dev.amfshr.budgeteer.domain.session.MagicLinkToken;
import dev.amfshr.budgeteer.repository.MagicLinkTokenRepository;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.repository.UserRepository;
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

    @Autowired
    private MonzoAccountRepository monzoAccountRepository;

    @Autowired
    private MonzoTransactionRepository monzoTransactionRepository;

    @Autowired
    private dev.amfshr.budgeteer.repository.AccountRepository accountRepository;

    @Autowired
    private dev.amfshr.budgeteer.repository.TransactionRepository transactionRepository;

    @Autowired
    private dev.amfshr.budgeteer.service.common.EncryptionService encryptionService;

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

    /**
     * Creates a Monzo connection with tokens expiring very soon (3 minutes).
     * Uses fake encrypted tokens (not suitable for tests that call the Monzo API).
     */
    public MonzoConnection createMonzoConnectionExpiringSoon(User user) {
        String fakeEncryptedAccessToken = "encrypted_access_" + generateRandomToken();
        String fakeEncryptedRefreshToken = "encrypted_refresh_" + generateRandomToken();
        Instant tokenExpiresAt = Instant.now().plus(3, java.time.temporal.ChronoUnit.MINUTES);

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
     * Creates a Monzo connection with real AES-256-GCM encrypted tokens.
     *
     * <p>Use this when the test will actually call the Monzo API via the refresh service,
     * since the real {@code EncryptionService} must be able to decrypt the stored tokens.
     *
     * @param user               the owning user
     * @param plainRefreshToken  the plaintext refresh token to store (encrypted)
     * @param expiresAt          when the access token expires
     * @return persisted connection with real encrypted tokens
     */
    public MonzoConnection createMonzoConnectionWithRealTokens(
            User user,
            String plainRefreshToken,
            Instant expiresAt
    ) {
        String encAccessToken = encryptionService.encrypt("test-access-token-" + UUID.randomUUID());
        String encRefreshToken = encryptionService.encrypt(plainRefreshToken);
        String monzoUserId = "user_" + UUID.randomUUID().toString().substring(0, 8);

        MonzoConnection connection = new MonzoConnection(
                user, monzoUserId, encAccessToken, encRefreshToken, expiresAt
        );
        return monzoConnectionRepository.save(connection);
    }

    // ========================================================================
    // Monzo Account + Transaction creation
    // ========================================================================

    public MonzoAccount createMonzoAccount(MonzoConnection connection, User user) {
        return createMonzoAccount(connection, user, "acc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    }

    public MonzoAccount createMonzoAccount(MonzoConnection connection, User user, String accountId) {
        MonzoAccount account = new MonzoAccount(
                accountId, connection, user, "uk_retail", "Current Account", "GBP", false
        );
        return monzoAccountRepository.save(account);
    }

    public MonzoTransaction createMonzoTransaction(MonzoAccount account, User user) {
        String txId = "tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MonzoTransaction tx = new MonzoTransaction(
                txId, account, user,
                -1000, "GBP", "Test transaction",
                null, null, null,
                false, Instant.now(), null
        );
        return monzoTransactionRepository.save(tx);
    }

    // ========================================================================
    // Domain (bank_accounts / transactions) creation
    // ========================================================================

    public dev.amfshr.budgeteer.domain.account.Account createBankAccount(User user) {
        return createBankAccount(user, "acc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    }

    /** Active MONZO CURRENT domain account. */
    public dev.amfshr.budgeteer.domain.account.Account createBankAccount(User user, String providerAccountId) {
        dev.amfshr.budgeteer.domain.account.Account account = new dev.amfshr.budgeteer.domain.account.Account(
                user, dev.amfshr.budgeteer.domain.account.Provider.MONZO, providerAccountId,
                dev.amfshr.budgeteer.domain.account.AccountType.CURRENT, "Monzo", "Current Account", "GBP");
        return accountRepository.save(account);
    }

    /**
     * Settled domain transaction via the native upsert (the only write path — the entity has no
     * public constructor). Returns the generated provider transaction id.
     */
    public String createDomainTransaction(dev.amfshr.budgeteer.domain.account.Account account, User user,
                                          long amountMinorUnits, Instant occurredAt) {
        String providerTxId = "tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        transactionRepository.upsert(
                user.getId(), account.getId(), "MONZO", providerTxId,
                amountMinorUnits, "GBP", "SETTLED",
                "Test transaction", null, null, null, occurredAt, occurredAt);
        return providerTxId;
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
