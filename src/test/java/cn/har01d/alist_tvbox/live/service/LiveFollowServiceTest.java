package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.LiveFollow;
import cn.har01d.alist_tvbox.entity.LiveFollowRepository;
import cn.har01d.alist_tvbox.service.UserService;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveFollowServiceTest {
    @Mock
    private LiveFollowRepository followRepository;
    @Mock
    private UserService userService;
    @Mock
    private AppProperties appProperties;

    @InjectMocks
    private LiveFollowService liveFollowService;

    @Test
    void offlineRoomGetsPlaceholderBeforeFollowTrack() {
        when(followRepository.findByUidAndPlatformAndRoomId(anyInt(), anyString(), anyString())).thenReturn(Optional.empty());
        MovieDetail detail = new MovieDetail();
        detail.setVod_id("huya$123");

        liveFollowService.appendFollowTrack(detail, 1);

        assertEquals("未开播$$$关注", detail.getVod_play_from());
        assertEquals("未开播$offline$$$关注主播$follow$huya$123#取消关注$unfollow$huya$123", detail.getVod_play_url());
        // 第一集必须是占位,不能是会被播放器自动选中的关注操作
        assertTrue(detail.getVod_play_url().startsWith("未开播$offline"));
    }

    @Test
    void followedOfflineRoomShowsFollowedLabel() {
        when(followRepository.findByUidAndPlatformAndRoomId(anyInt(), anyString(), anyString()))
                .thenReturn(Optional.of(new LiveFollow()));
        MovieDetail detail = new MovieDetail();
        detail.setVod_id("douyu$456");

        liveFollowService.appendFollowTrack(detail, 1);

        assertEquals("未开播$$$已关注", detail.getVod_play_from());
        assertEquals("未开播$offline$$$关注主播$follow$douyu$456#取消关注$unfollow$douyu$456", detail.getVod_play_url());
    }

    @Test
    void liveRoomKeepsPlatformTracksFirst() {
        when(followRepository.findByUidAndPlatformAndRoomId(anyInt(), anyString(), anyString())).thenReturn(Optional.empty());
        MovieDetail detail = new MovieDetail();
        detail.setVod_id("huya$123");
        detail.setVod_play_from("线路1$$$线路2");
        detail.setVod_play_url("原画$http://a.flv$$$超清$http://b.flv");

        liveFollowService.appendFollowTrack(detail, 1);

        assertEquals("线路1$$$线路2$$$关注", detail.getVod_play_from());
        assertEquals("原画$http://a.flv$$$超清$http://b.flv$$$关注主播$follow$huya$123#取消关注$unfollow$huya$123",
                detail.getVod_play_url());
    }
}
