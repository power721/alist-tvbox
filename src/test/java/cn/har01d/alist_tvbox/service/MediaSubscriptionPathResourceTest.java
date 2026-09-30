package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.DeadLinkRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisode;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手动路径资源(issue #1071):入账形态(MOUNTED 直连目录,shareId=null)、异剧门禁、
 * 巡检原位刷新(增长同步/归属复核豁免)、换血排除、主源/启用/恢复三入口的路径分支。
 */
class MediaSubscriptionPathResourceTest {

    private final AppProperties appProperties = new AppProperties();
    private final MediaSubscriptionRepository subscriptionRepository = mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = mock(MediaSubscriptionResourceRepository.class);
    private final MediaSubscriptionEventRepository eventRepository = mock(MediaSubscriptionEventRepository.class);
    private final MediaSubscriptionEpisodeRepository episodeRepository = mock(MediaSubscriptionEpisodeRepository.class);
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository = mock(MediaSubscriptionEpisodeSourceRepository.class);
    private final DeadLinkRepository deadLinkRepository = mock(DeadLinkRepository.class);
    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final SettingRepository settingRepository = mock(SettingRepository.class);
    private final AListService aListService = mock(AListService.class);

    private MediaSubscriptionCheckService service;

    private static final String DIR = "/115/115/电视剧/父母的剧";

    @BeforeEach
    void setUp() {
        service = new MediaSubscriptionCheckService(
                subscriptionRepository, resourceRepository, eventRepository, episodeRepository, episodeSourceRepository,
                deadLinkRepository, null, siteRepository, null, null, settingRepository,
                null, aListService, null,
                null, null, null, null, null,
                null, null, null, appProperties, new ObjectMapper(), null, null);
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription()));
        when(siteRepository.findById(1)).thenReturn(Optional.of(new Site()));
        when(settingRepository.findById(anyString())).thenReturn(Optional.empty());
        when(episodeRepository.findBySubscriptionIdOrderByNumber(anyInt())).thenReturn(List.of());
        when(episodeRepository.findBySubscriptionIdAndSeasonAndNumber(anyInt(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(episodeRepository.save(any())).thenAnswer(inv -> {
            MediaSubscriptionEpisode episode = inv.getArgument(0);
            if (episode.getId() == null) {
                episode.setId(601);
            }
            return episode;
        });
        when(episodeSourceRepository.findByResourceId(anyInt())).thenReturn(List.of());
        when(episodeSourceRepository.findByEpisodeIdAndResourceId(anyInt(), anyInt())).thenReturn(Optional.empty());
        when(episodeSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(anyInt())).thenReturn(List.of());
        when(resourceRepository.save(any())).thenAnswer(inv -> {
            MediaSubscriptionResource row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(41);
            }
            return row;
        });
        when(aListService.listFiles(any(), anyString(), anyInt(), anyInt(), anyBoolean())).thenReturn(new FsResponse());
        appProperties.getSubscription().setPrimeCheckTimes(List.of());
        appProperties.getSubscription().setNightCheckTimes(List.of());
        appProperties.setFormats(Set.of("mkv", "mp4"));
    }

    private MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(9);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setKeyword("测试剧");
        subscription.setSeason(1);
        subscription.setMountPath("/追剧/9-测试剧"); // auxMounts 按「非主源挂载路径」过滤,主路径非空
        return subscription;
    }

    private MediaSubscriptionResource pathResource(String state) {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(41);
        resource.setSubscriptionId(9);
        resource.setLink("path:" + DIR);
        resource.setSource(MediaSubscriptionResource.SOURCE_MANUAL);
        resource.setTitle("");
        resource.setScore(1000);
        resource.setState(state);
        resource.setMountPath(MediaSubscriptionResource.STATE_MOUNTED.equals(state) ? DIR : null);
        return resource;
    }

    private void stubEpisodes(String... names) {
        FsResponse listing = new FsResponse();
        listing.setFiles(java.util.Arrays.stream(names).map(name -> {
            FsInfo file = new FsInfo();
            file.setName(name);
            file.setType(0);
            file.setSize(800L * 1024 * 1024);
            return file;
        }).toList());
        when(aListService.listFiles(any(), eq(DIR), anyInt(), anyInt(), anyBoolean())).thenReturn(listing);
    }

    // ---------- 入账 ----------

    @Test
    void registerWritesMountedRowsWithDirectPath() {
        stubEpisodes("第01集.mp4", "第02集.mp4");
        MediaSubscriptionResource resource = pathResource(null);

        Set<Integer> covered = service.registerPathResource(subscription(), resource);

        assertEquals(Set.of(1, 2), covered);
        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository, times(2)).save(captor.capture());
        MediaSubscriptionResource row = captor.getAllValues().get(0);
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, row.getState());
        assertEquals(DIR, row.getMountPath());
        assertNullShareId(row);
        ArgumentCaptor<MediaSubscriptionEpisodeSource> rows = ArgumentCaptor.forClass(MediaSubscriptionEpisodeSource.class);
        verify(episodeSourceRepository, times(2)).save(rows.capture());
        assertEquals("第01集.mp4", rows.getAllValues().get(0).getRelPath());
        assertEquals(2, captor.getAllValues().get(1).getEpisodesFound());
    }

    private static void assertNullShareId(MediaSubscriptionResource row) {
        assertTrue(row.getShareId() == null, "路径资源无 Share 挂载:shareId 必须为 null");
    }

    @Test
    void registerRejectsForeignEpisodeNumbers() {
        // 官方 3 集已播完,目录列到第 5 集(衔接链式超界,非断裂噪声):资源级异剧门禁整体拒绝
        stubEpisodes("第01集.mp4", "第02集.mp4", "第03集.mp4", "第04集.mp4", "第05集.mp4");
        MediaSubscription subscription = subscription();
        subscription.setOfficialTotal(3);
        subscription.setOfficialEpisodes(3);
        subscription.setStatus(MediaSubscription.STATUS_ENDED);

        MediaSubscriptionResource resource = pathResource(null);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.registerPathResource(subscription, resource));

        assertTrue(error.getMessage().contains("集号超出官方范围"), error.getMessage());
        verify(resourceRepository, never()).save(any(MediaSubscriptionResource.class));
    }

    @Test
    void registerRejectsUnrecognizableDirectory() {
        when(aListService.listFiles(any(), eq(DIR), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new FsResponse()); // 空/不可访问

        MediaSubscriptionResource resource = pathResource(null);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.registerPathResource(subscription(), resource));

        assertTrue(error.getMessage().contains("目录"), error.getMessage());
        verify(resourceRepository, never()).save(any(MediaSubscriptionResource.class));
    }

    // ---------- 巡检原位刷新 ----------

    @Test
    void refreshAuxMountsRelistsPathResourceForGrowth() {
        stubEpisodes("第01集.mp4", "第02集.mp4", "第03集.mp4");
        MediaSubscriptionResource resource = pathResource(MediaSubscriptionResource.STATE_MOUNTED);
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(List.of(resource));

        service.refreshAuxMounts(subscription());

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository).save(captor.capture());
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, captor.getValue().getState());
        assertEquals(DIR, captor.getValue().getMountPath());
        assertEquals(3, captor.getValue().getEpisodesFound());
        verify(episodeSourceRepository, times(3)).save(any(MediaSubscriptionEpisodeSource.class));
    }

    @Test
    void refreshAuxMountsKeepsPathResourceOnOwnershipFail() {
        // 官方 3 集已播完,目录衔接列到第 5 集:归属复核必败,路径资源=用户手动事实,豁免保留(钉选同款)
        stubEpisodes("第01集.mp4", "第02集.mp4", "第03集.mp4", "第04集.mp4", "第05集.mp4");
        MediaSubscription subscription = subscription();
        subscription.setOfficialTotal(3);
        subscription.setOfficialEpisodes(3);
        subscription.setStatus(MediaSubscription.STATUS_ENDED);
        MediaSubscriptionResource resource = pathResource(MediaSubscriptionResource.STATE_MOUNTED);
        resource.setTitle("不匹配的标题");
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(List.of(resource));

        service.refreshAuxMounts(subscription);

        verify(resourceRepository, never()).save(any(MediaSubscriptionResource.class));
    }

    @Test
    void refreshAuxMountsKeepsPathResourceOnTransientListingError() {
        // 网络抖动/驱动限流变体等未识别错误:目录是用户事实,退役后无分享链接可重探 = 永久失流,
        // 必须保挂载下轮重试(线上反馈:自己网盘目录作源,一轮巡检后被判死,只能重新分享建新资源)
        when(aListService.listFiles(any(), eq(DIR), anyInt(), anyInt(), anyBoolean()))
                .thenThrow(new IllegalStateException("read timeout"));
        MediaSubscriptionResource resource = pathResource(MediaSubscriptionResource.STATE_MOUNTED);
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(List.of(resource));

        service.refreshAuxMounts(subscription());

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, resource.getState());
        assertEquals(DIR, resource.getMountPath());
        verify(resourceRepository, never()).save(any(MediaSubscriptionResource.class));
        verify(deadLinkRepository, never()).save(any());
    }

    @Test
    void refreshAuxMountsRetiresPathResourceWhenDirectoryGone() {
        // 「确认不存在」类错误 = 目录真被删除:照常退役;path: 合成链接不进跨订阅黑名单
        when(aListService.listFiles(any(), eq(DIR), anyInt(), anyInt(), anyBoolean()))
                .thenThrow(new IllegalStateException("object not found"));
        MediaSubscriptionResource resource = pathResource(MediaSubscriptionResource.STATE_MOUNTED);
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(List.of(resource));

        service.refreshAuxMounts(subscription());

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository).save(captor.capture());
        assertEquals(MediaSubscriptionResource.STATE_RETIRED, captor.getValue().getState());
        assertNull(captor.getValue().getMountPath());
        verify(deadLinkRepository, never()).save(any());
    }

    @Test
    void refreshAuxMountsDemotesRowsToMissingWhenPathListingEmptied() {
        // 列空(列举有文件但全部不可识别,如用户把剧集文件挪走只剩说明文件)≠ 目录死:
        // 行降 MISSING 保挂载,内容回来下轮重列自愈;分享列空才是分享被清理,退役
        stubEpisodes("说明.txt");
        MediaSubscriptionResource resource = pathResource(MediaSubscriptionResource.STATE_MOUNTED);
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(List.of(resource));
        when(episodeSourceRepository.findByResourceId(41)).thenReturn(List.of(
                sourceRow(MediaSubscriptionEpisodeSource.STATE_LISTED),
                sourceRow(MediaSubscriptionEpisodeSource.STATE_LISTED)));

        service.refreshAuxMounts(subscription());

        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, resource.getState());
        assertEquals(DIR, resource.getMountPath());
        ArgumentCaptor<MediaSubscriptionResource> resources = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository).save(resources.capture());
        assertEquals(0, resources.getValue().getEpisodesFound());
        ArgumentCaptor<MediaSubscriptionEpisodeSource> rows = ArgumentCaptor.forClass(MediaSubscriptionEpisodeSource.class);
        verify(episodeSourceRepository, times(2)).save(rows.capture());
        assertEquals(MediaSubscriptionEpisodeSource.STATE_MISSING, rows.getAllValues().get(0).getState());
        assertEquals(MediaSubscriptionEpisodeSource.STATE_MISSING, rows.getAllValues().get(1).getState());
    }

    @Test
    void contagionNeverRetiresPathResource() {
        // 播放/采样取链双败的传染判定:分享整源退役合理,路径资源退役=失去供流且永不救回;
        // 行级 FAILED 照留,下轮重列按用户目录事实翻案
        MediaSubscriptionResource resource = pathResource(MediaSubscriptionResource.STATE_MOUNTED);
        MediaSubscriptionEpisodeSource liveRow = sourceRow(MediaSubscriptionEpisodeSource.STATE_LISTED);
        when(episodeSourceRepository.findByResourceId(41)).thenReturn(List.of(liveRow));
        when(aListService.getFile(any(), anyString())).thenThrow(new IllegalStateException("object not found"));

        boolean retired = service.contagion(subscription(), resource, 555);

        assertFalse(retired);
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, resource.getState());
        assertEquals(DIR, resource.getMountPath());
        verify(resourceRepository, never()).save(any(MediaSubscriptionResource.class));
    }

    @Test
    void contagionKeepsPathResourceWhenAllRowsFailed() {
        // 单集目录(无其他集可二次探测):旧逻辑「全部集取链失败」直接整源退役,路径资源必须豁免
        MediaSubscriptionResource resource = pathResource(MediaSubscriptionResource.STATE_MOUNTED);
        when(episodeSourceRepository.findByResourceId(41)).thenReturn(List.of(
                sourceRow(MediaSubscriptionEpisodeSource.STATE_FAILED)));

        boolean retired = service.contagion(subscription(), resource, 555);

        assertFalse(retired);
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, resource.getState());
        verify(resourceRepository, never()).save(any(MediaSubscriptionResource.class));
    }

    @Test
    void reRegisterPathResourceHealsFreshFailedRowsImmediately() {
        // 线上反馈的致死闭环:播放失败把行打成 FAILED(判决时间=now)→ 用户重加同目录 →
        // 旧 7 天翻案窗拒绝回血(路径没变)→ 看似"路径不能作源只能重新分享"。路径资源以目录
        // 列举为事实源:文件还在即回 LISTED,重加/恢复立即生效
        stubEpisodes("第01集.mp4");
        MediaSubscriptionEpisode episode = new MediaSubscriptionEpisode();
        episode.setId(601);
        episode.setSubscriptionId(9);
        episode.setSeason(1);
        episode.setNumber(1);
        when(episodeRepository.findBySubscriptionIdOrderByNumber(9)).thenReturn(List.of(episode));
        MediaSubscriptionEpisodeSource failed = sourceRow(MediaSubscriptionEpisodeSource.STATE_FAILED);
        failed.setEpisodeId(601);
        failed.setRelPath("第01集.mp4");
        failed.setLastVerifiedTime(System.currentTimeMillis()); // 判决刚落:7 天窗内
        when(episodeSourceRepository.findByResourceId(41)).thenReturn(List.of(failed));
        MediaSubscriptionResource resource = pathResource(MediaSubscriptionResource.STATE_MOUNTED);

        Set<Integer> covered = service.registerPathResource(subscription(), resource);

        assertEquals(Set.of(1), covered);
        ArgumentCaptor<MediaSubscriptionEpisodeSource> rows = ArgumentCaptor.forClass(MediaSubscriptionEpisodeSource.class);
        verify(episodeSourceRepository).save(rows.capture());
        assertEquals(MediaSubscriptionEpisodeSource.STATE_LISTED, rows.getValue().getState(),
                "重列用户目录文件仍在,FAILED 行必须立即回 LISTED,不等 7 天翻案窗");
    }

    private MediaSubscriptionEpisodeSource sourceRow(String state) {
        MediaSubscriptionEpisodeSource row = new MediaSubscriptionEpisodeSource();
        row.setId(701);
        row.setResourceId(41);
        row.setRelPath("第01集.mp4");
        row.setState(state);
        return row;
    }

    // ---------- 换血/主源闸门 ----------

    @Test
    void evictWeakestNeverEvictsPathResource() {
        MediaSubscriptionResource resource = pathResource(MediaSubscriptionResource.STATE_MOUNTED);
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(List.of(resource));

        MediaSubscriptionResource evicted = service.evictWeakestAuxMount(subscription(), Set.of(1, 2, 3));

        assertTrue(evicted == null, "路径资源是用户亲自添加的目录,不得被换血挤掉");
    }

    @Test
    void usableAsPrimaryBlocksPathResource() {
        assertFalse(service.usableAsPrimary(pathResource(MediaSubscriptionResource.STATE_MOUNTED), 10));
    }

    // ---------- 启用/转主源/恢复三入口 ----------

    @Test
    void mountCandidateRefreshesPathResourceWithoutProbe() {
        stubEpisodes("第01集.mp4");
        MediaSubscriptionResource resource = pathResource(MediaSubscriptionResource.STATE_MOUNTED);

        service.mountCandidate(subscription(), resource);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository, times(2)).save(captor.capture());
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, captor.getAllValues().get(0).getState());
    }

    @Test
    void activateAsyncRejectsPathResourceSynchronously() {
        when(resourceRepository.findById(41)).thenReturn(Optional.of(pathResource(MediaSubscriptionResource.STATE_MOUNTED)));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.activateAsync(1, 9, 41));

        assertTrue(error.getMessage().contains("路径资源不支持转主源"), error.getMessage());
    }

    @Test
    void restorePathResourceReRegistersInPlace() {
        stubEpisodes("第01集.mp4", "第02集.mp4");
        when(resourceRepository.findById(41))
                .thenReturn(Optional.of(pathResource(MediaSubscriptionResource.STATE_RETIRED)));

        service.restoreResource(1, 9, 41);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository, times(2)).save(captor.capture());
        MediaSubscriptionResource row = captor.getAllValues().get(0);
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, row.getState());
        assertEquals(DIR, row.getMountPath());
    }

    // ---------- 静态助手 ----------

    @Test
    void browseDriveDirsListsOnlyDirectoriesSorted() {
        FsResponse listing = new FsResponse();
        FsInfo dirB = new FsInfo();
        dirB.setName("剧集B");
        dirB.setType(1);
        FsInfo file = new FsInfo();
        file.setName("第01集.mp4");
        file.setType(0);
        FsInfo dirA = new FsInfo();
        dirA.setName("剧集A");
        dirA.setType(1);
        listing.setFiles(List.of(dirB, file, dirA));
        when(aListService.listFiles(any(), eq("/115"), anyInt(), anyInt(), anyBoolean())).thenReturn(listing);

        List<String> dirs = service.browseDriveDirs("/115");

        assertEquals(List.of("剧集A", "剧集B"), dirs);
        verify(aListService).listFiles(any(), eq("/115"), eq(1), eq(0), eq(false));
    }

    @Test
    void browseDriveDirsBlankPathListsRootAndRejectsTraversal() {
        when(aListService.listFiles(any(), eq("/"), anyInt(), anyInt(), anyBoolean())).thenReturn(new FsResponse());
        assertEquals(List.of(), service.browseDriveDirs(" "));
        assertEquals(List.of(), service.browseDriveDirs(null));
        assertThrows(BadRequestException.class, () -> service.browseDriveDirs("/115/../etc"));
        assertThrows(BadRequestException.class, () -> service.browseDriveDirs("115/相对路径"));
    }

    @Test
    void browseDriveDirsSurfacesListingFailure() {
        when(aListService.listFiles(any(), eq("/不存在"), anyInt(), anyInt(), anyBoolean()))
                .thenThrow(new IllegalStateException("object not found"));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.browseDriveDirs("/不存在"));

        assertTrue(error.getMessage().contains("目录不可访问"), error.getMessage());
    }

    @Test
    void pathHelpersRecognizeOnlyPathLinks() {
        assertTrue(MediaSubscriptionCheckService.isPathResource(pathResource(null)));
        MediaSubscriptionResource magnet = new MediaSubscriptionResource();
        magnet.setLink("offline:某产物");
        magnet.setSource(MediaSubscriptionResource.SOURCE_MAGNET);
        assertFalse(MediaSubscriptionCheckService.isPathResource(magnet));
        assertEquals(DIR, MediaSubscriptionCheckService.pathOf("path:" + DIR));
        assertTrue(MediaSubscriptionCheckService.pathOf("https://pan.quark.cn/x") == null);
        assertTrue(MediaSubscriptionCheckService.pathOf("path:") == null);
    }

    @Test
    void driveIdMapsAlistDriverNames() {
        assertEquals(8, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("115 Cloud"));
        assertEquals(8, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("115 Open"));
        assertEquals(5, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("Quark"));
        assertEquals(7, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("UC"));
        assertEquals(10, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("BaiduNetdisk"));
        assertEquals(0, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("AliyundriveOpen"));
        assertEquals(3, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("123Pan"));
        assertEquals(9, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("189CloudPC"));
        assertEquals(6, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("139Yun"));
        assertEquals(2, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("ThunderBrowser"));
        assertEquals(12, cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("GuangYaPan"));
        assertTrue(cn.har01d.alist_tvbox.domain.DriveId.fromDriverName("OpenList") == null);
        assertTrue(cn.har01d.alist_tvbox.domain.DriveId.fromDriverName(null) == null);
    }
}
