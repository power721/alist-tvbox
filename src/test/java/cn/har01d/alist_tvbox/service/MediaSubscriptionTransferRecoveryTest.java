package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService.EpisodeFile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转存降级与恢复(#1094):全部目标失败自动降级挂载时置 transfer_degraded ——
 * 已转存集继续走自有盘副本供播(播放门放行见 MediaSubscriptionService.transferCopiesUsable),
 * sweep 每小时静默重试,任一目标成功即恢复 TRANSFER;恢复失败不刷事件不落库。
 */
class MediaSubscriptionTransferRecoveryTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final DriverAccountRepository accountRepository = Mockito.mock(DriverAccountRepository.class);
    private final AccountRepository aliAccountRepository = Mockito.mock(AccountRepository.class);
    private final SiteRepository siteRepository = Mockito.mock(SiteRepository.class);
    private final SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
    private final ShareRepository shareRepository = Mockito.mock(ShareRepository.class);
    private final AListService aListService = Mockito.mock(AListService.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final TaskService taskService = Mockito.mock(TaskService.class);

    private MediaSubscriptionTransferService service() {
        return new MediaSubscriptionTransferService(subscriptionRepository,
                resourceRepository, accountRepository, aliAccountRepository, siteRepository,
                settingRepository, shareRepository, aListService, checkService,
                taskService, null, new AppProperties());
    }

    private MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(9);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setAccountIds("[\"pan:1\"]");
        return subscription;
    }

    private static DriverAccount account(int id, String mountName) {
        DriverAccount account = new DriverAccount();
        account.setId(id);
        account.setType(DriverType.QUARK);
        account.setName(mountName);
        return account;
    }

    /** 源有第 1 集、目标盘目录按 stub 决定:空 = 缺 1 集要起任务;含 1 = 已齐无需转存。 */
    private void stubSources(TreeSet<Integer> targetEpisodes) {
        when(siteRepository.findById(1)).thenReturn(Optional.of(mock(Site.class)));
        when(accountRepository.findById(1)).thenReturn(Optional.of(account(1, "/盘A")));
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(List.of());
        TreeMap<Integer, EpisodeFile> sources = new TreeMap<>();
        sources.put(1, new EpisodeFile(1, "/分享/剧名", "第01集.mkv", 500_000_000L, 0));
        when(checkService.walkEpisodeFiles(any(), anyBoolean())).thenReturn(sources);
        when(checkService.walkEpisodes(any(), any(), eq("/盘A/我的追剧/测试剧"), any()))
                .thenReturn(targetEpisodes);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(77);
        when(taskService.addSubscriptionTask(anyString())).thenReturn(task);
    }

    @Test
    void downgradeMarksTransferDegraded() {
        MediaSubscriptionTransferService transferService = service();
        MediaSubscription subscription = subscription();
        subscription.setMode(MediaSubscription.MODE_TRANSFER);
        stubSources(new TreeSet<>());
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription));
        when(aListService.copy(any(), anyString(), anyString(), any()))
                .thenThrow(new cn.har01d.alist_tvbox.exception.BadRequestException("quota"));

        transferService.transfer(subscription);

        ArgumentCaptor<MediaSubscription> captor = ArgumentCaptor.forClass(MediaSubscription.class);
        verify(subscriptionRepository).save(captor.capture());
        assertEquals(MediaSubscription.MODE_FOLLOW, captor.getValue().getMode());
        assertTrue(captor.getValue().isTransferDegraded());
        verify(checkService).addEvent(eq(9),
                eq(cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent.TYPE_ERROR), contains("降级"));
    }

    @Test
    void recoverySuccessRestoresTransferMode() {
        MediaSubscriptionTransferService transferService = service();
        MediaSubscription subscription = subscription();
        subscription.setMode(MediaSubscription.MODE_FOLLOW);
        subscription.setTransferDegraded(true);
        // 目标已齐:transferToAccount 返回 0(无需转存)也算成功 → 恢复
        stubSources(new TreeSet<>(java.util.Set.of(1)));
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription));

        transferService.transfer(subscription);

        ArgumentCaptor<MediaSubscription> captor = ArgumentCaptor.forClass(MediaSubscription.class);
        verify(subscriptionRepository).save(captor.capture());
        assertEquals(MediaSubscription.MODE_TRANSFER, captor.getValue().getMode());
        assertFalse(captor.getValue().isTransferDegraded());
        verify(checkService).addEvent(eq(9),
                eq(cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent.TYPE_TRANSFER_DONE), contains("恢复"));
        verify(taskService, never()).addSubscriptionTask(anyString());
    }

    @Test
    void recoveryFailureStaysDegradedSilently() {
        MediaSubscriptionTransferService transferService = service();
        MediaSubscription subscription = subscription();
        subscription.setMode(MediaSubscription.MODE_FOLLOW);
        subscription.setTransferDegraded(true);
        stubSources(new TreeSet<>());
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription));
        when(aListService.copy(any(), anyString(), anyString(), any()))
                .thenThrow(new cn.har01d.alist_tvbox.exception.BadRequestException("quota"));

        transferService.transfer(subscription);

        // 恢复失败:保持降级不落库、不刷事件(每小时重试,事件只在降级/恢复的状态变更时写)
        verify(subscriptionRepository, never()).save(any());
        verify(checkService, never()).addEvent(anyInt(), anyString(), anyString());
        verify(taskService).failTask(eq(77), anyString()); // 失败仍留痕在任务里
    }

    @Test
    void recoverySkipsSilentlyWhenTargetsUnresolvable() {
        MediaSubscriptionTransferService transferService = service();
        MediaSubscription subscription = subscription();
        subscription.setMode(MediaSubscription.MODE_FOLLOW);
        subscription.setTransferDegraded(true);
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription));
        when(accountRepository.findById(1)).thenReturn(Optional.empty()); // 账号已删

        transferService.transfer(subscription);

        verify(subscriptionRepository, never()).save(any());
        verify(checkService, never()).addEvent(anyInt(), anyString(), anyString());
    }

    @Test
    void defaultTransferIdsFiltersUnavailableTargets() {
        MediaSubscriptionTransferService transferService = service();
        when(settingRepository.findById("msub_default_mode"))
                .thenReturn(Optional.of(setting("msub_default_mode", "TRANSFER")));
        when(settingRepository.findById("msub_default_account_ids"))
                .thenReturn(Optional.of(setting("msub_default_account_ids", "pan:1, pan:9")));
        when(accountRepository.findById(1)).thenReturn(Optional.of(account(1, "/盘A")));
        when(accountRepository.findById(9)).thenReturn(Optional.empty());
        when(aliAccountRepository.count()).thenReturn(0L);

        assertEquals(List.of("pan:1"), transferService.defaultTransferIds(1));

        // 默认模式非转存:直接空(不读账号清单)
        when(settingRepository.findById("msub_default_mode"))
                .thenReturn(Optional.of(setting("msub_default_mode", "FOLLOW")));
        assertEquals(List.of(), transferService.defaultTransferIds(1));
    }

    private static Setting setting(String name, String value) {
        Setting setting = new Setting();
        setting.setName(name);
        setting.setValue(value);
        return setting;
    }
}
