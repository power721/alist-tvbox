package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.jsoup.Jsoup;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 蜗牛搜索源:卡片/盘链解析、打码判定、Cookie 归一化、登录续期与冷却、双线路测速。
 */
class WoniuSearchServiceTest {

    private static final String SEARCH_HTML = """
            <html><body>
              <a class="video-card" href="/voddetail/10038/" title="难哄">
                <div class="video-title">难哄</div>
                <span class="video-score">8.0</span><span class="video-episode">4K</span>
              </a>
              <a class="video-card" href="/voddetail/18360/">
                <div class="video-title">  难哄   第二季 </div>
              </a>
              <a class="video-card" href="/voddetail/10038/" title="重复卡片"></a>
              <a class="video-card" href="/other/1/" title="无 voddetail">
                <img alt="别的">
              </a>
            </body></html>
            """;

    private static final String DETAIL_UNLOCKED = """
            <html><body><h1 class="desktop-detail-title">难哄</h1>
              <div class="pan-link-item">
                <div class="pan-link-title">4K WEB[268G]</div>
                <a class="pan-link-btn" href="https://pan.quark.cn/s/wn001">取链</a>
              </div>
              <div class="pan-link-item">
                <div class="pan-link-title">夸克备用</div>
                <div class="pan-link-meta">https://pan.quark.cn/s/wn002</div>
              </div>
              <div class="pan-link-item">
                <div class="pan-link-title">未知站点</div>
                <a class="pan-link-btn" href="https://unknown.example/s/x">取链</a>
              </div>
            </body></html>
            """;

    private static final String DETAIL_LOCKED = """
            <html><body>
              <div class="pan-link-item"><div class="pan-link-meta">https://******（登录后可见）</div></div>
            </body></html>
            """;

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
    void extractVodId() {
        assertEquals("10038", WoniuSearchService.extractVodId("/voddetail/10038/"));
        assertEquals("18360", WoniuSearchService.extractVodId("/voddetail/18360"));
        assertEquals("", WoniuSearchService.extractVodId("/vodtype/1-2/"));
    }

    @Test
    void parseCards() {
        WoniuSearchService service = new WoniuSearchService(null, new ObjectMapper());
        List<WoniuSearchService.Card> cards = service.parseCards(SEARCH_HTML);
        // voddetail 缺失/重复 href 去重
        assertEquals(2, cards.size());
        assertEquals("10038", cards.get(0).vodId());
        assertEquals("难哄", cards.get(0).title());
        assertEquals("8.0 · 4K", cards.get(0).remarks());
        // @title 缺失走 video-title 文本(空白折叠)
        assertEquals("难哄 第二季", cards.get(1).title());
    }

    @Test
    void collectPanLinksFiltersMaskedAndUnknown() {
        WoniuSearchService service = new WoniuSearchService(null, new ObjectMapper());
        List<String[]> links = service.collectPanLinks(Jsoup.parse(DETAIL_UNLOCKED));
        // btn@href 与 meta 文本两条有效链;打码与未知站点被滤
        assertEquals(2, links.size());
        assertEquals("4K WEB[268G]", links.get(0)[0]);
        assertEquals("https://pan.quark.cn/s/wn001", links.get(0)[1]);
        assertEquals("https://pan.quark.cn/s/wn002", links.get(1)[1]);
    }

    @Test
    void lockedDetection() {
        WoniuSearchService service = new WoniuSearchService(null, new ObjectMapper());
        assertTrue(WoniuSearchService.isLocked(Jsoup.parse(DETAIL_LOCKED)));
        assertFalse(WoniuSearchService.isLocked(Jsoup.parse(DETAIL_UNLOCKED)));
    }

    @Test
    void normalizeCookie() {
        assertEquals("a=1; b=2", WoniuSearchService.normalizeCookie("Cookie: a=1; b=2"));
        assertEquals("a=1; b=2", WoniuSearchService.normalizeCookie("a=1\nb=2\r\n垃圾"));
        assertEquals("", WoniuSearchService.normalizeCookie("无等号片段"));
    }

    @Test
    void normalizeHost() {
        assertEquals("", WoniuSearchService.normalizeHost(""));
        assertEquals("https://wn.example", WoniuSearchService.normalizeHost("wn.example/"));
        assertEquals("http://127.0.0.1:8080", WoniuSearchService.normalizeHost("http://127.0.0.1:8080/"));
    }

