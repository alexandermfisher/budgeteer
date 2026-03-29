package dev.amf.budgeteer.util;

import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Utility for validating and normalizing IP addresses from untrusted input.
 *
 * <p>IP addresses sourced from HTTP headers like {@code X-Forwarded-For} are user-controlled
 * and must be validated before storage or logging. This utility rejects anything that is not
 * a well-formed IPv4 or IPv6 address.
 */
public final class IpAddressUtil {

    // Strict IPv4: each octet 0–255, no leading zeros beyond single digit
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d{1,2})\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d{1,2})$");

    // Pre-check for IPv6: only hex digits and colons — guarantees InetAddress.getByName
    // cannot perform a DNS lookup (no letters outside [a-fA-F], no dots, no hyphens).
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:]+$");

    private IpAddressUtil() {
    }

    /**
     * Validates and normalizes an IP address string.
     *
     * <p>Accepts well-formed IPv4 (e.g. {@code 192.168.1.1}) and IPv6
     * (e.g. {@code 2001:db8::1}, {@code ::1}) addresses. Returns {@code null}
     * for any input that is not a valid numeric IP address, including hostnames,
     * out-of-range octets, and injected strings.
     *
     * @param candidate the raw IP string to validate (may be null or blank)
     * @return the normalized IP string, or {@code null} if invalid
     */
    @Nullable
    public static String sanitize(@Nullable String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String trimmed = candidate.trim();

        if (IPV4_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }

        // IPv6: must contain a colon and consist only of hex digits and colons.
        // The character pre-check ensures InetAddress.getByName cannot resolve a hostname.
        if (trimmed.contains(":") && IPV6_CHARS.matcher(trimmed).matches()) {
            try {
                return InetAddress.getByName(trimmed).getHostAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }

        return null;
    }
}
