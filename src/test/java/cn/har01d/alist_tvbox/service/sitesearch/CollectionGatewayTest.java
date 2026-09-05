package cn.har01d.alist_tvbox.service.sitesearch;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集源兜底网关:搜索结果硬过滤(归属/季/年三道门禁)+ 排序、详情播放列表解析
 * (组$$$集#条目、直链组优先、集号解析失败不补)、HTTP 打桩零网络。
 */
class CollectionGatewayTest {

    private static final String SEARCH_BODY = """
            {"code":1,"list":[
              {"vod_id":101,"vod_name":"凡人修仙传","vod_year":"2025","vod_remarks":"更新至40集"},
              {"vod_id":102,"vod_name":"凡人修仙传 第二季","vod_year":"2025","vod_remarks":"全30集"},
              {"vod_id":103,"vod_name":"凡人修仙传 真人版","vod_year":"2019","vod_remarks":"全37集"},
              {"vod_id":104,"vod_name":"凡人修仙外传","vod_year":"2025","vod_remarks":"全12集"},
              {"vod_id":"0","vod_name":"无效条目","vod_year":"","vod_remarks":""}
            ]}
            """;

    private static final String DETAIL_BODY = """
            {"code":1,"list":[{
              "vod_id":101,"vod_name":"凡人修仙传","vod_year":"2025",
              "vod_play_from":"卧龙云$$$无尽云",
              "vod_play_url":"第01集$http://x.example/01.m3u8#第02集$http://x.example/02.m3u8#第10集$http://x.example/10.m3u8$$$第01集$http://y.example/w1.html"
            }]}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MediaSubscription subscription(String name, Integer season) {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(7);
        subscription.setName(name);
        subscription.setSeason(season);
        return subscription;
    }

    private CollectionGateway gateway(Map<String, String> responses, MediaSubscription subscription) {
        MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
        Mockito.when(checkService.matchNames(Mockito.any(MediaSubscription.class)))
                .thenReturn(List.of(subscription.getName()));
        Mockito.when(checkService.metaYear(Mockito.any())).thenReturn(2025);
        Mockito.when(checkService.parseEpisodeFromTitle(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> parseEpisode(invocation.getArgument(0)));
        return new CollectionGateway(new AppProperties(), objectMapper, checkService) {
            @Override
            protected String http(String url, int timeoutSeconds) throws IOException {
                for (var entry : responses.entrySet()) {
                    if (url.contains(entry.getKey())) {
                        return entry.getValue();
                    }
                }
                return null;
            }
        };
    }

    /** 与 parseEpisodeFromTitle 同口径的测试桩:第N集/EPn/纯数字。 */
    private static int parseEpisode(String title) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)第\\s*(\\d{1,4})\\s*集|(?:^|[^a-z])e(?:p)?\\s*(\\d{1,4})(?!\\d)|(\\d{1,4})")
                .matcher(title == null ? "" : title);
        if (matcher.find()) {
            for (int i = 1; i <= 3; i++) {
                String group = matcher.group(i);
                if (group != null) {
                    return Integer.parseInt(group);
                }
            }
        }
        return -1;
    }

    @Test
    void searchFiltersSeasonAndYearMismatches() {
        Map<String, String> responses = new HashMap<>();
        responses.put("wd=", SEARCH_BODY);
        MediaSubscription subscription = subscription("凡人修仙传", null);
        CollectionGateway gateway = gateway(responses, subscription);

        List<CollectionGateway.CollectionItem> items = gateway.search(subscription, 10);
        // 真人版(2019≠2025)被年份门禁拒;外传不归属(matchNames 只有正名,归属匹配拒);
        // 102 无期望季号降权但保留
        assertTrue(items.stream().noneMatch(i -> i.title().contains("真人版")));
        assertTrue(items.stream().noneMatch(i -> i.title().contains("外传")));
        assertTrue(items.stream().anyMatch(i -> i.vodId().equals("101")));
        assertTrue(items.stream().anyMatch(i -> i.vodId().equals("102")));
    }

    @Test
    void searchRejectsOtherSeasonWhenExpected() {
        Map<String, String> responses = new HashMap<>();
        responses.put("wd=", SEARCH_BODY);
        MediaSubscription subscription = subscription("凡人修仙传", 1);
        CollectionGateway gateway = gateway(responses, subscription);

        List<CollectionGateway.CollectionItem> items = gateway.search(subscription, 10);
        assertTrue(items.stream().noneMatch(i -> i.vodId().equals("102"))); // 明确标注第二季
    }

    @Test
    void loadPlaylistPrefersDirectGroupAndParsesEpisodes() {
        Map<String, String> responses = new HashMap<>();
        responses.put("ac=videolist", DETAIL_BODY);
        CollectionGateway gateway = gateway(responses, subscription("凡人修仙传", null));

        CollectionGateway.CollectionPlaylist playlist = gateway.loadPlaylist(subscription("凡人修仙传", null),
                new CollectionGateway.CollectionItem("wolong", "卧龙资源", "101", "凡人修仙传", "2025", "", 0));
        assertNotNull(playlist);
        assertEquals("卧龙云", playlist.line());
        TreeMap<Integer, String> episodes = playlist.episodes();
        assertEquals(3, episodes.size());
        assertEquals("http://x.example/10.m3u8", episodes.get(10));
        // 非直链组(无尽云 .html)不得覆盖直链组的条目
        assertTrue(episodes.values().stream().noneMatch(u -> u.contains("y.example")));
    }

    @Test
    void loadPlaylistSkipsUnparsableEpisodes() {
        String detail = """
                {"list":[{"vod_id":101,"vod_name":"凡人修仙传","vod_play_from":"主",
                  "vod_play_url":"第10集$http://x.example/10.m3u8#预告$http://x.example/trailer.m3u8#花絮$http://x/extra.m3u8"}]}
                """;
        Map<String, String> responses = new HashMap<>();
        responses.put("ac=videolist", detail);
        CollectionGateway gateway = gateway(responses, subscription("凡人修仙传", null));

        CollectionGateway.CollectionPlaylist playlist = gateway.loadPlaylist(subscription("凡人修仙传", null),
                new CollectionGateway.CollectionItem("wolong", "卧龙资源", "101", "凡人修仙传", "2025", "", 0));
        assertNotNull(playlist);
        assertEquals(1, playlist.episodes().size());
        assertEquals("http://x.example/10.m3u8", playlist.episodes().get(10));
    }

    @Test
    void loadPlaylistRejectsNonDirectOnlyGroups() {
        // 只有网页/网盘组(无任何直接媒体后缀):整条拒收,不得把页面地址当直链存进覆盖层
        String detail = """
                {"list":[{"vod_id":101,"vod_name":"凡人修仙传","vod_play_from":"无尽云$$$百度盘",
                  "vod_play_url":"第01集$http://y.example/w1.html$$$第01集$https://pan.baidu.com/s/abcd"}]}
                """;
        Map<String, String> responses = new HashMap<>();
        responses.put("ac=videolist", detail);
        CollectionGateway gateway = gateway(responses, subscription("凡人修仙传", null));

        assertNull(gateway.loadPlaylist(subscription("凡人修仙传", null),
                new CollectionGateway.CollectionItem("wolong", "卧龙资源", "101", "凡人修仙传", "2025", "", 0)));
    }

    @Test
    void loadPlaylistReturnsNullWhenSiteUnknownOrBodyEmpty() {
        Map<String, String> responses = new HashMap<>();
        CollectionGateway gateway = gateway(responses, subscription("凡人修仙传", null));
        assertNull(gateway.loadPlaylist(subscription("凡人修仙传", null),
                new CollectionGateway.CollectionItem("nosuch", "无", "1", "凡人修仙传", "", "", 0)));
        assertNull(gateway.loadPlaylist(subscription("凡人修仙传", null),
                new CollectionGateway.CollectionItem("wolong", "卧龙资源", "101", "凡人修仙传", "", "", 0)));
    }

    @Test
    void searchHandlesAllSitesDown() {
        Map<String, String> responses = new HashMap<>();
        CollectionGateway gateway = gateway(responses, subscription("凡人修仙传", null));
        assertTrue(gateway.search(subscription("凡人修仙传", null), 10).isEmpty());
    }
}
