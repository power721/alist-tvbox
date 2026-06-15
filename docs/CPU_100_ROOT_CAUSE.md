# 订阅页面 CPU 100% 根本原因分析

## 问题特征
- 用户打开订阅管理页面
- 没有任何操作
- CPU 占用 100%
- 必须重启容器才能恢复
- 订阅数量很少（只有几个）
- 新功能（GitHub代理、Telegram登录）已被排除

## 最可能的原因

### 1. JSON 循环引用导致序列化死循环 ⭐⭐⭐（最可能）

**症状**：后端 API 响应时 CPU 100%，前端永远收不到响应

**原因**：
订阅对象可能包含循环引用，Jackson 序列化时进入无限递归：

```java
// Subscription 实体类
public class Subscription {
    private Integer id;
    private String override;  // JSON 字符串
    // ...
}

// 如果 override 字段本身引用了 Subscription 对象
// 或者通过 JPA 关联关系形成循环引用
```

**诊断方法**：

```bash
# 1. 查看 Subscription 实体类定义
grep -A 30 "class Subscription" src/main/java/cn/har01d/alist_tvbox/entity/Subscription.java

# 2. 查看是否有双向关联
grep -n "@ManyToOne\|@OneToMany\|@ManyToMany" src/main/java/cn/har01d/alist_tvbox/entity/Subscription.java

# 3. 测试 API（添加超时）
timeout 5 curl http://localhost:5244/api/subscriptions || echo "超时！"
```

**修复方案**：

```java
// 在 Subscription 实体类中添加 @JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
public class Subscription {
    // 对可能导致循环引用的字段添加 @JsonIgnore
    @JsonIgnore
    @OneToMany(mappedBy = "subscription")
    private List<SomeRelatedEntity> relatedEntities;
}

// 或者使用 @JsonManagedReference 和 @JsonBackReference
```

### 2. 数据库连接池耗尽 ⭐⭐

**症状**：第一次打开正常，后续打开卡死

**原因**：
- 某个查询没有释放连接
- 连接泄漏累积
- 连接池配置过小

**诊断方法**：

```bash
# 查看数据库连接配置
grep -rn "spring.datasource\|hikari" src/main/resources/application.properties

# 检查是否有未关闭的 ResultSet 或 Statement
grep -rn "ResultSet\|Statement\|Connection" src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java
```

**修复方案**：

```properties
# application.properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
spring.datasource.hikari.leak-detection-threshold=60000
```

### 3. 缓存无限增长 ⭐⭐

**症状**：运行一段时间后 CPU 逐渐升高

**原因**：
TelegramService 中的 Caffeine 缓存没有正确配置最大容量：

```java
// 可能的问题代码
private final LoadingCache<String, List<Message>> searchCache = 
    Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(15))
    // ❌ 没有设置 maximumSize！
    .build(this::getFromChannel);
```

**修复方案**：

```java
private final LoadingCache<String, List<Message>> searchCache = 
    Caffeine.newBuilder()
    .maximumSize(1000)  // ✅ 添加最大容量
    .expireAfterWrite(Duration.ofMinutes(15))
    .build(this::getFromChannel);
```

### 4. 线程池任务堆积 ⭐

**症状**：容器运行一段时间后打开页面卡死

**原因**：

```java
// TelegramService.java:96
private final ExecutorService executorService = 
    Executors.newFixedThreadPool(Math.min(10, Runtime.getRuntime().availableProcessors() * 2));
```

如果有任务一直阻塞不返回，线程池会被占满。

**诊断方法**：

```bash
# 线程堆栈中查看线程状态
jstack $(pgrep java) | grep -A 20 "TelegramService"
```

**修复方案**：

```java
// 使用带超时的 Future
Future<List<Message>> future = executorService.submit(() -> searchFromChannel(...));
try {
    return future.get(10, TimeUnit.SECONDS);  // 10秒超时
} catch (TimeoutException e) {
    future.cancel(true);
    throw new RuntimeException("查询超时");
}
```

### 5. 前端响应式陷阱（低概率）⭐

**可疑代码**：

```typescript
// 行 1013
const rawJsonData = computed(() => JSON.stringify(jsonData.value, null, 2))

// 如果 jsonData.value 是一个巨大的对象或有循环引用
// JSON.stringify 可能卡死
```

**修复方案**：

```typescript
const rawJsonData = computed(() => {
  try {
    return JSON.stringify(jsonData.value, null, 2)
  } catch (e) {
    console.error('JSON序列化失败:', e)
    return '{ "error": "无法序列化" }'
  }
})
```

## 优先诊断步骤

### 步骤1：确定是前端还是后端卡死

