package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.config.MonzoTokenRefreshProperties;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.api.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MonzoTokenRefreshJob}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonzoTokenRefreshJob")
class MonzoTokenRefreshJobTest {

    @Mock
    private MonzoTokenRefreshService tokenRefreshService;

    @Mock
    private MonzoTokenRefreshProperties properties;

    @InjectMocks
    private MonzoTokenRefreshJob job;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User("test@example.com");
        testUser.setId(UUID.randomUUID());
    }

    @Nested
    @DisplayName("refreshExpiringSoon()")
    class RefreshExpiringSoonTests {

        @Test
        @DisplayName("should query with threshold of now + configured window")
        void shouldQueryWithCorrectThreshold() {
            // Given
            when(properties.jobRefreshWindowMinutes()).thenReturn(60);
            when(tokenRefreshService.findExpiringConnections(any(Instant.class)))
                    .thenReturn(List.of());

            Instant beforeCall = Instant.now().plus(59, ChronoUnit.MINUTES);

            // When
            job.refreshExpiringSoon();

            // Then
            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(tokenRefreshService).findExpiringConnections(captor.capture());
            Instant threshold = captor.getValue();

            assertThat(threshold).isAfter(beforeCall);
            assertThat(threshold).isBefore(Instant.now().plus(61, ChronoUnit.MINUTES));
        }

        @Test
        @DisplayName("should refresh each expiring connection")
        void shouldRefreshEachExpiringConnection() {
            // Given
            when(properties.jobRefreshWindowMinutes()).thenReturn(60);
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            MonzoConnection conn1 = connectionWithId(id1);
            MonzoConnection conn2 = connectionWithId(id2);

            when(tokenRefreshService.findExpiringConnections(any())).thenReturn(List.of(conn1, conn2));
            when(tokenRefreshService.refresh(any())).thenAnswer(inv -> connectionWithId(inv.getArgument(0)));

            // When
            job.refreshExpiringSoon();

            // Then
            verify(tokenRefreshService).refresh(id1);
            verify(tokenRefreshService).refresh(id2);
        }

        @Test
        @DisplayName("should continue refreshing remaining connections after one failure")
        void shouldContinueAfterOneFailure() {
            // Given
            when(properties.jobRefreshWindowMinutes()).thenReturn(60);
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            UUID id3 = UUID.randomUUID();
            MonzoConnection conn1 = connectionWithId(id1);
            MonzoConnection conn2 = connectionWithId(id2);
            MonzoConnection conn3 = connectionWithId(id3);

            when(tokenRefreshService.findExpiringConnections(any()))
                    .thenReturn(List.of(conn1, conn2, conn3));
            when(tokenRefreshService.refresh(id1))
                    .thenAnswer(inv -> connectionWithId(id1));
            when(tokenRefreshService.refresh(id2))
                    .thenThrow(new ApiException(ErrorCode.PROVIDER_API_ERROR, "Monzo error"));
            when(tokenRefreshService.refresh(id3))
                    .thenAnswer(inv -> connectionWithId(id3));

            // When - should not throw
            assertThatNoException().isThrownBy(() -> job.refreshExpiringSoon());

            // Then - all three were attempted
            verify(tokenRefreshService).refresh(id1);
            verify(tokenRefreshService).refresh(id2);
            verify(tokenRefreshService).refresh(id3);
        }

        @Test
        @DisplayName("should skip calling refresh when no connections are expiring")
        void shouldSkipWhenNoConnectionsExpiring() {
            // Given
            when(properties.jobRefreshWindowMinutes()).thenReturn(60);
            when(tokenRefreshService.findExpiringConnections(any())).thenReturn(List.of());

            // When
            job.refreshExpiringSoon();

            // Then
            verify(tokenRefreshService, never()).refresh(any());
        }

        @Test
        @DisplayName("should use configured window from properties")
        void shouldUseConfiguredWindowFromProperties() {
            // Given — custom window of 90 minutes overrides default
            when(properties.jobRefreshWindowMinutes()).thenReturn(90);
            when(tokenRefreshService.findExpiringConnections(any())).thenReturn(List.of());

            Instant beforeCall = Instant.now().plus(89, ChronoUnit.MINUTES);

            // When
            job.refreshExpiringSoon();

            // Then
            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(tokenRefreshService).findExpiringConnections(captor.capture());
            assertThat(captor.getValue()).isAfter(beforeCall);
            assertThat(captor.getValue()).isBefore(Instant.now().plus(91, ChronoUnit.MINUTES));
        }
    }

    @Nested
    @DisplayName("@Scheduled annotation")
    class ScheduledAnnotationTests {

        @Test
        @DisplayName("refreshExpiringSoon() should be annotated with @Scheduled")
        void shouldHaveScheduledAnnotation() throws NoSuchMethodException {
            var method = MonzoTokenRefreshJob.class.getMethod("refreshExpiringSoon");
            assertThat(method.isAnnotationPresent(Scheduled.class)).isTrue();
        }

        @Test
        @DisplayName("@Scheduled cron expression should reference the config property")
        void scheduledCronShouldReferenceConfigProperty() throws NoSuchMethodException {
            var method = MonzoTokenRefreshJob.class.getMethod("refreshExpiringSoon");
            Scheduled annotation = method.getAnnotation(Scheduled.class);
            assertThat(annotation.cron()).isEqualTo("${monzo.token-refresh.job-cron}");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private MonzoConnection connectionWithId(UUID id) {
        MonzoConnection conn = new MonzoConnection(
                testUser, "user_" + id.toString().substring(0, 8),
                "enc_access", "enc_refresh",
                Instant.now().plus(1, ChronoUnit.HOURS)
        );
        conn.setId(id);
        return conn;
    }
}
