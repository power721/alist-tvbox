package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionNotifyTask;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionNotifyTaskRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Telegram 通知升级(借鉴 media-vault P5):同剧编辑同一条消息 + outbox 重试。
 * 覆盖:首发落绑定/后续编辑同消息/"内容未变"视为成功/绑定失效重发换绑/网络失败退避/
 * 超限转 FAILED/chat 变更换绑/未配置即完成/入队去重/非推送类型忽略/孤儿任务回收/卡片构建。
 */
class MediaSubscriptionNotificationServiceTest {

    private final MediaSubscriptionRepository subscriptionRepository = mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionEventRepository eventRepository = mock(MediaSubscriptionEventRepository.class);
    private final MediaSubscriptionNotifyTaskRepository taskRepository = mock(MediaSubscriptionNotifyTaskRepository.class);
    private final SettingService settingService = mock(SettingService.class);
    private final org.springframework.web.client.RestTemplate rest = mock(org.springframework.web.client.RestTemplate.class);

    private MediaSubscriptionNotificationService service;

    @BeforeEach
    void setUp() {
        service = new MediaSubscriptionNotificationService(subscriptionRepository, eventRepository,
                taskRepository, settingService, new ObjectMapper(), rest);
        when(settingService.getUserSetting("msub_telegram_bot_token", 0)).thenReturn("bot-token");
        when(settingService.getUserSetting("msub_telegram_chat_id", 0)).thenReturn("100200");
        when(eventRepository.findTop100BySubscriptionIdOrderByCreatedTimeDesc(Mockito.anyInt())).thenReturn(List.of());
    }

