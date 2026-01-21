package dev.amf.budgeteer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter for logging HTTP requests and responses with structured data.
 * Adds contextual information to MDC (Mapped Diagnostic Context) for correlation.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_USER_ID = "userId";
    private static final String MDC_IP_ADDRESS = "ipAddress";
    
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        
        long startTime = System.currentTimeMillis();
        
        // Generate or extract request ID
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        
        // Add to MDC for correlation across all logs in this request
        MDC.put(MDC_REQUEST_ID, requestId);
        
        // Extract IP address
        String ipAddress = getClientIpAddress(request);
        MDC.put(MDC_IP_ADDRESS, ipAddress);
        
        // Add request ID to response headers
        response.setHeader(REQUEST_ID_HEADER, requestId);
        
        try {
            // Log incoming request
            logRequest(request, requestId);
            
            // Process request
            filterChain.doFilter(request, response);
            
            // Log response
            long duration = System.currentTimeMillis() - startTime;
            logResponse(request, response, duration, requestId);
            
        } finally {
            // Always clear MDC to prevent memory leaks
            MDC.clear();
        }
    }
    
    /**
     * Logs incoming HTTP request details.
     */
    private void logRequest(HttpServletRequest request, String requestId) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();
        String userAgent = request.getHeader("User-Agent");
        
        if (shouldLogRequest(uri)) {
            log.info("Incoming request: {} {} {} [requestId={}, userAgent={}]",
                    method,
                    uri,
                    queryString != null ? "?" + maskSensitiveQueryParams(queryString) : "",
                    requestId,
                    maskUserAgent(userAgent));
        }
    }
    
    /**
     * Logs HTTP response details including status and duration.
     */
    private void logResponse(HttpServletRequest request, HttpServletResponse response, 
                             long duration, String requestId) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        int status = response.getStatus();
        
        if (shouldLogRequest(uri)) {
            if (status >= 400) {
                log.warn("Request completed: {} {} -> {} in {}ms [requestId={}]",
                        method, uri, status, duration, requestId);
            } else {
                log.info("Request completed: {} {} -> {} in {}ms [requestId={}]",
                        method, uri, status, duration, requestId);
            }
        }
    }
    
    /**
     * Determines if the request should be logged (exclude health checks and actuator).
     */
    private boolean shouldLogRequest(String uri) {
        return !uri.startsWith("/actuator/health") && !uri.equals("/api/health/live");
    }
    
    /**
     * Extracts the client's IP address, accounting for proxies.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // X-Forwarded-For can contain multiple IPs, take the first one
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
    
    /**
     * Masks user agent to reduce log verbosity (just browser/version).
     */
    private String maskUserAgent(String userAgent) {
        if (userAgent == null || userAgent.length() < 20) {
            return userAgent;
        }
        // Just return first 50 chars to keep logs clean
        return userAgent.substring(0, Math.min(50, userAgent.length())) + "...";
    }
    
    /**
     * Masks sensitive query parameters to prevent logging tokens and secrets.
     * Replaces values of known sensitive parameters with "***".
     * 
     * <p>Sensitive parameters masked:</p>
     * <ul>
     *   <li>token - Magic link tokens</li>
     *   <li>code - OAuth authorization codes</li>
     *   <li>state - OAuth state parameters</li>
     *   <li>access_token - Access tokens (should never be in URL, but just in case)</li>
     *   <li>refresh_token - Refresh tokens (should never be in URL, but just in case)</li>
     * </ul>
     */
    private String maskSensitiveQueryParams(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return queryString;
        }
        // Mask known sensitive parameter values
        return queryString
                .replaceAll("(?i)(token)=([^&]*)", "$1=***")
                .replaceAll("(?i)(code)=([^&]*)", "$1=***")
                .replaceAll("(?i)(state)=([^&]*)", "$1=***")
                .replaceAll("(?i)(access_token)=([^&]*)", "$1=***")
                .replaceAll("(?i)(refresh_token)=([^&]*)", "$1=***");
    }
}
