package cn.har01d.alist_tvbox.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 断路器
 * 防止系统雪崩，当连续失败达到阈值时，暂时拒绝请求
 */
@Slf4j
@Component
public class CircuitBreaker {

    private static class CircuitState {
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicLong lastFailTime = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        volatile boolean open = false;
    }

    private final ConcurrentHashMap<String, CircuitState> circuits = new ConcurrentHashMap<>();

    private static final int FAILURE_THRESHOLD = 3;  // 连续失败 3 次打开断路器
    private static final long COOLDOWN_MS = 60_000;  // 冷却时间 60 秒
    private static final int SUCCESS_TO_CLOSE = 2;   // 连续成功 2 次关闭断路器

    /**
     * 检查断路器是否打开
     */
    public boolean isOpen(String name) {
        CircuitState state = circuits.computeIfAbsent(name, k -> new CircuitState());

        if (!state.open) {
            return false;
        }

        // 检查是否过了冷却期
        long now = System.currentTimeMillis();
        if (now - state.lastFailTime.get() > COOLDOWN_MS) {
            log.info("🔄 断路器 {} 进入半开状态，尝试恢复", name);
            return false;  // 半开状态，允许尝试
        }

        log.warn("⚠️ 断路器 {} 已打开，拒绝请求", name);
        return true;
    }

    /**
     * 记录成功
     */
    public void recordSuccess(String name) {
        CircuitState state = circuits.get(name);
        if (state != null) {
            state.failureCount.set(0);

            if (state.open) {
                int successCount = state.successCount.incrementAndGet();
                if (successCount >= SUCCESS_TO_CLOSE) {
                    state.open = false;
                    state.successCount.set(0);
                    log.info("✅ 断路器 {} 已关闭", name);
                }
            }
        }
    }

    /**
     * 记录失败
     */
    public void recordFailure(String name) {
        CircuitState state = circuits.computeIfAbsent(name, k -> new CircuitState());

        int failures = state.failureCount.incrementAndGet();
        state.lastFailTime.set(System.currentTimeMillis());
        state.successCount.set(0);

        if (failures >= FAILURE_THRESHOLD && !state.open) {
            state.open = true;
            log.error("🚨 断路器 {} 已打开！连续失败 {} 次", name, failures);
        }
    }

    /**
     * 获取断路器状态
     */
    public String getStatus(String name) {
        CircuitState state = circuits.get(name);
        if (state == null) {
            return "CLOSED";
        }

        if (state.open) {
            long now = System.currentTimeMillis();
            if (now - state.lastFailTime.get() > COOLDOWN_MS) {
                return "HALF_OPEN";
            }
            return "OPEN";
        }

        return "CLOSED";
    }
}
