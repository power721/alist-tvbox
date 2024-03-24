package cn.har01d.alist_tvbox.auth;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Slf4j
@Component
public class TokenFilter extends OncePerRequestFilter {
    private final TokenService tokenService;
    private final String apiKey;

    public TokenFilter(TokenService tokenService, SettingRepository settingRepository) {
        this.tokenService = tokenService;
        apiKey = settingRepository.findById("api_key").map(Setting::getValue).orElse("");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            if (StringUtils.isNotBlank(apiKey)) {
                String key = request.getHeader("X-API-KEY");
                if (apiKey.equals(key)) {
                    Authentication authentication = new UsernamePasswordAuthenticationToken("client", key, Set.of(new SimpleGrantedAuthority("client")));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            String token = getToken(request);
            if (token != null) {
                Authentication authentication = buildAuthentication(token);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else if (request.getRequestURI().startsWith("/open") || request.getRequestURI().startsWith("/node") || request.getRequestURI().startsWith("/cat")) {
                String auth = request.getHeader("Authorization");
                if (StringUtils.isBlank(auth) || !"Basic YWxpc3Q6YWxpc3Q=".equals(auth)) {
                    response.setHeader("Www-Authenticate", "Basic realm=\"alist\"");
                    response.sendError(401);
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } catch (UserUnauthorizedException e) {
            sendError(response, e);
        }
    }

    private void sendError(HttpServletResponse response, UserUnauthorizedException e) {
        String body = "{\"message\":\"" + e.getMessage() + "\",\"code\":" + e.getCode() + "}";
        response.setContentType("application/json");
        response.setStatus(401);
        try {
            StreamUtils.copy(body.getBytes(), response.getOutputStream());
        } catch (IOException ex) {
            logger.warn("send error failed", ex);
        }
    }

    private String getToken(HttpServletRequest request) {
        var token = request.getHeader("X-ACCESS-TOKEN");
        if (token == null || token.isEmpty()) {
            token = request.getParameter("X-ACCESS-TOKEN");
        }
        return token;
    }

    private Authentication buildAuthentication(String token) {
        try {
            UserToken userToken = tokenService.extractToken(token);
            if (userToken == null) {
                return null;
            }
            return new UsernamePasswordAuthenticationToken(userToken.getName(), "", userToken.getAuthorities());
        } catch (UserUnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("Token失效", e);
            throw new UserUnauthorizedException("Token失效", 40100, e);
        }
    }
}
