package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 轻量内存登录限速器:按 key(如 用户名+IP)统计失败次数,达到阈值后锁定一段时间。
 * 用 Caffeine 缓存,写后过期 + 容量上限自动 evict,避免 username-spraying 导致内存无限增长。
 */
@Component
public class RateLimiter {
    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private static final long LOCK_MS = 5 * 60 * 1000L;

    private static final class Attempt {
        final int count;
        final long firstAttempt;
        final long lockedUntil;

        Attempt(int count, long firstAttempt, long lockedUntil) {
            this.count = count;
            this.firstAttempt = firstAttempt;
            this.lockedUntil = lockedUntil;
        }
    }

    private final Cache<String, Attempt> attempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1_000)
            .build();

    /** 若 key 仍处于锁定状态则抛异常。鉴权前调用。 */
    public void checkLocked(String key) {
        Attempt a = attempts.getIfPresent(key);
        if (a != null && a.lockedUntil > 0 && System.currentTimeMillis() < a.lockedUntil) {
            throw new UserUnauthorizedException("尝试过于频繁，请稍后再试", 42901);
        }
    }

    /** 记录一次失败;达到阈值则置锁定时间为 now + LOCK_MS。 */
    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        attempts.asMap().compute(key, (k, v) -> {
            if (v == null || now - v.firstAttempt > WINDOW_MS) {
                return new Attempt(1, now, 0);
            }
            int count = v.count + 1;
            return new Attempt(count, v.firstAttempt, count >= MAX_ATTEMPTS ? now + LOCK_MS : v.lockedUntil);
        });
    }

    /** 成功后清除计数。 */
    public void reset(String key) {
        attempts.invalidate(key);
    }
}
