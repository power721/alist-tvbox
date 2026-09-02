package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
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
 * 夸父搜索源:帖子列表解析(置顶跳过/屏蔽词过滤/去重)/链接提取四级回退(alert 带码 →
 * alert 纯码配对 a[href] → 整页正则(锁贴 JSON-LD 泄漏,123 按 key 回原文) → message
 * a[href] 补码)/提取码折 pwd=(115 password=)/Cookie 失效判定/回复解锁(冷却跳过、
 * 登录标记拒)/整链路打桩(匿名抓锁贴泄漏链接)。
 */
class KuafuSearchServiceTest {

    private static final String SEARCH_HTML = """
            <html><body><ul class="threadlist media threads">
              <li><i data-placement="top"></i><div class="media"><div class="subject">
                <a href="thread-99881.htm">置顶公告:社区规则</a></div></div></li>
              <li><div class="media"><div class="subject">
                <a href="thread-102269.htm">凡人修仙传 年番 4K 更新至178集</a>
                <a class="badge badge-pill">夸克</a></div></div></li>
              <li><div class="media"><div class="subject">
                <a href="thread-102270.htm">性感写真福利合集</a></div></div></li>
              <li><div class="media"><div class="subject">
                <a href="thread-102269.htm">重复帖按 thread 去重</a></div></div></li>
            </ul></body></html>
            """;

    /** 已解锁帖:alert 块 = 链接 + 提取码(Level ①)。 */
    private static final String DETAIL_HTML = """
            <html><body>
              <div class="message"><div><div class="alert alert-info">
                网盘链接:https://pan.quark.cn/s/abc123 提取码:qk88,请您务必转存保存后再进行下载，以免消耗分享者的免登流量
              </div></div></div>
              <div class="message">第二楼:https://www.123684.com/s/zzz999</div>
            </body></html>
            """;

    /** 锁贴:alert 是提示语(立即回复),真实链接泄漏在 JSON-LD(Level ③ 匿名可抓)。 */
    private static final String LOCKED_LEAK_HTML = """
            <html><body>
              <div class="message"><div class="alert alert-warning">立即回复查看资源</div></div>
              <script type="application/ld+json">{"ttreply":"pan.quark.cn/s/leak77 drive.uc.cn/s/uc88 https://123912.com/s/k12345"}</script>
            </body></html>
            """;

    /** 锁贴无泄漏:alert 提示语且正文无任何链接(回复解锁前真拿不到)。 */
    private static final String LOCKED_NO_LEAK_HTML = """
            <html><body>
              <div class="message"><div class="alert alert-warning">立即回复查看资源</div></div>
            </body></html>
            """;

    /** Level ②:alert 只有纯提取码,链接在 a[href]。 */
    private static final String CODE_ONLY_HTML = """
            <html><body>
              <div class="message"><div class="alert alert-info">6y8a</div></div>
              <div class="message"><p><a href="https://www.alipan.com/s/al111">阿里盘</a></p></div>
            </body></html>
            """;

    private static final String LOGIN_EXPIRED_HTML = """
            <html><body><div class="message"><div class="alert">待登录</div></div></body></html>
            """;

    private static AppProperties props() {
        return new AppProperties();
    }

    private static SettingRepository settings(String host, String cookie) {
        SettingRepository repository = Mockito.mock(SettingRepository.class);
        Mockito.when(repository.findById(Mockito.anyString())).thenReturn(Optional.empty());
        if (host != null) {
            Mockito.when(repository.findById(KuafuSearchService.HOST_SETTING))
                    .thenReturn(Optional.of(new Setting(KuafuSearchService.HOST_SETTING, host)));
        }
        if (cookie != null) {
            Mockito.when(repository.findById(KuafuSearchService.COOKIE_SETTING))
                    .thenReturn(Optional.of(new Setting(KuafuSearchService.COOKIE_SETTING, cookie)));
        }
        return repository;
    }

