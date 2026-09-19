package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliV2Info;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliV2InfoResponse;
import cn.har01d.alist_tvbox.dto.bili.Data;
import cn.har01d.alist_tvbox.dto.bili.Resp;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BiliBiliServiceTest {
    private final RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
    private final SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
    private final AppProperties appProperties = Mockito.mock(AppProperties.class);
    private BiliBiliService service;

    @BeforeEach
    void setUp() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.defaultHeader(anyString(), any())).thenReturn(builder);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);
        when(settingRepository.findById(anyString())).thenReturn(Optional.empty());
        when(appProperties.getQns()).thenReturn(List.of());
        when(appProperties.getUserAgent()).thenReturn("Mozilla/5.0 Test");

        service = new BiliBiliService(settingRepository, mock(NavigationService.class), appProperties,
                mock(BiliCookieRefreshService.class), builder, new ObjectMapper());
        // 预置 WBI key,跳过 NAV_API 往返
        ReflectionTestUtils.setField(service, "imgKey", "7cd084941338484aae1ad9425b84077c");
        ReflectionTestUtils.setField(service, "subKey", "4932caff0ff746eab6f21fae462f4454");
        ReflectionTestUtils.setField(service, "keyTime", LocalDate.now());
    }

    private BiliBiliV2Info playerInfoWithChapters() {
        BiliBiliV2Info info = new BiliBiliV2Info();
        info.setSubtitle(new BiliBiliV2Info.SubtitleList());
        BiliBiliV2Info.ViewPoint opening = new BiliBiliV2Info.ViewPoint();
        opening.setFrom(0);
        opening.setTo(37);
        opening.setContent("片头");
        BiliBiliV2Info.ViewPoint first = new BiliBiliV2Info.ViewPoint();
        first.setFrom(37);
        first.setTo(522);
        first.setContent(" 第一集 ");
        info.setView_points(List.of(opening, first));
        return info;
    }

    private void stubPlayUrl() {
        Resp resp = new Resp();
        resp.setCode(0);
        resp.setData(new Data());
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/player/wbi/playurl"), eq(HttpMethod.GET), any(), eq(Resp.class)))
                .thenReturn(ResponseEntity.ok(resp));
    }

    @Test
    void getPlayUrlReturnsChaptersFromViewPoints() throws Exception {
        stubPlayUrl();
        BiliBiliV2InfoResponse playerResponse = new BiliBiliV2InfoResponse();
        playerResponse.setData(playerInfoWithChapters());
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/player/wbi/v2"), eq(HttpMethod.GET), any(), eq(BiliBiliV2InfoResponse.class)))
                .thenReturn(ResponseEntity.ok(playerResponse));

        Map<String, Object> result = service.getPlayUrl("116958703918865-40168587741", true, "gui");

        assertEquals(List.of(
                Map.of("from", 0L, "to", 37L, "title", "片头"),
                Map.of("from", 37L, "to", 522L, "title", "第一集")
        ), result.get("chapters"));
        assertEquals(List.of(), result.get("subs"));
        assertEquals("https://comment.bilibili.com/40168587741.xml", result.get("danmaku"));
    }

    @Test
    void getPlayUrlToleratesPlayerInfoFailure() throws Exception {
        stubPlayUrl();
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/player/wbi/v2"), eq(HttpMethod.GET), any(), eq(BiliBiliV2InfoResponse.class)))
                .thenThrow(new RuntimeException("player api down"));

        Map<String, Object> result = service.getPlayUrl("116958703918865-40168587741", true, "gui");

        assertEquals(List.of(), result.get("chapters"));
        assertEquals(List.of(), result.get("subs"));
    }

    @Test
    void getChaptersSkipsBlankAndNullEntries() {
        BiliBiliV2Info info = new BiliBiliV2Info();
        BiliBiliV2Info.ViewPoint blank = new BiliBiliV2Info.ViewPoint();
        blank.setFrom(10);
        blank.setTo(20);
        blank.setContent("  ");
        info.setView_points(java.util.Arrays.asList(null, blank));

        assertEquals(List.of(), service.getChapters(info));
        assertEquals(List.of(), service.getChapters(null));
    }

    @Test
    void viewPointsDeserializeFromUpstreamSnakeCaseJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String json = """
                {"code":0,"message":"0","data":{
                  "aid":116958703918865,"bvid":"BV195KY6YEeY","cid":40168587741,
                  "view_points":[
                    {"type":2,"from":0,"to":37,"content":"片头","imgUrl":"http://i0.hdslb.com/bfs/vchapter/40168587741_0.jpg","logoUrl":"","team_type":"","team_name":""},
                    {"type":2,"from":37,"to":522,"content":"第一集"}
                  ],
                  "subtitle":{"subtitles":[]}}}
                """;

        BiliBiliV2InfoResponse response = mapper.readValue(json, BiliBiliV2InfoResponse.class);

        List<BiliBiliV2Info.ViewPoint> points = response.getData().getView_points();
        assertEquals(2, points.size());
        assertEquals(0, points.get(0).getFrom());
        assertEquals(37, points.get(0).getTo());
        assertEquals("片头", points.get(0).getContent());
        assertEquals("第一集", points.get(1).getContent());
        assertTrue(response.getData().getSubtitle().getSubtitles().isEmpty());
    }
}
