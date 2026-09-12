package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.DeadLinkRepository;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisode;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.model.FsInfo;
import cn.har01d.alist_tvbox.model.FsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 115 自有分享编排:批次收集过滤(仅 115 分享挂载、排除自有/磁力/路径行)、首批 activate 接管
 * 主源 + 删源、限频、开关闸门、巡检豁免(补缺不重列/主源失效不换源)。
 */
class MediaSubscriptionSelfShareTest {

    private static final String MOUNT = "/追剧/9-测试剧";
    private static final String TARGET = "/115云盘/号5/我的追剧/测试剧";
    private static final String SELF_LINK = "https://115.com/s/swsexqo3hjs?password=6666";

    private final AppProperties appProperties = new AppProperties();
    private final MediaSubscriptionRepository subscriptionRepository = mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = mock(MediaSubscriptionResourceRepository.class);
    private final MediaSubscriptionEventRepository eventRepository = mock(MediaSubscriptionEventRepository.class);
    private final MediaSubscriptionEpisodeRepository episodeRepository = mock(MediaSubscriptionEpisodeRepository.class);
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository = mock(MediaSubscriptionEpisodeSourceRepository.class);
    private final DeadLinkRepository deadLinkRepository = mock(DeadLinkRepository.class);
    private final ShareRepository shareRepository = mock(ShareRepository.class);
    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final SettingRepository settingRepository = mock(SettingRepository.class);
    private final ShareService shareService = mock(ShareService.class);
    private final AListService aListService = mock(AListService.class);
    private final Pan115SelfShareService selfShareService = mock(Pan115SelfShareService.class);

