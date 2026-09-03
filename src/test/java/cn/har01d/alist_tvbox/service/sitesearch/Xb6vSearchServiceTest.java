package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 6V磁力搜索源:搜索卡片解析(title 属性 <font> 高亮剥离)/详情页磁力+网盘产出
 * (组头并入 content、dn 解码优先)/条目上限/整链路搜索(POST 搜索 + GET 详情打桩)。
 */
class Xb6vSearchServiceTest {

    private static final String SEARCH_HTML = """
            <html><body><div id="post_container">
              <div class="post_hover">
                <a href="/donghuapian/13804.html" class="zoom" rel="bookmark" title="<font color='red'>凡人修仙传</font>"></a>
                <h2><a href="/donghuapian/13804.html"><font color='red'>凡人修仙传</font></a></h2>
              </div>
              <div class="post_hover">
                <a href="/dianshiju/guoju/26608.html" class="zoom" rel="bookmark" title="<font color='red'>凡人修仙传</font> 真人版[全集]"></a>
                <h2><a href="/dianshiju/guoju/26608.html"><font color='red'>凡人修仙传</font> 真人版[全集]</a></h2>
              </div>
            </div></body></html>
            """;

    private static final String DETAIL_HTML = """
            <html><body><div id="post_content">
              <table><tbody>
                <tr><td><p><strong>幕兰之战&amp;lrm; 年番4</strong></p></td></tr>
                <tr><td>磁力：<a href="magnet:?xt=urn:btih:15665de833a3365e85a9be1c3284abc658091257&amp;dn=%E5%87%A1%E4%BA%BA.%E5%B9%B4%E7%95%AA4.%E7%AC%AC177-178%E9%9B%86&amp;tr=udp%3A%2F%2Ftracker.example%3A80">177-178.1080p.HD国语中字无水印.mkv</a></td></tr>
                <tr><td>磁力：<a href="magnet:?xt=urn:btih:32c9ec24ae81e81e5bc78c2ef848e466e433d2f6">177-178.2160p.HD国语中字无水印.mkv</a></td></tr>
                <tr><td>夸克网盘链接：<a target="_blank" href="https://pan.quark.cn/s/b7f1c90b80bb">https://pan.quark.cn/s/b7f1c90b80bb</a></td></tr>
                <tr><td>迅雷云盘：<a target="_blank" href="https://pan.xunlei.com/s/VOyMgNjeIbkvgE_HM8BPtiziA1?pwd=g223">https://pan.xunlei.com/s/VOyMgNjeIbkvgE_HM8BPtiziA1?pwd=g223</a></td></tr>
              </tbody></table>
              <p>◎简　　介　少年韩立修炼成仙。</p>
            </div></body></html>
            """;

    private static AppProperties props() {
        return new AppProperties();
    }

    private static SettingRepository emptySettings() {
        SettingRepository repository = Mockito.mock(SettingRepository.class);
        Mockito.when(repository.findById(Mockito.anyString())).thenReturn(Optional.empty());
        return repository;
    }

    @Test
    void parseCardsStripsHighlightAndDedupes() {
        Xb6vSearchService service = new Xb6vSearchService(emptySettings(), props());
        List<Xb6vSearchService.Card> cards = service.parseCards(SEARCH_HTML);
        assertEquals(2, cards.size());
        assertEquals("/donghuapian/13804.html", cards.get(0).href());
        assertEquals("凡人修仙传", cards.get(0).title());
        assertTrue(cards.get(1).title().contains("真人版"));
        assertTrue(service.parseCards("<html><body>没有搜索到相关的内容</body></html>").isEmpty());
        assertTrue(service.parseCards("").isEmpty());
    }

