package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盘聚搜索源:搜索卡片/详情网盘行解析、盘型判定(域名+文本兜底)、中转页真实分享链提取、
 * 候选池价值排序、Cloudflare 挑战判定与端到端搜索(打桩 fetch)。
 * HTML 样本取自 2026-08-30 实测页面结构的精简版(.cover 卡片、.pan-links 隐藏容器、var panLink 脚本)。
 * 盘检过滤在聚合层(MediaSubscriptionCheckService.searchAllSources)统一接线,不在本源内。
 */
class PanjuSearchServiceTest {

    private static final String SEARCH_HTML = """
            <html><body><main>
              <div class="cover"><a href="/movies/129879/"><img alt="末日地堡 第三季 Silo Season 3" src="/p/1.jpg"></a></div>
              <div class="cover"><a href="/movies/117750/"><img alt="末日地堡 第二季 Silo Season 2" src="/p/2.jpg"></a></div>
              <div class="cover"><a href="/movies/129879/"><img alt="重复卡片应去重" src="/p/1.jpg"></a></div>
              <div class="cover"><a href="/other"><img alt="无电影ID" src="/p/3.jpg"></a></div>
              <div class="cover"><a href="/movies/200/"></a></div>
            </main></body></html>
            """;

    private static final String DETAIL_HTML = """
            <html><body>
              <div class="pan-links" style="display:none;"><ul>
                <li><a href="/link_start/?redirect_to=pan_id_2&amp;movie_title=百度资源"
                       data-link="pan.baidu.com" title="末日地堡 第二季 全集 1080P">百度</a></li>
                <li><a href="/link_start/?seed_id=748697&amp;movie_title=磁力" data-link="pan.quark.cn">磁力行不算</a></li>
                <li><a href="/link_start/?redirect_to=pan_id_1&amp;movie_title=夸克资源"
                       data-link="pan.quark.cn" title="末日地堡 第三季 4K 更新至08集">夸克</a></li>
                <li><a href="/link_start/?redirect_to=pan_id_1&amp;movie_title=重复" data-link="pan.quark.cn">重复行</a></li>
                <li><a href="/link_start/?redirect_to=pan_id_3" data-link="example.com">未知盘不占配额</a></li>
                <li><a href="/link_start/?redirect_to=pan_id_4" title="UC网盘 蓝光原盘">无data-link靠标题判盘</a></li>
              </ul></div>
              <div class="seed-list"><ul class="seeds">
                <li><a href="/link_start/?seed_id=111">magnet 行</a></li>
                <li><a href="/link_start/?seed_id=222&amp;movie_title=末日地堡.第3季.磁力" title="4K 种子">种子</a></li>
                <li><a href="/link_start/?seed_id=111&amp;movie_title=dup">重复 seed 去重</a></li>
              </ul></div>
            </body></html>
            """;

    private static final String QUARK_REDIRECT = """
            <html><body><script>var panLink = "https://pan.quark.cn/s/9391ecca1fe8";</script></body></html>
            """;

    private static final String BAIDU_REDIRECT = """
            <html><body><script>var panLink = "https://pan.baidu.com/s/19JCogV_qlHg?pwd=ewiq";</script></body></html>
            """;

    private static final String UC_REDIRECT = """
            <html><body><script>window.open("https://drive.uc.cn/s/abc123");</script></body></html>
            """;

    private static final String MAGNET_REDIRECT = """
            <html><body><script>const data = "bWFnbmV0Oj94dD11cm46YnRpaDo=";</script></body></html>
            """;

    /** seed 中转页:脚本里直出 magnet 明链(window.location.href 形态,引号即正则边界) */
    private static final String SEED_PLAIN_PAGE = """
            <html><body><script>window.location.href='magnet:?xt=urn:btih:1111aaaabbbb';</script></body></html>
            """;

    private static AppProperties props() {
        return new AppProperties();
    }

