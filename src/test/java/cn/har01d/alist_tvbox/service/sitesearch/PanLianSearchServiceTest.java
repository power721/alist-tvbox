package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盘链搜索源(2026-09 改版后契约):两步解锁/提取码折叠/登录态/无凭证关闭/
 * 配额尽停/解锁预算与缓存/每日签到防重/登录失败冷却。
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

    private static String bodyText(Request request) {
        RequestBody body = request.body();
        if (body == null) {
            return "";
        }
        try (okio.Buffer buffer = new okio.Buffer()) {
            body.writeTo(buffer);
            return buffer.readUtf8();
        } catch (IOException e) {
            return "";
        }
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
    void noCredentialsMeansDisabled() {
        PanLianSearchService service = new PanLianSearchService(settings(), new ObjectMapper());
        assertTrue(service.search("难哄").isEmpty());
    }

    @Test
    void searchLoginsChecksInUnlocksAndExtractsLinks() {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger checkins = new AtomicInteger();
        AtomicInteger tickets = new AtomicInteger();
        Map<String, String> unlockUrls = Map.of(
                "16019", "https://pan.quark.cn/s/direct1",
                "16020", "https://pan.baidu.com/s/1AbCdEfGhIjKlMnOpQrSt",
                "16021", "magnet:?xt=urn:btih:abc123&dn=%E9%9A%BE%E5%93%8404",
                "16022", "ed2k://|file|nanhong.EP05.1080p.mp4|1234567|hash|/");
        PanLianSearchService service = new PanLianSearchService(
                settings("panlian_username", "a@b.com", "panlian_password", "secret"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                String method = request.method();
                if ("POST".equals(method) && path.equals("/api/auth/login")) {
                    logins.incrementAndGet();
                    assertTrue(request.body() instanceof FormBody, "登录必须表单编码");
                    FormBody form = (FormBody) request.body();
                    Map<String, String> fields = new HashMap<>();
                    for (int i = 0; i < form.size(); i++) {
                        fields.put(form.name(i), form.value(i));
                    }
                    assertEquals("a@b.com", fields.get("username"));
                    assertEquals("secret", fields.get("password"));
                    assertEquals("1", fields.get("remember"));
                    return new Resp(200, List.of("admin_session=sess-token-1; Path=/; HttpOnly"),
                            "{\"success\":true,\"data\":{\"user_id\":1}}");
                }
                if ("POST".equals(method) && path.equals("/api/tasks/checkin")) {
                    checkins.incrementAndGet();
                    assertEquals("{}", bodyText(request));
                    return new Resp(200, List.of(),
                            "{\"success\":true,\"data\":{\"bonus\":20,\"quota\":{\"remaining\":49,\"limit\":50}},\"message\":\"签到成功\"}");
                }
                if ("GET".equals(method) && path.equals("/api/videos")) {
                    assertTrue(request.header("Cookie").contains("admin_session=sess-token-1"), "搜索必须带登录 Cookie");
                    assertTrue(request.url().encodedQuery().contains("search=%E9%9A%BE%E5%93%84"));
                    return new Resp(200, List.of(),
                            "{\"success\":true,\"data\":{\"list\":[{\"id\":88,\"title\":\"难哄 国语\",\"remarks\":\"全40集\"}],\"total\":1}}");
                }
                if ("GET".equals(method) && path.equals("/api/videos/88")) {
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"video\":{\"id\":88},\"links\":["
                            + "{\"id\":16019,\"pan_type\":\"quark\",\"title\":\"夸克直链\"},"
                            + "{\"id\":16020,\"pan_type\":\"baidu\",\"title\":\"百度链\"},"
                            + "{\"id\":16021,\"pan_type\":\"magnet\",\"is_magnet\":true,\"title\":\"难哄 04集 1080P·介绍：全集网盘\"},"
                            + "{\"id\":16022,\"pan_type\":\"ed2k\",\"title\":\"第05集 电驴\"},"
                            + "{\"id\":16023,\"pan_type\":\"weiyun\",\"title\":\"微云不解锁\"}]}}");
                }
                if ("POST".equals(method) && path.equals("/api/videos/link-ticket")) {
                    tickets.incrementAndGet();
                    String body = bodyText(request);
                    assertTrue(body.matches("\\{\"link_id\":\\d+}"), "ticket 体带数字 link_id: " + body);
                    String linkId = body.replaceAll("\\D", "");
                    return new Resp(200, List.of(),
                            "{\"success\":true,\"data\":{\"ticket\":\"t-" + linkId + "\",\"expires_in\":90}}");
                }
                if ("GET".equals(method) && path.startsWith("/api/videos/link-open/")) {
                    String linkId = path.substring(path.lastIndexOf('/') + 1);
                    assertTrue(request.url().encodedQuery().contains("t=t-" + linkId), "open 必须带 ticket");
                    if (linkId.equals("16020")) {
                        return new Resp(200, List.of(),
                                "{\"success\":true,\"data\":{\"url\":\"https://pan.baidu.com/s/1AbCdEfGhIjKlMnOpQrSt\",\"code\":\"ab12\"}}");
                    }
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"url\":\"" + unlockUrls.get(linkId) + "\"}}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        List<Message> messages = service.search("难哄");
        assertEquals(4, messages.size());
        assertEquals("https://pan.quark.cn/s/direct1", messages.get(0).getLink());
        assertEquals("5", messages.get(0).getType());
        assertEquals("盘链", messages.get(0).getChannel());
        assertEquals("难哄 国语", messages.get(0).getName());
        // 解锁的结构化提取码折成 pwd=
        assertEquals("https://pan.baidu.com/s/1AbCdEfGhIjKlMnOpQrSt?pwd=ab12", messages.get(1).getLink());
        assertEquals("10", messages.get(1).getType());
        // 磁力条目:link 原样,type=magnet,content=清洗后的资源标题(剥「介绍:」尾巴)
        assertEquals("magnet:?xt=urn:btih:abc123&dn=%E9%9A%BE%E5%93%8404", messages.get(2).getLink());
        assertEquals("magnet", messages.get(2).getType());
        assertEquals("难哄 04集 1080P", messages.get(2).getContent());
        // ed2k 条目:type=ed2k(文件名在链接 |file| 段,标题口径由磁力兜底侧解析)
        assertEquals("ed2k://|file|nanhong.EP05.1080p.mp4|1234567|hash|/", messages.get(3).getLink());
        assertEquals("ed2k", messages.get(3).getType());
        assertEquals(1, logins.get());
        assertEquals(1, checkins.get(), "每日签到只触发一次");
        assertEquals(4, tickets.get(), "微云链在解锁前按 pan_type 剔除");

        // 二次搜索:解锁结果走短缓存、签到同日不重复、登录态复用
        List<Message> again = service.search("难哄");
        assertEquals(4, again.size());
        assertEquals(4, tickets.get(), "缓存命中不得再走两步解锁");
        assertEquals(1, checkins.get());
        assertEquals(1, logins.get());
    }

    @Test
    void searchReloginsOnSessionExpiry() {
        AtomicInteger logins = new AtomicInteger();
        PanLianSearchService service = new PanLianSearchService(
                settings("panlian_username", "a@b.com", "panlian_password", "secret"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                String method = request.method();
                if ("POST".equals(method) && path.equals("/api/auth/login")) {
                    int n = logins.incrementAndGet();
                    return new Resp(200, List.of("admin_session=sess-" + n + "; Path=/"), "{\"success\":true}");
                }
                if ("POST".equals(method) && path.equals("/api/tasks/checkin")) {
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"quota\":{\"remaining\":49,\"limit\":50}}}");
                }
                if ("GET".equals(method) && path.equals("/api/videos")) {
                    if (!request.header("Cookie").contains("admin_session=sess-2")) {
                        return new Resp(200, List.of(),
                                "{\"success\":false,\"message\":\"请先登录\",\"error_type\":\"ADMIN_AUTH_REQUIRED\"}");
                    }
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"list\":[],\"total\":0}}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertTrue(service.search("难哄").isEmpty());
        assertEquals(2, logins.get(), "搜索报登录失效后必须重登再重试一次");
    }

    @Test
    void unlockReloginsOnSessionExpiry() {
        AtomicInteger logins = new AtomicInteger();
        PanLianSearchService service = new PanLianSearchService(
                settings("panlian_username", "a@b.com", "panlian_password", "secret"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                String method = request.method();
                if ("POST".equals(method) && path.equals("/api/auth/login")) {
                    int n = logins.incrementAndGet();
                    return new Resp(200, List.of("admin_session=sess-" + n + "; Path=/"), "{\"success\":true}");
                }
                if ("POST".equals(method) && path.equals("/api/tasks/checkin")) {
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{}}");
                }
                if ("GET".equals(method) && path.equals("/api/videos")) {
                    return new Resp(200, List.of(),
                            "{\"success\":true,\"data\":{\"list\":[{\"id\":88,\"title\":\"难哄\",\"remarks\":\"全40集\"}]}}");
                }
                if ("GET".equals(method) && path.equals("/api/videos/88")) {
                    return new Resp(200, List.of(),
                            "{\"success\":true,\"data\":{\"links\":[{\"id\":16019,\"pan_type\":\"quark\",\"title\":\"夸克\"}]}}");
                }
                if ("POST".equals(method) && path.equals("/api/videos/link-ticket")) {
                    if (!request.header("Cookie").contains("admin_session=sess-2")) {
                        return new Resp(200, List.of(),
                                "{\"success\":false,\"message\":\"登录已过期\"}");
                    }
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"ticket\":\"t-1\",\"expires_in\":90}}");
                }
                if ("GET".equals(method) && path.startsWith("/api/videos/link-open/")) {
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"url\":\"https://pan.quark.cn/s/relogin\"}}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        List<Message> messages = service.search("难哄");
        assertEquals(1, messages.size());
        assertEquals("https://pan.quark.cn/s/relogin", messages.get(0).getLink());
        assertEquals(2, logins.get(), "解锁 ticket 报登录失效后必须重登重试");
    }

    @Test
    void quotaExhaustedStopsUnlocking() {
        AtomicInteger tickets = new AtomicInteger();
        PanLianSearchService service = new PanLianSearchService(
                settings("panlian_cookie", "admin_session=cfg"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                String method = request.method();
                if ("POST".equals(method) && path.equals("/api/tasks/checkin")) {
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{}}");
                }
                if ("GET".equals(method) && path.equals("/api/videos")) {
                    return new Resp(200, List.of(),
                            "{\"success\":true,\"data\":{\"list\":[{\"id\":88,\"title\":\"难哄\",\"remarks\":\"\"}]}}");
                }
                if ("GET".equals(method) && path.equals("/api/videos/88")) {
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"links\":["
                            + "{\"id\":16019,\"pan_type\":\"quark\",\"title\":\"链1\"},"
                            + "{\"id\":16020,\"pan_type\":\"quark\",\"title\":\"链2\"}]}}");
                }
                if ("POST".equals(method) && path.equals("/api/videos/link-ticket")) {
                    tickets.incrementAndGet();
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"ticket\":\"t-1\",\"expires_in\":90}}");
                }
                if ("GET".equals(method) && path.startsWith("/api/videos/link-open/")) {
                    return new Resp(200, List.of(), "{\"success\":false,\"message\":\"今日解锁配额已用完\"}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertTrue(service.search("难哄").isEmpty());
        assertEquals(1, tickets.get(), "配额用尽后不得继续解锁后续链接");
    }

    @Test
    void unlockBudgetCapsPerSearch() {
        AtomicInteger tickets = new AtomicInteger();
        StringBuilder links = new StringBuilder();
        for (int i = 1; i <= 15; i++) {
            if (links.length() > 0) {
                links.append(',');
            }
            links.append("{\"id\":").append(i).append(",\"pan_type\":\"quark\",\"title\":\"链").append(i).append("\"}");
        }
        PanLianSearchService service = new PanLianSearchService(
                settings("panlian_cookie", "admin_session=cfg"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                String method = request.method();
                if ("POST".equals(method) && path.equals("/api/tasks/checkin")) {
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{}}");
                }
                if ("GET".equals(method) && path.equals("/api/videos")) {
                    return new Resp(200, List.of(),
                            "{\"success\":true,\"data\":{\"list\":[{\"id\":88,\"title\":\"难哄\",\"remarks\":\"\"}]}}");
                }
                if ("GET".equals(method) && path.equals("/api/videos/88")) {
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"links\":[" + links + "]}}");
                }
                if ("POST".equals(method) && path.equals("/api/videos/link-ticket")) {
                    tickets.incrementAndGet();
                    String linkId = bodyText(request).replaceAll("\\D", "");
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"ticket\":\"t-" + linkId + "\"}}");
                }
                if ("GET".equals(method) && path.startsWith("/api/videos/link-open/")) {
                    String linkId = path.substring(path.lastIndexOf('/') + 1);
                    return new Resp(200, List.of(),
                            "{\"success\":true,\"data\":{\"url\":\"https://pan.quark.cn/s/budget-" + linkId + "\"}}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        List<Message> messages = service.search("难哄");
        assertEquals(12, messages.size());
        assertEquals(12, tickets.get(), "单次搜索解锁走预算封顶");
    }

    @Test
    void configuredCookieUsedDirectly() {
        AtomicInteger logins = new AtomicInteger();
        PanLianSearchService service = new PanLianSearchService(
                settings("panlian_cookie", "admin_session=cfg-cookie"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String path = request.url().encodedPath();
                String method = request.method();
                if ("POST".equals(method) && path.equals("/api/auth/login")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of(), "{}");
                }
                if ("POST".equals(method) && path.equals("/api/tasks/checkin")) {
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{}}");
                }
                if ("GET".equals(method) && path.equals("/api/videos")) {
                    assertEquals("admin_session=cfg-cookie", request.header("Cookie"));
                    return new Resp(200, List.of(), "{\"success\":true,\"data\":{\"list\":[]}}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertTrue(service.search("难哄").isEmpty());
        assertEquals(0, logins.get(), "配置了 Cookie 时不得触发账号密码登录");
    }

    @Test
    void loginFailureEntersCooldown() {
        AtomicInteger loginCalls = new AtomicInteger();
        PanLianSearchService service = new PanLianSearchService(
                settings("panlian_username", "a@b.com", "panlian_password", "wrong"), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                if (request.url().encodedPath().equals("/api/auth/login")) {
                    loginCalls.incrementAndGet();
                    return new Resp(200, List.of(), "{\"success\":false,\"message\":\"密码错误\"}");
                }
                return new Resp(200, List.of(), "{\"success\":false,\"message\":\"请先登录\"}");
            }
        };
        assertTrue(service.search("难哄").isEmpty());
        assertTrue(service.search("难哄").isEmpty());
        assertEquals(1, loginCalls.get(), "冷却期内不得反复撞登录接口");
    }

    @Test
    void loginRequiredDetection() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(PanLianSearchService.isLoginRequired(mapper.readTree("{\"success\":false,\"message\":\"请先登录\",\"error_type\":\"ADMIN_AUTH_REQUIRED\"}")));
        assertTrue(PanLianSearchService.isLoginRequired(mapper.readTree("{\"error_type\":\"ADMIN_AUTH_REQUIRED\"}")));
        assertTrue(PanLianSearchService.isLoginRequired(mapper.readTree("{\"success\":false,\"message\":\"登录已过期\"}")));
        assertTrue(PanLianSearchService.isLoginRequired(mapper.readTree("{\"success\":false,\"message\":\"未授权访问\"}")));
        assertFalse(PanLianSearchService.isLoginRequired(mapper.readTree("{\"success\":true,\"data\":{}}")));
        assertFalse(PanLianSearchService.isLoginRequired(mapper.readTree("{\"success\":false,\"message\":\"参数错误\"}")));
    }

    @Test
    void cleanLinkTitleStripsIntroAndHtml() {
        assertEquals("难哄 04集 1080P", PanLianSearchService.cleanLinkTitle("难哄 04集 1080P·介绍：全集网盘"));
        assertEquals("资源", PanLianSearchService.cleanLinkTitle("<b>资源</b>"));
        assertEquals("", PanLianSearchService.cleanLinkTitle(""));
    }
}
