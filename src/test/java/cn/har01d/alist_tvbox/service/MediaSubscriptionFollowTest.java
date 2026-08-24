package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TVBox「追更」动作的幂等:同名订阅 create 已复用既有行,带 link 的资源入池同样要幂等 ——
 * 重试/另一入口重复 follow 时再插一行会撞 (subscription_id, link) 唯一索引把整个事务打挂。
 */
class MediaSubscriptionFollowTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, null, null, null, null, null, null, null, null,
            checkService, null, null, new AppProperties(), new ObjectMapper(), null, null);

    private MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(3);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        return subscription;
    }

    @Test
    void repeatedFollowReusesExistingResourceAsNoOp() {
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription()));
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(9);
        resource.setSubscriptionId(3);
        resource.setLink("https://pan.example/s/abc");
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        when(resourceRepository.findBySubscriptionIdAndLink(3, "https://pan.example/s/abc"))
                .thenReturn(Optional.of(resource));

        Map<String, Object> result = service.handleAction(1, "follow",
                Map.of("name", "测试剧", "link", "https://pan.example/s/abc"));

        assertEquals(3, result.get("id"));
        verify(resourceRepository, never()).save(any());
        verify(checkService, never()).activateAsync(anyInt(), anyInt(), anyInt());
    }

    @Test
    void firstFollowSavesCandidateResourceAndActivates() {
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription()));
        when(resourceRepository.findBySubscriptionIdAndLink(3, "https://pan.example/s/abc"))
                .thenReturn(Optional.empty());
        when(resourceRepository.save(any(MediaSubscriptionResource.class))).thenAnswer(invocation -> {
            MediaSubscriptionResource saved = invocation.getArgument(0);
            saved.setId(11);
            return saved;
        });

        Map<String, Object> result = service.handleAction(1, "follow",
                Map.of("name", "测试剧", "link", "https://pan.example/s/abc"));

        assertEquals(3, result.get("id"));
        ArgumentCaptor<MediaSubscriptionResource> captor = ArgumentCaptor.forClass(MediaSubscriptionResource.class);
        verify(resourceRepository).save(captor.capture());
        MediaSubscriptionResource saved = captor.getValue();
        assertEquals(3, saved.getSubscriptionId());
        assertEquals("https://pan.example/s/abc", saved.getLink());
        assertEquals(1000, saved.getScore()); // 订阅即所见:当前源优先
        assertEquals(MediaSubscriptionResource.STATE_CANDIDATE, saved.getState());
        verify(checkService).activateAsync(1, 3, 11);
    }

    @Test
    void followWithoutLinkJustRunsFirstCheck() {
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of(subscription()));

        service.handleAction(1, "follow", Map.of("name", "测试剧"));

        verify(resourceRepository, never()).save(any());
        verify(checkService).checkAsync(1, 3);
    }
}
