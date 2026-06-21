package dev.amfshr.budgeteer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Monzo transaction sync.
 *
 * <p>{@code job-cron} is consumed directly by {@code @Scheduled} and is not bound here.
 *
 * <p>Registered via {@code @ConfigurationPropertiesScan} in {@link dev.amfshr.budgeteer.BudgeteerApplication}.
 */
@ConfigurationProperties(prefix = "monzo.transaction-sync")
public record TransactionSyncProperties(
        int backfillCorePoolSize,
        int backfillMaxPoolSize,
        int backfillQueueCapacity
) {
}
