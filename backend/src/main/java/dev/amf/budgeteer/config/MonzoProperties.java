package dev.amf.budgeteer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Monzo OAuth integration.
 * Values are loaded from application.properties or environment variables.
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
