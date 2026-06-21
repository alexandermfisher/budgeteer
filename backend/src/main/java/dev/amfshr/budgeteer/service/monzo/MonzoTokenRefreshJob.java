package dev.amfshr.budgeteer.service.monzo;

import dev.amfshr.budgeteer.config.MonzoTokenRefreshProperties;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled job that proactively refreshes Monzo OAuth tokens before they expire.
 *
 * <p>Runs on a configurable cron schedule (default: every 30 minutes). On each
 * execution it finds all active connections whose tokens expire within the configured
 * refresh window (default: 60 minutes ahead), then refreshes each one.
 *
 * <p>Each connection is refreshed in its own transaction (via
 * {@link MonzoTokenRefreshService#refresh}). A failure for one connection is logged
 * and skipped; the job continues with the rest.
 *
 * <h2>Configuration</h2>
 * <pre>
 * monzo.token-refresh.job-cron=0 &#42;/30 * * * *       # every 30 minutes
 * monzo.token-refresh.job-refresh-window-minutes=60  # refresh tokens expiring within 60 min
 * </pre>
 *
 * @see MonzoTokenRefreshService
 * @see MonzoTokenRefreshProperties
 */
@Component
public class MonzoTokenRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(MonzoTokenRefreshJob.class);

    private final MonzoTokenRefreshService tokenRefreshService;
    private final MonzoTokenRefreshProperties properties;

    public MonzoTokenRefreshJob(
            MonzoTokenRefreshService tokenRefreshService,
            MonzoTokenRefreshProperties properties
    ) {
        this.tokenRefreshService = tokenRefreshService;
        this.properties = properties;
    }

    /**
     * Finds connections expiring within the configured window and refreshes each one.
     *
     * <p>Cron expression is configurable via {@code monzo.token-refresh.job-cron}.
     * Defaults to every 30 minutes.
     */
    @Scheduled(cron = "${monzo.token-refresh.job-cron}")
    public void refreshExpiringSoon() {
        Duration window = Duration.ofMinutes(properties.jobRefreshWindowMinutes());
        Instant threshold = Instant.now().plus(window);

        List<MonzoConnection> expiring = tokenRefreshService.findExpiringConnections(threshold);

        if (expiring.isEmpty()) {
            log.debug("Token refresh job: no connections expiring within {} minutes",
                    properties.jobRefreshWindowMinutes());
            return;
        }

        log.info("Token refresh job: found {} connection(s) expiring within {} minutes",
                expiring.size(), properties.jobRefreshWindowMinutes());

        int refreshed = 0;
        int failed = 0;

        for (MonzoConnection connection : expiring) {
            try {
                tokenRefreshService.refresh(connection.getId());
                refreshed++;
            } catch (Exception e) {
                failed++;
                log.error("Token refresh job: failed to refresh connection {} - {}",
                        connection.getId(), e.getMessage());
            }
        }

        log.info("Token refresh job complete: {}/{} refreshed, {} failed",
                refreshed, expiring.size(), failed);
    }
}
