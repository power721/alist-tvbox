package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.DanmakuConfig;
import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import cn.har01d.alist_tvbox.live.service.BilibiliService;
import cn.har01d.alist_tvbox.live.service.DouyinService;
import cn.har01d.alist_tvbox.live.service.HuyaService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.CipherSuite;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 直播弹幕会话管理:每个 platform$roomId 一个上游连接与滚动缓冲,多个观众共享;
 * 请求触发懒连接,空闲(无人轮询)超过 60 秒自动断开上游。客户端以 seq 为游标增量轮询。
 */
@Slf4j
@Service
public class LiveDanmakuService {

    private static final int BUFFER_LIMIT = 500;
    private static final int RECENT_SIZE = 30;
    private static final long IDLE_MILLIS = 60_000;
    private static final long RETRY_DELAY = 60_000;
    private static final Set<String> SUPPORTED = Set.of("huya", "douyu", "bili", "douyin", "twitch");
    // 弹幕速度档(慢/正常/快)对应的横穿一个屏宽毫秒数,下发解析值,调档不必更新 spider
    private static final int[] SPEED_DURATIONS = {12_000, 8_000, 5_000};

    private final Map<String, RoomSession> sessions = new ConcurrentHashMap<>();
    private final OkHttpClient okHttpClient;
    private final ScheduledExecutorService scheduler;
    private final AppProperties appProperties;
    // seq 按房间单调(会话被空闲驱逐重建后不归零,否则客户端旧游标会一直错过新消息);
    // 不用全服务计数器:那会让换房间后的 after 延续全局大数,且后端重启归零后旧游标长期卡死
    private final Map<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();
    private final HuyaService huyaService;
    private final BilibiliService bilibiliService;
    private final DouyinService douyinService;

