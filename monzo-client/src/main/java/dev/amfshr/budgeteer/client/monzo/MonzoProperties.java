package dev.amfshr.budgeteer.client.monzo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Monzo OAuth integration.
 * Values are loaded from application.properties or environment variables.
 * 
 * Registered as a bean via @ConfigurationPropertiesScan in BudgeteerApplication.
 */
@ConfigurationProperties(prefix = "monzo")
public record MonzoProperties(
    String clientId,
    String clientSecret,
    String redirectUri,
    String authUrl,
    String tokenUrl,
    String apiBaseUrl
) {}
