package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.BiliCookieRefreshUtils;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE;
import static cn.har01d.alist_tvbox.util.Constants.BILIBILI_TOKEN;

@Slf4j
@Service
public class BiliCookieRefreshService {
    static final String COOKIE_INFO_API = "https://passport.bilibili.com/x/passport-login/web/cookie/info";
    static final String COOKIE_REFRESH_API = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh";
    static final String CONFIRM_REFRESH_API = "https://passport.bilibili.com/x/passport-login/web/confirm/refresh";
    private static final String CORRESPOND_API = "https://www.bilibili.com/correspond/1/%s";
    private static final Duration CHECK_INTERVAL = Duration.ofMinutes(10);

    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;
    private Instant lastCheckedAt = Instant.EPOCH;
    private String lastCheckedCookie = "";

    @Autowired
    public BiliCookieRefreshService(SettingRepository settingRepository, RestTemplateBuilder builder) {
        this(settingRepository, builder.build());
    }

    BiliCookieRefreshService(SettingRepository settingRepository, RestTemplate restTemplate) {
        this.settingRepository = settingRepository;
        this.restTemplate = restTemplate;
    }

    public synchronized String refreshIfNeeded(String cookie) {
        if (StringUtils.isBlank(cookie) || Constants.BILIBILI_CODE.equals(cookie)) {
            return cookie;
        }
        String refreshToken = settingRepository.findById(BILIBILI_TOKEN).map(Setting::getValue).orElse("");
        if (StringUtils.isBlank(refreshToken)) {
            return cookie;
        }
        if (cookie.equals(lastCheckedCookie) && Instant.now().isBefore(lastCheckedAt.plus(CHECK_INTERVAL))) {
            return cookie;
        }
        String csrf = BiliCookieRefreshUtils.getCookieValue(cookie, "bili_jct");
        if (StringUtils.isBlank(csrf)) {
            return cookie;
        }
        try {
            JsonNode info = restTemplate.exchange(COOKIE_INFO_API, HttpMethod.GET, new HttpEntity<>(buildHeaders(cookie, false)), JsonNode.class).getBody();
            if (!needsRefresh(info)) {
                remember(cookie);
                return cookie;
            }

            long timestamp = Optional.ofNullable(info)
                    .map(node -> node.path("data"))
                    .map(node -> node.path("timestamp"))
                    .filter(JsonNode::canConvertToLong)
                    .map(JsonNode::asLong)
                    .orElse(System.currentTimeMillis());
            String correspondPath = BiliCookieRefreshUtils.getCorrespondPath(timestamp);
            String html = restTemplate.exchange(String.format(CORRESPOND_API, correspondPath), HttpMethod.GET, new HttpEntity<>(buildHeaders(cookie, false)), String.class).getBody();
            String refreshCsrf = BiliCookieRefreshUtils.extractRefreshCsrf(html);
            if (StringUtils.isBlank(refreshCsrf)) {
                log.warn("B站 Cookie 刷新失败：未获取到 refresh_csrf");
                return cookie;
            }

            MultiValueMap<String, String> refreshBody = new LinkedMultiValueMap<>();
            refreshBody.add("csrf", csrf);
            refreshBody.add("refresh_csrf", refreshCsrf);
            refreshBody.add("source", "main_web");
            refreshBody.add("refresh_token", refreshToken);

            ResponseEntity<JsonNode> refreshResponse = restTemplate.exchange(
                    COOKIE_REFRESH_API,
                    HttpMethod.POST,
                    new HttpEntity<>(refreshBody, buildHeaders(cookie, true)),
                    JsonNode.class
            );
            JsonNode refreshJson = refreshResponse.getBody();
            if (refreshJson == null || refreshJson.path("code").asInt(-1) != 0) {
                log.warn("B站 Cookie 刷新失败：{}", refreshJson == null ? "empty response" : refreshJson.path("message").asText());
                return cookie;
            }

            String newCookie = BiliCookieRefreshUtils.mergeCookieHeader(cookie, refreshResponse.getHeaders().get(HttpHeaders.SET_COOKIE));
            if (!newCookie.contains("buvid3=")) {
                newCookie += "; buvid3=" + UUID.randomUUID() + ThreadLocalRandom.current().nextInt(10000, 99999) + "infoc";
            }
            String newRefreshToken = refreshJson.path("data").path("refresh_token").asText("");
            settingRepository.save(new Setting(BILIBILI_COOKIE, newCookie));
            if (StringUtils.isNotBlank(newRefreshToken)) {
                settingRepository.save(new Setting(BILIBILI_TOKEN, newRefreshToken));
            }

            confirmRefresh(newCookie, refreshToken);
            remember(newCookie);
            log.info("B站 Cookie 已刷新");
            return newCookie;
        } catch (Exception e) {
            log.warn("B站 Cookie 刷新异常", e);
            return cookie;
        }
    }

    private void confirmRefresh(String cookie, String oldRefreshToken) {
        String csrf = BiliCookieRefreshUtils.getCookieValue(cookie, "bili_jct");
        if (StringUtils.isBlank(csrf)) {
            return;
        }
        MultiValueMap<String, String> confirmBody = new LinkedMultiValueMap<>();
        confirmBody.add("csrf", csrf);
        confirmBody.add("refresh_token", oldRefreshToken);
        try {
            JsonNode confirm = restTemplate.exchange(
                    CONFIRM_REFRESH_API,
                    HttpMethod.POST,
                    new HttpEntity<>(confirmBody, buildHeaders(cookie, true)),
                    JsonNode.class
            ).getBody();
            if (confirm == null || confirm.path("code").asInt(-1) != 0) {
                log.warn("B站 Cookie 刷新确认失败：{}", confirm == null ? "empty response" : confirm.path("message").asText());
            }
        } catch (Exception e) {
            log.warn("B站 Cookie 刷新确认异常", e);
        }
    }

    private boolean needsRefresh(JsonNode info) {
        return info != null
                && info.path("code").asInt(-1) == 0
                && info.path("data").path("refresh").asBoolean(false);
    }

    private void remember(String cookie) {
        lastCheckedCookie = cookie;
        lastCheckedAt = Instant.now();
    }

    private HttpHeaders buildHeaders(String cookie, boolean form) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        headers.set(HttpHeaders.USER_AGENT, Constants.USER_AGENT);
        headers.set(HttpHeaders.ACCEPT, "*/*");
        headers.set(HttpHeaders.REFERER, "https://www.bilibili.com");
        if (form) {
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        }
        return headers;
    }
}
