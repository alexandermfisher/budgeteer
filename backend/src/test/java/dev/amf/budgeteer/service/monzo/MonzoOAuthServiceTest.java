package dev.amf.budgeteer.service.monzo;

import dev.amf.budgeteer.client.monzo.MonzoClient;
import dev.amf.budgeteer.client.monzo.dto.TokenResponse;
import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.config.MonzoProperties;
import dev.amf.budgeteer.domain.oauth.OAuthState;
import dev.amf.budgeteer.repository.OAuthStateRepository;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MonzoOAuthService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonzoOAuthService")
class MonzoOAuthServiceTest {

    @Mock
    private MonzoProperties monzoProperties;

    @Mock
    private OAuthStateRepository stateRepository;

    @Mock
    private MonzoClient monzoClient;

    @Mock
    private SecureRandom secureRandom;

    private MonzoOAuthService oauthService;

    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        oauthService = new MonzoOAuthService(
                monzoProperties,
                stateRepository,
                monzoClient,
                secureRandom
        );

        userId = UUID.randomUUID();
        testUser = new User("test@example.com");
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(testUser, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ============ initiateOAuthFlow Tests ============

    @Nested
    @DisplayName("initiateOAuthFlow")
    class InitiateOAuthFlow {

        @Test
        @DisplayName("should generate state and return authorization URL")
        void shouldGenerateStateAndReturnAuthUrl() {
            // Given
            when(monzoProperties.authUrl()).thenReturn("https://auth.monzo.com");
            when(monzoProperties.clientId()).thenReturn("client-123");
            when(monzoProperties.redirectUri()).thenReturn("http://localhost:8080/callback");
            when(stateRepository.save(any(OAuthState.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            String authUrl = oauthService.initiateOAuthFlow(testUser);

            // Then
            assertThat(authUrl).contains("https://auth.monzo.com");
            assertThat(authUrl).contains("client_id=client-123");
            assertThat(authUrl).contains("redirect_uri=http://localhost:8080/callback");
            assertThat(authUrl).contains("response_type=code");
            assertThat(authUrl).contains("state=");

            // Verify state was saved
            ArgumentCaptor<OAuthState> stateCaptor = ArgumentCaptor.forClass(OAuthState.class);
            verify(stateRepository).save(stateCaptor.capture());

            OAuthState savedState = stateCaptor.getValue();
            assertThat(savedState.getUser()).isEqualTo(testUser);
            assertThat(savedState.getState()).isNotEmpty();
            assertThat(savedState.isUsed()).isFalse();
            assertThat(savedState.getExpiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("should generate unique states for each call")
        void shouldGenerateUniqueStates() {
            // Given
            when(monzoProperties.authUrl()).thenReturn("https://auth.monzo.com");
            when(monzoProperties.clientId()).thenReturn("client-123");
            when(monzoProperties.redirectUri()).thenReturn("http://localhost:8080/callback");
            when(stateRepository.save(any(OAuthState.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            String authUrl1 = oauthService.initiateOAuthFlow(testUser);
            String authUrl2 = oauthService.initiateOAuthFlow(testUser);

            // Then - states should be different
            verify(stateRepository, times(2)).save(any(OAuthState.class));
        }
    }

    // ============ verifyStateAndGetUser Tests ============

    @Nested
    @DisplayName("verifyStateAndGetUser")
    class VerifyStateAndGetUser {

        @Test
        @DisplayName("should return user for valid state")
        void shouldReturnUserForValidState() {
            // Given
            String state = "valid-state-token";
            OAuthState oauthState = new OAuthState(testUser, state);
            when(stateRepository.findByState(state)).thenReturn(Optional.of(oauthState));
            when(stateRepository.save(any(OAuthState.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            User result = oauthService.verifyStateAndGetUser(state);

            // Then
            assertThat(result).isEqualTo(testUser);
            assertThat(oauthState.isUsed()).isTrue();
            verify(stateRepository).save(oauthState);
        }

        @Test
        @DisplayName("should throw exception for unknown state")
        void shouldThrowExceptionForUnknownState() {
            // Given
            when(stateRepository.findByState("unknown-state")).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> oauthService.verifyStateAndGetUser("unknown-state"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.OAUTH_STATE_INVALID);
                    });
        }

        @Test
        @DisplayName("should throw exception for expired state")
        void shouldThrowExceptionForExpiredState() {
            // Given
            String state = "expired-state";
            OAuthState expiredState = new OAuthState(testUser, state, Instant.now().minusSeconds(60));
            when(stateRepository.findByState(state)).thenReturn(Optional.of(expiredState));

            // When/Then
            assertThatThrownBy(() -> oauthService.verifyStateAndGetUser(state))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.OAUTH_STATE_EXPIRED);
                    });
        }

        @Test
        @DisplayName("should throw exception for already used state")
        void shouldThrowExceptionForUsedState() {
            // Given
            String state = "used-state";
            OAuthState usedState = new OAuthState(testUser, state);
            usedState.markUsed();
            when(stateRepository.findByState(state)).thenReturn(Optional.of(usedState));

            // When/Then
            assertThatThrownBy(() -> oauthService.verifyStateAndGetUser(state))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.OAUTH_STATE_INVALID);
                    });
        }
    }

    // ============ exchangeCodeForTokens Tests ============

    @Nested
    @DisplayName("exchangeCodeForTokens")
    class ExchangeCodeForTokens {

        @Test
        @DisplayName("should exchange code for tokens successfully")
        void shouldExchangeCodeForTokens() {
            // Given
            Instant expiresAt = Instant.now().plusSeconds(3600);
            TokenResponse clientResponse = new TokenResponse(
                    "access-token-xyz",
                    "refresh-token-abc",
                    expiresAt
            );
            when(monzoClient.exchangeCode("auth-code-123")).thenReturn(clientResponse);

            // When
            MonzoOAuthService.TokenResponse result = oauthService.exchangeCodeForTokens("auth-code-123");

            // Then
            assertThat(result.accessToken()).isEqualTo("access-token-xyz");
            assertThat(result.refreshToken()).isEqualTo("refresh-token-abc");
            assertThat(result.expiresAt()).isEqualTo(expiresAt);
            verify(monzoClient).exchangeCode("auth-code-123");
        }
    }

    // ============ getMonzoUserId Tests ============

    @Nested
    @DisplayName("getMonzoUserId")
    class GetMonzoUserId {

        @Test
        @DisplayName("should return user ID from MonzoClient")
        void shouldReturnUserIdFromMonzoClient() {
            // Given
            when(monzoClient.whoAmI("access-token")).thenReturn("user_abc123");

            // When
            String result = oauthService.getMonzoUserId("access-token");

            // Then
            assertThat(result).isEqualTo("user_abc123");
            verify(monzoClient).whoAmI("access-token");
        }
    }

    // ============ cleanupExpiredStates Tests ============

    @Nested
    @DisplayName("cleanupExpiredStates")
    class CleanupExpiredStates {

        @Test
        @DisplayName("should delete expired states and return count")
        void shouldDeleteExpiredStates() {
            // Given
            when(stateRepository.deleteExpiredStates(any(Instant.class))).thenReturn(5);

            // When
            int deleted = oauthService.cleanupExpiredStates();

            // Then
            assertThat(deleted).isEqualTo(5);
            verify(stateRepository).deleteExpiredStates(any(Instant.class));
        }

        @Test
        @DisplayName("should return 0 when no expired states")
        void shouldReturnZeroWhenNoExpiredStates() {
            // Given
            when(stateRepository.deleteExpiredStates(any(Instant.class))).thenReturn(0);

            // When
            int deleted = oauthService.cleanupExpiredStates();

            // Then
            assertThat(deleted).isZero();
        }
    }
}
