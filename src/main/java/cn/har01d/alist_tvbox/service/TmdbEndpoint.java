package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * TMDB 访问线路:官方 api/image 域名国内直连不通,Setting tmdb_api_host / tmdb_image_host 可切换到反代镜像。
 * Worker 型镜像 API 与图片同域(只配 tmdb_api_host 即可);NAStool 型分开(tmdb.nastool.org 管 API、
 * img.nastool.org/t/p 管图床),需另配 tmdb_image_host。池按原值缓存、原值变化即重解析,改设置立即生效;
 * 未配置/非法值一律回落官方直连,行为与历史版本一致。
 *
 * 镜像池:两个 Setting 均支持逗号/分号/空白分隔多个镜像(免费 Worker 各有每日限额,轮询分摊额度),
 * 逐请求 round robin(API 与图片各自独立计数);单项值行为与历史版本完全一致。
 * 池顺序在服务启动首次读取时随机打乱(预设池被大量实例共用,固定顺序轮询会让集中重启的流量全砸第一个
 * Worker),同一原值存续期内不再重洗,保证轮询序列稳定。
 * 设置值 worker-pool = 后端内置 Worker 池(BUILTIN_WORKER_POOL):地址只存后端代码,前端预设只落哨兵值,
 * 存量显式逗号串照旧按显式池轮询,行为不变。
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
    /** tmdb_api_host/tmdb_image_host 的哨兵值:解析为内置 Worker 池,12 个地址只存后端,前端 bundle/设置值均不携带。 */
    public static final String WORKER_POOL_VALUE = "worker-pool";
    /** 内置 Worker 轮询池(免费额度分摊);启动首读即洗牌,此处书写顺序无关紧要。 */
    static final List<String> BUILTIN_WORKER_POOL = List.of(
            "https://tmdb.8866033.xyz",
            "https://tmdb.swust-oj.workers.dev",
            "https://tmdb.8866033.workers.dev",
            "https://tmdb.power348045.workers.dev",
            "https://tmdb.harold348047.workers.dev",
            "https://tmdb.ai-09b.workers.dev",
            "https://tmdb.root-df0.workers.dev",
            "https://tmdb.atv-8c1.workers.dev",
            "https://tmdb.odd-math-a42b.workers.dev",
            "https://tmdb.test-d2c.workers.dev",
            "https://tmdb.code-a96.workers.dev",
            "https://tmdb.claude-b79.workers.dev");
    public static final String OFFICIAL_API = "https://api.themoviedb.org";
    private static final String MEDIA_HOST = "https://media.themoviedb.org";
    private static final String IMAGE_HOST = "https://image.tmdb.org";
    private static final Pattern POOL_SEPARATOR = Pattern.compile("[,，;；\\s]+");

    private final SettingRepository settingRepository;
    private final AtomicInteger apiRotation = new AtomicInteger();
    private final AtomicInteger imageRotation = new AtomicInteger();
    /** 池缓存:启动首读洗一次牌,原值不变复用(轮询序列稳定);hosts 不可变,防 imageHost 跟随池的删改透传进缓存。 */
    private record Pool(String raw, List<String> hosts) {
        static final Pool EMPTY = new Pool("", List.of());
    }

    private volatile Pool apiPoolCache = Pool.EMPTY;
    private volatile Pool imagePoolCache = Pool.EMPTY;

    public TmdbEndpoint(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    /** API base(形如 https://api.themoviedb.org,不带 /3);池为空回落官方,多项时逐请求轮询。 */
    public String apiHost() {
        List<String> pool = cachedApiPool();
        return pool.isEmpty() ? OFFICIAL_API : next(pool, apiRotation);
    }

    public boolean isMirrorEnabled() {
        return imageHost() != null;
    }

    /** 图片镜像 base(不含 /t/p 路径);null = 不重写(官方直连)。
     * 未单独配置图床时跟随 tmdb_api_host 池(Worker 同域反代 /t/p/),两者均未配置才回落官方;
     * 显式配官方 API 不构成镜像线路(官方图床国内不通,重写到它等于没救)。 */
    String imageHost() {
        List<String> imagePool = cachedImagePool();
        if (!imagePool.isEmpty()) {
            return next(imagePool, imageRotation);
        }
        List<String> apiPool = new ArrayList<>(cachedApiPool());
        apiPool.remove(OFFICIAL_API);
        return apiPool.isEmpty() ? null : next(apiPool, apiRotation);
    }

    /** 启动首读随机打乱顺序(不同实例从不同 worker 起步,分摊免费额度),原值变化重解析重洗;并发首读各洗各的,落缓存者胜,均为合法排列。 */
    private List<String> cachedApiPool() {
        String raw = readSetting(SETTING_NAME);
        Pool cached = apiPoolCache;
        if (!cached.raw().equals(raw)) {
            cached = new Pool(raw, shuffledPool(raw));
            apiPoolCache = cached;
        }
        return cached.hosts();
    }

    private List<String> cachedImagePool() {
        String raw = readSetting(SETTING_NAME_IMAGE);
        Pool cached = imagePoolCache;
        if (!cached.raw().equals(raw)) {
            cached = new Pool(raw, shuffledPool(raw));
            imagePoolCache = cached;
        }
        return cached.hosts();
    }

    private static List<String> shuffledPool(String raw) {
        List<String> pool = parsePool(WORKER_POOL_VALUE.equals(raw) ? String.join(",", BUILTIN_WORKER_POOL) : raw);
        Collections.shuffle(pool);
        return List.copyOf(pool);
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

    /** Bearer 形态给 headers 加 Authorization;v3 形态加 X-TMDB-API-Key 头。
     * 头与 query 双形态并存:官方与透传型 Worker 以 query 的 api_key 认证、忽略多余头;
     * 严格型 Worker(power348045 变体)只认请求头、无视 query——单靠任一形态都会在另一类线路上 401。 */
    public HttpHeaders applyAuth(HttpHeaders headers) {
        if (isBearerToken()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey());
        } else {
            headers.set("X-TMDB-API-Key", apiKey());
        }
        return headers;
    }

    /** 尾斜杠与 /t/p 路径尾巴归一(img.nastool.org/t/p 这类图床前缀写法);空值或非 http(s) 返回 null。 */
    private static String normalize(String value) {
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

    /** 拆镜像池:逗号/分号/空白(含全角)分隔,逐项归一,丢弃空项/非法项/重复项。 */
    private static List<String> parsePool(String value) {
        List<String> pool = new ArrayList<>();
        for (String item : POOL_SEPARATOR.split(value)) {
            if (item.isBlank()) {
                continue;
            }
            String host = normalize(item);
            if (host != null && !pool.contains(host)) {
                pool.add(host);
            }
        }
        return pool;
    }

    /** 单镜像直取,多镜像 round robin(floorMod 防溢出取整;计数只增不减,池大小变化时自然重新分布)。 */
    private static String next(List<String> pool, AtomicInteger rotation) {
        if (pool.size() == 1) {
            return pool.get(0);
        }
        return pool.get(Math.floorMod(rotation.getAndIncrement(), pool.size()));
    }
}
