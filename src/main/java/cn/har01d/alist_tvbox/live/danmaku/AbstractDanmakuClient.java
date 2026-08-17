package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 直播弹幕平台 WebSocket 客户端基类(移植 pure_live WebScoketUtils 的连接/心跳/重连策略):
 * 心跳定时、断线每 5 秒重连(最多 5 次,收到消息即重置计数)。
 */
@Slf4j
abstract class AbstractDanmakuClient {
    protected static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0";

    private static final int MAX_RECONNECT = 5;
    private static final int RECONNECT_DELAY_SECONDS = 5;

    private final OkHttpClient okHttpClient;
    private final ScheduledExecutorService scheduler;
    private final String url;
    private final Map<String, String> headers;
    private final long heartbeatMillis;
    private final String name;

    private volatile boolean running;
    private volatile boolean dead;
    private volatile WebSocket webSocket;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> reconnectTask;
    private volatile int reconnectCount;
    protected volatile Consumer<LiveDanmaku> listener;

    protected AbstractDanmakuClient(String name, String url, Map<String, String> headers,
                                    long heartbeatMillis, OkHttpClient okHttpClient, ScheduledExecutorService scheduler) {
        this.name = name;
        this.url = url;
        this.headers = headers;
        this.heartbeatMillis = heartbeatMillis;
        this.okHttpClient = okHttpClient;
        this.scheduler = scheduler;
    }

    public void setListener(Consumer<LiveDanmaku> listener) {
        this.listener = listener;
    }

    public void start() {
        running = true;
        reconnectCount = 0;
        connect();
    }

    public synchronized void stop() {
        running = false;
        cancelHeartbeat();
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
        listener = null;
        if (webSocket != null) {
            webSocket.cancel();
            webSocket = null;
        }
    }

    /**
     * 重连次数用尽(上游长期不可达、签名/token 失效等)后置位。
     * 会话层据此废弃本实例并按重试节奏重建新客户端。
     */
    public boolean isDead() {
        return dead;
    }

    private void connect() {
        if (!running) {
            return;
        }
        Request.Builder builder = new Request.Builder().url(url).header("User-Agent", USER_AGENT);
        headers.forEach(builder::header);
        log.debug("[{}] connecting {}", name, url);
        webSocket = okHttpClient.newWebSocket(builder.build(), new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                log.info("[{}] connected", name);
                reconnectCount = 0;
                try {
                    onConnected(ws);
                } catch (Exception e) {
                    log.error("[{}] join room failed", name, e);
                }
                // 必须在进房包之后启动:首个心跳不等一个完整周期,抢在进房前发出会让服务端忽略这次进房
                startHeartbeat();
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull ByteString bytes) {
                // 任何帧都证明连接有效(含心跳回应等无弹幕帧),斗鱼安静房间靠它避免重连计数误累积
                reconnectCount = 0;
                try {
                    if (log.isTraceEnabled()) {
                        String hex = bytes.hex();
                        log.trace("[{}] frame {} bytes: {}", name, bytes.size(),
                                hex.length() > 320 ? hex.substring(0, 320) + "..." : hex);
                    }
                    handleMessage(bytes.toByteArray());
                } catch (Exception e) {
                    log.debug("[{}] handle message failed, frame: {}", name, bytes.hex(), e);
                }
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                reconnectCount = 0;
                try {
                    if (log.isTraceEnabled()) {
                        log.trace("[{}] text frame: {}", name,
                                text.length() > 320 ? text.substring(0, 320) + "..." : text);
                    }
                    handleTextMessage(text);
                } catch (Exception e) {
                    log.debug("[{}] handle text message failed: {}", name, text, e);
                }
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, @Nullable Response response) {
                log.warn("[{}] connection failure: {}", name, t.toString());
                scheduleReconnect();
            }

            @Override
            public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
                log.info("[{}] closed: {} {}", name, code, reason);
                scheduleReconnect();
            }
        });
    }

    private synchronized void scheduleReconnect() {
        cancelHeartbeat();
        if (!running) {
            return;
        }
        if (reconnectCount >= MAX_RECONNECT) {
            log.warn("[{}] exceed max reconnect attempts", name);
            dead = true;
            return;
        }
        reconnectCount++;
        log.info("[{}] reconnect in {}s ({}/{})", name, RECONNECT_DELAY_SECONDS, reconnectCount, MAX_RECONNECT);
        if (reconnectTask == null) {
            reconnectTask = scheduler.scheduleWithFixedDelay(this::reconnect, RECONNECT_DELAY_SECONDS, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private synchronized void reconnect() {
        if (!running) {
            if (reconnectTask != null) {
                reconnectTask.cancel(false);
                reconnectTask = null;
            }
            return;
        }
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
        connect();
    }

    // 与 stop()/scheduleReconnect() 同锁:本方法在 OkHttp 读线程(onOpen)调用,与关闭方并发访问 heartbeatTask
    private synchronized void startHeartbeat() {
        cancelHeartbeat();
        // 首个心跳不等一个完整周期:虎牙在进房后十几秒内没收到心跳就会停止推送(连接仍开着)
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                byte[] message = heartbeatMessage();
                if (message != null && webSocket != null) {
                    webSocket.send(ByteString.of(message));
                }
            } catch (Exception e) {
                log.debug("[{}] heartbeat failed", name, e);
            }
        }, 0, heartbeatMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    protected void emit(LiveDanmaku message) {
        Consumer<LiveDanmaku> callback = listener;
        if (callback != null && running) {
            callback.accept(message);
        }
    }

    protected void send(byte[] data) {
        WebSocket ws = webSocket;
        if (ws != null && running) {
            ws.send(ByteString.of(data));
        }
    }

    /** 发送文本帧(IRC 类协议,如 Twitch) */
    protected void sendText(String message) {
        WebSocket ws = webSocket;
        if (ws != null && running) {
            ws.send(message);
        }
    }

    /** 连接建立后发送进房包 */
    protected abstract void onConnected(WebSocket ws);

    /** 心跳包内容,返回 null 表示无心跳 */
    protected abstract byte[] heartbeatMessage();

    /** 解析平台二进制帧并回调 emit */
    protected abstract void handleMessage(byte[] data);

    /** 解析平台文本帧并回调 emit,纯二进制协议无需覆写 */
    protected void handleTextMessage(String text) {
    }
}
