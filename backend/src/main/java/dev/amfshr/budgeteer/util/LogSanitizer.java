/**
 * Utility for logging operations in the Budgeteer application.
 */
package dev.amfshr.budgeteer.util;

/**
 * Sanitizes user-controlled input before logging to prevent log injection attacks.
 *
 * <p>Log injection occurs when an attacker can inject malicious content (like newlines,
 * tabs, or fake log entries) into log files by manipulating user input that gets logged.
 * This can be used to hide malicious activity or corrupt log integrity.
 *
 * <p>This utility replaces control characters with safe alternatives to prevent
 * log forging attacks.
 *
 * @see <a href="https://owasp.org/www-community/attacks/Log_Injection">OWASP Log Injection</a>
 */
public final class LogSanitizer {

    private LogSanitizer() {
        // Utility class - no instantiation
    }

    /**
     * Sanitizes user input for safe logging by replacing control characters.
     *
     * <p>Replaces newlines, carriage returns, and tabs with underscores to prevent
     * log injection attacks where attackers could forge log entries.
     *
     * @param input the user-controlled input to sanitize
     * @return the sanitized string safe for logging, or null if input was null
     */
    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        // Replace newlines, carriage returns, and tabs with underscores
        // This prevents attackers from injecting fake log entries
        return input.replaceAll("[\n\r\t]", "_");
    }

    /**
     * Sanitizes and truncates user input for safe logging.
     *
     * <p>Useful for potentially long inputs where you want to limit log size.
     *
     * @param input the user-controlled input to sanitize
     * @param maxLength the maximum length of the output string
     * @return the sanitized and truncated string, or null if input was null
     */
    public static String sanitize(String input, int maxLength) {
        if (input == null) {
            return null;
        }
        String sanitized = sanitize(input);
        if (sanitized.length() > maxLength) {
            return sanitized.substring(0, maxLength) + "...";
        }
        return sanitized;
    }

    /**
     * Masks a sensitive value for logging (shows only first few and last few characters).
     *
     * <p>Useful for logging identifiers where you need some visibility but don't want
     * to expose the full value.
     *
     * @param input the sensitive value to mask
     * @param visibleChars number of characters to show at start and end
     * @return the masked string, or null if input was null
     */
    public static String mask(String input, int visibleChars) {
        if (input == null) {
            return null;
        }
        if (input.length() <= visibleChars * 2) {
            return "***";
        }
        return input.substring(0, visibleChars) + "***" + input.substring(input.length() - visibleChars);
    }

    /**
     * Masks an email address for logging (shows first 2 chars and domain).
     *
     * <p>Example: "john.doe@example.com" becomes "jo***@example.com"
     *
     * @param email the email address to mask
     * @return the masked email, or null if input was null
     */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        int visibleChars = Math.min(2, atIndex);
        return email.substring(0, visibleChars) + "***" + email.substring(atIndex);
    }
}
