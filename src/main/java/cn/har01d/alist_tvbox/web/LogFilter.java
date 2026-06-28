package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.util.Utils;
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
import java.util.Set;

@Component
public class LogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(LogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String url = request.getRequestURI();
        if (!skip(request)) {
            log.info("{} - {} {} {}", getRemoteAddr(request), request.getMethod(), url, decodeUrl(maskQuery(request.getQueryString())));
        }
        filterChain.doFilter(request, response);
    }

    private boolean skip(HttpServletRequest request) {
        String query = request.getQueryString();
        return query != null && query.contains("log=false");
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

    private static final Set<String> SENSITIVE_PARAMS = Set.of(
            "x-access-token", "token", "access_token", "refresh_token",
            "password", "passwd", "secret", "api_key", "apikey", "cookie", "sign");

    /** 对 query 中敏感参数(token/密码/签名等)的值脱敏(基于原始编码串拆分,避免解码后 & = 歧义)。 */
    private String maskQuery(String query) {
        if (StringUtils.isBlank(query)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String pair : query.split("&")) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                sb.append(pair);
                continue;
            }
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if (SENSITIVE_PARAMS.contains(name.toLowerCase())) {
                sb.append(name).append("=").append(Utils.mask(value));
            } else {
                sb.append(pair);
            }
        }
        return sb.toString();
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
