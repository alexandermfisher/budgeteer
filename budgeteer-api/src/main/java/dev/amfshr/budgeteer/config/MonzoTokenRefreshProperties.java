package dev.amfshr.budgeteer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Monzo token auto-refresh.
 *
 * <p>Controls the scheduled job frequency and the proactive refresh windows.
 *
 * <h2>Configuration keys</h2>
 * <pre>
 * # Cron expression — read directly by @Scheduled, not bound here
 * monzo.token-refresh.job-cron=0 &#42;/30 * * * *
 *
 * # Refresh tokens expiring within this many minutes (must be &gt; job interval)
 * monzo.token-refresh.job-refresh-window-minutes=60
 *
 * # Eager refresh: refresh inline if token expires within this many minutes
 * monzo.token-refresh.eager-refresh-window-minutes=5
 * </pre>
 *
 * <p>{@code job-cron} is intentionally absent from this record — it is consumed
 * directly by the {@code @Scheduled} annotation via Spring EL
 * ({@code ${monzo.token-refresh.job-cron}}) and does not need to be bound here.
 *
 * <p>Registered as a bean via {@code @ConfigurationPropertiesScan} in
 * {@link dev.amfshr.budgeteer.BudgeteerApplication}.
 */
@ConfigurationProperties(prefix = "monzo.token-refresh")
public record MonzoTokenRefreshProperties(
        int jobRefreshWindowMinutes,
        int eagerRefreshWindowMinutes
) {
}