    @Test
    void parseSearchCards() {
        PanjuSearchService service = new PanjuSearchService(props(), new ObjectMapper());
        List<PanjuSearchService.Card> cards = service.parseSearchCards(SEARCH_HTML);
        // 重复 id 去重、无 id / 无标题卡片跳过
        assertEquals(2, cards.size());
        assertEquals("129879", cards.get(0).id());
        assertEquals("末日地堡 第三季 Silo Season 3", cards.get(0).title());
        assertEquals("117750", cards.get(1).id());
    }

    @Test
    void parsePanRowsSortsByPoolValueAndFilters() {
        PanjuSearchService service = new PanjuSearchService(props(), new ObjectMapper());
        List<PanjuSearchService.PanRow> rows = service.parsePanRows(DETAIL_HTML);
        // seed 行/同 redirect_to 重复行/未知盘剔除:剩 夸克 + UC + 百度
        assertEquals(3, rows.size());
        // 候选池价值排序:夸克最前(而非页面顺序的百度最前)
        assertEquals("5", rows.get(0).disk());
        assertTrue(rows.get(0).href().contains("pan_id_1"));
        assertEquals("末日地堡 第三季 4K 更新至08集", rows.get(0).label());
        assertEquals("7", rows.get(1).disk()); // UC:无 data-link,靠标题文本"UC网盘"判定
        assertEquals("10", rows.get(2).disk());
        assertEquals("3", PanjuSearchService.diskType("pan.123592.com")); // 不在前排但能识别
    }

    @Test
    void resolvePanLinkExtractsRealShare() {
        PanjuSearchService service = new PanjuSearchService(props(), new ObjectMapper()) {
            @Override
            protected String fetch(String url) {
                if (url.contains("pan_id_1")) {
                    return QUARK_REDIRECT;
                }
                if (url.contains("pan_id_2")) {
                    return BAIDU_REDIRECT;
                }
                if (url.contains("pan_id_4")) {
                    return UC_REDIRECT;
                }
                return "";
            }
        };
        assertEquals("https://pan.quark.cn/s/9391ecca1fe8", service.resolvePanLink("https://sidhub.cc/link_start/?redirect_to=pan_id_1"));
        assertEquals("https://pan.baidu.com/s/19JCogV_qlHg?pwd=ewiq", service.resolvePanLink("https://sidhub.cc/link_start/?redirect_to=pan_id_2"));
        // window.open 兜底
        assertEquals("https://drive.uc.cn/s/abc123", service.resolvePanLink("https://sidhub.cc/link_start/?redirect_to=pan_id_4"));
        // 纯磁力中转页:对候选池无意义,返回空
        assertEquals("", service.resolvePanLink("https://sidhub.cc/link_start/?seed_id=748697"));
    }

    @Test
    void diskTypeDomainAndTextFallback() {
        assertEquals("5", PanjuSearchService.diskType("pan.quark.cn"));
        assertEquals("0", PanjuSearchService.diskType("https://www.alipan.com/s/xx"));
        assertEquals("2", PanjuSearchService.diskType("pan.xunlei.com"));
        assertEquals("9", PanjuSearchService.diskType("cloud.189.cn"));
        assertEquals("8", PanjuSearchService.diskType("115.com"));
        assertNull(PanjuSearchService.diskType("example.com"));
        assertNull(PanjuSearchService.diskType(""));
        assertEquals("10", PanjuSearchService.diskTypeFromText("百度网盘 4K 合集"));
        assertEquals("5", PanjuSearchService.diskTypeFromText("夸克网盘 更新至08集"));
        assertNull(PanjuSearchService.diskTypeFromText("普通资源标题"));
    }

