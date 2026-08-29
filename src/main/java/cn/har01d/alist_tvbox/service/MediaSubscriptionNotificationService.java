package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEventRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionNotifyTask;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionNotifyTaskRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.service.metadata.MetadataHttp;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 追剧 Telegram 通知(借鉴 media-vault P5 publish_tasks + message_bindings):同剧编辑同一条消息 + 持久化重试。
 * <p>
 * 旧实现是 fire-and-forget sendMessage —— 一集一条刷屏、网络失败即丢(只打 debug 日志)。新结构:
 * <ul>
 * <li><b>消息绑定</b>:每订阅首条通知 sendMessage 落 message_id(订阅行 tg_message_id/tg_chat_id),
 *     之后所有事件 editMessageText 编辑同一条消息,内容为"当前状态 + 最近推送事件"卡片;</li>
 * <li><b>outbox 重试</b>:事件落 notify_task 行,发送/编辑失败按平方退避重试(上限 5 次转 FAILED 留审计),
 *     每分钟兜底扫描捞起;同订阅多条 PENDING 合并为一次卡片刷新,重复入队无害;</li>
 * <li>编辑目标失效(消息被删/超龄不可编辑)自动重发新消息换绑;"message is not modified"(内容未变)视为成功。</li>
 * </ul>
 * 内容不存任务行 —— 执行时从事件流现算,卡片永远是最新状态。
 */
@Service
public class MediaSubscriptionNotificationService {
    private static final Logger log = LoggerFactory.getLogger(MediaSubscriptionNotificationService.class);

    /** 与旧 notifyTelegram 同口径:只外发用户真正关心的事件(其余只进站内时间线) */
    private static final Set<String> PUSH_TYPES = Set.of(
            MediaSubscriptionEvent.TYPE_NEW_EPISODE,
            MediaSubscriptionEvent.TYPE_ERROR,
            MediaSubscriptionEvent.TYPE_ENDED,
            MediaSubscriptionEvent.TYPE_TRANSFER_DONE);
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_BASE_MS = 60_000L;
    private static final long RETRY_CAP_MS = 15 * 60_000L;
    /** TG 硬上限 4096,留余量给编码膨胀 */
    private static final int MAX_TEXT_LENGTH = 3800;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final MediaSubscriptionRepository subscriptionRepository;
    private final MediaSubscriptionEventRepository eventRepository;
    private final MediaSubscriptionNotifyTaskRepository taskRepository;
    private final SettingService settingService;
    private final ObjectMapper objectMapper;
    private final RestTemplate rest;
    /** 串行派发:入队即试发(保持旧实现的秒级到达),失败留给兜底扫描 */
    private final ExecutorService executor;
    /** 同订阅处理互斥(入队即试发与兜底扫描并发时防重复编辑) */
    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public MediaSubscriptionNotificationService(MediaSubscriptionRepository subscriptionRepository,
                                                MediaSubscriptionEventRepository eventRepository,
                                                MediaSubscriptionNotifyTaskRepository taskRepository,
                                                SettingService settingService,
                                                ObjectMapper objectMapper) {
        this(subscriptionRepository, eventRepository, taskRepository, settingService, objectMapper,
                new MetadataHttp(null).create());
    }