    @Test
    void parseCardsSkipsPinnedAndBlockedAndDedupes() {
        KuafuSearchService service = new KuafuSearchService(settings(null, null), props());
        List<KuafuSearchService.Card> cards = service.parseCards(SEARCH_HTML);
        // 置顶跳过、屏蔽词(性感/写真/福利)丢弃、重复去重
        assertEquals(1, cards.size());
        assertEquals("102269", cards.get(0).threadId());
        assertEquals("凡人修仙传 年番 4K 更新至178集", cards.get(0).title());
        assertTrue(service.parseCards("<html><body>没有搜索到</body></html>").isEmpty());
        assertTrue(service.parseCards("").isEmpty());
    }

    @Test
    void extractAlertLinksFoldCode() {
        KuafuSearchService service = new KuafuSearchService(settings(null, null), props());
        List<KuafuSearchService.Extracted> links = service.extractLinks(DETAIL_HTML);
        // Level ① 命中即止:alert 的夸克链接(码折 ?pwd=),第二楼的 123 不进(上级非空)
        assertEquals(1, links.size());
        assertEquals("https://pan.quark.cn/s/abc123", links.get(0).link());
        assertEquals("qk88", links.get(0).code());
        assertEquals("https://pan.quark.cn/s/abc123?pwd=qk88", KuafuSearchService.foldPassword(links.get(0).link(), links.get(0).code()));
        // 115 特判 password=
        assertEquals("https://115cdn.com/s/x?password=ab12",
                KuafuSearchService.foldPassword("https://115cdn.com/s/x", "ab12"));
    }

    @Test
    void extractPairsBareCodeWithAnchor() {
        KuafuSearchService service = new KuafuSearchService(settings(null, null), props());
        List<KuafuSearchService.Extracted> links = service.extractLinks(CODE_ONLY_HTML);
        // Level ②:alert 纯码(无前缀)剥非字母数字后配对 a[href] 的阿里链接
        assertEquals(1, links.size());
        assertEquals("https://www.alipan.com/s/al111", links.get(0).link());
        assertEquals("6y8a", links.get(0).code());
    }

    @Test
    void extractRegexFallbackRebuildsCanonicalLinks() {
        KuafuSearchService service = new KuafuSearchService(settings(null, null), props());
        List<KuafuSearchService.Extracted> links = service.extractLinks(LOCKED_LEAK_HTML);
        // Level ③:锁贴泄漏在 JSON-LD,规范重建(123 按 key 回原文匹配完整 URL)
        assertEquals(3, links.size());
        assertEquals("https://pan.quark.cn/s/leak77", links.get(0).link());
        assertEquals("https://drive.uc.cn/s/uc88", links.get(1).link());
        assertEquals("https://123912.com/s/k12345", links.get(2).link());
    }

    @Test
    void levelFourScansAnchorsWithParentCode() {
        // 无 alert、正则族全空(123pan.cn 不在 123xxx.com 正则内):div.message 的 a[href]
        // 网盘域 + 父文本码(Level ④)
        String html = """
                <html><body><div class="message">
                  <p>全集打包 <a href="https://www.123pan.cn/s/p333">123盘</a> 提取码:pn11</p>
                </div></body></html>
                """;
        KuafuSearchService service = new KuafuSearchService(settings(null, null), props());
        List<KuafuSearchService.Extracted> links = service.extractLinks(html);
        assertEquals(1, links.size());
        assertEquals("https://www.123pan.cn/s/p333", links.get(0).link());
        assertEquals("pn11", links.get(0).code());
    }

    @Test
    void cookieExpiredAndBlockedHelpers() {
        assertTrue(KuafuSearchService.cookieExpired(LOGIN_EXPIRED_HTML));
        assertFalse(KuafuSearchService.cookieExpired(DETAIL_HTML));
        // Cookie 失效帖整体跳过
        assertTrue(new KuafuSearchService(settings(null, null), props()).extractLinks(LOGIN_EXPIRED_HTML).isEmpty());
        assertTrue(KuafuSearchService.blocked("网红私拍合集"));
        assertFalse(KuafuSearchService.blocked("凡人修仙传"));
        assertEquals("https://pan.quark.cn/s/x", KuafuSearchService.fixScheme("pan.quark.cn/s/x"));
        assertEquals("裸文本", KuafuSearchService.fixScheme("裸文本"));
        assertEquals("ab12", KuafuSearchService.passwordOf("访问码:ab12"));
    }

