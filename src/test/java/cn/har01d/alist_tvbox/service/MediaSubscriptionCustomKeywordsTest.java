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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 订阅自定义搜索词(多个)的服务层透传:创建/编辑规范化存储(与解析同口径拆分,换行 join,≤5),
 * 编辑空串清除、null 不动,变更即触发下一轮巡检(searchRelevant)。
 */
class MediaSubscriptionCustomKeywordsTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final cn.har01d.alist_tvbox.entity.UserPreferenceRepository preferenceRepository =
            Mockito.mock(cn.har01d.alist_tvbox.entity.UserPreferenceRepository.class);
    private final cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository episodeSourceRepository =
            Mockito.mock(cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository.class);
    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, null, episodeSourceRepository, preferenceRepository,
            null, null, null, null, null,
            Mockito.mock(MediaSubscriptionCheckService.class), null, null, new AppProperties(), new ObjectMapper(), null, null, null);

    MediaSubscriptionCustomKeywordsTest() {
        // create 的 resolveFilter 在请求未带 filter 时回落用户偏好
        when(preferenceRepository.findByUid(Mockito.anyInt())).thenReturn(Optional.empty());
    }

    private MediaSubscriptionRequest request(String name, String customKeywords) {
        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setName(name);
        request.setCustomKeywords(customKeywords);
        return request;
    }

    private void stubDtoDeps(MediaSubscription subscription) {
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(resourceRepository.findBySubscriptionIdOrderByScoreDesc(subscription.getId())).thenReturn(List.of());
        when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(anyInt(), anyCollection()))
                .thenReturn(List.of());
    }

    @Test
    void createNormalizesCustomKeywords() {
        when(subscriptionRepository.findByUidOrderByCreatedTimeDesc(1)).thenReturn(List.of());
        when(subscriptionRepository.saveAndFlush(Mockito.any())).thenAnswer(inv -> {
            MediaSubscription saved = inv.getArgument(0);
            saved.setId(11);
            return saved;
        });
        when(subscriptionRepository.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));
        when(episodeSourceRepository.findNumbersBySubscriptionAndStatesIn(Mockito.anyInt(), anyCollection()))
                .thenReturn(List.of());

        MediaSubscriptionDto dto = service.create(1, request("醒来",
                " Waking Up \nwakeup，醒来,别名\n多余1\n多余2\n多余3"));

        assertEquals("Waking Up\nwakeup\n醒来\n别名\n多余1", dto.getCustomKeywords(),
                "trim+去重+≤5,多分隔符(换行/中英文逗号)统一拆分后换行存储");
    }

    @Test
    void updateOverwritesClearsAndKeeps() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(7);
        subscription.setUid(1);
        subscription.setName("醒来");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setMode(MediaSubscription.MODE_FOLLOW);
        subscription.setNextCheckTime(0L);
        subscription.setCustomKeywords("旧词");
        stubDtoDeps(subscription);
        when(subscriptionRepository.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

        MediaSubscriptionRequest overwrite = request("醒来", "英文名\n别名2");
        assertEquals("英文名\n别名2", service.update(1, 7, overwrite).getCustomKeywords());

        MediaSubscriptionRequest clear = request("醒来", "");
        assertNull(service.update(1, 7, clear).getCustomKeywords(), "空串 = 清除");

        MediaSubscriptionRequest keep = request("醒来", null);
        assertNull(service.update(1, 7, keep).getCustomKeywords(), "null = 不动(上一轮已清空,保持空)");
    }

    @Test
    void updateTriggersImmediateNextCheck() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(9);
        subscription.setUid(1);
        subscription.setName("醒来");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setMode(MediaSubscription.MODE_FOLLOW);
        subscription.setNextCheckTime(0L);
        stubDtoDeps(subscription);
        when(subscriptionRepository.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));

        long before = System.currentTimeMillis();
        service.update(1, 9, request("醒来", "英文名"));

        assertTrue(subscription.getNextCheckTime() >= before, "搜索词变更属 searchRelevant,下一轮巡检立即生效");
    }
}