    /** 测试注入 RestTemplate 桩;生产走带超时的 MetadataHttp 工厂 */
    MediaSubscriptionNotificationService(MediaSubscriptionRepository subscriptionRepository,
                                         MediaSubscriptionEventRepository eventRepository,
                                         MediaSubscriptionNotifyTaskRepository taskRepository,
                                         SettingService settingService,
                                         ObjectMapper objectMapper,
                                         RestTemplate rest) {
        this.subscriptionRepository = subscriptionRepository;
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
        this.settingService = settingService;
        this.objectMapper = objectMapper;
        this.rest = rest;
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "msub-notify-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** addEvent 的外发入口:可推送类型落 outbox 并立即试发(通知未配置时任务静默完成,事件仍进站内时间线) */
    void onEvent(int subscriptionId, String type) {
        if (!PUSH_TYPES.contains(type)) {
            return;
        }
        try {
            if (taskRepository.existsBySubscriptionIdAndStatus(subscriptionId, MediaSubscriptionNotifyTask.STATUS_PENDING)) {
                return;
            }
            MediaSubscriptionNotifyTask task = new MediaSubscriptionNotifyTask();
            task.setSubscriptionId(subscriptionId);
            task.setStatus(MediaSubscriptionNotifyTask.STATUS_PENDING);
            task.setCreatedTime(System.currentTimeMillis());
            taskRepository.save(task);
        } catch (Exception e) {
            log.warn("enqueue telegram notify for {} failed: {}", subscriptionId, e.getMessage());
            return;
        }
        dispatch(subscriptionId);
    }

    /** 每分钟第 15 秒兜底:捞起退避到期的 PENDING(崩机恢复/入队即试发失败/入队去重窗口外的新任务) */
    @Scheduled(cron = "15 * * * * *")
    public void sweep() {
        try {
            List<MediaSubscriptionNotifyTask> due =
                    taskRepository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedTimeAsc(
                            MediaSubscriptionNotifyTask.STATUS_PENDING, System.currentTimeMillis());
            Set<Integer> ids = new LinkedHashSet<>();
            for (MediaSubscriptionNotifyTask task : due) {
                ids.add(task.getSubscriptionId());
            }
            ids.forEach(this::dispatch);
        } catch (Exception e) {
            log.warn("telegram notify sweep failed: {}", e.getMessage());
        }
    }

    private void dispatch(int subscriptionId) {
        try {
            executor.submit(() -> processSubscription(subscriptionId));
        } catch (Exception e) {
            log.debug("telegram notify dispatch skipped: {}", e.getMessage());
        }
    }

    /** 包可见供单测直调(绕过派发线程同步等待);生产入口是 onEvent 即时派发与 sweep 兜底 */
    void processSubscription(int subscriptionId) {
        if (!inFlight.add(subscriptionId)) {
            return;
        }
        try {
            if (subscriptionRepository.findById(subscriptionId).isEmpty()) {
                taskRepository.deleteBySubscriptionId(subscriptionId);
                return;
            }
            List<MediaSubscriptionNotifyTask> tasks = taskRepository
                    .findBySubscriptionIdAndStatusOrderByIdAsc(subscriptionId, MediaSubscriptionNotifyTask.STATUS_PENDING);
            if (tasks.isEmpty()) {
                return;
            }
            MediaSubscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
            // TG 渠道按订阅人解析:用户级 {key}:u{uid} 优先,未配置回退全局键(见 docs/multi-user-design.md §3.1)
            int uid = subscription.getUid();
            String token = settingService.getUserSetting("msub_telegram_bot_token", uid);
            String chatId = settingService.getUserSetting("msub_telegram_chat_id", uid);
            if (token.isBlank() || chatId.isBlank()) {
                markSent(tasks);
                return;
            }
            String text = buildCard(subscription);
            try {
                deliver(subscription, token, chatId, text);
                markSent(tasks);
            } catch (NotifyRetryException e) {
                scheduleRetry(tasks, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("telegram notify for {} failed: {}", subscriptionId, e.getMessage());
        } finally {
            inFlight.remove(subscriptionId);
        }
    }

    /** 首选编辑绑定消息;绑定失效(chat 变更/消息被删/超龄)重发新消息换绑 */
    private void deliver(MediaSubscription subscription, String token, String chatId, String text) {
        if (subscription.getTgMessageId() != null && chatId.equals(subscription.getTgChatId())) {
            try {
                callTelegram(token, "editMessageText", chatId, subscription.getTgMessageId(), text);
                return;
            } catch (HttpStatusCodeException e) {
                String description = errorDescription(e);
                if (description.contains("message is not modified")) {
                    return;
                }
                if (!editBindingLost(description)) {
                    throw new NotifyRetryException(description);
                }
                log.info("telegram binding for {} lost ({}), re-sending", subscription.getId(), description);
            } catch (ResourceAccessException e) {
                throw new NotifyRetryException(e.getMessage());
            }
        }
        try {
            long messageId = callTelegram(token, "sendMessage", chatId, null, text);
            if (messageId > 0) {
                rebind(subscription.getId(), chatId, messageId);
            }
        } catch (HttpStatusCodeException e) {
            throw new NotifyRetryException(errorDescription(e));
        } catch (ResourceAccessException e) {
            throw new NotifyRetryException(e.getMessage());
        }
    }

    /** 落绑定前重取订阅:发送成功到落库之间订阅可能已被删除,对 detached 实体 save 会整行复活(无 @Version) */
    private void rebind(int subscriptionId, String chatId, long messageId) {
        try {
            subscriptionRepository.findById(subscriptionId).ifPresent(current -> {
                current.setTgMessageId(messageId);
                current.setTgChatId(chatId);
                subscriptionRepository.save(current);
            });
        } catch (Exception e) {
            log.warn("persist telegram binding for {} failed: {}", subscriptionId, e.getMessage());
        }
    }

    private long callTelegram(String token, String method, String chatId, Long messageId, String text) {
        URI uri = URI.create("https://api.telegram.org/bot" + token + "/" + method);
        var body = objectMapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (messageId != null) {
            body.put("message_id", messageId);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(uri, HttpMethod.POST,
                new HttpEntity<>(body.toString(), headers), String.class);
        long returnedId;
        try {
            returnedId = objectMapper.readTree(response.getBody()).path("result").path("message_id").asLong(0);
        } catch (JsonProcessingException e) {
            throw new NotifyRetryException("bad telegram response: " + abbreviate(response.getBody()));
        }
        return returnedId;
    }

    /** "message to edit not found"/"message can't be edited"/MESSAGE_ID_INVALID:绑定已不可用,重发换绑 */
    private boolean editBindingLost(String description) {
        return description.contains("message to edit not found")
                || description.contains("message can't be edited")
                || description.contains("message not found")
                || description.contains("MESSAGE_ID_INVALID");
    }

    private String errorDescription(HttpStatusCodeException e) {
        try {
            String description = objectMapper.readTree(e.getResponseBodyAsString()).path("description").asText("");
            if (!description.isBlank()) {
                return description;
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体,回落原始文本
        }
        return abbreviate(e.getResponseBodyAsString().isBlank() ? e.getMessage() : e.getResponseBodyAsString());
    }

    /** 卡片 = 标题 + 状态行 + 最近 5 条已推送事件(内容现算,重复投递幂等) */
    String buildCard(MediaSubscription subscription) {
        StringBuilder card = new StringBuilder("📺 ").append(subscription.getName() == null ? "" : subscription.getName());
        card.append('\n').append(statusLine(subscription));
        List<MediaSubscriptionEvent> events = eventRepository
                .findTop100BySubscriptionIdOrderByCreatedTimeDesc(subscription.getId());
        int kept = 0;
        StringBuilder history = new StringBuilder();
        for (MediaSubscriptionEvent event : events) {
            if (kept >= 5) {
                break;
            }
            if (!PUSH_TYPES.contains(event.getType())) {
                continue;
            }
            if (history.length() > 0) {
                history.append('\n');
            }
            history.append(emoji(event.getType())).append(' ')
                    .append(event.getDetail() == null ? "" : event.getDetail())
                    .append(" · ").append(TIME_FORMAT.format(Instant.ofEpochMilli(event.getCreatedTime())));
            kept++;
        }
        if (kept > 0) {
            card.append("\n────────────\n").append(history);
        }
        // 超长时从最旧的历史行截断(卡片头部与状态行保留)
        while (card.length() > MAX_TEXT_LENGTH && card.lastIndexOf("\n", card.length() - 2) > 0) {
            card.setLength(card.lastIndexOf("\n", card.length() - 2));
        }
        return abbreviate(card.toString());
    }

    private String statusLine(MediaSubscription subscription) {
        String status = subscription.getStatus() == null ? "" : subscription.getStatus();
        String label = switch (status) {
            case MediaSubscription.STATUS_ENDED -> "✅ 已完结";
            case MediaSubscription.STATUS_PAUSED -> "⏸ 已暂停";
            case MediaSubscription.STATUS_ERROR -> "⚠️ 异常";
            default -> "🔄 更新中";
        };
        Integer current = subscription.getCurrentEpisodes();
        Integer total = subscription.getOfficialTotal();
        if (current != null && current > 0) {
            return label + " · 更新至第 " + current + " 集";
        }
        if (total != null && total > 0) {
            return label + " · 共 " + total + " 集";
        }
        return label;
    }

    private String emoji(String type) {
        return switch (type) {
            case MediaSubscriptionEvent.TYPE_NEW_EPISODE -> "🆕";
            case MediaSubscriptionEvent.TYPE_ENDED -> "✅";
            case MediaSubscriptionEvent.TYPE_ERROR -> "❌";
            case MediaSubscriptionEvent.TYPE_TRANSFER_DONE -> "💾";
            default -> "⚙️";
        };
    }

    private void markSent(List<MediaSubscriptionNotifyTask> tasks) {
        long now = System.currentTimeMillis();
        for (MediaSubscriptionNotifyTask task : tasks) {
            task.setStatus(MediaSubscriptionNotifyTask.STATUS_SENT);
            task.setSentTime(now);
        }
        taskRepository.saveAll(tasks);
    }

    private void scheduleRetry(List<MediaSubscriptionNotifyTask> tasks, String error) {
        long now = System.currentTimeMillis();
        String brief = abbreviate(error);
        for (MediaSubscriptionNotifyTask task : tasks) {
            task.setAttempts(task.getAttempts() + 1);
            task.setLastError(brief);
            if (task.getAttempts() >= MAX_ATTEMPTS) {
                task.setStatus(MediaSubscriptionNotifyTask.STATUS_FAILED);
                task.setNextAttemptAt(0);
            } else {
                long backoff = Math.min(task.getAttempts() * task.getAttempts() * RETRY_BASE_MS, RETRY_CAP_MS);
                task.setNextAttemptAt(now + backoff);
            }
        }
        taskRepository.saveAll(tasks);
        log.info("telegram notify retry scheduled ({} tasks): {}", tasks.size(), brief);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 480 ? text : text.substring(0, 480) + "…";
    }

    /** 可重试失败(网络/限流/5xx/未知 4xx):与绑定丢失(换绑)区分,退避后重试 */
    private static final class NotifyRetryException extends RuntimeException {
        NotifyRetryException(String message) {
            super(message);
        }
    }
}
