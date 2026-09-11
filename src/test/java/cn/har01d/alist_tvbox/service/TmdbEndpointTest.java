package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TmdbEndpointTest {
    private static final String OFFICIAL = "https://api.themoviedb.org";
    /** rewriteImage 会把镜像喂给 Utils.isSafeExternalUrl:该守卫对域名做真实 DNS 解析且解析失败即拒绝,
     * 假域名(如 example.workers.dev)在 CI 上 NXDOMAIN 被拦、在有 Fake-IP 应答的本地却放行 —— 测试值必须
     * IP 直写(TEST-NET 文档段 203.0.113.0/24,getByName 字面量解析零 DNS、非私网/环回),任何环境行为一致。 */
    private static final String MIRROR = "https://203.0.113.10";

    @Mock
    private SettingRepository settingRepository;

    /** null = 该键未配置(库中不存在行);统一 lenient 桩,api/image 两键任意组合的用例都免 PotentialStubbingProblem。 */
    private TmdbEndpoint endpoint(String api, String image) {
        lenient().when(settingRepository.findById("tmdb_api_host"))
                .thenReturn(api == null ? Optional.empty() : Optional.of(setting("tmdb_api_host", api)));
        lenient().when(settingRepository.findById("tmdb_image_host"))
                .thenReturn(image == null ? Optional.empty() : Optional.of(setting("tmdb_image_host", image)));
        return new TmdbEndpoint(settingRepository);
    }

    @Test
    void fallsBackToOfficialWhenUnsetOrBlank() {
        assertEquals(OFFICIAL, endpoint(null, null).apiHost());
        assertFalse(endpoint(null, null).isMirrorEnabled());
        assertEquals(OFFICIAL, endpoint(" ", null).apiHost());
    }

    @Test
    void normalizesTrailingSlashAndExplicitOfficial() {
        assertEquals(MIRROR, endpoint(MIRROR + "/", null).apiHost());
        assertFalse(endpoint(OFFICIAL, null).isMirrorEnabled());
    }

    @Test
    void rejectsNonHttpScheme() {
        assertEquals(OFFICIAL, endpoint("ftp://tmdb.example.com", null).apiHost());
    }

    @Test
    void officialModeKeepsImageUrlsUntouched() {
        TmdbEndpoint endpoint = endpoint(null, null);
        String url = "https://media.themoviedb.org/t/p/w300_and_h450_bestv2/abc.jpg";
        assertEquals(url, endpoint.rewriteImage(url));
        assertEquals("https://img9.doubanio.com/x.jpg", endpoint.rewriteImage("https://img9.doubanio.com/x.jpg"));
    }

    @Test
    void rewritesBothOfficialImageHostsInMirrorMode() {
        TmdbEndpoint endpoint = endpoint(MIRROR, null); // 图床未单独配置:跟随 API 线路(Worker 同域反代)
        assertTrue(endpoint.isMirrorEnabled());
        assertEquals(MIRROR + "/t/p/w300_and_h450_bestv2/abc.jpg",
                endpoint.rewriteImage("https://media.themoviedb.org/t/p/w300_and_h450_bestv2/abc.jpg"));
        assertEquals(MIRROR + "/t/p/w500/poster.jpg",
                endpoint.rewriteImage("https://image.tmdb.org/t/p/w500/poster.jpg"));
        // 快照存量直链是 301 前的 media 域名,两者都要救
        assertEquals("https://img9.doubanio.com/x.jpg", endpoint.rewriteImage("https://img9.doubanio.com/x.jpg"));
    }

    @Test
    void mirrorOnLoopbackFallsBackToOriginalUrl() {
        TmdbEndpoint endpoint = endpoint("http://127.0.0.1:8787", null);
        assertTrue(endpoint.isMirrorEnabled());
        String url = "https://media.themoviedb.org/t/p/w500/p.jpg";
        assertEquals(url, endpoint.rewriteImage(url)); // 与 /images 入口的 SSRF 口径一致:内网镜像不重写
    }

    @Test
    void separateImageHostOverridesApiMirror() {
        // NAStool 形态:API 与图床分域名;图床配置带 /t/p 前缀写法也要归一
        // (主机名用 TEST-NET IP:真域名 DNS 一变/一过期 CI 就红,见 MIRROR 注释)
        TmdbEndpoint endpoint = endpoint("https://203.0.113.20", "https://203.0.113.21/t/p/");
        assertEquals("https://203.0.113.20", endpoint.apiHost());
        assertEquals("https://203.0.113.21/t/p/w300_and_h450_bestv2/abc.jpg",
                endpoint.rewriteImage("https://media.themoviedb.org/t/p/w300_and_h450_bestv2/abc.jpg"));
        assertEquals("https://203.0.113.21/t/p/w500/poster.jpg",
                endpoint.rewriteImage("https://image.tmdb.org/t/p/w500/poster.jpg"));
    }

    @Test
    void imageOnlyMirrorKeepsApiOfficial() {
        // 只救图片不动 API:API 未配置仍官方直连,图床单独走镜像
        TmdbEndpoint endpoint = endpoint(null, "https://203.0.113.21");
        assertEquals(OFFICIAL, endpoint.apiHost());
        assertTrue(endpoint.isMirrorEnabled());
        assertEquals("https://203.0.113.21/t/p/w500/p.jpg",
                endpoint.rewriteImage("https://image.tmdb.org/t/p/w500/p.jpg"));
    }

    @Test
    void invalidImageHostFallsBackToApiMirror() {
        TmdbEndpoint endpoint = endpoint(MIRROR, "ftp://img.example.com");
        assertEquals(MIRROR + "/t/p/w500/p.jpg",
                endpoint.rewriteImage("https://image.tmdb.org/t/p/w500/p.jpg"));
    }

    @Test
    void blankImageHostFollowsApiHost() {
        // 显式空串(切回官方时写空)不构成独立线路,回落跟随 API 线路
        TmdbEndpoint endpoint = endpoint(MIRROR, "");
        assertTrue(endpoint.isMirrorEnabled());
        assertEquals(MIRROR + "/t/p/w500/p.jpg",
                endpoint.rewriteImage("https://image.tmdb.org/t/p/w500/p.jpg"));
    }

    // ---------- 镜像池(免费 Worker 每日限额,round robin 分摊) ----------
    // 池成员同为 TEST-NET IP 直写(见 MIRROR 注释:rewriteImage 的安全检查不做 DNS 才能环境无关)。

    private static final String POOL = "https://203.0.113.11,https://203.0.113.12,https://203.0.113.13";
    private static final String IMAGE_POOL = "https://203.0.113.21,https://203.0.113.22,https://203.0.113.23";

    @Test
    void roundRobinsApiAcrossWorkerPool() {
        TmdbEndpoint endpoint = endpoint(POOL, null);
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            counts.merge(endpoint.apiHost(), 1, Integer::sum);
        }
        assertEquals(3, counts.size());
        counts.values().forEach(count -> assertEquals(2, count)); // 均匀轮询:每个 worker 各 2 次
    }

    @Test
    void shuffledOncePerStartupThenSequenceStaysStable() {
        // 启动首读洗牌后序列固定:第一轮的顺序在后续轮次原样重复(防退化成每请求重洗=随机直取)
        TmdbEndpoint endpoint = endpoint(POOL, null);
        String[] firstCycle = new String[3];
        for (int i = 0; i < 3; i++) {
            firstCycle[i] = endpoint.apiHost();
        }
        assertEquals(3, java.util.Arrays.stream(firstCycle).distinct().count()); // 是排列,无遗漏无重复
        for (int i = 0; i < 3; i++) {
            assertEquals(firstCycle[i], endpoint.apiHost()); // 第二轮与第一轮同序
        }
    }

    @Test
    void poolRefreshesImmediatelyWhenSettingChanges() {
        // 池按原值缓存,但改设置必须立即生效:单镜像 → 3 worker 池,后续请求即按新池轮询
        TmdbEndpoint endpoint = endpoint(MIRROR, null);
        assertEquals(MIRROR, endpoint.apiHost());
        when(settingRepository.findById("tmdb_api_host"))
                .thenReturn(Optional.of(setting("tmdb_api_host", POOL)));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            seen.add(endpoint.apiHost());
        }
        assertEquals(3, seen.size());
    }

    @Test
    void workerPoolSentinelResolvesToBackendBuiltinPool() {
        // 前端预设只落哨兵值 worker-pool(地址不进前端 bundle),12 线路由后端内置并均匀轮询;图床未单独配置跟随同池
        TmdbEndpoint endpoint = endpoint(TmdbEndpoint.WORKER_POOL_VALUE, null);
        int size = TmdbEndpoint.BUILTIN_WORKER_POOL.size();
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < size * 2; i++) {
            counts.merge(endpoint.apiHost(), 1, Integer::sum);
        }
        assertEquals(size, counts.size());
        counts.keySet().forEach(host -> assertTrue(TmdbEndpoint.BUILTIN_WORKER_POOL.contains(host)));
        counts.values().forEach(count -> assertEquals(2, count)); // 均匀轮询:每线路各 2 次
        assertTrue(endpoint.isMirrorEnabled());
        // 图床跟随同池:包私有 imageHost() 直取断言。不走 rewriteImage —— 它对镜像做真实 DNS
        // 安全检查(守卫防 rebinding 的 fail-closed 语义不动),内置 Worker 真域名在无网/解析抖动
        // 环境会让测试变红;重写的路径拼接语义由 IP 池用例(imageRewriteRotatesAcrossPool)覆盖。
        assertTrue(TmdbEndpoint.BUILTIN_WORKER_POOL.contains(endpoint.imageHost()));
    }

    @Test
    void workerPoolSentinelOnImageKeyEnablesImageMirrorOnly() {
        // 图床键同样可用哨兵(Worker 同域反代 /t/p/),API 未配置仍官方直连;DNS 无关断言见上注
        TmdbEndpoint endpoint = endpoint(null, TmdbEndpoint.WORKER_POOL_VALUE);
        assertEquals(OFFICIAL, endpoint.apiHost());
        assertTrue(endpoint.isMirrorEnabled());
        assertTrue(TmdbEndpoint.BUILTIN_WORKER_POOL.contains(endpoint.imageHost()));
    }

    @Test
    void poolDropsInvalidAndDuplicateItems() {
        // 全角逗号分隔 + 非法项 + 重复项:只剩一个有效镜像时退化为单项直取
        TmdbEndpoint endpoint = endpoint("ftp://bad.example，" + MIRROR + "，" + MIRROR + " ,", null);
        assertEquals(MIRROR, endpoint.apiHost());
    }

    @Test
    void imageRewriteRotatesAcrossPool() {
        TmdbEndpoint endpoint = endpoint(POOL, null); // 图床未单独配置:跟随 API 池轮询
        String url = "https://image.tmdb.org/t/p/w500/p.jpg";
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            seen.add(endpoint.rewriteImage(url));
        }
        assertEquals(3, seen.size()); // 三次重写落在三个不同 worker 上
        for (String rewritten : seen) {
            assertTrue(rewritten.endsWith("/t/p/w500/p.jpg"));
            assertTrue(rewritten.startsWith("https://203.0.113."));
        }
    }

    @Test
    void imagePoolConfiguredSeparatelyRotatesIndependently() {
        // API 单镜像、图床独立池:图床按自己的计数轮询,不与 API 混
        TmdbEndpoint endpoint = endpoint(MIRROR, IMAGE_POOL);
        assertEquals(MIRROR, endpoint.apiHost());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            seen.add(endpoint.rewriteImage("https://image.tmdb.org/t/p/w500/p.jpg"));
        }
        assertEquals(3, seen.size());
    }

    private static final String V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String V4_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig";

    private TmdbEndpoint endpointWithKey(String key) {
        lenient().when(settingRepository.findById("tmdb_api_key"))
                .thenReturn(key == null ? Optional.empty() : Optional.of(setting("tmdb_api_key", key)));
        return new TmdbEndpoint(settingRepository);
    }

    @Test
    void blankKeyFallsBackToBuiltinPublicKey() {
        assertEquals(cn.har01d.alist_tvbox.util.Constants.TMDB_API_KEY, endpointWithKey(null).apiKey());
        assertEquals(cn.har01d.alist_tvbox.util.Constants.TMDB_API_KEY, endpointWithKey(" ").apiKey());
    }

    @Test
    void v3KeyGoesIntoQueryAndHeader() {
        TmdbEndpoint endpoint = endpointWithKey(V3_KEY);
        assertFalse(endpoint.isBearerToken());
        assertEquals("https://tmdb.example.workers.dev/3/search/tv?query=x&api_key=" + V3_KEY,
                endpoint.appendApiKey("https://tmdb.example.workers.dev/3/search/tv?query=x"));
        assertEquals("https://tmdb.example.workers.dev/3/tv/1?api_key=" + V3_KEY,
                endpoint.appendApiKey("https://tmdb.example.workers.dev/3/tv/1"));
        org.springframework.http.HttpHeaders headers =
                endpoint.applyAuth(new org.springframework.http.HttpHeaders());
        // query 之外双带 X-TMDB-API-Key 头:严格型 Worker(power348045 变体)只认头,官方/透传型忽略多余头
        assertNull(headers.get(org.springframework.http.HttpHeaders.AUTHORIZATION));
        assertEquals(V3_KEY, headers.getFirst("X-TMDB-API-Key"));
    }

    @Test
    void readAccessTokenGoesIntoBearerHeaderAndUrlStaysClean() {
        TmdbEndpoint endpoint = endpointWithKey(V4_TOKEN);
        assertTrue(endpoint.isBearerToken());
        String url = "https://tmdb.example.workers.dev/3/search/tv?query=x";
        assertEquals(url, endpoint.appendApiKey(url)); // Bearer 形态凭证不落 URL/代理访问日志
        assertEquals("Bearer " + V4_TOKEN, endpoint.applyAuth(new org.springframework.http.HttpHeaders())
                .getFirst(org.springframework.http.HttpHeaders.AUTHORIZATION));
    }

    private static Setting setting(String name, String value) {
        Setting setting = new Setting();
        setting.setName(name);
        setting.setValue(value);
        return setting;
    }
}
