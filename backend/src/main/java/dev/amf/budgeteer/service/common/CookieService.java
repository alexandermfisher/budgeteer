package dev.amf.budgeteer.service.common;

import dev.amf.budgeteer.config.JweProperties;
import dev.amf.budgeteer.util.IpAddressUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Service for managing authentication cookies.
 * Centralizes all cookie creation, extraction, and clearing logic.
 * 
 * <p>Uses {@link ResponseCookie} to support SameSite attribute for CSRF protection.</p>
 * 
 * <h3>Cookie Security Configuration:</h3>
 * <ul>
 *   <li><b>HttpOnly</b>: true - Prevents JavaScript access (XSS protection)</li>
 *   <li><b>Secure</b>: Configurable - Should be true in production (HTTPS only)</li>
 *   <li><b>SameSite</b>: Lax - CSRF protection while allowing OAuth redirects</li>
 * </ul>
 */
@Service
public class CookieService {

    private static final Logger log = LoggerFactory.getLogger(CookieService.class);
    
    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    /**
     * SameSite=Lax provides CSRF protection while allowing:
     * - OAuth callback redirects (Monzo OAuth flow)
     * - Top-level navigation requests
     * 
     * SameSite=Strict would break OAuth callbacks.
     */
    private static final String SAME_SITE_POLICY = "Lax";

    private final JweProperties jweProperties;
    private final boolean secureCookies;

    public CookieService(JweProperties jweProperties,
                         @Value("${app.cookies.secure:false}") boolean secureCookies) {
        this.jweProperties = jweProperties;
        this.secureCookies = secureCookies;
    }

    /**
     * Sets the access token cookie on the response.
     * 
     * <p>Cookie is scoped to /api path and includes SameSite=Lax for CSRF protection.</p>
     *
     * @param response the HTTP response
     * @param token    the access token value
     */
    public void setAccessTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite(SAME_SITE_POLICY)
                .path("/api")
                .maxAge(jweProperties.getAccessTokenExpiry())
                .build();
        
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * Sets the refresh token cookie on the response.
     * 
     * <p>Cookie is scoped to /api/auth path (more restrictive than access token)
     * and includes SameSite=Lax for CSRF protection.</p>
     *
     * @param response the HTTP response
     * @param token    the refresh token value
     */
    public void setRefreshTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite(SAME_SITE_POLICY)
                .path("/api/auth")
                .maxAge(jweProperties.getRefreshTokenExpiry())
                .build();
        
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * Sets both access and refresh token cookies.
     *
     * @param response     the HTTP response
     * @param accessToken  the access token value
     * @param refreshToken the refresh token value
     */
    public void setAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        setAccessTokenCookie(response, accessToken);
        setRefreshTokenCookie(response, refreshToken);
        log.debug("Auth cookies set [secure={}, sameSite={}]", secureCookies, SAME_SITE_POLICY);
    }

    /**
     * Clears all authentication cookies from the response.
     * 
     * <p>Sets both cookies to empty values with maxAge=0 to delete them.</p>
     *
     * @param response the HTTP response
     */
    public void clearAuthCookies(HttpServletResponse response) {
        ResponseCookie accessCookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite(SAME_SITE_POLICY)
                .path("/api")
                .maxAge(Duration.ZERO)
                .build();
        
        ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite(SAME_SITE_POLICY)
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
        
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        
        log.debug("Auth cookies cleared");
    }

    /**
     * Extracts a cookie value from the request.
     *
     * @param request    the HTTP request
     * @param cookieName the name of the cookie
     * @return Optional containing the cookie value, or empty if not found
     */
    public Optional<String> extractCookie(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts the access token from request cookies.
     *
     * @param request the HTTP request
     * @return Optional containing the access token, or empty if not found
     */
    public Optional<String> extractAccessToken(HttpServletRequest request) {
        return extractCookie(request, ACCESS_TOKEN_COOKIE);
    }

    /**
     * Extracts the refresh token from request cookies.
     *
     * @param request the HTTP request
     * @return Optional containing the refresh token, or empty if not found
     */
    public Optional<String> extractRefreshToken(HttpServletRequest request) {
        return extractCookie(request, REFRESH_TOKEN_COOKIE);
    }

    /**
     * Extracts and validates the client IP address from the request, handling proxy headers.
     *
     * <p>Checks headers in order: {@code X-Forwarded-For}, {@code X-Real-IP}, then the
     * direct remote address. The extracted value is validated against strict IPv4/IPv6
     * format rules before being returned — invalid values (including injected strings from
     * user-controlled headers) are rejected and {@code null} is returned instead.
     *
     * @param request the HTTP request
     * @return the validated client IP address, or {@code null} if no valid IP can be determined
     */
    @Nullable
    public String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String candidate = xForwardedFor.split(",")[0].trim();
            String validated = IpAddressUtil.sanitize(candidate);
            if (validated != null) {
                return validated;
            }
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            String validated = IpAddressUtil.sanitize(xRealIp.trim());
            if (validated != null) {
                return validated;
            }
        }

        return IpAddressUtil.sanitize(request.getRemoteAddr());
    }
}
