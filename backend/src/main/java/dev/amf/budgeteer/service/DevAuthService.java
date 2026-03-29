package dev.amf.budgeteer.service;

import dev.amf.budgeteer.repository.AppRefreshTokenRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.repository.UserRepository;
import dev.amf.budgeteer.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Service for development-only authentication operations.
 * Contains business logic for dev auth endpoints.
 * 
 * <p><strong>⚠️ WARNING: This service only exists in the 'dev' profile!</strong>
 * It will NOT be available in production.
 */
@Service
@Profile("dev")
public class DevAuthService {

    private static final Logger log = LoggerFactory.getLogger(DevAuthService.class);

    private final UserRepository userRepository;
    private final AppRefreshTokenRepository refreshTokenRepository;
    private final SessionService sessionService;

    public DevAuthService(UserRepository userRepository,
                          AppRefreshTokenRepository refreshTokenRepository,
                          SessionService sessionService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.sessionService = sessionService;
    }

    /**
     * Find existing user by email, or create a new one for dev testing.
     * Auto-verifies email for convenience.
     * 
     * @param email the user's email (will be normalized)
     * @return the existing or newly created user
     */
    @Transactional
    public User findOrCreateDevUser(String email) {
        String normalizedEmail = email.toLowerCase().trim();
        
        return userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseGet(() -> {
                    log.info("Creating new dev user with email: {}", normalizedEmail);
                    User newUser = new User(normalizedEmail);
                    newUser.setEmailVerified(true); // Auto-verify in dev
                    return userRepository.save(newUser);
                });
    }

    /**
     * Revoke all sessions for ALL users.
     * Nuclear option - logs everyone out!
     * 
     * @return number of sessions revoked
     */
    @Transactional
    public int revokeAllSessions() {
        log.warn("☢️  REVOKING ALL SESSIONS - Everyone will be logged out!");
        
        int revoked = refreshTokenRepository.revokeAllTokens(Instant.now());
        
        log.info("Revoked {} session(s) across all users", revoked);
        
        return revoked;
    }

    /**
     * Revoke all sessions for a specific user by email.
     * 
     * @param email the user's email
     * @return number of sessions revoked, or -1 if user not found
     */
    @Transactional
    public int revokeUserSessions(String email) {
        String normalizedEmail = email.toLowerCase().trim();
        
        log.warn("Revoking sessions for user: {}", LogSanitizer.maskEmail(normalizedEmail));
        
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);
        
        if (userOpt.isEmpty()) {
            log.info("User not found: {}", LogSanitizer.maskEmail(normalizedEmail));
            return -1;
        }
        
        User user = userOpt.get();
        int revoked = sessionService.revokeAllSessions(user);
        
        log.info("Revoked {} session(s) for user {}", revoked, LogSanitizer.maskEmail(normalizedEmail));
        
        return revoked;
    }
}
