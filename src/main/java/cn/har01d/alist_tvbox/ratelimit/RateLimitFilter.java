package cn.har01d.alist_tvbox.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiting filter to prevent brute force attacks and API abuse.
 * Uses token bucket algorithm with per-IP and per-endpoint limits.
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    // Global rate limit: 100 requests per second per IP
    private static final int GLOBAL_RATE_LIMIT = 100;
    private static final long GLOBAL_WINDOW_MS = 1000;

    // Authentication endpoints: 5 requests per minute per IP
    private static final int AUTH_RATE_LIMIT = 5;
    private static final long AUTH_WINDOW_MS = 60000;

    // Per-IP rate limiters cache
    private final Cache<String, TokenBucket> globalLimiters = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    private final Cache<String, TokenBucket> authLimiters = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String clientIp = getClientIpAddress(request);
        String uri = request.getRequestURI();

        // Check authentication endpoint rate limit first (stricter)
        if (isAuthEndpoint(uri)) {
            TokenBucket authBucket = authLimiters.get(clientIp,
                    key -> new TokenBucket(AUTH_RATE_LIMIT, AUTH_WINDOW_MS));

            if (!authBucket.tryConsume()) {
                log.warn("Rate limit exceeded for auth endpoint | ip={} | uri={}", clientIp, uri);
                response.setStatus(429); // Too Many Requests
                response.setHeader("Retry-After", "60");
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Too many authentication attempts. Please try again later.\"}");
                return;
            }
        }

        // Check global rate limit
        TokenBucket globalBucket = globalLimiters.get(clientIp,
                key -> new TokenBucket(GLOBAL_RATE_LIMIT, GLOBAL_WINDOW_MS));

        if (!globalBucket.tryConsume()) {
            log.warn("Global rate limit exceeded | ip={} | uri={}", clientIp, uri);
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded. Please slow down.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Check if endpoint is authentication-related (requires stricter limits).
     */
    private boolean isAuthEndpoint(String uri) {
        return uri.contains("/login") ||
               uri.contains("/logout") ||
               uri.contains("/token") ||
               uri.contains("/api/accounts/login");
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

        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip != null ? ip : "unknown";
    }

    /**
     * Simple token bucket implementation for rate limiting.
     */
    private static class TokenBucket {
        private final int capacity;
        private final long windowMs;
        private final AtomicInteger tokens;
        private final AtomicLong lastRefillTime;

        public TokenBucket(int capacity, long windowMs) {
            this.capacity = capacity;
            this.windowMs = windowMs;
            this.tokens = new AtomicInteger(capacity);
            this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        }

        public synchronized boolean tryConsume() {
            refill();

            if (tokens.get() > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long lastRefill = lastRefillTime.get();
            long timeSinceRefill = now - lastRefill;

            if (timeSinceRefill >= windowMs) {
                tokens.set(capacity);
                lastRefillTime.set(now);
            }
        }
    }
}
