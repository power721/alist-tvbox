package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.LiveFollowDto;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.live.web.LiveController;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4 适配层:五动作(home/category/detail/search/player)按 FongMi type 4 参数契约
 * 落到既有端点,逐参数对齐 spring.jar 各 spider 的回源调用。
 */
@ExtendWith(MockitoExtension.class)
class T4ControllerTest {
    private static final String TOKEN = "tok";

    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private TvBoxController tvBoxController;
    @Mock
    private PianDanController pianDanController;
    @Mock
    private MediaLibraryController mediaLibraryController;
    @Mock
    private BiliBiliController biliBiliController;
    @Mock
    private EmbyController embyController;
    @Mock
    private JellyfinController jellyfinController;
    @Mock
    private FeiniuController feiniuController;
    @Mock
    private LiveController liveController;
    @Mock
    private TelegramController telegramController;
    @Mock
    private RemoteSearchController remoteSearchController;
    @Mock
    private PlayController playController;

    private T4Controller controller;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        controller = new T4Controller(subscriptionService, tvBoxController, pianDanController,
                mediaLibraryController, biliBiliController, embyController, jellyfinController,
                feiniuController, liveController, telegramController, remoteSearchController,
                playController, new ObjectMapper());
        request = new MockHttpServletRequest("GET", "/t4/tok/v/csp_AList");
        response = new MockHttpServletResponse();
    }

    @Test
    void categoryDecodesBase64UrlExtIntoFilterJson() throws Exception {
        String ext = Base64.getUrlEncoder().encodeToString("{\"year\":\"2024\"}".getBytes());
        when(tvBoxController.api(eq(TOKEN), eq("1"), eq("{\"year\":\"2024\"}"), isNull(), isNull(),
                isNull(), isNull(), eq(2), eq(100), eq(1), eq(0), same(request)))
                .thenReturn(new MovieList());

        controller.vod(TOKEN, "csp_AList", "1", null, null, null, null, ext, 2, request, response);

        verify(tvBoxController).api(eq(TOKEN), eq("1"), eq("{\"year\":\"2024\"}"), isNull(), isNull(),
                isNull(), isNull(), eq(2), eq(100), eq(1), eq(0), same(request));
    }

    @Test
    void detailForwardsIdParamToMediaEndpoint() throws Exception {
        when(mediaLibraryController.browse(eq(TOKEN), eq("db:123"), isNull(), isNull(), isNull(),
                isNull(), eq(1), eq(Map.of()), same(request))).thenReturn(new MovieList());

        controller.vod(TOKEN, "csp_Media", null, "db:123", null, null, null, null, null, request, response);

        verify(mediaLibraryController).browse(eq(TOKEN), eq("db:123"), isNull(), isNull(), isNull(),
                isNull(), eq(1), eq(Map.of()), same(request));
    }

    @Test
    void playerForwardsBilibiliIdAsBvidWithT4Client() throws Exception {
        // B站播放以 t4 身份下发(X-CLIENT 头):BiliBiliService 据此改写为服务端代理地址
        org.mockito.ArgumentMatcher<jakarta.servlet.http.HttpServletRequest> t4Client =
                r -> "t4".equals(r.getHeader("X-CLIENT"));
        when(playController.play(eq(TOKEN), isNull(), isNull(), isNull(), eq("BV1xx411c7mD"),
                isNull(), eq(false), org.mockito.ArgumentMatchers.argThat(t4Client))).thenReturn(Map.of());

        controller.vod(TOKEN, "csp_BiliBili", null, null, null, null, "BV1xx411c7mD", null, null, request, response);

        verify(playController).play(eq(TOKEN), isNull(), isNull(), isNull(), eq("BV1xx411c7mD"),
                isNull(), eq(false), org.mockito.ArgumentMatchers.argThat(t4Client));
    }

    @Test
    void playerLiveFollowActionHitsFollowEndpoint() throws Exception {
        when(liveController.follow(eq(TOKEN), org.mockito.ArgumentMatchers.any(LiveFollowDto.class)))
                .thenReturn(Map.of("success", true));

        Object result = controller.vod(TOKEN, "csp_Live", null, null, null, null, "follow$huya$12345",
                null, null, request, response);

        ArgumentCaptor<LiveFollowDto> captor = ArgumentCaptor.forClass(LiveFollowDto.class);
        verify(liveController).follow(eq(TOKEN), captor.capture());
        assertEquals("huya", captor.getValue().getPlatform());
        assertEquals("12345", captor.getValue().getRoomId());
        assertInstanceOf(Map.class, result);
    }

    @Test
    void liveDetailRequestsWebClientFormForServerProxy() throws Exception {
        // 直播详情按 web 口径取地址:Twitch/SOOP 等平台仅 web 形态走服务端代理,
        // client=null 会拿到裸直链(T3 设备端自带播,T4 壳子播不了)
        when(liveController.browse(eq(TOKEN), eq("huya$52647"), isNull(), isNull(), isNull(),
                eq("web"), isNull(), eq(1))).thenReturn(new MovieList());

        controller.vod(TOKEN, "csp_Live", null, "huya$52647", null, null, null, null, null, request, response);

        verify(liveController).browse(eq(TOKEN), eq("huya$52647"), isNull(), isNull(), isNull(),
                eq("web"), isNull(), eq(1));
    }

    @Test
    void playerLiveDirectUrlIsPassedThrough() throws Exception {
        // 直播详情的 vod_play_url 已是 /p 代理直链,播放须直接透传(spring.jar Live.play 同构),
        // 不得再进 /live-play(其按直播间 id 解析,对 URL 返回无 url 的 {parse:0})
        String direct = "http://192.168.50.60:4567/p/Harold/0@245568";

        Object result = controller.vod(TOKEN, "csp_Live", null, null, null, null, direct,
                null, null, request, response);

        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals(direct, map.get("url"));
        assertEquals(0, map.get("parse"));
        verify(liveController, org.mockito.Mockito.never()).play(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void titleCacheFeedsTelegramDetail() throws Exception {
        MovieList searchResult = new MovieList();
        MovieDetail item = new MovieDetail();
        item.setVod_id("36675942");
        item.setVod_name("醒来");
        searchResult.setList(List.of(item));
        when(telegramController.db(eq(TOKEN), isNull(), isNull(), isNull(), eq("醒来吧"),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(30))).thenReturn(searchResult);
        when(telegramController.db(eq(TOKEN), eq("36675942"), isNull(), isNull(), isNull(),
                eq("醒来"), isNull(), isNull(), isNull(), isNull(), eq(1), eq(30))).thenReturn(new MovieList());

        controller.vod(TOKEN, "csp_TgDouBan", null, null, "醒来吧", null, null, null, null, request, response);
        controller.vod(TOKEN, "csp_TgDouBan", null, "36675942", null, null, null, null, null, request, response);

        verify(telegramController).db(eq(TOKEN), eq("36675942"), isNull(), isNull(), isNull(),
                eq("醒来"), isNull(), isNull(), isNull(), isNull(), eq(1), eq(30));
    }

    @Test
    void homeMergesCategoriesWithRecommendList() throws Exception {
        CategoryList categories = new CategoryList();
        when(tvBoxController.api(eq(TOKEN), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(1), eq(100), eq(1), eq(0), same(request))).thenReturn(categories);
        MovieList recommend = new MovieList();
        MovieDetail item = new MovieDetail();
        item.setVod_id("recommend-1");
        recommend.setList(List.of(item));
        when(tvBoxController.api(eq(TOKEN), isNull(), isNull(), eq("recommend"), isNull(), isNull(), isNull(),
                eq(1), eq(100), eq(1), eq(0), same(request))).thenReturn(recommend);

        Object result = controller.vod(TOKEN, "csp_AList", null, null, null, null, null, null, null, request, response);

        assertInstanceOf(Map.class, result);
        Map<?, ?> merged = (Map<?, ?>) result;
        List<?> list = (List<?>) merged.get("list");
        assertEquals(1, list.size());
        assertEquals("recommend-1", ((Map<?, ?>) list.get(0)).get("vod_id"));
    }

    @Test
    void bareSiteNameWithoutCspPrefixRoutes() throws Exception {
        // 配置生成的 api URL 路径段不带 csp_ 前缀(applyT4 口径);适配层归一化后分发
        when(tvBoxController.api(eq(TOKEN), isNull(), isNull(), eq("movie/1"), isNull(), isNull(), isNull(),
                eq(1), eq(100), eq(1), eq(0), same(request))).thenReturn(new MovieList());

        controller.vod(TOKEN, "AList", null, "movie/1", null, null, null, null, null, request, response);

        verify(tvBoxController).api(eq(TOKEN), isNull(), isNull(), eq("movie/1"), isNull(), isNull(), isNull(),
                eq(1), eq(100), eq(1), eq(0), same(request));
    }

    @Test
    void pianDanNavDetailDelegatesToMediaPianDanDetail() throws Exception {
        // 片单导航服务端代搜:db:/tmdb:/s:/douban: 条目委托 /media 片单详情(豆瓣元数据+搜电报网格+动作),
        // 通用壳子不执行 indexs=1(FongMi 独有),T3 的客户端全局搜片由服务端代劳
        when(mediaLibraryController.browse(eq(TOKEN), eq("db:36675942"), isNull(), isNull(), isNull(),
                isNull(), eq(1), eq(Map.of()), org.mockito.ArgumentMatchers.any())).thenReturn(new MovieList());

        controller.vod(TOKEN, "csp_PianDan", null, "db:36675942", null, null, null, null, null, request, response);

        verify(mediaLibraryController).browse(eq(TOKEN), eq("db:36675942"), isNull(), isNull(), isNull(),
                isNull(), eq(1), eq(Map.of()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pianDanNonNavIdStaysEmpty() throws Exception {
        Object result = controller.vod(TOKEN, "csp_PianDan", null, "other-form", null, null, null, null, null, request, response);

        assertEquals(Map.of(), result);
        verify(mediaLibraryController, org.mockito.Mockito.never()).browse(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void formPostAndJsonPostDispatch() throws Exception {
        // 杂牌壳子双 POST 形态:表单参数由 Spring 绑定;JSON body 在参数缺位时兜底解析
        org.springframework.mock.web.MockHttpServletRequest formRequest =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/t4/tok/v/Media");
        formRequest.setContentType("application/x-www-form-urlencoded");
        formRequest.setParameter("ids", "db:1");
        when(mediaLibraryController.browse(eq(TOKEN), eq("db:1"), isNull(), isNull(), isNull(),
                isNull(), eq(1), eq(Map.of()), org.mockito.ArgumentMatchers.any())).thenReturn(new MovieList());

        controller.vod(TOKEN, "Media", null, "db:1", null, null, null, null, null, formRequest, response);

        org.springframework.mock.web.MockHttpServletRequest jsonRequest =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/t4/tok/v/AList");
        jsonRequest.setContentType("application/json");
        jsonRequest.setContent("{\"t\":\"1\",\"pg\":\"2\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(tvBoxController.api(eq(TOKEN), eq("1"), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(100), eq(1), eq(0), org.mockito.ArgumentMatchers.any())).thenReturn(new MovieList());

        controller.vod(TOKEN, "AList", null, null, null, null, null, null, null, jsonRequest, response);

        verify(mediaLibraryController).browse(eq(TOKEN), eq("db:1"), isNull(), isNull(), isNull(),
                isNull(), eq(1), eq(Map.of()), org.mockito.ArgumentMatchers.any());
        verify(tvBoxController).api(eq(TOKEN), eq("1"), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(2), eq(100), eq(1), eq(0), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unknownSiteRejected() {
        assertThrows(NotFoundException.class,
                () -> controller.vod(TOKEN, "csp_Unknown", null, null, null, null, null, null, null, request, response));
    }
}
