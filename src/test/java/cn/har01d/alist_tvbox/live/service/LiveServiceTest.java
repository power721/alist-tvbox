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
    private LiveFollowService liveFollowService;
    @Mock
    private SubscriptionService subscriptionService;

    private final AppProperties appProperties = new AppProperties();
    private LiveService liveService;

    @BeforeEach
    void setUp() {
        liveService = new LiveService(huyaService, douyuService, bilibiliService, ccService, kuaishouService,
                douyinService, twitchService, soopService, liveFollowService, subscriptionService, appProperties);
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
