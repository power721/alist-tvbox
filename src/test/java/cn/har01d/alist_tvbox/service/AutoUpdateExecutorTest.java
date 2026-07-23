package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AutoUpdateExecutorTest {

    @Test
    void scheduleWithJitterDefersByZeroToTwentyNineMinutes() {
        ScheduledExecutorService pool = mock(ScheduledExecutorService.class);
        AutoUpdateExecutor executor = new AutoUpdateExecutor(pool);

        executor.scheduleWithJitter(() -> {});

        verify(pool).schedule(any(Runnable.class), longThat(m -> m >= 0 && m < 30), eq(TimeUnit.MINUTES));
    }

    @Test
    void wrapsTaskSoExceptionsAreSwallowed() {
        ScheduledExecutorService pool = mock(ScheduledExecutorService.class);
        AutoUpdateExecutor executor = new AutoUpdateExecutor(pool);

        executor.scheduleWithJitter(() -> { throw new RuntimeException("boom"); });

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(pool).schedule(captor.capture(), anyLong(), eq(TimeUnit.MINUTES));
        assertDoesNotThrow(() -> captor.getValue().run());
    }
}
