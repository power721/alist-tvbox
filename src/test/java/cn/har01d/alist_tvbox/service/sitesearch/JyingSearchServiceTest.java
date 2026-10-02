package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.dto.SiteCredentialCheckRequest;
import cn.har01d.alist_tvbox.dto.SiteCredentialCheckResult;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚影搜索源:整链路打桩(账号密码登录 → 匿名搜索 → 资源列表 → ticket 解锁 →
 * 提取码折 pwd=)/Token 形态免登录/登录失效自动重登/磁力资源按磁力兜底开关门控/
 * 解锁限流熔断/解锁预算/x-refreshed-token 轮换捕获/资源标题清洗/凭证检查两形态。
 */
class JyingSearchServiceTest {

    private static final String MOVIES_JSON = """
            {"count": 2, "results": [
              {"id": 101, "title": "凡人修仙传", "resource_count": 2},
              {"id": 102, "title": "私密作品", "is_private": true, "resource_count": 1}
            ]}
            """;

    private static final String RESOURCES_JSON = """
            {"resources": [
              {"id": 11, "resource_type": "quark", "resource_type_display": "夸克网盘",
               "link_exposed": true, "access_ticket": "tk11",
               "title": "4K|国语|第01-24集", "file_size": "8.2GB"},
              {"id": 12, "resource_type": "p123", "resource_type_display": "123网盘",
               "link_exposed": true, "access_ticket": "tk12",
               "title": "第01-24集#全集", "file_size": "3.1GB"}
            ], "has_more": false}
            """;

