package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.EpisodeInfo;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TVBox 快路径详情:集源行(LIVE)× MOUNTED 资源 + 转存副本直接装配播放列表,零目录列举 ——
 * 「我的追剧」逻辑线路 msubep-{id}-{集} + 盘线路 `1@{pid}`;行未同步回落旧实时列举路径。
 */
class MediaSubscriptionFastDetailTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final long MB = 1024L * 1024;

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository = Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class);
    private final SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final TvBoxService tvBoxService = Mockito.mock(TvBoxService.class);
    private final MediaSubscriptionTransferService transferService = Mockito.mock(MediaSubscriptionTransferService.class);
    private final MetadataService metadataService = Mockito.mock(MetadataService.class);
    private final ProxyService proxyService = Mockito.mock(ProxyService.class);
    private final SiteRepository siteRepository = Mockito.mock(SiteRepository.class);
    private final AtomicInteger pid = new AtomicInteger(100);

    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, null, episodeSourceRepository,
            null, null, null, tvBoxService, null, metadataService, checkService, transferService, settingRepository,
            new AppProperties(), new ObjectMapper(), proxyService, siteRepository);

    private final MediaSubscription subscription = subscription();

    private static MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(7);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setCurrentEpisodes(18);
        subscription.setMountPath("/追剧/7-测试剧");
        subscription.setShareId(123);
        return subscription;
    }

    @BeforeEach
    void setUp() {
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));
        Mockito.when(siteRepository.findById(1)).thenReturn(Optional.of(new Site()));
        Mockito.when(checkService.mainDrives(Mockito.any())).thenReturn(List.of());
        Mockito.when(settingRepository.findById(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(proxyService.generateProxyUrl(Mockito.any(Site.class), Mockito.anyString(), Mockito.any(java.time.Duration.class)))
                .thenAnswer(invocation -> pid.incrementAndGet());
    }

    private static MediaSubscriptionResource resource(int id, int type, String mountPath, int score, String state) {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(id);
        resource.setType(type);
        resource.setMountPath(mountPath);
        resource.setScore(score);
        resource.setState(state);
        resource.setTitle("资源" + id);
        return resource;
    }

    private static MediaSubscriptionEpisodeSource row(int resourceId, String relPath, long size, String state) {
        MediaSubscriptionEpisodeSource row = new MediaSubscriptionEpisodeSource();
        row.setResourceId(resourceId);
        row.setRelPath(relPath);
        row.setFileSize(size);
        row.setState(state);
        return row;
    }

    private static List<Object[]> rows(Object[]... entries) {
        return List.of(entries);
    }

    @Test
    void fastDetailBuildsPlaylistFromEpisodeSourceRows() {
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of(
                resource(11, 5, "/追剧/7-测试剧", 100, MediaSubscriptionResource.STATE_MOUNTED),
                resource(12, 10, "/追剧/.sources/7-测试剧-补1", 80, MediaSubscriptionResource.STATE_MOUNTED),
                resource(13, 5, "/追剧/.sources/7-测试剧-补2", 60, MediaSubscriptionResource.STATE_RETIRED)));
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(rows(
                new Object[]{1, row(11, "第01集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)},
                new Object[]{2, row(11, "第02集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_VERIFIED)},
                new Object[]{2, row(12, "EP02.mp4", 800 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)},
                new Object[]{3, row(12, "EP03.mp4", 800 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)},
                new Object[]{4, row(12, "EP04.mp4", 800 * MB, MediaSubscriptionEpisodeSource.STATE_FAILED)},
                new Object[]{5, row(13, "第05集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)}));

        MovieList result = service.contentDetail(1, 7, null, null);

        Mockito.verifyNoInteractions(tvBoxService);
        MovieDetail detail = result.getList().getFirst();
        assertEquals("msub:7", detail.getVod_id());
        String[] from = detail.getVod_play_from().split("\\$\\$\\$");
        String[] groups = detail.getVod_play_url().split("\\$\\$\\$");
        assertEquals(3, from.length);
        assertEquals("我的追剧", from[0]);
        assertEquals("夸克网盘", from[1]);
        assertEquals("百度网盘", from[2]);
        // 逻辑线路:资源行并集(1-3),FAILED 集 4 与退役资源集 5 不入;标题元数据缺失兜底"第N集",大小取该集最大文件
        assertEquals("01. 第1集(1.46 GB)$msubep-7-1#02. 第2集(1.46 GB)$msubep-7-2#03. 第3集(800 MB)$msubep-7-3", groups[0]);
        // 盘线路:条目为 `文件名(大小)$1@pid` 物理地址,同盘只装该盘的行
        assertEquals("第01集.mkv(1.46 GB)$1@101#第02集.mkv(1.46 GB)$1@102", groups[1]);
        assertEquals("EP02.mp4(800 MB)$1@103#EP03.mp4(800 MB)$1@104", groups[2]);
        assertFalse(detail.getVod_play_url().contains("msubep-7-4"));
        assertFalse(detail.getVod_play_url().contains("第05集"));
        assertEquals(1, result.getTotal());
    }

    @Test
    void metadataTitlesUsedInLogicalLine() {
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("12345");
        MetadataDetails details = new MetadataDetails();
        details.setEpisodes(List.of(new EpisodeInfo(1, "启程", null), new EpisodeInfo(2, "相遇", null)));
        Mockito.when(metadataService.cachedDetails(Mockito.eq("tmdb"), Mockito.eq("12345"), Mockito.any()))
                .thenReturn(details);
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of(
                resource(11, 5, "/追剧/7-测试剧", 100, MediaSubscriptionResource.STATE_MOUNTED)));
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(rows(
                new Object[]{1, row(11, "第01集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)},
                new Object[]{2, row(11, "第02集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)}));

        MovieList result = service.contentDetail(1, 7, null, null);

        String logical = result.getList().getFirst().getVod_play_url().split("\\$\\$\\$")[0];
        assertEquals("01. 启程(1.46 GB)$msubep-7-1#02. 相遇(1.46 GB)$msubep-7-2", logical);
    }

    @Test
    void transferTargetsMergedIntoLines() {
        subscription.setMode(MediaSubscription.MODE_TRANSFER);
        subscription.setAccountIds("[1]");
        String transferPath = "/pan/1/追剧/测试剧";
        Mockito.when(transferService.transferredTargets(1, 7)).thenReturn(List.of(
                new MediaSubscriptionTransferService.TransferredTarget("pan:1", transferPath, "quark")));
        TreeMap<Integer, MediaSubscriptionCheckService.EpisodeFile> files = new TreeMap<>();
        files.put(2, new MediaSubscriptionCheckService.EpisodeFile(2, transferPath, "E02.mp4", 900 * MB, 0));
        Mockito.when(checkService.episodeFilesAt(transferPath, subscription)).thenReturn(files);
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of(
                resource(11, 10, "/追剧/7-测试剧", 100, MediaSubscriptionResource.STATE_MOUNTED)));
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(rows(
                new Object[]{1, row(11, "第01集.mp4", 800 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)},
                new Object[]{3, row(11, "第03集.mp4", 800 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)}));

        MovieList result = service.contentDetail(1, 7, null, null);

        String[] from = result.getList().getFirst().getVod_play_from().split("\\$\\$\\$");
        String[] groups = result.getList().getFirst().getVod_play_url().split("\\$\\$\\$");
        // 线路序:主源盘(2 集)按集数降序居前,转存盘(1 集)在后
        assertEquals("我的追剧", from[0]);
        assertEquals("百度网盘", from[1]);
        assertEquals("夸克网盘", from[2]);
        // 逻辑线路 = 资源行 ∪ 转存副本(1-3)
        assertEquals("01. 第1集(800 MB)$msubep-7-1#02. 第2集(900 MB)$msubep-7-2#03. 第3集(800 MB)$msubep-7-3", groups[0]);
        assertEquals("第01集.mp4(800 MB)$1@102#第03集.mp4(800 MB)$1@103", groups[1]);
        assertEquals("E02.mp4(900 MB)$1@101", groups[2]);
    }

    @Test
    void sameDriveDuplicateEpisodeKeepsFirstResource() {
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of(
                resource(12, 5, "/追剧/.sources/7-测试剧-补1", 80, MediaSubscriptionResource.STATE_MOUNTED),
                resource(11, 5, "/追剧/7-测试剧", 100, MediaSubscriptionResource.STATE_MOUNTED)));
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(rows(
                new Object[]{1, row(11, "第01集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)},
                new Object[]{2, row(11, "第02集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)},
                new Object[]{2, row(12, "EP02.mp4", 800 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)}));

        MovieList result = service.contentDetail(1, 7, null, null);

        String[] groups = result.getList().getFirst().getVod_play_url().split("\\$\\$\\$");
        assertEquals("我的追剧$$$夸克网盘", result.getList().getFirst().getVod_play_from());
        // 同盘同集先到先得,主源(装配序在前)胜出,补缺副本不重复出条目
        assertEquals("第01集.mkv(1.46 GB)$1@101#第02集.mkv(1.46 GB)$1@102", groups[1]);
    }

    @Test
    void emptyInventoryFallsBackToLegacyDetail() {
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of());
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(rows(
                new Object[]{1, row(11, "第01集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_FAILED)}));
        MovieList legacy = new MovieList();
        legacy.getList().add(new MovieDetail());
        Mockito.when(tvBoxService.getDetail(Mockito.any(), Mockito.anyString(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean())).thenReturn(legacy);

        MovieList result = service.contentDetail(1, 7, null, null);

        Mockito.verify(tvBoxService).getDetail(Mockito.any(), Mockito.anyString(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean());
        assertEquals(1, result.getList().size());
    }

    @Test
    void fastPathFailureFallsBackToLegacyDetail() {
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7))
                .thenThrow(new RuntimeException("db down"));
        MovieList legacy = new MovieList();
        legacy.getList().add(new MovieDetail());
        Mockito.when(tvBoxService.getDetail(Mockito.any(), Mockito.anyString(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean())).thenReturn(legacy);

        MovieList result = service.contentDetail(1, 7, null, null);

        Mockito.verify(tvBoxService).getDetail(Mockito.any(), Mockito.anyString(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean());
        assertEquals(1, result.getList().size());
    }

    @Test
    void nonBlankAcSkipsFastPath() {
        MovieList legacy = new MovieList();
        legacy.getList().add(new MovieDetail());
        Mockito.when(tvBoxService.getDetail(Mockito.any(), Mockito.anyString(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean())).thenReturn(legacy);

        service.contentDetail(1, 7, "detail", null);

        Mockito.verify(tvBoxService).getDetail(Mockito.any(), Mockito.anyString(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean());
        Mockito.verify(episodeSourceRepository, Mockito.never()).findNumberAndSource(Mockito.anyInt());
    }

    @Test
    void noResourceShowsPlaceholder() {
        subscription.setMountPath(null);
        subscription.setShareId(null);

        MovieList result = service.contentDetail(1, 7, null, null);

        assertEquals("尚未找到可用资源", result.getList().getFirst().getVod_remarks());
        Mockito.verifyNoInteractions(tvBoxService);
    }

    @Test
    void logicalTitleFormatsNumberTitleAndSize() {
        assertEquals("01. 噗噗先生(1.46 GB)", MediaSubscriptionService.logicalEpisodeTitle(1, "噗噗先生", 1500 * MB));
        assertEquals("105. 第105集", MediaSubscriptionService.logicalEpisodeTitle(105, null, 0));
        assertEquals("03. 第3集 下集", MediaSubscriptionService.logicalEpisodeTitle(3, "第3集$下集#", 0));
    }

    @Test
    void transferListFailureStillProducesLines() {
        subscription.setMode(MediaSubscription.MODE_TRANSFER);
        subscription.setAccountIds("[1]");
        Mockito.when(transferService.transferredTargets(1, 7))
                .thenThrow(new RuntimeException("account offline"));
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of(
                resource(11, 5, "/追剧/7-测试剧", 100, MediaSubscriptionResource.STATE_MOUNTED)));
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(rows(
                new Object[]{1, row(11, "第01集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)}));

        MovieList result = service.contentDetail(1, 7, null, null);

        assertTrue(result.getList().getFirst().getVod_play_url().contains("msubep-7-1"));
    }
}
