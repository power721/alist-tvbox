package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 123臻藏搜索源:搜索卡片解析(站名后缀剥离)/详情正文链接产出(123 折码 pwd=、115
 * password=、磁力原样、付费/推广块清单剔除)/golink 解码/评论锁自动解锁(POST 302 后
 * 重取正文)/无 Cookie 静默关闭/整链路搜索(搜索 + 详情打桩)。
 */
class ZhenCangSearchServiceTest {

    private static final String SEARCH_HTML = """
            <html><body>
              <div class="posts-item"><h2 class="item-heading">
                <a href="https://123.qsxy.top/12345.html">凡人修仙传 - 123云盘·臻藏阁</a></h2></div>
              <div class="posts-item"><h2 class="item-heading">
                <a href="/12345.html">重复卡片应被去重</a></h2></div>
              <div class="posts-item"><h2 class="item-heading">
                <a href="/67890.html">凡人修仙传 年番 - 臻藏阁</a></h2></div>
            </body></html>
            """;

    private static final String DETAIL_HTML = """
            <html><body><div class="wp-posts-content">
              <p>本资源由臻藏阁分享,提取码:ab12,链接永久有效</p>
              <p>123云盘 <a href="https://www.123pan.com/s/abc123">https://www.123pan.com/s/abc123</a> 第01-12集 4K国语</p>
              <p>夸克备用 <a href="https://pan.quark.cn/s/xyz789">点我跳转</a></p>
              <p>115 <a href="https://115.com/s/abc115">115网盘</a></p>
              <p>磁力:magnet:?xt=urn:btih:15665de833a3365e85a9be1c3284abc658091257&amp;dn=%E5%87%A1%E4%BA%BA</p>
              <p>电驴 ed2k://|file|fanren.mkv|123456789|ABCDEF0123456789ABCDEF0123456789|h=/</p>
              <p><a href="https://www.123pan.com/outsidePay">付费升级</a></p>
              <p><a href="https://example.com/x">外站无关链接</a></p>
            </div></body></html>
            """;

    private static final String COMMENT_LOCKED_HTML = """
            <html><body>
              <div class="wp-posts-content">请评论后刷新页面查看
                <div class="hidden-box reply-show"></div>
              </div>
              <form>
                <input type="hidden" name="comment_post_ID" value="12345"/>
                <input type="hidden" name="comment_parent" value="0"/>
                <input type="hidden" name="_wpnonce" value="wp9x"/>
              </form>
            </body></html>
            """;

    private static final String UNLOCKED_HTML = """
            <html><body><div class="wp-posts-content">
              <p><a href="https://www.123pan.com/s/unlock1">https://www.123pan.com/s/unlock1</a> 第01-08集</p>
            </div></body></html>
            """;

    private static AppProperties props() {
        return new AppProperties();
    }

    private static SettingRepository emptySettings() {
        return settings(null, null);
    }

    private static SettingRepository settings(String cookie) {
        return settings(null, cookie);
    }

    private static SettingRepository settings(String host, String cookie) {
        SettingRepository repository = Mockito.mock(SettingRepository.class);
        Mockito.when(repository.findById(Mockito.anyString())).thenReturn(Optional.empty());
        if (host != null) {
            Mockito.when(repository.findById(ZhenCangSearchService.HOST_SETTING))
                    .thenReturn(Optional.of(new Setting(ZhenCangSearchService.HOST_SETTING, host)));
        }
        if (cookie != null) {
            Mockito.when(repository.findById(ZhenCangSearchService.COOKIE_SETTING))
                    .thenReturn(Optional.of(new Setting(ZhenCangSearchService.COOKIE_SETTING, cookie)));
        }
        return repository;
    }

    @Test
    void parseCardsStripsSiteSuffixAndDedupes() {
        ZhenCangSearchService service = new ZhenCangSearchService(emptySettings(), props());
        List<ZhenCangSearchService.Card> cards = service.parseCards(SEARCH_HTML);
        assertEquals(2, cards.size());
        assertEquals("12345", cards.get(0).postId());
        assertEquals("凡人修仙传", cards.get(0).title());
        assertEquals("凡人修仙传 年番", cards.get(1).title());
        assertTrue(service.parseCards("<html><body>没有找到相关文章</body></html>").isEmpty());
        assertTrue(service.parseCards("").isEmpty());
    }

