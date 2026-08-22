package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩偶聚合搜索源:卡片/详情解析、盘型识别、域名 failover 与粘滞、监控域名刷新、跨站去重。
 */
class WanouSearchServiceTest {

    private static final String SEARCH_HTML = """
            <html><body><div class="module-items">
              <div class="module-search-item">
                <a class="video-serial" href="/voddetail/1.html" title="难哄4K"></a>
                <div class="module-item-pic"><img alt="难哄" data-src="/pic/1.jpg"></div>
                <div class="module-item-text">全40集 夸克</div>
              </div>
              <div class="module-search-item">
                <a class="video-serial" href="/voddetail/1.html" title="难哄重复卡片"></a>
              </div>
              <div class="module-search-item">
                <a href="/voddetail/2.html"></a>
              </div>
              <div class="module-search-item">
                <a class="video-serial" href="/voddetail/3.html" title="难哄剧场版"></a>
                <div class="module-item-text">1080P</div>
              </div>
            </div></body></html>
            """;

    private static final String DETAIL_HTML = """
            <html><body>
              <div class="module-row-info"><p>https://pan.quark.cn/s/abc123 提取码：x7kp</p></div>
              <div class="module-row-info"><p>hhttps://pan.baidu.com/s/1AbCdEfGhIjKlMnOpQrStU</p></div>
              <div class="module-row-info"><p>本资源由站长整理</p></div>
              <div class="module-row-info"><p>https://www.123pan.com/s/xyz-def?pwd=1a2b</p></div>
            </body></html>
            """;

    private static final String HUBAN_DETAIL_HTML = """
            <html><body>
              <div class="module-row-info">
                <div class="module-row-text" data-clipboard-text="https://115.com/s/abc123?password=k3m9#资源"></div>
              </div>
              <div class="module-row-info">
                <div class="module-row-text" data-clipboard-text="磁力 magnets are ignored"></div>
              </div>
            </body></html>
            """;

    private static AppProperties props() {
        AppProperties props = new AppProperties();
        props.getSubscription().setWanouMonitorUrl("");
        return props;
    }

    @Test
    void normalizeTitleStripsNoise() {
        assertEquals("难哄", WanouSearchService.normalizeTitle(" 难哄4K "));
        assertEquals("斗罗大陆", WanouSearchService.normalizeTitle("斗罗大陆·1080P（木偶）"));
        assertEquals("thelastofus", WanouSearchService.normalizeTitle("The.Last.of.Us"));
    }

    @Test
    void matchKeywordToleratesSeasonAndQualityMarkers() {
        WanouSearchService service = new WanouSearchService(props(), new ObjectMapper());
        assertTrue(service.matchKeyword("难哄4K", "难哄"));
        assertTrue(service.matchKeyword("斗罗大陆2绝世唐门", "斗罗大陆 第二季"));
        assertTrue(service.matchKeyword("难哄", "难哄 第12集 更新至12集"));
        assertTrue(service.matchKeyword("难哄(2025)", "难哄 2025"));
    }

    @Test
    void parseSearchCards() {
        WanouSearchService service = new WanouSearchService(props(), new ObjectMapper());
        List<WanouSearchService.Card> cards = service.parseSearchCards(SEARCH_HTML);
        // 重复 href 去重、无标题卡片跳过
        assertEquals(2, cards.size());
        assertEquals("/voddetail/1.html", cards.get(0).href());
        assertEquals("难哄4K", cards.get(0).title());
        assertEquals("全40集 夸克", cards.get(0).remarks());
        // 无 video-serial@title 时回退 img@alt
        assertEquals("难哄", service.parseSearchCards("""
                <div class="module-search-item"><a href="/voddetail/1.html"></a>
                <img alt="难哄"><div class="module-item-text">HD</div></div>
                """).get(0).title());
    }

    @Test
    void parseDetailPanUrlsStandard() {
        WanouSearchService service = new WanouSearchService(props(), new ObjectMapper());
        List<String> urls = service.parseDetailPanUrls(WanouSearchService.siteById("muou"), DETAIL_HTML);
        assertEquals(3, urls.size());
        assertEquals("https://pan.quark.cn/s/abc123?password=x7kp", urls.get(0));
        // hhttps:// 复制瑕疵修正
        assertEquals("https://pan.baidu.com/s/1AbCdEfGhIjKlMnOpQrStU", urls.get(1));
        // 已带 pwd 参数不再追加 password
        assertEquals("https://www.123pan.com/s/xyz-def?pwd=1a2b", urls.get(2));
        assertEquals("5", Message.parseType(urls.get(0)));
        assertEquals("10", Message.parseType(urls.get(1)));
        assertEquals("3", Message.parseType(urls.get(2)));
    }

    @Test
    void parseDetailPanUrlsClipboard() {
        WanouSearchService service = new WanouSearchService(props(), new ObjectMapper());
        List<String> urls = service.parseDetailPanUrls(WanouSearchService.siteById("huban"), HUBAN_DETAIL_HTML);
        assertEquals(1, urls.size());
        assertEquals("https://115.com/s/abc123?password=k3m9", urls.get(0));
        assertEquals("8", Message.parseType(urls.get(0)));
    }

