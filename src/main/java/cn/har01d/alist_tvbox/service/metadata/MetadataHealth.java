package cn.har01d.alist_tvbox.service.metadata;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 元数据 provider 熔断器(V90 思想):连续失败 3 次短路 60 秒,期间直接跳过该 provider 的外呼,
 * 避免接口抖动时每轮巡检都撞墙。成功一次即复位。
 */
@Component
public class MetadataHealth {
    private static final int FAILURE_THRESHOLD = 3;
    private static final long OPEN_MILLIS = 60_000;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        volatile int consecutiveFailures;
        volatile long openUntil;
    }

    public boolean isOpen(String provider) {
        State state = states.get(provider);
        return state != null && System.currentTimeMillis() < state.openUntil;
    }

    public void record(String provider, boolean success) {
        State state = states.computeIfAbsent(provider, key -> new State());
        if (success) {
            state.consecutiveFailures = 0;
            return;
        }
        if (++state.consecutiveFailures >= FAILURE_THRESHOLD) {
            state.openUntil = System.currentTimeMillis() + OPEN_MILLIS;
            state.consecutiveFailures = 0;
        }
    }
}
