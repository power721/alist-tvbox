package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编辑订阅立即联动转存:切入 TRANSFER(挂载模式改转存)或转存目标账号变化 → 立即排队
 * 增量转存,不再等下一轮巡检/每小时 :40 sweep(线上:「编辑改为转存模式后根本没有转存」)。
 * 单测直调无事务上下文,走 isSynchronizationActive 兜底的同步执行路径。
 */
class MediaSubscriptionUpdateTransferTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository episodeSourceRepository =
            Mockito.mock(cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final MediaSubscriptionTransferService transferService = Mockito.mock(MediaSubscriptionTransferService.class);
    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, null, episodeSourceRepository, null, null, null, null, null, null,
            checkService, transferService, null, new AppProperties(), new ObjectMapper(), null, null, null);

    private MediaSubscription subscription(String mode, String accountIds) {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(3);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setMode(mode);
        subscription.setAccountIds(accountIds);
        when(subscriptionRepository.findById(3)).thenReturn(Optional.of(subscription));
        // toDto 装配候选源抽屉口径 + TRANSFER 模式的集源行统计
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(3)).thenReturn(List.of());
        when(checkService.allowedCandidateDrives(Mockito.any())).thenReturn(Set.of());
        when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.anyInt(), Mockito.anyCollection()))
                .thenReturn(List.of());
        return subscription;
    }

    @Test
    void switchToTransferModeQueuesTransferImmediately() {
        subscription(MediaSubscription.MODE_FOLLOW, "[]");
        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setMode(MediaSubscription.MODE_TRANSFER);
        request.setAccountIds(List.of("pan:6"));

        service.update(1, 3, request);

        verify(transferService).transferAsync(1, 3);
    }

    @Test
    void transferTargetChangeQueuesTransfer() {
        subscription(MediaSubscription.MODE_TRANSFER, "[\"pan:6\"]");
        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setAccountIds(List.of("pan:6", "pan:9"));

        service.update(1, 3, request);

        verify(transferService).transferAsync(1, 3);
    }

    @Test
    void unrelatedFollowUpdateDoesNotQueueTransfer() {
        subscription(MediaSubscription.MODE_FOLLOW, "[]");
        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setName("新名字");

        service.update(1, 3, request);

        verify(transferService, never()).transferAsync(anyInt(), anyInt());
    }

    @Test
    void transferModeUnchangedAccountsDoesNotQueueTransfer() {
        subscription(MediaSubscription.MODE_TRANSFER, "[\"pan:6\"]");
        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setExpectedEpisodes(20); // 只改期望集数,模式与账号都没动

        service.update(1, 3, request);

        verify(transferService, never()).transferAsync(anyInt(), anyInt());
    }

    // ---------- 长番缺口提示(2026-08-27):expected 未配时「缺第 X 集」整个丢失 ----------
    // 线上形态:柯南 expectedEpisodes=null,旧 missingEpisodes 只认 expected 直接返回空,
    // 列表页从不显示缺口提示 —— 用户只能进集数清单肉眼找 27 个缺口。
    // 新口径与巡检 computeMissing 对齐:官方已播/期望/观测最大集号取大为范围。

    @Test
    void dtoMissingEpisodesFallBackToObservedRangeWithoutExpected() {
        MediaSubscription sub = subscription(MediaSubscription.MODE_FOLLOW, "[]");
        sub.setExpectedEpisodes(null);
        sub.setOfficialEpisodes(1210);
        sub.setMaxEpisode(1270);
        sub.setCurrentEpisodes(1243);
        when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(anyInt(), anyCollection()))
                .thenReturn(IntStream.rangeClosed(1, 1270).filter(n -> n != 257 && n != 926).boxed().toList());

        MediaSubscriptionDto dto = service.toDto(sub);

        assertEquals(List.of(257, 926), dto.getMissingEpisodes());
    }

    @Test
    void dtoMissingEpisodesClampedByOfficialTotal() {
        // 瑞克 S9 形态:官方总 10 完结,官方已播 11 系上游 S1 分集桥接污染 ——
        // 不被总集数夹住会凭空"10/10 缺第 11 集",巡检空转搜不存在的集
        MediaSubscription sub = subscription(MediaSubscription.MODE_FOLLOW, "[]");
        sub.setOfficialTotal(10);
        sub.setOfficialEpisodes(11);
        sub.setMaxEpisode(10);
        sub.setCurrentEpisodes(10);
        when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(anyInt(), anyCollection()))
                .thenReturn(IntStream.rangeClosed(1, 10).boxed().toList());

        MediaSubscriptionDto dto = service.toDto(sub);

        assertEquals(List.of(), dto.getMissingEpisodes());
    }
}
