package dev.amf.budgeteer.service.auth;

import dev.amf.budgeteer.config.JweProperties;
import dev.amf.budgeteer.domain.session.AppRefreshToken;
import dev.amf.budgeteer.repository.AppRefreshTokenRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Service for managing user sessions via refresh tokens.
 * Handles token generation, validation, and revocation.
 */
@Service
public class SessionService {

    /**
     * Record containing session tokens (access token and refresh token).
     */
    public record SessionTokens(
            String accessToken,
            String refreshToken
    ) {}

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppRefreshTokenRepository refreshTokenRepository;
    private final JweTokenService jweTokenService;
    private final JweProperties jweProperties;

    public SessionService(AppRefreshTokenRepository refreshTokenRepository,
                          JweTokenService jweTokenService,
                          JweProperties jweProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jweTokenService = jweTokenService;
        this.jweProperties = jweProperties;
    }

    /**
     * Creates a new session for a user, returning both access and refresh tokens.
     *
     * @param user      the user to create a session for
     * @param userAgent the user's browser/device user agent (optional)
     * @param ipAddress the user's IP address (optional)
     * @return session tokens
     */
    @Transactional
    public SessionTokens createSession(User user, @Nullable String userAgent, @Nullable String ipAddress) {
        // Generate access token (JWE)
        String accessToken = jweTokenService.createAccessToken(user);

        // Generate refresh token (opaque random string)
        String refreshToken = generateSecureToken();
        String refreshTokenHash = hashToken(refreshToken);

        Instant expiresAt = Instant.now().plus(jweProperties.getRefreshTokenExpiry());

        // Store refresh token hash in database
        AppRefreshToken tokenEntity = new AppRefreshToken(user, refreshTokenHash, expiresAt, userAgent, ipAddress);
        refreshTokenRepository.save(tokenEntity);

        log.info("Session created [userId={}, expiresAt={}, ipAddress={}]", 
                user.getId(), expiresAt, LogSanitizer.sanitize(ipAddress));

        return new SessionTokens(accessToken, refreshToken);
    }

    /**
     * Refreshes a session using a refresh token.
     * Implements token rotation - the old refresh token is revoked and a new one is issued.
     *
     * @param refreshToken the current refresh token
     * @param userAgent    the user's browser/device user agent (optional)
     * @param ipAddress    the user's IP address (optional)
     * @return new session tokens, or empty if the refresh token is invalid
     */
    @Transactional
    public Optional<SessionTokens> refreshSession(String refreshToken, @Nullable String userAgent, @Nullable String ipAddress) {
        String tokenHash = hashToken(refreshToken);

        Optional<AppRefreshToken> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);

        if (tokenOpt.isEmpty()) {
            log.warn("Session refresh failed: token not found");
            return Optional.empty();
        }

        AppRefreshToken token = tokenOpt.get();

        if (!token.isValid()) {
            log.warn("Session refresh failed: token invalid or expired [userId={}]", 
                    token.getUser().getId());
            return Optional.empty();
        }

        User user = token.getUser();

        // Revoke the old refresh token (token rotation)
        token.revoke();
        refreshTokenRepository.save(token);

        // Create new session
        SessionTokens newTokens = createSession(user, userAgent, ipAddress);

        log.info("Session refreshed successfully [userId={}, ipAddress={}]", user.getId(), LogSanitizer.sanitize(ipAddress));

        return Optional.of(newTokens);
    }

    /**
     * Revokes a refresh token (logout).
     *
     * @param refreshToken the refresh token to revoke
     * @return true if the token was found and revoked
     */
    @Transactional
    public boolean revokeSession(String refreshToken) {
        String tokenHash = hashToken(refreshToken);

        Optional<AppRefreshToken> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);

        if (tokenOpt.isEmpty()) {
            log.debug("Session revocation: token not found");
            return false;
        }

        AppRefreshToken token = tokenOpt.get();
        token.revoke();
        refreshTokenRepository.save(token);

        log.info("Session revoked [userId={}]", token.getUser().getId());

        return true;
    }

    /**
     * Revokes all sessions for a user (logout everywhere).
     *
     * @param user the user whose sessions should be revoked
     * @return the number of sessions revoked
     */
    @Transactional
    public int revokeAllSessions(User user) {
        int revoked = refreshTokenRepository.revokeAllTokensForUser(user, Instant.now());
        if (revoked > 0) {
            log.info("All sessions revoked [userId={}, count={}]", user.getId(), revoked);
        }
        return revoked;
    }

    /**
     * Validates a refresh token and returns the associated user.
     *
     * @param refreshToken the refresh token to validate
     * @return the user if the token is valid, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<User> validateRefreshToken(String refreshToken) {
        String tokenHash = hashToken(refreshToken);

        return refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(AppRefreshToken::isValid)
                .map(AppRefreshToken::getUser);
    }

    /**
     * Generates a cryptographically secure random token.
     *
     * @return URL-safe base64 encoded token
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[32]; // 256 bits
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hashes a token using SHA-256.
     *
     * @param token the token to hash
     * @return hex-encoded SHA-256 hash
     */
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
