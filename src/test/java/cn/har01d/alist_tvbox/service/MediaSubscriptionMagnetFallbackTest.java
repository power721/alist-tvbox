package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.entity.DeadLinkRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.model.MagnetSubmitResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 磁力兜底:门禁矩阵(转存模式/轮次/冷却/未配置)、离线产物收割(目录/单文件形态)、
 * 磁力提交三态(COMPLETED 当场入账/SUBMITTED 事件+冷却/全灭退避)、磁力标题集号解析。
 */
class MediaSubscriptionMagnetFallbackTest {

    private final AppProperties appProperties = new AppProperties();
    private final MediaSubscriptionResourceRepository resourceRepository = mock(MediaSubscriptionResourceRepository.class);
    private final MediaSubscriptionEventRepository eventRepository = mock(MediaSubscriptionEventRepository.class);
    private final MediaSubscriptionEpisodeRepository episodeRepository = mock(MediaSubscriptionEpisodeRepository.class);
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository = mock(MediaSubscriptionEpisodeSourceRepository.class);
    private final DeadLinkRepository deadLinkRepository = mock(DeadLinkRepository.class);
    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final SettingRepository settingRepository = mock(SettingRepository.class);
    private final AListService aListService = mock(AListService.class);
    private final TelegramService telegramService = mock(TelegramService.class);
    private final OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);

    private MediaSubscriptionCheckService service;

    @BeforeEach
    void setUp() {
        service = new MediaSubscriptionCheckService(
                null, resourceRepository, eventRepository, episodeRepository, episodeSourceRepository,
                deadLinkRepository, null, siteRepository, null, null, settingRepository,
                null, aListService, telegramService, null, null, null, null, null,
                null, null, null, appProperties, new ObjectMapper(), null, null);
        service.setOfflineDownloadService(offlineDownloadService);
        when(siteRepository.findById(1)).thenReturn(Optional.of(new Site()));
        when(settingRepository.findById(anyString())).thenReturn(Optional.empty());
        when(episodeRepository.findBySubscriptionIdOrderByNumber(anyInt())).thenReturn(List.of());
        when(episodeRepository.findBySubscriptionIdAndSeasonAndNumber(anyInt(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(episodeRepository.save(any())).thenAnswer(inv -> {
            cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisode episode = inv.getArgument(0);
            if (episode.getId() == null) {
                episode.setId(501); // mock 不生成 id,后续 findByEpisodeIdAndResourceId(null) 拆箱 NPE
            }
            return episode;
        });
        when(episodeSourceRepository.findByResourceId(anyInt())).thenReturn(List.of());
        when(episodeSourceRepository.findByEpisodeIdAndResourceId(anyInt(), anyInt())).thenReturn(Optional.empty());
        when(episodeSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(anyInt())).thenReturn(List.of());
        when(resourceRepository.findBySubscriptionIdAndLink(anyInt(), anyString())).thenReturn(Optional.empty());
        when(resourceRepository.save(any())).thenAnswer(inv -> {
            MediaSubscriptionResource row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(31);
            }
            return row;
        });
        when(aListService.listFiles(any(), anyString(), anyInt(), anyInt(), anyBoolean())).thenReturn(new FsResponse());
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of());
        when(offlineDownloadService.isConfigured()).thenReturn(true);
        when(offlineDownloadService.offlineRootPath()).thenReturn("/drive/alist-tvbox-offline");
        when(offlineDownloadService.pendingMagnetCount()).thenReturn(0L);
        when(offlineDownloadService.configuredDriveType()).thenReturn(8);
        appProperties.getSubscription().setPrimeCheckTimes(List.of());
        appProperties.getSubscription().setNightCheckTimes(List.of());
        appProperties.setFormats(Set.of("mkv", "mp4")); // 生产由 yaml 绑定,裸实例需手动补
    }

    private MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(9);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setKeyword("测试剧");
        subscription.setSeason(1);
        subscription.setMode(MediaSubscription.MODE_TRANSFER);
        subscription.setMagnetOffline(true);
        return subscription;
    }

    // ---------- 门禁矩阵 ----------

    @Test
    void gatesBlockNonTransferMode() {
        MediaSubscription subscription = subscription();
        subscription.setMode(MediaSubscription.MODE_FOLLOW);
        service.magnetFallback(subscription, Set.of(3), 5);
        verify(offlineDownloadService, never()).offlineRootPath();
    }

    @Test
    void gatesBlockEarlyRound() {
        service.magnetFallback(subscription(), Set.of(3), 1);
        verify(offlineDownloadService, never()).offlineRootPath();
    }

    @Test
    void gatesBlockDuringCustomKeywordRounds() {
        // 自定义词轮插进补搜轮转后,磁力阈值按词数推后(K=2 → 有效 minRound 4):
        // 网盘侧多词未穷尽前,磁力不提前入场烧离线配额
        MediaSubscription subscription = subscription();
        subscription.setCustomKeywords("英文名\n别名");
        service.magnetFallback(subscription, Set.of(3), 3);
        verify(offlineDownloadService, never()).offlineRootPath();
    }

    @Test
    void gatesBlockDisabledFlag() {
        MediaSubscription subscription = subscription();
        subscription.setMagnetOffline(false);
        service.magnetFallback(subscription, Set.of(3), 5);
        verify(offlineDownloadService, never()).offlineRootPath();
    }

    @Test
    void gatesBlockWhenOfflineNotConfigured() {
        when(offlineDownloadService.isConfigured()).thenReturn(false);
        service.magnetFallback(subscription(), Set.of(3), 5);
        verify(offlineDownloadService, never()).offlineRootPath();
    }

    @Test
    void toleratesMissingOfflineService() {
        service.setOfflineDownloadService(null);
        service.magnetFallback(subscription(), Set.of(3), 5); // 不炸即过(裸实例测试形态)
    }

    // ---------- 收割 ----------

    @Test
    void harvestsDirectoryProductIntoInventory() {
        String root = "/drive/alist-tvbox-offline";
        FsInfo dir = new FsInfo();
        dir.setName("测试剧 - 第03集");
        dir.setType(1);
        FsResponse rootListing = new FsResponse();
        rootListing.setFiles(List.of(dir));
        // 分路径桩:根目录 → 产物目录 → 文件
        when(aListService.listFiles(any(), org.mockito.ArgumentMatchers.eq(root), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(rootListing);
        FsInfo file = new FsInfo();
        file.setName("Show.S01E03.1080p.mkv");
        file.setType(0);
        file.setSize(800L * 1024 * 1024);
        FsResponse productListing = new FsResponse();
        productListing.setFiles(List.of(file));
        when(aListService.listFiles(any(), org.mockito.ArgumentMatchers.eq(root + "/" + dir.getName()), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(productListing);
        stubPendingEpisodeRow(3);

        service.magnetFallback(subscription(), Set.of(3), 5);

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository, times(2)).save(captor.capture());
        MediaSubscriptionResource row = captor.getAllValues().get(0);
        assertEquals("offline:测试剧 - 第03集", row.getLink());
        assertEquals(MediaSubscriptionResource.SOURCE_MAGNET, row.getSource());
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, row.getState());
        assertEquals(root + "/测试剧 - 第03集", row.getMountPath());
        assertNull(row.getShareId());
        assertEquals(8, row.getType());
        verify(episodeSourceRepository).save(any(MediaSubscriptionEpisodeSource.class));
    }

    @Test
    void harvestsSingleFileProduct() {
        String root = "/drive/alist-tvbox-offline";
        FsInfo file = new FsInfo();
        file.setName("Show.S01E05.1080p.mkv");
        file.setType(0);
        file.setSize(700L * 1024 * 1024);
        FsResponse rootListing = new FsResponse();
        rootListing.setFiles(List.of(file));
        when(aListService.listFiles(any(), org.mockito.ArgumentMatchers.eq(root), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(rootListing);
        stubPendingEpisodeRow(5); // 归属闸门:单文件产物覆盖第 5 集,订阅 PENDING 也在第 5 集

        Set<Integer> remaining = invokeHarvest(subscription(), Set.of(5));
        assertTrue(remaining.isEmpty());

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository, times(2)).save(captor.capture()); // syncInventory 前后各一次
        MediaSubscriptionResource row = captor.getAllValues().get(0);
        assertEquals(root, row.getMountPath()); // 单文件产物挂载点=产物根,rel_path=文件名
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, row.getState());
    }

    @Test
    void harvestSettlesPendingTaskForCoveredEpisodes() {
        // 超时 PENDING 行在收割入账时按集结算为 COMPLETED(补产物名/路径):
        // pending 闸门不被已完成任务永久占满,urlHash 查重语义恢复
        String root = "/drive/alist-tvbox-offline";
        FsInfo dir = new FsInfo();
        dir.setName("测试剧 - 第03集");
        dir.setType(1);
        FsResponse rootListing = new FsResponse();
        rootListing.setFiles(List.of(dir));
        when(aListService.listFiles(any(), org.mockito.ArgumentMatchers.eq(root), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(rootListing);
        FsInfo file = new FsInfo();
        file.setName("Show.S01E03.1080p.mkv");
        file.setType(0);
        file.setSize(800L * 1024 * 1024);
        FsResponse productListing = new FsResponse();
        productListing.setFiles(List.of(file));
        when(aListService.listFiles(any(), org.mockito.ArgumentMatchers.eq(root + "/" + dir.getName()), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(productListing);
        stubPendingEpisodeRow(3);

        invokeHarvest(subscription(), Set.of(3));

        verify(offlineDownloadService).settlePendingTask(9, 3, "测试剧 - 第03集",
                root + "/测试剧 - 第03集");
    }

    @Test
    void retiresMagnetRowWhenProductDisappears() {
        String root = "/drive/alist-tvbox-offline";
        MediaSubscriptionResource row = new MediaSubscriptionResource();
        row.setId(31);
        row.setSubscriptionId(9);
        row.setLink("offline:测试剧 - 第03集");
        row.setSource(MediaSubscriptionResource.SOURCE_MAGNET);
        row.setState(MediaSubscriptionResource.STATE_MOUNTED);
        row.setMountPath(root + "/测试剧 - 第03集");
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(List.of(row));
        when(aListService.listFiles(any(), org.mockito.ArgumentMatchers.eq(root), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new FsResponse()); // 目录已空

        invokeHarvest(subscription(), Set.of(3));

        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository).save(captor.capture());
        assertEquals(MediaSubscriptionResource.STATE_RETIRED, captor.getValue().getState());
        assertNull(captor.getValue().getMountPath());
    }

    @Test
    void skipsAlreadyRegisteredProduct() {
        String root = "/drive/alist-tvbox-offline";
        FsInfo dir = new FsInfo();
        dir.setName("测试剧 - 第03集");
        dir.setType(1);
        FsResponse rootListing = new FsResponse();
        rootListing.setFiles(List.of(dir));
        MediaSubscriptionResource row = new MediaSubscriptionResource();
        row.setLink("offline:测试剧 - 第03集");
        row.setSource(MediaSubscriptionResource.SOURCE_MAGNET);
        row.setState(MediaSubscriptionResource.STATE_MOUNTED);
        row.setMountPath(root + "/测试剧 - 第03集");
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(List.of(row));
        when(aListService.listFiles(any(), org.mockito.ArgumentMatchers.eq(root), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(rootListing);

        Set<Integer> remaining = invokeHarvest(subscription(), Set.of(3));

        assertTrue(remaining.contains(3), "已入账产物不再重复建行,缺口维持给提交阶段");
        verify(resourceRepository, never()).save(any());
    }

    /** 自动路径(episode 非空)PENDING 行桩:归属闸门的对账锚点。 */
    private void stubPendingEpisodeRow(int episode) {
        cn.har01d.alist_tvbox.entity.OfflineDownloadTask task =
                new cn.har01d.alist_tvbox.entity.OfflineDownloadTask();
        task.setAccountId(12);
        task.setStatus("PENDING");
        task.setSubscriptionId(9);
        task.setEpisode(episode);
        when(offlineDownloadService.pendingTasks(9)).thenReturn(List.of(task));
    }

    /** 直调 harvest(magnetFallback 的收割半程):绕过提交阶段对磁力搜索/提交的依赖。 */
    private Set<Integer> invokeHarvest(MediaSubscription subscription, Set<Integer> missing) {        try {
            var method = MediaSubscriptionCheckService.class.getDeclaredMethod("harvestOfflineProducts",
                    MediaSubscription.class, Set.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<Integer> result = (Set<Integer>) method.invoke(service, subscription, missing);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 提交 ----------

    @Test
    void submitsMatchedMagnetAndRecordsEvent() {
        Message magnet = new Message();
        magnet.setContent("测试剧 - 03 4K");
        magnet.setLink("magnet:?xt=urn:btih:abc&dn=" + URLEncoder.encode("测试剧 - 03 4K", StandardCharsets.UTF_8));
        magnet.setSize(900L * 1024 * 1024);
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(magnet));
        when(offlineDownloadService.submitMagnet(anyString(), anyInt(), anyInt(), anyInt())).thenReturn(MagnetSubmitResult.submitted("已提交,等待网盘下载"));

        service.magnetFallback(subscription(), Set.of(3), 5);

        verify(offlineDownloadService).submitMagnet(org.mockito.ArgumentMatchers.startsWith("magnet:"), anyInt(), anyInt(), anyInt());
        ArgumentCaptor<MediaSubscriptionEvent> captor = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        verify(eventRepository).save(captor.capture());
        assertEquals(MediaSubscriptionEvent.TYPE_MAGNET_SUBMITTED, captor.getValue().getType());

        // SUBMITTED 冷却:下轮不重复提交(网盘侧任务已在,重复提交烧配额)
        service.magnetFallback(subscription(), Set.of(3), 6);
        verify(offlineDownloadService, times(1)).submitMagnet(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void cooldownsWhenNoMagnetMatches() {
        Message magnet = new Message();
        magnet.setContent("不相干的剧 - 08");
        magnet.setLink("magnet:?xt=urn:btih:def&dn=" + URLEncoder.encode("不相干的剧 - 08", StandardCharsets.UTF_8));
        magnet.setSize(900L * 1024 * 1024);
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(magnet));

        service.magnetFallback(subscription(), Set.of(3), 5);
        verify(offlineDownloadService, never()).submitMagnet(anyString(), anyInt(), anyInt(), anyInt()); // 剧名不匹配:不提交

        service.magnetFallback(subscription(), Set.of(3), 6);
        verify(telegramService, times(1)).searchMagnets(anyString(), anyInt()); // 冷却期:连搜索都不发起
    }

    @Test
    void skipsSubmitWhenPendingLimitReached() {
        when(offlineDownloadService.pendingMagnetCount()).thenReturn(2L);
        service.magnetFallback(subscription(), Set.of(3), 5);
        verify(telegramService, never()).searchMagnets(anyString(), anyInt());
    }

    @Test
    void episodeMismatchNotSubmitted() {
        Message magnet = new Message();
        magnet.setContent("测试剧 - 07");
        magnet.setLink("magnet:?xt=urn:btih:xyz&dn=" + URLEncoder.encode("测试剧 - 07", StandardCharsets.UTF_8));
        magnet.setSize(900L * 1024 * 1024);
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(magnet));

        service.magnetFallback(subscription(), Set.of(3), 5);
        verify(offlineDownloadService, never()).submitMagnet(anyString(), anyInt(), anyInt(), anyInt()); // 缺 3 来 7:不提交
    }

    // ---------- 磁力标题集号解析 ----------

    @Test
    void parsesMagnetEpisodeFormats() {
        MediaSubscription subscription = subscription();
        assertEquals(12, MediaSubscriptionCheckService.parseMagnetEpisode("[Group] 测试剧 第12话 [1080p]", subscription));
        assertEquals(12, MediaSubscriptionCheckService.parseMagnetEpisode("测试剧.E12.2160p", subscription));
        assertEquals(12, MediaSubscriptionCheckService.parseMagnetEpisode("测试剧 - 12 [BDRip]", subscription));
        assertEquals(12, MediaSubscriptionCheckService.parseMagnetEpisode("测试剧【12】END", subscription));
        assertEquals(3, MediaSubscriptionCheckService.parseMagnetEpisode("测试剧 EP03 v2", subscription));
    }

    @Test
    void magnetEpisodeParserRejectsNoiseNumbers() {
        MediaSubscription subscription = subscription();
        assertNull(MediaSubscriptionCheckService.parseMagnetEpisode("测试剧 1080p 2024", subscription), "分辨率不当集号");
        assertNull(MediaSubscriptionCheckService.parseMagnetEpisode("测试剧 [2024]", subscription), "年份不当集号");
        assertNull(MediaSubscriptionCheckService.parseMagnetEpisode("测试剧", subscription), "无集号返回 null");
        subscription.setOfficialTotal(12);
        assertNull(MediaSubscriptionCheckService.parseMagnetEpisode("测试剧 - 99", subscription), "显著超出官方总集数判无效");
        assertEquals(13, MediaSubscriptionCheckService.parseMagnetEpisode("测试剧 - 13", subscription), "总集数+50 容差内有效");
    }

    @Test
    void magnetResourceIsExcludedFromShareSemantics() {
        MediaSubscriptionResource magnet = new MediaSubscriptionResource();
        magnet.setSource(MediaSubscriptionResource.SOURCE_MAGNET);
        assertTrue(MediaSubscriptionCheckService.isMagnetResource(magnet));
        assertFalse(MediaSubscriptionCheckService.isMagnetResource(new MediaSubscriptionResource()));
    }

    // ---------- 三档离线配额 ----------

    private Message matchedMagnet(int episode) {
        Message magnet = new Message();
        String name = "测试剧 - 0" + episode + " 4K";
        magnet.setContent(name);
        magnet.setLink("magnet:?xt=urn:btih:hash" + episode + "&dn=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        magnet.setSize(0L); // tg-search 磁力条目 size 恒 0(线上形态)
        return magnet;
    }

    @Test
    void totalQuotaBlocksAllSubmissions() {
        when(offlineDownloadService.totalMagnetCount()).thenReturn(200L);
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(matchedMagnet(3)));

        service.magnetFallback(subscription(), Set.of(3), 5);

        verify(telegramService, never()).searchMagnets(anyString(), anyInt());
    }

    @Test
    void subscriptionQuotaBlocksSubmissions() {
        when(offlineDownloadService.subscriptionMagnetCount(9)).thenReturn(30L);
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(matchedMagnet(3)));

        service.magnetFallback(subscription(), Set.of(3), 5);

        verify(offlineDownloadService, never()).submitMagnet(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void episodeQuotaMovesToNextGapEpisode() {
        when(offlineDownloadService.episodeMagnetCount(9, 3)).thenReturn(2L); // 单集配额(默认 2)已耗尽
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(matchedMagnet(5)));
        when(offlineDownloadService.submitMagnet(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(MagnetSubmitResult.submitted("已提交,等待网盘下载"));

        service.magnetFallback(subscription(), Set.of(3, 5), 5);

        verify(offlineDownloadService).submitMagnet(anyString(), org.mockito.ArgumentMatchers.eq(9), org.mockito.ArgumentMatchers.eq(5), anyInt());
    }

    @Test
    void zeroQuotaMeansUnlimited() {
        when(settingRepository.findById("msub_magnet_total_quota")).thenReturn(Optional.of(new cn.har01d.alist_tvbox.entity.Setting("msub_magnet_total_quota", "0")));
        when(offlineDownloadService.totalMagnetCount()).thenReturn(9999L);
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(matchedMagnet(3)));
        when(offlineDownloadService.submitMagnet(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(MagnetSubmitResult.submitted("已提交,等待网盘下载"));

        service.magnetFallback(subscription(), Set.of(3), 5);

        verify(offlineDownloadService).submitMagnet(anyString(), anyInt(), anyInt(), anyInt());
    }

    // ---------- 磁力解析预筛 ----------

    @Test
    void resolverFileListFiltersUndersizedTarget() {
        cn.har01d.alist_tvbox.service.magnet.MagnetResolver resolver = mock(cn.har01d.alist_tvbox.service.magnet.MagnetResolver.class);
        service.setMagnetResolver(resolver);
        // 文件列表里目标集(3)只有 5MB 样片(< 20MB 底线):整个磁力不可用
        when(resolver.resolve(anyString())).thenReturn(Optional.of(new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetInfo(
                "abc", "测试剧", 5L * 1024 * 1024,
                List.of(new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("Show.S01E03.1080p.mkv", 5L * 1024 * 1024)))));
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(matchedMagnet(3)));

        service.magnetFallback(subscription(), Set.of(3), 5);

        verify(offlineDownloadService, never()).submitMagnet(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void resolverFileListAcceptsQualifiedTarget() {
        cn.har01d.alist_tvbox.service.magnet.MagnetResolver resolver = mock(cn.har01d.alist_tvbox.service.magnet.MagnetResolver.class);
        service.setMagnetResolver(resolver);
        when(resolver.resolve(anyString())).thenReturn(Optional.of(new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetInfo(
                "abc", "测试剧", 800L * 1024 * 1024,
                List.of(new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("Show.S01E03.1080p.mkv", 800L * 1024 * 1024)))));
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(matchedMagnet(3)));
        when(offlineDownloadService.submitMagnet(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(MagnetSubmitResult.submitted("已提交,等待网盘下载"));

        service.magnetFallback(subscription(), Set.of(3), 5);

        verify(offlineDownloadService).submitMagnet(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void resolverFailureFallsBackToDnMatchWithZeroSize() {
        // 镜像全挂(resolve empty)→ dn 名口径:size=0 的磁力条目也能提交(修复体积门禁全灭)
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(matchedMagnet(3)));
        when(offlineDownloadService.submitMagnet(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(MagnetSubmitResult.submitted("已提交,等待网盘下载"));

        service.magnetFallback(subscription(), Set.of(3), 5);

        verify(offlineDownloadService).submitMagnet(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void helperDirectChecks() {
        MediaSubscription subscription = subscription();
        MediaSubscriptionCheckService.EpisodeSizePolicy policy = new MediaSubscriptionCheckService.EpisodeSizePolicy(20L * 1024 * 1024, 0, 0);
        cn.har01d.alist_tvbox.dto.MediaSubscriptionPoolFilter global = new cn.har01d.alist_tvbox.dto.MediaSubscriptionPoolFilter();
        assertFalse(MediaSubscriptionCheckService.magnetExcluded("测试剧 - 03 4K", null, global));
        assertTrue(MediaSubscriptionCheckService.magnetExcluded("测试剧 预告", null,
                new cn.har01d.alist_tvbox.dto.MediaSubscriptionPoolFilter() {{
                    setExcludeKeywords(java.util.List.of("预告"));
                }}));
        cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetInfo info =
                new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetInfo("abc", "测试剧", 800L * 1024 * 1024,
                        List.of(new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("Show.S01E03.1080p.mkv", 800L * 1024 * 1024)));
        assertTrue(service.magnetFilesAcceptable(subscription, info, 3, policy, null, global), "达标文件应可用");
        assertEquals(3, service.parseEpisode("Show.S01E03.1080p.mkv", 1));
    }

    @Test
    void adDomainWatermarkTorrentMatchesRealEpisodesOnly() {
        // 线上形态(醒来01-06 磁力,itorrents 实拉种子):六文件全部带 [最新电影www.dyg7.com] 水印,
        // 域名数字曾把全部文件解析成第 7 集 —— 缺 7 时 1-6 的合集被误匹配提交。
        MediaSubscription subscription = subscription();
        MediaSubscriptionCheckService.EpisodeSizePolicy policy = new MediaSubscriptionCheckService.EpisodeSizePolicy(20L * 1024 * 1024, 0, 0);
        cn.har01d.alist_tvbox.dto.MediaSubscriptionPoolFilter global = new cn.har01d.alist_tvbox.dto.MediaSubscriptionPoolFilter();
        cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetInfo info =
                new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetInfo("abc", "醒来01-06", 7_376_609_792L, List.of(
                        new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("01.2160p.HD国语中字无水印[最新电影www.dyg7.com].mkv", 1_139_312_006L),
                        new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("02.2160p.HD国语中字无水印[最新电影www.dyg7.com].mkv", 1_263_718_482L),
                        new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("03.2160p.HD国语中字无水印[最新电影www.dyg7.com].mkv", 1_288_622_395L),
                        new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("04.2160p.HD国语中字无水印[最新电影www.dyg7.com].mkv", 1_239_261_832L),
                        new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("05.2160p.HD国语中字无水印[最新电影www.dyg7.com].mkv", 1_160_238_511L),
                        new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("06.2160p.HD国语中字无水印[最新电影www.dyg7.com].mkv", 1_285_456_566L)));
        assertFalse(service.magnetFilesAcceptable(subscription, info, 7, policy, null, global), "第 7 集不在包里:不得误匹配");
        assertTrue(service.magnetFilesAcceptable(subscription, info, 5, policy, null, global), "包内第 5 集有达标文件:应可用");
    }

    // ---------- 优先消费搜索顺手的磁力候选 ----------

    @Test
    void magnetCandidateCollectionDedupesAndCaps() {
        Message first = matchedMagnet(3);
        Message duplicate = matchedMagnet(3); // 同 link
        service.collectMagnetCandidate(9, first);
        service.collectMagnetCandidate(9, duplicate);
        assertEquals(1, service.magnetCandidatesOf(9).size());
        for (int i = 0; i < 60; i++) {
            Message filler = matchedMagnet(3);
            filler.setLink("magnet:?xt=urn:btih:filler" + i);
            service.collectMagnetCandidate(9, filler);
        }
        assertEquals(50, service.magnetCandidatesOf(9).size(), "上限 50,超出截最旧");
    }

    @Test
    void poolMagnetsAreConsumedBeforeDedicatedSearch() {
        service.collectMagnetCandidate(9, matchedMagnet(3)); // 巡检搜索顺手收下的
        when(offlineDownloadService.submitMagnet(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(MagnetSubmitResult.submitted("已提交,等待网盘下载"));

        service.magnetFallback(subscription(), Set.of(3), 5);

        verify(offlineDownloadService).submitMagnet(anyString(), anyInt(), anyInt(), anyInt());
        verify(telegramService, never()).searchMagnets(anyString(), anyInt()); // 有现成磁力:不专项搜
    }

    @Test
    void ed2kCandidateIsCollectedAndSubmitted() {
        Message ed2k = new Message();
        ed2k.setContent("测试剧 - 03");
        ed2k.setLink("ed2k://|file|测试剧 - 03.mkv|834000000|31D6CFE0D16AE931B73C59D7E0C089C0|/");
        ed2k.setType("ed2k");
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(ed2k));
        when(offlineDownloadService.submitMagnet(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(MagnetSubmitResult.submitted("已提交,等待网盘下载"));

        service.magnetFallback(subscription(), Set.of(3), 5);

        verify(offlineDownloadService).submitMagnet(
                org.mockito.ArgumentMatchers.startsWith("ed2k://"), anyInt(), anyInt(), anyInt());
    }

    @Test
    void offlineLinkHelperAcceptsBothProtocols() {
        assertTrue(MediaSubscriptionCheckService.isOfflineLink("magnet:?xt=urn:btih:abc"));
        assertTrue(MediaSubscriptionCheckService.isOfflineLink("ed2k://|file|x|1|hash|/"));
        assertFalse(MediaSubscriptionCheckService.isOfflineLink("https://pan.quark.cn/s/abc"));
        assertFalse(MediaSubscriptionCheckService.isOfflineLink(null));
    }

    @Test
    void dedicatedSearchRunsOnlyWhenPoolMagnetsExhausted() {
        Message wrongEpisode = matchedMagnet(7); // 缓存里有但缺 3 来 7:不可用
        service.collectMagnetCandidate(9, wrongEpisode);
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(matchedMagnet(3)));
        when(offlineDownloadService.submitMagnet(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(MagnetSubmitResult.submitted("已提交,等待网盘下载"));

        service.magnetFallback(subscription(), Set.of(3), 5);

        verify(telegramService, times(1)).searchMagnets(anyString(), anyInt()); // 缓存无可用项才兜底搜
        verify(offlineDownloadService).submitMagnet(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void excludedKeywordRejectsMagnetTitle() {
        Message magnet = new Message();
        magnet.setContent("测试剧 - 03 预告合集");
        magnet.setLink("magnet:?xt=urn:btih:exc&dn=" + URLEncoder.encode("测试剧 - 03 预告合集", StandardCharsets.UTF_8));
        magnet.setSize(900L * 1024 * 1024);
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(magnet));
        MediaSubscription subscription = subscription();
        subscription.setFilterConfig("{\"excludeKeywords\":[\"预告\"]}");

        service.magnetFallback(subscription, Set.of(3), 5);

        verify(offlineDownloadService, never()).submitMagnet(anyString(), anyInt(), anyInt(), anyInt()); // 订阅排除词硬拒
    }
}
