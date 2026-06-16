package cn.har01d.alist_tvbox.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Security audit logging filter to track security-relevant operations.
 * Logs authentication attempts, authorization failures, and sensitive operations.
 */
@Slf4j
@Component
public class SecurityAuditFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String method = request.getMethod();
        String uri = request.getRequestURI();
        String remoteAddr = getClientIpAddress(request);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : "anonymous";

        long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            long duration = System.currentTimeMillis() - startTime;

            // Log security-relevant operations
            if (shouldAuditLog(method, uri, status)) {
                logSecurityEvent(method, uri, status, username, remoteAddr, duration);
            }
        }
    }

    /**
     * Determine if this request should be audit logged.
     */
    private boolean shouldAuditLog(String method, String uri, int status) {
        // Always log authentication/authorization endpoints
        if (uri.contains("/login") || uri.contains("/logout") || uri.contains("/token")) {
            return true;
        }

        // Log failed authentication/authorization
        if (status == 401 || status == 403) {
            return true;
        }

        // Log sensitive write operations
        if (("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method) || "PATCH".equals(method))) {
            // Log user/account management
            if (uri.contains("/api/users") || uri.contains("/api/accounts")) {
                return true;
            }
            // Log settings changes
            if (uri.contains("/api/settings")) {
                return true;
            }
            // Log security configuration changes
            if (uri.contains("/api/roles") || uri.contains("/api/permissions")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Log security event with relevant details.
     */
    private void logSecurityEvent(String method, String uri, int status, String username, String remoteAddr, long duration) {
        String eventType = getEventType(method, uri, status);

        log.info("SECURITY_AUDIT | timestamp={} | event={} | user={} | ip={} | method={} | uri={} | status={} | duration={}ms",
                Instant.now().toString(),
                eventType,
                username,
                remoteAddr,
                method,
                uri,
                status,
                duration);

        // Additional warning for failed auth attempts
        if (status == 401 || status == 403) {
            log.warn("SECURITY_ALERT | Unauthorized access attempt | user={} | ip={} | uri={} | status={}",
                    username, remoteAddr, uri, status);
        }
    }

    /**
     * Classify the security event type.
     */
    private String getEventType(String method, String uri, int status) {
        if (uri.contains("/login")) {
            return status == 200 ? "LOGIN_SUCCESS" : "LOGIN_FAILED";
        }
        if (uri.contains("/logout")) {
            return "LOGOUT";
        }
        if (status == 401) {
            return "AUTH_FAILED";
        }
        if (status == 403) {
            return "ACCESS_DENIED";
        }
        if (uri.contains("/users") || uri.contains("/accounts")) {
            return "ACCOUNT_MANAGEMENT";
        }
        if (uri.contains("/settings")) {
            return "CONFIG_CHANGE";
        }
        return "SENSITIVE_OPERATION";
    }

    /**
     * Extract client IP address, considering proxy headers.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // Take first IP if multiple (proxy chain)
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip;
    }
}
