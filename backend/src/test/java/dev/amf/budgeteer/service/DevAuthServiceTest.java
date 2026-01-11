package dev.amf.budgeteer.service;

import dev.amf.budgeteer.domain.session.AppRefreshTokenRepository;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DevAuthService}.
 * 
 * <p>Tests for the development-only authentication service.
 * While this service only runs in dev profile, we test it to:
 * <ul>
 *   <li>Document expected behavior</li>
 *   <li>Catch bugs if the service is modified</li>
 *   <li>Ensure dev tools work correctly</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("DevAuthService")
class DevAuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AppRefreshTokenRepository refreshTokenRepository;

    @Mock
    private SessionService sessionService;

    private DevAuthService devAuthService;

    @BeforeEach
    void setUp() {
        devAuthService = new DevAuthService(userRepository, refreshTokenRepository, sessionService);
    }

    private User createTestUser(String email) {
        User user = new User(email);
        user.setId(UUID.randomUUID());
        user.setEmailVerified(true);
        return user;
    }

    @Nested
    @DisplayName("findOrCreateDevUser")
    class FindOrCreateDevUser {

        @Test
        @DisplayName("should return existing user when found")
        void shouldReturnExistingUser() {
            // Given
            String email = "existing@example.com";
            User existingUser = createTestUser(email);
            when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(existingUser));

            // When
            User result = devAuthService.findOrCreateDevUser(email);

            // Then
            assertThat(result).isEqualTo(existingUser);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("should create new user when not found")
        void shouldCreateNewUserWhenNotFound() {
            // Given
            String email = "newuser@example.com";
            when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(UUID.randomUUID());
                return user;
            });

            // When
            User result = devAuthService.findOrCreateDevUser(email);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getEmail()).isEqualTo(email);
            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("should auto-verify email for new dev users")
        void shouldAutoVerifyEmailForNewUsers() {
            // Given
            String email = "newuser@example.com";
            when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());
            
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            when(userRepository.save(userCaptor.capture())).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(UUID.randomUUID());
                return user;
            });

            // When
            devAuthService.findOrCreateDevUser(email);

            // Then
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.isEmailVerified()).isTrue();
        }

        @Test
        @DisplayName("should normalize email to lowercase")
        void shouldNormalizeEmailToLowercase() {
            // Given
            String email = "MixedCase@Example.COM";
            String normalizedEmail = "mixedcase@example.com";
            when(userRepository.findByEmailIgnoreCase(normalizedEmail)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(UUID.randomUUID());
                return user;
            });

            // When
            devAuthService.findOrCreateDevUser(email);

            // Then
            verify(userRepository).findByEmailIgnoreCase(normalizedEmail);
        }

        @Test
        @DisplayName("should trim whitespace from email")
        void shouldTrimWhitespaceFromEmail() {
            // Given
            String email = "  test@example.com  ";
            String normalizedEmail = "test@example.com";
            when(userRepository.findByEmailIgnoreCase(normalizedEmail)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(UUID.randomUUID());
                return user;
            });

            // When
            devAuthService.findOrCreateDevUser(email);

            // Then
            verify(userRepository).findByEmailIgnoreCase(normalizedEmail);
        }
    }

    @Nested
    @DisplayName("revokeAllSessions")
    class RevokeAllSessions {

        @Test
        @DisplayName("should call repository to revoke all tokens")
        void shouldRevokeAllTokens() {
            // Given
            when(refreshTokenRepository.revokeAllTokens(any(Instant.class))).thenReturn(10);

            // When
            int revoked = devAuthService.revokeAllSessions();

            // Then
            assertThat(revoked).isEqualTo(10);
            verify(refreshTokenRepository).revokeAllTokens(any(Instant.class));
        }

        @Test
        @DisplayName("should return zero when no sessions exist")
        void shouldReturnZeroWhenNoSessions() {
            // Given
            when(refreshTokenRepository.revokeAllTokens(any(Instant.class))).thenReturn(0);

            // When
            int revoked = devAuthService.revokeAllSessions();

            // Then
            assertThat(revoked).isZero();
        }

        @Test
        @DisplayName("should pass current instant for revocation timestamp")
        void shouldPassCurrentInstant() {
            // Given
            ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
            when(refreshTokenRepository.revokeAllTokens(instantCaptor.capture())).thenReturn(5);
            
            Instant before = Instant.now();

            // When
            devAuthService.revokeAllSessions();

            // Then
            Instant after = Instant.now();
            Instant capturedInstant = instantCaptor.getValue();
            assertThat(capturedInstant).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("revokeUserSessions")
    class RevokeUserSessions {

        @Test
        @DisplayName("should revoke sessions for existing user")
        void shouldRevokeSessionsForExistingUser() {
            // Given
            String email = "user@example.com";
            User user = createTestUser(email);
            when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
            when(sessionService.revokeAllSessions(user)).thenReturn(3);

            // When
            int revoked = devAuthService.revokeUserSessions(email);

            // Then
            assertThat(revoked).isEqualTo(3);
            verify(sessionService).revokeAllSessions(user);
        }

        @Test
        @DisplayName("should return -1 when user not found")
        void shouldReturnMinusOneWhenUserNotFound() {
            // Given
            String email = "nonexistent@example.com";
            when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());

            // When
            int revoked = devAuthService.revokeUserSessions(email);

            // Then
            assertThat(revoked).isEqualTo(-1);
            verify(sessionService, never()).revokeAllSessions(any());
        }

        @Test
        @DisplayName("should normalize email before lookup")
        void shouldNormalizeEmailBeforeLookup() {
            // Given
            String email = "  USER@Example.COM  ";
            String normalizedEmail = "user@example.com";
            when(userRepository.findByEmailIgnoreCase(normalizedEmail)).thenReturn(Optional.empty());

            // When
            devAuthService.revokeUserSessions(email);

            // Then
            verify(userRepository).findByEmailIgnoreCase(normalizedEmail);
        }

        @Test
        @DisplayName("should return zero when user has no sessions")
        void shouldReturnZeroWhenNoSessions() {
            // Given
            String email = "user@example.com";
            User user = createTestUser(email);
            when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
            when(sessionService.revokeAllSessions(user)).thenReturn(0);

            // When
            int revoked = devAuthService.revokeUserSessions(email);

            // Then
            assertThat(revoked).isZero();
        }
    }
}