    public LiveDanmakuService(HuyaService huyaService, BilibiliService bilibiliService, DouyinService douyinService,
                              AppProperties appProperties) {
        this.huyaService = huyaService;
        this.bilibiliService = bilibiliService;
        this.douyinService = douyinService;
        this.appProperties = appProperties;
        // OkHttp 默认 ConnectionSpec 不含静态 RSA 密钥交换套件,斗鱼弹幕服务器需要,补进白名单
        List<CipherSuite> cipherSuites = new ArrayList<>(ConnectionSpec.MODERN_TLS.cipherSuites());
        cipherSuites.add(CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256);
        cipherSuites.add(CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384);
        ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .cipherSuites(cipherSuites.toArray(new CipherSuite[0]))
                .build();
        this.okHttpClient = new OkHttpClient.Builder()
                .connectionSpecs(List.of(spec, ConnectionSpec.CLEARTEXT))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                // 不用 okhttp 的 WS ping(斗鱼回的 pong 帧不合规会被 okhttp 判为协议错误),
                // 各平台均有应用层心跳
                .build();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "live-danmaku");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::cleanup, 30, 30, TimeUnit.SECONDS);
    }

    public static boolean isSupported(String platform) {
        return platform != null && SUPPORTED.contains(platform);
    }

    /**
     * 下发给客户端的渲染配置(已把速度档换算为 duration 毫秒、rows 改名 lanes,语义即最终值)。
     */
    public Map<String, Object> resolvedConfig() {
        return resolvedConfig(appProperties.getDanmakuConfig());
    }

    /** 按请求者的用户级配置下发(无用户级配置时由调用方回落全局)。 */
    public Map<String, Object> resolvedConfig(DanmakuConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", config.isEnabled());
        map.put("lanes", config.getRows());
        map.put("duration", SPEED_DURATIONS[config.getSpeed()]);
        map.put("fontSize", config.getFontSize());
        map.put("opacity", config.getOpacity());
        map.put("color", config.getColor());
        // 消费端据此清除已显示的人气角标:消息入口已过滤,旧值不会再来新值
        map.put("showOnline", config.isShowOnline());
        return map;
    }

    /**
     * 增量拉取弹幕。after 为空返回最近弹幕,否则返回 seq 大于 after 的消息;
     * after 超过本房间当前最大 seq(跨房间带来的旧游标、后端重启计数归零)时同样回退最近弹幕自愈,
     * 保证过期游标最多错过一帧,不会把房间卡成永久静默。
     * 请求者配置的总开关关闭时返回空且不 touch/不建会话,存量会话靠空闲清理自然断开上游。
     * next 游标按未过滤切片推进:showOnline 已改按请求者出口过滤,若按返回列表取尾,
     * 被过滤的 online 消息会让游标原地踏步、每轮重复空转。
     */
    public PollResult poll(String platform, String roomId, Long after, DanmakuConfig config) {
        if (!config.isEnabled()) {
            return new PollResult(List.of(), after == null ? 0L : after);
        }
        RoomSession session = sessions.computeIfAbsent(platform + "$" + roomId, key -> new RoomSession(platform, roomId));
        session.touch();
        session.ensureStarted();
        List<LiveDanmaku> slice = after == null || after > session.currentSeq() ? session.recent() : session.since(after);
        long next = slice.isEmpty() ? (after == null ? 0L : after) : slice.get(slice.size() - 1).getSeq();
        List<LiveDanmaku> messages = config.isShowOnline() ? slice
                : slice.stream().filter(m -> !LiveDanmaku.TYPE_ONLINE.equals(m.getType())).toList();
        return new PollResult(messages, next);
    }

    public record PollResult(List<LiveDanmaku> messages, long next) {
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            RoomSession session = entry.getValue();
            if (session.isIdle(now)) {
                log.debug("close idle danmaku session: {}", entry.getKey());
                session.close();
                return true;
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(RoomSession::close);
        sessions.clear();
        scheduler.shutdownNow();
        okHttpClient.dispatcher().executorService().shutdown();
        okHttpClient.connectionPool().evictAll();
    }

    private class RoomSession {
        private final String platform;
        private final String roomId;
        private final ArrayDeque<LiveDanmaku> buffer = new ArrayDeque<>();
        // 本房间的 seq 计数器,挂在服务层:会话驱逐重建后继续递增,游标不悬空
        private final AtomicLong seq;
        private volatile long lastAccess = System.currentTimeMillis();
        private volatile long lastAttempt;
        private volatile AbstractDanmakuClient client;
        private volatile boolean starting;

        RoomSession(String platform, String roomId) {
            this.platform = platform;
            this.roomId = roomId;
            this.seq = seqCounters.computeIfAbsent(platform + "$" + roomId, key -> new AtomicLong());
        }

        long currentSeq() {
            return seq.get();
        }

        void touch() {
            lastAccess = System.currentTimeMillis();
        }

        synchronized void ensureStarted() {
            if (starting) {
                return;
            }
            AbstractDanmakuClient current = client;
            if (current != null) {
                if (!current.isDead()) {
                    return;
                }
                // 上游重连耗尽(签名过期/长期不可达):废弃后仍按 RETRY_DELAY 节奏重建
                current.stop();
                client = null;
            }
            long now = System.currentTimeMillis();
            if (now - lastAttempt < RETRY_DELAY) {
                return;
            }
            lastAttempt = now;
            starting = true;
            try {
                AbstractDanmakuClient newClient = createClient();
                if (newClient == null) {
                    log.debug("danmaku not available: {} ${}", platform, roomId);
                    return;
                }
                newClient.setListener(this::accept);
                client = newClient;
                newClient.start();
            } catch (Exception e) {
                log.warn("start danmaku client failed: {} ${}", platform, roomId, e);
            } finally {
                starting = false;
            }
        }

        private AbstractDanmakuClient createClient() throws IOException {
            switch (platform) {
                case "douyu":
                    return new DouyuDanmakuClient(roomId, okHttpClient, scheduler);
                case "huya": {
                    long ayyuid = huyaService.getAyyuid(roomId);
                    if (ayyuid <= 0) {
                        log.warn("huya ayyuid not found: {}", roomId);
                        return null;
                    }
                    return new HuyaDanmakuClient(ayyuid, okHttpClient, scheduler);
                }
                case "bili": {
                    BilibiliDanmakuClient.BiliDanmakuArgs args = bilibiliService.getDanmakuArgs(roomId);
                    return args == null ? null : new BilibiliDanmakuClient(args, okHttpClient, scheduler);
                }
                case "douyin": {
                    DouyinDanmakuClient.DouyinDanmakuArgs args = douyinService.getDanmakuArgs(roomId);
                    return args == null ? null : new DouyinDanmakuClient(args, okHttpClient, scheduler);
                }
                case "twitch":
                    return new TwitchDanmakuClient(roomId, okHttpClient, scheduler);
                default:
                    return null;
            }
        }

        void accept(LiveDanmaku message) {
            // 房间缓冲是全体观众共享的,online 消息一律入缓冲,showOnline 按各请求者配置在 poll 出口过滤
            message.setSeq(seq.incrementAndGet());
            synchronized (buffer) {
                buffer.addLast(message);
                while (buffer.size() > BUFFER_LIMIT) {
                    buffer.removeFirst();
                }
            }
        }

        List<LiveDanmaku> recent() {
            synchronized (buffer) {
                List<LiveDanmaku> list = new ArrayList<>();
                var iterator = buffer.descendingIterator();
                for (int i = 0; i < RECENT_SIZE && iterator.hasNext(); i++) {
                    list.add(iterator.next());
                }
                Collections.reverse(list);
                return list;
            }
        }

        List<LiveDanmaku> since(long after) {
            synchronized (buffer) {
                List<LiveDanmaku> list = new ArrayList<>();
                for (LiveDanmaku message : buffer) {
                    if (message.getSeq() > after) {
                        list.add(message);
                    }
                }
                return list;
            }
        }

        boolean isIdle(long now) {
            return now - lastAccess > IDLE_MILLIS;
        }

        synchronized void close() {
            if (client != null) {
                client.stop();
                client = null;
            }
        }
    }
}
