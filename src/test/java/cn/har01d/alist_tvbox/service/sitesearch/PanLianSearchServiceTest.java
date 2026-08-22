package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盘链搜索源:URL 清洗/提取码折叠/登录态/无凭证关闭/token 跳转解析/登录失败冷却。
 */
class PanLianSearchServiceTest {

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
    void normalizeHost() {
        assertEquals("https://www.xn--vzy265d.cc", PanLianSearchService.normalizeHost(""));
        assertEquals("https://www.xn--vzy265d.cc", PanLianSearchService.normalizeHost("www.xn--vzy265d.cc/"));
        assertEquals("http://mi.example", PanLianSearchService.normalizeHost("http://mi.example/path/"));
        assertEquals("https://" + java.net.IDN.toASCII("盘链.example"), PanLianSearchService.normalizeHost("盘链.example"));
    }

    @Test
    void cleanShareUrlStripsTrailingNoise() {
        assertEquals("https://pan.quark.cn/s/abc", PanLianSearchService.cleanShareUrl("https://pan.quark.cn/s/abc#"));
        // 只剥尾部"提取码"标注;URL 里已有的 pwd 参数保留(py 同行为)
        assertEquals("https://pan.baidu.com/s/1Abc?pwd=x1y2",
                PanLianSearchService.cleanShareUrl("https://pan.baidu.com/s/1Abc?pwd=x1y2 提取码：ab12"));
    }

    @Test
    void foldPasswordByPanType() {
        assertEquals("https://pan.baidu.com/s/1Abc?pwd=ab12",
                PanLianSearchService.foldPassword("https://pan.baidu.com/s/1Abc", "ab12"));
        assertEquals("https://www.123pan.com/s/x?pwd=ab12",
                PanLianSearchService.foldPassword("https://www.123pan.com/s/x", "ab12"));
        assertEquals("https://115.com/s/x?password=ab12",
                PanLianSearchService.foldPassword("https://115.com/s/x", "ab12"));
        // 已有参数不重复折;夸克/UC 无 pwd 参数约定不折(站点自动免码或转存时处理)
        assertEquals("https://pan.baidu.com/s/1Abc?pwd=zz99",
                PanLianSearchService.foldPassword("https://pan.baidu.com/s/1Abc?pwd=zz99", "ab12"));
        assertEquals("https://pan.quark.cn/s/x",
                PanLianSearchService.foldPassword("https://pan.quark.cn/s/x", "ab12"));
    }

    @Test
    void buildKeywordStripsLanguageSuffix() {
        assertEquals("难哄", PanLianSearchService.buildKeyword("难哄 国语"));
        assertEquals("难哄 第二季", PanLianSearchService.buildKeyword("难哄 第二季 粤语"));
    }

    @Test
    void noCredentialsMeansDisabled() {
        PanLianSearchService service = new PanLianSearchService(settings(), new ObjectMapper());
        assertTrue(service.search("难哄").isEmpty());
    }