    @Test
    void replyCooldownSkipsAndLoginRejected() {
        AtomicInteger posts = new AtomicInteger();
        KuafuSearchService primed = new KuafuSearchService(settings(null, "bbs_sid=1; bbs_token=2"), props()) {
            @Override
            protected Resp http(Request request) throws IOException {
                posts.incrementAndGet();
                return new Resp(200, List.of(), "ok");
            }
        };
        assertTrue(primed.replyToUnlock("https://www.kfzy.net", "bbs_sid=1", "https://www.kfzy.net/thread-1.htm", "1"));
        assertEquals(1, posts.get());
        // 冷却内直接跳过,零请求零阻塞
        long start = System.currentTimeMillis();
        assertFalse(primed.replyToUnlock("https://www.kfzy.net", "bbs_sid=1", "https://www.kfzy.net/thread-2.htm", "2"));
        assertTrue(System.currentTimeMillis() - start < 2000);
        assertEquals(1, posts.get());
        // 登录标记拒绝
        KuafuSearchService stale = new KuafuSearchService(settings(null, "bbs_sid=1"), props()) {
            @Override
            protected Resp http(Request request) throws IOException {
                return new Resp(200, List.of(), "请先登录后发帖");
            }
        };
        assertFalse(stale.replyToUnlock("https://www.kfzy.net", "bbs_sid=1", "https://www.kfzy.net/thread-3.htm", "3"));
    }

    @Test
    void searchFullChainAnonymousCatchesLeakedLinks() {
        // 无 Cookie 匿名:搜索 + 详情照常,锁贴靠 Level ③ 正则抓泄漏链接(不回复)
        AtomicInteger posts = new AtomicInteger();
        KuafuSearchService service = new KuafuSearchService(settings("https://www.kfzy.net", null), props()) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.contains("/search-")) {
                    return new Resp(200, List.of(), SEARCH_HTML);
                }
                if (url.endsWith("/thread-102269.htm")) {
                    return new Resp(200, List.of(), LOCKED_LEAK_HTML);
                }
                posts.incrementAndGet();
                return new Resp(200, List.of(), "");
            }
        };
        List<Message> result = service.search("凡人修仙传");
        // 锁贴泄漏:夸克 + UC + 123 三条(123 链接盘型 "3")
        assertEquals(3, result.size());
        assertTrue(result.stream().anyMatch(m -> "5".equals(m.getType())));
        assertTrue(result.stream().anyMatch(m -> "7".equals(m.getType())));
        assertTrue(result.stream().anyMatch(m -> "3".equals(m.getType())));
        assertEquals("夸父", result.get(0).getChannel());
        assertEquals(0, posts.get(), "匿名不触达任何回复 POST");
        assertTrue(service.search("").isEmpty());
    }

    @Test
    void searchUnlocksLockedThreadWithCookie() {
        AtomicInteger detailFetches = new AtomicInteger();
        KuafuSearchService service = new KuafuSearchService(settings("https://www.kfzy.net", "bbs_sid=1; bbs_token=2"), props()) {
            @Override
            protected Resp http(Request request) throws IOException {
                String url = request.url().toString();
                if (url.contains("/search-")) {
                    return new Resp(200, List.of(), SEARCH_HTML);
                }
                if (url.contains("post-create-102269")) {
                    return new Resp(200, List.of(), "{\"code\":0}");
                }
                if (url.endsWith("/thread-102269.htm")) {
                    // 首取无泄漏(alert 提示语、链接真空)→ 回复解锁 → 重取到 alert 主链接
                    return new Resp(200, List.of(),
                            detailFetches.incrementAndGet() == 1 ? LOCKED_NO_LEAK_HTML : DETAIL_HTML);
                }
                return new Resp(200, List.of(), "");
            }
        };
        List<Message> result = service.search("凡人修仙传");
        assertEquals(1, result.size());
        assertTrue(result.get(0).getLink().endsWith("?pwd=qk88"));
        assertEquals(2, detailFetches.get());
    }

    @Test
    void searchFailureIsSilent() {
        KuafuSearchService service = new KuafuSearchService(settings("https://www.kfzy.net", null), props()) {
            @Override
            protected Resp http(Request request) throws IOException {
                throw new IOException("timeout");
            }
        };
        assertTrue(service.search("凡人修仙传").isEmpty());
    }
}
