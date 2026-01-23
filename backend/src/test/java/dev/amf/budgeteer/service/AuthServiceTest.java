package dev.amf.budgeteer.service;

import dev.amf.budgeteer.config.JweProperties;
import dev.amf.budgeteer.domain.session.MagicLinkToken;
import dev.amf.budgeteer.domain.session.MagicLinkTokenRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthService}.
 * 
 * <p>Uses Mockito to mock repository and service dependencies.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MagicLinkTokenRepository magicLinkTokenRepository;

    @Mock
    private SessionService sessionService;

    @Mock
    private EmailService emailService;

    private JweProperties jweProperties;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jweProperties = new JweProperties();
        jweProperties.setMagicLinkExpiry(Duration.ofMinutes(15));
        jweProperties.setAccessTokenExpiry(Duration.ofMinutes(15));
        jweProperties.setRefreshTokenExpiry(Duration.ofDays(7));

        authService = new AuthService(
                userRepository,
                magicLinkTokenRepository,
                sessionService,
                emailService,
                jweProperties
        );
    }

    private User createTestUser() {
        User user = new User("test@example.com");
        user.setId(UUID.randomUUID());
        user.setEmailVerified(true);
        return user;
    }

    private User createTestUser(String email) {
        User user = new User(email);
        user.setId(UUID.randomUUID());
        user.setEmailVerified(false);
        return user;
    }

    @Nested
    @DisplayName("requestMagicLink")
    class RequestMagicLink {

        @Test
        @DisplayName("should find existing user and send magic link")
        void shouldFindExistingUserAndSendMagicLink() {
            // Given
            User existingUser = createTestUser("existing@example.com");
            when(userRepository.findByEmailIgnoreCase("existing@example.com"))
                    .thenReturn(Optional.of(existingUser));
            when(sessionService.hashToken(anyString())).thenReturn("hashed-token");
            when(magicLinkTokenRepository.save(any(MagicLinkToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            authService.requestMagicLink("existing@example.com");

            // Then
            verify(userRepository).findByEmailIgnoreCase("existing@example.com");
            verify(userRepository, never()).save(any(User.class)); // Should not create new user
            verify(emailService).sendMagicLinkEmail(eq("existing@example.com"), anyString());
        }

        @Test
        @DisplayName("should create new user if email not found")
        void shouldCreateNewUserIfEmailNotFound() {
            // Given
            when(userRepository.findByEmailIgnoreCase("new@example.com"))
                    .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class)))
                    .thenAnswer(invocation -> {
                        User user = invocation.getArgument(0);
                        user.setId(UUID.randomUUID());
                        return user;
                    });
            when(sessionService.hashToken(anyString())).thenReturn("hashed-token");
            when(magicLinkTokenRepository.save(any(MagicLinkToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            authService.requestMagicLink("new@example.com");

            // Then
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getEmail()).isEqualTo("new@example.com");
            verify(emailService).sendMagicLinkEmail(eq("new@example.com"), anyString());
        }

        @Test
        @DisplayName("should normalize email to lowercase and trim")
        void shouldNormalizeEmail() {
            // Given
            when(userRepository.findByEmailIgnoreCase("test@example.com"))
                    .thenReturn(Optional.of(createTestUser()));
            when(sessionService.hashToken(anyString())).thenReturn("hashed-token");
            when(magicLinkTokenRepository.save(any(MagicLinkToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            authService.requestMagicLink("  TEST@EXAMPLE.COM  ");

            // Then
            verify(userRepository).findByEmailIgnoreCase("test@example.com");
            verify(emailService).sendMagicLinkEmail(eq("test@example.com"), anyString());
        }

        @Test
        @DisplayName("should store magic link token with correct expiry")
        void shouldStoreMagicLinkTokenWithCorrectExpiry() {
            // Given
            User existingUser = createTestUser();
            when(userRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(existingUser));
            when(sessionService.hashToken(anyString())).thenReturn("hashed-token");
            
            ArgumentCaptor<MagicLinkToken> tokenCaptor = ArgumentCaptor.forClass(MagicLinkToken.class);
            when(magicLinkTokenRepository.save(tokenCaptor.capture()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Instant beforeRequest = Instant.now();

            // When
            authService.requestMagicLink("test@example.com");

            // Then
            Instant afterRequest = Instant.now();
            MagicLinkToken savedToken = tokenCaptor.getValue();
            
            assertThat(savedToken.getUser()).isEqualTo(existingUser);
            assertThat(savedToken.getTokenHash()).isEqualTo("hashed-token");
            
            Instant expectedMinExpiry = beforeRequest.plus(jweProperties.getMagicLinkExpiry());
            Instant expectedMaxExpiry = afterRequest.plus(jweProperties.getMagicLinkExpiry());
            assertThat(savedToken.getExpiresAt()).isBetween(expectedMinExpiry, expectedMaxExpiry);
        }

        @Test
        @DisplayName("should generate unique token for each request")
        void shouldGenerateUniqueTokenForEachRequest() {
            // Given
            User existingUser = createTestUser();
            when(userRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(existingUser));
            when(sessionService.hashToken(anyString()))
                    .thenAnswer(invocation -> "hash-" + invocation.getArgument(0).toString().substring(0, 8));
            when(magicLinkTokenRepository.save(any(MagicLinkToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);

            // When
            authService.requestMagicLink("test@example.com");
            authService.requestMagicLink("test@example.com");

            // Then
            verify(emailService, times(2)).sendMagicLinkEmail(anyString(), tokenCaptor.capture());
            assertThat(tokenCaptor.getAllValues().get(0))
                    .isNotEqualTo(tokenCaptor.getAllValues().get(1));
        }
    }

    @Nested
    @DisplayName("verifyMagicLink")
    class VerifyMagicLink {

        @Test
        @DisplayName("should return session tokens for valid magic link")
        void shouldReturnSessionTokensForValidMagicLink() {
            // Given
            User user = createTestUser();
            user.setEmailVerified(true);
            String plainToken = "valid-magic-link-token";
            String tokenHash = "hashed-token";
            
            MagicLinkToken magicLinkToken = new MagicLinkToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));
            
            SessionService.SessionTokens expectedTokens = 
                    new SessionService.SessionTokens("access-token", "refresh-token");
            
            when(sessionService.hashToken(plainToken)).thenReturn(tokenHash);
            when(magicLinkTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(magicLinkToken));
            when(magicLinkTokenRepository.save(any(MagicLinkToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(sessionService.revokeAllSessions(user)).thenReturn(0);
            when(sessionService.createSession(user, "TestAgent", "127.0.0.1"))
                    .thenReturn(expectedTokens);

            // When
            Optional<SessionService.SessionTokens> result = 
                    authService.verifyMagicLink(plainToken, "TestAgent", "127.0.0.1");

            // Then
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(expectedTokens);
        }

        @Test
        @DisplayName("should mark magic link token as used")
        void shouldMarkMagicLinkTokenAsUsed() {
            // Given
            User user = createTestUser();
            String plainToken = "valid-token";
            String tokenHash = "hashed-token";
            
            MagicLinkToken magicLinkToken = new MagicLinkToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));
            
            when(sessionService.hashToken(plainToken)).thenReturn(tokenHash);
            when(magicLinkTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(magicLinkToken));
            when(magicLinkTokenRepository.save(any(MagicLinkToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(sessionService.revokeAllSessions(user)).thenReturn(0);
            when(sessionService.createSession(any(), any(), any()))
                    .thenReturn(new SessionService.SessionTokens("a", "r"));

            // When
            authService.verifyMagicLink(plainToken, null, null);

            // Then
            assertThat(magicLinkToken.getUsedAt()).isNotNull();
            verify(magicLinkTokenRepository).save(magicLinkToken);
        }

        @Test
        @DisplayName("should mark user email as verified on first login")
        void shouldMarkUserEmailAsVerifiedOnFirstLogin() {
            // Given
            User user = createTestUser("new@example.com");
            user.setEmailVerified(false); // Not yet verified
            String plainToken = "valid-token";
            String tokenHash = "hashed-token";
            
            MagicLinkToken magicLinkToken = new MagicLinkToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));
            
            when(sessionService.hashToken(plainToken)).thenReturn(tokenHash);
            when(magicLinkTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(magicLinkToken));
            when(magicLinkTokenRepository.save(any(MagicLinkToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(userRepository.save(user)).thenReturn(user);
            when(sessionService.revokeAllSessions(user)).thenReturn(0);
            when(sessionService.createSession(any(), any(), any()))
                    .thenReturn(new SessionService.SessionTokens("a", "r"));

            // When
            authService.verifyMagicLink(plainToken, null, null);

            // Then
            assertThat(user.isEmailVerified()).isTrue();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("should not update user if email already verified")
        void shouldNotUpdateUserIfEmailAlreadyVerified() {
            // Given
            User user = createTestUser();
            user.setEmailVerified(true); // Already verified
            String plainToken = "valid-token";
            String tokenHash = "hashed-token";
            
            MagicLinkToken magicLinkToken = new MagicLinkToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));
            
            when(sessionService.hashToken(plainToken)).thenReturn(tokenHash);
            when(magicLinkTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(magicLinkToken));
            when(magicLinkTokenRepository.save(any(MagicLinkToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(sessionService.revokeAllSessions(user)).thenReturn(0);
            when(sessionService.createSession(any(), any(), any()))
                    .thenReturn(new SessionService.SessionTokens("a", "r"));

            // When
            authService.verifyMagicLink(plainToken, null, null);

            // Then
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should invalidate all other magic links for user")
        void shouldInvalidateAllOtherMagicLinksForUser() {
            // Given
            User user = createTestUser();
            String plainToken = "valid-token";
            String tokenHash = "hashed-token";
            
            MagicLinkToken magicLinkToken = new MagicLinkToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));
            
            when(sessionService.hashToken(plainToken)).thenReturn(tokenHash);
            when(magicLinkTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(magicLinkToken));
            when(magicLinkTokenRepository.save(any(MagicLinkToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(sessionService.revokeAllSessions(user)).thenReturn(0);
            when(sessionService.createSession(any(), any(), any()))
                    .thenReturn(new SessionService.SessionTokens("a", "r"));

            // When
            authService.verifyMagicLink(plainToken, null, null);

            // Then
            verify(magicLinkTokenRepository).invalidateAllTokensForUser(eq(user), any(Instant.class));
        }

        @Test
        @DisplayName("should revoke existing sessions on new login (single-session policy)")
        void shouldRevokeExistingSessionsOnNewLogin() {
            // Given
            User user = createTestUser();
            String plainToken = "valid-token";
            String tokenHash = "hashed-token";
            
            MagicLinkToken magicLinkToken = new MagicLinkToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));
            
            when(sessionService.hashToken(plainToken)).thenReturn(tokenHash);
            when(magicLinkTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(magicLinkToken));
            when(magicLinkTokenRepository.save(any(MagicLinkToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(sessionService.revokeAllSessions(user)).thenReturn(2); // Had 2 existing sessions
            when(sessionService.createSession(any(), any(), any()))
                    .thenReturn(new SessionService.SessionTokens("a", "r"));

            // When
            authService.verifyMagicLink(plainToken, null, null);

            // Then
            verify(sessionService).revokeAllSessions(user);
        }

        @Test
        @DisplayName("should return empty for non-existent token")
        void shouldReturnEmptyForNonExistentToken() {
            // Given
            String invalidToken = "invalid-token";
            String tokenHash = "hashed-invalid";
            
            when(sessionService.hashToken(invalidToken)).thenReturn(tokenHash);
            when(magicLinkTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.empty());

            // When
            Optional<SessionService.SessionTokens> result = 
                    authService.verifyMagicLink(invalidToken, null, null);

            // Then
            assertThat(result).isEmpty();
            verify(sessionService, never()).createSession(any(), any(), any());
        }

        @Test
        @DisplayName("should return empty for expired token")
        void shouldReturnEmptyForExpiredToken() {
            // Given
            User user = createTestUser();
            String expiredToken = "expired-token";
            String tokenHash = "hashed-expired";
            
            MagicLinkToken magicLinkToken = new MagicLinkToken(
                    user, tokenHash, Instant.now().minusSeconds(3600)); // Expired 1 hour ago
            
            when(sessionService.hashToken(expiredToken)).thenReturn(tokenHash);
            when(magicLinkTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(magicLinkToken));

            // When
            Optional<SessionService.SessionTokens> result = 
                    authService.verifyMagicLink(expiredToken, null, null);

            // Then
            assertThat(result).isEmpty();
            verify(sessionService, never()).createSession(any(), any(), any());
        }

        @Test
        @DisplayName("should return empty for already used token")
        void shouldReturnEmptyForAlreadyUsedToken() {
            // Given
            User user = createTestUser();
            String usedToken = "used-token";
            String tokenHash = "hashed-used";
            
            MagicLinkToken magicLinkToken = new MagicLinkToken(
                    user, tokenHash, Instant.now().plusSeconds(3600));
            magicLinkToken.markAsUsed(); // Already used
            
            when(sessionService.hashToken(usedToken)).thenReturn(tokenHash);
            when(magicLinkTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(magicLinkToken));

            // When
            Optional<SessionService.SessionTokens> result = 
                    authService.verifyMagicLink(usedToken, null, null);

            // Then
            assertThat(result).isEmpty();
            verify(sessionService, never()).createSession(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("getUserById")
    class GetUserById {

        @Test
        @DisplayName("should return user when found")
        void shouldReturnUserWhenFound() {
            // Given
            User user = createTestUser();
            UUID userId = user.getId();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            // When
            Optional<User> result = authService.getUserById(userId);

            // Then
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(user);
        }

        @Test
        @DisplayName("should return empty when user not found")
        void shouldReturnEmptyWhenUserNotFound() {
            // Given
            UUID nonExistentId = UUID.randomUUID();
            when(userRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            // When
            Optional<User> result = authService.getUserById(nonExistentId);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getUserByEmail")
    class GetUserByEmail {

        @Test
        @DisplayName("should return user when found")
        void shouldReturnUserWhenFound() {
            // Given
            User user = createTestUser("found@example.com");
            when(userRepository.findByEmailIgnoreCase("found@example.com"))
                    .thenReturn(Optional.of(user));

            // When
            Optional<User> result = authService.getUserByEmail("found@example.com");

            // Then
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(user);
        }

        @Test
        @DisplayName("should return empty when user not found")
        void shouldReturnEmptyWhenUserNotFound() {
            // Given
            when(userRepository.findByEmailIgnoreCase("notfound@example.com"))
                    .thenReturn(Optional.empty());

            // When
            Optional<User> result = authService.getUserByEmail("notfound@example.com");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should normalize email before lookup")
        void shouldNormalizeEmailBeforeLookup() {
            // Given
            User user = createTestUser("test@example.com");
            when(userRepository.findByEmailIgnoreCase("test@example.com"))
                    .thenReturn(Optional.of(user));

            // When
            authService.getUserByEmail("  TEST@EXAMPLE.COM  ");

            // Then
            verify(userRepository).findByEmailIgnoreCase("test@example.com");
        }
    }
}
