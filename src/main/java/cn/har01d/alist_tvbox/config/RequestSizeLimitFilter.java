package cn.har01d.alist_tvbox.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to limit HTTP request body size and prevent DoS attacks.
 * Applies to all non-multipart requests (JSON, XML, etc.)
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    // Maximum allowed request body size: 10MB
    private static final long MAX_REQUEST_SIZE = 10 * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String contentType = request.getContentType();

        // Skip multipart requests (handled by Spring's multipart config)
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check Content-Length header
        long contentLength = request.getContentLengthLong();

        if (contentLength > MAX_REQUEST_SIZE) {
            log.warn("Rejected oversized request: {} bytes from {} to {}",
                    contentLength, request.getRemoteAddr(), request.getRequestURI());
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "Request body size exceeds maximum allowed size of " + (MAX_REQUEST_SIZE / 1024 / 1024) + "MB");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