    private MediaSubscriptionCheckService service;
    private final java.util.Map<Integer, MediaSubscriptionResource> resourceStore = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        service = new MediaSubscriptionCheckService(
                subscriptionRepository, resourceRepository, eventRepository, episodeRepository, episodeSourceRepository,
                deadLinkRepository, shareRepository, siteRepository, null, null, settingRepository,
                shareService, aListService, null,
                null, null, null, null, null,
                null, null, null, appProperties, new ObjectMapper(), null, null);
        service.setSelfShareService(selfShareService);
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription()));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
        when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(anyInt(), any(Collection.class)))
                .thenReturn(List.of());
        // 资源仓库用内存 store 模拟:activate 内部会重查资源列表,必须能看到刚 save 的自有行
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(anyInt()))
                .thenAnswer(inv -> resourceStore.values().stream()
                        .sorted(java.util.Comparator.comparing(MediaSubscriptionResource::getScore,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                        .toList());
        when(resourceRepository.save(any())).thenAnswer(inv -> {
            MediaSubscriptionResource row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(41 + resourceStore.size() * 10);
            }
            resourceStore.put(row.getId(), row);
            return row;
        });
        when(shareRepository.findByPath(anyString())).thenReturn(null);
        when(subscriptionRepository.existsByShareIdAndIdNot(anyInt(), anyInt())).thenReturn(false);
        when(resourceRepository.existsByShareIdAndSubscriptionIdNot(anyInt(), anyInt())).thenReturn(false);
        when(eventRepository.countBySubscriptionIdAndTypeAndCreatedTimeGreaterThanEqual(anyInt(), anyString(), anyLong()))
                .thenReturn(0L);
        when(aListService.listFiles(any(), anyString(), anyInt(), anyInt(), anyBoolean())).thenReturn(new FsResponse());
        appProperties.getSubscription().setPrimeCheckTimes(List.of());
        appProperties.getSubscription().setNightCheckTimes(List.of());
        appProperties.setFormats(Set.of("mkv", "mp4"));

        when(selfShareService.resolveAccount(any())).thenReturn(driverAccount());
        when(selfShareService.targetDir(any(), any())).thenReturn(TARGET);
        when(selfShareService.listNames(any(), eq(TARGET))).thenReturn(List.of("第01集.mp4", "第02集.mp4"));
        when(selfShareService.createShare(any(), anyString())).thenReturn(
                new Pan115SelfShareService.ShareCreated("swsexqo3hjs", "6666", "https://115cdn.com/s/swsexqo3hjs", "测试剧"));
    }

    private MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(9);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setKeyword("测试剧");
        subscription.setSeason(1);
        subscription.setMountPath(MOUNT);
        subscription.setSelfShare(true);
        return subscription;
    }

    private static DriverAccount driverAccount() {
        DriverAccount account = new DriverAccount();
        account.setId(5);
        account.setName("号5");
        return account;
    }

    /** 115 上游分享挂载资源(主源形态):type=8、MOUNTED、挂载在订阅固定路径。 */
    private MediaSubscriptionResource upstreamResource() {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(21);
        resource.setSubscriptionId(9);
        resource.setLink("https://115.com/s/upstream000?password=abcd");
        resource.setType(8);
        resource.setSource(MediaSubscriptionResource.SOURCE_MANUAL);
        resource.setTitle("测试剧 4K");
        resource.setScore(500);
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        resource.setMountPath(MOUNT);
        resource.setShareId(66);
        return resource;
    }

    /** 集源行投影:(集号, 资源id, 分享内相对路径)。 */
    private static Object[] row(int number, int resourceId, String relPath) {
        return new Object[]{number, resourceId, relPath};
    }

    private void stubUpstreamEpisodes(Object[]... rows) {
        MediaSubscriptionResource upstream = upstreamResource();
        resourceStore.put(upstream.getId(), upstream);
        when(episodeSourceRepository.findNumberAndResourceIdAndRelPathByStatesIn(anyInt(), any(Collection.class)))
                .thenReturn(List.of(rows));
    }

    /** activate 列目录:主源挂载路径上列出快照文件。 */
    private void stubMountedEpisodes(String... names) {
        FsResponse listing = new FsResponse();
        listing.setFiles(java.util.Arrays.stream(names).map(name -> {
            FsInfo file = new FsInfo();
            file.setName(name);
            file.setType(0);
            file.setSize(800L * 1024 * 1024);
            return file;
        }).toList());
        when(aListService.listFiles(any(), eq(MOUNT), anyInt(), anyInt(), anyBoolean())).thenReturn(listing);
        Share share = new Share();
        share.setId(77);
        share.setPath(MOUNT);
        when(shareRepository.findByPath(MOUNT)).thenReturn(null).thenReturn(share);
    }

    // ---------- 批次与入账 ----------

    /** 首批:115 主源 2 集 → 转存 → 建永久分享 → activate 接管固定路径 → 删盘内源文件 → 事件。 */
    @Test
    void firstBatchTransfersSharesAndReleasesSource() {
        stubUpstreamEpisodes(row(1, 21, "第01集.mp4"), row(2, 21, "第02集.mp4"));
        stubMountedEpisodes("第01集.mp4", "第02集.mp4");

        String message = service.selfShareNow(1, 9);

        assertTrue(message.contains("第1-2集"), message);
        verify(selfShareService).transferObjects(any(), eq(MOUNT), eq(List.of("第01集.mp4", "第02集.mp4")), eq(TARGET));
        verify(selfShareService).createShare(any(), eq(TARGET));
        verify(selfShareService).removeAll(any(), eq(TARGET), eq(List.of("第01集.mp4", "第02集.mp4")));
        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        MediaSubscriptionResource selfRow = captor.getAllValues().stream()
                .filter(r -> MediaSubscriptionResource.SOURCE_SELF_115.equals(r.getSource()))
                .findFirst().orElseThrow();
        assertEquals(SELF_LINK, selfRow.getLink());
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, selfRow.getState());
        assertEquals(MOUNT, selfRow.getMountPath());
        assertEquals(8, selfRow.getType());
        ArgumentCaptor<MediaSubscriptionEvent> events = ArgumentCaptor.forClass(MediaSubscriptionEvent.class);
        verify(eventRepository, org.mockito.Mockito.atLeastOnce()).save(events.capture());
        assertTrue(events.getAllValues().stream().anyMatch(e ->
                MediaSubscriptionEvent.TYPE_SELF_SHARE.equals(e.getType()) && e.getDetail().contains("swsexqo3hjs")),
                "固化事件应携带分享码");
    }

    /** 非 115 来源(夸克挂载/磁力/路径资源)不进批次。 */
    @Test
    void batchSkipsNon115Sources() {
        MediaSubscriptionResource quark = upstreamResource();
        quark.setId(22);
        quark.setType(5);
        MediaSubscriptionResource magnet = upstreamResource();
        magnet.setId(23);
        magnet.setSource(MediaSubscriptionResource.SOURCE_MAGNET);
        MediaSubscriptionResource selfRow = upstreamResource();
        selfRow.setId(24);
        selfRow.setSource(MediaSubscriptionResource.SOURCE_SELF_115);
        resourceStore.put(quark.getId(), quark);
        resourceStore.put(magnet.getId(), magnet);
        resourceStore.put(selfRow.getId(), selfRow);
        when(episodeSourceRepository.findNumberAndResourceIdAndRelPathByStatesIn(anyInt(), any(Collection.class)))
                .thenReturn(List.of(row(1, 22, "第01集.mp4"), row(2, 23, "第02集.mp4"), row(3, 24, "第03集.mp4")));

        String message = service.selfShareNow(1, 9);

        assertTrue(message.contains("没有可固化"), message);
        verify(selfShareService, never()).createShare(any(), anyString());
    }

    /** 每日限频:当日已建满 3 个分享(事件计数)→ 拒绝并提示。 */
    @Test
    void dailyLimitBlocksBatch() {
        when(eventRepository.countBySubscriptionIdAndTypeAndCreatedTimeGreaterThanEqual(
                eq(9), eq(MediaSubscriptionEvent.TYPE_SELF_SHARE), anyLong())).thenReturn(3L);
        stubUpstreamEpisodes(row(1, 21, "第01集.mp4"));

        String message = service.selfShareNow(1, 9);

        assertTrue(message.contains("上限"), message);
        verify(selfShareService, never()).createShare(any(), anyString());
    }

    /** 转存不完整(目录里缺批次文件)→ 上抛失败,不建分享不删源。 */
    @Test
    void incompleteTransferAbortsBeforeSharing() {
        stubUpstreamEpisodes(row(1, 21, "第01集.mp4"), row(2, 21, "第02集.mp4"));
        when(selfShareService.listNames(any(), eq(TARGET))).thenReturn(List.of("第01集.mp4"));

        String message = service.selfShareNow(1, 9);

        assertTrue(message.contains("失败"), message);
        verify(selfShareService, never()).createShare(any(), anyString());
        verify(selfShareService, never()).removeAll(any(), anyString(), anyList());
    }

    // ---------- 长番闸门 ----------

    /** 可看集超过上限(柯南形态:观测 201 集)→ 不建批,手动入口返回说明。 */
    @Test
    void longRunningShowSkippedByEpisodeScale() {
        stubUpstreamEpisodes(row(1, 21, "第01集.mp4"), row(2, 21, "第02集.mp4"));
        List<Integer> observed = new java.util.ArrayList<>();
        for (int i = 1; i <= 201; i++) {
            observed.add(i);
        }
        when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(anyInt(), any(Collection.class)))
                .thenReturn(observed);

        String message = service.selfShareNow(1, 9);

        assertTrue(message.contains("超过自有分享上限"), message);
        verify(selfShareService, never()).createShare(any(), anyString());
    }

    /** 前瞻拦截:官方已登记 300 集的在播长篇,当前只看到 2 集也不开启(避免固化一半停)。 */
    @Test
    void longRunningShowSkippedByOfficialTotal() {
        stubUpstreamEpisodes(row(1, 21, "第01集.mp4"), row(2, 21, "第02集.mp4"));
        MediaSubscription subscription = subscription();
        subscription.setOfficialTotal(300);
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription));

        String message = service.selfShareNow(1, 9);

        assertTrue(message.contains("超过自有分享上限"), message);
        verify(selfShareService, never()).createShare(any(), anyString());
    }

    // ---------- 开关 ----------

    /** 开关闸门:全局总闸关 / 非 FOLLOW 模式 / 订阅未开 → 自动批次不生效。 */
    @Test
    void selfShareEnabledRequiresAllGates() {
        MediaSubscription subscription = subscription();
        assertFalse(service.selfShareEnabled(subscription)); // 全局总闸默认关

        when(settingRepository.findById(MediaSubscriptionCheckService.MSUB_SELF_SHARE_ENABLED))
                .thenReturn(Optional.of(new Setting(MediaSubscriptionCheckService.MSUB_SELF_SHARE_ENABLED, "true")));
        assertTrue(service.selfShareEnabled(subscription));

        subscription.setMode(MediaSubscription.MODE_TRANSFER);
        assertFalse(service.selfShareEnabled(subscription)); // 与转存互斥

        subscription.setMode(MediaSubscription.MODE_FOLLOW);
        subscription.setSelfShare(false);
        assertFalse(service.selfShareEnabled(subscription));
    }

    // ---------- 巡检豁免 ----------

    /** 补缺重列跳过自有批次(快照不可变,重列白耗 share/snap 配额)。 */
    @Test
    void refreshAuxMountsSkipsSelfRows() {
        MediaSubscriptionResource selfRow = upstreamResource();
        selfRow.setId(31);
        selfRow.setSource(MediaSubscriptionResource.SOURCE_SELF_115);
        selfRow.setMountPath("/追剧/.sources/9-测试剧-补1");
        resourceStore.put(selfRow.getId(), selfRow);

        service.refreshAuxMounts(subscription());

        verify(aListService, never()).listFiles(any(), eq("/追剧/.sources/9-测试剧-补1"), anyInt(), anyInt(), anyBoolean());
    }

    /** 主源失效豁免:自有分享主源列目录失败只推迟重试,不退役不换源。 */
    @Test
    void onInvalidKeepsSelfPrimary() {
        MediaSubscriptionResource selfRow = upstreamResource(); // shareId=66, mountPath=MOUNT
        selfRow.setSource(MediaSubscriptionResource.SOURCE_SELF_115);
        resourceStore.put(selfRow.getId(), selfRow);
        MediaSubscription subscription = subscription();
        subscription.setShareId(66);
        subscription.setNextCheckTime(0L);

        boolean handled = service.onInvalid(subscription, "115 风控临时失败");

        assertTrue(handled, "自有主源失效应被豁免(推迟重试)");
        assertEquals(MediaSubscriptionResource.STATE_MOUNTED, selfRow.getState(), "自有主源不退役");
        assertTrue(subscription.getNextCheckTime() > 0, "推迟下轮重试");
    }
}
