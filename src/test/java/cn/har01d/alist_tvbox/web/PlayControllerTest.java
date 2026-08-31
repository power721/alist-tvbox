package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.PianDanService;
import cn.har01d.alist_tvbox.service.ProxyService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlayControllerTest {
    @Mock
    private TvBoxService tvBoxService;
    @Mock
    private BiliBiliService biliBiliService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private ProxyService proxyService;
    @Mock
    private MediaSubscriptionService mediaSubscriptionService;
    @Mock
    private MediaSubscriptionCheckService checkService;
    @Mock
    private PianDanService pianDanService;
    @Mock
    private cn.har01d.alist_tvbox.service.AccountAccessGuard accountAccessGuard;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 片单订阅编排已下沉为 service:包真实例透传三个既有 mock,既有打桩/断言原样生效
        cn.har01d.alist_tvbox.service.PianDanSubscriptionService pianDanSubscriptionService =
                new cn.har01d.alist_tvbox.service.PianDanSubscriptionService(
                        mediaSubscriptionService, checkService, pianDanService);
        PlayController controller = new PlayController(tvBoxService, biliBiliService, subscriptionService, proxyService,
                mediaSubscriptionService, pianDanSubscriptionService, accountAccessGuard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void playShouldRejectRequestsWithoutPlayableIdentifier() throws Exception {
        mockMvc.perform(get("/play/test-token"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(tvBoxService, biliBiliService, proxyService, mediaSubscriptionService);
    }

    @Test
    void playShouldResolveMediaSubscriptionEpisodeId() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);
        when(mediaSubscriptionService.playEpisode(7, 5, 2, null, null))
                .thenReturn(Map.of("url", "https://example.com/e2.mp4"));

        mockMvc.perform(get("/play/test-token").param("id", "msubep-5-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/e2.mp4"));
    }

    @Test
    void playShouldRejectMalformedMediaSubscriptionEpisodeId() throws Exception {
        mockMvc.perform(get("/play/test-token").param("id", "msubep-5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void playShouldSubscribePianDanDoubanEntry() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);
        when(mediaSubscriptionService.isSubscribedTitle(org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        when(mediaSubscriptionService.localDoubanId(eq("showa"), isNull())).thenReturn(123);
        MediaSubscriptionDto dto = new MediaSubscriptionDto();
        dto.setId(11);
        when(mediaSubscriptionService.create(eq(7), any())).thenReturn(dto);

        mockMvc.perform(get("/play/test-token").param("id", "msubadd-s:showa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("已加入追剧《showa》,稍后在我的追剧查看"));
        org.mockito.Mockito.verify(mediaSubscriptionService)
                .create(org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.argThat(request ->
                        "showa".equals(request.getName()) && "douban".equals(request.getMetaProvider())
                                && Integer.valueOf(123).equals(request.getDoubanId())));
        org.mockito.Mockito.verify(mediaSubscriptionService, org.mockito.Mockito.never()).metaSearch(any(), any());
        org.mockito.Mockito.verify(checkService).checkAsync(7, 11);
        verifyNoInteractions(tvBoxService, biliBiliService, proxyService, pianDanService);
    }

    @Test
    void playShouldBindDoubanMetaViaSuggestWhenLocalAmbiguous() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);
        when(mediaSubscriptionService.isSubscribedTitle(org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        when(mediaSubscriptionService.localDoubanId(eq("showa"), isNull())).thenReturn(null);
        cn.har01d.alist_tvbox.dto.MetadataSearchItem item = new cn.har01d.alist_tvbox.dto.MetadataSearchItem();
        item.setProvider("douban");
        item.setId("456");
        item.setName("showa");
        when(mediaSubscriptionService.metaSearch("douban", "showa")).thenReturn(Map.of("items", java.util.List.of(item)));
        MediaSubscriptionDto dto = new MediaSubscriptionDto();
        dto.setId(12);
        when(mediaSubscriptionService.create(eq(7), any())).thenReturn(dto);

        mockMvc.perform(get("/play/test-token").param("id", "msubadd-s:showa"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(mediaSubscriptionService)
                .create(eq(7), org.mockito.ArgumentMatchers.argThat(request ->
                        Integer.valueOf(456).equals(request.getDoubanId()) && "456".equals(request.getMetaId())));
    }

    @Test
    void playShouldSkipDoubanMetaWhenSuggestAlsoAmbiguous() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);
        when(mediaSubscriptionService.isSubscribedTitle(org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        when(mediaSubscriptionService.localDoubanId(eq("showa"), isNull())).thenReturn(null);
        cn.har01d.alist_tvbox.dto.MetadataSearchItem first = new cn.har01d.alist_tvbox.dto.MetadataSearchItem();
        first.setProvider("douban");
        first.setId("456");
        first.setName("showa");
        cn.har01d.alist_tvbox.dto.MetadataSearchItem remake = new cn.har01d.alist_tvbox.dto.MetadataSearchItem();
        remake.setProvider("douban");
        remake.setId("789");
        remake.setName("showa");
        when(mediaSubscriptionService.metaSearch("douban", "showa"))
                .thenReturn(Map.of("items", java.util.List.of(first, remake)));
        MediaSubscriptionDto dto = new MediaSubscriptionDto();
        dto.setId(13);
        when(mediaSubscriptionService.create(eq(7), any())).thenReturn(dto);

        mockMvc.perform(get("/play/test-token").param("id", "msubadd-s:showa"))
                .andExpect(status().isOk());
        // 同名翻拍 suggest 也无法消歧:不赌首条,回落纯标题订阅
        org.mockito.Mockito.verify(mediaSubscriptionService)
                .create(eq(7), org.mockito.ArgumentMatchers.argThat(request ->
                        request.getDoubanId() == null && request.getMetaProvider() == null));
    }

    @Test
    void playShouldSubscribePianDanTmdbEntryWithMetadata() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);
        when(mediaSubscriptionService.isSubscribedTitle(7, "测试剧")).thenReturn(true);

        // play id 内嵌剧名:订阅时零网络(不再调 tmdbDetail 取标题)
        mockMvc.perform(get("/play/test-token").param("id", "msubadd-tmdb:tv:42|测试剧"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("《测试剧》已在追剧中"));
        org.mockito.Mockito.verify(mediaSubscriptionService)
                .create(org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.argThat(request ->
                        "测试剧".equals(request.getName()) && "tmdb".equals(request.getMetaProvider())
                                && "42".equals(request.getMetaId())));
        org.mockito.Mockito.verifyNoInteractions(checkService);
        verifyNoInteractions(tvBoxService, biliBiliService, proxyService, pianDanService);
    }

    @Test
    void playShouldRejectUnknownPianDanEntry() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);
        mockMvc.perform(get("/play/test-token").param("id", "msubadd-xxx:1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void playShouldReturnPianDanInfoWithoutSideEffects() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);

        // 纯占位条目:静态 msg,零网络零 DB(不拉 TMDB、不查订阅状态)
        mockMvc.perform(get("/play/test-token").param("id", "msubinfo-tmdb:tv:1396|绝命毒师"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("媒体信息见详情页"));
        verifyNoInteractions(tvBoxService, biliBiliService, proxyService, pianDanService, checkService);
        org.mockito.Mockito.verify(mediaSubscriptionService, org.mockito.Mockito.only()).resolveUid("test-token");
    }

    @Test
    void playShouldSubscribePianDanTmdbSeasonEntry() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);
        when(mediaSubscriptionService.isSubscribedTitle(7, "测试剧 第5季")).thenReturn(false);
        MediaSubscriptionDto dto = new MediaSubscriptionDto();
        dto.setId(21);
        when(mediaSubscriptionService.create(eq(7), any())).thenReturn(dto);

        mockMvc.perform(get("/play/test-token").param("id", "msubadd-tmdb:tv:42|测试剧|5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("第5季已加入追剧《测试剧》,稍后在我的追剧查看"));
        org.mockito.Mockito.verify(mediaSubscriptionService)
                .create(eq(7), org.mockito.ArgumentMatchers.argThat(request ->
                        "测试剧".equals(request.getName()) && Integer.valueOf(5).equals(request.getSeason())
                                && "tmdb".equals(request.getMetaProvider()) && "42".equals(request.getMetaId())));
        org.mockito.Mockito.verify(checkService).checkAsync(7, 21);
    }

    @Test
    void playShouldUnsubscribePianDanEntryByName() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);
        when(mediaSubscriptionService.subscriptionIdsByTitle(7, "showa", null)).thenReturn(java.util.List.of(3, 9));

        mockMvc.perform(get("/play/test-token").param("id", "msubdel-s:showa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("已取消追剧《showa》(2 部)"));
        org.mockito.Mockito.verify(mediaSubscriptionService).delete(7, 3);
        org.mockito.Mockito.verify(mediaSubscriptionService).delete(7, 9);
        verifyNoInteractions(checkService);
    }

    @Test
    void playShouldReportNotSubscribedOnUnsubscribe() throws Exception {
        when(mediaSubscriptionService.resolveUid("test-token")).thenReturn(7);
        when(mediaSubscriptionService.subscriptionIdsByTitle(7, "showa", null)).thenReturn(java.util.List.of());

        mockMvc.perform(get("/play/test-token").param("id", "msubdel-s:showa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("《showa》未在追剧中"));
    }
}