在浏览器开发者工具 Network 中观察：

- **如果 `/api/subscriptions` 请求一直 pending** → 后端问题
- **如果请求很快返回但页面卡死** → 前端问题

### 步骤2：如果是后端问题

```bash
# 当问题复现时，立即执行：
jstack $(pgrep java) > /tmp/thread_dump_$(date +%s).txt

# 查找卡死的线程
grep -A 30 "http-nio\|qtp\|catalina" /tmp/thread_dump_*.txt

# 查看是否有大量 WAITING 或 BLOCKED 线程
grep "java.lang.Thread.State:" /tmp/thread_dump_*.txt | sort | uniq -c
```

### 步骤3：添加临时日志

**SubscriptionController.java**：

```java
@GetMapping
public List<Subscription> findAll() {
    log.info("=== 开始查询订阅列表 ===");
    long start = System.currentTimeMillis();
    try {
        List<Subscription> result = subscriptionService.findAll();
        log.info("=== 订阅列表查询成功，数量: {}, 耗时: {}ms ===", 
            result.size(), System.currentTimeMillis() - start);
        
        // 检查是否有异常大的数据
        for (Subscription sub : result) {
            if (sub.getOverride() != null && sub.getOverride().length() > 100000) {
                log.warn("!!! 发现异常大的 override 字段: id={}, size={}bytes", 
                    sub.getId(), sub.getOverride().length());
            }
        }
        
        return result;
    } catch (Exception e) {
        log.error("=== 订阅列表查询失败，耗时: {}ms ===", 
            System.currentTimeMillis() - start, e);
        throw e;
    }
}
```

### 步骤4：如果是前端问题

```typescript
// 在 SubscriptionsView.vue 中添加
const load = () => {
  console.log('[LOAD] 开始加载订阅列表')
  console.time('load-subscriptions')
  loading.value = true
  
  axios.get('/api/subscriptions')
    .then(({data}) => {
      console.log('[LOAD] 收到数据:', data.length, '条')
      console.log('[LOAD] 数据样本:', data[0])
      
      // 检查数据大小
      const dataSize = JSON.stringify(data).length
      console.log('[LOAD] 数据大小:', (dataSize / 1024).toFixed(2), 'KB')
      
      if (dataSize > 1024 * 1024) {
        console.error('[LOAD] ⚠️ 数据过大！超过 1MB')
      }
      
      subscriptions.value = data
      console.timeEnd('load-subscriptions')
    })
    .catch(err => {
      console.error('[LOAD] 加载失败:', err)
    })
    .finally(() => {
      loading.value = false
      console.log('[LOAD] 加载完成')
    })
}
```

## 快速修复方案（临时缓解）

如果无法立即定位问题，可以先添加：

### 1. API 超时保护

```java
// 在 SubscriptionController 前添加
@RestController
@RequestMapping("/api/subscriptions")
@Transactional(timeout = 10)  // 10秒超时
public class SubscriptionController {
    // ...
}
```

### 2. 前端请求超时

```typescript
// 创建带超时的 axios 实例
const api = axios.create({
  timeout: 10000,  // 10秒超时
})

// 在 load() 中使用
const load = () => {
  loading.value = true
  api.get('/api/subscriptions')
    .then(({data}) => {
      subscriptions.value = data
    })
    .catch(err => {
      if (err.code === 'ECONNABORTED') {
        ElMessage.error('加载超时，请刷新页面重试')
      }
    })
    .finally(() => {
      loading.value = false
    })
}
```

### 3. 添加断路器

```java
@Service
public class SubscriptionService {
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
    private volatile long lastFailTime = 0;
    
    public List<Subscription> findAll() {
        // 如果断路器打开且在冷却期内，直接返回空列表
        if (circuitOpen.get() && 
            System.currentTimeMillis() - lastFailTime < 60000) {
            log.warn("断路器打开，拒绝请求");
            return Collections.emptyList();
        }
        
        try {
            List<Subscription> result = subscriptionRepository.findAll();
            circuitOpen.set(false);  // 成功后关闭断路器
            return result;
        } catch (Exception e) {
            circuitOpen.set(true);  // 失败时打开断路器
            lastFailTime = System.currentTimeMillis();
            log.error("查询失败，断路器打开", e);
            throw e;
        }
    }
}
```

## 下一步

1. **立即执行**：问题复现时收集线程堆栈
2. **添加日志**：确认是哪个 API 导致的
3. **检查实体类**：Subscription 是否有循环引用
4. **检查缓存配置**：TelegramService 的缓存是否无限增长

需要我根据收集到的信息进一步分析吗？
