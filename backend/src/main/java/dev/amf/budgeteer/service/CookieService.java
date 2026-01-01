package dev.amf.budgeteer.service;

import dev.amf.budgeteer.config.JweProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Service for managing authentication cookies.
 * Centralizes all cookie creation, extraction, and clearing logic.
 */
@Service
public class CookieService {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

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
     * @param response the HTTP response
     * @param token    the access token value
     */
    public void setAccessTokenCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(ACCESS_TOKEN_COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookies);
        cookie.setPath("/api");
        cookie.setMaxAge((int) jweProperties.getAccessTokenExpiry().toSeconds());
        // Note: SameSite attribute requires ResponseCookie or server config
        response.addCookie(cookie);
    }

    /**
     * Sets the refresh token cookie on the response.
     *
     * @param response the HTTP response
     * @param token    the refresh token value
     */
    public void setRefreshTokenCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookies);
        cookie.setPath("/api/auth");
        cookie.setMaxAge((int) jweProperties.getRefreshTokenExpiry().toSeconds());
        response.addCookie(cookie);
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
    }

    /**
     * Clears all authentication cookies from the response.
     *
     * @param response the HTTP response
     */
    public void clearAuthCookies(HttpServletResponse response) {
        Cookie accessCookie = new Cookie(ACCESS_TOKEN_COOKIE, "");
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(secureCookies);
        accessCookie.setPath("/api");
        accessCookie.setMaxAge(0);
        response.addCookie(accessCookie);

        Cookie refreshCookie = new Cookie(REFRESH_TOKEN_COOKIE, "");
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(secureCookies);
        refreshCookie.setPath("/api/auth");
        refreshCookie.setMaxAge(0);
        response.addCookie(refreshCookie);
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
     * Extracts the client IP address from the request, handling proxy headers.
     *
     * @param request the HTTP request
     * @return the client IP address
     */
    public String getClientIpAddress(HttpServletRequest request) {
        // Check for forwarded IP (when behind proxy/load balancer)
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // Take the first IP in the list
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
