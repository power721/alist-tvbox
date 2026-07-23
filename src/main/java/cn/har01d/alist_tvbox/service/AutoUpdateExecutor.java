package cn.har01d.alist_tvbox.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AutoUpdateExecutor {
    private final ScheduledExecutorService executor;

    public AutoUpdateExecutor() {
        this(Executors.newScheduledThreadPool(2));
    }

    AutoUpdateExecutor(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    public void scheduleWithJitter(Runnable task) {
        long minutes = ThreadLocalRandom.current().nextLong(0, 30); // 0..29
        executor.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warn("auto-update task failed", e);
            }
        }, minutes, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
