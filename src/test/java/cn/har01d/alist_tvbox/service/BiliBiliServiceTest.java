package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliInfo;
import cn.har01d.alist_tvbox.dto.bili.BiliBiliInfoResponse;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BiliBiliServiceTest {
    private final RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
    private final SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
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

        service = new BiliBiliService(settingRepository, mock(NavigationService.class), appProperties,
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
        // 实测 episode 无顶层 duration,时长在 arc.duration(秒)
        String json = "{\"aid\":1,\"bvid\":\"BV1\",\"title\":\"t\",\"ugc_season\":{\"id\":748,\"title\":\"合集名\",\"mid\":9,"
                + "\"sections\":[{\"id\":1,\"title\":\"正片\",\"episodes\":[{\"aid\":10,\"bvid\":\"BV10\",\"cid\":100,\"title\":\"第一集\",\"arc\":{\"duration\":61}}]},"
                + "{\"id\":2,\"title\":\"花絮\",\"episodes\":[]}]}}";
        BiliBiliInfo info = new ObjectMapper().readValue(json, BiliBiliInfo.class);

        assertEquals("合集名", info.getUgcSeason().getTitle());
        assertEquals(2, info.getUgcSeason().getSections().size());
        BiliBiliInfo.UgcSeason.Episode episode = info.getUgcSeason().getSections().get(0).getEpisodes().get(0);
        assertEquals("第一集", episode.getTitle());
        assertEquals(100L, episode.getCid());
        assertEquals(10L, episode.getAid());
        assertEquals(61L, episode.getDuration());
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
}
