# 生产环境 CPU 100% 监控与防护方案

## 问题特征
- 用户环境偶发性出现
- 用户是小白，无法提供诊断信息
- 运行在 Docker 容器中，没有调试工具
- 必须重启容器才能恢复

## 解决策略

**无法重现 = 必须主动防御 + 自动诊断 + 优雅降级**

---

## 方案1: 添加健康检查端点（立即部署）

### 1.1 创建健康监控端点

```java
// src/main/java/cn/har01d/alist_tvbox/web/HealthController.java
package cn.har01d.alist_tvbox.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/api/health")
public class HealthController {
    
    private static final AtomicLong requestCounter = new AtomicLong(0);
    private static final Map<String, Long> apiCallTimes = new HashMap<>();
    
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new HashMap<>();
        
        // CPU 信息
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
        result.put("usedMemoryMB", usedMemory / 1024 / 1024);
        result.put("memoryUsagePercent", (usedMemory * 100) / maxMemory);
        
        // 请求计数
        result.put("totalRequests", requestCounter.get());
        result.put("apiCallTimes", new HashMap<>(apiCallTimes));
        
        return result;
    }
    
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
                threadInfo.put("cpuTime", threadBean.getThreadCpuTime(threadId) / 1_000_000); // ms
                
                // 如果线程阻塞或等待，记录详细信息
                if (info.getThreadState() == Thread.State.BLOCKED || 
                    info.getThreadState() == Thread.State.WAITING) {
                    threadInfo.put("lockName", info.getLockName());
                    threadInfo.put("lockOwner", info.getLockOwnerName());
                }
                
                threadList.add(threadInfo);
            }
        }
        
        // 按 CPU 时间排序，取前 10
        threadList.sort((a, b) -> 
            Long.compare((Long) b.get("cpuTime"), (Long) a.get("cpuTime")));
        
        result.put("threads", threadList.subList(0, Math.min(10, threadList.size())));
        result.put("totalThreads", threadList.size());
        
        return result;
    }
    
    public static void recordApiCall(String api, long durationMs) {
        requestCounter.incrementAndGet();
        apiCallTimes.put(api, durationMs);
        
        // 如果耗时超过 5 秒，记录警告
        if (durationMs > 5000) {
            log.warn("⚠️ API 调用超时: {} 耗时 {}ms", api, durationMs);
        }
    }
}
```

### 1.2 添加拦截器记录所有 API 耗时

```java
// src/main/java/cn/har01d/alist_tvbox/config/ApiMonitorInterceptor.java
package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.web.HealthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class ApiMonitorInterceptor implements HandlerInterceptor {
    
    private static final ThreadLocal<Long> startTime = new ThreadLocal<>();
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        startTime.set(System.currentTimeMillis());
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, 
                                Object handler, Exception ex) {
        Long start = startTime.get();
        if (start != null) {
            long duration = System.currentTimeMillis() - start;
            String api = request.getRequestURI();
            
            // 记录到健康检查端点
            HealthController.recordApiCall(api, duration);
            
            // 如果超过 3 秒，打印日志
            if (duration > 3000) {
                log.warn("⚠️ 慢请求: {} 耗时 {}ms", api, duration);
            }
            
            startTime.remove();
        }
    }
}
```

### 1.3 注册拦截器

```java
// src/main/java/cn/har01d/alist_tvbox/config/WebMvcConfig.java
package cn.har01d.alist_tvbox.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    
    @Autowired
    private ApiMonitorInterceptor apiMonitorInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiMonitorInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health/**");  // 排除健康检查
    }
}
```

---

## 方案2: 为关键 API 添加超时保护

### 2.1 订阅列表 API 添加超时

