package dev.amf.budgeteer.service.auth;

import dev.amf.budgeteer.config.JweProperties;
import dev.amf.budgeteer.domain.session.MagicLinkToken;
import dev.amf.budgeteer.repository.MagicLinkTokenRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.repository.UserRepository;
import dev.amf.budgeteer.service.common.EmailService;
import dev.amf.budgeteer.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.jspecify.annotations.Nullable;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Service for handling authentication flows.
 * Orchestrates magic link generation, verification, and user management.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final MagicLinkTokenRepository magicLinkTokenRepository;
    private final SessionService sessionService;
    private final EmailService emailService;
    private final JweProperties jweProperties;

    public AuthService(UserRepository userRepository,
                       MagicLinkTokenRepository magicLinkTokenRepository,
                       SessionService sessionService,
                       EmailService emailService,
                       JweProperties jweProperties) {
        this.userRepository = userRepository;
        this.magicLinkTokenRepository = magicLinkTokenRepository;
        this.sessionService = sessionService;
        this.emailService = emailService;
        this.jweProperties = jweProperties;
    }

    /**
     * Initiates the login process by sending a magic link to the user's email.
     * Creates a new user if one doesn't exist.
     *
     * @param email the user's email address
     */
    @Transactional
    public void requestMagicLink(String email) {
        String normalizedEmail = email.toLowerCase().trim();

        // Find or create user
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseGet(() -> {
                    log.info("Creating new user account [email={}]", maskEmail(normalizedEmail));
                    User newUser = new User(normalizedEmail);
                    User savedUser = userRepository.save(newUser);
                    log.info("User account created successfully [userId={}, email={}]", 
                            savedUser.getId(), maskEmail(normalizedEmail));
                    return savedUser;
                });

        // Generate magic link token
        String plainToken = generateSecureToken();
        String tokenHash = sessionService.hashToken(plainToken);

        Instant expiresAt = Instant.now().plus(jweProperties.getMagicLinkExpiry());

        // Store token hash
        MagicLinkToken magicLinkToken = new MagicLinkToken(user, tokenHash, expiresAt);
        magicLinkTokenRepository.save(magicLinkToken);

        // Send email with magic link
        emailService.sendMagicLinkEmail(normalizedEmail, plainToken);

        log.info("Magic link generated and sent [userId={}, email={}, expiresAt={}]", 
                user.getId(), maskEmail(normalizedEmail), expiresAt);
    }

    /**
     * Verifies a magic link token and creates a session.
     *
     * @param token     the magic link token (plain, not hashed)
     * @param userAgent the user's browser/device user agent (optional)
     * @param ipAddress the user's IP address (optional)
     * @return session tokens if verification succeeded
     */
    @Transactional
    public Optional<SessionService.SessionTokens> verifyMagicLink(String token, @Nullable String userAgent, @Nullable String ipAddress) {
        String tokenHash = sessionService.hashToken(token);

        Optional<MagicLinkToken> magicLinkOpt = magicLinkTokenRepository.findByTokenHash(tokenHash);

        if (magicLinkOpt.isEmpty()) {
            log.warn("Magic link verification failed: token not found");
            return Optional.empty();
        }

        MagicLinkToken magicLink = magicLinkOpt.get();

        // Check if token is valid
        if (!magicLink.isValid()) {
            log.warn("Magic link verification failed: token invalid or expired [userId={}]", 
                    magicLink.getUser().getId());
            return Optional.empty();
        }

        // Mark token as used
        magicLink.markAsUsed();
        magicLinkTokenRepository.save(magicLink);

        // Get user and mark email as verified
        User user = magicLink.getUser();
        
        // Add user ID to MDC for all subsequent logs in this request
        MDC.put("userId", user.getId().toString());
        
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            userRepository.save(user);
            log.info("Email verified for user [userId={}, email={}]", 
                    user.getId(), maskEmail(user.getEmail()));
        }

        // Invalidate any other pending magic links for this user
        magicLinkTokenRepository.invalidateAllTokensForUser(user, Instant.now());

        // Revoke all existing sessions (single-session policy)
        int revokedSessions = sessionService.revokeAllSessions(user);
        if (revokedSessions > 0) {
            log.info("Revoked {} existing session(s) for user on new login [userId={}, policy=single-session]", 
                    revokedSessions, user.getId());
        }

        // Create session
        SessionService.SessionTokens sessionTokens = sessionService.createSession(user, userAgent, ipAddress);

        log.info("User authenticated successfully via magic link [userId={}, ipAddress={}, userAgent={}]", 
                user.getId(), LogSanitizer.sanitize(ipAddress), maskUserAgent(userAgent));

        return Optional.of(sessionTokens);
    }

    /**
     * Gets a user by their ID.
     *
     * @param userId the user ID
     * @return the user if found
     */
    @Transactional(readOnly = true)
    public Optional<User> getUserById(java.util.UUID userId) {
        return userRepository.findById(userId);
    }

    /**
     * Gets a user by their email.
     *
     * @param email the user's email
     * @return the user if found
     */
    @Transactional(readOnly = true)
    public Optional<User> getUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email.toLowerCase().trim());
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
     * Masks email address for logging to protect PII.
     * Example: john.doe@example.com -> j***@example.com
     */
    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        String[] parts = email.split("@");
        if (parts[0].length() <= 1) {
            return "*@" + parts[1];
        }
        return parts[0].charAt(0) + "***@" + parts[1];
    }
    
    /**
     * Masks user agent for logging to reduce verbosity.
     */
    private String maskUserAgent(String userAgent) {
        if (userAgent == null || userAgent.length() < 20) {
            return userAgent;
        }
        return userAgent.substring(0, Math.min(50, userAgent.length())) + "...";
    }
}
