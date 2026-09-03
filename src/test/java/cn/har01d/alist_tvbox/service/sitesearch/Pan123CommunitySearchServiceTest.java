package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 123社区搜索源:AJAX 搜索 JSON 解析(url/tid 双回落)/帖子楼层 123 链接提取与规范化
 * (镜像域名收敛 123pan.cn、URL 自带 pwd 优先/key 后窗口提取、# 剥离、非 123 链接不产)/
 * 回复锁判定与自动回复解锁(POST + 成功/失败文案判定、冷却内跳过不发)/双站探活/
 * 无 Cookie 匿名照搜(锁帖跳过)/整链路打桩。
 */
class Pan123CommunitySearchServiceTest {

    private static final String SEARCH_JSON = """
            {"code":0,"message":[
              {"tid":"1234","url":"thread-1234.htm","subject":"凡人修仙传 年番 4K 更新中","user_avatar_url":"a.png"},
              {"tid":"5678","subject":"凡人修仙传 完结全集 2160P"},
              {"tid":"1234","url":"thread-1234.htm","subject":"重复帖按 thread 去重"}
            ]}
            """;

    private static final String THREAD_HTML = """
            <html><body>
              <div class="message break-all">资源:凡人修仙传 年番4 全集<br/>
                https://www.123pan.com/s/abc123 提取码:xy9z<br/>
                https://123912.com/s/def456<br/>
                https://anxia.com/s/shouldDrop 115盘不是123
              </div>
              <div class="message">第二楼:https://share.123pan.cn/123pan/ghi789?pwd=cool</div>
              <div class="message">第三楼:灌水</div>
              <div class="message">第四楼超出前3层:https://www.123pan.com/s/out999</div>
            </body></html>
            """;

    private static final String LOCKED_HTML = """
            <html><body><div class="message">本帖隐藏内容,请回复后再查看</div></body></html>
            """;

    private static AppProperties props() {
        return new AppProperties();
    }

    private static SettingRepository settings(String host, String cookie) {
        SettingRepository repository = Mockito.mock(SettingRepository.class);
        Mockito.when(repository.findById(Mockito.anyString())).thenReturn(Optional.empty());
        if (host != null) {
            Mockito.when(repository.findById(Pan123CommunitySearchService.HOST_SETTING))
                    .thenReturn(Optional.of(new Setting(Pan123CommunitySearchService.HOST_SETTING, host)));
        }
        if (cookie != null) {
            Mockito.when(repository.findById(Pan123CommunitySearchService.COOKIE_SETTING))
                    .thenReturn(Optional.of(new Setting(Pan123CommunitySearchService.COOKIE_SETTING, cookie)));
        }
        return repository;
    }

    @Test
    void parseSearchResultsFallsBackToTidAndDedupes() {
        Pan123CommunitySearchService service =
                new Pan123CommunitySearchService(settings(null, null), props(), new ObjectMapper());
        List<Pan123CommunitySearchService.Card> cards = service.parseSearchResults(SEARCH_JSON);
        assertEquals(2, cards.size());
        assertEquals("1234", cards.get(0).threadId());
        assertEquals("凡人修仙传 年番 4K 更新中", cards.get(0).title());
        // url 缺失回落 tid 字段
        assertEquals("5678", cards.get(1).threadId());
        assertTrue(service.parseSearchResults("not json").isEmpty());
        assertTrue(service.parseSearchResults("").isEmpty());
        assertEquals("9", Pan123CommunitySearchService.threadId("/thread-9.htm?x=1"));
        assertEquals("", Pan123CommunitySearchService.threadId("/forum-2-1.htm"));
    }

    @Test
    void extractLinksNormalizesTo123panCnOnly() {
        Pan123CommunitySearchService service =
                new Pan123CommunitySearchService(settings(null, null), props(), new ObjectMapper());
        List<String> links = service.extractLinks(THREAD_HTML);
        // 123 三条(镜像域名全部收敛 123pan.cn);115 盘链接不产(纯 123 源);第 4 层超出前 3 层不取
        assertEquals(3, links.size());
        assertEquals("https://123pan.cn/s/abc123?pwd=xy9z", links.get(0));
        assertEquals("https://123pan.cn/s/def456", links.get(1));
        assertEquals("https://123pan.cn/123pan/ghi789?pwd=cool", links.get(2));
        assertTrue(links.stream().noneMatch(l -> l.contains("anxia.com")));
        assertTrue(links.stream().noneMatch(l -> l.contains("out999")));
    }

    @Test
    void normalizeShareVariants() {
        String text = "https://www.123pan.com/s/abc123 提取码:xy9z";
        // URL 自带 pwd 优先于窗口提取码
        assertEquals("https://123pan.cn/s/abc123?pwd=fromurl",
                Pan123CommunitySearchService.normalizeShare(
                        "https://123684.com/s/abc123?pwd=fromurl#锚点", text));
        // 裸域(无 scheme)补 https;窗口无提取码则不带 pwd
        assertEquals("https://123pan.cn/s/zzz",
                Pan123CommunitySearchService.normalizeShare("share.123pan.cn/s/zzz", ""));
        // 非 123 链接直接空
        assertEquals("", Pan123CommunitySearchService.normalizeShare("https://pan.quark.cn/s/x", text));
        assertEquals("", Pan123CommunitySearchService.normalizeShare("https://example.com/", text));
    }

    @Test
    void loginWallAndReplyLockDetection() {
        Pan123CommunitySearchService service =
                new Pan123CommunitySearchService(settings(null, null), props(), new ObjectMapper());
        assertTrue(Pan123CommunitySearchService.loginRequired("<html>您需要先登录</html>"));
        assertFalse(Pan123CommunitySearchService.loginRequired(THREAD_HTML));
        // 登录墙页面整帖跳过
        assertTrue(service.extractLinks("<html>用户组无权访问该板块</html>").isEmpty());
        assertTrue(Pan123CommunitySearchService.replyLocked(LOCKED_HTML));
        assertFalse(Pan123CommunitySearchService.replyLocked(THREAD_HTML));
    }

    @Test
    void replySucceededVariants() {
        Pan123CommunitySearchService service =
                new Pan123CommunitySearchService(settings(null, null), props(), new ObjectMapper());
        assertTrue(service.replySucceeded("{\"code\":0,\"message\":\"回复成功\"}"));
        assertTrue(service.replySucceeded("{\"code\":1,\"message\":\"发布成功\"}"));
        assertFalse(service.replySucceeded("{\"code\":1,\"message\":\"发言太快,请稍后再试\"}"));
        assertTrue(service.replySucceeded("非 JSON 但含 成功"));
        // 无成功也无失败标记的非 JSON 按成功论(py 宽松口径)
        assertTrue(service.replySucceeded("ok whatever"));
        assertFalse(service.replySucceeded("请先登录后发帖"));
    }

    @Test
    void replyCooldownSkipsWithoutRequest() {
        AtomicInteger posts = new AtomicInteger();
        Pan123CommunitySearchService primed =
                new Pan123CommunitySearchService(settings(null, "bbs_sid=1; bbs_token=2"), props(), new ObjectMapper()) {
                    @Override
                    protected Resp http(Request request) throws IOException {
                        posts.incrementAndGet();
                        return new Resp(200, List.of(), "{\"code\":0}");
                    }
                };
        assertTrue(primed.replyToUnlock("https://123panfx.com", "bbs_sid=1", "https://123panfx.com/thread-1.htm", "1"));
        assertEquals(1, posts.get());
        // 同实例立即再回复:冷却内直接跳过,不等待也不发请求(py 的 sleep 等够不搬)
        long start = System.currentTimeMillis();
        assertFalse(primed.replyToUnlock("https://123panfx.com", "bbs_sid=1", "https://123panfx.com/thread-2.htm", "2"));
        assertTrue(System.currentTimeMillis() - start < 2000, "冷却跳过必须立即返回,不阻塞线程");
        assertEquals(1, posts.get(), "冷却内零请求");
    }

    @Test
    void searchFullChainAnonymousSkipsLockedThread() {
        // 无 Cookie 匿名:搜索 + 详情照常,锁帖直接跳过(不回复)
        AtomicInteger posts = new AtomicInteger();
        Pan123CommunitySearchService service =
                new Pan123CommunitySearchService(settings("https://123panfx.com", null), props(), new ObjectMapper()) {
                    @Override
                    protected Resp http(Request request) throws IOException {
                        String url = request.url().toString();
                        if (url.contains("/search.htm")) {
                            return new Resp(200, List.of(), SEARCH_JSON);
                        }
                        if (url.endsWith("/thread-1234.htm")) {
                            return new Resp(200, List.of(), THREAD_HTML);
                        }
                        if (url.endsWith("/thread-5678.htm")) {
                            return new Resp(200, List.of(), LOCKED_HTML);
                        }
                        // 意外请求(回复 POST/探活等)计数,匿名路径应为零
                        posts.incrementAndGet();
                        return new Resp(200, List.of(), "");
                    }
                };
        List<Message> result = service.search("凡人修仙传");
        // 帖1 三条 123;帖2 锁帖无 Cookie 跳过
        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(m -> "3".equals(m.getType())));
        assertTrue(result.stream().allMatch(m -> m.getLink().startsWith("https://123pan.cn/")));
        assertEquals("123社区", result.get(0).getChannel());
        assertEquals(0, posts.get(), "匿名不触达任何回复 POST");
        assertTrue(service.search("").isEmpty());
    }

    @Test
    void searchUnlocksLockedThreadWithCookie() {
        AtomicInteger detailFetches = new AtomicInteger();
        Pan123CommunitySearchService service =
                new Pan123CommunitySearchService(settings("https://123panfx.com", "bbs_sid=1; bbs_token=2"),
                        props(), new ObjectMapper()) {
                    @Override
                    protected Resp http(Request request) throws IOException {
                        String url = request.url().toString();
                        if (url.contains("/search.htm")) {
                            return new Resp(200, List.of(),
                                    "{\"message\":[{\"tid\":\"42\",\"subject\":\"凡人修仙传 全集\"}]}");
                        }
                        if (url.contains("post-create-42")) {
                            return new Resp(200, List.of(), "{\"code\":0,\"message\":\"回复成功\"}");
                        }
                        if (url.endsWith("/thread-42.htm")) {
                            // 首取锁页,回复后重取到内容
                            return new Resp(200, List.of(),
                                    detailFetches.incrementAndGet() == 1 ? LOCKED_HTML : THREAD_HTML);
                        }
                        return new Resp(200, List.of(), "");
                    }
                };
        List<Message> result = service.search("凡人修仙传");
        assertEquals(3, result.size(), "回复解锁后重取应提取到链接");
        assertEquals(2, detailFetches.get());
    }

    @Test
    void searchFailureIsSilent() {
        Pan123CommunitySearchService service =
                new Pan123CommunitySearchService(settings("https://123panfx.com", null), props(), new ObjectMapper()) {
                    @Override
                    protected Resp http(Request request) throws IOException {
                        throw new IOException("timeout");
                    }
                };
        assertTrue(service.search("凡人修仙传").isEmpty());
    }
}
