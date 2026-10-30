package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Plugin;

import cn.har01d.alist_tvbox.config.AppProperties;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    @Mock
    private cn.har01d.alist_tvbox.service.PluginWorkerService pluginWorkerService;
    @Mock
    private cn.har01d.alist_tvbox.entity.PluginRepository pluginRepository;

    private T4Controller controller;
    private AppProperties appProperties;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        controller = new T4Controller(subscriptionService, appProperties, tvBoxController, pianDanController,
                mediaLibraryController, biliBiliController, embyController, jellyfinController,
                feiniuController, liveController, telegramController, remoteSearchController,
                playController, pluginWorkerService, pluginRepository, new ObjectMapper());
        // vod/cenc 入口的 T4 专用 token 换算:默认回显(未命中),个别用例按需覆写
        lenient().when(subscriptionService.resolveT4Token(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
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
    void pluginSiteDispatchesToWorkerRuntime() throws Exception {
        // 插件源 T4:非内置站点按插件站点 key 反查 Plugin,五动作转投 Python worker 运行时
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setUrl("https://up.example/spider.txt");
        when(pluginRepository.findByExternalId("plugin-9")).thenReturn(java.util.Optional.empty());
        when(pluginRepository.findById(9)).thenReturn(java.util.Optional.of(plugin));
        when(pluginWorkerService.isAvailable()).thenReturn(true);
        when(pluginWorkerService.call(eq("plugin-9"), org.mockito.ArgumentMatchers.anyString(),
                eq("home"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(new ObjectMapper().readTree("{\"class\":[]}"));

        Object result = controller.vod(TOKEN, "plugin-9", null, null, null, null, null, null, null, request, response);

        assertTrue(result.toString().contains("class"));
    }

    @Test
    void pluginSiteUnavailableRuntimeRejected() {
        // 运行时不可用(无 python3):插件站点 404(配置层也不会下发该站点)
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setUrl("https://up.example/spider.txt");
        when(pluginRepository.findByExternalId("plugin-9")).thenReturn(java.util.Optional.empty());
        when(pluginRepository.findById(9)).thenReturn(java.util.Optional.of(plugin));
        when(pluginWorkerService.isAvailable()).thenReturn(false);

        assertThrows(NotFoundException.class,
                () -> controller.vod(TOKEN, "plugin-9", null, null, null, null, null, null, null, request, response));
    }

    @Test
    void pluginSiteHiddenFromT4Rejected() {
        // 订阅源管理「T4」开关关闭:配置不出现,直连五动作同样 404
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setUrl("https://up.example/spider.txt");
        plugin.setT4Visible(false);
        when(pluginRepository.findByExternalId("plugin-9")).thenReturn(java.util.Optional.empty());
        when(pluginRepository.findById(9)).thenReturn(java.util.Optional.of(plugin));

        assertThrows(NotFoundException.class,
                () -> controller.vod(TOKEN, "plugin-9", null, null, null, null, null, null, null, request, response));
    }

    @Test
    void unknownSiteRejected() {
        assertThrows(NotFoundException.class,
                () -> controller.vod(TOKEN, "csp_Unknown", null, null, null, null, null, null, null, request, response));
    }

    @Test
    void standaloneConfigWithDedicatedToken() {
        // 单段 /t4/{t4token}:T4 专用 token 命中 → 独立配置(与订阅无关),先换算回 vod token 过 checkToken
        when(subscriptionService.isT4Token("t4tok")).thenReturn(true);
        when(subscriptionService.resolveT4Token("t4tok")).thenReturn("");
        Map<String, Object> config = Map.of("sites", List.of());
        when(pluginWorkerService.isAvailable()).thenReturn(false);
        when(subscriptionService.standaloneT4("t4tok", false)).thenReturn(config);

        assertEquals(config, controller.config("t4tok"));

        verify(subscriptionService).checkToken("");
        verify(subscriptionService, never()).subscriptionT4(anyString(), anyString(), anyBoolean());
    }

    @Test
    void bareSingleSegmentFallsBackToLegacySubscriptionForm() {
        // 单段路径未命中 T4 token:回落旧裸订阅形态(兼容保留)
        when(subscriptionService.isT4Token("0")).thenReturn(false);
        when(pluginWorkerService.isAvailable()).thenReturn(false);
        when(subscriptionService.subscriptionT4("", "0", false)).thenReturn(Map.of("sites", List.of()));

        controller.config("0");

        verify(subscriptionService).subscriptionT4("", "0", false);
        verify(subscriptionService, never()).standaloneT4(anyString(), anyBoolean());
    }

    @Test
    void vodResolvesDedicatedTokenToBackingVodToken() throws Exception {
        // 五动作入口:T4 专用 token 换算回背后 vod token 再进业务(业务面只见 vod token)
        when(subscriptionService.resolveT4Token("t4tok")).thenReturn(TOKEN);
        when(mediaLibraryController.browse(eq(TOKEN), eq("db:1"), isNull(), isNull(), isNull(),
                isNull(), eq(1), eq(Map.of()), same(request))).thenReturn(new MovieList());

        controller.vod("t4tok", "csp_Media", null, "db:1", null, null, null, null, null, request, response);

        verify(subscriptionService).checkToken(TOKEN);
        verify(mediaLibraryController).browse(eq(TOKEN), eq("db:1"), isNull(), isNull(), isNull(),
                isNull(), eq(1), eq(Map.of()), same(request));
    }

    @Test
    void cencBridgeResolvesDedicatedTokenAndKeepsPathSegment() throws Exception {
        // CENC 桥:token 段换算鉴权,URI 定位用原始路径段(T4 专用 token 形态与 vod token 形态通吃)
        when(subscriptionService.resolveT4Token("t4tok")).thenReturn(TOKEN);
        MockHttpServletRequest bridgeRequest =
                new MockHttpServletRequest("GET", "/t4cenc/t4tok/plugin-9/cenc/abc.mp4");

        controller.cenc("t4tok", "plugin-9", bridgeRequest, response);

        verify(subscriptionService).checkToken(TOKEN);
        verify(pluginWorkerService).forward(eq("plugin-9"), eq("/cenc/abc.mp4"), same(bridgeRequest), same(response));
    }

    @Test
    void disabledSwitchRejectsAllFamilies() throws Exception {
        // t4_enabled 关闭:配置(独立/双段/裸)、五动作、CENC 桥全部 404
        appProperties.setT4Enabled(false);

        assertThrows(NotFoundException.class, () -> controller.config("t4tok"));
        assertThrows(NotFoundException.class, () -> controller.config(TOKEN, "0"));
        assertThrows(NotFoundException.class,
                () -> controller.vod(TOKEN, "csp_AList", null, null, null, null, null, null, null, request, response));
        assertThrows(NotFoundException.class,
                () -> controller.cenc(TOKEN, "plugin-9",
                        new MockHttpServletRequest("GET", "/t4cenc/tok/plugin-9/cenc/abc.mp4"), response));
    }

    @Test
    void standaloneOnlyRejectsLegacyForms() throws Exception {
        // 仅独立地址(t4_standalone_only):旧裸订阅/双段配置/五动作/CENC 桥全部 404,
        // vod token / 订阅号持有者只能走 /sub
        appProperties.setT4StandaloneOnly(true);
        when(subscriptionService.isT4Token(anyString())).thenReturn(false);

        assertThrows(NotFoundException.class, () -> controller.config("0"));
        assertThrows(NotFoundException.class, () -> controller.config(TOKEN, "0"));
        assertThrows(NotFoundException.class,
                () -> controller.vod(TOKEN, "csp_AList", null, null, null, null, null, null, null, request, response));
        assertThrows(NotFoundException.class,
                () -> controller.cenc(TOKEN, "plugin-9",
                        new MockHttpServletRequest("GET", "/t4cenc/tok/plugin-9/cenc/abc.mp4"), response));

        verify(subscriptionService, never()).subscriptionT4(anyString(), anyString(), anyBoolean());
        verify(subscriptionService, never()).standaloneT4(anyString(), anyBoolean());
    }

    @Test
    void standaloneOnlyKeepsDedicatedTokenForms() throws Exception {
        // 仅独立地址模式下 /t4/{t4token} 形态照常:独立配置/五动作/CENC 桥不受影响
        appProperties.setT4StandaloneOnly(true);
        when(subscriptionService.isT4Token("t4tok")).thenReturn(true);
        when(subscriptionService.resolveT4Token("t4tok")).thenReturn(TOKEN);
        when(pluginWorkerService.isAvailable()).thenReturn(false);
        Map<String, Object> config = Map.of("sites", List.of());
        when(subscriptionService.standaloneT4("t4tok", false)).thenReturn(config);

        assertEquals(config, controller.config("t4tok"));

        when(mediaLibraryController.browse(eq(TOKEN), eq("db:1"), isNull(), isNull(), isNull(),
                isNull(), eq(1), eq(Map.of()), same(request))).thenReturn(new MovieList());
        controller.vod("t4tok", "csp_Media", null, "db:1", null, null, null, null, null, request, response);

        MockHttpServletRequest bridgeRequest =
                new MockHttpServletRequest("GET", "/t4cenc/t4tok/plugin-9/cenc/abc.mp4");
        controller.cenc("t4tok", "plugin-9", bridgeRequest, response);

        verify(subscriptionService, never()).subscriptionT4(anyString(), anyString(), anyBoolean());
    }

    @Test
    void directProxyModeSendsT4dClientForBilibili() throws Exception {
        // 直连优先(t4_proxy_mode=direct):B站播放以 t4d 身份下发 → 原始 durl + 脱敏 header
        appProperties.setT4ProxyMode("direct");
        org.mockito.ArgumentMatcher<jakarta.servlet.http.HttpServletRequest> t4dClient =
                r -> "t4d".equals(r.getHeader("X-CLIENT"));
        when(playController.play(eq(TOKEN), isNull(), isNull(), isNull(), eq("BV1xx411c7mD"),
                isNull(), eq(false), org.mockito.ArgumentMatchers.argThat(t4dClient))).thenReturn(Map.of());

        controller.vod(TOKEN, "csp_BiliBili", null, null, null, null, "BV1xx411c7mD", null, null, request, response);

        verify(playController).play(eq(TOKEN), isNull(), isNull(), isNull(), eq("BV1xx411c7mD"),
                isNull(), eq(false), org.mockito.ArgumentMatchers.argThat(t4dClient));
    }

    @Test
    void directProxyModeLiveDetailUsesDirectClientForm() throws Exception {
        // 直连优先:直播详情 client 传 null(Twitch/SOOP 裸直链、酷狗/映客/LOOK/YY dual 双线路)
        appProperties.setT4ProxyMode("direct");
        when(liveController.browse(eq(TOKEN), eq("huya$1"), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(1))).thenReturn(new MovieList());

        controller.vod(TOKEN, "csp_Live", null, "huya$1", null, null, null, null, null, request, response);

        verify(liveController).browse(eq(TOKEN), eq("huya$1"), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(1));
    }
}
