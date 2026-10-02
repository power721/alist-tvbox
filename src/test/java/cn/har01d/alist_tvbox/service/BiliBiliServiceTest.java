package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.FilterDto;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliHot;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliHotResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliInfo;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliInfoResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliList;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliListResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliRelatedResponse;
import cn.har01d.alist_tvbox.util.BiliBiliUtils;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliV2Info;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliV2InfoResponse;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliWatchLaterResponse;
import cn.har01d.alist_tvbox.dto.bili.Data;
import cn.har01d.alist_tvbox.dto.bili.Resp;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BiliBiliServiceTest {
    private final RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
    private final SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
    private final NavigationService navigationService = Mockito.mock(NavigationService.class);
    private final AppProperties appProperties = Mockito.mock(AppProperties.class);
    private final BiliCookieRefreshService biliCookieRefreshService = Mockito.mock(BiliCookieRefreshService.class);
    private BiliBiliService service;

    @BeforeEach
    void setUp() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.defaultHeader(anyString(), any())).thenReturn(builder);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);
        when(settingRepository.findById(anyString())).thenReturn(Optional.empty());
        when(settingRepository.findById(cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE))
                .thenReturn(java.util.Optional.of(new cn.har01d.alist_tvbox.entity.Setting(
                        cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE, BiliBiliUtils.getCookie())));
        when(biliCookieRefreshService.refreshIfNeeded(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(appProperties.getQns()).thenReturn(List.of());
        when(appProperties.getUserAgent()).thenReturn("Mozilla/5.0 Test");

        service = new BiliBiliService(settingRepository, navigationService, appProperties,
                biliCookieRefreshService, builder, new ObjectMapper());
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

    private void stubInfoApi(BiliBiliInfo info) {
        BiliBiliInfoResponse response = new BiliBiliInfoResponse();
        response.setData(info);
        when(restTemplate.getForObject(startsWith("https://api.bilibili.com/x/web-interface/view?bvid="), eq(BiliBiliInfoResponse.class)))
                .thenReturn(response);
    }

    private BiliBiliInfo videoInfo() {
        BiliBiliInfo info = new BiliBiliInfo();
        info.setAid(116958703918865L);
        info.setBvid("BV195KY6YEeY");
        info.setCid(40168587741L);
        info.setDuration(4531);
        info.setPubdate(1758220800L);
        info.setTitle("归墟");
        info.setTname("动画");
        info.setTname_v2("短片");
        BiliBiliInfo.User owner = new BiliBiliInfo.User();
        owner.setMid(378885845L);
        owner.setName("归墟制造局");
        info.setOwner(owner);
        info.setStat(new BiliBiliInfo.Stats());
        return info;
    }

    @Test
    void getDetailReturnsClickableDirectorMarkupForGuiClient() throws Exception {
        stubInfoApi(videoInfo());

        MovieList detail = service.getDetail("BV195KY6YEeY", "gui");

        // atv-player 渲染 [a=cr:...] 为内联链接,点击跳 t=up:<mid> 的 UP 主视频列表
        assertEquals("[a=cr:{\"target\":\"bilibili\",\"type\":\"category\",\"value\":\"up:378885845\"}/]归墟制造局[/a]",
                detail.getList().get(0).getVod_director());
    }

    @Test
    void getDetailKeepsFongmiAndPlainDirectorForOtherClients() throws Exception {
        stubInfoApi(videoInfo());

        assertEquals("[a=cr:{\"id\":\"up:378885845\",\"name\":\"归墟制造局\"}/]归墟制造局[/a]",
                service.getDetail("BV195KY6YEeY", "com.fongmi.android.tv").getList().get(0).getVod_director());
        assertEquals("归墟制造局",
                service.getDetail("BV195KY6YEeY", "com.github.tvbox.osc").getList().get(0).getVod_director());
    }

    @Test
    void getDetailResolvesAidCidEntryIdsThroughBvConversion() throws Exception {
        // 相关视频/合集线路条目 id 为 aid-cid(/play 同款格式)、稍后再看有纯 aid 回落:
        // 详情必须折 BV 后照常返回(stub 只认折转后的 BV,拿到响应即证明走了转换),而非恒 404
        BiliBiliInfoResponse response = new BiliBiliInfoResponse();
        response.setData(videoInfo());
        when(restTemplate.getForObject(startsWith("https://api.bilibili.com/x/web-interface/view?bvid=BV195KY6YEeY"), eq(BiliBiliInfoResponse.class)))
                .thenReturn(response);

        assertEquals("BV195KY6YEeY",
                service.getDetail("116958703918865-40168587741", "gui").getList().get(0).getVod_id());
        assertEquals("BV195KY6YEeY",
                service.getDetail("116958703918865", "gui").getList().get(0).getVod_id());
    }


    @Test
    void getUpMediaSendsCookieHeaderToSpaceArcSearch() throws Exception {
        // 空间投稿接口无 Cookie 直接 412 回 HTML(JsonParseException '<'),回归点=entity 头必须随 OkHttp 请求发出
        OkHttpClient httpClient = Mockito.mock(OkHttpClient.class);
        Call call = Mockito.mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://api.bilibili.com/x/space/wbi/arc/search").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("{\"code\":0,\"data\":{\"list\":{\"vlist\":[]},\"page\":{\"count\":0}}}",
                        MediaType.parse("application/json")))
                .build();
        when(httpClient.newCall(org.mockito.ArgumentMatchers.any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
        ReflectionTestUtils.setField(service, "client", httpClient);
        when(biliCookieRefreshService.refreshIfNeeded(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(settingRepository.findById(cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE))
                .thenReturn(java.util.Optional.of(new cn.har01d.alist_tvbox.entity.Setting(
                        cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE, BiliBiliUtils.getCookie())));

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            service.getUpMedia("21384754", "pubdate", 1);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        org.mockito.ArgumentCaptor<Request> captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(httpClient).newCall(captor.capture());
        Request sent = captor.getValue();
        assertFalse(StringUtils.isBlank(sent.header("Cookie")), "space arc-search 必须携带 Cookie,否则风控 412 回 HTML");
        assertEquals("https://space.bilibili.com", sent.header("Referer"));
    }

    private void stubGetJson(String urlPrefix, String body) {
        try {
            when(restTemplate.exchange(startsWith(urlPrefix), eq(HttpMethod.GET), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                    .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(body)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getPlayUrlReturnsDetailActionsForGuiClient() throws Exception {
        stubPlayUrl();
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":1}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/coins", "{\"code\":0,\"data\":{\"multiply\":2}}");
        stubGetJson("https://api.bilibili.com/x/v2/fav/video/favoured", "{\"code\":0,\"data\":{\"favoured\":true}}");

        Map<String, Object> result = service.getPlayUrl("116958703918865-40168587741", true, "gui");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) result.get("actions");
        assertEquals(3, actions.size());
        assertEquals(Map.of("id", "like", "icon", "like", "label", "已点赞", "active", true, "enabled", true, "tooltip", "已点赞,点击取消"), actions.get(0));
        assertEquals(Map.of("id", "coin", "icon", "coin", "label", "已投币×2", "active", true, "enabled", false, "tooltip", "已投 2/2 枚"), actions.get(1));
        assertEquals(Map.of("id", "favorite", "icon", "favorite", "label", "已收藏", "active", true, "enabled", true, "tooltip", "已收藏(默认收藏夹),点击取消"), actions.get(2));
    }

    @Test
    void getPlayUrlOmitsActionsForOtherClients() throws Exception {
        stubPlayUrl();

        Map<String, Object> result = service.getPlayUrl("116958703918865-40168587741", true, "com.github.tvbox.osc");

        assertTrue(!result.containsKey("actions"));
    }

    @Test
    void getPlayUrlDisablesActionsWithoutCsrf() throws Exception {
        stubPlayUrl();
        when(settingRepository.findById(cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE))
                .thenReturn(java.util.Optional.of(new cn.har01d.alist_tvbox.entity.Setting(
                        cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE, "buvid3=abc; SESSDATA=xyz")));

        Map<String, Object> result = service.getPlayUrl("116958703918865-40168587741", true, "gui");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) result.get("actions");
        assertEquals(3, actions.size());
        for (Map<String, Object> actionMap : actions) {
            assertEquals(false, actionMap.get("enabled"));
            assertEquals("未登录 B站,请先在设置中配置 Cookie", actionMap.get("tooltip"));
        }
    }

    @Test
    void getPlayUrlDisablesActionsWhenCookieExpired() throws Exception {
        stubPlayUrl();
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":-101,\"message\":\"账号未登录\"}");

        Map<String, Object> result = service.getPlayUrl("116958703918865-40168587741", true, "gui");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) result.get("actions");
        assertEquals(3, actions.size());
        for (Map<String, Object> actionMap : actions) {
            assertEquals(false, actionMap.get("enabled"));
            assertEquals("B站账号未登录或已过期,请更新 Cookie", actionMap.get("tooltip"));
        }
    }

    @Test
    void runActionTogglesLikeBasedOnCurrentState() throws Exception {
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":1}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/coins", "{\"code\":0,\"data\":{\"multiply\":0}}");
        stubGetJson("https://api.bilibili.com/x/v2/fav/video/favoured", "{\"code\":0,\"data\":{\"favoured\":false}}");
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/web-interface/archive/like"), eq(HttpMethod.POST), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"code\":0}")));

        Map<String, Object> result = service.runAction("116958703918865-40168587741", "like");

        @SuppressWarnings("rawtypes")
        org.mockito.ArgumentCaptor<HttpEntity> captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://api.bilibili.com/x/web-interface/archive/like"), eq(HttpMethod.POST), captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class));
        @SuppressWarnings("unchecked")
        org.springframework.util.MultiValueMap<String, String> form = (org.springframework.util.MultiValueMap<String, String>) captor.getValue().getBody();
        assertEquals("116958703918865", form.getFirst("aid"));
        assertEquals("2", form.getFirst("like")); // 已点赞 → 取消
        assertTrue(form.getFirst("csrf") != null && !form.getFirst("csrf").isBlank());
        // 状态接口有延迟,回查可能仍是 0 —— 刷新清单必须由动作结果推导(已点赞 → 取消 → 未点赞)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refreshed = (List<Map<String, Object>>) result.get("actions");
        assertEquals(false, refreshed.get(0).get("active"));
        assertEquals("点赞", refreshed.get(0).get("label"));
    }

    @Test
    void runActionCoinRejectsAtLimit() throws Exception {
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/coins", "{\"code\":0,\"data\":{\"multiply\":2}}");

        org.junit.jupiter.api.Assertions.assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                () -> service.runAction("116958703918865-40168587741", "coin"));
    }

    @Test
    void runActionFavoriteDealsWithDefaultFolder() throws Exception {
        stubGetJson("https://api.bilibili.com/x/v3/fav/folder/created/list-all",
                "{\"code\":0,\"data\":{\"list\":[{\"id\":44233921,\"title\":\"默认收藏夹\",\"fav_state\":0},{\"id\":999,\"title\":\"观影\",\"fav_state\":1}]}}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":0}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/coins", "{\"code\":0,\"data\":{\"multiply\":0}}");
        stubGetJson("https://api.bilibili.com/x/v2/fav/video/favoured", "{\"code\":0,\"data\":{\"favoured\":false}}");
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/v3/fav/resource/deal"), eq(HttpMethod.POST), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"code\":0}")));

        Map<String, Object> favResult = service.runAction("BV195KY6YEeY", "favorite");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> favRefreshed = (List<Map<String, Object>>) favResult.get("actions");
        assertEquals(true, favRefreshed.get(2).get("active"));
        assertEquals("已收藏", favRefreshed.get(2).get("label"));

        @SuppressWarnings("rawtypes")
        org.mockito.ArgumentCaptor<HttpEntity> captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://api.bilibili.com/x/v3/fav/resource/deal"), eq(HttpMethod.POST), captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class));
        @SuppressWarnings("unchecked")
        org.springframework.util.MultiValueMap<String, String> form = (org.springframework.util.MultiValueMap<String, String>) captor.getValue().getBody();
        assertEquals("116958703918865", form.getFirst("rid")); // BV → aid
        assertEquals("2", form.getFirst("type"));
        assertEquals("44233921", form.getFirst("add_media_ids")); // 默认收藏夹,未收藏 → add
    }

    @Test
    void runActionLikeDirectionFollowsLoadedStateNotFlakyQuery() throws Exception {
        // 加载时已点赞(按钮所见);点击瞬间 has/like 抖动回 0 —— 方向必须仍按快照取消,否则要点两次
        stubPlayUrl();
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":1}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/coins", "{\"code\":0,\"data\":{\"multiply\":0}}");
        stubGetJson("https://api.bilibili.com/x/v2/fav/video/favoured", "{\"code\":0,\"data\":{\"favoured\":false}}");
        service.getPlayUrl("116958703918865-40168587741", true, "gui");

        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":0}"); // 点击瞬间抖动
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/web-interface/archive/like"), eq(HttpMethod.POST), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"code\":0}")));

        Map<String, Object> result = service.runAction("116958703918865-40168587741", "like");

        @SuppressWarnings("rawtypes")
        org.mockito.ArgumentCaptor<HttpEntity> captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://api.bilibili.com/x/web-interface/archive/like"), eq(HttpMethod.POST), captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class));
        @SuppressWarnings("unchecked")
        org.springframework.util.MultiValueMap<String, String> form = (org.springframework.util.MultiValueMap<String, String>) captor.getValue().getBody();
        assertEquals("2", form.getFirst("like")); // 快照=已点赞 → 取消,不受抖动影响
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refreshed = (List<Map<String, Object>>) result.get("actions");
        assertEquals(false, refreshed.get(0).get("active"));
        assertEquals("点赞", refreshed.get(0).get("label"));
    }

    @Test
    void runActionCoinDerivesNewCoinCount() throws Exception {
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":0}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/coins", "{\"code\":0,\"data\":{\"multiply\":1}}");
        stubGetJson("https://api.bilibili.com/x/v2/fav/video/favoured", "{\"code\":0,\"data\":{\"favoured\":false}}");
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/web-interface/coin/add"), eq(HttpMethod.POST), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"code\":0}")));

        Map<String, Object> result = service.runAction("116958703918865-40168587741", "coin");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refreshed = (List<Map<String, Object>>) result.get("actions");
        assertEquals("已投币×2", refreshed.get(1).get("label"));
        assertEquals(false, refreshed.get(1).get("enabled")); // 满上限自动禁用
    }

    @Test
    void runActionRejectsUnknownActionAndBadId() {
        org.junit.jupiter.api.Assertions.assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                () -> service.runAction("116958703918865-40168587741", "share"));
        org.junit.jupiter.api.Assertions.assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                () -> service.runAction("abc", "like"));
    }

    @Test
    void runActionFetchesRealBuvid3WhenCookieLacksIt() throws Exception {
        // 风控要点:点赞/投币/收藏的 Cookie 必须带真实 buvid3,否则上游可能静默丢弃(返回成功但官网无效果)
        when(settingRepository.findById(cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE))
                .thenReturn(java.util.Optional.of(new cn.har01d.alist_tvbox.entity.Setting(
                        cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE, "SESSDATA=abc; bili_jct=xyz")));
        when(restTemplate.getForObject(eq("https://api.bilibili.com/x/web-frontend/getbuvid"), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(new ObjectMapper().readTree("{\"code\":0,\"data\":{\"buvid\":\"REAL-BUVID-123infoc\"}}"));
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":0}");
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/web-interface/archive/like"), eq(HttpMethod.POST), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"code\":0}")));

        service.runAction("116958703918865-40168587741", "like");

        @SuppressWarnings("rawtypes")
        org.mockito.ArgumentCaptor<HttpEntity> captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://api.bilibili.com/x/web-interface/archive/like"), eq(HttpMethod.POST), captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class));
        String cookieHeader = captor.getValue().getHeaders().getFirst("Cookie");
        assertTrue(cookieHeader != null && cookieHeader.contains("buvid3=REAL-BUVID-123infoc"), "Cookie 应并入 getbuvid 真值: " + cookieHeader);
        assertTrue(String.valueOf(captor.getValue().getHeaders().getFirst("Referer")).startsWith("https://www.bilibili.com/video/BV"));
        assertEquals("https://www.bilibili.com", captor.getValue().getHeaders().getFirst("Origin"));
    }

    @Test
    void getDetailAppendsActionsToFirstLineForNonGuiClients() throws Exception {
        stubInfoApi(videoInfo());

        MovieList detail = service.getDetail("BV195KY6YEeY", "com.fongmi.android.tv");

        // 动作并入第一条 BiliBili 线路(视频条目之后 # 追加),不单开操作线路;「互动状态」占位守在动作之前
        var movieDetail = detail.getList().get(0);
        assertFalse(movieDetail.getVod_play_from().contains("操作"), movieDetail.getVod_play_from());
        String firstLine = movieDetail.getVod_play_url().split("\\$\\$\\$", -1)[0];
        assertTrue(firstLine.startsWith("视频$"), firstLine);
        assertTrue(firstLine.endsWith("互动状态$bilistat-116958703918865#点赞$bililike-116958703918865#投币$bilicoin-116958703918865#收藏$bilifav-116958703918865"), firstLine);
    }

    @Test
    void getDetailOmitsBiliActionsForGuiClient() throws Exception {
        stubInfoApi(videoInfo());

        MovieList detail = service.getDetail("BV195KY6YEeY", "gui");

        // atv-player 的动作按钮由 getPlayUrl 的 actions 键下发,首线路不追加动作条目
        var movieDetail = detail.getList().get(0);
        assertFalse(movieDetail.getVod_play_url().contains("bililike-"), movieDetail.getVod_play_url());
        assertFalse(movieDetail.getVod_play_from().contains("操作"), movieDetail.getVod_play_from());
    }

    @Test
    void getDetailPutsActionsOnSeparateLineWhenEnabled() throws Exception {
        stubInfoApi(videoInfo());
        when(appProperties.isActionSeparateLine()).thenReturn(true);

        MovieList detail = service.getDetail("BV195KY6YEeY", "com.fongmi.android.tv");

        // 开关开启(bilibili_action_separate_line):动作单独「操作」线路置于末位,第一条线路保持纯视频条目
        var movieDetail = detail.getList().get(0);
        assertTrue(movieDetail.getVod_play_from().endsWith("$$$操作"), movieDetail.getVod_play_from());
        String[] lines = movieDetail.getVod_play_url().split("\\$\\$\\$", -1);
        assertTrue(lines[0].startsWith("视频$"), lines[0]);
        assertFalse(lines[0].contains("bililike-"), lines[0]);
        assertEquals("互动状态$bilistat-116958703918865#点赞$bililike-116958703918865#投币$bilicoin-116958703918865#收藏$bilifav-116958703918865",
                lines[lines.length - 1]);
    }

    @Test
    void getActionStatusTextReportsCurrentState() throws Exception {
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":1}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/coins", "{\"code\":0,\"data\":{\"multiply\":1}}");
        stubGetJson("https://api.bilibili.com/x/v2/fav/video/favoured", "{\"code\":0,\"data\":{\"favoured\":true}}");

        assertEquals("点赞: 已点赞  投币: 已投 1/2 枚  收藏: 已收藏", service.getActionStatusText("116958703918865"));
    }

    @Test
    void getActionStatusTextShowsReasonWhenNotLoggedIn() throws Exception {
        when(settingRepository.findById(cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE))
                .thenReturn(java.util.Optional.of(new cn.har01d.alist_tvbox.entity.Setting(
                        cn.har01d.alist_tvbox.util.Constants.BILIBILI_COOKIE, "buvid3=abc; SESSDATA=xyz")));

        assertEquals("未登录 B站,请先在设置中配置 Cookie", service.getActionStatusText("116958703918865"));
    }

    @Test
    void runActionTextDerivesLikeResult() throws Exception {
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":0}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/coins", "{\"code\":0,\"data\":{\"multiply\":0}}");
        stubGetJson("https://api.bilibili.com/x/v2/fav/video/favoured", "{\"code\":0,\"data\":{\"favoured\":false}}");
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/web-interface/archive/like"), eq(HttpMethod.POST), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"code\":0}")));

        assertEquals("点赞成功", service.runActionText("116958703918865-40168587741", "like"));
    }

    @Test
    void runActionTextCoinReportsNewCount() throws Exception {
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":0}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/coins", "{\"code\":0,\"data\":{\"multiply\":1}}");
        stubGetJson("https://api.bilibili.com/x/v2/fav/video/favoured", "{\"code\":0,\"data\":{\"favoured\":false}}");
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/web-interface/coin/add"), eq(HttpMethod.POST), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"code\":0}")));

        assertEquals("投币成功: 已投 2/2 枚", service.runActionText("116958703918865", "coin"));
    }

    @Test
    void runActionTextFavoriteReportsToggle() throws Exception {
        stubGetJson("https://api.bilibili.com/x/v3/fav/folder/created/list-all",
                "{\"code\":0,\"data\":{\"list\":[{\"id\":44233921,\"title\":\"默认收藏夹\",\"fav_state\":0}]}}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/has/like", "{\"code\":0,\"data\":0}");
        stubGetJson("https://api.bilibili.com/x/web-interface/archive/coins", "{\"code\":0,\"data\":{\"multiply\":0}}");
        stubGetJson("https://api.bilibili.com/x/v2/fav/video/favoured", "{\"code\":0,\"data\":{\"favoured\":false}}");
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/v3/fav/resource/deal"), eq(HttpMethod.POST), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"code\":0}")));

        assertEquals("收藏成功(默认收藏夹)", service.runActionText("BV195KY6YEeY", "favorite"));
    }

    @Test
    void getWatchLaterMapsItemsWithProgress() throws Exception {
        String json = "{\"code\":0,\"data\":{\"count\":2,\"list\":["
                + "{\"aid\":1,\"bvid\":\"BV1aa\",\"title\":\"看了一半\",\"pic\":\"http://pic/1.jpg\",\"duration\":600,\"progress\":120,\"add_at\":1700000000,\"owner\":{\"mid\":9,\"name\":\"up主\"}},"
                + "{\"aid\":2,\"bvid\":\"BV2bb\",\"title\":\"还没看\",\"pic\":\"http://pic/2.jpg\",\"duration\":300,\"progress\":0,\"add_at\":1700000001,\"owner\":{\"mid\":9,\"name\":\"up主\"}}"
                + "]}}";
        BiliBiliWatchLaterResponse response = new ObjectMapper().readValue(json, BiliBiliWatchLaterResponse.class);
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/v2/history/toview/web"), eq(HttpMethod.GET), any(), eq(BiliBiliWatchLaterResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        MovieList result = service.getWatchLater(1);

        assertEquals(2, result.getList().size());
        assertEquals(2, result.getTotal());
        assertEquals(1, result.getPagecount());
        assertEquals("BV1aa", result.getList().get(0).getVod_id());
        assertEquals("看了一半", result.getList().get(0).getVod_name());
        assertEquals("已看02:00/10:00", result.getList().get(0).getVod_remarks());
        assertEquals("up主", result.getList().get(0).getVod_director());
        assertEquals("05:00", result.getList().get(1).getVod_remarks());
    }

    @Test
    void getWatchLaterBeyondFirstPageIsEmpty() {
        MovieList result = service.getWatchLater(2);

        assertTrue(result.getList().isEmpty());
        assertEquals(1, result.getPagecount());
        Mockito.verify(restTemplate, Mockito.never())
                .exchange(anyString(), eq(HttpMethod.GET), any(), eq(BiliBiliWatchLaterResponse.class));
    }

    @Test
    void getWatchLaterToleratesNotLoggedIn() {
        BiliBiliWatchLaterResponse response = new BiliBiliWatchLaterResponse();
        response.setCode(-101);
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/v2/history/toview/web"), eq(HttpMethod.GET), any(), eq(BiliBiliWatchLaterResponse.class)))
                .thenReturn(ResponseEntity.ok(response));

        MovieList result = service.getWatchLater(1);

        assertTrue(result.getList().isEmpty());
    }

    @Test
    void viewApiUgcSeasonDeserializes() throws Exception {
        // 实测 episode 无顶层 duration,时长在 arc.duration(秒);多P成员的分P全集在 pages[](各P独立 cid)
        String json = "{\"aid\":1,\"bvid\":\"BV1\",\"title\":\"t\",\"ugc_season\":{\"id\":748,\"title\":\"合集名\",\"mid\":9,"
                + "\"sections\":[{\"id\":1,\"title\":\"正片\",\"episodes\":[{\"aid\":10,\"bvid\":\"BV10\",\"cid\":100,\"title\":\"第一集\","
                + "\"arc\":{\"duration\":346},\"pages\":[{\"page\":1,\"part\":\"上\",\"cid\":100,\"duration\":45},{\"page\":2,\"part\":\"下\",\"cid\":101,\"duration\":301}]}]},"
                + "{\"id\":2,\"title\":\"花絮\",\"episodes\":[]}]}}";
        BiliBiliInfo info = new ObjectMapper().readValue(json, BiliBiliInfo.class);

        assertEquals("合集名", info.getUgcSeason().getTitle());
        assertEquals(2, info.getUgcSeason().getSections().size());
        BiliBiliInfo.UgcSeason.Episode episode = info.getUgcSeason().getSections().get(0).getEpisodes().get(0);
        assertEquals("第一集", episode.getTitle());
        assertEquals(100L, episode.getCid());
        assertEquals(10L, episode.getAid());
        assertEquals(346L, episode.getDuration());
        assertEquals(2, episode.getPages().size());
        assertEquals(101L, episode.getPages().get(1).getCid());
        assertEquals("下", episode.getPages().get(1).getPart());
    }

    private BiliBiliInfo.UgcSeason.Arc arcOf(long seconds) {
        BiliBiliInfo.UgcSeason.Arc arc = new BiliBiliInfo.UgcSeason.Arc();
        arc.setDuration(seconds);
        return arc;
    }

    @Test
    void getDetailAppendsUgcSeasonLineBeforeRelated() throws Exception {
        BiliBiliInfo info = videoInfo();
        BiliBiliInfo.UgcSeason season = new BiliBiliInfo.UgcSeason();
        season.setId(74800L);
        season.setTitle("修仙合集");
        BiliBiliInfo.UgcSeason.Section main = new BiliBiliInfo.UgcSeason.Section();
        main.setId(1L);
        main.setTitle("正片");
        BiliBiliInfo.UgcSeason.Episode first = new BiliBiliInfo.UgcSeason.Episode();
        first.setAid(1130000001L);
        first.setBvid("BV1first");
        first.setCid(1500000001L);
        first.setTitle("1 初入宗门");
        first.setArc(arcOf(631L));
        BiliBiliInfo.UgcSeason.Episode second = new BiliBiliInfo.UgcSeason.Episode();
        second.setAid(116958703918865L);
        second.setBvid("BV195KY6YEeY");
        second.setCid(40168587741L);
        second.setTitle("2 突破金丹#特辑$");
        second.setArc(arcOf(4531L));
        main.setEpisodes(List.of(first, second));
        BiliBiliInfo.UgcSeason.Section extra = new BiliBiliInfo.UgcSeason.Section();
        extra.setId(2L);
        extra.setTitle("花絮");
        BiliBiliInfo.UgcSeason.Episode bonus = new BiliBiliInfo.UgcSeason.Episode();
        bonus.setAid(1130000003L);
        bonus.setBvid("BV1bonus");
        bonus.setCid(1500000003L);
        bonus.setTitle("幕后");
        bonus.setArc(arcOf(90L));
        extra.setEpisodes(List.of(bonus));
        season.setSections(List.of(main, extra));
        info.setUgcSeason(season);
        stubInfoApi(info);

        BiliBiliInfo related = new BiliBiliInfo();
        related.setAid(1130000009L);
        related.setCid(1500000009L);
        related.setTitle("相关推荐");
        BiliBiliRelatedResponse relatedResponse = new BiliBiliRelatedResponse();
        relatedResponse.setData(List.of(related));
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/archive/related"), eq(HttpMethod.GET), any(), eq(BiliBiliRelatedResponse.class)))
                .thenReturn(ResponseEntity.ok(relatedResponse));

        cn.har01d.alist_tvbox.tvbox.MovieDetail movie = service.getDetail("BV195KY6YEeY", "com.github.tvbox.osc").getList().get(0);

        int seasonIdx = movie.getVod_play_from().indexOf("$$$合集·修仙合集");
        int relatedIdx = movie.getVod_play_from().indexOf("$$$相关视频");
        assertTrue(seasonIdx > 0);
        assertTrue(seasonIdx < relatedIdx);
        String playUrl = movie.getVod_play_url();
        // 当前集 ▶ 前缀;#/$ 经 fixTitle 清洗;条目载荷 aid-cid 与相关视频线路同款
        assertTrue(playUrl.contains("【正片】1 初入宗门$1130000001-1500000001"));
        assertTrue(playUrl.contains("▶ 【正片】2 突破金丹 特辑$116958703918865-40168587741"));
        assertTrue(playUrl.contains("【花絮】幕后$1130000003-1500000003"));

        // gui(atv-player)条目带时长后缀——时长取 arc.duration,曾因映射不存在顶层字段恒为 0
        String guiPlayUrl = service.getDetail("BV195KY6YEeY", "gui").getList().get(0).getVod_play_url();
        assertTrue(guiPlayUrl.contains("【正片】1 初入宗门(10:31)$1130000001-1500000001"));
        assertTrue(guiPlayUrl.contains("▶ 【正片】2 突破金丹 特辑(01:15:31)$116958703918865-40168587741"));
    }

    private BiliBiliInfo.PageInfo pageOf(int page, String part, long cid, long duration) {
        BiliBiliInfo.PageInfo pageInfo = new BiliBiliInfo.PageInfo();
        pageInfo.setPage(page);
        pageInfo.setPart(part);
        pageInfo.setCid(cid);
        pageInfo.setDuration(duration);
        return pageInfo;
    }

    @Test
    void getDetailExpandsMultiPageSeasonEpisodes() throws Exception {
        // 实测形态(创造101 BV1ES7D6XEZy):合集成员自身带分P,episode.cid 只锚 P1,
        // 不展开则合集线连播只播每成员首个分P(该视频 P1 恰为 45 秒短片即跳下一成员)
        BiliBiliInfo info = videoInfo();
        BiliBiliInfo.UgcSeason season = new BiliBiliInfo.UgcSeason();
        season.setId(8319251L);
        season.setTitle("创造101");
        BiliBiliInfo.UgcSeason.Section main = new BiliBiliInfo.UgcSeason.Section();
        main.setId(1L);
        main.setTitle("正片");
        BiliBiliInfo.UgcSeason.Episode multi = new BiliBiliInfo.UgcSeason.Episode();
        multi.setAid(116702348056416L);
        multi.setBvid("BV195KY6YEeY"); // 当前视频=多P成员
        multi.setCid(38905774565L);
        multi.setTitle("EP1上");
        multi.setArc(arcOf(5305L));
        multi.setPages(List.of(
                pageOf(1, "xbb", 38905774565L, 45),
                pageOf(2, "真1", 38906171035L, 301)));
        BiliBiliInfo.UgcSeason.Episode single = new BiliBiliInfo.UgcSeason.Episode();
        single.setAid(116736204478395L);
        single.setBvid("BV1BDE166Esn");
        single.setCid(39363544915L);
        single.setTitle("EP1下");
        single.setArc(arcOf(301L));
        main.setEpisodes(List.of(multi, single));
        season.setSections(List.of(main));
        info.setUgcSeason(season);
        stubInfoApi(info);

        cn.har01d.alist_tvbox.tvbox.MovieDetail movie = service.getDetail("BV195KY6YEeY", "com.github.tvbox.osc").getList().get(0);

        String playUrl = movie.getVod_play_url();
        // 多P成员按分P展开、载荷 aid-各P cid;▶ 只标当前视频首个分P
        assertTrue(playUrl.contains("▶ EP1上 P1 xbb$116702348056416-38905774565"));
        assertTrue(playUrl.contains("EP1上 P2 真1$116702348056416-38906171035"));
        // 单P成员不展开、条目无 P 标(与既有形态一致)
        assertTrue(playUrl.contains("EP1下$116736204478395-39363544915"));

        // gui 时长后缀=各分P自身时长(P1=45 秒非整视频 01:28:25)
        String guiPlayUrl = service.getDetail("BV195KY6YEeY", "gui").getList().get(0).getVod_play_url();
        assertTrue(guiPlayUrl.contains("▶ EP1上 P1 xbb(0:45)$116702348056416-38905774565"));
        assertTrue(guiPlayUrl.contains("EP1上 P2 真1(05:01)$116702348056416-38906171035"));
    }

    // ==== B 站分区改版(2026-10):dynamic/region 与 newlist_rank 下线、view 接口 tname 清空 的替代链路 ====

    /** 列表页封面 getListPic 依赖 fromCurrentRequest,单测线程须伪造请求上下文 */
    private MovieList withRequestContext(java.util.concurrent.Callable<MovieList> call) throws Exception {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            return call.call();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private BiliBiliListResponse newlistResponse(BiliBiliInfo... archives) {
        BiliBiliList list = new BiliBiliList();
        list.setArchives(new ArrayList<>(List.of(archives)));
        list.setPage(new BiliBiliList.Page()); // newlist 恒回 count=0
        BiliBiliListResponse response = new BiliBiliListResponse();
        response.setData(list);
        return response;
    }

    private BiliBiliInfo archive(int tid, String bvid) {
        BiliBiliInfo info = new BiliBiliInfo();
        info.setTid(tid);
        info.setBvid(bvid);
        info.setTitle("视频" + bvid);
        info.setDuration(60);
        return info;
    }

    @Test
    void getRegionFallsBackToFixedDepthWhenNewlistOmitsTotal() throws Exception {
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/newlist"), eq(HttpMethod.GET), any(), eq(BiliBiliListResponse.class)))
                .thenReturn(ResponseEntity.ok(newlistResponse(archive(130, "BV1sub1"), archive(28, "BV1sub2"))));

        MovieList result = withRequestContext(() -> service.getRegion("3", 1));

        assertEquals(3, result.getList().size()); // 合集 + 2 条
        assertEquals("region$3$0$1", result.getList().get(0).getVod_id());
        assertTrue(result.getTotal() > 0); // page.count 恒 0,须兜底固定翻页深度
    }

    private BiliBiliHotResponse rankResponse(int count, int tid) {
        List<BiliBiliInfo> items = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            items.add(archive(tid, "BV1rank" + i));
        }
        BiliBiliHot hot = new BiliBiliHot();
        hot.setList(items);
        BiliBiliHotResponse response = new BiliBiliHotResponse();
        response.setData(hot);
        return response;
    }

    @Test
    void getRegionSlicesInCategoryRankingForRemovedRegions() throws Exception {
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/ranking/v2"), eq(HttpMethod.GET), any(), eq(BiliBiliHotResponse.class)))
                .thenReturn(ResponseEntity.ok(rankResponse(35, 218))); // 动物圈 217 已撤:newlist 无流,热榜兜底

        MovieList page1 = withRequestContext(() -> service.getRegion("217", 1));
        assertEquals(31, page1.getList().size()); // 合集 + 30 条切片
        assertEquals(2, page1.getPagecount()); // 35 条 → 2 页

        MovieList page2 = withRequestContext(() -> service.getRegion("217", 2));
        assertEquals(6, page2.getList().size()); // 合集 + 剩余 5 条

        MovieList page3 = withRequestContext(() -> service.getRegion("217", 3));
        assertEquals(0, page3.getList().size()); // 越界空页,客户端停止翻页
        verify(restTemplate, never()).exchange(startsWith("https://api.bilibili.com/x/web-interface/newlist"), eq(HttpMethod.GET), any(), eq(BiliBiliListResponse.class));
    }

    @Test
    void subRegionOfRemovedRegionAlsoUsesParentRanking() throws Exception {
        when(navigationService.getParentValue("218")).thenReturn("217"); // 喵星人 → 动物圈(已撤)
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/ranking/v2"), eq(HttpMethod.GET), any(), eq(BiliBiliHotResponse.class)))
                .thenReturn(ResponseEntity.ok(rankResponse(20, 218)));

        FilterDto filter = new FilterDto();
        filter.setCategory("218");

        MovieList result = withRequestContext(() -> service.getMovieList("217", filter, 1, ""));
        assertEquals(21, result.getList().size()); // 合集 + 20 条全命中

        MovieList page2 = withRequestContext(() -> service.getMovieList("217", filter, 2, ""));
        assertEquals(0, page2.getList().size()); // 子分区仅第 1 页,第 2 页起空页
        verify(restTemplate, never()).exchange(startsWith("https://api.bilibili.com/x/web-interface/newlist"), eq(HttpMethod.GET), any(), eq(BiliBiliListResponse.class));
    }

    @Test
    void getHotRankToleratesNullDataFromRiskControl() throws Exception {
        // ranking/v2 裸请求恒 -352(data=null),改版后必须带浏览器头且不得 NPE
        BiliBiliHotResponse rejected = new BiliBiliHotResponse();
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/ranking/v2"), eq(HttpMethod.GET), any(), eq(BiliBiliHotResponse.class)))
                .thenReturn(ResponseEntity.ok(rejected));

        assertTrue(service.getHotRank("all", 223, 1).isEmpty());

        MovieList result = withRequestContext(() -> service.getRegion("223", 1));
        assertEquals(0, result.getList().size()); // 空页,不再 500
    }

    @Test
    void originAndRookieBoardsGoThroughHeadedRankingRequest() throws Exception {
        // 原创(origin$0)/新人(rookie$0)与撤区分类同走 getHotRank,改版后必须带头请求
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/ranking/v2"), eq(HttpMethod.GET), any(), eq(BiliBiliHotResponse.class)))
                .thenReturn(ResponseEntity.ok(rankResponse(25, 21)));

        assertEquals(25, withRequestContext(() -> service.getMovieList("origin$0", new FilterDto(), 1, "")).getList().size());
        assertEquals(25, withRequestContext(() -> service.getMovieList("rookie$0", new FilterDto(), 1, "")).getList().size());
        verify(restTemplate, never()).getForObject(startsWith("https://api.bilibili.com/x/web-interface/ranking"), eq(BiliBiliHotResponse.class));
    }

    @Test
    void newlistRiskControlReturnsEmptyAndTripsCircuitBreaker() throws Exception {
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/newlist"), eq(HttpMethod.GET), any(), eq(BiliBiliListResponse.class)))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.PRECONDITION_FAILED));

        MovieList first = withRequestContext(() -> service.getRegion("11", 1));
        assertEquals(0, first.getList().size()); // 412 HTML 挑战页降级为空页,不再 500 也不出空合集

        MovieList second = withRequestContext(() -> service.getRegion("11", 2));
        assertEquals(0, second.getList().size()); // 熔断冷却期内直接返空,不再撞接口
        verify(restTemplate, org.mockito.Mockito.times(1)).exchange(startsWith("https://api.bilibili.com/x/web-interface/newlist"), eq(HttpMethod.GET), any(), eq(BiliBiliListResponse.class));
    }

    @Test
    void newlistPagesAreCachedToReduceRequestFanout() throws Exception {
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/newlist"), eq(HttpMethod.GET), any(), eq(BiliBiliListResponse.class)))
                .thenReturn(ResponseEntity.ok(newlistResponse(archive(130, "BV1cached"))));

        MovieList first = withRequestContext(() -> service.getRegion("3", 1));
        MovieList again = withRequestContext(() -> service.getRegion("3", 1));

        assertEquals(2, first.getList().size());
        assertEquals(first.getList().size(), again.getList().size());
        // 同 (rid,page) 短缓存命中,只发一次请求
        verify(restTemplate, org.mockito.Mockito.times(1)).exchange(startsWith("https://api.bilibili.com/x/web-interface/newlist"), eq(HttpMethod.GET), any(), eq(BiliBiliListResponse.class));
    }

    @Test
    void subRegionLatestUsesParentRankingFilteredAndSortedByPubdate() throws Exception {
        when(navigationService.getParentValue("130")).thenReturn("3");
        BiliBiliInfo older = archive(130, "BV1older"); // 榜位靠前但发布早
        older.setPubdate(100);
        BiliBiliInfo newer = archive(130, "BV1newer"); // 榜位靠后但发布晚
        newer.setPubdate(200);
        BiliBiliHotResponse hotResponse = new BiliBiliHotResponse();
        BiliBiliHot hot = new BiliBiliHot();
        hot.setList(new ArrayList<>(List.of(older, archive(28, "BV1other1"), newer)));
        hotResponse.setData(hot);
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/ranking/v2"), eq(HttpMethod.GET), any(), eq(BiliBiliHotResponse.class)))
                .thenReturn(ResponseEntity.ok(hotResponse));

        FilterDto filter = new FilterDto();
        filter.setCategory("130"); // 子分区:音乐综合,「最新」档

        MovieList result = withRequestContext(() -> service.getMovieList("3", filter, 1, ""));

        assertEquals(3, result.getList().size()); // 合集 + 2 条命中(非 130 被滤掉)
        assertEquals("type$130$$1", result.getList().get(0).getVod_id());
        assertEquals("视频BV1newer", result.getList().get(1).getVod_name()); // 「最新」按发布时间倒序
        assertEquals("视频BV1older", result.getList().get(2).getVod_name());
        assertEquals(1, result.getPagecount());

        MovieList page2 = withRequestContext(() -> service.getMovieList("3", filter, 2, ""));
        assertEquals(0, page2.getList().size()); // 单请求无扇出,仅第 1 页
        verify(restTemplate, never()).exchange(startsWith("https://api.bilibili.com/x/web-interface/newlist"), eq(HttpMethod.GET), any(), eq(BiliBiliListResponse.class));
    }

    @Test
    void getMovieListSubRegionHotFiltersParentRanking() throws Exception {
        when(navigationService.getParentValue("130")).thenReturn("3");
        BiliBiliHotResponse hotResponse = new BiliBiliHotResponse();
        BiliBiliHot hot = new BiliBiliHot();
        hot.setList(new ArrayList<>(List.of(archive(130, "BV1hot1"), archive(28, "BV1hot2"))));
        hotResponse.setData(hot);
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/ranking/v2"), eq(HttpMethod.GET), any(), eq(BiliBiliHotResponse.class)))
                .thenReturn(ResponseEntity.ok(hotResponse));

        FilterDto filter = new FilterDto();
        filter.setCategory("130");
        filter.setType("hot");

        MovieList result = withRequestContext(() -> service.getMovieList("3", filter, 1, ""));

        assertEquals(2, result.getList().size()); // 合集 + 1 条命中
        assertEquals("type$130$hot$1", result.getList().get(0).getVod_id());
        assertEquals(1, result.getPagecount());
    }

    @Test
    void getTypePlaylistMirrorsSubRegionListing() throws Exception {
        when(navigationService.getParentValue("130")).thenReturn("3");
        BiliBiliHotResponse hotResponse = new BiliBiliHotResponse();
        BiliBiliHot hot = new BiliBiliHot();
        hot.setList(new ArrayList<>(List.of(archive(130, "BV1hot1"))));
        hotResponse.setData(hot);
        when(restTemplate.exchange(startsWith("https://api.bilibili.com/x/web-interface/ranking/v2"), eq(HttpMethod.GET), any(), eq(BiliBiliHotResponse.class)))
                .thenReturn(ResponseEntity.ok(hotResponse));

        MovieList result = withRequestContext(() -> service.getTypePlaylist("type$130$hot$1"));

        assertEquals("type$130$0$1", result.getList().get(0).getVod_id());
        assertTrue(result.getList().get(0).getVod_play_url().contains("视频BV1hot1$"));
    }

    @Test
    void typeNameFallsBackToNavigationLookup() throws Exception {
        BiliBiliInfo info = videoInfo();
        info.setTname(""); // view 接口 tname 已被 B 站清空
        info.setTname_v2("");
        info.setTid(21);
        when(navigationService.getNameByValue("21")).thenReturn("日常");
        stubInfoApi(info);

        assertEquals("日常", service.getDetail("BV195KY6YEeY", "").getList().get(0).getType_name());
    }

    @Test
    void typeNameKeepsUpstreamValueWhenPresent() throws Exception {
        stubInfoApi(videoInfo()); // tname=动画 tname_v2=短片

        assertEquals("动画 / 短片", service.getDetail("BV195KY6YEeY", "").getList().get(0).getType_name());
    }

    @Test
    void getCommentsReturnsMainListWithTopMergeAndCursor() throws Exception {
        String body = """
                {"code":0,"data":{
                  "cursor":{"all_count":962,"is_end":false,
                    "pagination_reply":{"next_offset":"{\\"type\\":3,\\"direction\\":1,\\"Data\\":{\\"cursor\\":71859}}"}},
                  "upper":{"mid":2},
                  "top":{"upper":{
                    "rpid_str":"1001","member":{"mid":"2","uname":"UP主","avatar":"https://i0.hdslb.com/face/up.jpg",
                      "level_info":{"current_level":6}},
                    "content":{"message":"置顶说明"},"like":99,"rcount":3,"ctime":1700000000,"action":1,
                    "reply_control":{"time_desc":"3天前发布","location":"IP属地：上海"},"replies":[]}},
                  "replies":[{
                    "rpid_str":"1002","member":{"mid":"42","uname":"小明","avatar":"https://i0.hdslb.com/face/a.jpg",
                      "level_info":{"current_level":4}},
                    "content":{"message":"这个视频太好了[doge]","emote":{"[doge]":{
                        "url":"https://i0.hdslb.com/bfs/emote/3087d273.png","meta":{"size":1}}},
                      "pictures":[{"img_src":"https://i0.hdslb.com/bfs/new_dyn/1.jpg","img_width":800,"img_height":600}]},
                    "like":12000,"rcount":2,"ctime":1700000100,
                    "reply_control":{"time_desc":"2天前发布","location":"IP属地：河北"},
                    "replies":[{
                      "rpid_str":"1003","parent_str":"1002","member":{"mid":"43","uname":"小刚",
                        "avatar":"https://i0.hdslb.com/face/b.jpg","level_info":{"current_level":3}},
                      "content":{"message":"确实"},"like":5,"rcount":0,"ctime":1700000200,
                      "reply_control":{"time_desc":"2天前发布"},"replies":[]},
                      {"rpid_str":"1004","parent_str":"1003","member":{"mid":"2","uname":"UP主",
                        "avatar":"https://i0.hdslb.com/face/up.jpg","level_info":{"current_level":6}},
                      "content":{"message":"回复小刚"},"like":8,"rcount":0,"ctime":1700000300,
                      "reply_control":{"time_desc":"1天前发布"},"replies":[]}]}]}}
                """;
        org.mockito.ArgumentCaptor<HttpEntity<Void>> captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(any(java.net.URI.class), eq(HttpMethod.GET),
                captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(body)));

        Map<String, Object> result = service.getComments("BV195KY6YEeY", 3, "", "", 1);

        assertEquals(962, result.get("count"));
        assertEquals(false, result.get("is_end"));
        assertEquals("{\"type\":3,\"direction\":1,\"Data\":{\"cursor\":71859}}", result.get("next_offset"));
        List<Map<String, Object>> comments = (List<Map<String, Object>>) result.get("comments");
        assertEquals(2, comments.size());
        // 置顶合并到首位,top 标记;UP 主回复带 is_up
        assertEquals("1001", comments.get(0).get("rpid"));
        assertEquals(true, comments.get(0).get("top"));
        assertEquals(true, comments.get(0).get("is_up"));
        assertEquals(true, comments.get(0).get("liked"));
        assertEquals(false, comments.get(1).get("liked"));
        assertEquals(false, comments.get(1).get("is_up"));
        // 表情与图片评论透传
        List<Map<String, Object>> emotes = (List<Map<String, Object>>) comments.get(1).get("emotes");
        assertEquals(1, emotes.size());
        assertEquals("[doge]", emotes.get(0).get("text"));
        assertEquals("https://i0.hdslb.com/bfs/emote/3087d273.png", emotes.get(0).get("url"));
        assertEquals(1, emotes.get(0).get("size"));
        List<Map<String, Object>> pictures = (List<Map<String, Object>>) comments.get(1).get("pictures");
        assertEquals(1, pictures.size());
        assertEquals("https://i0.hdslb.com/bfs/new_dyn/1.jpg", pictures.get(0).get("url"));
        assertEquals(800, pictures.get(0).get("width"));
        // 子回复预览:直答不带 parent_uname,层内互答带;UP 主身份透传
        List<Map<String, Object>> preview = (List<Map<String, Object>>) comments.get(1).get("preview");
        assertEquals("", preview.get(0).get("parent_uname"));
        assertEquals("小刚", preview.get(1).get("parent_uname"));
        assertEquals(true, preview.get(1).get("is_up"));
        // wbi 主列表必须带 cookie/UA 头
        org.junit.jupiter.api.Assertions.assertNotNull(captor.getValue().getHeaders().getFirst("Cookie"));
    }

    @Test
    void getCommentsMainListPassesCursorOffset() throws Exception {
        String body = """
                {"code":0,"data":{"cursor":{"all_count":10,"is_end":true,"pagination_reply":{"next_offset":""}},
                  "upper":{"mid":2},"top":{"upper":null},"replies":[]}}
                """;
        when(restTemplate.exchange(any(java.net.URI.class), eq(HttpMethod.GET),
                any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(body)));

        Map<String, Object> result = service.getComments("BV195KY6YEeY", 2,
                "{\"type\":3,\"direction\":1,\"Data\":{\"cursor\":71859}}", "", 1);

        assertEquals(10, result.get("count"));
        assertEquals(true, result.get("is_end"));
        assertTrue(((List<?>) result.get("comments")).isEmpty());
    }

    @Test
    void getCommentsPaginationStrStaysCompactForWbiSignature() throws Exception {
        // 根因回归:注入 ObjectMapper 开 INDENT_OUTPUT 时 pagination_str 会变成多行 JSON,
        // 空格经 form 编码为 '+' 与官方验签(空格 %20)不一致 → 上游 -403;必须紧凑序列化
        StringBuilder urlHolder = new StringBuilder();
        when(restTemplate.exchange(any(java.net.URI.class), eq(HttpMethod.GET),
                any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenAnswer(invocation -> {
                    urlHolder.append(invocation.getArgument(0, java.net.URI.class).toString());
                    return ResponseEntity.ok(new ObjectMapper().readTree(
                            "{\"code\":0,\"data\":{\"cursor\":{\"all_count\":10,\"is_end\":true,"
                                    + "\"pagination_reply\":{\"next_offset\":\"\"}},\"upper\":{\"mid\":2},"
                                    + "\"top\":{\"upper\":null},\"replies\":[]}}"));
                });

        service.getComments("BV195KY6YEeY", 3, "CAEaCAoG4Yez4ZMJIgIIAg==", "", 1);

        String url = urlHolder.toString();
        assertTrue(url.contains("pagination_str="), url);
        String encoded = url.substring(url.indexOf("pagination_str=") + "pagination_str=".length())
                .split("&")[0];
        assertFalse(encoded.contains("%0A"), "分页载荷不得含换行(美化输出): " + encoded);
        assertFalse(encoded.contains("+"), "分页载荷不得含 form 编码空格 '+': " + encoded);
        // 紧凑形态 {"offset":"..."}: %22offset%22%3A%22
        assertTrue(encoded.contains("%22offset%22%3A%22"), encoded);
    }

    @Test
    void getCommentsReturnsFloorRepliesWithParentNames() throws Exception {
        String body = """
                {"code":0,"data":{
                  "page":{"count":32,"num":1,"size":20},
                  "root":{"rpid_str":"1002"},
                  "upper":{"mid":2},
                  "replies":[{
                    "rpid_str":"2001","parent_str":"1002","member":{"mid":"43","uname":"小刚",
                      "avatar":"https://i0.hdslb.com/face/b.jpg","level_info":{"current_level":3}},
                    "content":{"message":"直答根评论"},"like":5,"rcount":0,"ctime":1700000200,
                    "reply_control":{"time_desc":"2天前发布"},"replies":[]},
                    {"rpid_str":"2002","parent_str":"2001","member":{"mid":"44","uname":"小强",
                      "avatar":"https://i0.hdslb.com/face/c.jpg","level_info":{"current_level":5}},
                    "content":{"message":"层内互答"},"like":6,"rcount":0,"ctime":1700000250,
                    "reply_control":{"time_desc":"2天前发布"},"replies":[]},
                    {"rpid_str":"2003","parent_str":"2001","member":{"mid":"2","uname":"UP主",
                      "avatar":"https://i0.hdslb.com/face/up.jpg","level_info":{"current_level":6}},
                    "content":{"message":"作者回复"},"like":7,"rcount":0,"ctime":1700000300,
                    "reply_control":{"time_desc":"1天前发布"},"replies":[]}],
                  "config":{},"control":{},"show_bvid":false,"show_text":"","show_type":0}}
                """;
        when(restTemplate.exchange(org.mockito.ArgumentMatchers.argThat((java.net.URI u) ->
                        u.toString().startsWith("https://api.bilibili.com/x/v2/reply/reply?type=1&oid=116958703918865&root=1002&pn=2")),
                eq(HttpMethod.GET), any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(body)));

        Map<String, Object> result = service.getComments("BV195KY6YEeY", 3, "", "1002", 2);

        assertEquals(32, result.get("count"));
        assertEquals(1, result.get("page"));
        List<Map<String, Object>> replies = (List<Map<String, Object>>) result.get("replies");
        assertEquals(3, replies.size());
        assertEquals("", replies.get(0).get("parent_uname"));
        assertEquals("小刚", replies.get(1).get("parent_uname"));
        assertEquals(true, replies.get(2).get("is_up"));
        // 主列表字段在楼中楼同结构透出
        assertEquals("作者回复", replies.get(2).get("message"));
    }

    @Test
    void getCommentsSurfacesUpstreamClosedError() throws Exception {
        when(restTemplate.exchange(any(java.net.URI.class), eq(HttpMethod.GET),
                any(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(
                        "{\"code\":12002,\"message\":\"评论区已关闭\"}")));

        cn.har01d.alist_tvbox.exception.BadRequestException ex =
                org.junit.jupiter.api.Assertions.assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                        () -> service.getComments("BV195KY6YEeY", 3, "", "", 1));
        assertTrue(ex.getMessage().contains("评论区已关闭"));
    }
    @Test
    void runCommentActionPostsReplyActionFormWithCsrf() throws Exception {
        org.mockito.ArgumentCaptor<HttpEntity<org.springframework.util.MultiValueMap<String, String>>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/v2/reply/action"), eq(HttpMethod.POST),
                captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"code\":0}")));

        Map<String, Object> result = service.runCommentAction("BV195KY6YEeY", "314458109537", 1);

        assertEquals(true, result.get("liked"));
        org.springframework.util.MultiValueMap<String, String> form = captor.getValue().getBody();
        assertEquals("1", form.getFirst("type"));
        assertEquals("116958703918865", form.getFirst("oid"));
        assertEquals("314458109537", form.getFirst("rpid"));
        assertEquals("1", form.getFirst("action"));
        org.junit.jupiter.api.Assertions.assertNotNull(captor.getValue().getHeaders().getFirst("Cookie"));
    }

    @Test
    void runCommentActionRejectsInvalidRpid() {
        cn.har01d.alist_tvbox.exception.BadRequestException ex =
                org.junit.jupiter.api.Assertions.assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                        () -> service.runCommentAction("BV195KY6YEeY", "abc", 1));
        assertTrue(ex.getMessage().contains("无效的评论 ID"));
    }

    @Test
    void runCommentReplyPostsAddFormAndReturnsNewComment() throws Exception {
        String selfMid = cn.har01d.alist_tvbox.util.BiliCookieRefreshUtils.getCookieValue(BiliBiliUtils.getCookie(), "DedeUserID");
        String body = """
                {"code":0,"data":{"reply":{
                  "rpid_str":"9999","member":{"mid":"%s","uname":"我","avatar":"https://i0.hdslb.com/face/me.jpg",
                    "level_info":{"current_level":6}},
                  "content":{"message":"回复内容"},"like":0,"rcount":0,"ctime":1790837000,"action":0,
                  "reply_control":{"time_desc":"刚刚"},"replies":[]}}}
                """.formatted(selfMid);
        org.mockito.ArgumentCaptor<HttpEntity<org.springframework.util.MultiValueMap<String, String>>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/v2/reply/add"), eq(HttpMethod.POST),
                captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(body)));

        Map<String, Object> result = service.runCommentReply("BV195KY6YEeY", "1002", "1003", "  回复内容  ");

        org.springframework.util.MultiValueMap<String, String> form = captor.getValue().getBody();
        assertEquals("1", form.getFirst("type"));
        assertEquals("116958703918865", form.getFirst("oid"));
        assertEquals("1002", form.getFirst("root"));
        assertEquals("1003", form.getFirst("parent"));
        assertEquals("回复内容", form.getFirst("message"));
        assertEquals("1", form.getFirst("plat"));
        Map<String, Object> comment = (Map<String, Object>) result.get("comment");
        assertEquals("9999", comment.get("rpid"));
        assertEquals("回复内容", comment.get("message"));
        // 自己发的回复:标「我」(is_self)而非「作者」(is_up)
        assertEquals(true, comment.get("is_self"));
        assertEquals(false, comment.get("is_up"));
    }

    @Test
    void runCommentReplyValidatesRootAndMessage() {
        cn.har01d.alist_tvbox.exception.BadRequestException badRoot =
                org.junit.jupiter.api.Assertions.assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                        () -> service.runCommentReply("BV195KY6YEeY", "abc", "abc", "hi"));
        assertTrue(badRoot.getMessage().contains("无效的评论 ID"));
        cn.har01d.alist_tvbox.exception.BadRequestException emptyMessage =
                org.junit.jupiter.api.Assertions.assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                        () -> service.runCommentReply("BV195KY6YEeY", "1002", "1002", "   "));
        assertTrue(emptyMessage.getMessage().contains("1-1000 字"));
    }

    @Test
    void runCommentReplyWithoutRootPostsTopLevelComment() throws Exception {
        String body = """
                {"code":0,"data":{"reply":{
                  "rpid_str":"7777","member":{"mid":"2340134","uname":"我",
                    "avatar":"https://i0.hdslb.com/face/me.jpg","level_info":{"current_level":6}},
                  "content":{"message":"直接评论视频"},"like":0,"rcount":0,"ctime":1790838000,"action":0,
                  "reply_control":{"time_desc":"刚刚"},"replies":[]}}}
                """;
        org.mockito.ArgumentCaptor<HttpEntity<org.springframework.util.MultiValueMap<String, String>>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq("https://api.bilibili.com/x/v2/reply/add"), eq(HttpMethod.POST),
                captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(body)));

        Map<String, Object> result = service.runCommentReply("BV195KY6YEeY", "", null, "直接评论视频");

        org.springframework.util.MultiValueMap<String, String> form = captor.getValue().getBody();
        assertEquals("1", form.getFirst("type"));
        assertEquals("116958703918865", form.getFirst("oid"));
        assertEquals("直接评论视频", form.getFirst("message"));
        // 顶层评论:不带 root/parent
        assertNull(form.getFirst("root"));
        assertNull(form.getFirst("parent"));
        Map<String, Object> comment = (Map<String, Object>) result.get("comment");
        assertEquals("7777", comment.get("rpid"));
    }

}
