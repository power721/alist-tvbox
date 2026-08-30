package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * TMDB 访问线路:官方 api/image 域名国内直连不通,Setting tmdb_api_host / tmdb_image_host 可切换到反代镜像。
 * Worker 型镜像 API 与图片同域(只配 tmdb_api_host 即可);NAStool 型分开(tmdb.nastool.org 管 API、
 * img.nastool.org/t/p 管图床),需另配 tmdb_image_host。即读即用无缓存,改设置立即生效;
 * 未配置/非法值一律回落官方直连,行为与历史版本一致。
 *
 * 凭证同此:Setting tmdb_api_key 支持两形态——v3 api key(32 位,拼 query)与 v4 read access
 * token(eyJ 开头 JWT,走 Authorization: Bearer,不落 URL/代理访问日志),按值自动识别。
 */
@Slf4j
@Component
public class TmdbEndpoint {
    public static final String SETTING_NAME = "tmdb_api_host";
    public static final String SETTING_NAME_IMAGE = "tmdb_image_host";
    public static final String SETTING_NAME_KEY = "tmdb_api_key";
    public static final String OFFICIAL_API = "https://api.themoviedb.org";
    private static final String MEDIA_HOST = "https://media.themoviedb.org";
    private static final String IMAGE_HOST = "https://image.tmdb.org";

    private final SettingRepository settingRepository;

    public TmdbEndpoint(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    /** API base(形如 https://api.themoviedb.org,不带 /3);尾斜杠归一,http(s) 之外的值视为无效回落官方。 */
    public String apiHost() {
        String host = normalize(readSetting(SETTING_NAME));
        return host != null ? host : OFFICIAL_API;
    }

    public boolean isMirrorEnabled() {
        return imageHost() != null;
    }

    /** 图片镜像 base(不含 /t/p 路径);null = 不重写(官方直连)。
     * 未单独配置图床时跟随 tmdb_api_host(Worker 同域反代 /t/p/),两者均未配置才回落官方。 */
    String imageHost() {
        String host = normalize(readSetting(SETTING_NAME_IMAGE));
        if (host != null) {
            return host;
        }
        String api = apiHost();
        return OFFICIAL_API.equals(api) ? null : api;
    }

    /** 官方图床(media.themoviedb.org 301→image.tmdb.org,两者国内均被墙,/images 代理的后端出网跳就是死在这)拉取前重写为镜像;
     * 非 TMDB 域名或镜像不安全(内网等,与 /images 入口的 SSRF 口径一致)时原样返回。 */
    public String rewriteImage(String url) {
        String mirror = imageHost();
        if (StringUtils.isBlank(url) || mirror == null) {
            return url;
        }
        if (url.startsWith(MEDIA_HOST + "/") || url.startsWith(IMAGE_HOST + "/")) {
            String rewritten = mirror + url.substring(url.indexOf('/', 8));
            return Utils.isSafeExternalUrl(rewritten) ? rewritten : url;
        }
        return url;
    }

    private String readSetting(String name) {
        return settingRepository.findById(name).map(Setting::getValue)
                .filter(StringUtils::isNotBlank).orElse("").trim();
    }

    /** 当前生效凭证:Setting tmdb_api_key(两形态任一);空回落内置公共 key(api key 形态)。 */
    public String apiKey() {
        return settingRepository.findById(SETTING_NAME_KEY).map(Setting::getValue)
                .filter(StringUtils::isNotBlank).orElse(Constants.TMDB_API_KEY);
    }

    /** read access token(v4 JWT)走 Authorization: Bearer;v3 api key 拼 query。v3 端点两种都接受,只读场景等价。 */
    public boolean isBearerToken() {
        return apiKey().startsWith("eyJ");
    }

    /** v3 形态把 api_key 追加进 query;Bearer 形态原样返回(凭证改走 header)。 */
    public String appendApiKey(String url) {
        if (isBearerToken()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "api_key=" + apiKey();
    }

    /** Bearer 形态给 headers 加 Authorization;v3 形态原样返回(headers 传引用就地修改)。 */
    public HttpHeaders applyAuth(HttpHeaders headers) {
        if (isBearerToken()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey());
        }
        return headers;
    }

    /** 尾斜杠与 /t/p 路径尾巴归一(img.nastool.org/t/p 这类图床前缀写法);空值或非 http(s) 返回 null。 */
    private String normalize(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/t/p")) {
            value = value.substring(0, value.length() - 4);
        }
        if (value.isEmpty()) {
            return null;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            log.warn("invalid tmdb mirror setting: {}", value);
            return null;
        }
        return value;
    }
}