    private MediaSubscription subscription(Long tgMessageId, String tgChatId) {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(7);
        subscription.setName("测试剧");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setCurrentEpisodes(6);
        subscription.setTgMessageId(tgMessageId);
        subscription.setTgChatId(tgChatId);
        when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));
        return subscription;
    }

    private MediaSubscriptionNotifyTask pendingTask() {
        MediaSubscriptionNotifyTask task = new MediaSubscriptionNotifyTask();
        task.setSubscriptionId(7);
        task.setStatus(MediaSubscriptionNotifyTask.STATUS_PENDING);
        task.setCreatedTime(1L);
        return task;
    }

    private void stubTasks(MediaSubscriptionNotifyTask task) {
        when(taskRepository.findBySubscriptionIdAndStatusOrderByIdAsc(7, MediaSubscriptionNotifyTask.STATUS_PENDING))
                .thenReturn(List.of(task));
    }

    private void stubSend(String body) {
        when(rest.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    private void stubHttpError(HttpStatus status, String body) {
        when(rest.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(status, status.getReasonPhrase(),
                        new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8), null));
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(String raw) {
        try {
            return new ObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void firstNotificationSendsAndBindsMessage() {
        subscription(null, null);
        MediaSubscriptionNotifyTask task = pendingTask();
        stubTasks(task);
        stubSend("{\"ok\":true,\"result\":{\"message_id\":42}}");

        service.processSubscription(7);

        ArgumentCaptor<MediaSubscription> saved = ArgumentCaptor.forClass(MediaSubscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertEquals(42L, saved.getValue().getTgMessageId());
        assertEquals("100200", saved.getValue().getTgChatId());
        assertEquals(MediaSubscriptionNotifyTask.STATUS_SENT, task.getStatus());
        assertTrue(task.getSentTime() > 0, "任务应记录送达时间");
    }

    @Test
    void subsequentEventEditsBoundMessageWithoutResend() {
        subscription(42L, "100200");
        MediaSubscriptionNotifyTask task = pendingTask();
        stubTasks(task);
        stubSend("{\"ok\":true,\"result\":true}");

        service.processSubscription(7);

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).exchange(uri.capture(), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        assertTrue(uri.getValue().getPath().endsWith("/editMessageText"), "应编辑绑定消息而非重发");
        assertEquals(42, readJson(String.valueOf(entity.getValue().getBody())).path("message_id").asInt());
        assertEquals(MediaSubscriptionNotifyTask.STATUS_SENT, task.getStatus());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void editNotModifiedTreatedAsSuccess() {
        subscription(42L, "100200");
        MediaSubscriptionNotifyTask task = pendingTask();
        stubTasks(task);
        stubHttpError(HttpStatus.BAD_REQUEST,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message is not modified\"}");

        service.processSubscription(7);

        assertEquals(MediaSubscriptionNotifyTask.STATUS_SENT, task.getStatus(), "内容未变不算失败");
        verify(rest, Mockito.times(1)).exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void staleBindingFallsBackToSendNewAndRebinds() {
        subscription(42L, "100200");
        MediaSubscriptionNotifyTask task = pendingTask();
        stubTasks(task);
        when(rest.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                        "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message to edit not found\"}"
                                .getBytes(StandardCharsets.UTF_8), null))
                .thenReturn(ResponseEntity.ok("{\"ok\":true,\"result\":{\"message_id\":99}}"));

        service.processSubscription(7);

        ArgumentCaptor<MediaSubscription> saved = ArgumentCaptor.forClass(MediaSubscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertEquals(99L, saved.getValue().getTgMessageId(), "应换绑到新消息");
        assertEquals(MediaSubscriptionNotifyTask.STATUS_SENT, task.getStatus());
    }

    @Test
    void chatChangeSendsNewMessageInsteadOfEditingForeignChat() {
        subscription(42L, "old-chat");
        MediaSubscriptionNotifyTask task = pendingTask();
        stubTasks(task);
        stubSend("{\"ok\":true,\"result\":{\"message_id\":77}}");

        service.processSubscription(7);

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(rest).exchange(uri.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        assertTrue(uri.getValue().getPath().endsWith("/sendMessage"), "chat 变更后旧绑定失效,应重发");
        ArgumentCaptor<MediaSubscription> saved = ArgumentCaptor.forClass(MediaSubscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertEquals("100200", saved.getValue().getTgChatId());
    }

    @Test
    void networkFailureSchedulesBackoffRetry() {
        subscription(42L, "100200");
        MediaSubscriptionNotifyTask task = pendingTask();
        stubTasks(task);
        when(rest.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection timed out"));

        service.processSubscription(7);

        assertEquals(MediaSubscriptionNotifyTask.STATUS_PENDING, task.getStatus());
        assertEquals(1, task.getAttempts());
        assertTrue(task.getNextAttemptAt() > System.currentTimeMillis(), "应安排未来重试");
        assertTrue(task.getLastError().contains("connection timed out"));
    }

    @Test
    void attemptsExhaustedMarksFailed() {
        subscription(null, null);
        MediaSubscriptionNotifyTask task = pendingTask();
        task.setAttempts(4);
        stubTasks(task);
        when(rest.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection timed out"));

        service.processSubscription(7);

        assertEquals(MediaSubscriptionNotifyTask.STATUS_FAILED, task.getStatus(), "超限转 FAILED 留审计");
        assertEquals(5, task.getAttempts());
    }

    @Test
    void notificationUnconfiguredMarksTaskSentSilently() {
        when(settingService.getUserSetting("msub_telegram_bot_token", 0)).thenReturn("");
        subscription(null, null);
        MediaSubscriptionNotifyTask task = pendingTask();
        stubTasks(task);

        service.processSubscription(7);

        assertEquals(MediaSubscriptionNotifyTask.STATUS_SENT, task.getStatus(), "未配置=无外发渠道,任务即完成");
        verify(rest, never()).exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void enqueueDeduplicatesPendingTask() {
        when(taskRepository.existsBySubscriptionIdAndStatus(7, MediaSubscriptionNotifyTask.STATUS_PENDING))
                .thenReturn(true);

        service.onEvent(7, MediaSubscriptionEvent.TYPE_NEW_EPISODE);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void nonPushTypesAreNotEnqueued() {
        service.onEvent(7, MediaSubscriptionEvent.TYPE_SOURCE_REPLACED);
        service.onEvent(7, MediaSubscriptionEvent.TYPE_PINNED);

        verify(taskRepository, never()).existsBySubscriptionIdAndStatus(Mockito.anyInt(), Mockito.anyString());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void orphanTaskDeletedWhenSubscriptionGone() {
        when(subscriptionRepository.findById(7)).thenReturn(Optional.empty());

        service.processSubscription(7);

        verify(taskRepository).deleteBySubscriptionId(7);
    }

    @Test
    void cardContainsHeaderStatusAndRecentPushedEventsOnly() {
        MediaSubscription subscription = subscription(null, null);
        subscription.setStatus(MediaSubscription.STATUS_ENDED);
        subscription.setCurrentEpisodes(0);
        subscription.setOfficialTotal(24);
        long now = System.currentTimeMillis();
        MediaSubscriptionEvent pushed = new MediaSubscriptionEvent();
        pushed.setType(MediaSubscriptionEvent.TYPE_NEW_EPISODE);
        pushed.setDetail("更新 第23-24 集(共 24 集)");
        pushed.setCreatedTime(now);
        MediaSubscriptionEvent skipped = new MediaSubscriptionEvent();
        skipped.setType(MediaSubscriptionEvent.TYPE_SOURCE_REPLACED);
        skipped.setDetail("换源(旧源失效)");
        skipped.setCreatedTime(now + 1);
        when(eventRepository.findTop100BySubscriptionIdOrderByCreatedTimeDesc(7))
                .thenReturn(List.of(skipped, pushed));

        String card = service.buildCard(subscription);

        assertTrue(card.startsWith("📺 测试剧"));
        assertTrue(card.contains("✅ 已完结 · 共 24 集"));
        assertTrue(card.contains("🆕 更新 第23-24 集(共 24 集)"));
        assertTrue(!card.contains("换源"), "非推送类型不进卡片");
    }

    // ---------- 免打扰时段(2026-09-01,借鉴 MoviePilot 免打扰队列):凌晨巡检不半夜响铃 ----------

    @Test
    void quietHoursSpecParsing() {
        assertEquals(0L, MediaSubscriptionNotificationService.quietHoursRemainingMs(null));
        assertEquals(0L, MediaSubscriptionNotificationService.quietHoursRemainingMs("bad"));
        assertEquals(0L, MediaSubscriptionNotificationService.quietHoursRemainingMs("08:00-08:00"));

        java.time.LocalDateTime at = java.time.LocalDateTime.of(2026, 9, 1, 23, 30);
        assertEquals(30_600_000L, MediaSubscriptionNotificationService.quietHoursRemainingMs("23:00-08:00", at),
                "跨零点窗口:23:30 剩 8.5 小时");
        assertEquals(28_200_000L, MediaSubscriptionNotificationService.quietHoursRemainingMs("23:00-08:00",
                java.time.LocalDateTime.of(2026, 9, 1, 0, 10)), "00:10 在窗口内:剩 7 小时 50 分");
        assertEquals(0L, MediaSubscriptionNotificationService.quietHoursRemainingMs("23:00-08:00",
                java.time.LocalDateTime.of(2026, 9, 1, 12, 0)), "白天不在窗口");
        assertEquals(3_600_000L, MediaSubscriptionNotificationService.quietHoursRemainingMs("12:00-14:00",
                java.time.LocalDateTime.of(2026, 9, 1, 13, 0)), "普通窗口剩 1 小时");
        assertEquals(3_600_000L, MediaSubscriptionNotificationService.quietHoursRemainingMs("8:00-14:00",
                java.time.LocalDateTime.of(2026, 9, 1, 13, 0)), "单数字小时宽容解析");
    }

    @Test
    void processSubscriptionDefersDuringQuietHours() {
        subscription(42L, "100200");
        MediaSubscriptionNotifyTask task = pendingTask();
        stubTasks(task);
        // 覆盖"当前时刻 ±窗口"的动态 spec,保证此刻必在静默期内(含跨零点拼接)
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        String spec = now.minusMinutes(1).format(fmt) + "-" + now.plusMinutes(10).format(fmt);
        when(settingService.getUserSetting("msub_notify_quiet_hours", 0)).thenReturn(spec);

        service.processSubscription(7);

        verify(rest, never()).exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        assertTrue(task.getNextAttemptAt() > System.currentTimeMillis(),
                "任务推迟到静默结束,不计失败不计重试");
        assertEquals(0, task.getAttempts());
        verify(taskRepository).saveAll(Mockito.anyList());
    }

    @Test
    void processSubscriptionSendsImmediatelyOutsideQuietHours() {
        subscription(42L, "100200");
        MediaSubscriptionNotifyTask task = pendingTask();
        stubTasks(task);
        when(settingService.getUserSetting("msub_notify_quiet_hours", 0)).thenReturn("");
        stubSend("{\"ok\":true,\"result\":{\"message_id\":43}}");

        service.processSubscription(7);

        verify(rest).exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }
}
