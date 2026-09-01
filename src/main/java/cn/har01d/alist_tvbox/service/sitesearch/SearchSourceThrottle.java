package cn.har01d.alist_tvbox.service.sitesearch;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * 搜索源统一退避(订阅巡检侧):某源连续失败/撞限流时,指数退避期内直接跳过该源,
 * 不再把好源往封禁里撞(借鉴 MoviePilot SubscriptionSiteBudget 的失败分类 delay 表)。
 * <p>
 * 六路源(telegram/玩偶/盘链/观影/蜗牛/盘聚)只有百度系有专属熔断,其余裸打 ——
 * 订阅越多、补搜轮次越多,失败源每轮照撞。失败按语义分类退避:
 * <ul>
 * <li>限流/被封(429/403/Cloudflare):基数 900s×2ⁿ,封顶 6h —— 撞了就是重罪,长退;</li>
 * <li>超时:基数 300s×2ⁿ,封顶 2h;</li>
 * <li>其它(瞬时网络/解析):基数 180s×2ⁿ,封顶 1h —— 网关抖动快速自愈;</li>
 * <li>成功后:恢复期(此前有失败连击)源加 60~300s×恢复系数 的最小间隔,失败越久恢复越慢;
 *     健康源不加间隔 —— 补搜一轮多关键词(整季词→单集词)不被卡。</li>
 * </ul>
 * 内存态:重启清零可接受(退避本来就是短窗行为)。手动预览(preview)不走本闸门 ——
 * 用户亲自点的搜索不该被自动退避吞掉。
 */
@Component
public class SearchSourceThrottle {
    private static final Logger log = LoggerFactory.getLogger(SearchSourceThrottle.class);

    /** 失败语义分类;指数 = 连续失败次数-1,封顶 5(2⁵=32 倍)。 */
    enum FailureKind { RATE_LIMITED, FORBIDDEN, TIMEOUT, OTHER }

    private static final int MAX_EXPONENT = 5;
    private static final long RATE_LIMIT_BASE_MS = 900_000L;
    private static final long RATE_LIMIT_CAP_MS = 6 * 3600_000L;
    private static final long TIMEOUT_BASE_MS = 300_000L;
    private static final long TIMEOUT_CAP_MS = 2 * 3600_000L;
    private static final long OTHER_BASE_MS = 180_000L;
    private static final long OTHER_CAP_MS = 3600_000L;

    private static final class SourceState {
        volatile int consecutiveFailures;
        volatile long nextAllowedAt;
    }

    private final ConcurrentHashMap<String, SourceState> sources = new ConcurrentHashMap<>();

    /** 退避期内返回 true,调用方应跳过该源本轮搜索。 */
    public boolean blocked(String source) {
        SourceState state = sources.get(source);
        return state != null && System.currentTimeMillis() < state.nextAllowedAt;
    }

    public void recordSuccess(String source) {
        SourceState state = sources.get(source);
        if (state == null) {
            return;
        }
        synchronized (state) {
            int streak = state.consecutiveFailures;
            state.consecutiveFailures = 0;
            if (streak > 0) {
                // 恢复期最小间隔:失败越久,恢复后放行越慢(1+0.5×min(连击,3));健康源无间隔
                double factor = 1 + 0.5 * Math.min(streak, 3);
                long spacing = (long) ((60_000L + ThreadLocalRandom.current().nextLong(240_000L)) * factor);
                state.nextAllowedAt = System.currentTimeMillis() + spacing;
                log.info("source {} recovered after {} failures, spacing {}s", source, streak, spacing / 1000);
            } else {
                state.nextAllowedAt = 0;
            }
        }
    }

    public void recordFailure(String source, Throwable cause) {
        SourceState state = sources.computeIfAbsent(source, k -> new SourceState());
        FailureKind kind = classify(cause);
        synchronized (state) {
            int failures = state.consecutiveFailures + 1;
            state.consecutiveFailures = failures;
            long delay = delayMs(kind, failures);
            state.nextAllowedAt = System.currentTimeMillis() + delay;
            log.warn("source {} failure #{} ({}), backoff {}s", source, failures, kind, delay / 1000);
        }
    }

    static long delayMs(FailureKind kind, int consecutiveFailures) {
        long base;
        long cap;
        switch (kind) {
            case RATE_LIMITED, FORBIDDEN -> {
                base = RATE_LIMIT_BASE_MS;
                cap = RATE_LIMIT_CAP_MS;
            }
            case TIMEOUT -> {
                base = TIMEOUT_BASE_MS;
                cap = TIMEOUT_CAP_MS;
            }
            default -> {
                base = OTHER_BASE_MS;
                cap = OTHER_CAP_MS;
            }
        }
        int exponent = Math.min(Math.max(consecutiveFailures - 1, 0), MAX_EXPONENT);
        return Math.min(base * (1L << exponent), cap);
    }

    /** 限流 → 封禁 → 超时(cause 链上找 TimeoutException)→ 其它。 */
    static FailureKind classify(Throwable cause) {
        String message = messageOf(cause);
        if (message.contains("429") || message.contains("too many requests")
                || message.contains("rate limit") || message.contains("rate_limited")
                || message.contains("限流") || message.contains("频繁")) {
            return FailureKind.RATE_LIMITED;
        }
        if (message.contains("403") || message.contains("forbidden")
                || message.contains("cloudflare") || message.contains("just a moment")) {
            return FailureKind.FORBIDDEN;
        }
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TimeoutException) {
                return FailureKind.TIMEOUT;
            }
            String m = StringUtils.defaultString(t.getMessage()).toLowerCase(Locale.ROOT);
            if (m.contains("timeout") || m.contains("timed out")) {
                return FailureKind.TIMEOUT;
            }
        }
        return FailureKind.OTHER;
    }

    private static String messageOf(Throwable cause) {
        return StringUtils.defaultString(cause == null ? null : cause.getMessage()).toLowerCase(Locale.ROOT);
    }
}