```java
// SubscriptionController.java
@GetMapping
@Transactional(timeout = 10)  // 10秒超时
public List<Subscription> findAll() {
    long start = System.currentTimeMillis();
    log.info("📋 开始查询订阅列表");
    
    try {
        List<Subscription> result = subscriptionService.findAll();
        long duration = System.currentTimeMillis() - start;
        
        log.info("✅ 订阅列表查询成功: {} 条, 耗时 {}ms", result.size(), duration);
        
        // 检查是否有异常数据
        for (Subscription sub : result) {
            if (sub.getOverride() != null && sub.getOverride().length() > 100_000) {
                log.warn("⚠️ 发现超大 override 字段: id={}, size={}KB", 
                    sub.getId(), sub.getOverride().length() / 1024);
            }
        }
        
        return result;
    } catch (Exception e) {
        long duration = System.currentTimeMillis() - start;
        log.error("❌ 订阅列表查询失败, 耗时 {}ms", duration, e);
        throw e;
    }
}
```

### 2.2 设备列表 API 添加超时

```java
// TvBoxController.java
@GetMapping("/api/devices")
@Transactional(timeout = 5)  // 5秒超时
public List<Device> devices() {
    long start = System.currentTimeMillis();
    log.info("📱 开始查询设备列表");
    
    try {
        List<Device> result = deviceRepository.findAll();
        long duration = System.currentTimeMillis() - start;
        
        log.info("✅ 设备列表查询成功: {} 个, 耗时 {}ms", result.size(), duration);
        return result;
    } catch (Exception e) {
        long duration = System.currentTimeMillis() - start;
        log.error("❌ 设备列表查询失败, 耗时 {}ms", duration, e);
        throw e;
    }
}
```

---

## 方案3: 添加断路器防止雪崩

```java
// src/main/java/cn/har01d/alist_tvbox/service/CircuitBreaker.java
package cn.har01d.alist_tvbox.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
```

### 使用断路器

```java
// SubscriptionService.java
@Service
public class SubscriptionService {
    
    @Autowired
    private CircuitBreaker circuitBreaker;
    
    public List<Subscription> findAll() {
        String circuitName = "subscription-findAll";
        
        // 检查断路器
        if (circuitBreaker.isOpen(circuitName)) {
            log.warn("断路器打开，返回空列表");
            return Collections.emptyList();
        }
        
        try {
            List<Subscription> result = subscriptionRepository.findAll();
            circuitBreaker.recordSuccess(circuitName);
            return result;
        } catch (Exception e) {
            circuitBreaker.recordFailure(circuitName);
            throw e;
        }
    }
}
```

---

## 方案4: 前端添加防护

### 4.1 创建带超时和重试的 axios 实例

```typescript
// web-ui/src/utils/api.ts
import axios from 'axios'
import { ElMessage } from 'element-plus'

// 创建实例
const api = axios.create({
  timeout: 15000,  // 15秒超时
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器
api.interceptors.request.use(
  config => {
    config.metadata = { startTime: Date.now() }
    return config
  },
  error => Promise.reject(error)
)

// 响应拦截器
api.interceptors.response.use(
  response => {
    const duration = Date.now() - response.config.metadata.startTime
    if (duration > 5000) {
      console.warn(`⚠️ 慢请求: ${response.config.url} 耗时 ${duration}ms`)
    }
    return response
  },
  error => {
    if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请稍后重试')
      console.error('超时:', error.config.url)
    } else if (error.response?.status === 504) {
      ElMessage.error('服务器响应超时')
    } else if (!error.response) {
      ElMessage.error('网络连接失败')
    }
    return Promise.reject(error)
  }
)

export default api
```

### 4.2 使用防护 API

```typescript
// SubscriptionsView.vue
import api from '@/utils/api'

// 添加防重复请求
const loadingPromises = new Map<string, Promise<any>>()

const load = async () => {
  const key = 'subscriptions'
  
  // 防止重复请求
  if (loadingPromises.has(key)) {
    console.log('订阅列表正在加载中，跳过重复请求')
    return loadingPromises.get(key)
  }
  
  loading.value = true
  const promise = api.get('/api/subscriptions')
    .then(({data}) => {
      console.log('✅ 订阅加载成功:', data.length, '条')
      subscriptions.value = data
      return data
    })
    .catch((error) => {
      console.error('❌ 订阅加载失败:', error)
      ElMessage.error('加载订阅失败，请刷新页面重试')
      throw error
    })
    .finally(() => {
      loading.value = false
      loadingPromises.delete(key)
    })
  
  loadingPromises.set(key, promise)
  return promise
}
```

