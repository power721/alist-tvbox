# 订阅页面 CPU 100% 问题诊断方案

## 问题描述
用户反馈打开订阅管理页面（`/subscriptions`）可能导致 CPU 占用 100%，必须重启容器才能恢复。

## 诊断步骤

### 1. 确认问题现场

当 CPU 100% 时，在容器内执行：

```bash
# 查看 Java 进程 CPU 占用
top -b -n 1 | head -20

# 查看具体线程 CPU 占用
top -H -b -n 1 -p $(pgrep java) | head -30

# 获取线程堆栈（找到占用 CPU 最高的线程 ID，转为16进制）
jstack $(pgrep java) > /tmp/thread_dump.txt

# 示例：如果线程 ID 是 12345
# 转16进制: printf "%x\n" 12345  # 输出: 3039
# 在 thread_dump.txt 中搜索 nid=0x3039
```

### 2. 检查前端是否有残留的轮询

在浏览器开发者工具中：

```javascript
// 打开 Console，粘贴以下代码
// 1. 查看活跃的定时器数量
let timers = 0;
const originalSetInterval = window.setInterval;
const originalSetTimeout = window.setTimeout;
window.setInterval = function(...args) {
  timers++;
  console.log('New interval created, total:', timers);
  return originalSetInterval(...args);
};
window.setTimeout = function(...args) {
  timers++;
  console.log('New timeout created, total:', timers);
  return originalSetTimeout(...args);
};

// 2. 查看网络请求频率
// 在 Network 标签页中观察是否有高频请求（每秒多次）
// 特别关注：
// - /api/settings/tg_phase
// - /api/settings/github-proxy/benchmark/results
// - /api/subscriptions
```

### 3. 检查后端 API 响应时间

在服务器上：

```bash
# 安装 httpie（如果没有）
apt-get install -y httpie

# 测试各个 API 的响应时间
time curl -H "Authorization: Bearer YOUR_TOKEN" http://localhost:5244/api/subscriptions
time curl -H "Authorization: Bearer YOUR_TOKEN" http://localhost:5244/api/token
time curl -H "Authorization: Bearer YOUR_TOKEN" http://localhost:5244/api/devices
time curl http://localhost:5244/pg/version
time curl http://localhost:5244/zx/version

# 如果某个 API 超过 5 秒，说明有问题
```

### 4. 检查数据库

```bash
# 进入容器
docker exec -it <container_name> bash

# 连接数据库（H2）
java -cp /app/lib/h2-*.jar org.h2.tools.Shell -url jdbc:h2:/data/alist_tvbox -user sa

# 执行以下查询
SELECT COUNT(*) FROM subscription;
SELECT COUNT(*) FROM subscription_source;
SELECT COUNT(*) FROM plugin;
SELECT COUNT(*) FROM plugin_filter;
SELECT COUNT(*) FROM device;

# 如果订阅数量超过 1000，可能是问题根源
```

### 5. 添加日志诊断

临时修改代码添加日志：

**SubscriptionController.java**

```java
@GetMapping
public List<Subscription> findAll() {
    long start = System.currentTimeMillis();
    log.info("开始查询订阅列表");
    try {
        List<Subscription> result = subscriptionService.findAll();
        log.info("订阅列表查询完成，数量: {}, 耗时: {}ms", result.size(), System.currentTimeMillis() - start);
        return result;
    } catch (Exception e) {
        log.error("订阅列表查询失败，耗时: {}ms", System.currentTimeMillis() - start, e);
        throw e;
    }
}
```

**SubscriptionsView.vue**

```typescript
const load = () => {
  console.time('load-subscriptions')
  loading.value = true
  axios.get('/api/subscriptions').then(({data}) => {
    console.log('订阅数据:', data.length, '条')
    subscriptions.value = data
    console.timeEnd('load-subscriptions')
  }).finally(() => {
    loading.value = false
  })
}
```

## 已知可能的问题点

### 问题1: 订阅数据过大导致前端渲染卡死

**症状**：
- 后端 API 响应正常（< 1秒）
- 前端浏览器 CPU 100%
- 页面卡死无响应

**原因**：
- 订阅表格数据量过大（几百上千条）
- 每条订阅包含多个 URL 字段
- el-table 渲染大量 DOM 导致浏览器卡死

