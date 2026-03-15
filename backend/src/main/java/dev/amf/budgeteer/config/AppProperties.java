package dev.amf.budgeteer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * General application configuration properties.
 * Binds properties with prefix "app" from application.properties.
 * 
 * Registered as a bean via @ConfigurationPropertiesScan in BudgeteerApplication.
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /**
     * Base URL for the application (used in magic link emails).
     * Example: <a href="http://localhost:8080">...</a> or <a href="https://app.budgeteer.dev">...</a>
     */
    private String baseUrl = "http://localhost:8080";

    /**
     * URL to redirect to after successful login.
     */
    private String loginSuccessUrl = "/";

    /**
     * Whether email sending is enabled.
     * When false, magic links are logged to console (for development).
     */
    private boolean emailEnabled = false;

    /**
     * Mail configuration properties.
     */
    private Mail mail = new Mail();

    /**
     * Nested class for mail configuration.
     */
    @Setter
    @Getter
    public static class Mail {
        /**
         * From address for outgoing emails.
         * Must be a verified domain in Resend.
         */
        private String from = "noreply@budgeteer.amfshr.dev";
    }
}
