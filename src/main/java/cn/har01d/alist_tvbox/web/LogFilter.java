package cn.har01d.alist_tvbox.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;

@Component
public class LogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(LogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String url = request.getRequestURI();
        if (!"/youtube-proxy".equals(url)) {
            log.info("{} - {} {} {}", getRemoteAddr(request), request.getMethod(), url, decodeUrl(request.getQueryString()));
        }
        filterChain.doFilter(request, response);
    }

    private String getRemoteAddr(HttpServletRequest req) {
        if (!StringUtils.isEmpty(req.getHeader("X-Real-IP"))) {
            return req.getHeader("X-Real-IP");
        }
        if (!StringUtils.isEmpty(req.getHeader("X-FORWARDED-FOR"))) {
            return req.getHeader("X-FORWARDED-FOR");
        }
        return req.getRemoteAddr();
    }

    private String decodeUrl(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }

        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (Exception e) {
            return text;
        }
    }
}
