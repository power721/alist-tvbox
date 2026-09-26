package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.config.AppProperties;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static cn.har01d.alist_tvbox.util.Constants.FOLDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveServiceTest {
    @Mock
    private HuyaService huyaService;
    @Mock
    private DouyuService douyuService;
    @Mock
    private BilibiliService bilibiliService;
    @Mock
    private CcService ccService;
    @Mock
    private KuaishouService kuaishouService;
    @Mock
    private DouyinService douyinService;
    @Mock
    private TwitchService twitchService;
    @Mock
    private SoopService soopService;
    @Mock
    private AcfunService acfunService;
    @Mock
    private InkeService inkeService;
    @Mock
    private HuajiaoService huajiaoService;
    @Mock
    private SixRoomService sixRoomService;
    @Mock
    private KugouLiveService kugouLiveService;
    @Mock
    private LookLiveService lookLiveService;
    @Mock
    private YyService yyService;
    @Mock
    private LiveFollowService liveFollowService;
    @Mock
    private SubscriptionService subscriptionService;

    private final AppProperties appProperties = new AppProperties();
    private LiveService liveService;

    @BeforeEach
    void setUp() {
        liveService = new LiveService(huyaService, douyuService, bilibiliService, ccService, kuaishouService,
                douyinService, twitchService, soopService, acfunService, inkeService, huajiaoService,
                sixRoomService, kugouLiveService, lookLiveService, yyService, liveFollowService, subscriptionService, appProperties);
    }

    @Test
    void searchCombinesAvailablePlatformResultsWhenOnePlatformFails() throws IOException {
        when(huyaService.getName()).thenReturn("虎牙");
        when(douyuService.getName()).thenReturn("斗鱼");
        when(huyaService.search("test")).thenReturn(movieList("huya$1"));
        when(douyuService.search("test")).thenThrow(new IOException("unavailable"));
        when(bilibiliService.search("test")).thenReturn(movieList("bili$2"));

        MovieList result = liveService.search("test");

        assertEquals(List.of("huya$1", "bili$2"), result.getList().stream().map(MovieDetail::getVod_id).toList());
        assertEquals("[虎牙]", result.getList().get(0).getVod_remarks());
        assertEquals(2, result.getTotal());
        assertEquals(2, result.getLimit());
    }

    @Test
    void categoryAddsPlatformFilterToFollowTab() throws IOException {
        // 本用例锁定"全部注册平台都进筛选"的语义,清掉默认隐藏(花椒默认隐藏因平台匿名接口限流)
        appProperties.setLiveHiddenPlatforms(List.of());
        stubPlatformTypes();
        when(huyaService.getName()).thenReturn("虎牙");
        when(douyuService.getName()).thenReturn("斗鱼");

        CategoryList result = liveService.category();

        var filter = result.getFilters().get("follow");
        assertEquals(1, filter.size());
        assertEquals("platform", filter.get(0).getKey());
        // 首项是"全部"(空值),其后按平台分类顺序逐一列出可选平台
        assertEquals("全部", filter.get(0).getValue().get(0).getN());
        assertEquals("", filter.get(0).getValue().get(0).getV());
        assertEquals("虎牙", filter.get(0).getValue().get(1).getN());
        assertEquals("huya", filter.get(0).getValue().get(1).getV());
        assertEquals("斗鱼", filter.get(0).getValue().get(2).getN());
        assertEquals("douyu", filter.get(0).getValue().get(2).getV());
        // 全部支持的平台都在筛选项里(8 老平台 + 6 个 pure_live 同源新平台),不只四大平台
        assertEquals(1 + 15, filter.get(0).getValue().size());
    }

    @Test
    void hiddenPlatformExcludedFromCategoryFilterAndSearchButDetailSurvives() throws IOException {
        stubPlatformTypes();
        when(huyaService.getName()).thenReturn("虎牙");
        appProperties.setLiveHiddenPlatforms(List.of("douyu"));

        CategoryList categories = liveService.category();
        // 平台分类与关注筛选都不再出现隐藏平台
        assertTrue(categories.getCategories().stream().noneMatch(c -> "douyu".equals(c.getType_id())));
        var filter = categories.getFilters().get("follow").get(0);
        assertTrue(filter.getValue().stream().noneMatch(v -> "douyu".equals(v.getV())));

        when(huyaService.search("test")).thenReturn(movieList("huya$1"));
        MovieList searchResult = liveService.search("test");
        assertEquals(List.of("huya$1"), searchResult.getList().stream().map(MovieDetail::getVod_id).toList());

        // detail 保留:已关注/历史里的隐藏平台房间仍可直达播放
        MovieList detailResult = movieList("douyu$1");
        when(douyuService.detail("douyu$1", null)).thenReturn(detailResult);
        MovieList decorated = liveService.detail("douyu$1", null);
        assertEquals("douyu$1", decorated.getList().get(0).getVod_id());
        verify(douyuService).detail("douyu$1", null);
    }

    @Test
    void platformOrderRearrangesCategoriesAndSearchButUnknownPlatformsAppend() throws IOException {
        stubPlatformTypes();
        appProperties.setLivePlatformOrder(List.of("douyu", "huya"));

        // 在册平台按配置序在前,未列入的平台按注册序追加尾部
        CategoryList categories = liveService.category();
        List<String> ids = categories.getCategories().stream().map(Category::getType_id).toList();
        assertEquals("follow", ids.get(0));
        assertEquals("douyu", ids.get(1));
        assertEquals("huya", ids.get(2));
        assertTrue(ids.indexOf("bilibili") > 2);

        // 聚合搜索结果同样按配置顺序聚拢
        when(huyaService.getName()).thenReturn("虎牙");
        when(douyuService.getName()).thenReturn("斗鱼");
        when(huyaService.search("test")).thenReturn(movieList("huya$1"));
        when(douyuService.search("test")).thenReturn(movieList("douyu$1"));
        MovieList searchResult = liveService.search("test");
        assertEquals(List.of("douyu$1", "huya$1"), searchResult.getList().stream().map(MovieDetail::getVod_id).toList());
    }

    private void stubPlatformTypes() {
        when(huyaService.getType()).thenReturn("huya");
        when(douyuService.getType()).thenReturn("douyu");
        when(bilibiliService.getType()).thenReturn("bilibili");
        when(ccService.getType()).thenReturn("cc");
        when(kuaishouService.getType()).thenReturn("kuaishou");
        when(douyinService.getType()).thenReturn("douyin");
        when(twitchService.getType()).thenReturn("twitch");
        when(soopService.getType()).thenReturn("soop");
        when(acfunService.getType()).thenReturn("acfun");
        when(inkeService.getType()).thenReturn("inke");
        when(huajiaoService.getType()).thenReturn("huajiao");
        when(sixRoomService.getType()).thenReturn("sixroom");
        when(kugouLiveService.getType()).thenReturn("kugoulive");
        when(lookLiveService.getType()).thenReturn("look");
        when(yyService.getType()).thenReturn("yy");
    }

    @Test
    void mixModeShowsHotRoomsBeforeCategoryFolders() throws IOException {
        appProperties.setLiveHotMode("mix");
        when(huyaService.getType()).thenReturn("huya");
        when(huyaService.home()).thenReturn(hotRooms(25));
        when(huyaService.category()).thenReturn(categoryList());

        MovieList result = liveService.list("huya", null, null, 1);

        // 热门最多 20 条,后面跟分类文件夹
        assertEquals(21, result.getList().size());
        assertEquals("huya$0", result.getList().get(0).getVod_id());
        assertEquals("huya$19", result.getList().get(19).getVod_id());
        assertEquals("huya-1", result.getList().get(20).getVod_id());
        assertEquals(FOLDER, result.getList().get(20).getVod_tag());
    }

    @Test
    void folderModePutsHotFolderFirst() throws IOException {
        // 不显式设置,锁定默认值为 folder
        when(huyaService.getType()).thenReturn("huya");
        when(huyaService.category()).thenReturn(categoryList());

        MovieList result = liveService.list("huya", null, null, 1);

        assertEquals(2, result.getList().size());
        assertEquals("huya-hot", result.getList().get(0).getVod_id());
        assertEquals("热门直播间", result.getList().get(0).getVod_name());
        assertEquals(FOLDER, result.getList().get(0).getVod_tag());
    }

    @Test
    void noneModeKeepsCategoryFoldersOnly() throws IOException {
        appProperties.setLiveHotMode("none");
        when(huyaService.getType()).thenReturn("huya");
        when(huyaService.category()).thenReturn(categoryList());

        MovieList result = liveService.list("huya", null, null, 1);

        assertEquals(1, result.getList().size());
        assertEquals("huya-1", result.getList().get(0).getVod_id());
    }

    @Test
    void hotCategoryIdReturnsPlatformHome() throws IOException {
        when(huyaService.getType()).thenReturn("huya");
        when(huyaService.home()).thenReturn(hotRooms(2));

        MovieList result = liveService.list("huya-hot", null, null, 1);

        assertEquals(List.of("huya$0", "huya$1"), result.getList().stream().map(MovieDetail::getVod_id).toList());
    }

    @Test
    void mixModeFallsBackToCategoriesWhenHomeFails() throws IOException {
        appProperties.setLiveHotMode("mix");
        when(huyaService.getType()).thenReturn("huya");
        when(huyaService.home()).thenThrow(new IOException("unavailable"));
        when(huyaService.category()).thenReturn(categoryList());

        MovieList result = liveService.list("huya", null, null, 1);

        assertEquals(1, result.getList().size());
        assertEquals("huya-1", result.getList().get(0).getVod_id());
    }

    private MovieList movieList(String id) {
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(id);
        MovieList result = new MovieList();
        result.setList(List.of(detail));
        return result;
    }

    private MovieList hotRooms(int count) {
        List<MovieDetail> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(movieList("huya$" + i).getList().get(0));
        }
        MovieList result = new MovieList();
        result.setList(list);
        return result;
    }

    private CategoryList categoryList() {
        Category category = new Category();
        category.setType_id("huya-1");
        category.setType_name("英雄联盟");
        CategoryList result = new CategoryList();
        result.setCategories(List.of(category));
        return result;
    }
}
