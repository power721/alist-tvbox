package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 直播平台用户 cookie 管理(pure_live 的 cookie 管理中心对应物):
 * 抖音/SOOP 的 key 由本服务定义,B站复用既有 bilibili_cookie(BilibiliService 已在读)。
 * 保存后通知平台服务丢弃内存态,立即以新身份生效。
 */
@Slf4j
@Service
public class LivePlatformCookieService {
    /** platform type → Setting 存储键。 */
    private static final Map<String, String> COOKIE_KEYS = new LinkedHashMap<>();

    static {
        COOKIE_KEYS.put("douyin", DouyinService.COOKIE_SETTING);
        COOKIE_KEYS.put("douyu", DouyuService.COOKIE_SETTING);
        COOKIE_KEYS.put("bili", "bilibili_cookie");
        COOKIE_KEYS.put("soop", SoopService.COOKIE_SETTING);
    }

    private final SettingRepository settingRepository;
    private final DouyinService douyinService;
    private final RestTemplate restTemplate;

    public LivePlatformCookieService(SettingRepository settingRepository, DouyinService douyinService, RestTemplateBuilder builder) {
        this.settingRepository = settingRepository;
        this.douyinService = douyinService;
        this.restTemplate = builder.build();
    }

    public Map<String, String> cookieKeys() {
        return COOKIE_KEYS;
    }

    public String getCookie(String platform) {
        String key = COOKIE_KEYS.get(platform);
        return key == null ? null : settingRepository.findById(key).map(Setting::getValue).orElse("");
    }

    /** 保存(空串等于清除);抖音同步失效内存态与风控冷却;斗鱼记录保存时间戳(网页 Cookie 7 天时效起算点)
     *  并做粘贴保护(passport 凭证串不带会话,只取走 LTP0/dy_did 合并进既有登录,防整串覆盖等于登出)。 */
    public void save(String platform, String cookie) {
        String key = COOKIE_KEYS.get(platform);
        if (key == null) {
            throw new IllegalArgumentException("unknown platform: " + platform);
        }
        String value = cookie == null ? "" : cookie.trim();
        if (value.isEmpty()) {
            settingRepository.deleteById(key);
            if ("douyu".equals(platform)) {
                settingRepository.deleteById(DouyuService.COOKIE_SAVED_AT_SETTING);
            }
        } else {
            if ("douyu".equals(platform)) {
                String existing = settingRepository.findById(key).map(Setting::getValue).orElse("");
                value = DouyuService.normalizePastedCookie(existing, value);
                settingRepository.save(new Setting(DouyuService.COOKIE_SAVED_AT_SETTING,
                        String.valueOf(System.currentTimeMillis() / 1000)));
            }
            settingRepository.save(new Setting(key, value));
        }
        if ("douyin".equals(platform)) {
            douyinService.invalidateCookie();
        }
        log.info("live platform cookie updated: {}", platform);
    }

    /** 验证 cookie 可用性:B站校验登录态返回账号名;抖音/SOOP 无免签名的账号接口,做连通性验证。 */
    public String[] verify(String platform, String cookie) throws IOException {
        String value = cookie == null ? "" : cookie.trim();
        if (value.isEmpty()) {
            return new String[]{"false", "cookie 为空"};
        }
        return switch (platform) {
            case "bili" -> verifyBili(value);
            case "douyin" -> verifyDouyin(value);
            case "douyu" -> verifyDouyu(value);
            case "soop" -> verifySoop(value);
            default -> new String[]{"false", "不支持的平台: " + platform};
        };
    }

    /** 斗鱼无公开账号接口,登录态按 Cookie 内 token 判定(pure_live 3.1.6 同款):H5 的 acf_jwt_token/acf_auth
     *  是 JWT 可读到期时间,网页版 dy_auth 是有效登录但读不到到期;getEncryption 仅验证连通与描述符。 */
    private String[] verifyDouyu(String cookie) {
        String token = DouyuService.sessionToken(cookie);
        String stateNote;
        if (token == null) {
            stateNote = "游客 Cookie(未含登录凭证)";
        } else {
            Long expiry = DouyuService.sessionExpirySeconds(cookie, null);
            long now = System.currentTimeMillis() / 1000;
            if (expiry != null && expiry <= now) {
                boolean refreshable = DouyuService.cookieField(cookie, "LTP0") != null;
                return new String[]{"false", "登录已过期" + (refreshable ? "(含 LTP0,播放时将自动续期)" : ",请重新粘贴 Cookie")};
            }
            stateNote = expiry != null ? "已登录,有效至 " + java.time.Instant.ofEpochSecond(expiry)
                    : "已登录(网页版 Cookie,未提供到期时间)";
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.COOKIE, cookie);
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.REFERER, "https://www.douyu.com/");
            JsonNode root = restTemplate.exchange(
                    "https://www.douyu.com/wgapi/livenc/liveweb/websec/getEncryption?did=10000000000000000000000000001501",
                    HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
            if (root.path("error").asInt(-1) == 0 && root.path("data").path("enc_data").isTextual()) {
                return new String[]{"true", stateNote + ";请求连通正常"};
            }
            return new String[]{"false", "斗鱼返回结构异常: error=" + root.path("error").asInt(-1)};
        } catch (Exception e) {
            log.warn("verify douyu cookie failed", e);
            return new String[]{"false", "验证请求失败: " + e.getMessage()};
        }
    }

    private String[] verifyBili(String cookie) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.COOKIE, cookie);
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.REFERER, "https://www.bilibili.com/");
            JsonNode root = restTemplate.exchange("https://api.bilibili.com/x/member/web/account", HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody();
            int code = root.path("code").asInt(-1);
            if (code == 0) {
                return new String[]{"true", "已登录: " + root.path("data").path("uname").asText("B站账号")};
            }
            if (code == -101) {
                return new String[]{"false", "cookie 未登录或已过期"};
            }
            return new String[]{"false", "B站返回 code=" + code + ": " + root.path("message").asText("")};
        } catch (Exception e) {
            log.warn("verify bili cookie failed", e);
            return new String[]{"false", "验证请求失败: " + e.getMessage()};
        }
    }

    private String[] verifyDouyin(String cookie) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.COOKIE, cookie);
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0");
            var response = restTemplate.exchange("https://live.douyin.com/?from_nav=1", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && !response.getBody().isEmpty()) {
                return new String[]{"true", "请求连通正常(抖音无免签名账号接口,仅验证连通)"};
            }
            return new String[]{"false", "抖音返回异常: HTTP " + response.getStatusCode().value()};
        } catch (Exception e) {
            log.warn("verify douyin cookie failed", e);
            return new String[]{"false", "验证请求失败: " + e.getMessage()};
        }
    }

    private String[] verifySoop(String cookie) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.COOKIE, cookie);
            headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36");
            JsonNode root = restTemplate.exchange("https://sch.sooplive.com/api.php?m=categoryList&szOrder=view_cnt&nListCnt=5&szPlatform=pc", HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody();
            if (root.path("data").path("list").isArray()) {
                return new String[]{"true", "请求连通正常(SOOP 无公开账号接口,仅验证连通)"};
            }
            return new String[]{"false", "SOOP 返回结构异常"};
        } catch (Exception e) {
            log.warn("verify soop cookie failed", e);
            return new String[]{"false", "验证请求失败: " + e.getMessage()};
        }
    }
}
