package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.telegram.BotUpdate;
import cn.har01d.alist_tvbox.service.SettingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bot 入站长轮询生命周期:getUpdates 循环 + offset 推进 + token 热切换 + 错误退避。
 * <p>
 * <ul>
 * <li><b>启用条件</b>:Setting {@code msub_telegram_bot_enabled}(默认 true)且全局
 *     {@code msub_telegram_bot_token} 非空;未配置时线程空转轮询配置(30s 一查),配好即自动启用;</li>
 * <li><b>积压丢弃</b>:启动/换 token 先做队尾快照(offset=-1、timeout=0),offset 直推队尾 ——
 *     离线期间的旧命令(可能是几小时前的 /start)按确认语义原子丢弃,重放只会制造困惑;
 *     快照之后到达的命令(包括重启后用户发的第一条)在下轮长轮询照常执行;</li>
 * <li><b>409 退避</b>:同 token 双实例轮询会 409(单实例部署下偶发于误配),指数退避到 60s 上限并告警;</li>
 * <li><b>处理池</b>:2 线程守护 executor 串并有限地处理 update(搜索等秒级网络调用不阻塞拉取)。</li>
 * </ul>
 * 单 token 口径:入站只消费全局 token;用户级 token 仅用于各自的通知出站,互不干扰。
 */
@Service
public class TelegramBotService {
    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);
    static final String ENABLED_KEY = "msub_telegram_bot_enabled";
    static final String TOKEN_KEY = "msub_telegram_bot_token";
    private static final long IDLE_SLEEP_MS = 30_000L;
    private static final long ERROR_BACKOFF_FLOOR_MS = 5_000L;
    private static final long ERROR_BACKOFF_CAP_MS = 60_000L;

    private final TelegramBotClient client;
    private final TelegramUpdateRouter router;
    private final SettingService settingService;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> poller = new AtomicReference<>();

    public TelegramBotService(TelegramBotClient client, TelegramUpdateRouter router,
                              SettingService settingService) {
        this.client = client;
        this.router = router;
        this.settingService = settingService;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "tg-bot-update");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::pollLoop, "tg-bot-poller");
        thread.setDaemon(true);
        poller.set(thread);
        thread.start();
        log.info("telegram bot poller started");
    }

    @PreDestroy
    void stop() {
        running.set(false);
        Thread thread = poller.get();
        if (thread != null) {
            thread.interrupt();
        }
        executor.shutdownNow();
    }

    private void pollLoop() {
        String token = null;
        long offset = 0;
        boolean primed = false;
        long backoff = ERROR_BACKOFF_FLOOR_MS;
        while (running.get()) {
            String current = currentToken();
            if (current == null) {
                if (token != null) {
                    log.info("telegram bot disabled or token removed, polling paused");
                    token = null;
                }
                primed = false;
                if (!sleepQuietly(IDLE_SLEEP_MS)) {
                    return;
                }
                continue;
            }
            if (!current.equals(token)) {
                // 首启或 token 变更:offset 归零,重新做积压确认
                if (token != null) {
                    log.info("telegram bot token changed, restarting stream");
                }
                token = current;
                offset = 0;
                primed = false;
                client.setMyCommands(token);
            }
            if (!primed) {
                try {
                    offset = primeOffset(token);
                    primed = true;
                    backoff = ERROR_BACKOFF_FLOOR_MS;
                    log.info("telegram bot stream primed, live updates from offset {}", offset);
                } catch (Exception e) {
                    log.warn("telegram bot backlog prime failed: {}", e.getMessage());
                    if (!sleepQuietly(backoff)) {
                        return;
                    }
                    backoff = Math.min(backoff * 2, ERROR_BACKOFF_CAP_MS);
                }
                continue;
            }
            try {
                List<BotUpdate> updates = client.getUpdates(token, offset);
                backoff = ERROR_BACKOFF_FLOOR_MS;
                for (BotUpdate update : updates) {
                    offset = Math.max(offset, update.getUpdateId() + 1);
                    final String activeToken = token;
                    executor.submit(() -> router.dispatch(activeToken, update));
                }
            } catch (Exception e) {
                log.warn("telegram getUpdates failed: {}", e.getMessage());
                if (!sleepQuietly(backoff)) {
                    return;
                }
                backoff = Math.min(backoff * 2, ERROR_BACKOFF_CAP_MS);
            }
        }
    }

    /**
     * 积压确认:队尾快照(offset=-1、timeout=0,只回最后一条待处理 update)把 offset 直推队尾+1,
     * Telegram 的确认语义一次丢弃<b>全部</b>离线 update(不受每批 100 条上限约束),快照之后到达的都是活命令。
     * <p>
     * 旧实现「首批整批跳过」会把重启后用户发的第一条命令吞掉 —— 首轮 getUpdates 是 25s 长轮询,
     * 期间到达的命令恰好落在被跳过的首批里,表现为「重启后第一次命令没有响应」。
     */
    private long primeOffset(String token) {
        List<BotUpdate> tail = client.tailUpdates(token);
        return tail.isEmpty() ? 0 : tail.get(tail.size() - 1).getUpdateId() + 1;
    }

    /** 全局 token + 开关判定;禁用或未配置返回 null(内部线程无认证上下文,密钥读取放行)。 */
    private String currentToken() {
        var enabled = settingService.get(ENABLED_KEY);
        if (enabled != null && "false".equalsIgnoreCase(enabled.getValue())) {
            return null;
        }
        var setting = settingService.get(TOKEN_KEY);
        String token = setting == null ? "" : StringUtils.trimToEmpty(setting.getValue());
        return token.isEmpty() ? null : token;
    }

    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
