package dev.amfshr.budgeteer.provider.monzo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Monzo OAuth integration.
 * Values are loaded from application.properties or environment variables.
 * 
 * Registered via {@code MonzoAutoConfiguration}.
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
