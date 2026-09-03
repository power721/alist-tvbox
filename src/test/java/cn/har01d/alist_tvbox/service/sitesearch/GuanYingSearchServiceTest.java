package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        assertEquals(8, GuanYingSearchService.normalizeHosts("").size());
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
}