    @Test
    void noCredentialsMeansDisabled() {
        WoniuSearchService service = new WoniuSearchService(settings(), new ObjectMapper());
        assertTrue(service.search("难哄").isEmpty());
    }

    @Test
    void searchLoginsRelocksAndExtracts() {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger details = new AtomicInteger();
        WoniuSearchService service = new WoniuSearchService(
                settings("woniu_username", "u1", "woniu_password", "pw", "woniu_host", "https://wn.example"),
                new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.equals("https://wn.example/user/login.html")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of(
                            "user_check=abc123; Path=/",
                            "user_id=42; Path=/",
                            "user_name=u1; Path=/",
                            "PHPSESSID=drop; Path=/"), "{\"code\":\"1\"}");
                }
                if (url.startsWith("https://wn.example/vodsearch/")) {
                    assertTrue(request.header("Cookie").contains("user_check=abc123"), "搜索须带登录凭证");
                    return new Resp(200, List.of(), SEARCH_HTML);
                }
                if (url.equals("https://wn.example/voddetail/10038/")) {
                    // 第一次打码(登录态过期)→ 续期后重取解锁
                    if (details.incrementAndGet() == 1) {
                        return new Resp(200, List.of(), DETAIL_LOCKED);
                    }
                    return new Resp(200, List.of(), DETAIL_UNLOCKED);
                }
                if (url.equals("https://wn.example/voddetail/18360/")) {
                    return new Resp(200, List.of(), DETAIL_UNLOCKED);
                }
                return new Resp(404, List.of(), "");
            }
        };
        List<Message> messages = service.search("难哄");
        assertEquals(2, messages.size());
        assertEquals("https://pan.quark.cn/s/wn001", messages.get(0).getLink());
        assertEquals("5", messages.get(0).getType());
        assertEquals("蜗牛", messages.get(0).getChannel());
        assertEquals("难哄", messages.get(0).getName());
        assertEquals("https://pan.quark.cn/s/wn002", messages.get(1).getLink());
        assertEquals(2, logins.get(), "打码触发续期一次");
        assertEquals(2, details.get());
    }

    @Test
    void loginFailureCooldown() {
        AtomicInteger logins = new AtomicInteger();
        WoniuSearchService service = new WoniuSearchService(
                settings("woniu_username", "u1", "woniu_password", "bad", "woniu_host", "https://wn.example"),
                new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                if (request.url().toString().endsWith("/user/login.html")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of(), "{\"code\":\"0\",\"msg\":\"密码错误\"}");
                }
                return new Resp(200, List.of(), SEARCH_HTML);
            }
        };
        assertTrue(service.search("难哄").isEmpty());
        assertTrue(service.search("难哄").isEmpty());
        assertEquals(1, logins.get(), "冷却期内不撞登录接口");
    }

    @Test
    void probePicksFastestHost() {
        WoniuSearchService service = new WoniuSearchService(settings(), new ObjectMapper()) {
            @Override
            protected long probeHost(String host) {
                return host.contains("zmi") ? 50 : 500;
            }
        };
        assertEquals("https://zmi.kdns.fr",
                service.probeHosts(List.of("https://wn4k.com", "https://zmi.kdns.fr")));
        // 全部不可达 → null,保持默认顺序
        WoniuSearchService dead = new WoniuSearchService(settings(), new ObjectMapper()) {
            @Override
            protected long probeHost(String host) {
                return -1;
            }
        };
        assertNull(dead.probeHosts(List.of("https://wn4k.com", "https://zmi.kdns.fr")));
    }

    @Test
    void cookieFromConfigUsedDirectly() {
        AtomicInteger logins = new AtomicInteger();
        WoniuSearchService service = new WoniuSearchService(
                settings("woniu_cookie", "user_check=cfg; user_id=7; user_name=x", "woniu_host", "https://wn.example"),
                new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.endsWith("/user/login.html")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of(), "{\"code\":\"1\"}");
                }
                if (url.startsWith("https://wn.example/vodsearch/")) {
                    assertEquals("user_check=cfg; user_id=7; user_name=x", request.header("Cookie"));
                    return new Resp(200, List.of(), SEARCH_HTML);
                }
                if (url.endsWith("/voddetail/10038/") || url.endsWith("/voddetail/18360/")) {
                    return new Resp(200, List.of(), DETAIL_LOCKED);
                }
                return new Resp(404, List.of(), "");
            }
        };
        // Cookie 打码(无账号密码可续期)→ 返回空但绝不触发登录
        assertTrue(service.search("难哄").isEmpty());
        assertEquals(0, logins.get());
    }

    @Test
    void loginCookiePersistedAndReusedAfterRestart() {
        Map<String, String> store = new ConcurrentHashMap<>();
        store.put("woniu_host", "https://wn.example");
        store.put("woniu_username", "u1");
        store.put("woniu_password", "pw");
        AtomicInteger logins = new AtomicInteger();
        WoniuSearchService first = new WoniuSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.endsWith("/user/login.html")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of(
                            "user_check=chk-1; Path=/", "user_id=7; Path=/", "user_name=u1; Path=/",
                            "PHPSESSID=junk; Path=/"), "{\"code\":\"1\"}");
                }
                if (url.contains("/vodsearch/")) {
                    return new Resp(200, List.of(), SEARCH_HTML);
                }
                if (url.contains("/voddetail/10038/")) {
                    assertEquals("user_check=chk-1; user_id=7; user_name=u1", request.header("Cookie"));
                    return new Resp(200, List.of(), DETAIL_UNLOCKED);
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertEquals(2, first.search("难哄").size());
        assertEquals(1, logins.get());
        // 登录成功即落库:只留最小凭证集(user_check/user_id/user_name),PHPSESSID 不进
        assertEquals("user_check=chk-1; user_id=7; user_name=u1", store.get(WoniuSearchService.SESSION_SETTING));

        // 新实例=进程重启(内存会话清零):播种落库会话,零登录
        WoniuSearchService restarted = new WoniuSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.endsWith("/user/login.html")) {
                    throw new AssertionError("持久会话有效期内不得重新登录");
                }
                if (url.contains("/vodsearch/")) {
                    return new Resp(200, List.of(), SEARCH_HTML);
                }
                if (url.contains("/voddetail/10038/")) {
                    assertEquals("user_check=chk-1; user_id=7; user_name=u1", request.header("Cookie"),
                            "重启后播种落库会话");
                    return new Resp(200, List.of(), DETAIL_UNLOCKED);
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertEquals(2, restarted.search("难哄").size());
        assertEquals(1, logins.get(), "重启后复用持久化 Cookie,不再撞登录接口");
    }

    @Test
    void stalePersistedSessionReloginsAndOverwritesStore() {
        Map<String, String> store = new ConcurrentHashMap<>();
        store.put("woniu_host", "https://wn.example");
        store.put("woniu_username", "u1");
        store.put("woniu_password", "pw");
        store.put(WoniuSearchService.SESSION_SETTING, "user_check=stale; user_id=7; user_name=u1");
        AtomicInteger logins = new AtomicInteger();
        WoniuSearchService service = new WoniuSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                String cookie = request.header("Cookie") == null ? "" : request.header("Cookie");
                if (url.endsWith("/user/login.html")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of(
                            "user_check=fresh; Path=/", "user_id=7; Path=/", "user_name=u1; Path=/"),
                            "{\"code\":\"1\"}");
                }
                if (url.contains("/vodsearch/")) {
                    return new Resp(200, List.of(), SEARCH_HTML);
                }
                if (url.contains("/voddetail/10038/")) {
                    return new Resp(200, List.of(),
                            cookie.contains("user_check=fresh") ? DETAIL_UNLOCKED : DETAIL_LOCKED);
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertEquals(2, service.search("难哄").size());
        assertEquals(1, logins.get(), "落库会话被站点打码后必须续期一次");
        assertEquals("user_check=fresh; user_id=7; user_name=u1", store.get(WoniuSearchService.SESSION_SETTING),
                "续期后覆盖旧会话");
    }

    @Test
    void dailyCheckinLoginsPostsAndIsIdempotentPerDay() {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger checkins = new AtomicInteger();
        WoniuSearchService service = new WoniuSearchService(
                settings("woniu_username", "u1", "woniu_password", "pw", "woniu_host", "https://wn.example"),
                new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.equals("https://wn.example/user/login.html")) {
                    logins.incrementAndGet();
                    return new Resp(200, List.of(
                            "user_check=abc123; Path=/", "user_id=42; Path=/", "user_name=u1; Path=/"),
                            "{\"code\":\"1\"}");
                }
                if (url.equals("https://wn.example/user/checkin.html")) {
                    checkins.incrementAndGet();
                    // 2026-09-13 抓包契约:XHR/Origin/Referer 头 + 空 body
                    assertEquals("XMLHttpRequest", request.header("X-Requested-With"));
                    assertEquals("https://wn.example", request.header("Origin"));
                    assertEquals("https://wn.example/user/checkin.html", request.header("Referer"));
                    assertEquals("user_check=abc123; user_id=42; user_name=u1", request.header("Cookie"));
                    try (okio.Buffer buffer = new okio.Buffer()) {
                        request.body().writeTo(buffer);
                        assertEquals("", buffer.readUtf8(), "签到空 body(content-length: 0)");
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                    return new Resp(200, List.of(),
                            "{\"code\":1,\"msg\":\"签到成功\",\"info\":{\"points\":10,\"base_points\":10,\"serial_days\":1,\"claimable_milestones\":0}}");
                }
                return new Resp(404, List.of(), "");
            }
        };
        service.dailyCheckin();
        service.dailyCheckin();
        assertEquals(1, logins.get(), "无会话先登录一次");
        assertEquals(1, checkins.get(), "code==1 记当日完成,同日幂等");
    }

    @Test
    void dailyCheckinAlreadyDoneCountsAsDone() {
        AtomicInteger checkins = new AtomicInteger();
        WoniuSearchService service = new WoniuSearchService(
                settings("woniu_cookie", "user_check=cfg; user_id=7; user_name=x", "woniu_host", "https://wn.example"),
                new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                if (request.url().toString().endsWith("/user/checkin.html")) {
                    checkins.incrementAndGet();
                    assertEquals("user_check=cfg; user_id=7; user_name=x", request.header("Cookie"),
                            "Cookie 形态直用用户配置,不触发登录");
                    return new Resp(200, List.of(), "{\"code\":0,\"msg\":\"今天已签到过了\"}");
                }
                throw new AssertionError("Cookie 形态不得触发其它请求: " + request.url());
            }
        };
        service.dailyCheckin();
        service.dailyCheckin();
        assertEquals(1, checkins.get(), "已签文案同样记当日完成,同日不再撞接口");
    }

    @Test
    void dailyCheckinWithoutCredentialsDoesNothing() {
        WoniuSearchService service = new WoniuSearchService(settings(), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                throw new AssertionError("无凭证不得发任何请求");
            }
        };
        service.dailyCheckin();
    }

    @Test
    void loginFailureEvictsPersistedSession() {
        Map<String, String> store = new ConcurrentHashMap<>();
        store.put("woniu_host", "https://wn.example");
        store.put("woniu_username", "u1");
        store.put("woniu_password", "wrong");
        store.put(WoniuSearchService.SESSION_SETTING, "user_check=stale; user_id=7; user_name=u1");
        WoniuSearchService service = new WoniuSearchService(writableSettings(store), new ObjectMapper()) {
            @Override
            protected Resp http(Request request) {
                String url = request.url().toString();
                if (url.endsWith("/user/login.html")) {
                    return new Resp(200, List.of(), "{\"code\":\"0\",\"msg\":\"密码错误\"}");
                }
                if (url.contains("/vodsearch/")) {
                    return new Resp(200, List.of(), SEARCH_HTML);
                }
                if (url.contains("/voddetail/10038/")) {
                    return new Resp(200, List.of(), DETAIL_LOCKED);
                }
                return new Resp(404, List.of(), "");
            }
        };
        assertTrue(service.search("难哄").isEmpty());
        // 过期会话被打码 → 续期失败(如密码已改)→ 须清除,防下次重启回灌同一张死 Cookie
        assertTrue(store.get(WoniuSearchService.SESSION_SETTING).isEmpty(),
                "重登失败须清除过期会话,防重启回灌死 Cookie");
    }
}
