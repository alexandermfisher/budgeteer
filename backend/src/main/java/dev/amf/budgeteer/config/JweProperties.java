package dev.amf.budgeteer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for JWE token management.
 * Binds properties with prefix "app.jwe" from application.properties.
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "app.jwe")
public class JweProperties {

    /**
     * Secret key for JWE encryption (Base64 encoded 32 bytes).
     * Generate with: openssl rand -base64 32
     */
    private String secretKey;

    /**
     * Access token expiry duration (default: 15 minutes).
     */
    private Duration accessTokenExpiry = Duration.ofMinutes(15);

    /**
     * Refresh token expiry duration (default: 7 days).
     */
    private Duration refreshTokenExpiry = Duration.ofDays(7);

    /**
     * Magic link token expiry duration (default: 15 minutes).
     */
    private Duration magicLinkExpiry = Duration.ofMinutes(15);

    // Getters and Setters

}