    @Test
    void parseDetailProducesMagnetsAndPanLinks() {
        Xb6vSearchService service = new Xb6vSearchService(emptySettings(), props());
        List<Message> out = new ArrayList<>();
        service.parseDetail(DETAIL_HTML, new Xb6vSearchService.Card("/donghuapian/13804.html", "凡人修仙传"),
                out, new HashSet<>());
        assertEquals(4, out.size());
        Message firstMagnet = out.get(0);
        assertEquals("magnet", firstMagnet.getType());
        assertTrue(firstMagnet.getLink().startsWith("magnet:?xt=urn:btih:15665de"));
        // dn 解码优先,组头(年番4)并入 content 供集数分组打分
        assertTrue(firstMagnet.getContent().contains("年番4"));
        assertTrue(firstMagnet.getContent().contains("第177-178集"));
        assertEquals("凡人修仙传", firstMagnet.getName());
        assertEquals("6V", firstMagnet.getChannel());
        // 无 dn 的磁力回落行文本
        Message secondMagnet = out.get(1);
        assertTrue(secondMagnet.getContent().contains("177-178.2160p"));
        // 网盘行:数字盘型 + 提取码内嵌链接原样保留
        Message quark = out.get(2);
        assertEquals("5", quark.getType());
        assertEquals("https://pan.quark.cn/s/b7f1c90b80bb", quark.getLink());
        assertTrue(quark.getContent().contains("夸克网盘链接"));
        Message xunlei = out.get(3);
        assertEquals("2", xunlei.getType());
        assertTrue(xunlei.getLink().endsWith("?pwd=g223"));
    }

    @Test
    void magnetDnDecodesOrFallsBack() {
        assertEquals("凡人.年番4.第177-178集",
                Xb6vSearchService.magnetDn("magnet:?xt=urn:btih:abc&dn=%E5%87%A1%E4%BA%BA.%E5%B9%B4%E7%95%AA4.%E7%AC%AC177-178%E9%9B%86&tr=x"));
        assertEquals("", Xb6vSearchService.magnetDn("magnet:?xt=urn:btih:abc"));
        assertEquals("", Xb6vSearchService.magnetDn("magnet:"));
        // 非法百分号编码回落原值,不抛
        assertTrue(Xb6vSearchService.magnetDn("magnet:?xt=x&dn=%zz%").contains("%zz%"));
    }

    @Test
    void magnetCapPerDetail() {
        AppProperties properties = props();
        properties.getSubscription().setXb6vMaxMagnets(1);
        Xb6vSearchService service = new Xb6vSearchService(emptySettings(), properties);
        List<Message> out = new ArrayList<>();
        service.parseDetail(DETAIL_HTML, new Xb6vSearchService.Card("/x", "t"), out, new HashSet<>());
        assertEquals(1, out.stream().filter(m -> "magnet".equals(m.getType())).count());
        // 网盘行不受磁力上限影响
        assertEquals(2, out.stream().filter(m -> !"magnet".equals(m.getType())).count());
    }

    @Test
    void searchFullChainWithStubbedHttp() {
        Xb6vSearchService service = new Xb6vSearchService(emptySettings(), props()) {
            @Override
            protected Resp http(Request request, Map<String, String> jar) throws IOException {
                String url = request.url().toString();
                if (url.contains("/e/search/")) {
                    return new Resp(200, List.of(), SEARCH_HTML);
                }
                if (url.contains("/donghuapian/13804.html")) {
                    return new Resp(200, List.of(), DETAIL_HTML);
                }
                // 第二个卡片:只有外站普通链接(无盘型不产出)
                return new Resp(200, List.of(),
                        "<html><body><div id=\"post_content\"><a href=\"https://example.com/x\">外站</a></div></body></html>");
            }
        };
        List<Message> result = service.search("凡人修仙传");
        // 卡片1 详情:2 磁力 + 夸克 + 迅雷;卡片2 详情无产出
        assertEquals(4, result.size());
        assertTrue(result.stream().anyMatch(m -> "magnet".equals(m.getType())));
        assertTrue(result.stream().anyMatch(m -> "5".equals(m.getType())));
        assertTrue(service.search("").isEmpty());
    }

    @Test
    void searchFailureIsSilent() {
        Xb6vSearchService service = new Xb6vSearchService(emptySettings(), props()) {
            @Override
            protected Resp http(Request request, Map<String, String> jar) throws IOException {
                throw new IOException("timeout");
            }
        };
        assertTrue(service.search("凡人修仙传").isEmpty());
    }
}