    @Test
    void searchLoginsAndExtractsLinks() {
        AtomicInteger loginCalls = new AtomicInteger();
        PanLianSearchService service = new PanLianSearchService(
                settings("panlian_username", "a@b.com", "panlian_password", "secret"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                if (path.equals("/pages/login.php")) {
                    return new Resp(200, List.of("PHPSESSID=page123; Path=/"), "<html>login</html>");
                }
                if (path.equals("/api/login.php")) {
                    loginCalls.incrementAndGet();
                    return new Resp(200, List.of("user=token123; Path=/", "remember=1; Path=/"),
                            "{\"success\":true}");
                }
                if (path.equals("/api/get_videos.php")) {
                    assertTrue(request.header("Cookie").contains("user=token123"), "搜索必须带登录 Cookie");
                    return new Resp(200, List.of(), "{\"code\":1,\"list\":[{\"vod_id\":\"88\",\"vod_name\":\"难哄 国语\",\"vod_remarks\":\"全40集\"}]}");
                }
                if (path.equals("/api/search_pan_links.php")) {
                    assertTrue(request.url().encodedQuery().contains("keyword=%E9%9A%BE%E5%93%84"), "keyword 去掉语言后缀");
                    return new Resp(200, List.of(), """
                            {"success":true,"data":{"quark":{"name":"夸克","links":[
                               {"url":"https://pan.quark.cn/s/direct1","title":"夸克直链","time":"2025-08-01"},
                               {"token":"tok-baidu","password":"ab12","type":"百度","time":"2025-07-01"},
                               {"url":"magnet:?xt=urn:btih:xyz","title":"磁力","time":"2025-06-01"}]},
                              "dead":{"name":"其他","links":[{"url":"https://unknown.example/s/x","title":"未知盘"}]}}}
                            """);
                }
                return new Resp(404, List.of(), "");
            }

            @Override
            protected String resolveRedirect(String url, Map<String, String> headers) {
                if (url.contains("tok-baidu")) {
                    assertTrue(url.startsWith("https://www.xn--vzy265d.cc/api/go.php?t="));
                    assertTrue(headers.get("Cookie").contains("skip_go_warning=1"));
                    return "https://pan.baidu.com/s/1AbCdEfGhIjKlMnOpQrSt";
                }
                return url;
            }
        };
        List<Message> messages = service.search("难哄");
        assertEquals(2, messages.size());
        assertEquals("https://pan.quark.cn/s/direct1", messages.get(0).getLink());
        assertEquals("5", messages.get(0).getType());
        assertEquals("盘链", messages.get(0).getChannel());
        assertEquals("难哄 国语", messages.get(0).getName());
        // token 经 go.php 解析出的百度链,结构化 password 折成 pwd=
        assertEquals("https://pan.baidu.com/s/1AbCdEfGhIjKlMnOpQrSt?pwd=ab12", messages.get(1).getLink());
        assertEquals("10", messages.get(1).getType());
        assertEquals(1, loginCalls.get());
    }

    @Test
    void loginFailureEntersCooldown() {
        AtomicInteger loginCalls = new AtomicInteger();
        PanLianSearchService service = new PanLianSearchService(
                settings("panlian_username", "a@b.com", "panlian_password", "wrong"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                if (path.equals("/pages/login.php")) {
                    return new Resp(200, List.of("PHPSESSID=page123"), "<html></html>");
                }
                if (path.equals("/api/login.php")) {
                    loginCalls.incrementAndGet();
                    return new Resp(200, List.of(), "{\"success\":false,\"msg\":\"密码错误\"}");
                }
                return new Resp(200, List.of(), "{\"code\":-1,\"msg\":\"请先登录\"}");
            }
        };
        assertTrue(service.search("难哄").isEmpty());
        assertTrue(service.search("难哄").isEmpty());
        assertEquals(1, loginCalls.get(), "冷却期内不得反复撞登录接口");
    }

    @Test
    void configuredCookieUsedDirectly() {
        AtomicInteger logins = new AtomicInteger();
        PanLianSearchService service = new PanLianSearchService(
                settings("panlian_cookie", "user=cfg-cookie"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                if (path.equals("/pages/login.php") || path.equals("/api/login.php")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of(), "{}");
                }
                if (path.equals("/api/get_videos.php")) {
                    assertEquals("user=cfg-cookie", request.header("Cookie"));
                    return new Resp(200, List.of(), "{\"code\":1,\"list\":[]}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertTrue(service.search("难哄").isEmpty());
        assertEquals(0, logins.get(), "配置了 Cookie 时不得触发账号密码登录");
    }

    @Test
    void loginRequiredDetection() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(PanLianSearchService.isLoginRequired(mapper.readTree("{\"code\":-1,\"msg\":\"请先登录后再使用搜索功能\"}")));
        assertTrue(PanLianSearchService.isLoginRequired(mapper.readTree("{\"success\":false,\"msg\":\"登录已过期\"}")));
        assertTrue(!PanLianSearchService.isLoginRequired(mapper.readTree("{\"code\":1,\"list\":[]}")));
        assertTrue(!PanLianSearchService.isLoginRequired(mapper.readTree("{\"code\":-1,\"msg\":\"参数错误\"}")));
    }
}
