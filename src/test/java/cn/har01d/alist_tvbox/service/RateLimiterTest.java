package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 登录限速器回归测试:确保达阈值后真正锁定(回归 lockedUntil=now 的 bug)。
 */
class RateLimiterTest {
    private final RateLimiter limiter = new RateLimiter();

    @Test
    void shouldLockAfterMaxFailures() {
        String key = "user:10.0.0.1";
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(key);
        }
        // 第 5 次失败达阈值,lockedUntil 应为未来时刻,checkLocked 必须抛异常
        assertThrows(UserUnauthorizedException.class, () -> limiter.checkLocked(key));
    }

    @Test
    void shouldNotLockBeforeMaxFailures() {
        String key = "user:10.0.0.2";
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(key);
        }
        assertDoesNotThrow(() -> limiter.checkLocked(key));
    }

    @Test
    void shouldResetOnSuccess() {
        String key = "user:10.0.0.3";
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(key);
        }
        limiter.reset(key);
        assertDoesNotThrow(() -> limiter.checkLocked(key));
    }
}
