package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.RestErrorHandler;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionTransferService;
import cn.har01d.alist_tvbox.service.PianDanService;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 片单追更管理端代理端点:免 vod token(登录态鉴权),ac 固定 web(豆瓣封面 /images 代理)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaSubscriptionControllerTest {
    @Mock
    private MediaSubscriptionService subscriptionService;
    @Mock
    private MediaSubscriptionCheckService checkService;
    @Mock
    private MediaSubscriptionTransferService transferService;
    @Mock
    private PianDanService pianDanService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MediaSubscriptionController(subscriptionService, checkService, transferService, pianDanService))
                .setControllerAdvice(new RestErrorHandler())
                .build();
    }

    @Test
    void navigationCategoriesDelegatesToPianDan() throws Exception {
        CategoryList categoryList = new CategoryList();
        Category category = new Category();
        category.setType_id("douban:hot_tv");
        category.setType_name("豆瓣·热门电视");
        categoryList.getCategories().add(category);
        when(pianDanService.subscriptionCategory()).thenReturn(categoryList);

        mockMvc.perform(get("/api/media-subscriptions/navigation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.class[0].type_id").value("douban:hot_tv"))
                .andExpect(jsonPath("$.class[0].type_name").value("豆瓣·热门电视"));
    }

    @Test
    void navigationListFixesAcToWeb() throws Exception {
        MovieList movieList = new MovieList();
        MovieDetail detail = new MovieDetail();
        detail.setVod_id("tmdb:tv:42");
        detail.setVod_name("测试剧");
        movieList.setList(List.of(detail));
        when(pianDanService.list(eq("tmdb:tv_popular"), eq("web"), eq(1), eq(24), any())).thenReturn(movieList);

        mockMvc.perform(get("/api/media-subscriptions/navigation/list")
                        .param("t", "tmdb:tv_popular").param("pg", "1").param("size", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].vod_id").value("tmdb:tv:42"));

        verify(pianDanService).list(eq("tmdb:tv_popular"), eq("web"), eq(1), eq(24), any());
    }

    @Test
    void navigationListPassesFiltersAsQueryParams() throws Exception {
        when(pianDanService.list(any(), any(), eq(2), eq(24), any())).thenReturn(new MovieList());

        mockMvc.perform(get("/api/media-subscriptions/navigation/list")
                        .param("t", "tmdb:discover_tv").param("pg", "2").param("with_origin_country", "JP"))
                .andExpect(status().isOk());

        verify(pianDanService).list(eq("tmdb:discover_tv"), eq("web"), eq(2), eq(24),
                eq(Map.of("t", "tmdb:discover_tv", "pg", "2", "with_origin_country", "JP")));
    }

    @Test
    void navigationDetailTmdbProxiesCoverWithoutPollutingCache() throws Exception {
        MovieDetail cached = new MovieDetail();
        cached.setVod_id("tmdb:tv:42");
        cached.setVod_name("测试剧");
        cached.setVod_pic("https://image.tmdb.org/t/p/w500/abc.jpg");
        cached.setVod_content("剧情简介");
        cached.setExt(List.of(1, 2));
        when(pianDanService.tmdbDetail("tv", 42)).thenReturn(cached);
        when(subscriptionService.proxiedCover(any())).thenAnswer(invocation -> {
            String cover = invocation.getArgument(0);
            if (cover == null || !cover.startsWith("http")) {
                return cover;
            }
            return "/images?url=" + java.net.URLEncoder.encode(cover, java.nio.charset.StandardCharsets.UTF_8);
        });

        mockMvc.perform(get("/api/media-subscriptions/navigation/detail").param("id", "tmdb:tv:42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vod_name").value("测试剧"))
                .andExpect(jsonPath("$.vod_pic").value("/images?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw500%2Fabc.jpg"))
                .andExpect(jsonPath("$.vod_content").value("剧情简介"))
                .andExpect(jsonPath("$.ext[0]").value(1))
                .andExpect(jsonPath("$.ext[1]").value(2));

        // 共享缓存实例不得被改写:tmdbDetail 命中缓存返回同一对象
        org.junit.jupiter.api.Assertions.assertEquals("https://image.tmdb.org/t/p/w500/abc.jpg", cached.getVod_pic());
    }

    @Test
    void navigationDetailDoubanLocalHit() throws Exception {
        MovieDetail local = new MovieDetail();
        local.setVod_name("测试剧");
        local.setVod_pic("https://img9.doubanio.com/x.jpg");
        local.setVod_director("导演甲");
        local.setVod_actor("演员甲 / 演员乙");
        when(subscriptionService.localDoubanDetail("测试剧", 2024)).thenReturn(local);
        when(subscriptionService.proxiedCover("https://img9.doubanio.com/x.jpg"))
                .thenReturn("/images?url=https%3A%2F%2Fimg9.doubanio.com%2Fx.jpg");

        mockMvc.perform(get("/api/media-subscriptions/navigation/detail").param("id", "s:测试剧@2024"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vod_id").value("s:测试剧@2024"))
                .andExpect(jsonPath("$.vod_name").value("测试剧"))
                .andExpect(jsonPath("$.vod_pic").value("/images?url=https%3A%2F%2Fimg9.doubanio.com%2Fx.jpg"))
                .andExpect(jsonPath("$.vod_director").value("导演甲"))
                .andExpect(jsonPath("$.vod_actor").value("演员甲 / 演员乙"));
    }

    @Test
    void navigationDetailDoubanLocalMissFallsBackToTitleOnly() throws Exception {
        when(subscriptionService.localDoubanDetail("冷门剧", null)).thenReturn(null);

        mockMvc.perform(get("/api/media-subscriptions/navigation/detail").param("id", "s:冷门剧"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vod_id").value("s:冷门剧"))
                .andExpect(jsonPath("$.vod_name").value("冷门剧"))
                .andExpect(jsonPath("$.vod_content").value(""));
    }

    @Test
    void navigationDetailRejectsUnknownId() throws Exception {
        mockMvc.perform(get("/api/media-subscriptions/navigation/detail").param("id", "msubep-1-2"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/media-subscriptions/navigation/detail").param("id", "tmdb:tv:notanumber"))
                .andExpect(status().isBadRequest());
    }
}
