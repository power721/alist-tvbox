package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTask;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTaskRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.offline.OfflineDownloadHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日离线清理触发矩阵(docs/pan115-offline-auto-delete-design.md §5):
 * FAILED 即清 / PENDING 活体检查 / 通用入口 TTL / 固化先行 / 追平门禁 / 幂等与重试。
 */
@ExtendWith(MockitoExtension.class)
class OfflineCleanupServiceTest {
    private static final String HASH = "c140e4eaf4fd88decf40ed52156c209c9ca88a8b";

    @Mock
    private OfflineDownloadService offlineDownloadService;
    @Mock
    private OfflineDownloadTaskRepository taskRepository;
    @Mock
    private DriverAccountRepository driverAccountRepository;
    @Mock
    private MediaSubscriptionRepository subscriptionRepository;
    @Mock
    private MediaSubscriptionResourceRepository resourceRepository;
    @Mock
    private MediaSubscriptionEpisodeSourceRepository episodeSourceRepository;
    @Mock
    private MediaSubscriptionCheckService checkService;
    @Mock
    private OfflineDownloadHandler handler;
    @Mock
    private AListService aListService;
    @Mock
    private SiteService siteService;
    @Mock
    private SettingRepository settingRepository;

    private OfflineCleanupService service;
    private DriverAccount account;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        service = new OfflineCleanupService(offlineDownloadService, taskRepository, driverAccountRepository,
                subscriptionRepository, resourceRepository, episodeSourceRepository, checkService, appProperties,
                aListService, siteService, settingRepository);
        account = new DriverAccount();
        account.setId(12);
        account.setType(DriverType.PAN115);
        account.setCookie("UID=1_A1_1; CID=c");
        lenient().when(offlineDownloadService.getHandler("PAN115")).thenReturn(handler);
        lenient().when(handler.supportsTaskManagement()).thenReturn(true); // 115/迅雷/123 形态;光鸭走 false 用例
        lenient().when(handler.deletesFilesWithTask()).thenReturn(true); // 115/迅雷:任务+文件一体删
        lenient().when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 调度节流 marker:默认无记录(=从未跑过/升级首跑),单测内 mock 不持久化
        lenient().when(settingRepository.findById(OfflineCleanupService.LAST_RUN_SETTING)).thenReturn(Optional.empty());
        lenient().when(settingRepository.save(any(Setting.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void enable(boolean autoDelete, int ttlHours, boolean selfShare) {
        when(offlineDownloadService.cleanupConfig())
                .thenReturn(new OfflineDownloadService.CleanupConfig(12, "PAN115", autoDelete, ttlHours, selfShare));
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
    }

    private OfflineDownloadTask task(String status, Integer subscriptionId, String taskName) {
        OfflineDownloadTask task = new OfflineDownloadTask();
        task.setId(1);
        task.setAccountId(12);
        task.setUrlHash("url-hash");
        task.setInfoHash(HASH);
        task.setTaskName(taskName);
        task.setStatus(status);
        task.setSubscriptionId(subscriptionId);
        task.setCreatedTime(Instant.now().minusSeconds(48 * 3600));
        task.setUpdatedTime(Instant.now().minusSeconds(47 * 3600));
        return task;
    }

    private MediaSubscriptionResource mountedRow(int subscriptionId, String taskName) {
        MediaSubscriptionResource row = new MediaSubscriptionResource();
        row.setId(subscriptionId * 100);
        row.setSubscriptionId(subscriptionId);
        row.setLink("offline:" + taskName);
        row.setSource(MediaSubscriptionResource.SOURCE_MAGNET);
        row.setState(MediaSubscriptionResource.STATE_MOUNTED);
        return row;
    }

    private MediaSubscription subscription(int id) {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(id);
        return subscription;
    }

    // ---------- 开关门禁 ----------

    @Test
    void skipsWhenConfigMissingOrAllOff() {
        when(offlineDownloadService.cleanupConfig()).thenReturn(null);
        service.dailyCleanup();
        when(offlineDownloadService.cleanupConfig())
                .thenReturn(new OfflineDownloadService.CleanupConfig(12, "PAN115", false, 24, false));
        service.dailyCleanup();
        verify(taskRepository, never()).findCleanupCandidates(anyInt());
    }

    @Test
    void unmanagedDriverCleansFilesViaAlistWithoutTaskDeletion() {
        // 光鸭形态(无任务删除契约):不删任务记录,经内嵌 AList 删产物文件回收空间
        when(offlineDownloadService.cleanupConfig())
                .thenReturn(new OfflineDownloadService.CleanupConfig(12, "GUANGYA", true, 24, false));
        when(offlineDownloadService.getHandler("GUANGYA")).thenReturn(handler);
        when(handler.supportsTaskManagement()).thenReturn(false);
        when(handler.deletesFilesWithTask()).thenReturn(false);
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        OfflineDownloadTask task = task("COMPLETED", null, "即看即走");
        task.setCompletedTime(Instant.now().minusSeconds(30 * 3600));
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        cn.har01d.alist_tvbox.entity.Site site = new cn.har01d.alist_tvbox.entity.Site();
        when(siteService.getById(1)).thenReturn(site);
        when(offlineDownloadService.offlineRootPath()).thenReturn("/挂载/alist-tvbox-offline");

        service.dailyCleanup();

        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());
        verify(aListService).remove(site, "/挂载/alist-tvbox-offline/即看即走");
        assertEquals("DONE", task.getCleanupState());
    }

    @Test
    void unmanagedDriverPendingFallsBackToStuckDaysOnly() {
        // 光鸭 PENDING:预测产物名对不上任务列表会误判查无,不活体检查只按滞留天数
        when(offlineDownloadService.cleanupConfig())
                .thenReturn(new OfflineDownloadService.CleanupConfig(12, "GUANGYA", true, 24, false));
        when(offlineDownloadService.getHandler("GUANGYA")).thenReturn(handler);
        when(handler.supportsTaskManagement()).thenReturn(false);
        when(handler.deletesFilesWithTask()).thenReturn(false);
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        OfflineDownloadTask task = task("PENDING", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));

        service.dailyCleanup();
        verify(handler, never()).taskStatus(any(), any(), any());
        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());

        task.setCreatedTime(Instant.now().minusSeconds(8 * 24 * 3600));
        when(offlineDownloadService.offlineRootPath()).thenReturn("/挂载/alist-tvbox-offline");
        when(siteService.getById(1)).thenReturn(new cn.har01d.alist_tvbox.entity.Site());
        service.dailyCleanup();
        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean()); // 光鸭仍不删任务记录
        verify(aListService).remove(any(), eq("/挂载/alist-tvbox-offline/产物"));
    }

    @Test
    void selfShareSwitchIsIgnoredForNon115Drivers() {
        // 固化仅 cookie 115:光鸭开着固化开关也忽略,走追平门禁
        when(offlineDownloadService.cleanupConfig())
                .thenReturn(new OfflineDownloadService.CleanupConfig(12, "GUANGYA", true, 24, true));
        when(offlineDownloadService.getHandler("GUANGYA")).thenReturn(handler);
        when(handler.supportsTaskManagement()).thenReturn(false);
        when(handler.deletesFilesWithTask()).thenReturn(false);
        when(driverAccountRepository.findById(12)).thenReturn(Optional.of(account));
        OfflineDownloadTask task = task("COMPLETED", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        MediaSubscriptionResource row = mountedRow(9, "产物");
        when(resourceRepository.findByLink("offline:产物")).thenReturn(List.of(row));
        MediaSubscription sub = subscription(9);
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(sub));
        when(episodeSourceRepository.findNumbersByResourceIdAndStatesIn(row.getId(),
                List.of(MediaSubscriptionEpisodeSource.STATE_LISTED, MediaSubscriptionEpisodeSource.STATE_VERIFIED)))
                .thenReturn(List.of(3));
        when(checkService.watchedEpisode(sub)).thenReturn(3); // 追平
        when(offlineDownloadService.offlineRootPath()).thenReturn("/挂载/alist-tvbox-offline");
        when(siteService.getById(1)).thenReturn(new cn.har01d.alist_tvbox.entity.Site());

        service.dailyCleanup();

        verify(checkService, never()).selfifyOfflineProduct(anyInt(), any(), anyString());
        verify(aListService).remove(any(), eq("/挂载/alist-tvbox-offline/产物"));
    }

    // ---------- FAILED 即清 ----------

    @Test
    void failedTaskIsCleanedImmediatelyWithFiles() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("FAILED", 9, "失败产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));

        service.dailyCleanup();

        verify(handler).deleteTask(account, HASH, "失败产物", true);
        assertEquals("DONE", task.getCleanupState());
        assertNotNull(task.getCleanupTime());
    }

    @Test
    void failedTaskIsKeptWhenAutoDeleteOff() {
        enable(false, 24, true);
        OfflineDownloadTask task = task("FAILED", 9, "失败产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));

        service.dailyCleanup();

        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());
        assertEquals(null, task.getCleanupState());
    }

    // ---------- PENDING 活体检查 ----------

    @Test
    void pendingSucceededWithAliveSubscriptionWaitsForHarvest() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("PENDING", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        when(subscriptionRepository.existsById(9)).thenReturn(true);
        when(handler.taskStatus(account, HASH, "产物")).thenReturn(OfflineDownloadHandler.TaskStatus.SUCCEEDED);

        service.dailyCleanup();

        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());
    }

    @Test
    void pendingSucceededWithDeadSubscriptionIsCleaned() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("PENDING", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        when(subscriptionRepository.existsById(9)).thenReturn(false);
        when(handler.taskStatus(account, HASH, "产物")).thenReturn(OfflineDownloadHandler.TaskStatus.SUCCEEDED);

        service.dailyCleanup();

        verify(handler).deleteTask(account, HASH, "产物", true);
        assertEquals("DONE", task.getCleanupState());
    }

    @Test
    void pendingRunningIsKeptUntilStuckDays() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("PENDING", 9, "产物");
        task.setCreatedTime(Instant.now().minusSeconds(2 * 24 * 3600)); // 未超 7 天滞留阈值
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        when(handler.taskStatus(account, HASH, "产物")).thenReturn(OfflineDownloadHandler.TaskStatus.RUNNING);

        service.dailyCleanup();
        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());

        task.setCreatedTime(Instant.now().minusSeconds(8 * 24 * 3600)); // 超阈值:按滞留清
        task.setCleanupState(null);
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        service.dailyCleanup();
        verify(handler).deleteTask(account, HASH, "产物", true);
    }

    @Test
    void pendingFailedOrAbsentIsCleaned() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("PENDING", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        when(handler.taskStatus(account, HASH, "产物")).thenReturn(OfflineDownloadHandler.TaskStatus.FAILED);

        service.dailyCleanup();

        verify(handler).deleteTask(account, HASH, "产物", true);
    }

    @Test
    void pendingWithoutInfoHashFallsBackToStuckDaysOnly() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("PENDING", 9, "ed2k 产物");
        task.setInfoHash(null); // ed2k 无 btih,对不上任务列表
        task.setCreatedTime(Instant.now().minusSeconds(2 * 24 * 3600));
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));

        service.dailyCleanup();
        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());

        task.setCreatedTime(Instant.now().minusSeconds(8 * 24 * 3600));
        service.dailyCleanup();
        verify(handler).deleteTask(account, null, "ed2k 产物", true);
    }

    // ---------- COMPLETED:通用入口 TTL ----------

    @Test
    void generalEntryCleanedAfterTtlFromCompletedTime() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("COMPLETED", null, "即看即走");
        task.setCompletedTime(Instant.now().minusSeconds(30 * 3600)); // 完成 30h > TTL 24h
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));

        service.dailyCleanup();
        verify(handler).deleteTask(account, HASH, "即看即走", true);

        // TTL 内不删:提交 48h 前但完成仅 2h(起算点=完成时间,不是提交时间)
        OfflineDownloadTask fresh = task("COMPLETED", null, "即看即走");
        fresh.setCompletedTime(Instant.now().minusSeconds(2 * 3600));
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(fresh));
        service.dailyCleanup();
        verify(handler, times(1)).deleteTask(any(), any(), any(), anyBoolean()); // 仍只有第一次的删除
    }

    @Test
    void generalEntryKeptWhenAutoDeleteOff() {
        enable(false, 24, true);
        OfflineDownloadTask task = task("COMPLETED", null, "即看即走");
        task.setCompletedTime(Instant.now().minusSeconds(30 * 3600));
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));

        service.dailyCleanup();
        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());
    }

    // ---------- COMPLETED:msub 行 ----------

    @Test
    void msubRowWithoutLiveMagnetRowsIsCleaned() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("COMPLETED", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        when(resourceRepository.findByLink("offline:产物")).thenReturn(List.of()); // 行已退役/订阅已删

        service.dailyCleanup();

        verify(handler).deleteTask(account, HASH, "产物", true);
    }

    @Test
    void msubRowWaitsUntilCaughtUpWhenSelfShareOff() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("COMPLETED", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        MediaSubscriptionResource row = mountedRow(9, "产物");
        when(resourceRepository.findByLink("offline:产物")).thenReturn(List.of(row));
        MediaSubscription sub = subscription(9);
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(sub));
        when(episodeSourceRepository.findNumbersByResourceIdAndStatesIn(row.getId(),
                List.of(MediaSubscriptionEpisodeSource.STATE_LISTED, MediaSubscriptionEpisodeSource.STATE_VERIFIED)))
                .thenReturn(List.of(1, 2, 3, 4));

        when(checkService.watchedEpisode(sub)).thenReturn(2); // 40 集只看到 2:不删
        service.dailyCleanup();
        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());

        when(checkService.watchedEpisode(sub)).thenReturn(4); // 追平:删
        service.dailyCleanup();
        verify(handler).deleteTask(account, HASH, "产物", true);
    }

    @Test
    void msubRowSelfifiedThenCleanedWhenSelfShareOn() {
        enable(true, 24, true);
        OfflineDownloadTask task = task("COMPLETED", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        when(resourceRepository.findByLink("offline:产物")).thenReturn(List.of(mountedRow(9, "产物")));
        when(checkService.selfifyOfflineProduct(9, account, "产物"))
                .thenReturn("https://115.com/s/abc123?password=x1y2");

        service.dailyCleanup();

        verify(checkService).selfifyOfflineProduct(9, account, "产物"); // 删除前固化
        assertEquals("https://115.com/s/abc123?password=x1y2", task.getShareUrl());
        verify(handler).deleteTask(account, HASH, "产物", true); // 固化成功即删,不等观看进度
        assertEquals("DONE", task.getCleanupState());
    }

    @Test
    void msubRowKeptWhenSelfifyFails() {
        enable(true, 24, true);
        OfflineDownloadTask task = task("COMPLETED", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        when(resourceRepository.findByLink("offline:产物")).thenReturn(List.of(mountedRow(9, "产物")));
        when(checkService.selfifyOfflineProduct(9, account, "产物"))
                .thenThrow(new IllegalStateException("建分享失败"));

        service.dailyCleanup();

        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());
        assertEquals(null, task.getCleanupState()); // 次日重试
        assertEquals(null, task.getShareUrl());
    }

    @Test
    void selfShareAloneBanksShareWithoutDeletion() {
        enable(false, 24, true); // 纯固化模式:银行化但不删
        OfflineDownloadTask task = task("COMPLETED", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        when(resourceRepository.findByLink("offline:产物")).thenReturn(List.of(mountedRow(9, "产物")));
        when(checkService.selfifyOfflineProduct(9, account, "产物"))
                .thenReturn("https://115.com/s/abc123?password=x1y2");

        service.dailyCleanup();

        assertEquals("https://115.com/s/abc123?password=x1y2", task.getShareUrl());
        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());
        assertEquals(null, task.getCleanupState()); // 未删:行保持未清理,继续纯固化语义
    }

    @Test
    void alreadySelfifiedRowSkipsShareCreation() {
        enable(true, 24, true);
        OfflineDownloadTask task = task("COMPLETED", 9, "产物");
        task.setShareUrl("https://115.com/s/old?password=1"); // 上轮已固化
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        when(resourceRepository.findByLink("offline:产物")).thenReturn(List.of(mountedRow(9, "产物")));

        service.dailyCleanup();

        verify(checkService, never()).selfifyOfflineProduct(anyInt(), any(), anyString());
        verify(handler).deleteTask(account, HASH, "产物", true);
    }

    @Test
    void deletionDeferredWhenOtherSubscriptionStillMountsSameProduct() {
        enable(true, 24, true);
        OfflineDownloadTask task = task("COMPLETED", 9, "产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        when(resourceRepository.findByLink("offline:产物"))
                .thenReturn(List.of(mountedRow(9, "产物"), mountedRow(33, "产物"))); // 另一订阅也在挂
        when(checkService.selfifyOfflineProduct(9, account, "产物"))
                .thenReturn("https://115.com/s/abc123?password=x1y2");

        service.dailyCleanup();

        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());
    }

    // ---------- 重试与放弃 ----------

    @Test
    void taskOnlyDriverDeletesRecordThenFilesViaAlist() {
        // 123 形态:任务删除无文件参数 —— 删任务记录之外,产物文件经内嵌 AList 兜底删
        enable(true, 24, false);
        when(handler.deletesFilesWithTask()).thenReturn(false);
        OfflineDownloadTask task = task("COMPLETED", null, "即看即走");
        task.setCompletedTime(Instant.now().minusSeconds(30 * 3600));
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        cn.har01d.alist_tvbox.entity.Site site = new cn.har01d.alist_tvbox.entity.Site();
        when(siteService.getById(1)).thenReturn(site);
        when(offlineDownloadService.offlineRootPath()).thenReturn("/挂载/alist-tvbox-offline");

        service.dailyCleanup();

        verify(handler).deleteTask(account, HASH, "即看即走", true); // 任务记录删(幂等键=123任务id)
        verify(aListService).remove(site, "/挂载/alist-tvbox-offline/即看即走"); // 文件由 AList 删
        assertEquals("DONE", task.getCleanupState());
    }

    @Test
    void deleteFailureMarksRowFailedForRetry() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("FAILED", 9, "失败产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        org.mockito.Mockito.doThrow(new RuntimeException("network down"))
                .when(handler).deleteTask(any(), any(), any(), anyBoolean());

        service.dailyCleanup();

        assertEquals("FAILED", task.getCleanupState());
        assertEquals(1, task.getCleanupAttempts());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        enable(true, 24, false);
        OfflineDownloadTask task = task("FAILED", 9, "失败产物");
        task.setCleanupState("FAILED");
        task.setCleanupAttempts(5); // 连续失败超限(47h 前):7 天冷却期内不再尝试
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));

        service.dailyCleanup();

        verify(handler, never()).deleteTask(any(), any(), any(), anyBoolean());
    }

    @Test
    void retryCooldownHealsAfterSevenDays() {
        // 冷却期满(cookie 换新等环境修复后):重置计数重试一轮,不再永久放弃
        enable(true, 24, false);
        OfflineDownloadTask task = task("FAILED", 9, "失败产物");
        task.setCleanupState("FAILED");
        task.setCleanupAttempts(5);
        task.setUpdatedTime(Instant.now().minusSeconds(8 * 24 * 3600)); // 最后失败在 8 天前
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));

        service.dailyCleanup();

        verify(handler).deleteTask(account, HASH, "失败产物", true);
        assertEquals("DONE", task.getCleanupState());
    }

    @Test
    void retryCooldownFailureRestartsAttemptCount() {
        // 冷却期满后重试又失败:attempts 从重置后的 1 重新数,而非无限累积
        enable(true, 24, false);
        OfflineDownloadTask task = task("FAILED", 9, "失败产物");
        task.setCleanupState("FAILED");
        task.setCleanupAttempts(5);
        task.setUpdatedTime(Instant.now().minusSeconds(8 * 24 * 3600));
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));
        org.mockito.Mockito.doThrow(new RuntimeException("still broken"))
                .when(handler).deleteTask(any(), any(), any(), anyBoolean());

        service.dailyCleanup();

        assertEquals("FAILED", task.getCleanupState());
        assertEquals(1, task.getCleanupAttempts());
    }

    // ---------- 调度节流与错过补偿 ----------

    @Test
    void cleanupSkippedWithinMinInterval() {
        // 23h 内跑过:主调度与补偿调度都被节流(常开设备每天恰好一次)
        lenient().when(settingRepository.findById(OfflineCleanupService.LAST_RUN_SETTING))
                .thenReturn(Optional.of(new Setting(OfflineCleanupService.LAST_RUN_SETTING,
                        Instant.now().minusSeconds(10 * 3600).toString())));

        service.dailyCleanup();
        service.catchUpCleanup();

        verify(taskRepository, never()).findCleanupCandidates(anyInt());
    }

    @Test
    void catchUpRunsWhenOverdueOrNeverRun() {
        // 上次清理在 30h 前(昨天 05:40 后设备关机错过今天主调度):补偿调度接管
        when(settingRepository.findById(OfflineCleanupService.LAST_RUN_SETTING))
                .thenReturn(Optional.of(new Setting(OfflineCleanupService.LAST_RUN_SETTING,
                        Instant.now().minusSeconds(30 * 3600).toString())));
        enable(true, 24, false);
        OfflineDownloadTask task = task("FAILED", 9, "失败产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));

        service.catchUpCleanup();

        verify(handler).deleteTask(account, HASH, "失败产物", true);
        verify(settingRepository).save(org.mockito.ArgumentMatchers.argThat((Setting s) ->
                OfflineCleanupService.LAST_RUN_SETTING.equals(s.getName()) && s.getValue() != null));
    }

    @Test
    void catchUpRunsOnFirstSightWithoutMarker() {
        // 升级上来从未跑过(无 marker):首次补偿检查立即执行存量清理
        when(settingRepository.findById(OfflineCleanupService.LAST_RUN_SETTING)).thenReturn(Optional.empty());
        enable(true, 24, false);
        OfflineDownloadTask task = task("FAILED", 9, "失败产物");
        when(taskRepository.findCleanupCandidates(12)).thenReturn(List.of(task));

        service.catchUpCleanup();

        verify(handler).deleteTask(account, HASH, "失败产物", true);
    }

    @Test
    void catchUpRecordsMarkerEvenWhenConfigDisabled() {
        // 配置未启用空跑也记 marker:防每小时补偿调度反复穿透到配置检查
        when(offlineDownloadService.cleanupConfig()).thenReturn(null);

        service.catchUpCleanup();

        verify(settingRepository).save(org.mockito.ArgumentMatchers.argThat((Setting s) ->
                OfflineCleanupService.LAST_RUN_SETTING.equals(s.getName())));
        verify(taskRepository, never()).findCleanupCandidates(anyInt());
    }
}
