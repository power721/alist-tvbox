package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.LiveFollow;
import cn.har01d.alist_tvbox.entity.LiveFollowRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.UserService;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveFollowServiceTest {
    @Mock
    private LiveFollowRepository followRepository;
    @Mock
    private UserService userService;
    @Mock
    private AppProperties appProperties;
    @Mock
    private LiveShortLinkResolver shortLinkResolver;

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

    @Test
    void listShowsAnchorNameAsTitle() throws IOException {
        LivePlatform platform = mock(LivePlatform.class);
        when(platform.getType()).thenReturn("huya");
        when(platform.getName()).thenReturn("虎牙");
        MovieDetail info = new MovieDetail();
        info.setVod_name("三伏天机房降温大作战");
        info.setVod_actor("主机在燃烧");
        info.setVod_pic("https://example.com/cover.jpg");
        info.setVod_play_url("原画$http://a.flv");
        MovieList detailResult = new MovieList();
        detailResult.setList(List.of(info));
        when(platform.detail(eq("huya$123"), isNull())).thenReturn(detailResult);
        LiveFollowService service = new LiveFollowService(followRepository, userService, appProperties, List.of(platform), shortLinkResolver);

        LiveFollow follow = new LiveFollow();
        follow.setUid(1);
        follow.setPlatform("huya");
        follow.setRoomId("123");
        follow.setRoomName("旧房间名");
        follow.setAnchorName("旧主播名");
        when(followRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(follow));

        MovieList result = service.list(1);

        MovieDetail item = result.getList().get(0);
        assertEquals("主机在燃烧", item.getVod_name());
        assertEquals("虎牙 · 直播中", item.getVod_remarks());
        assertEquals("主机在燃烧", item.getVod_actor());
        assertEquals("主机在燃烧", follow.getAnchorName());
    }

    @Test
    void listFallsBackToStoredAnchorThenRoomName() {
        LiveFollowService service = new LiveFollowService(followRepository, userService, appProperties, List.of(), shortLinkResolver);
        LiveFollow follow = new LiveFollow();
        follow.setUid(1);
        follow.setPlatform("huya");
        follow.setRoomId("123");
        follow.setRoomName("房间名");
        follow.setAnchorName("主播名");
        when(followRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(follow));

        assertEquals("主播名", service.list(1).getList().get(0).getVod_name());

        follow.setAnchorName(null);
        assertEquals("房间名", service.list(1).getList().get(0).getVod_name());
    }

    @Test
    void followByUrlValidatesAndStoresRoomInfo() throws IOException {
        LivePlatform platform = mock(LivePlatform.class);
        when(platform.getType()).thenReturn("huya");
        when(followRepository.findByUidAndPlatformAndRoomId(1, "huya", "11342412")).thenReturn(Optional.empty());
        MovieDetail info = new MovieDetail();
        info.setVod_name("直播间标题");
        info.setVod_actor("主播名");
        MovieList detailResult = new MovieList();
        detailResult.setList(List.of(info));
        when(platform.detail(eq("huya$11342412"), isNull())).thenReturn(detailResult);
        when(followRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LiveFollowService service = new LiveFollowService(followRepository, userService, appProperties, List.of(platform), shortLinkResolver);

        service.followByUrl(1, "【主播】正在直播,快来看 https://www.huya.com/11342412 复制打开抖音");

        ArgumentCaptor<LiveFollow> captor = ArgumentCaptor.forClass(LiveFollow.class);
        verify(followRepository).save(captor.capture());
        assertEquals("huya", captor.getValue().getPlatform());
        assertEquals("11342412", captor.getValue().getRoomId());
        assertEquals("直播间标题", captor.getValue().getRoomName());
        assertEquals("主播名", captor.getValue().getAnchorName());
    }

    @Test
    void followByUrlExpandsShareLink() throws IOException {
        LivePlatform platform = mock(LivePlatform.class);
        when(platform.getType()).thenReturn("bili");
        when(shortLinkResolver.resolve("https://b23.tv/abc123")).thenReturn(new String[]{"bili", "6"});
        when(followRepository.findByUidAndPlatformAndRoomId(1, "bili", "6")).thenReturn(Optional.empty());
        MovieDetail info = new MovieDetail();
        info.setVod_name("直播间");
        MovieList detailResult = new MovieList();
        detailResult.setList(List.of(info));
        when(platform.detail(eq("bili$6"), isNull())).thenReturn(detailResult);
        when(followRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LiveFollowService service = new LiveFollowService(followRepository, userService, appProperties, List.of(platform), shortLinkResolver);

        service.followByUrl(1, "https://b23.tv/abc123");

        verify(followRepository).save(any());
    }

    @Test
    void followByUrlRejectsUnrecognizedUrl() {
        assertThrows(BadRequestException.class, () -> liveFollowService.followByUrl(1, "https://example.com/1"));
        verify(followRepository, never()).save(any());
    }

    @Test
    void followByUrlRejectsDuplicateAndMissingRoom() throws IOException {
        LivePlatform platform = mock(LivePlatform.class);
        when(platform.getType()).thenReturn("huya");
        LiveFollowService service = new LiveFollowService(followRepository, userService, appProperties, List.of(platform), shortLinkResolver);

        when(followRepository.findByUidAndPlatformAndRoomId(1, "huya", "11342412")).thenReturn(Optional.of(new LiveFollow()));
        assertThrows(BadRequestException.class, () -> service.followByUrl(1, "https://www.huya.com/11342412"));

        when(followRepository.findByUidAndPlatformAndRoomId(1, "huya", "11342412")).thenReturn(Optional.empty());
        MovieList empty = new MovieList();
        empty.setList(List.of());
        when(platform.detail(eq("huya$11342412"), isNull())).thenReturn(empty);
        assertThrows(BadRequestException.class, () -> service.followByUrl(1, "https://www.huya.com/11342412"));
        verify(followRepository, never()).save(any());
    }

    /**
     * 预热线程的 mock 请求上下文必须能支撑 ServletUriComponentsBuilder.fromCurrentRequest():
     * 平台 detail(B站/虎牙/CC 的封面代理 URL)依赖它,原生类型方法(如 getServerPort 返回 int)
     * 若拆箱 null 会 NPE,导致预热写入的失败结果被长缓存放大成"状态全部未知"。
     */
    @Test
    void mockRequestAttributesSupportsCurrentRequestBuilder() throws Exception {
        java.lang.reflect.Method method = LiveFollowService.class.getDeclaredMethod("mockRequestAttributes");
        method.setAccessible(true);
        Object attributes = method.invoke(null);

        RequestContextHolder.setRequestAttributes((RequestAttributes) attributes);
        try {
            var uri = org.springframework.web.servlet.support.ServletUriComponentsBuilder
                    .fromCurrentRequest()
                    .replacePath("/images")
                    .replaceQuery("url=https://example.com/a.jpg")
                    .build()
                    .toUriString();
            assertTrue(uri.startsWith("http://127.0.0.1"));
            assertTrue(uri.contains("/images"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