    @Test
    void matchKeywordToleratesSeasonAndEnglishSuffix() {
        PanjuSearchService service = new PanjuSearchService(props(), new ObjectMapper());
        assertTrue(service.matchKeyword("末日地堡 第三季 Silo Season 3", "末日地堡"));
        assertTrue(service.matchKeyword("末日地堡 第三季 Silo Season 3", "末日地堡 第三季"));
        assertTrue(service.matchKeyword("凡人修仙传", "凡人修仙传 第12集"));
        assertTrue(service.matchKeyword("模范出租车", "模范出租车 2025"));
        org.junit.jupiter.api.Assertions.assertFalse(service.matchKeyword("小黄人与大怪兽", "末日地堡"));
        org.junit.jupiter.api.Assertions.assertFalse(service.matchKeyword("末日地堡", "2025"));
    }

    @Test
    void challengeDetection() {
        assertTrue(PanjuSearchService.isChallenge("challenge", "anything"));
        assertTrue(PanjuSearchService.isChallenge(null, "<html>Just a moment...</html>"));
        assertTrue(PanjuSearchService.isChallenge(null, "<script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate\"></script>"));
        assertTrue(PanjuSearchService.isChallenge(null, "  "));
        org.junit.jupiter.api.Assertions.assertFalse(PanjuSearchService.isChallenge(null, "<html>正常页面</html>"));
    }

    @Test
    void parseSeedRowsDedupesAndStripsMovieTitle() {
        PanjuSearchService service = new PanjuSearchService(props(), new ObjectMapper());
        List<PanjuSearchService.SeedRow> rows = service.parseSeedRows(DETAIL_HTML);
        // 重复 seed_id 去重;href 剥裸 Unicode 的 movie_title 只留 seed_id;label 跳过泛化名
        assertEquals(2, rows.size());
        assertEquals("/link_start/?seed_id=111", rows.get(0).href());
        assertEquals("magnet 行", rows.get(0).label());
        assertEquals("/link_start/?seed_id=222", rows.get(1).href());
        assertEquals("4K 种子", rows.get(1).label());
    }

