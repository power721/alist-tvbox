package cn.har01d.alist_tvbox.service.sitesearch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索源统一退避(借鉴 MoviePilot SubscriptionSiteBudget delay 表):
 * 失败语义分类、指数退避封顶、成功恢复期最小间隔(健康源不加间隔)。
 */
class SearchSourceThrottleTest {

    private final SearchSourceThrottle throttle = new SearchSourceThrottle();

    @Test
    void classifiesFailuresBySemantics() {
        assertEquals(SearchSourceThrottle.FailureKind.RATE_LIMITED,
                SearchSourceThrottle.classify(new IOException("HTTP 429 Too Many Requests")));
        assertEquals(SearchSourceThrottle.FailureKind.RATE_LIMITED,
                SearchSourceThrottle.classify(new IOException("百度网盘:操作过于频繁")));
        assertEquals(SearchSourceThrottle.FailureKind.FORBIDDEN,
                SearchSourceThrottle.classify(new IOException("HTTP 403 Forbidden")));
        assertEquals(SearchSourceThrottle.FailureKind.FORBIDDEN,
                SearchSourceThrottle.classify(new IOException("Cloudflare challenge")));
        assertEquals(SearchSourceThrottle.FailureKind.TIMEOUT,
                SearchSourceThrottle.classify(new java.util.concurrent.CompletionException(new TimeoutException())));
        assertEquals(SearchSourceThrottle.FailureKind.TIMEOUT,
                SearchSourceThrottle.classify(new IOException("connect timed out")));
        assertEquals(SearchSourceThrottle.FailureKind.OTHER,
                SearchSourceThrottle.classify(new IOException("connection reset")));
    }

    @Test
    void delayTableExponentAndCaps() {
        assertEquals(900_000L, SearchSourceThrottle.delayMs(SearchSourceThrottle.FailureKind.RATE_LIMITED, 1));
        assertEquals(1_800_000L, SearchSourceThrottle.delayMs(SearchSourceThrottle.FailureKind.RATE_LIMITED, 2));
        // 2⁵ 封顶:第 6 次连击 900s×32=8h 超过 6h 上限
        assertEquals(6 * 3600_000L, SearchSourceThrottle.delayMs(SearchSourceThrottle.FailureKind.RATE_LIMITED, 6));
        assertEquals(300_000L, SearchSourceThrottle.delayMs(SearchSourceThrottle.FailureKind.TIMEOUT, 1));
        assertEquals(2 * 3600_000L, SearchSourceThrottle.delayMs(SearchSourceThrottle.FailureKind.TIMEOUT, 99));
        assertEquals(180_000L, SearchSourceThrottle.delayMs(SearchSourceThrottle.FailureKind.OTHER, 1));
        assertEquals(3600_000L, SearchSourceThrottle.delayMs(SearchSourceThrottle.FailureKind.OTHER, 99));
    }

    @Test
    void blockedUntilBackoffExpiresAndSuccessRecovers() {
        assertFalse(throttle.blocked("wanou"), "健康源不拦");

        throttle.recordFailure("wanou", new IOException("HTTP 429 Too Many Requests"));
        assertTrue(throttle.blocked("wanou"), "限流即进退避");

        // 恢复期成功:最小间隔 60s 起(×恢复系数 ≥60s),此刻仍在间隔内
        throttle.recordSuccess("wanou");
        assertTrue(throttle.blocked("wanou"), "恢复期源有最小间隔,防立即再撞");

        // 再次成功(已健康):无间隔,立即放行
        throttle.recordSuccess("wanou");
        assertFalse(throttle.blocked("wanou"));
    }

    @Test
    void sourceStatesAreIsolated() {
        throttle.recordFailure("woniu", new IOException("connection reset"));
        assertTrue(throttle.blocked("woniu"));
        assertFalse(throttle.blocked("panju"), "单源退避不株连其它源");
    }
}
