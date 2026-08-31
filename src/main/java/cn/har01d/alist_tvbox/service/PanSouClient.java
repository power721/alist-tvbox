package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * PanSou 传输客户端:登录 token 单点持有、健康检查/auth_enabled 探测、认证 POST。
 * 搜索(RemoteSearchService)与盘检(PanLinkCheckService)共用 —— token 必须单实例持有,
 * 两处各自登录会互踢(PanSou 登录轮换 token)。PanSou 契约的 TVBox 盘型代码 → cloud 名
 * 映射也落在这里(搜索 cloud_types 与盘检 disk_type 共用一份口径)。
 */
@Slf4j
@Component
public class PanSouClient {
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private String panSouToken;
    private String checkedPanSouUrl;

    public PanSouClient(AppProperties appProperties, RestTemplateBuilder restTemplateBuilder) {
        this.appProperties = appProperties;
        this.restTemplate = restTemplateBuilder.build();
    }

    @PostConstruct
    public void setup() {
        refreshAsync();
    }

    /** PanSou 健康信息(/api/health):可达性、auth_enabled、内置频道;不可达抛出由调用方处置。 */
    public ObjectNode getPanSouInfo() {
        String url = appProperties.getPanSouUrl();
        ObjectNode info = restTemplate.getForObject(url + "/api/health", ObjectNode.class);
        if (info != null) {
            checkedPanSouUrl = StringUtils.defaultString(url);
            updateAuthEnabled(info);
        }
        return info;
    }

    /** URL 变更后异步重探健康/auth 状态;失败视为 auth 未启用。 */
    private void refreshAsync() {
        String url = appProperties.getPanSouUrl();
        checkedPanSouUrl = StringUtils.defaultString(url);
        if (StringUtils.isBlank(url)) {
            appProperties.setPanSouAuthEnabled(null);
            return;
        }
        appProperties.setPanSouAuthEnabled(null);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                getPanSouInfo();
            } catch (Exception e) {
                log.warn("check PanSou health failed: {}", url, e);
                appProperties.setPanSouAuthEnabled(null);
            }
        });
    }

    private void refreshIfUrlChanged() {
        String url = appProperties.getPanSouUrl();
        if (checkedPanSouUrl != null && !StringUtils.equals(StringUtils.defaultString(url), checkedPanSouUrl)) {
            refreshAsync();
        }
    }

    private void updateAuthEnabled(ObjectNode info) {
        if (info.has("auth_enabled")) {
            appProperties.setPanSouAuthEnabled(info.get("auth_enabled").asBoolean(false));
        }
    }

    private boolean hasCredentials() {
        return StringUtils.isNoneBlank(appProperties.getPanSouUsername(), appProperties.getPanSouPassword());
    }

    boolean shouldUseAuth() {
        refreshIfUrlChanged();
        return hasCredentials() && Boolean.TRUE.equals(appProperties.getPanSouAuthEnabled());
    }

    String token() {
        if (StringUtils.isNotBlank(panSouToken)) {
            return panSouToken;
        }
        Map<String, String> body = Map.of(
                "username", appProperties.getPanSouUsername(),
                "password", appProperties.getPanSouPassword());
        Map<?, ?> response;
        try {
            response = restTemplate.postForObject(appProperties.getPanSouUrl() + "/api/auth/login", body, Map.class);
        } catch (HttpClientErrorException.Forbidden e) {
            if (e.getResponseBodyAsString().contains("认证功能未启用")) {
                log.info("PanSou auth is disabled, use unauthenticated requests");
                appProperties.setPanSouAuthEnabled(false);
                return "";
            }
            throw e;
        }
        if (response == null || response.get("token") == null) {
            throw new IllegalStateException("PanSou login failed");
        }
        panSouToken = response.get("token").toString();
        return panSouToken;
    }

    /** 认证 POST:启用了认证且拿到 token 时带 Bearer,否则裸 POST —— PanSou 搜索/盘检共用同一形态。 */
    <T> T post(String url, Object request, Class<T> responseType) {
        if (!shouldUseAuth()) {
            return restTemplate.postForObject(url, request, responseType);
        }
        String token = token();
        if (StringUtils.isBlank(token)) {
            return restTemplate.postForObject(url, request, responseType);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), responseType).getBody();
    }

    /** TVBox 盘型代码 → PanSou cloud 名(搜索 cloud_types 与盘检 disk_type 共用);未知返回 null。 */
    public static String cloudType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> "aliyun";
            case "1" -> "pikpak";
            case "2" -> "xunlei";
            case "3" -> "123";
            case "5" -> "quark";
            case "6" -> "mobile";
            case "7" -> "uc";
            case "8" -> "115";
            case "9" -> "tianyi";
            case "10" -> "baidu";
            case "12" -> "guangya";
            case "magnet" -> "magnet";
            case "ed2k" -> "ed2k";
            default -> null;
        };
    }
}
