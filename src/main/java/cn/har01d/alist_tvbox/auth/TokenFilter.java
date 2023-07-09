package cn.har01d.alist_tvbox.auth;

import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class TokenFilter extends OncePerRequestFilter {
    private final TokenService tokenService;

    public TokenFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = getToken(request);
            if (token != null) {
                Authentication authentication = buildAuthentication(token);
                SecurityContextHolder.getContext().setAuthentication(authentication);
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