**解决方案**：
- 添加分页（推荐）
- 添加虚拟滚动
- 限制订阅数量

### 问题2: 计算属性无限重算

**可疑代码（行 1070-1077）**：

```typescript
const filteredManagedSources = computed(() => {
  const keyword = sourceFilter.value.trim().toLowerCase()
  if (!keyword) return managedSources.value
  return managedSources.value.filter(item =>
    (item.name && item.name.toLowerCase().includes(keyword)) ||
    (item.url && item.url.toLowerCase().includes(keyword))
  )
})
```

**如果 `managedSources` 是响应式数组且频繁变化，可能触发无限重算。**

### 问题3: 后端死锁或无限递归

**需要检查的方法**：
- `SubscriptionService.findAll()`
- `SubscriptionService.getGlobalConfig()`
- `SubscriptionService.getCatalog()`

**特别关注**：
- 是否有循环依赖注入
- 是否有递归调用
- 是否有未释放的锁

### 问题4: TelegramService 线程池耗尽

**代码（行96）**：

```java
private final ExecutorService executorService = 
    Executors.newFixedThreadPool(Math.min(10, Runtime.getRuntime().availableProcessors() * 2));
```

**如果有大量任务提交到线程池但未正确处理超时，可能导致：**
- 线程全部阻塞
- 新任务堆积在队列中
- CPU 100% 空转

**检查方法**：

```bash
# 在线程堆栈中查找 TelegramService
grep -A 20 "TelegramService" /tmp/thread_dump.txt
```

## 建议的修复补丁

### 补丁1: 添加订阅列表分页

```typescript
// SubscriptionsView.vue
const currentPage = ref(1)
const pageSize = ref(50)
const paginatedSubscriptions = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return subscriptions.value.slice(start, end)
})

// 模板修改
<el-table :data="paginatedSubscriptions" ...>

<el-pagination
  v-model:current-page="currentPage"
  v-model:page-size="pageSize"
  :total="subscriptions.value.length"
  layout="total, prev, pager, next"
/>
```

### 补丁2: 添加API请求超时和错误处理

```typescript
// 创建带超时的 axios 实例
const api = axios.create({
  timeout: 10000, // 10秒超时
})

api.interceptors.response.use(
  response => response,
  error => {
    if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查网络连接')
    }
    return Promise.reject(error)
  }
)

// 使用 api 替换 axios
const load = () => {
  loading.value = true
  api.get('/api/subscriptions')
    .then(({data}) => {
      subscriptions.value = data
    })
    .catch((error) => {
      console.error('加载订阅失败:', error)
      ElMessage.error('加载订阅失败: ' + error.message)
    })
    .finally(() => {
      loading.value = false
    })
}
```

### 补丁3: 防止重复请求

```typescript
let loadingPromise: Promise<any> | null = null

const load = () => {
  // 如果正在加载，返回现有 Promise
  if (loadingPromise) {
    console.log('订阅列表正在加载中，跳过重复请求')
    return loadingPromise
  }

  loading.value = true
  loadingPromise = axios.get('/api/subscriptions')
    .then(({data}) => {
      subscriptions.value = data
    })
    .finally(() => {
      loading.value = false
      loadingPromise = null
    })
  
  return loadingPromise
}
```

## 临时缓解措施

如果问题频繁发生，建议：

1. **限制订阅数量**：在后端添加最大订阅数限制（如 100 条）
2. **增加容器资源**：CPU 限制改为 2 核以上
3. **添加监控**：使用 Prometheus + Grafana 监控 CPU/内存
4. **定期重启**：临时方案，每天凌晨自动重启容器

## 下一步行动

请按以下优先级执行：

1. **立即执行**：在问题复现时，收集线程堆栈和浏览器网络请求日志
2. **短期**：添加分页和请求超时
3. **中期**：优化后端查询性能，添加缓存
4. **长期**：重构订阅管理页面，使用虚拟滚动

---

**需要进一步帮助，请提供：**
- 线程堆栈文件（`jstack` 输出）
- 浏览器 Network 请求截图
- 数据库订阅数量
- 容器资源配置（CPU/内存限制）
