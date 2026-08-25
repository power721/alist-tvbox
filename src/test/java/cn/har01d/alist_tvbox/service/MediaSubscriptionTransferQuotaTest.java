package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService.EpisodeFile;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转存日配额按「每个转存任务」计:多目标逐盘各起一个任务,配额在循环外只查一次会被
 * 同一订阅的多个盘吃穿(maxTransfersPerDay=1、两盘都缺集 → 两个任务)。逐目标复查。
 */
class MediaSubscriptionTransferQuotaTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final DriverAccountRepository accountRepository = Mockito.mock(DriverAccountRepository.class);
    private final AccountRepository aliAccountRepository = Mockito.mock(AccountRepository.class);
    private final SiteRepository siteRepository = Mockito.mock(SiteRepository.class);
    private final ShareRepository shareRepository = Mockito.mock(ShareRepository.class);
    private final AListService aListService = Mockito.mock(AListService.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final TaskService taskService = Mockito.mock(TaskService.class);

    private MediaSubscriptionTransferService service(AppProperties appProperties) {
        return new MediaSubscriptionTransferService(subscriptionRepository,
                resourceRepository, accountRepository, aliAccountRepository, siteRepository,
                Mockito.mock(SettingRepository.class), shareRepository, aListService, checkService,
                taskService, null, appProperties);
    }

    private MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(9);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setMode(MediaSubscription.MODE_TRANSFER);
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setAccountIds("[\"pan:1\",\"pan:2\"]");
        return subscription;
    }

    private static DriverAccount account(int id, String mountName) {
        DriverAccount account = new DriverAccount();
        account.setId(id);
        account.setType(DriverType.QUARK);
        account.setName(mountName); // 以 / 开头即挂载根,免拼平台前缀
        return account;
    }

    @Test
    void quotaIsRecheckedBeforeEachTargetTask() {
        AppProperties appProperties = new AppProperties();
        appProperties.getSubscription().setMaxTransfersPerDay(1); // 只剩 1 个名额,订阅却配了 2 个目标盘
        MediaSubscriptionTransferService transferService = service(appProperties);

        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription()));
        when(accountRepository.findById(1)).thenReturn(Optional.of(account(1, "/盘A")));
        when(accountRepository.findById(2)).thenReturn(Optional.of(account(2, "/盘B")));
        when(siteRepository.findById(1)).thenReturn(Optional.of(mock(Site.class)));
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(9)).thenReturn(java.util.List.of());

        TreeMap<Integer, EpisodeFile> sources = new TreeMap<>();
        sources.put(1, new EpisodeFile(1, "/src", "第01集.mkv", 500_000_000L, 0));
        when(checkService.walkEpisodeFiles(any(), anyBoolean())).thenReturn(sources);
        when(checkService.maxEpisodeBytes(any())).thenReturn(0L);
        // 目标盘 A:转存前为空(缺 1 集 → 起任务),事后校验集齐;目标盘 B 首查也为空 ——
        // 若配额没拦住,同样会走到起任务,addSubscriptionTask 就会被调两次
        when(checkService.walkEpisodes(any(), any(), eq("/盘A/追剧/测试剧"), anyLong()))
                .thenReturn(new TreeSet<>(), new TreeSet<>(java.util.Set.of(1)));
        when(checkService.walkEpisodes(any(), any(), eq("/盘B/追剧/测试剧"), anyLong()))
                .thenReturn(new TreeSet<>());

        Task task = mock(Task.class);
        when(task.getId()).thenReturn(77);
        when(taskService.addSubscriptionTask(anyString())).thenReturn(task);
        when(aListService.awaitCopyTasks(any(), anyLong())).thenReturn(true);

        transferService.transfer(subscription());

        verify(taskService, times(1)).addSubscriptionTask(anyString()); // 名额 1 个 = 任务 1 个,盘 B 被配额拦下
        verify(aListService, times(1)).copy(any(), anyString(), eq("/盘A/追剧/测试剧"), any());
        verify(aListService, Mockito.never()).copy(any(), anyString(), eq("/盘B/追剧/测试剧"), any());
    }

    @Test
    void transferSkippedEntirelyWhenQuotaAlreadyExhausted() {
        AppProperties appProperties = new AppProperties();
        appProperties.getSubscription().setMaxTransfersPerDay(0);
        MediaSubscriptionTransferService transferService = service(appProperties);

        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription()));

        transferService.transfer(subscription());

        verify(accountRepository, Mockito.never()).findById(anyInt());
        verify(taskService, Mockito.never()).addSubscriptionTask(anyString());
    }

    // 转存与删除并发:无 @Version,detached 实体 save 已删行 = 整行 INSERT 复活(线上 #40 同族)
    @Test
    void transferSkippedWhenSubscriptionDeleted() {
        MediaSubscriptionTransferService transferService = service(new AppProperties());

        when(subscriptionRepository.findById(9)).thenReturn(Optional.empty());

        transferService.transfer(subscription());

        verify(accountRepository, Mockito.never()).findById(anyInt());
        verify(taskService, Mockito.never()).addSubscriptionTask(anyString());
        verify(subscriptionRepository, Mockito.never()).save(any());
        verify(checkService, Mockito.never()).addEvent(anyInt(), anyString(), anyString());
    }

    @Test
    void downgradeSkippedWhenSubscriptionDeletedDuringTransfer() {
        MediaSubscriptionTransferService transferService = service(new AppProperties());
        MediaSubscription subscription = subscription();
        subscription.setAccountIds("[\"pan:1\"]");
        // 入口取到活行,降级落库前(可能已历数分钟转存)订阅被删 → 不 save 复活、不写降级事件
        when(subscriptionRepository.findById(9)).thenReturn(Optional.of(subscription)).thenReturn(Optional.empty());

        transferService.transfer(subscription);

        verify(subscriptionRepository, Mockito.never()).save(any());
        verify(checkService, Mockito.never()).addEvent(anyInt(), anyString(),
                org.mockito.ArgumentMatchers.contains("降级"));
    }
}
