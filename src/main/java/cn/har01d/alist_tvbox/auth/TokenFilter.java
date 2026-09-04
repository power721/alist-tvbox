package cn.har01d.alist_tvbox.auth;

import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.util.Constants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;

@Slf4j
@Component
public class TokenFilter extends OncePerRequestFilter {
    private final TokenService tokenService;

    @Lazy
    @Autowired(required = false)
    private SubscriptionService subscriptionService;
    private volatile String apiKey;
    private volatile String basicAuthCredentials;

    public TokenFilter(TokenService tokenService, SettingRepository settingRepository) {
        this.tokenService = tokenService;
        apiKey = settingRepository.findById("api_key").map(Setting::getValue).orElse("");
        basicAuthCredentials = loadBasicAuthCredentials(settingRepository);
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setBasicAuthCredentials(String basicAuthCredentials) {
        this.basicAuthCredentials = basicAuthCredentials;
    }

    private static String loadBasicAuthCredentials(SettingRepository settingRepository) {
        String username = settingRepository.findById(Constants.BASIC_AUTH_USERNAME).map(Setting::getValue).orElse("");
        String password = settingRepository.findById(Constants.BASIC_AUTH_PASSWORD).map(Setting::getValue).orElse("");
        if (username.isEmpty() || password.isEmpty()) {
            return null;
        }
        return encodeBasic(username, password);
    }

    static String encodeBasic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            if (StringUtils.isNotBlank(apiKey)) {
                String key = request.getHeader("X-API-KEY");
                if (key != null && MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
                    Authentication authentication = new UsernamePasswordAuthenticationToken("client", key, Set.of(new SimpleGrantedAuthority(Role.CLIENT.name())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            String uri = request.getRequestURI();
            if (uri.startsWith("/node") || uri.startsWith("/cat")) {
                // 完整 basic auth 协议:无/错凭证一律 401 + WWW-Authenticate 挑战,
                // 客户端(猫影视系,URL 内嵌凭证)凭挑战重试;basic 凭证已随订阅地址下发(含 USER)。
                // 例外:bundle 自定义爬虫装载器的清单/爬虫/依赖请求(/node/{token}/custom|lib/**)
                // 不带凭证,凭路径 vod token 放行——爬虫文件非敏感,配置三件套仍须完整挑战。
                String auth = request.getHeader("Authorization");
                boolean ok = basicAuthCredentials != null && auth != null
                        && MessageDigest.isEqual(basicAuthCredentials.getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8));
                if (!ok && isCustomResourcePath(uri) && hasValidVodTokenInPath(uri)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                if (!ok) {
                    response.setHeader("Www-Authenticate", "Basic realm=\"alist\"");
                    response.sendError(401);
                    return;
                }
                filterChain.doFilter(request, response);
                return;
            }

            String token = getToken(request);
            if (StringUtils.isNotBlank(token)) {
                try {
                    Authentication authentication = buildAuthentication(token);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (UserUnauthorizedException e) {
                    // 播放同步端点自带令牌鉴权:Authorization 里可能是播放令牌(或 Bearer 形式),
                    // 不是会话令牌。此处不能提前 401,交给控制器按播放令牌解析。
                    if (!PLAYBACK_SYNC_PATHS.contains(uri)) {
                        throw e;
                    }
                    log.debug("非会话令牌,交由播放同步端点解析: {}", uri);
                }
            }
            filterChain.doFilter(request, response);
        } catch (UserUnauthorizedException e) {
            sendError(response, e);
        } finally {
            SecurityContextHolder.clearContext();
            if (subscriptionService != null) {
                subscriptionService.clearRequestContext();
            }
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

    // 仅这些 GET 下载端点允许 query token(浏览器 window.location.href 无法设置 Authorization 头)
    private static final Set<String> TOKEN_QUERY_DOWNLOAD_PATHS = Set.of(
            "/api/settings/export", "/api/settings/export-json",
            "/api/export-shares", "/api/logs/download", "/api/index-files/download",
            "/api/static-files/download", "/api/cat/download");

    // permitAll 的播放同步端点:令牌即鉴权,由 PlaybackSyncController 解析(playback_token ∪ session)
    private static final Set<String> PLAYBACK_SYNC_PATHS = Set.of(
            "/api/playback/event", "/api/playback/events", "/api/playback/changes", "/api/playback/sync");

    // 装载器资源:清单/爬虫/依赖(/node/{token}/custom/** 与 /node/{token}/lib/**)
    private static boolean isCustomResourcePath(String uri) {
        return uri.startsWith("/node/") && (uri.contains("/custom/") || uri.contains("/lib/"));
    }

    /**
     * /node/{token}/... 的路径第二段是 vod token:合法(共享 token 或 u- 用户 token)即认可。
     * checkToken 同时会设置请求级 tenant/currentToken,控制器里会再走一遍,幂等。
     */
    private boolean hasValidVodTokenInPath(String uri) {
        if (subscriptionService == null) {
            return false;
        }
        String[] parts = uri.split("/");
        if (parts.length < 3 || !"node".equals(parts[1])) {
            return false;
        }
        try {
            subscriptionService.checkToken(parts[2]);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getToken(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (StringUtils.isBlank(token)
                && "GET".equalsIgnoreCase(request.getMethod())
                && TOKEN_QUERY_DOWNLOAD_PATHS.stream().anyMatch(p -> request.getRequestURI().startsWith(p))) {
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
            AbstractAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userToken.getUsername(), userToken.getToken(), userToken.getAuthorities());
            authToken.setDetails(userToken.getUserId());
            return authToken;
        } catch (UserUnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("Token失效", e);
            throw new UserUnauthorizedException("Token失效", 40100, e);
        }
    }
}