    private static final String MAGNET_RESOURCES_JSON = """
            {"resources": [
              {"id": 21, "resource_type": "MagnetLink", "link_exposed": true, "access_ticket": "tk21",
               "title": "第01-24集 4K"},
              {"id": 22, "resource_type": "ed2k", "link_exposed": true, "access_ticket": "tk22",
               "title": "全集"}
            ], "has_more": false}
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SettingRepository settings(String username, String password, String token) {
        SettingRepository repository = Mockito.mock(SettingRepository.class);
        Mockito.when(repository.findById(Mockito.anyString())).thenReturn(Optional.empty());
        if (username != null) {
            Mockito.when(repository.findById(JyingSearchService.USERNAME_SETTING))
                    .thenReturn(Optional.of(new Setting(JyingSearchService.USERNAME_SETTING, username)));
        }
        if (password != null) {
            Mockito.when(repository.findById(JyingSearchService.PASSWORD_SETTING))
                    .thenReturn(Optional.of(new Setting(JyingSearchService.PASSWORD_SETTING, password)));
        }
        if (token != null) {
            Mockito.when(repository.findById(JyingSearchService.TOKEN_SETTING))
                    .thenReturn(Optional.of(new Setting(JyingSearchService.TOKEN_SETTING, token)));
        }
        return repository;
    }

    private static String bodyOf(Request request) throws IOException {
        if (request.body() == null) {
            return "";
        }
        okio.Buffer buffer = new okio.Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }

    private static JsonNode json(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    @Test
    void searchFullChainWithAccountLogin() throws IOException {
        SettingRepository repository = settings("fanren", "pass#1", null);
        AtomicInteger unlocks = new AtomicInteger();
        JyingSearchService service = new JyingSearchService(repository, MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.endsWith("/api/app/login/")) {
                    assertTrue(bodyOf(request).contains("\"username\":\"fanren\""));
                    assertTrue(bodyOf(request).contains("\"password\":\"pass#1\""));
                    return new Resp(200, List.of(), "{\"status\":\"success\",\"token\":\"tok1\"}");
                }
                if (url.contains("/api/app/movies/")) {
                    assertTrue(request.header("X-App-User-Token") == null, "搜索接口匿名可用,不应带认证头");
                    assertTrue(url.contains("q=%E5%87%A1%E4%BA%BA"), "关键词须编码入参");
                    return new Resp(200, List.of(), MOVIES_JSON);
                }
                if (url.contains("/api/app/movie/101/resources/")) {
                    assertEquals("tok1", request.header("X-App-User-Token"));
                    return new Resp(200, List.of(), RESOURCES_JSON);
                }
                if (url.contains("/api/app/resource/11/access/")) {
                    unlocks.incrementAndGet();
                    assertTrue(bodyOf(request).contains("\"access_ticket\":\"tk11\""));
                    return new Resp(200, List.of(),
                            "{\"target\":\"https://pan.quark.cn/s/abc123\",\"access_code\":\"qk88\"}");
                }
                if (url.contains("/api/app/resource/12/access/")) {
                    unlocks.incrementAndGet();
                    return new Resp(200, List.of(),
                            "{\"target\":\"https://www.123684.com/s/zzz999\",\"access_code\":\"pn12\"}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        List<Message> messages = service.search("凡人", true);
        assertEquals(2, messages.size());
        assertEquals("5", messages.get(0).getType());
        assertEquals("https://pan.quark.cn/s/abc123?pwd=qk88", messages.get(0).getLink());
        assertEquals("聚影", messages.get(0).getChannel());
        assertEquals("凡人修仙传", messages.get(0).getName());
        assertTrue(messages.get(0).getContent().contains("第01-24集"), "资源标题须进正文供集号解析");
        assertTrue(messages.get(0).getContent().contains("8.2GB"));
        assertEquals("3", messages.get(1).getType());
        assertEquals("https://www.123684.com/s/zzz999?pwd=pn12", messages.get(1).getLink());
        // 资源标题里的 # 是播放串分隔符,必须剥掉
        assertFalse(messages.get(1).getContent().contains("#"));
        assertEquals(2, unlocks.get());
        // 账号密码形态登录所得 token 落库,重启免重登
        ArgumentCaptor<Setting> captor = ArgumentCaptor.forClass(Setting.class);
        Mockito.verify(repository).save(captor.capture());
        assertEquals(JyingSearchService.SESSION_SETTING, captor.getValue().getName());
        assertEquals("tok1", captor.getValue().getValue());
    }

    @Test
    void tokenFormSkipsLoginAndUsesConfiguredToken() throws IOException {
        SettingRepository repository = settings(null, null, "tok9");
        JyingSearchService service = new JyingSearchService(repository, MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.contains("/api/app/login/")) {
                    throw new IOException("Token 形态不应登录");
                }
                if (url.contains("/api/app/movies/")) {
                    return new Resp(200, List.of(), MOVIES_JSON);
                }
                if (url.contains("/api/app/movie/101/resources/")) {
                    assertEquals("tok9", request.header("X-App-User-Token"));
                    return new Resp(200, List.of(), RESOURCES_JSON);
                }
                if (url.contains("/access/")) {
                    String target = url.contains("/resource/11/") ? "abc123" : "def456";
                    return new Resp(200, List.of(),
                            "{\"target\":\"https://pan.quark.cn/s/" + target + "\"}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        List<Message> messages = service.search("凡人", false);
        assertEquals(2, messages.size());
        assertEquals("https://pan.quark.cn/s/abc123", messages.get(0).getLink());
        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void noCredentialsSilentlyDisabled() throws IOException {
        JyingSearchService service = new JyingSearchService(settings(null, null, null), MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                throw new IOException("无凭证不应发任何请求");
            }
        };
        assertTrue(service.search("凡人", true).isEmpty());
    }

    @Test
    void authErrorTriggersReloginAndRetry() throws IOException {
        SettingRepository repository = settings("fanren", "pw", null);
        AtomicInteger resourceFetches = new AtomicInteger();
        AtomicInteger logins = new AtomicInteger();
        JyingSearchService service = new JyingSearchService(repository, MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.endsWith("/api/app/login/")) {
                    // 首登 tok1(即将被站点拒收),重登换发 tok2
                    return new Resp(200, List.of(),
                            "{\"status\":\"success\",\"token\":\"tok" + logins.incrementAndGet() + "\"}");
                }
                if (url.contains("/api/app/movies/")) {
                    return new Resp(200, List.of(), MOVIES_JSON);
                }
                if (url.contains("/resources/")) {
                    resourceFetches.incrementAndGet();
                    // 第一次用失效 token 被拒,重登(tok2)后放行
                    if (!"tok2".equals(request.header("X-App-User-Token"))) {
                        return new Resp(401, List.of(),
                                "{\"status\":\"error\",\"message\":\"请先登录后访问资源\"}");
                    }
                    return new Resp(200, List.of(), RESOURCES_JSON);
                }
                if (url.contains("/access/")) {
                    assertEquals("tok2", request.header("X-App-User-Token"));
                    String target = url.contains("/resource/11/") ? "abc123" : "def456";
                    return new Resp(200, List.of(),
                            "{\"target\":\"https://pan.quark.cn/s/" + target + "\",\"access_code\":\"qk88\"}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        List<Message> messages = service.search("凡人", false);
        assertEquals(2, messages.size());
        assertEquals(2, logins.get(), "首登 + 失效重登各一次");
        assertEquals(2, resourceFetches.get(), "拒收一次 + 重登后放行一次");
        // relogin 先清旧会话再落新 token:最终落库值是新 token
        ArgumentCaptor<Setting> captor = ArgumentCaptor.forClass(Setting.class);
        Mockito.verify(repository, Mockito.atLeastOnce()).save(captor.capture());
        List<String> saved = captor.getAllValues().stream().map(Setting::getValue).toList();
        assertEquals("tok2", saved.getLast());
    }

    @Test
    void magnetResourcesGatedByOfflineIncluded() throws IOException {
        AtomicInteger unlocks = new AtomicInteger();
        JyingSearchService service = new JyingSearchService(settings(null, null, "tok9"), MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.contains("/api/app/movies/")) {
                    return new Resp(200, List.of(), MOVIES_JSON);
                }
                if (url.contains("/resources/")) {
                    return new Resp(200, List.of(), MAGNET_RESOURCES_JSON);
                }
                if (url.contains("/access/")) {
                    unlocks.incrementAndGet();
                    String target = url.contains("/resource/21/")
                            ? "magnet:?xt=urn:btih:0123456789abcdef"
                            : "magnet:?xt=urn:btih:fedcba9876543210";
                    return new Resp(200, List.of(), "{\"target\":\"" + target + "\"}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        // 兜底未开:磁力/ed2k 资源不解锁(省配额,产出也会被定向集闸门裁掉)
        assertTrue(service.search("凡人", false).isEmpty());
        assertEquals(0, unlocks.get());
        // 兜底开:解锁产出离线候选条目
        List<Message> messages = service.search("凡人", true);
        assertEquals(2, messages.size());
        assertEquals("magnet", messages.get(0).getType());
        assertEquals("magnet:?xt=urn:btih:0123456789abcdef", messages.get(0).getLink());
        assertTrue(messages.get(0).getContent().contains("第01-24集"));
        assertEquals("magnet", messages.get(1).getType());
        assertEquals(2, unlocks.get());
    }

    @Test
    void unlockThrottleStopsRemainingResources() throws IOException {
        AtomicInteger unlocks = new AtomicInteger();
        JyingSearchService service = new JyingSearchService(settings(null, null, "tok9"), MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.contains("/api/app/movies/")) {
                    return new Resp(200, List.of(), "{\"results\":[" +
                            "{\"id\":101,\"title\":\"凡人修仙传\",\"resource_count\":2}]}");
                }
                if (url.contains("/resources/")) {
                    return new Resp(200, List.of(), RESOURCES_JSON);
                }
                if (url.contains("/access/")) {
                    unlocks.incrementAndGet();
                    return new Resp(429, List.of(),
                            "{\"status\":\"error\",\"message\":\"资源访问过于频繁,请稍后再试\"}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        assertTrue(service.search("凡人", false).isEmpty());
        assertEquals(1, unlocks.get(), "限流后应整段停止,不再撞第二条资源");
    }

    @Test
    void unlockBudgetCapsAtTwelve() throws IOException {
        StringBuilder resources = new StringBuilder("{\"resources\":[");
        for (int i = 1; i <= 15; i++) {
            if (i > 1) {
                resources.append(',');
            }
            resources.append("{\"id\":").append(100 + i)
                    .append(",\"resource_type\":\"quark\",\"link_exposed\":true,\"access_ticket\":\"tk")
                    .append(i).append("\",\"title\":\"第01-24集\"}");
        }
        resources.append("],\"has_more\":false}");
        AtomicInteger unlocks = new AtomicInteger();
        JyingSearchService service = new JyingSearchService(settings(null, null, "tok9"), MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.contains("/api/app/movies/")) {
                    return new Resp(200, List.of(), "{\"results\":[" +
                            "{\"id\":101,\"title\":\"凡人修仙传\",\"resource_count\":15}]}");
                }
                if (url.contains("/resources/")) {
                    return new Resp(200, List.of(), resources.toString());
                }
                if (url.contains("/access/")) {
                    unlocks.incrementAndGet();
                    return new Resp(200, List.of(),
                            "{\"target\":\"https://pan.quark.cn/s/abc" + unlocks.get() + "\"}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        assertEquals(12, service.search("凡人", false).size());
        assertEquals(12, unlocks.get());
    }

    @Test
    void refreshedTokenCapturedFromResponseHeader() throws IOException {
        SettingRepository repository = settings("fanren", "pw", null);
        JyingSearchService service = new JyingSearchService(repository, MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.endsWith("/api/app/login/")) {
                    return new Resp(200, List.of(), "{\"status\":\"success\",\"token\":\"tok1\"}");
                }
                if (url.contains("/api/app/movies/")) {
                    return new Resp(200, List.of(), MOVIES_JSON);
                }
                if (url.contains("/resources/")) {
                    assertEquals("tok1", request.header("X-App-User-Token"));
                    // 服务端轮换 token:响应头下发新值
                    return new Resp(200, List.of(), RESOURCES_JSON,
                            Map.of("x-refreshed-token", List.of("tok2")));
                }
                if (url.contains("/access/")) {
                    assertEquals("tok2", request.header("X-App-User-Token"), "轮换后须立即用新 token");
                    String target = url.contains("/resource/11/") ? "abc123" : "def456";
                    return new Resp(200, List.of(), "{\"target\":\"https://pan.quark.cn/s/" + target + "\"}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        assertEquals(2, service.search("凡人", false).size());
        // 账号密码形态:轮换 token 顺手落库
        ArgumentCaptor<Setting> captor = ArgumentCaptor.forClass(Setting.class);
        Mockito.verify(repository, Mockito.atLeastOnce()).save(captor.capture());
        assertEquals("tok2", captor.getAllValues().getLast().getValue());
    }

    @Test
    void resourceLabelCleansAndTruncates() throws IOException {
        assertEquals("4K|国语|第01-24集·8.2GB",
                JyingSearchService.resourceLabel(json(
                        "{\"title\":\"4K|国语|第01-24集\",\"file_size\":\"8.2GB\"}")));
        assertEquals("第01-24集 全集",
                JyingSearchService.resourceLabel(json(
                        "{\"title\":\"第01-24集#全集\\r\\n\",\"file_size\":\"\"}")));
        // 描述兜底链:title → resource_description → description
        assertEquals("来自描述", JyingSearchService.resourceLabel(json(
                "{\"resource_description\":\"来自描述\"}")));
        assertEquals("来自二级描述", JyingSearchService.resourceLabel(json(
                "{\"description\":\"来自二级描述\"}")));
        // 体积已含于标题不重复拼
        assertEquals("合集 8.2GB", JyingSearchService.resourceLabel(json(
                "{\"title\":\"合集 8.2GB\",\"file_size\":\"8.2GB\"}")));
        assertTrue(JyingSearchService.resourceLabel(json(
                        "{\"title\":\"" + "长".repeat(80) + "\"}")).endsWith("..."),
                "超长标题截断");
        assertEquals("", JyingSearchService.resourceLabel(json("{}")));
    }

    @Test
    void foldPasswordPerDrive() {
        assertEquals("https://pan.quark.cn/s/abc?pwd=qk88",
                JyingSearchService.foldPassword("https://pan.quark.cn/s/abc", "qk88"));
        assertEquals("https://115cdn.com/s/x?password=ab12",
                JyingSearchService.foldPassword("https://115cdn.com/s/x", "ab12"));
        // 已带提取码参数不重复折
        assertEquals("https://pan.quark.cn/s/abc?pwd=old",
                JyingSearchService.foldPassword("https://pan.quark.cn/s/abc?pwd=old", "new"));
        assertEquals("https://pan.quark.cn/s/abc",
                JyingSearchService.foldPassword("https://pan.quark.cn/s/abc", ""));
    }

    @Test
    void authErrorDetection() throws IOException {
        assertTrue(JyingSearchService.isAuthError(json("{\"status\":\"error\",\"message\":\"请先登录\"}")));
        assertTrue(JyingSearchService.isAuthError(json("{\"status\":\"error\",\"message\":\"登录已失效\"}")));
        assertFalse(JyingSearchService.isAuthError(json("{\"status\":\"error\",\"message\":\"资源访问过于频繁\"}")));
        assertFalse(JyingSearchService.isAuthError(json("{\"status\":\"success\",\"message\":\"未登录\"}")));
        assertFalse(JyingSearchService.isAuthError(json("{}")));
    }

    @Test
    void checkCredentialTokenFormProbesProfile() throws IOException {
        JyingSearchService service = new JyingSearchService(settings(null, null, null), MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.endsWith("/api/app/profile/")) {
                    assertEquals("formtok", request.header("X-App-User-Token"));
                    return new Resp(200, List.of(), "{\"user\":{\"username\":\"站内名\",\"points\":10}}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(
                new SiteCredentialCheckRequest("jying", "formtok", "", "", ""));
        assertTrue(result.valid());
        assertTrue(result.message().contains("站内名"));
    }

    @Test
    void checkCredentialAccountFormLogsInAndPersistsSession() throws IOException {
        SettingRepository repository = settings(null, null, null);
        JyingSearchService service = new JyingSearchService(repository, MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.endsWith("/api/app/login/")) {
                    return new Resp(200, List.of(), "{\"status\":\"success\",\"token\":\"tokN\"}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        SiteCredentialCheckResult result = service.checkCredential(
                new SiteCredentialCheckRequest("jying", "", "", "user1", "pw1"));
        assertTrue(result.valid());
        ArgumentCaptor<Setting> captor = ArgumentCaptor.forClass(Setting.class);
        Mockito.verify(repository).save(captor.capture());
        assertEquals(JyingSearchService.SESSION_SETTING, captor.getValue().getName());
        assertEquals("tokN", captor.getValue().getValue());
    }

    @Test
    void dailyCheckinSignsAndDrawsBonusPool() throws IOException {
        SettingRepository repository = settings("fanren", "pw", null);
        AtomicInteger checkins = new AtomicInteger();
        AtomicInteger draws = new AtomicInteger();
        JyingSearchService service = new JyingSearchService(repository, MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.endsWith("/api/app/login/")) {
                    return new Resp(200, List.of(), "{\"status\":\"success\",\"token\":\"tok1\"}");
                }
                if (url.contains("/checkin/do/")) {
                    checkins.incrementAndGet();
                    assertEquals("tok1", request.header("X-App-User-Token"));
                    return new Resp(200, List.of(),
                            "{\"status\":\"success\",\"message\":\"签到成功!获得 5 积分\",\"points_awarded\":5}");
                }
                if (url.contains("/checkin/bonus-pool/draw/")) {
                    draws.incrementAndGet();
                    // pool_id 从奖池接口动态取,不硬编码
                    assertTrue(bodyOf(request).contains("\"pool_id\":4"), bodyOf(request));
                    return new Resp(200, List.of(),
                            "{\"status\":\"success\",\"message\":\"恭喜获得 5 积分\",\"replayed\":false,\"user_points\":10}");
                }
                if (url.contains("/checkin/bonus-pool/")) {
                    return new Resp(200, List.of(),
                            "{\"status\":\"success\",\"pool\":{\"id\": 4, \"can_draw\": true}, \"user_points\": 5}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        service.dailyCheckin();
        assertEquals(1, checkins.get());
        assertEquals(1, draws.get(), "签到成功后须领取签到奖池");
        // 同日第二次:内存日记账命中,不再发任何请求
        service.dailyCheckin();
        assertEquals(1, checkins.get());
        assertEquals(1, draws.get());
    }

    @Test
    void alreadyCheckedInStillDrawsBonus() throws IOException {
        AtomicInteger checkins = new AtomicInteger();
        AtomicInteger draws = new AtomicInteger();
        JyingSearchService service = new JyingSearchService(settings(null, null, "tok9"), MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.contains("/checkin/do/")) {
                    checkins.incrementAndGet();
                    return new Resp(200, List.of(),
                            "{\"status\":\"error\",\"message\":\"今日已签到,明天再来吧~\"}");
                }
                if (url.contains("/checkin/bonus-pool/draw/")) {
                    draws.incrementAndGet();
                    return new Resp(200, List.of(),
                            "{\"status\":\"success\",\"message\":\"已恢复今日抽奖结果\",\"replayed\":true}");
                }
                if (url.contains("/checkin/bonus-pool/")) {
                    return new Resp(200, List.of(), "{\"pool\":{\"id\": 4, \"can_draw\": true}}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        service.dailyCheckin();
        assertEquals(1, checkins.get());
        assertEquals(1, draws.get(), "已签到(如重启丢记账)仍要领奖池,站点幂等重放不发分");
    }

    @Test
    void drawSkippedWhenPoolClosedOrAlreadyDrawn() throws IOException {
        AtomicInteger draws = new AtomicInteger();
        JyingSearchService service = new JyingSearchService(settings(null, null, "tok9"), MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.contains("/checkin/do/")) {
                    return new Resp(200, List.of(), "{\"status\":\"success\",\"message\":\"签到成功!获得 5 积分\"}");
                }
                if (url.contains("/checkin/bonus-pool/")) {
                    // 今日已参与/奖池关闭:can_draw=false
                    return new Resp(200, List.of(),
                            "{\"pool\":{\"id\": 4, \"can_draw\": false, \"can_draw_reason\": \"今日已参与,明天再来\"}}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        service.dailyCheckin();
        assertEquals(0, draws.get());
    }

    @Test
    void checkinAuthErrorReloginsAndRetries() throws IOException {
        SettingRepository repository = settings("fanren", "pw", null);
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger checkins = new AtomicInteger();
        JyingSearchService service = new JyingSearchService(repository, MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.endsWith("/api/app/login/")) {
                    return new Resp(200, List.of(),
                            "{\"status\":\"success\",\"token\":\"tok" + logins.incrementAndGet() + "\"}");
                }
                if (url.contains("/checkin/do/")) {
                    checkins.incrementAndGet();
                    if (!"tok2".equals(request.header("X-App-User-Token"))) {
                        return new Resp(401, List.of(), "{\"status\":\"error\",\"message\":\"登录已失效,请重新登录\"}");
                    }
                    return new Resp(200, List.of(), "{\"status\":\"success\",\"message\":\"签到成功!获得 5 积分\"}");
                }
                if (url.contains("/checkin/bonus-pool/")) {
                    return new Resp(200, List.of(), "{\"pool\":{\"id\": 4, \"can_draw\": false}}");
                }
                throw new IOException("unexpected url: " + url);
            }
        };
        service.dailyCheckin();
        assertEquals(2, logins.get(), "首登 + 签到失效重登");
        assertEquals(2, checkins.get(), "拒收一次 + 重登后放行一次");
    }

    @Test
    void dailyCheckinSilentWithoutCredentials() throws IOException {
        JyingSearchService service = new JyingSearchService(settings(null, null, null), MAPPER) {
            @Override
            protected Resp http(Request request) throws IOException {
                throw new IOException("无凭证不应发任何请求");
            }
        };
        service.dailyCheckin();
    }
}
