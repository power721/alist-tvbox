package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.DeadLinkRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
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
import cn.har01d.alist_tvbox.model.MagnetSubmitResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手动磁力补缺:门禁(仅全局离线已配置,不限订阅 mode/磁力兜底开关)、三态分支
 * (COMPLETED 立即收割入账/SUBMITTED 事件/FAILED 透传)、收割结算接线(episode=null 的手动 PENDING 行)。
 */
class MediaSubscriptionManualMagnetTest {

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
    private final TelegramService telegramService = mock(TelegramService.class);
    private final cn.har01d.alist_tvbox.service.sitesearch.PanLianSearchService panLianSearchService =
            mock(cn.har01d.alist_tvbox.service.sitesearch.PanLianSearchService.class);
    private final cn.har01d.alist_tvbox.service.sitesearch.GuanYingSearchService guanYingSearchService =
            mock(cn.har01d.alist_tvbox.service.sitesearch.GuanYingSearchService.class);
    private final cn.har01d.alist_tvbox.service.sitesearch.PanjuSearchService panjuSearchService =
            mock(cn.har01d.alist_tvbox.service.sitesearch.PanjuSearchService.class);
    private final OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);

    private MediaSubscriptionCheckService service;

    private static final String ROOT = "/drive/alist-tvbox-offline";
    private static final String MAGNET = "magnet:?xt=urn:btih:manual01&dn=manual";

    @BeforeEach
    void setUp() {
        service = new MediaSubscriptionCheckService(
                subscriptionRepository, resourceRepository, eventRepository, episodeRepository, episodeSourceRepository,
                deadLinkRepository, null, siteRepository, null, null, settingRepository,
                null, aListService, telegramService,
                null, panLianSearchService, guanYingSearchService, null, panjuSearchService,
                null, null, null, appProperties, new ObjectMapper(), null, null);
        service.setOfflineDownloadService(offlineDownloadService);
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription()));
        when(siteRepository.findById(1)).thenReturn(Optional.of(new Site()));
        when(settingRepository.findById(anyString())).thenReturn(Optional.empty());
        when(episodeRepository.findBySubscriptionIdOrderByNumber(anyInt())).thenReturn(List.of());
        when(episodeRepository.findBySubscriptionIdAndSeasonAndNumber(anyInt(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(episodeRepository.save(any())).thenAnswer(inv -> {
            cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisode episode = inv.getArgument(0);
            if (episode.getId() == null) {
                episode.setId(601); // mock 不生成 id,后续 findByEpisodeIdAndResourceId(null) 拆箱 NPE
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
                row.setId(41);
            }
            return row;
        });
        when(aListService.listFiles(any(), anyString(), anyInt(), anyInt(), anyBoolean())).thenReturn(new FsResponse());
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of());
        when(guanYingSearchService.search(anyString())).thenReturn(List.of());
        when(panLianSearchService.search(anyString())).thenReturn(List.of());
        when(panjuSearchService.search(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of());
        when(offlineDownloadService.isConfigured()).thenReturn(true);
        when(offlineDownloadService.offlineRootPath()).thenReturn(ROOT);
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
        subscription.setMagnetOffline(false); // 手动路径不要求磁力兜底开关
        return subscription;
    }

    /** 根目录有产物目录(内含 E03 达标文件)的分路径桩。 */
    private void stubProductWithEpisode3() {
        FsInfo dir = new FsInfo();
        dir.setName("测试剧 - 第03集");
        dir.setType(1);
        FsResponse rootListing = new FsResponse();
        rootListing.setFiles(List.of(dir));
        when(aListService.listFiles(any(), eq(ROOT), anyInt(), anyInt(), anyBoolean())).thenReturn(rootListing);
        FsInfo file = new FsInfo();
        file.setName("Show.S01E03.1080p.mkv");
        file.setType(0);
        file.setSize(800L * 1024 * 1024);
        FsResponse productListing = new FsResponse();
        productListing.setFiles(List.of(file));
        when(aListService.listFiles(any(), eq(ROOT + "/" + dir.getName()), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(productListing);
    }

    // ---------- 三态 ----------

    @Test
    void completedHarvestsProductAndReturnsEpisodes() {
        stubProductWithEpisode3();
        when(offlineDownloadService.submitMagnetRetryFailed(anyString(), eq(9), any(), anyInt()))
                .thenReturn(MagnetSubmitResult.completed("测试剧 - 第03集"));

        Map<String, Object> result = service.submitManualMagnet(1, 9, MAGNET, null);

        assertEquals("completed", result.get("status"));
        assertEquals(List.of(3), result.get("episodes"));
        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        MediaSubscriptionResource row = captor.getAllValues().get(0);
        assertEquals("offline:测试剧 - 第03集", row.getLink());
        assertEquals(MediaSubscriptionResource.SOURCE_MAGNET, row.getSource());
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, row.getState());
    }

    @Test
    void completedButUnmatchedProductReportsNoEpisodes() {
        // 产物目录空(标题/体积不匹配的形态):下载完成但门禁拒入账,episodes 空 + 提示可反馈
        FsInfo dir = new FsInfo();
        dir.setName("不相干的剧 - 08");
        dir.setType(1);
        FsResponse rootListing = new FsResponse();
        rootListing.setFiles(List.of(dir));
        when(aListService.listFiles(any(), eq(ROOT), anyInt(), anyInt(), anyBoolean())).thenReturn(rootListing);
        when(offlineDownloadService.submitMagnetRetryFailed(anyString(), eq(9), any(), anyInt()))
                .thenReturn(MagnetSubmitResult.completed("不相干的剧 - 08"));

        Map<String, Object> result = service.submitManualMagnet(1, 9, MAGNET, 7);

        assertEquals("completed", result.get("status"));
        assertEquals(List.of(), result.get("episodes"));
        assertTrue(String.valueOf(result.get("message")).contains("未入账"));
    }

    @Test
    void submittedRecordsEventWithManualWording() {
        when(offlineDownloadService.submitMagnetRetryFailed(anyString(), eq(9), eq(3), anyInt()))
                .thenReturn(MagnetSubmitResult.submitted("已提交,等待网盘下载"));

        Map<String, Object> result = service.submitManualMagnet(1, 9, MAGNET, 3);

        assertEquals("submitted", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("巡检"));
        ArgumentCaptor<MediaSubscriptionEvent> captor = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        verify(eventRepository).save(captor.capture());
        assertEquals(MediaSubscriptionEvent.TYPE_MAGNET_SUBMITTED, captor.getValue().getType());
        assertTrue(captor.getValue().getDetail().contains("手动"), "手动提交的事件文案应可区分自动兜底");
    }

    @Test
    void failedPassesMessageThrough() {
        when(offlineDownloadService.submitMagnetRetryFailed(anyString(), eq(9), any(), anyInt()))
                .thenReturn(MagnetSubmitResult.failed("网盘拒绝:链接无效"));

        Map<String, Object> result = service.submitManualMagnet(1, 9, MAGNET, null);

        assertEquals("failed", result.get("status"));
        assertEquals("网盘拒绝:链接无效", result.get("message"));
    }

    // ---------- 门禁:仅全局离线已配置 ----------

    @Test
    void nonTransferModeIsAllowed() {
        // 用户定规:手动补缺不限订阅 mode(与自动兜底的 TRANSFER 门禁分开)
        MediaSubscription follow = subscription();
        follow.setMode(MediaSubscription.MODE_FOLLOW);
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(follow));
        when(offlineDownloadService.submitMagnetRetryFailed(anyString(), eq(9), any(), anyInt()))
                .thenReturn(MagnetSubmitResult.submitted("已提交,等待网盘下载"));

        Map<String, Object> result = service.submitManualMagnet(1, 9, MAGNET, null);

        assertEquals("submitted", result.get("status"));
        verify(offlineDownloadService).submitMagnetRetryFailed(eq(MAGNET), eq(9), any(), anyInt());
    }

    @Test
    void rejectsWhenOfflineNotConfigured() {
        when(offlineDownloadService.isConfigured()).thenReturn(false);

        assertThrows(BadRequestException.class, () -> service.submitManualMagnet(1, 9, MAGNET, null));
    }

    @Test
    void rejectsWrongOwner() {
        assertThrows(BadRequestException.class, () -> service.submitManualMagnet(2, 9, MAGNET, null));
    }

    @Test
    void rejectsBlankUrl() {
        assertThrows(BadRequestException.class, () -> service.submitManualMagnet(1, 9, "  ", null));
    }

    // ---------- 收割结算接线 ----------

    @Test
    void harvestSettlesManualPendingTaskForNullEpisodeRow() {
        // 手动路径 episode=null 的 PENDING 行不按集结算(settlePendingTask 按 episode 匹配),
        // 收割入账时须单独结算到本次产物,防 pending 闸门被永久占位
        stubProductWithEpisode3();
        stubPendingManualRow(null); // 无预测名的手动行:归属闸门近似放行

        invokeHarvest(subscription(), Set.of(3));

        verify(offlineDownloadService).settleManualPendingTask(9, "测试剧 - 第03集",
                ROOT + "/测试剧 - 第03集");
    }

    @Test
    void harvestRegistersProductMatchingManualRowPredictedName() {
        // 手动 PENDING 行带预测产物名(dn/ed2k 名):产物名对上即归属本订阅,登记入账
        stubProductWithEpisode3();
        stubPendingManualRow("测试剧 - 第03集");

        Set<Integer> remaining = invokeHarvest(subscription(), Set.of());

        verify(offlineDownloadService).settleManualPendingTask(9, "测试剧 - 第03集",
                ROOT + "/测试剧 - 第03集");
        assertTrue(remaining.isEmpty());
    }

    @Test
    void harvestSkipsUnknownProductWithoutPendingOwnership() {
        // 归属闸门:该订阅没有任何 PENDING 行时,离线目录里的未知产物(别的订阅/用户侧离线)
        // 不冒领登记 —— 过了标题/集号门禁也不行
        stubProductWithEpisode3();

        Set<Integer> remaining = invokeHarvest(subscription(), Set.of(3));

        assertTrue(remaining.contains(3), "无归属的产物不入账,缺口维持");
        verify(resourceRepository, org.mockito.Mockito.never()).save(any());
        verify(offlineDownloadService, org.mockito.Mockito.never()).settleManualPendingTask(anyInt(), anyString(), anyString());
    }

    @Test
    void harvestSkipsForeignProductWhenPendingEpisodesDisjoint() {
        // PENDING 只有第 3 集:目录里另一部剧的第 5 集产物(过标题门禁的巧合形态)不冒领
        FsInfo dir = new FsInfo();
        dir.setName("测试剧 - 第05集");
        dir.setType(1);
        FsResponse rootListing = new FsResponse();
        rootListing.setFiles(List.of(dir));
        when(aListService.listFiles(any(), eq(ROOT), anyInt(), anyInt(), anyBoolean())).thenReturn(rootListing);
        FsInfo file = new FsInfo();
        file.setName("Show.S01E05.1080p.mkv");
        file.setType(0);
        file.setSize(800L * 1024 * 1024);
        FsResponse productListing = new FsResponse();
        productListing.setFiles(List.of(file));
        when(aListService.listFiles(any(), eq(ROOT + "/" + dir.getName()), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(productListing);
        stubPendingEpisodeRow(3);

        Set<Integer> remaining = invokeHarvest(subscription(), Set.of(3));

        assertTrue(remaining.contains(3), "覆盖集与 PENDING 集号不相交:不是本订阅的产物");
        verify(resourceRepository, org.mockito.Mockito.never()).save(any());
    }

    /** 手动(episode=null)PENDING 行桩:taskName 非空=预测产物名,null=无预测名的近似形态。 */
    private void stubPendingManualRow(String predictedName) {
        cn.har01d.alist_tvbox.entity.OfflineDownloadTask task =
                new cn.har01d.alist_tvbox.entity.OfflineDownloadTask();
        task.setAccountId(12);
        task.setStatus("PENDING");
        task.setSubscriptionId(9);
        task.setEpisode(null);
        task.setTaskName(predictedName);
        when(offlineDownloadService.pendingTasks(9)).thenReturn(List.of(task));
    }

    /** 自动路径(episode 非空)PENDING 行桩。 */
    private void stubPendingEpisodeRow(int episode) {
        cn.har01d.alist_tvbox.entity.OfflineDownloadTask task =
                new cn.har01d.alist_tvbox.entity.OfflineDownloadTask();
        task.setAccountId(12);
        task.setStatus("PENDING");
        task.setSubscriptionId(9);
        task.setEpisode(episode);
        when(offlineDownloadService.pendingTasks(9)).thenReturn(List.of(task));
    }

    /** 直调收割(harvestOfflineProducts):绕过 doCheck 的重依赖。 */
    private Set<Integer> invokeHarvest(MediaSubscription subscription, Set<Integer> missing) {
        try {
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

    // ---------- 磁力候选搜索 ----------

    @Test
    void searchDefaultsToSubscriptionKeywordAndAppendsEpisode() {
        cn.har01d.alist_tvbox.dto.tg.Message magnet = new cn.har01d.alist_tvbox.dto.tg.Message();
        magnet.setLink("magnet:?xt=urn:btih:s01&dn=" + java.net.URLEncoder.encode("测试剧 - 03 4K", java.nio.charset.StandardCharsets.UTF_8));
        magnet.setContent("消息正文");
        magnet.setType("magnet");
        magnet.setSize(0L);
        cn.har01d.alist_tvbox.dto.tg.Message http = new cn.har01d.alist_tvbox.dto.tg.Message();
        http.setLink("https://pan.quark.cn/s/abc");
        http.setContent("网盘链接不应出现");
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(java.util.List.of(magnet, http));

        List<Map<String, Object>> results = service.searchManualMagnets(1, 9, null, 3);

        verify(telegramService).searchMagnets(eq("测试剧 3"), anyInt()); // 空关键词回落订阅词,集号拼进搜索词
        assertEquals(1, results.size(), "非磁力/ed2k 链接过滤掉");
        assertEquals("测试剧 - 03 4K", results.get(0).get("title"), "标题取 dn= 解码优先于消息正文");
        assertEquals("magnet:?xt=urn:btih:s01&dn=%E6%B5%8B%E8%AF%95%E5%89%A7+-+03+4K", results.get(0).get("link"));
    }

    @Test
    void searchUsesTrimmedCustomKeyword() {
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of());

        service.searchManualMagnets(1, 9, " 自定义词 ", null);

        verify(telegramService).searchMagnets(eq("自定义词"), anyInt());
    }

    @Test
    void searchMergesSiteSourcesAndLabelsOrigin() {
        // 多源并发:TG 未配/为空不致命,站点源(观影/盘链/盘聚)的磁力并入并标来源,
        // link 去重跨源生效,非离线链接过滤
        cn.har01d.alist_tvbox.dto.tg.Message tgMagnet = new cn.har01d.alist_tvbox.dto.tg.Message();
        tgMagnet.setLink("magnet:?xt=urn:btih:shared&dn=x");
        tgMagnet.setContent("TG 已召回的同链接");
        when(telegramService.searchMagnets(anyString(), anyInt())).thenReturn(List.of(tgMagnet));
        cn.har01d.alist_tvbox.dto.tg.Message guanyingMagnet = new cn.har01d.alist_tvbox.dto.tg.Message();
        guanyingMagnet.setLink("magnet:?xt=urn:btih:gy01&dn=%E8%A7%82%E5%BD%B1");
        guanyingMagnet.setContent("观影磁力");
        guanyingMagnet.setType("magnet");
        cn.har01d.alist_tvbox.dto.tg.Message guanyingHttp = new cn.har01d.alist_tvbox.dto.tg.Message();
        guanyingHttp.setLink("https://pan.quark.cn/s/xyz");
        guanyingHttp.setContent("网盘链接");
        when(guanYingSearchService.search(anyString())).thenReturn(List.of(guanyingMagnet, guanyingHttp));
        cn.har01d.alist_tvbox.dto.tg.Message panlianEd2k = new cn.har01d.alist_tvbox.dto.tg.Message();
        panlianEd2k.setLink("ed2k://|file|x.mkv|834000000|31D6CFE0D16AE931B73C59D7E0C089C0|/");
        panlianEd2k.setContent("盘链 ed2k");
        panlianEd2k.setType("ed2k");
        when(panLianSearchService.search(anyString())).thenReturn(List.of(panlianEd2k));
        cn.har01d.alist_tvbox.dto.tg.Message panjuDuplicate = new cn.har01d.alist_tvbox.dto.tg.Message();
        panjuDuplicate.setLink("magnet:?xt=urn:btih:shared&dn=x");
        panjuDuplicate.setContent("盘聚召回的同链接应被去重");
        when(panjuSearchService.search(anyString(), org.mockito.ArgumentMatchers.eq(true))).thenReturn(List.of(panjuDuplicate));

        List<Map<String, Object>> results = service.searchManualMagnets(1, 9, null, null);

        assertEquals(3, results.size(), "跨源 link 去重 + 非离线链接过滤");
        assertEquals("TG", results.get(0).get("source"));
        assertEquals("观影", results.get(1).get("source"));
        assertEquals("盘链", results.get(2).get("source"));
        verify(panjuSearchService).search(anyString(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void searchToleratesSiteSourceFailure() {
        // 某一路站点源抛错(凭证失效/站点挂):searchAsync 静默为空,不影响其它源
        when(guanYingSearchService.search(anyString())).thenThrow(new RuntimeException("cookie 失效"));
        cn.har01d.alist_tvbox.dto.tg.Message panlianEd2k = new cn.har01d.alist_tvbox.dto.tg.Message();
        panlianEd2k.setLink("ed2k://|file|x.mkv|834000000|31D6CFE0D16AE931B73C59D7E0C089C0|/");
        panlianEd2k.setContent("盘链 ed2k");
        when(panLianSearchService.search(anyString())).thenReturn(List.of(panlianEd2k));

        List<Map<String, Object>> results = service.searchManualMagnets(1, 9, null, null);

        assertEquals(1, results.size());
        assertEquals("盘链", results.get(0).get("source"));
    }

    @Test
    void searchRejectsWrongOwner() {
        assertThrows(BadRequestException.class, () -> service.searchManualMagnets(2, 9, null, null));
    }

    // ---------- 磁力解析 ----------

    @Test
    void resolveLabelsFilesWithEpisodes() {
        cn.har01d.alist_tvbox.service.magnet.MagnetResolver resolver = mock(cn.har01d.alist_tvbox.service.magnet.MagnetResolver.class);
        service.setMagnetResolver(resolver);
        when(resolver.resolve(MAGNET)).thenReturn(Optional.of(new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetInfo(
                "manual01", "测试剧合集", 1600L * 1024 * 1024, List.of(
                new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("Show/Show.S01E03.1080p.mkv", 800L * 1024 * 1024),
                new cn.har01d.alist_tvbox.service.magnet.MagnetResolver.MagnetFile("readme.txt", 1024L)))));

        Map<String, Object> result = service.resolveManualMagnet(1, 9, MAGNET);

        assertEquals(Boolean.TRUE, result.get("resolved"));
        assertEquals("测试剧合集", result.get("name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        assertEquals(2, files.size());
        assertEquals("Show.S01E03.1080p.mkv", files.get(0).get("path"), "文件路径剥目录前缀");
        assertEquals(3, files.get(0).get("episode"), "文件名解析出集号");
        assertEquals("readme.txt", files.get(1).get("path"), "根级文件名保留全名");
    }

    @Test
    void resolveReportsMirrorFailure() {
        cn.har01d.alist_tvbox.service.magnet.MagnetResolver resolver = mock(cn.har01d.alist_tvbox.service.magnet.MagnetResolver.class);
        service.setMagnetResolver(resolver);
        when(resolver.resolve(MAGNET)).thenReturn(Optional.empty());

        Map<String, Object> result = service.resolveManualMagnet(1, 9, MAGNET);

        assertEquals(Boolean.FALSE, result.get("resolved"));
        assertTrue(String.valueOf(result.get("message")).contains("解析失败"));
    }

    @Test
    void resolveRejectsBlankUrlAndWrongOwner() {
        assertThrows(BadRequestException.class, () -> service.resolveManualMagnet(1, 9, " "));
        assertThrows(BadRequestException.class, () -> service.resolveManualMagnet(2, 9, MAGNET));
    }
}