    @Test
    void resolveSeedLinkPrefersPlainLinkOverBase64() {
        String ed2k = "ed2k://|file|末日地堡.EP08.mp4|123456|hash|/";
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(ed2k.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String base64Page = "<html><body><script>const data = \"" + encoded + "\";</script></body></html>";
        PanjuSearchService service = new PanjuSearchService(props(), new ObjectMapper()) {
            @Override
            protected String fetch(String url) {
                if (url.contains("seed_id=111")) {
                    return SEED_PLAIN_PAGE;
                }
                if (url.contains("seed_id=222")) {
                    return base64Page;
                }
                if (url.contains("seed_id=333")) {
                    return MAGNET_REDIRECT; // base64 解出截断的 magnet 前缀,仍算离线链
                }
                return "<html><body>无链接</body></html>";
            }
        };
        // 明链优先
        assertEquals("magnet:?xt=urn:btih:1111aaaabbbb",
                service.resolveSeedLink("https://sidhub.cc/link_start/?seed_id=111"));
        // base64 密文兜底,解出 ed2k
        assertEquals(ed2k, service.resolveSeedLink("https://sidhub.cc/link_start/?seed_id=222"));
        assertEquals("magnet:?xt=urn:btih:",
                service.resolveSeedLink("https://sidhub.cc/link_start/?seed_id=333"));
        assertEquals("", service.resolveSeedLink("https://sidhub.cc/link_start/?seed_id=444"));
        assertEquals("", service.resolveSeedLink(""));
    }

    @Test
    void searchEndToEndWithStubbedFetch() {
        java.util.concurrent.atomic.AtomicInteger seedRequests = new java.util.concurrent.atomic.AtomicInteger();
        PanjuSearchService service = new PanjuSearchService(props(), new ObjectMapper()) {
            @Override
            protected String fetch(String url) {
                if (url.contains("/s/") && url.contains("page=1")) {
                    return SEARCH_HTML;
                }
                if (url.contains("/movies/")) {
                    return DETAIL_HTML;
                }
                if (url.contains("pan_id_1")) {
                    return QUARK_REDIRECT;
                }
                if (url.contains("pan_id_2")) {
                    return BAIDU_REDIRECT;
                }
                if (url.contains("pan_id_4")) {
                    return UC_REDIRECT;
                }
                if (url.contains("seed_id=")) {
                    seedRequests.incrementAndGet();
                    return SEED_PLAIN_PAGE;
                }
                return ""; // hosts 刷新接口等:空=失败,不干扰主流程
            }
        };
        List<Message> messages = service.search("末日地堡");
        // 两张卡片共用同一份详情样本 → 中转链按 link 去重后剩 3 条(夸克/UC/百度);
        // 默认不解析 seed 行(磁力兜底未开,不白烧中转请求)
        assertEquals(3, messages.size());
        assertEquals(0, seedRequests.get(), "includeOffline=false 不得发起 seed 中转请求");
        Message quark = messages.get(0);
        assertEquals("5", quark.getType());
        assertEquals("https://pan.quark.cn/s/9391ecca1fe8", quark.getLink());
        assertEquals("盘聚", quark.getChannel());
        assertEquals("末日地堡 第三季 Silo Season 3", quark.getName());
        assertTrue(quark.getContent().contains("更新至08集"));
        assertEquals("7", messages.get(1).getType());
        assertEquals("10", messages.get(2).getType());
        assertTrue(messages.get(2).getLink().endsWith("?pwd=ewiq"));
    }

    @Test
    void searchOfflineIncludeHarvestsSeeds() {
        String ed2k = "ed2k://|file|末日地堡.S03E08.1080p.mp4|4567890|hash|/";
        String base64Page = "<html><body><script>const data = \"" + java.util.Base64.getEncoder()
                .encodeToString(ed2k.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "\";</script></body></html>";
        PanjuSearchService service = new PanjuSearchService(props(), new ObjectMapper()) {
            @Override
            protected String fetch(String url) {
                if (url.contains("/s/") && url.contains("page=1")) {
                    return SEARCH_HTML;
                }
                if (url.contains("/movies/")) {
                    return DETAIL_HTML;
                }
                if (url.contains("pan_id_1")) {
                    return QUARK_REDIRECT;
                }
                if (url.contains("pan_id_2")) {
                    return BAIDU_REDIRECT;
                }
                if (url.contains("pan_id_4")) {
                    return UC_REDIRECT;
                }
                if (url.contains("seed_id=111")) {
                    return SEED_PLAIN_PAGE;
                }
                if (url.contains("seed_id=222")) {
                    return base64Page;
                }
                return "";
            }
        };
        List<Message> messages = service.search("末日地堡", true);
        // 3 网盘 + 2 seed 离线(明链 magnet + base64 ed2k);两卡片同详情,link 去重
        assertEquals(5, messages.size());
        Message magnet = messages.get(3);
        assertEquals("magnet", magnet.getType());
        assertEquals("magnet:?xt=urn:btih:1111aaaabbbb", magnet.getLink());
        assertEquals("盘聚", magnet.getChannel());
        assertEquals("末日地堡 第三季 Silo Season 3", magnet.getName());
        assertEquals("magnet 行", magnet.getContent());
        Message ed2kMessage = messages.get(4);
        assertEquals("ed2k", ed2kMessage.getType());
        assertEquals(ed2k, ed2kMessage.getLink());
        assertEquals("4K 种子", ed2kMessage.getContent());
    }

    @Test
    void searchDisabledReturnsEmpty() {
        AppProperties props = props();
        props.getSubscription().setPanjuEnabled(false);
        PanjuSearchService service = new PanjuSearchService(props, new ObjectMapper()) {
            @Override
            protected String fetch(String url) {
                throw new AssertionError("开关关闭时不应发起任何请求");
            }
        };
        assertEquals(List.of(), service.search("末日地堡"));
    }
}
