package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

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

    private LiveService liveService;

    @BeforeEach
    void setUp() {
        liveService = new LiveService(huyaService, douyuService, bilibiliService, ccService, kuaishouService,
                douyinService, twitchService, soopService, liveFollowService, subscriptionService);
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

    private MovieList movieList(String id) {
        MovieDetail detail = new MovieDetail();
        detail.setVod_id(id);
        MovieList result = new MovieList();
        result.setList(List.of(detail));
        return result;
    }
}