    @Test
    void parseDetailFoldsCodesAndFiltersNoise() {
        ZhenCangSearchService service = new ZhenCangSearchService(emptySettings(), props());
        List<Message> out = new ArrayList<>();
        service.parseDetail(DETAIL_HTML,
                new ZhenCangSearchService.Card("12345", "凡人修仙传"), out, new HashSet<>());
        // 123 + 夸克 + 115 + 磁力 + ed2k;outsidePay(付费)与外站链接不产出
        assertEquals(5, out.size());
        Message pan123 = out.get(0);
        assertEquals("3", pan123.getType());
        assertEquals("https://www.123pan.com/s/abc123?pwd=ab12", pan123.getLink());
        assertEquals("123臻藏", pan123.getChannel());
        assertEquals("凡人修仙传", pan123.getName());
        // 所在块文本(剥链接本身)并入 content,供集数分组打分
        assertTrue(pan123.getContent().contains("第01-12集"));
        Message quark = out.get(1);
        assertEquals("5", quark.getType());
        assertEquals("https://pan.quark.cn/s/xyz789?pwd=ab12", quark.getLink());
        Message pan115 = out.get(2);
        assertEquals("8", pan115.getType());
        assertEquals("https://115.com/s/abc115?password=ab12", pan115.getLink());
        // 磁力/ed2k 原样(提取码不折),由定向集闸门统一裁决
        assertEquals("magnet", out.get(3).getType());
        assertTrue(out.get(3).getLink().startsWith("magnet:?xt=urn:btih:15665de"));
        assertEquals("ed2k", out.get(4).getType());
        assertTrue(out.get(4).getLink().startsWith("ed2k://|file|"));
        assertTrue(out.stream().noneMatch(m -> m.getLink().contains("outsidePay")));
        assertTrue(out.stream().noneMatch(m -> m.getLink().contains("example.com")));
    }

    @Test
    void parseDetailWithoutContentNodeIsSilent() {
        ZhenCangSearchService service = new ZhenCangSearchService(emptySettings(), props());
        List<Message> out = new ArrayList<>();
        service.parseDetail("<html><body><p>错误页</p></body></html>",
                new ZhenCangSearchService.Card("1", "t"), out, new HashSet<>());
        assertTrue(out.isEmpty());
    }

    @Test
    void normalizeLinkDecodesGolinkAndStripsPunctuation() {
        // %3D 编码的 padding 与剥掉 padding 的变体都要解出
        assertEquals("https://pan.quark.cn/s/yzz",
                ZhenCangSearchService.normalizeLink("https://123.qsxy.top/?golink=aHR0cHM6Ly9wYW4ucXVhcmsuY24vcy95eno%3D"));
        assertEquals("https://pan.quark.cn/s/yzz",
                ZhenCangSearchService.normalizeLink("https://123.qsxy.top/?golink=aHR0cHM6Ly9wYW4ucXVhcmsuY24vcy95eno"));
        // URL-safe 变体:-/_ 还原标准字母表
        assertEquals("https://www.123pan.com/s/a-b",
                ZhenCangSearchService.normalizeLink("https://123.qsxy.top/go?golink=aHR0cHM6Ly93d3cuMTIzcGFuLmNvbS9zL2EtYg"));
        assertEquals("https://pan.quark.cn/s/abc",
                ZhenCangSearchService.normalizeLink("https://pan.quark.cn/s/abc,"));
        assertEquals("", ZhenCangSearchService.normalizeLink("https://www.123pan.com/outsidePay"));
        assertEquals("", ZhenCangSearchService.normalizeLink("不是链接"));
    }

    @Test
    void accessCodeAndCookieNormalization() {
        assertEquals("ab12", ZhenCangSearchService.extractAccessCode("提取码:ab12"));
        assertEquals("xy9z", ZhenCangSearchService.extractAccessCode("访问码:xy9z"));
        assertEquals("1234", ZhenCangSearchService.extractAccessCode("密码 1234"));
        assertEquals("", ZhenCangSearchService.extractAccessCode("没有码"));
        assertEquals("a=b; c=d",
                ZhenCangSearchService.normalizeCookie("Cookie: a=b;\r\n junk\nc=d;;"));
        assertEquals("", ZhenCangSearchService.normalizeCookie("  "));
    }