---

## 方案5: Docker 容器监控

### 5.1 添加健康检查到 Docker

```dockerfile
# Dockerfile 添加
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:5244/api/health/metrics || exit 1
```

### 5.2 添加资源限制

```yaml
# docker-compose.yml
services:
  alist-tvbox:
    image: haroldli/alist-tvbox:latest
    container_name: alist-tvbox
    deploy:
      resources:
        limits:
          cpus: '2.0'      # 限制 CPU
          memory: 2G       # 限制内存
        reservations:
          cpus: '0.5'
          memory: 512M
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:5244/api/health/metrics"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
```

### 5.3 添加自动重启脚本

```bash
#!/bin/bash
# monitor.sh - 放在容器外部

CONTAINER_NAME="alist-tvbox"
MAX_CPU=80  # CPU 超过 80% 触发重启

while true; do
    # 获取容器 CPU 使用率
    CPU=$(docker stats --no-stream --format "{{.CPUPerc}}" $CONTAINER_NAME | sed 's/%//')
    
    if (( $(echo "$CPU > $MAX_CPU" | bc -l) )); then
        echo "[$(date)] ⚠️ CPU 使用率 ${CPU}% 超过阈值，准备重启容器..."
        
        # 尝试获取健康信息
        docker exec $CONTAINER_NAME curl -s http://localhost:5244/api/health/threads > /tmp/threads_$(date +%s).json
        
        # 重启容器
        docker restart $CONTAINER_NAME
        
        echo "[$(date)] ✅ 容器已重启"
        sleep 300  # 冷却 5 分钟
    fi
    
    sleep 30  # 每 30 秒检查一次
done
```

---

## 方案6: 日志增强

### 6.1 添加结构化日志

```xml
<!-- logback-spring.xml -->
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/data/logs/alist-tvbox.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/data/logs/alist-tvbox-%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- 慢请求单独记录 -->
    <appender name="SLOW_API" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/data/logs/slow-api.log</file>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>WARN</level>
        </filter>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/data/logs/slow-api-%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <logger name="cn.har01d.alist_tvbox.config.ApiMonitorInterceptor" level="WARN" additivity="false">
        <appender-ref ref="SLOW_API" />
    </logger>
    
    <root level="INFO">
        <appender-ref ref="FILE" />
    </root>
</configuration>
```

---

## 部署清单

### 立即部署（优先级 P0）
- [ ] 添加 `HealthController` 和 `/api/health/metrics` 端点
- [ ] 为 `SubscriptionController.findAll()` 添加 `@Transactional(timeout=10)`
- [ ] 为 `TvBoxController.devices()` 添加 `@Transactional(timeout=5)`
- [ ] 前端 `SubscriptionsView.vue` 使用带超时的 axios

### 短期部署（优先级 P1）
- [ ] 添加 `ApiMonitorInterceptor` 记录所有 API 耗时
- [ ] 添加 `CircuitBreaker` 断路器
- [ ] 前端添加防重复请求逻辑

### 中期优化（优先级 P2）
- [ ] Docker 添加健康检查和资源限制
- [ ] 添加结构化日志
- [ ] 部署外部监控脚本

## 用户使用指南

向用户提供以下说明：

```markdown
# 如果遇到页面卡死问题

1. **立即操作**：
   - 打开新标签页访问：http://你的地址:5244/api/health/metrics
   - 复制页面内容发给开发者

2. **查看日志**：
   ```bash
   docker logs alist-tvbox --tail=100 > logs.txt
   ```
   将 logs.txt 发给开发者

3. **临时恢复**：
   ```bash
   docker restart alist-tvbox
   ```

4. **预防措施**：
   - 定期重启容器（每周一次）
   - 不要同时打开多个订阅页面
```

---

这套方案部署后，即使无法重现问题，也能在用户环境自动收集诊断信息。