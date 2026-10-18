package cn.har01d.alist_tvbox.live.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快手主播原生搜索解析单测(pure_live 2753a910 契约):/live_api/search/author 的 data.list 出
 * 主播 id/开播标记/封禁标记/预格式化粉丝数,房间号即主播 id,详情走 /u/&lt;id&gt; 既有链路。
 */
class KuaishouServiceTest {
    private final KuaishouService service = new KuaishouService(new RestTemplateBuilder(), new ObjectMapper());
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseAuthorSearchBuildsEntriesWithLivingAndFanRemarks() throws Exception {
        String body = """
                {"result":1,"data":{"list":[
                  {"id":"tianci666","name":"CHEN天赐","living":false,"avatar":"https://p2.a.yximgs.com/a.jpg",
                   "description":"每晚9点左右直播","counts":{"fan":"1944.8w"},"bannedStatus":{"banned":false}},
                  {"id":"lala666","name":"辣辣","living":true,"avatar":"https://p2.b.yximgs.com/b.jpg",
                   "counts":{"fan":"123"},"bannedStatus":{"banned":false}}
                ]}}""";
        var rooms = service.parseAuthorSearch(objectMapper.readTree(body));
        assertEquals(2, rooms.size());
        var first = rooms.get(0);
        assertEquals("ks$tianci666", first.getVod_id());
        assertEquals("CHEN天赐", first.getVod_name());
        assertEquals("https://p2.a.yximgs.com/a.jpg", first.getVod_pic());
        assertEquals("每晚9点左右直播", first.getVod_content());
        // counts.fan 是快手预格式化字符串("1944.8w"),原样展示不重排
        assertEquals("未开播 · 粉丝 1944.8w", first.getVod_remarks());
        assertEquals("直播中 · 粉丝 123", rooms.get(1).getVod_remarks());
    }

    @Test
    void parseAuthorSearchMarksBannedOverLivingAndToleratesMissingCounts() throws Exception {
        String body = """
                {"data":{"list":[
                  {"id":"bad1","name":"封禁主播","living":true,"bannedStatus":{"banned":true}},
                  {"id":"ok2","name":"无粉丝数据","living":false}
                ]}}""";
        var rooms = service.parseAuthorSearch(objectMapper.readTree(body));
        // 封禁标记优先于开播标记;无粉丝数据时只留状态
        assertEquals("封禁", rooms.get(0).getVod_remarks());
        assertEquals("未开播", rooms.get(1).getVod_remarks());
    }

    @Test
    void parseAuthorSearchSkipsBlankIdAndEmptyShapes() throws Exception {
        String withBlankId = """
                {"data":{"list":[{"id":"  ","name":"无id主播"},{"id":"real1","name":"有id"}]}}""";
        var rooms = service.parseAuthorSearch(objectMapper.readTree(withBlankId));
        assertEquals(1, rooms.size());
        assertEquals("ks$real1", rooms.get(0).getVod_id());
        // 空/异常形态:空列表、无 data、错误响应,一律空结果不炸
        assertTrue(service.parseAuthorSearch(objectMapper.readTree("{}")).isEmpty());
        assertTrue(service.parseAuthorSearch(objectMapper.readTree("{\"data\":{\"list\":[]}}")).isEmpty());
        assertTrue(service.parseAuthorSearch(objectMapper.readTree("{\"result\":10,\"data\":null}")).isEmpty());
    }

    @Test
    void parsePlayUrlsListsHevcOnlyQualitiesAfterH264() throws Exception {
        // H.265 里有 H.264 没有的档位(4K、蓝光质臻):两套都列,hevc 档位带标记排在 h264 之后
        String body = """
                {"h264":{"adaptationSet":{"representation":[
                  {"name":"原画","url":"https://h.flv","level":0},
                  {"name":"超清","url":"https://h2.flv","level":2}
                ]}},
                 "hevc":{"adaptationSet":{"representation":[
                  {"name":"蓝光质臻","url":"https://e.flv","level":66},
                  {"name":"4K","url":"https://e2.flv","level":70}
                ]}}}""";
        var detail = new cn.har01d.alist_tvbox.tvbox.MovieDetail();
        service.parsePlayUrls(detail, objectMapper.readTree(body));
        assertEquals("原画", detail.getVod_play_from());
        // h264 按 level 降序在前,hevc 同样降序在后
        assertEquals("超清$https://h2.flv#原画$https://h.flv"
                + "#4K·H.265$https://e2.flv#蓝光质臻·H.265$https://e.flv", detail.getVod_play_url());
    }

    @Test
    void parsePlayUrlsH264OnlyKeepsLegacyShape() throws Exception {
        String body = """
                {"h264":{"adaptationSet":{"representation":[
                  {"name":"原画","url":"https://h.flv","level":0}
                ]}}}""";
        var detail = new cn.har01d.alist_tvbox.tvbox.MovieDetail();
        service.parsePlayUrls(detail, objectMapper.readTree(body));
        assertEquals("原画", detail.getVod_play_from());
        assertEquals("原画$https://h.flv", detail.getVod_play_url());
    }

    @Test
    void parsePlayUrlsEmptyShapesLeavePlayableBlank() throws Exception {
        var detail = new cn.har01d.alist_tvbox.tvbox.MovieDetail();
        service.parsePlayUrls(detail, objectMapper.readTree("{}"));
        // 无任何可播档位:不设线路,等效未开播标记
        assertEquals(null, detail.getVod_play_from());
        service.parsePlayUrls(detail, objectMapper.readTree(
                "{\"h264\":{\"adaptationSet\":{\"representation\":[]}},\"hevc\":{\"adaptationSet\":{}}}"));
        assertEquals(null, detail.getVod_play_from());
    }
}
