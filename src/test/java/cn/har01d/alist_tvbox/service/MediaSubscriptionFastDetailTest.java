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
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private final cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository episodeRepository =
            Mockito.mock(cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository.class);
    private final SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final TvBoxService tvBoxService = Mockito.mock(TvBoxService.class);
    private final MediaSubscriptionTransferService transferService = Mockito.mock(MediaSubscriptionTransferService.class);
    private final MetadataService metadataService = Mockito.mock(MetadataService.class);
    private final ProxyService proxyService = Mockito.mock(ProxyService.class);
    private final SiteRepository siteRepository = Mockito.mock(SiteRepository.class);
    private final cn.har01d.alist_tvbox.service.sitesearch.EpisodeFallbackService episodeFallbackService =
            Mockito.mock(cn.har01d.alist_tvbox.service.sitesearch.EpisodeFallbackService.class);
    private final AtomicInteger pid = new AtomicInteger(100);

    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, episodeRepository, episodeSourceRepository,
            null, null, null, tvBoxService, null, metadataService, checkService, transferService, settingRepository,
            new AppProperties(), new ObjectMapper(), proxyService, siteRepository, episodeFallbackService);

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
        Mockito.when(proxyService.generateProxyUrl(Mockito.any(Site.class), Mockito.anyString(), Mockito.any(java.time.Duration.class), Mockito.anyInt()))
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
        assertEquals(4, from.length);
        assertEquals("我的追剧", from[0]);
        assertEquals("夸克网盘", from[1]);
        assertEquals("百度网盘", from[2]);
        assertEquals("操作", from[3]);
        // 逻辑线路:资源行并集(1-3),FAILED 集 4 与退役资源集 5 不入;标题元数据缺失兜底"第N集",大小取该集最大文件
        assertEquals("01. 第1集(1.46 GB)$msubep-7-1#02. 第2集(1.46 GB)$msubep-7-2#03. 第3集(800 MB)$msubep-7-3", groups[0]);
        // 盘线路:条目为 `文件名(大小)$1@pid` 物理地址,同盘只装该盘的行
        assertEquals("第01集.mkv(1.46 GB)$1@101#第02集.mkv(1.46 GB)$1@102", groups[1]);
        assertEquals("EP02.mp4(800 MB)$1@103#EP03.mp4(800 MB)$1@104", groups[2]);
        // 操作线路:首条纯占位(防内核自动触发),「检查更新」次之,「立即巡检」(异步完整巡检)与
        // 「取消追剧」(播放器端弹窗确认后删除)居后
        assertEquals("订阅信息$msubstat-7#检查更新$msubcheck-7#立即巡检$msubinspect-7#取消追剧$msubunsub-7", groups[3]);
        assertFalse(detail.getVod_play_url().contains("msubep-7-4"));
        assertFalse(detail.getVod_play_url().contains("第05集"));
        assertEquals(1, result.getTotal());
    }

    @Test
    void collectionFallbackOverlayJoinsLogicalLine() {
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of(
                resource(11, 5, "/追剧/7-测试剧", 100, MediaSubscriptionResource.STATE_MOUNTED)));
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(rows(
                new Object[]{1, row(11, "第01集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)},
                new Object[]{2, row(11, "第02集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_VERIFIED)}));
        // activeRows 已按 ACTIVE+未过期过滤:第 2 集与真源重叠(真源优先)、第 3 集是采集垫底的洞
        Mockito.when(episodeFallbackService.activeRows(7)).thenReturn(List.of(fallbackRow(2), fallbackRow(3)));

        MovieList result = service.contentDetail(1, 7, null, null);

        MovieDetail detail = result.getList().getFirst();
        String[] groups = detail.getVod_play_url().split("\\$\\$\\$");
        // 逻辑线路:真源条目带大小不被覆盖层顶替;缺集 3 由覆盖层补入(无大小),播放走 msubep 兜底快路径
        assertEquals("01. 第1集(1.46 GB)$msubep-7-1#02. 第2集(1.46 GB)$msubep-7-2#03. 第3集$msubep-7-3", groups[0]);
        // 盘线路只装真源物理文件,覆盖层直链不进盘线路
        assertEquals("第01集.mkv(1.46 GB)$1@101#第02集.mkv(1.46 GB)$1@102", groups[1]);
    }

    @Test
    void missingEpisodesAppearAsPlaceholdersInLogicalLine() {
        // 官方已播 4 集,本地 LIVE 只有 1、2:第 3 集被覆盖层垫底、第 4 集是纯缺集
        subscription.setOfficialEpisodes(4);
        Mockito.when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.eq(7), Mockito.anyCollection()))
                .thenReturn(List.of(1, 2));
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of(
                resource(11, 5, "/追剧/7-测试剧", 100, MediaSubscriptionResource.STATE_MOUNTED)));
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(rows(
                new Object[]{1, row(11, "第01集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)},
                new Object[]{2, row(11, "第02集.mkv", 1500 * MB, MediaSubscriptionEpisodeSource.STATE_LISTED)}));
        Mockito.when(episodeFallbackService.activeRows(7)).thenReturn(List.of(fallbackRow(3)));

        MovieList result = service.contentDetail(1, 7, null, null);

        String logical = result.getList().getFirst().getVod_play_url().split("\\$\\$\\$")[0];
        // 真源条目原样;覆盖层垫底的第 3 集无标记;纯缺集第 4 集以(缺源)占位 —— 全部可点,不再整集隐身
        assertEquals("01. 第1集(1.46 GB)$msubep-7-1#02. 第2集(1.46 GB)$msubep-7-2"
                + "#03. 第3集$msubep-7-3#04. 第4集(缺源)$msubep-7-4", logical);
    }

    private static cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback fallbackRow(int episode) {
        cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback row =
                new cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback();
        row.setSubscriptionId(7);
        row.setEpisode(episode);
        row.setSiteId("feifan");
        row.setResourceId("12345");
        row.setLine("非凡线路");
        row.setUrl("http://collect.example/" + episode + ".m3u8");
        row.setState(cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeFallback.STATE_ACTIVE);
        row.setValidatedAt(System.currentTimeMillis());
        row.setExpiresAt(System.currentTimeMillis() + 3600_000L);
        return row;
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
        assertEquals("我的追剧$$$夸克网盘$$$操作", result.getList().getFirst().getVod_play_from());
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
    @SuppressWarnings("unchecked")
    void detailUpgradesLegacyBackdropAndMergesCarouselCandidates() {
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("12345");
        MetadataDetails details = new MetadataDetails();
        // 存量形态:主图 w780;轮播候选里主图重复出现 + 一张已是 original(上一版升级产物,同样收敛到 w1280)
        details.setBackdrop("https://media.themoviedb.org/t/p/w780/primary.jpg");
        details.setBackdrops(List.of(
                "https://media.themoviedb.org/t/p/w780/primary.jpg",
                "https://media.themoviedb.org/t/p/original/hero.jpg"));
        Mockito.when(metadataService.cachedDetails(Mockito.eq("tmdb"), Mockito.eq("12345"), Mockito.any()))
                .thenReturn(details);
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of());
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(List.of());
        Mockito.when(episodeRepository.findBySubscriptionIdOrderByNumber(7)).thenReturn(List.of());

        Map<String, Object> media = (Map<String, Object>) service.detail(1, 7).get("media");

        assertEquals(proxied("https://media.themoviedb.org/t/p/w1280/primary.jpg"), media.get("backdrop"),
                "存量 w780 主图免刷新收敛 w1280");
        assertEquals(List.of(proxied("https://media.themoviedb.org/t/p/w1280/primary.jpg"),
                proxied("https://media.themoviedb.org/t/p/w1280/hero.jpg")), media.get("backdrops"),
                "候选去重 + 收敛 w1280 + 走代理");
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailLegacySingleBackdropStillYieldsCarouselList() {
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("12345");
        MetadataDetails details = new MetadataDetails();
        details.setBackdrop("https://media.themoviedb.org/t/p/w780/primary.jpg"); // 老订阅快照:只有主图
        Mockito.when(metadataService.cachedDetails(Mockito.eq("tmdb"), Mockito.eq("12345"), Mockito.any()))
                .thenReturn(details);
        Mockito.when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(7)).thenReturn(List.of());
        Mockito.when(episodeSourceRepository.findNumberAndSource(7)).thenReturn(List.of());
        Mockito.when(episodeRepository.findBySubscriptionIdOrderByNumber(7)).thenReturn(List.of());

        Map<String, Object> media = (Map<String, Object>) service.detail(1, 7).get("media");

        assertEquals(List.of(proxied("https://media.themoviedb.org/t/p/w1280/primary.jpg")),
                media.get("backdrops"), "无候选时主图兜底成单元素列表,前端轮播逻辑零分支");
    }

    @Test
    void upgradeBackdropUrlOnlyRewritesTmdbSizeSegment() {
        assertEquals("https://media.themoviedb.org/t/p/w1280/a.jpg",
                MediaSubscriptionService.upgradeBackdropUrl("https://media.themoviedb.org/t/p/w780/a.jpg"));
        assertEquals("https://media.themoviedb.org/t/p/w1280/a.jpg",
                MediaSubscriptionService.upgradeBackdropUrl("https://media.themoviedb.org/t/p/original/a.jpg"),
                "上一版已落库的 original 同样收敛 w1280");
        assertEquals("https://example.com/w780/a.jpg",
                MediaSubscriptionService.upgradeBackdropUrl("https://example.com/w780/a.jpg"), "非 TMDB 图床不动");
        assertNull(MediaSubscriptionService.upgradeBackdropUrl(null));
    }

    private static String proxied(String url) {
        return "/images?url=" + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8);
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