    @Test
    void commentUnlockPostsAndRefetches() {
        AtomicInteger detailFetches = new AtomicInteger();
        ZhenCangSearchService service = new ZhenCangSearchService(emptySettings(), props()) {
            @Override
            protected Resp http(Request request, boolean followRedirects) throws IOException {
                String url = request.url().toString();
                if (url.contains("wp-comments-post.php")) {
                    assertEquals("感谢分享资源", request.body() instanceof okhttp3.FormBody form
                            ? form.value(0) : "");
                    return new Resp(302, List.of(), "");
                }
                return new Resp(200, List.of(),
                        detailFetches.incrementAndGet() == 1 ? UNLOCKED_HTML : COMMENT_LOCKED_HTML);
            }
        };
        String html = service.maybeUnlockByComment("https://123.qsxy.top", "wp=1",
                "https://123.qsxy.top/12345.html", COMMENT_LOCKED_HTML);
        assertTrue(html.contains("unlock1"), "评论 302 后应重取到解锁正文");
        assertEquals(1, detailFetches.get(), "重取一次详情页");
    }

    @Test
    void commentUnlockSkippedWhenUnlockedOrNoForm() {
        ZhenCangSearchService service = new ZhenCangSearchService(emptySettings(), props()) {
            @Override
            protected Resp http(Request request, boolean followRedirects) throws IOException {
                throw new IOException("不应发任何请求");
            }
        };
        assertEquals(DETAIL_HTML,
                service.maybeUnlockByComment("https://123.qsxy.top", "wp=1", "https://x/1.html", DETAIL_HTML));
        // 无表单(缺 _wpnonce)的评论锁不硬闯
        String noForm = COMMENT_LOCKED_HTML.replace("<input type=\"hidden\" name=\"_wpnonce\" value=\"wp9x\"/>", "");
        assertEquals(noForm,
                service.maybeUnlockByComment("https://123.qsxy.top", "wp=1", "https://x/1.html", noForm));
    }

    @Test
    void searchFullChainWithStubbedHttp() {
        ZhenCangSearchService service = new ZhenCangSearchService(settings("wordpress_logged_in=t"), props()) {
            @Override
            protected Resp http(Request request, boolean followRedirects) throws IOException {
                String url = request.url().toString();
                if (url.contains("s=")) {
                    assertTrue(url.startsWith("https://123.qsxy.top/?s="));
                    return new Resp(200, List.of(), SEARCH_HTML);
                }
                if (url.endsWith("/12345.html")) {
                    return new Resp(200, List.of(), DETAIL_HTML);
                }
                // 第二个卡片:正文无网盘链接
                return new Resp(200, List.of(),
                        "<html><body><div class=\"wp-posts-content\"><p>暂无资源</p></div></body></html>");
            }
        };
        List<Message> result = service.search("凡人修仙传");
        assertEquals(5, result.size());
        assertTrue(result.stream().anyMatch(m -> "3".equals(m.getType())));
        assertTrue(result.stream().anyMatch(m -> "magnet".equals(m.getType())));
        assertTrue(service.search("").isEmpty());
    }

    @Test
    void searchDisabledWithoutCookie() {
        ZhenCangSearchService service = new ZhenCangSearchService(emptySettings(), props()) {
            @Override
            protected Resp http(Request request, boolean followRedirects) throws IOException {
                throw new IOException("无 Cookie 不应发任何请求");
            }
        };
        assertTrue(service.search("凡人修仙传").isEmpty());
    }

    @Test
    void searchFailureIsSilent() {
        ZhenCangSearchService service = new ZhenCangSearchService(settings("wordpress_logged_in=t"), props()) {
            @Override
            protected Resp http(Request request, boolean followRedirects) throws IOException {
                throw new IOException("timeout");
            }
        };
        assertTrue(service.search("凡人修仙传").isEmpty());
    }
}
