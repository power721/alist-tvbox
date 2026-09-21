package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.SiteCredentialCheckRequest;
import cn.har01d.alist_tvbox.dto.SiteCredentialCheckResult;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观影搜索源:PoW 求解/挑战判定/搜索解析(内嵌 JSON+suggest 回退)/提取码折参/多镜像与登录/
 * downlist 磁力种子产出。
 */
class GuanYingSearchServiceTest {

    private static final String P_N = "fedcba9876543210fedcba9876543210abcd";
    private static final String P_X = "1234567890abcdef";

    private static SettingRepository settings(String... pairs) {
        SettingRepository repository = Mockito.mock(SettingRepository.class);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Setting setting = new Setting();
            setting.setName(pairs[i]);
            setting.setValue(pairs[i + 1]);
            Mockito.when(repository.findById(pairs[i])).thenReturn(Optional.of(setting));
        }
        return repository;
    }

    /** 可写 Setting 仓库桩:findById/save 走同一张内存表,模拟跨重启的持久化语义。 */
    private static SettingRepository writableSettings(Map<String, String> store) {
        SettingRepository repository = Mockito.mock(SettingRepository.class);
        Mockito.when(repository.findById(Mockito.any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if (name == null || !store.containsKey(name)) {
                return Optional.empty();
            }
            Setting setting = new Setting();
            setting.setName(name);
            setting.setValue(store.get(name));
            return Optional.of(setting);
        });
        Mockito.when(repository.save(Mockito.any())).thenAnswer(invocation -> {
            Setting setting = invocation.getArgument(0);
            if (setting != null) {
                store.put(setting.getName(), setting.getValue());
            }
            return setting;
        });
        return repository;
    }

    @Test
    void solvePowMatchesPython() {
        // 与 Python pow(x, 1<<t, N) 对照
        assertEquals("1234567890abcdef", GuanYingSearchService.solvePow(P_N, P_X, 0));
        assertEquals("14b66dc328828bca6475f09a2f2a521", GuanYingSearchService.solvePow(P_N, P_X, 1));
        assertEquals("27183b6395d97d7e6aa3c3e989ac8cbfbe4c", GuanYingSearchService.solvePow(P_N, P_X, 137));
        assertEquals("77966f955867f8d29da249f40f2d71dde64", GuanYingSearchService.solvePow(P_N, P_X, 4096));
    }

    @Test
    void detectChallengeVariants() {
        GuanYingSearchService service = new GuanYingSearchService(null, new ObjectMapper());
        assertTrue(service.detectChallenge("{\"code\":419,\"msg\":\"reload\"}"));
        assertTrue(service.detectChallenge("{\"refresh\":1,\"msg\":\"请完成浏览器验证\"}"));
        assertTrue(service.detectChallenge("<html>浏览器验证已过期</html>"));
        assertTrue(service.detectChallenge("some pow.worker stuff"));
        assertTrue(service.detectChallenge("浏览器安全验证"));
        // _obj. 在场即正常数据页(即便同页有 filejin 静态资源域名)
        assertFalse(service.detectChallenge("_obj.footer={t:'1.0'}; static.filejin.ru/x.js"));
        assertFalse(service.detectChallenge("<html><script>var data={a:1};</script></html>"));
        assertFalse(service.detectChallenge(""));
    }

    @Test
    void appendPassword() {
        assertEquals("https://pan.quark.cn/s/a?password=ab12",
                GuanYingSearchService.appendPassword("https://pan.quark.cn/s/a", "ab12"));
        assertEquals("https://pan.baidu.com/s/x?pwd=zz",
                GuanYingSearchService.appendPassword("https://pan.baidu.com/s/x?pwd=zz", "ab12"));
        assertEquals("https://pan.quark.cn/s/a",
                GuanYingSearchService.appendPassword("https://pan.quark.cn/s/a", ""));
    }

    @Test
    void normalizeHosts() {
        assertEquals(6, GuanYingSearchService.normalizeHosts("").size(), "内置镜像表同步官方发布页清单");
        assertEquals("https://" + java.net.IDN.toASCII("观影.example"),
                GuanYingSearchService.normalizeHosts("观影.example").get(0));
        List<String> two = GuanYingSearchService.normalizeHosts("a.example, https://b.example/path");
        assertEquals(List.of("https://a.example", "https://b.example"), two);
    }

    @Test
    void parseSearchFromEmbeddedJson() {
        GuanYingSearchService service = new GuanYingSearchService(null, new ObjectMapper());
        List<GuanYingSearchService.Item> items = service.parseSearch(
                "<html><script>_obj.header={};_obj.search={\"l\":{\"i\":[\"11\",\"22\"],"
                        + "\"title\":[\"难哄\",\"别的剧\"],\"d\":[\"tv\",\"mv\"],\"year\":[\"2025\",\"\"],"
                        + "\"info\":[\"更新至12集\",\"\"]}};_obj.footer={};</script></html>");
        assertEquals(2, items.size());
        assertEquals("tv", items.get(0).dtype());
        assertEquals("11", items.get(0).rid());
        assertEquals("难哄", items.get(0).title());
        assertEquals("更新至12集", items.get(0).remarks());
        assertEquals("mv", items.get(1).dtype());
        // 无内嵌 JSON 时返回空(suggest 回退的上游条件)
        assertTrue(service.parseSearch("<html>nothing</html>").isEmpty());
    }

    @Test
    void parseSearchSuggestFallback() throws Exception {
        GuanYingSearchService service = new GuanYingSearchService(null, new ObjectMapper());
        List<GuanYingSearchService.Item> items = service.parseSearchSuggest(
                new ObjectMapper().readTree("[{\"id\":\"33\",\"title\":\"难哄\",\"dir\":\"tv\",\"year\":\"2025\"},"
                        + "{\"id\":\"44\",\"title\":\"坏条目\",\"dir\":\"xx\"}]"));
        assertEquals(1, items.size());
        assertEquals("33", items.get(0).rid());
        assertEquals("2025", items.get(0).remarks());
    }

    @Test
    void noCredentialsMeansDisabled() {
        GuanYingSearchService service = new GuanYingSearchService(settings(), new ObjectMapper());
        assertTrue(service.search("难哄").isEmpty());
    }

    @Test
    void searchRecoversFromPowChallengeAndExtractsLinks() {
        AtomicInteger searchCalls = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(
                settings("guanying_cookie", "auth=token", "guanying_host", "https://gy.example"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (request.url().encodedPath().equals("/search")) {
                    if (searchCalls.incrementAndGet() == 1) {
                        return new Resp(200, List.of(), "{\"code\":419,\"msg\":\"browser verify\"}");
                    }
                    return new Resp(200, List.of(), "<html><script>_obj.search={\"l\":{\"i\":[\"11\"],"
                            + "\"title\":[\"难哄\"],\"d\":[\"tv\"],\"year\":[\"2025\"],\"info\":[\"\"]}};_obj.x=1;</script></html>");
                }
                if (url.equals("https://gy.example/")) {
                    // ensurePow 先看首页:首页也是挑战页才会走 /res/pow
                    return new Resp(200, List.of("PHPSESSID=s1; Path=/"), "浏览器安全验证 pow.worker");
                }
                if (url.equals("https://gy.example/res/pow") && request.method().equals("GET")) {
                    return new Resp(200, List.of("browser_pow=p1; Path=/"), "{\"N\":\"" + P_N + "\",\"x\":\"" + P_X + "\",\"t\":\"137\"}");
                }
                if (url.equals("https://gy.example/res/pow") && request.method().equals("POST")) {
                    assertTrue(request.header("Cookie").contains("browser_pow=p1"), "PoW 提交带挑战 Cookie");
                    return new Resp(200, List.of("browser_verified=1; Path=/"), "{\"success\":true}");
                }
                if (url.equals("https://gy.example/res/downurl/tv/11")) {
                    assertTrue(request.header("Cookie").contains("browser_verified=1"), "盘链请求带已验证 Cookie");
                    return new Resp(200, List.of(), "{\"panlist\":{\"url\":[\"https://pan.quark.cn/s/gy1\","
                            + "\"https://pan.baidu.com/s/1GyBd\"],\"name\":[\"夸克4K\",\"百度\"],\"p\":[\"\",\"ab12\"]},"
                            + "\"downlist\":{\"list\":{\"m\":[\"0123456789abcdef\"],\"t\":[\"磁力\"]}}}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        List<Message> messages = service.search("难哄");
        assertEquals(3, messages.size());
        assertEquals("https://pan.quark.cn/s/gy1", messages.get(0).getLink());
        assertEquals("5", messages.get(0).getType());
        assertEquals("观影", messages.get(0).getChannel());
        assertEquals("难哄", messages.get(0).getName());
        assertEquals("https://pan.baidu.com/s/1GyBd?password=ab12", messages.get(1).getLink());
        assertEquals("10", messages.get(1).getType());
        // downlist 磁力种子:btih 哈希折 magnet,种子名折 dn= 与 content
        assertEquals("magnet:?xt=urn:btih:0123456789abcdef&dn=%E7%A3%81%E5%8A%9B", messages.get(2).getLink());
        assertEquals("magnet", messages.get(2).getType());
        assertEquals("观影", messages.get(2).getChannel());
        assertEquals("磁力", messages.get(2).getContent());
        assertEquals(2, searchCalls.get(), "挑战后重试搜索一次");
    }

    @Test
    void magnetsFromDetailParsesHashesAndNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode detail = mapper.readTree("""
                {"downlist":{"list":{"m":["ABCDEF1234","Def567890AbcD","Fedcba098765","short"],
                                     "t":["🎬 难哄 第04集   1080P","","ED2K 名","x"]}}}
                """);
        List<Message> messages = GuanYingSearchService.magnetsFromDetail(detail,
                new GuanYingSearchService.Item("tv", "11", "难哄", "2025"));
        // 短哈希(<8)跳过;哈希小写化;种子名剥开头杂符+压空白后折 dn
        assertEquals(3, messages.size());
        assertEquals("magnet:?xt=urn:btih:abcdef1234&dn=%E9%9A%BE%E5%93%84+%E7%AC%AC04%E9%9B%86+1080P",
                messages.get(0).getLink());
        assertEquals("难哄 第04集 1080P", messages.get(0).getContent());
        assertEquals("难哄", messages.get(0).getName());
        // 空种子名:不带 dn,content 回落"磁力"
        assertEquals("magnet:?xt=urn:btih:def567890abcd", messages.get(1).getLink());
        assertEquals("磁力", messages.get(1).getContent());
        assertEquals("magnet:?xt=urn:btih:fedcba098765&dn=ED2K+%E5%90%8D", messages.get(2).getLink());
        // 无 downlist 的详情:空列表
        assertTrue(GuanYingSearchService.magnetsFromDetail(mapper.readTree("{\"panlist\":{}}"),
                new GuanYingSearchService.Item("tv", "11", "难哄", "")).isEmpty());
    }

    @Test
    void searchFallsBackToSuggest() {
        GuanYingSearchService service = new GuanYingSearchService(
                settings("guanying_cookie", "auth=token", "guanying_host", "https://gy.example"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.startsWith("https://gy.example/search")) {
                    return new Resp(200, List.of(), "<html>no _obj here</html>");
                }
                if (url.startsWith("https://gy.example/res/search_suggest")) {
                    return new Resp(200, List.of(), "[{\"id\":\"33\",\"title\":\"难哄\",\"dir\":\"tv\",\"year\":\"2025\"}]");
                }
                if (url.equals("https://gy.example/res/downurl/tv/33")) {
                    return new Resp(200, List.of(), "{\"panlist\":{\"url\":[\"https://www.123pan.com/s/gy2\"],"
                            + "\"name\":[\"123盘\"],\"p\":[\"1a2b\"]}}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        List<Message> messages = service.search("难哄");
        assertEquals(1, messages.size());
        assertEquals("https://www.123pan.com/s/gy2?password=1a2b", messages.get(0).getLink());
        assertEquals("3", messages.get(0).getType());
    }

    @Test
    void loginFlowSucceedsAndSearches() {
        AtomicInteger logins = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(
                settings("guanying_username", "u1", "guanying_password", "pw", "guanying_host", "https://gy.example"),
                new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.equals("https://gy.example/user/login") && request.method().equals("POST")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of("auth=user1; Path=/"), "{\"code\":200}");
                }
                if (url.equals("https://gy.example/user/login/")) {
                    return new Resp(200, List.of("PHPSESSID=s1; Path=/"), "login page");
                }
                if (url.equals("https://gy.example/")) {
                    return new Resp(200, List.of(), "_obj.ok");
                }
                if (url.startsWith("https://gy.example/search")) {
                    return new Resp(200, List.of(), "<html><script>_obj.search={\"l\":{\"i\":[\"11\"],"
                            + "\"title\":[\"难哄\"],\"d\":[\"tv\"],\"year\":[\"2025\"],\"info\":[\"\"]}};_obj.x=1;</script></html>");
                }
                if (url.equals("https://gy.example/res/downurl/tv/11")) {
                    return new Resp(200, List.of(), "{\"panlist\":{\"url\":[],\"name\":[],\"p\":[]}}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        // 账号密码登录成功后正常搜索
        assertEquals(0, service.search("难哄").size());
        assertEquals(1, logins.get());
    }

    @Test
    void cookieDeletionHonored() {
        // nologin 响应 + 登录不可用(Cookie-only 配置失效)时搜索返回空
        GuanYingSearchService service = new GuanYingSearchService(
                settings("guanying_cookie", "auth=token", "guanying_host", "https://gy.example"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                if (request.url().toString().startsWith("https://gy.example/search")) {
                    return new Resp(200, List.of("auth=deleted; Max-Age=0"), "<html>未登录</html>");
                }
                return new Resp(200, List.of(), "");
            }
        };
        assertTrue(service.search("难哄").isEmpty());
    }

    private static final String EMPTY_SEARCH_HTML =
            "<html><script>_obj.search={\"l\":{\"i\":[],\"title\":[],\"d\":[],\"year\":[],\"info\":[]}};_obj.x=1;</script></html>";

    @Test
    void loginCookiePersistedAndReusedAfterRestart() {
        Map<String, String> store = new ConcurrentHashMap<>();
        store.put("guanying_host", "https://gy.example");
        store.put("guanying_username", "u1");
        store.put("guanying_password", "pw");
        AtomicInteger logins = new AtomicInteger();
        GuanYingSearchService first = new GuanYingSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.equals("https://gy.example/user/login") && request.method().equals("POST")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of("auth=user1; Path=/", "uid=9; Path=/"), "{\"code\":200}");
                }
                if (url.equals("https://gy.example/user/login/")) {
                    return new Resp(200, List.of("PHPSESSID=s1; Path=/"), "login page");
                }
                if (url.equals("https://gy.example/")) {
                    return new Resp(200, List.of(), "_obj.ok");
                }
                if (url.startsWith("https://gy.example/search")) {
                    return new Resp(200, List.of(), EMPTY_SEARCH_HTML);
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertEquals(0, first.search("难哄").size());
        assertEquals(1, logins.get());
        // 登录态快照落库:非 PoW 短时效 Cookie 全进(登录页会话 PHPSESSID 同属登录态)
        String persisted = store.get(GuanYingSearchService.SESSION_SETTING);
        assertTrue(persisted.contains("auth=user1"));
        assertTrue(persisted.contains("uid=9"));
        assertTrue(persisted.contains("PHPSESSID=s1"));

        // 新实例=进程重启(内存 Cookie 清零):播种落库会话,零登录
        GuanYingSearchService restarted = new GuanYingSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.contains("/user/login")) {
                    throw new AssertionError("持久会话有效期内不得重新登录");
                }
                if (url.startsWith("https://gy.example/search")) {
                    assertTrue(request.header("Cookie").contains("auth=user1"), "重启后播种落库会话");
                    return new Resp(200, List.of(), EMPTY_SEARCH_HTML);
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertEquals(0, restarted.search("难哄").size());
        assertEquals(1, logins.get(), "重启后复用持久化 Cookie,不再撞登录接口");
    }

    @Test
    void stalePersistedSessionReloginsAndOverwritesStore() {
        Map<String, String> store = new ConcurrentHashMap<>();
        store.put("guanying_host", "https://gy.example");
        store.put("guanying_username", "u1");
        store.put("guanying_password", "pw");
        store.put(GuanYingSearchService.SESSION_SETTING, "auth=stale; uid=9");
        AtomicInteger logins = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                String cookie = request.header("Cookie") == null ? "" : request.header("Cookie");
                if (url.equals("https://gy.example/user/login") && request.method().equals("POST")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of("auth=fresh; Path=/"), "{\"code\":200}");
                }
                if (url.equals("https://gy.example/user/login/")) {
                    return new Resp(200, List.of(), "login page");
                }
                if (url.equals("https://gy.example/")) {
                    return new Resp(200, List.of(), "_obj.ok");
                }
                if (url.startsWith("https://gy.example/search")) {
                    if (!cookie.contains("auth=fresh")) {
                        return new Resp(200, List.of(), "nologin");
                    }
                    return new Resp(200, List.of(), EMPTY_SEARCH_HTML);
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertEquals(0, service.search("难哄").size());
        assertEquals(1, logins.get(), "落库会话被站点判 nologin 后必须重登一次");
        String persisted = store.get(GuanYingSearchService.SESSION_SETTING);
        assertTrue(persisted.contains("auth=fresh"), "重登后覆盖旧会话");
        assertTrue(persisted.contains("uid=9"), "播种的其余登录态 Cookie 不因重登丢失");
    }

    @Test
    void loginFailureEvictsPersistedSession() {
        Map<String, String> store = new ConcurrentHashMap<>();
        store.put("guanying_host", "https://gy.example");
        store.put("guanying_username", "u1");
        store.put("guanying_password", "wrong");
        store.put(GuanYingSearchService.SESSION_SETTING, "auth=stale; uid=9");
        GuanYingSearchService service = new GuanYingSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.equals("https://gy.example/user/login") && request.method().equals("POST")) {
                    return new Resp(200, List.of(), "{\"code\":400,\"msg\":\"账号密码被拒绝\"}");
                }
                if (url.equals("https://gy.example/user/login/")) {
                    return new Resp(200, List.of(), "login page");
                }
                if (url.equals("https://gy.example/")) {
                    return new Resp(200, List.of(), "_obj.ok");
                }
                if (url.startsWith("https://gy.example/search")) {
                    return new Resp(200, List.of(), "nologin");
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertTrue(service.search("难哄").isEmpty());
        // 过期会话被判 nologin → 重登失败(如密码已改)→ 须清除,防下次重启回灌死 Cookie
        assertTrue(store.get(GuanYingSearchService.SESSION_SETTING).isEmpty(),
                "重登失败须清除过期会话,防重启回灌死 Cookie");
    }

    @Test
    void checkCredentialValidAfterPowChallenge() {
        AtomicInteger homeCalls = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(settings(), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                assertEquals("https://gy.example/", request.url().toString());
                // 第 1 次:校验遇挑战页;第 2 次:PoW 探测(ensurePow)已过;第 3 次:校验重试
                if (homeCalls.incrementAndGet() == 1) {
                    return new Resp(200, List.of(), "<script src='//static.filejin.ru/pow.worker.js'></script>");
                }
                return new Resp(200, List.of(), "_obj.site={};");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(req("uid=42; token=abc", "https://gy.example"));
        assertEquals("guanying", result.site());
        assertTrue(result.valid(), result.message());
        assertEquals(3, homeCalls.get(), "挑战→PoW 探测→重试");
    }

    @Test
    void checkCredentialNologinMeansInvalid() {
        GuanYingSearchService service = new GuanYingSearchService(settings(), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                assertTrue(request.header("Cookie").contains("uid=42"), "校验须带用户提供的 Cookie");
                return new Resp(200, List.of(), "<title>未登录，访问受限</title> nologin");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(req("uid=42", "https://gy.example"));
        assertFalse(result.valid());
        assertTrue(result.message().contains("未登录"), result.message());
    }

    @Test
    void checkCredentialAccountLogsInAndSavesSession() {
        Map<String, String> store = new ConcurrentHashMap<>();
        GuanYingSearchService service = new GuanYingSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.equals("https://gy.example/") ) {
                    return new Resp(200, List.of("browser_verified=ok; Path=/"), "_obj.site={};");
                }
                if (url.equals("https://gy.example/user/login/")) {
                    return new Resp(200, List.of(), "login page");
                }
                if (url.equals("https://gy.example/user/login")) {
                    return new Resp(200, List.of("auth=fresh; Path=/"), "{\"code\":200}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(
                new SiteCredentialCheckRequest("guanying", "", "https://gy.example", "u1", "pw"));
        assertTrue(result.valid(), result.message());
        assertTrue(result.message().contains("账号密码可用"), result.message());
        assertTrue(store.get(GuanYingSearchService.SESSION_SETTING).contains("auth=fresh"),
                "登录成功须保存会话(剔 PoW 短时效项)");
    }

    @Test
    void checkCredentialAggregatesDeadMirrors() {
        AtomicInteger attempts = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(settings(), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) throws IOException {
                if (request.url().encodedPath().equals("/check.js")) {
                    return new Resp(200, List.of(), ""); // 发布页发现不到新镜像
                }
                attempts.incrementAndGet();
                throw new IOException("Failed to connect to dead.mirror/0.0.0.0:443");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(req("uid=42", ""));
        assertFalse(result.valid());
        assertTrue(result.message().contains("6 个站点地址探测失败"), result.message());
        assertTrue(result.message().contains("6×"), "同因失败分组计数:" + result.message());
        assertEquals(6, attempts.get(), "内置 6 镜像逐个尝试");
    }

    @Test
    void checkCredentialGroupsFailureCauses() {
        AtomicInteger attempts = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(settings(), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) throws IOException {
                if (request.url().encodedPath().equals("/check.js")) {
                    return new Resp(200, List.of(), "");
                }
                // 前 2 个镜像 DNS 轮换下线(0.0.0.0),其余连接失败:须分组计数而非只回显最后一个
                throw attempts.incrementAndGet() <= 2
                        ? new UnknownHostException("域名被解析到 0.0.0.0(镜像轮换下线或被 DNS 拦截)")
                        : new IOException("failed to connect to mirror/1.2.3.4:443");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(req("uid=42", ""));
        assertFalse(result.valid());
        assertTrue(result.message().contains("2×域名被解析到 0.0.0.0"), result.message());
        assertTrue(result.message().contains("4×failed to connect"), result.message());
    }

    @Test
    void checkCredentialRecoversViaPublishPageDiscovery() {
        AtomicInteger deadAttempts = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(settings(), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (request.url().encodedPath().equals("/check.js")) {
                    // 官方发布页清单:已知 6 镜像全退役,只剩新镜像 gy3
                    return new Resp(200, List.of(), "const urlData = [\n  { url: 'https://gy3.example' },\n];");
                }
                if (url.startsWith("https://gy3.example/")) {
                    return new Resp(200, List.of(), "_obj.site={};");
                }
                deadAttempts.incrementAndGet();
                throw new IOException("Failed to connect to dead.mirror/0.0.0.0:443");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(req("uid=42", ""));
        assertTrue(result.valid(), result.message());
        assertEquals(6, deadAttempts.get(), "已知镜像全失败后才走发布页发现");
    }

    @Test
    void searchRecoversViaPublishPageDiscovery() {
        AtomicInteger deadAttempts = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(
                settings("guanying_cookie", "auth=token"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (request.url().encodedPath().equals("/check.js")) {
                    return new Resp(200, List.of(), "const urlData = [ { url: 'https://gy3.example' } ];");
                }
                if (url.startsWith("https://gy3.example/res/downurl/")) {
                    return new Resp(200, List.of(), "{\"panlist\":{\"url\":[\"https://pan.quark.cn/s/gy1\"],"
                            + "\"name\":[\"夸克4K\"],\"p\":[\"\"]},\"downlist\":{\"list\":{\"m\":[],\"t\":[]}}}");
                }
                if (url.startsWith("https://gy3.example/")) {
                    return new Resp(200, List.of(), "<html><script>_obj.search={\"l\":{\"i\":[\"11\"],"
                            + "\"title\":[\"难哄\"],\"d\":[\"tv\"],\"year\":[\"2025\"],\"info\":[\"\"]}};_obj.x=1;</script></html>");
                }
                deadAttempts.incrementAndGet();
                throw new IOException("Failed to connect to dead.mirror/0.0.0.0:443");
            }
        };
        List<Message> messages = service.search("难哄");
        assertEquals(1, messages.size());
        assertEquals("https://pan.quark.cn/s/gy1", messages.get(0).getLink());
        assertTrue(deadAttempts.get() >= 6, "已知镜像失败后自发现新镜像续命:" + deadAttempts.get());
    }

    @Test
    void loginRecoversViaPublishPageDiscovery() {
        Map<String, String> store = new ConcurrentHashMap<>();
        GuanYingSearchService service = new GuanYingSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (request.url().encodedPath().equals("/check.js")) {
                    return new Resp(200, List.of(), "const urlData = [ { url: 'https://gy3.example' } ];");
                }
                if (url.equals("https://gy3.example/user/login/")) {
                    return new Resp(200, List.of(), "login page");
                }
                if (url.equals("https://gy3.example/user/login")) {
                    return new Resp(200, List.of("auth=fresh; Path=/"), "{\"code\":200}");
                }
                if (url.startsWith("https://gy3.example/")) {
                    return new Resp(200, List.of(), "_obj.site={};");
                }
                throw new IOException("Failed to connect to dead.mirror/0.0.0.0:443");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(
                new SiteCredentialCheckRequest("guanying", "", "", "u1", "pw"));
        assertTrue(result.valid(), result.message());
        assertTrue(store.get(GuanYingSearchService.SESSION_SETTING).contains("auth=fresh"),
                "发布页发现的镜像登录成功须保存会话");
    }

    @Test
    void filterAliveRecordsDropsDeadAddresses() throws Exception {
        assertEquals(List.of(InetAddress.getByName("64.118.159.152")),
                GuanYingSearchService.filterAliveRecords(
                        List.of(InetAddress.getByName("0.0.0.0"), InetAddress.getByName("64.118.159.152"))));
        assertTrue(GuanYingSearchService.filterAliveRecords(
                List.of(InetAddress.getByName("0.0.0.0"), InetAddress.getByName("::"))).isEmpty(),
                "全 0 答案 = 镜像轮换下线");
    }

    @Test
    void loginFallsBackWhenFirstMirrorDead() {
        Map<String, String> store = new ConcurrentHashMap<>();
        AtomicInteger deadMirrorAttempts = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.startsWith("https://gy1.example")) {
                    deadMirrorAttempts.incrementAndGet();
                    throw new IOException("域名被解析到 0.0.0.0(镜像轮换下线或被 DNS 拦截)");
                }
                if (url.equals("https://gy2.example/user/login/")) {
                    return new Resp(200, List.of(), "login page");
                }
                if (url.equals("https://gy2.example/user/login")) {
                    return new Resp(200, List.of("auth=fresh; Path=/"), "{\"code\":200}");
                }
                return new Resp(200, List.of(), "_obj.site={};");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(
                new SiteCredentialCheckRequest("guanying", "", "https://gy1.example,https://gy2.example", "u1", "pw"));
        assertTrue(result.valid(), result.message());
        assertTrue(deadMirrorAttempts.get() >= 1, "首镜像死时须换下一镜像而非直接判登录失败");
        assertTrue(store.get(GuanYingSearchService.SESSION_SETTING).contains("auth=fresh"),
                "可用镜像登录成功须保存会话");
    }

    @Test
    void checkCredentialWithoutAnythingReportsMissing() {
        GuanYingSearchService service = new GuanYingSearchService(settings(), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                throw new AssertionError("未填凭证不得发任何请求");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(req("", ""));
        assertFalse(result.valid());
        assertEquals("未填写 Cookie 或账号密码", result.message());
    }

    @Test
    void checkCredentialBreaksWhenPowVerificationRejected() {
        AtomicInteger solveCalls = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(settings(), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                if (path.equals("/check.js")) {
                    return new Resp(200, List.of(), "");
                }
                if (path.equals("/res/pow") && request.method().equals("GET")) {
                    return new Resp(200, List.of("browser_pow=p1; Path=/"),
                            "{\"N\":\"" + P_N + "\",\"x\":\"" + P_X + "\",\"t\":\"137\"}");
                }
                if (path.equals("/res/pow") && request.method().equals("POST")) {
                    solveCalls.incrementAndGet();
                    return new Resp(200, List.of("browser_verified=1; Path=/"), "{\"success\":true}");
                }
                // GET / 恒为挑战页:PoW 明明被收账(success:true)仍被要求验证 = 反爬不认账
                return new Resp(200, List.of(), "<title>浏览器安全验证</title> pow.worker filejin");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(req("uid=42", ""));
        assertFalse(result.valid());
        assertTrue(result.message().contains("PoW 通过仍被要求验证"), result.message());
        assertEquals(1, solveCalls.get(), "不认账态只解一次 PoW,熔断不再打剩余 5 镜像");
        assertTrue(result.message().contains("1 个站点地址探测失败"), result.message());
    }

    @Test
    void searchStopsAcrossMirrorsWhenPowRejected() {
        AtomicInteger searchCalls = new AtomicInteger();
        AtomicInteger solveCalls = new AtomicInteger();
        GuanYingSearchService service = new GuanYingSearchService(
                settings("guanying_cookie", "auth=token"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                if (path.equals("/check.js")) {
                    return new Resp(200, List.of(), "");
                }
                if (path.equals("/res/pow") && request.method().equals("GET")) {
                    return new Resp(200, List.of("browser_pow=p1; Path=/"),
                            "{\"N\":\"" + P_N + "\",\"x\":\"" + P_X + "\",\"t\":\"137\"}");
                }
                if (path.equals("/res/pow") && request.method().equals("POST")) {
                    solveCalls.incrementAndGet();
                    return new Resp(200, List.of("browser_verified=1; Path=/"), "{\"success\":true}");
                }
                if (path.equals("/search")) {
                    searchCalls.incrementAndGet();
                }
                return new Resp(200, List.of(), "<title>浏览器安全验证</title> pow.worker filejin");
            }
        };
        assertTrue(service.search("难哄").isEmpty());
        assertEquals(2, searchCalls.get(), "首个镜像初始+PoW 后重试各一次,风控熔断不再打剩余镜像");
        assertEquals(1, solveCalls.get(), "整轮搜索只解一次 PoW");
    }

    @Test
    void checkCredentialStaleBrowserVerifiedMustNotShadowFresh() {
        GuanYingSearchService service = new GuanYingSearchService(settings(), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                if (path.equals("/res/pow") && request.method().equals("GET")) {
                    return new Resp(200, List.of("browser_pow=p1; Path=/"),
                            "{\"N\":\"" + P_N + "\",\"x\":\"" + P_X + "\",\"t\":\"137\"}");
                }
                if (path.equals("/res/pow") && request.method().equals("POST")) {
                    return new Resp(200, List.of("browser_verified=fresh123; Path=/"), "{\"success\":true}");
                }
                String cookie = StringUtils.defaultString(request.header("Cookie"));
                if (path.equals("/") && cookie.contains("browser_verified=fresh123")) {
                    // 放行页:只有带本轮 PoW 换来的新 browser_verified 才放行
                    assertFalse(cookie.contains("browser_verified=stale999"), "陈旧值遮蔽新值=真凶: " + cookie);
                    assertTrue(cookie.contains("app_auth=abc"), "登录 Cookie 保留: " + cookie);
                    return new Resp(200, List.of(), "_obj.site={};");
                }
                // 无新 browser_verified(含携带陈旧值的)一律挑战,与线上反爬同语义
                return new Resp(200, List.of(), "<title>浏览器安全验证</title> pow.worker filejin");
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(
                req("app_auth=abc; browser_verified=stale999", ""));
        assertTrue(result.valid(), result.message());
        assertTrue(result.message().contains("Cookie 有效"), result.message());
    }

    private static SiteCredentialCheckRequest req(String cookie, String host) {
        return new SiteCredentialCheckRequest("guanying", cookie, host, "", "");
    }
}
