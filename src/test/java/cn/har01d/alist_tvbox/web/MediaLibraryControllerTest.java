package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.PianDanService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaLibraryControllerTest {
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private MediaSubscriptionService mediaSubscriptionService;
    @Mock
    private PianDanService pianDanService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MediaLibraryController(subscriptionService, mediaSubscriptionService, pianDanService))
                .setControllerAdvice(new RestErrorHandler())
                .build();
        when(mediaSubscriptionService.resolveUid("token-a")).thenReturn(7);
    }

    private MovieList list(String vodId) {
        MovieList result = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(vodId);
        result.setList(List.of(detail));
        return result;
    }

    @Test
    void homeContentReturnsStatusCategories() throws Exception {
        when(pianDanService.subscriptionCategory()).thenReturn(new CategoryList());
        mockMvc.perform(get("/media/token-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.class[0].type_id").value("recent"))
                .andExpect(jsonPath("$.class[0].type_name").value("最近更新"))
                .andExpect(jsonPath("$.class[1].type_id").value("active"))
                .andExpect(jsonPath("$.class[2].type_id").value("ended"))
                .andExpect(jsonPath("$.class[3].type_id").value("all"))
                .andExpect(jsonPath("$.class[3].type_name").value("全部订阅"));
    }

    @Test
    void homeContentMergesPianDanCategories() throws Exception {
        CategoryList pianDan = new CategoryList();
        Category category = new Category();
        category.setType_id(PianDanService.TMDB_PREFIX + "tv_popular");
        category.setType_name("TMDB热门剧集");
        pianDan.getCategories().add(category);
        when(pianDanService.subscriptionCategory()).thenReturn(pianDan);

        mockMvc.perform(get("/media/token-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.class[4].type_id").value("tmdb:tv_popular"))
                .andExpect(jsonPath("$.class[4].type_name").value("TMDB热门剧集"));
    }

    @Test
    void pianDanCategoryContentMarksSubscribed() throws Exception {
        MovieList source = new MovieList();
        MovieDetail item = new MovieDetail();
        item.setVod_id(PianDanService.TMDB_PREFIX + "tv:42");
        item.setVod_name("测试剧");
        item.setVod_pic("https://image.tmdb.org/t/p/w500/x.jpg");
        source.getList().add(item);
        when(pianDanService.list(eq("tmdb:tv_popular"), eq("web"), eq(1), eq(24), anyMap())).thenReturn(source);
        when(mediaSubscriptionService.isSubscribedTitle(7, "测试剧")).thenReturn(true);
        when(mediaSubscriptionService.absoluteClientCover(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(get("/media/token-a").param("t", "tmdb:tv_popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("tmdb:tv:42"))
                .andExpect(jsonPath("$.list[0].vod_remarks").value("已追 "));
    }

    @Test
    void pianDanTmdbDetailCarriesSubscribePlayItem() throws Exception {
        MovieDetail meta = new MovieDetail();
        meta.setVod_id("tmdb:tv:42");
        meta.setVod_name("测试剧");
        when(pianDanService.tmdbDetail("tv", 42)).thenReturn(meta);
        when(mediaSubscriptionService.isSubscribedTitle(org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        when(mediaSubscriptionService.absoluteClientCover(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(get("/media/token-a").param("id", "tmdb:tv:42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_name").value("测试剧"))
                .andExpect(jsonPath("$.list[0].vod_play_from").value("片单"))
                .andExpect(jsonPath("$.list[0].vod_play_url")
                        .value("📄 媒体信息$msubinfo-" + encode("tmdb:tv:42|测试剧")
                                + "#➕ 加入追剧$msubadd-" + encode("tmdb:tv:42|测试剧")));
    }

    @Test
    void pianDanTmdbMultiSeasonDetailExpandsPerSeason() throws Exception {
        MovieDetail meta = new MovieDetail();
        meta.setVod_id("tmdb:tv:42");
        meta.setVod_name("测试剧");
        meta.setExt(java.util.List.of(1, 5));
        when(pianDanService.tmdbDetail("tv", 42)).thenReturn(meta);
        when(mediaSubscriptionService.isSubscribedTitle(7, "测试剧 第1季")).thenReturn(true);
        when(mediaSubscriptionService.isSubscribedTitle(7, "测试剧 第5季")).thenReturn(false);
        when(mediaSubscriptionService.absoluteClientCover(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(get("/media/token-a").param("id", "tmdb:tv:42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_play_url")
                        .value("📄 媒体信息$msubinfo-" + encode("tmdb:tv:42|测试剧")
                                + "#➖ 取消·第1季$msubdel-" + encode("tmdb:tv:42|测试剧|1")
                                + "#➕ 追剧·第5季$msubadd-" + encode("tmdb:tv:42|测试剧|5")))
                .andExpect(jsonPath("$.list[0].ext").doesNotExist());
    }

    @Test
    void pianDanDoubanDetailMarksSubscribed() throws Exception {
        when(mediaSubscriptionService.isSubscribedTitle(7, "showa")).thenReturn(true);
        when(mediaSubscriptionService.absoluteClientCover(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(get("/media/token-a").param("id", "s:showa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_name").value("showa"))
                .andExpect(jsonPath("$.list[0].vod_remarks").value("已追 "))
                .andExpect(jsonPath("$.list[0].vod_play_url")
                        .value("📄 媒体信息$msubinfo-" + encode("s:showa|showa")
                                + "#➖ 取消追剧$msubdel-" + encode("s:showa|showa")));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void pianDanDetailRejectsUnknownPrefix() throws Exception {
        mockMvc.perform(get("/media/token-a").param("id", "tmdb:tv"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void homeVideoContentReturnsAllSubscriptions() throws Exception {
        when(mediaSubscriptionService.contentList(7)).thenReturn(list("msub:1"));
        mockMvc.perform(get("/media/token-a").param("t", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("msub:1"));
    }

    @Test
    void categoryContentFiltersByStatus() throws Exception {
        when(mediaSubscriptionService.contentList(eq(7), eq("active"), isNull())).thenReturn(list("msub:2"));
        mockMvc.perform(get("/media/token-a").param("t", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("msub:2"));
    }

    @Test
    void searchContentMatchesKeyword() throws Exception {
        when(mediaSubscriptionService.contentList(eq(7), isNull(), eq("凡人"))).thenReturn(list("msub:3"));
        when(pianDanService.search("凡人", 1, 24)).thenReturn(list("tmdb:tv:42"));
        when(mediaSubscriptionService.isSubscribedTitle(eq(7), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        when(mediaSubscriptionService.absoluteClientCover(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(get("/media/token-a").param("wd", "凡人"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("msub:3"))
                .andExpect(jsonPath("$.list[1].vod_id").value("tmdb:tv:42"));
    }

    @Test
    void searchMarksSubscribedTmdbItemWithBadge() throws Exception {
        MovieList tmdb = new MovieList();
        MovieDetail item = new MovieDetail();
        item.setVod_id("tmdb:tv:42");
        item.setVod_name("测试剧");
        item.setVod_pic("https://image.tmdb.org/t/p/w500/x.jpg");
        item.setVod_remarks("2024 · 8.5");
        tmdb.getList().add(item);
        when(pianDanService.search("测试", 1, 24)).thenReturn(tmdb);
        when(mediaSubscriptionService.contentList(eq(7), isNull(), eq("测试"))).thenReturn(new MovieList());
        when(mediaSubscriptionService.isSubscribedTitle(7, "测试剧")).thenReturn(true);
        when(mediaSubscriptionService.absoluteClientCover("https://image.tmdb.org/t/p/w500/x.jpg")).thenReturn("/images?url=x.jpg");

        mockMvc.perform(get("/media/token-a").param("wd", "测试"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("tmdb:tv:42"))
                .andExpect(jsonPath("$.list[0].vod_remarks").value("已追 2024 · 8.5"))
                .andExpect(jsonPath("$.list[0].vod_pic").value("/images?url=x.jpg"));
    }

    @Test
    void searchPageTwoOnlyCarriesTmdbResults() throws Exception {
        when(pianDanService.search("凡人", 2, 24)).thenReturn(list("tmdb:tv:43"));

        mockMvc.perform(get("/media/token-a").param("wd", "凡人").param("pg", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].vod_id").value("tmdb:tv:43"));
    }

    @Test
    void detailContentDelegatesToSubscriptionDetail() throws Exception {
        when(mediaSubscriptionService.contentDetail(eq(7), eq(3), isNull(), isNull())).thenReturn(list("msub:3"));
        mockMvc.perform(get("/media/token-a").param("id", "msub:3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("msub:3"));
    }

    @Test
    void detailContentRejectsUnknownIdPrefix() throws Exception {
        mockMvc.perform(get("/media/token-a").param("id", "tg:123"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailContentRejectsMalformedId() throws Exception {
        mockMvc.perform(get("/media/token-a").param("id", "msub:abc"))
                .andExpect(status().isBadRequest());
    }
}