    @Test
    void failoverSkipsDeadDomainAndSticksToWinner() throws IOException {
        List<String> calls = new ArrayList<>();
        WanouSearchService service = new WanouSearchService(props(), new ObjectMapper()) {
            @Override
            protected String fetch(String url, int timeoutSeconds) throws IOException {
                calls.add(url);
                if (url.startsWith("https://www.muou.asia")) {
                    return "<html>ok</html>";
                }
                throw new IOException("boom");
            }
        };
        WanouSearchService.Site muou = WanouSearchService.siteById("muou");
        assertEquals("<html>ok</html>", service.requestWithFailover(muou, "/index.php/vod/search/page/1/wd/x.html"));
        // 首选域名失败后落到第二域名
        assertTrue(calls.get(0).startsWith("https://www.muou.site/"));
        assertTrue(calls.get(1).startsWith("https://www.muou.asia/"));
        // 成功域名粘滞:下一次直接从 muou.asia 起步
        calls.clear();
        service.requestWithFailover(muou, "/index.php/vod/search/page/1/wd/x.html");
        assertTrue(calls.get(0).startsWith("https://www.muou.asia/"));
    }

    @Test
    void monitorRefreshReordersDomains() throws IOException {
        AppProperties props = props();
        props.getSubscription().setWanouMonitorUrl("https://monitor.test/api/data");
        List<String> calls = new ArrayList<>();
        WanouSearchService service = new WanouSearchService(props, new ObjectMapper()) {
            @Override
            protected String fetch(String url, int timeoutSeconds) throws IOException {
                if (url.equals("https://monitor.test/api/data")) {
                    return """
                            {"sites":{"玩偶":{"site_name":"玩偶","status":"success","best_url":"https://fresh1.example","urls":[
                               {"url":"https://fresh1.example","latency":0.1,"has_keyword":true},
                               {"url":"https://dead.example","latency":null,"has_keyword":false,"error_type":"http_error"}]},
                              "欧哥":{"site_name":"欧哥","status":"success","best_url":"https://og1.example","urls":[
                               {"url":"https://og1.example","latency":0.3,"has_keyword":true}]}}}}
                            """;
                }
                calls.add(url);
                if (url.startsWith("https://fresh1.example")) {
                    return "<html>ok</html>";
                }
                throw new IOException("boom");
            }
        };
        service.refreshDomainsIfNeeded();
        assertEquals("<html>ok</html>",
                service.requestWithFailover(WanouSearchService.siteById("wanou"), "/vodsearch/-------------.html?wd=x&page=1"));
        // 监控可达域名排在静态种子之前,失败域名垫底
        assertEquals("https://fresh1.example/vodsearch/-------------.html?wd=x&page=1", calls.get(0));
    }

    @Test
    void searchAggregatesSitesAndDedupesByLink() {
        AppProperties props = props();
        props.getSubscription().setWanouMaxDetailPages(2);
        WanouSearchService service = new WanouSearchService(props, new ObjectMapper()) {
            @Override
            protected String fetch(String url, int timeoutSeconds) throws IOException {
                if (url.startsWith("https://tv.yydsys.top") && url.contains("/vod/search/")) {
                    return SEARCH_HTML;
                }
                if (url.startsWith("https://woggpan.xxooo.cf") && url.contains("/vodsearch/")) {
                    return """
                            <div class="module-search-item">
                              <a class="video-serial" href="/voddetail/2.html" title="难哄"></a>
                            </div>
                            """;
                }
                if (url.contains("/voddetail/1.html") || url.contains("/voddetail/3.html")) {
                    return DETAIL_HTML;
                }
                if (url.contains("/voddetail/2.html")) {
                    // 与多多站同一分享链接(含提取码,折成相同的 ?password= 串),聚合时应去重
                    return """
                            <div class="module-row-info"><p>https://pan.quark.cn/s/abc123 提取码：x7kp</p></div>
                            """;
                }
                throw new IOException("site down");
            }
        };
        List<Message> messages = service.search("难哄");
        // 多多产出 3 条(夸克/百度/123),玩偶产出同一条夸克链接 → 去重后 3 条;
        // 夸克那条保留站点优先级最高的玩偶
        assertEquals(3, messages.size());
        Message quark = messages.get(0);
        assertEquals("https://pan.quark.cn/s/abc123?password=x7kp", quark.getLink());
        assertEquals("5", quark.getType());
        assertEquals("难哄", quark.getName());
        assertEquals("玩偶", quark.getChannel());
        assertEquals("10", messages.get(1).getType());
        assertEquals("多多", messages.get(1).getChannel());
        assertEquals("3", messages.get(2).getType());
    }

    @Test
    void disabledReturnsEmpty() {
        AppProperties props = props();
        props.getSubscription().setWanouEnabled(false);
        WanouSearchService service = new WanouSearchService(props, new ObjectMapper());
        assertTrue(service.search("难哄").isEmpty());
    }

    @Test
    void cloudflareChallengePageIsFailure() {
        assertTrue(WanouSearchService.isChallenge("challenge", "<html>ok</html>"));
        assertTrue(WanouSearchService.isChallenge(null,
                "<!DOCTYPE html><html lang=\"en-US\"><head><title>Just a moment...</title>"));
        assertTrue(WanouSearchService.isChallenge(null, "<script src=\"https://challenges.cloudflare.com/turnstile\"></script>"));
        assertTrue(WanouSearchService.isChallenge(null, ""));
        org.junit.jupiter.api.Assertions.assertFalse(WanouSearchService.isChallenge(null,
                "<html><div class=\"module-search-item\">card</div></html>"));
    }
}
