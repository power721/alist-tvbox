package cn.har01d.alist_tvbox.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 健康检查和监控端点
 * 用于诊断生产环境偶发性 CPU 100% 问题
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final AtomicLong requestCounter = new AtomicLong(0);
    private static final Map<String, ApiMetrics> apiMetricsMap = new ConcurrentHashMap<>();

    /**
     * 系统指标
     * 访问: http://localhost:5244/api/health/metrics
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new HashMap<>();

        // 线程信息
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        result.put("threadCount", threadBean.getThreadCount());
        result.put("peakThreadCount", threadBean.getPeakThreadCount());
        result.put("daemonThreadCount", threadBean.getDaemonThreadCount());

        // 内存信息
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        result.put("maxMemoryMB", maxMemory / 1024 / 1024);
        result.put("totalMemoryMB", totalMemory / 1024 / 1024);
        result.put("usedMemoryMB", usedMemory / 1024 / 1024);
        result.put("freeMemoryMB", freeMemory / 1024 / 1024);
        result.put("memoryUsagePercent", (usedMemory * 100) / maxMemory);

        // 请求统计
        result.put("totalRequests", requestCounter.get());

        // API 调用统计（最近 100 个）
        List<Map<String, Object>> apiStats = new ArrayList<>();
        apiMetricsMap.forEach((api, metrics) -> {
            Map<String, Object> stat = new HashMap<>();
            stat.put("api", api);
            stat.put("count", metrics.count);
            stat.put("lastDurationMs", metrics.lastDurationMs);
            stat.put("maxDurationMs", metrics.maxDurationMs);
            stat.put("avgDurationMs", metrics.count > 0 ? metrics.totalDurationMs / metrics.count : 0);
            apiStats.add(stat);
        });
        // 按最大耗时排序
        apiStats.sort((a, b) -> Long.compare((Long) b.get("maxDurationMs"), (Long) a.get("maxDurationMs")));
        result.put("apiStats", apiStats);

        // 系统时间
        result.put("currentTimeMillis", System.currentTimeMillis());
        result.put("timestamp", new Date().toString());

        return result;
    }

    /**
     * 线程详情（包括 CPU 占用 Top 10）
     * 访问: http://localhost:5244/api/health/threads
     */
    @GetMapping("/threads")
    public Map<String, Object> threads() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        Map<String, Object> result = new HashMap<>();

        // 获取所有线程信息
        long[] threadIds = threadBean.getAllThreadIds();
        List<Map<String, Object>> threadList = new ArrayList<>();

        for (long threadId : threadIds) {
            ThreadInfo info = threadBean.getThreadInfo(threadId, 10);
            if (info != null) {
                Map<String, Object> threadInfo = new HashMap<>();
                threadInfo.put("id", info.getThreadId());
                threadInfo.put("name", info.getThreadName());
                threadInfo.put("state", info.getThreadState().toString());

                // CPU 时间（毫秒）
                long cpuTimeNs = threadBean.getThreadCpuTime(threadId);
                threadInfo.put("cpuTimeMs", cpuTimeNs > 0 ? cpuTimeNs / 1_000_000 : 0);

                // 如果线程阻塞或等待，记录详细信息
                if (info.getThreadState() == Thread.State.BLOCKED ||
                        info.getThreadState() == Thread.State.WAITING) {
                    threadInfo.put("lockName", info.getLockName());
                    threadInfo.put("lockOwner", info.getLockOwnerName());
                }

                // 记录堆栈（前 3 层）
                StackTraceElement[] stackTrace = info.getStackTrace();
                if (stackTrace != null && stackTrace.length > 0) {
                    List<String> stack = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, stackTrace.length); i++) {
                        stack.add(stackTrace[i].toString());
                    }
                    threadInfo.put("stackTrace", stack);
                }

                threadList.add(threadInfo);
            }
        }

        // 按 CPU 时间排序，取前 10
        threadList.sort((a, b) ->
                Long.compare((Long) b.get("cpuTimeMs"), (Long) a.get("cpuTimeMs")));

        result.put("topThreads", threadList.subList(0, Math.min(10, threadList.size())));
        result.put("totalThreads", threadList.size());

        // 统计线程状态
        Map<String, Long> stateCount = threadList.stream()
                .collect(HashMap::new,
                        (map, thread) -> {
                            String state = (String) thread.get("state");
                            map.merge(state, 1L, Long::sum);
                        },
                        HashMap::putAll);
        result.put("threadStates", stateCount);

        return result;
    }

    /**
     * 记录 API 调用（由拦截器调用）
     */
    public static void recordApiCall(String api, long durationMs) {
        requestCounter.incrementAndGet();

        ApiMetrics metrics = apiMetricsMap.computeIfAbsent(api, k -> new ApiMetrics());
        metrics.count++;
        metrics.lastDurationMs = durationMs;
        metrics.totalDurationMs += durationMs;
        metrics.maxDurationMs = Math.max(metrics.maxDurationMs, durationMs);

        // 如果耗时超过 5 秒，记录警告
        if (durationMs > 5000) {
            log.warn("⚠️ API 调用超时: {} 耗时 {}ms", api, durationMs);
        }

        // 保持最多 100 个 API 统计
        if (apiMetricsMap.size() > 100) {
            // 移除最久未使用的
            String oldestKey = apiMetricsMap.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().count))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldestKey != null) {
                apiMetricsMap.remove(oldestKey);
            }
        }
    }

    /**
     * API 指标
     */
    private static class ApiMetrics {
        long count = 0;
        long lastDurationMs = 0;
        long totalDurationMs = 0;
        long maxDurationMs = 0;
    }
}
