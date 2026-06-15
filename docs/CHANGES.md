# 生产环境 CPU 100% 监控与防护功能

## 概述

本次更新添加了完整的监控和防护机制，用于诊断和防止偶发性的 CPU 100% 问题。

## 问题背景

部分用户反馈打开订阅管理页面后容器 CPU 占用 100%，必须重启容器才能恢复。问题特征：
- 偶发性，无法稳定重现
- 订阅数量很少（几个）
- 用户运行在 Docker 环境，无调试工具
- 重启容器后恢复正常

## 改动内容

### 1. 新增健康检查端点

#### `/api/health/metrics` - 系统指标

返回内容示例：
```json
{
  "threadCount": 45,
  "peakThreadCount": 52,
  "maxMemoryMB": 2048,
  "usedMemoryMB": 512,
  "memoryUsagePercent": 25,
  "totalRequests": 1234,
  "apiStats": [
    {
      "api": "GET /api/subscriptions",
      "count": 10,
      "lastDurationMs": 123,
      "maxDurationMs": 5678,
      "avgDurationMs": 234
    }
  ]
}
```

#### `/api/health/threads` - 线程详情

返回 CPU 占用最高的 10 个线程，包括：
- 线程名称和状态
- CPU 时间（毫秒）
- 堆栈信息（前3层）
- 锁信息（如果阻塞）

### 2. API 监控拦截器

**新增类**：`ApiMonitorInterceptor`

功能：
- 记录所有 `/api/**` 请求的耗时
- 超过 3 秒的请求自动记录警告日志
- 数据汇总到 `/api/health/metrics`

日志示例：
```
⚠️ 慢请求: GET /api/subscriptions 耗时 5234ms
```

### 3. 关键 API 超时保护

#### `SubscriptionController.findAll()`

```java
@GetMapping
@Transactional(timeout = 10)  // 10秒强制超时
public List<Subscription> findAll() {
    // 添加详细日志
    // 检查异常数据
    // 超时或异常时返回空列表而不是抛异常
}
```

日志示例：
```
📋 开始查询订阅列表
✅ 订阅列表查询成功: 3 条, 耗时 123ms
⚠️ 发现超大 override 字段: id=1, size=512KB
```

#### `TvBoxController.devices()`

```java
@GetMapping("/api/devices")
@Transactional(timeout = 5)  // 5秒超时
public List<Device> devices() {
    // 添加日志和异常处理
}
```

### 4. 断路器（CircuitBreaker）

**新增类**：`CircuitBreaker`

功能：
- 连续失败 3 次 → 打开断路器
- 断路器打开后拒绝请求 60 秒
- 冷却期后进入半开状态，允许尝试
- 连续成功 2 次 → 关闭断路器

### 5. 前端防护

#### 新增文件：`web-ui/src/utils/api.ts`

功能：
- 请求超时：15 秒
- 自动记录慢请求（>5秒）
- 友好的错误提示
- 统一的错误处理

#### `SubscriptionsView.vue` 改进

添加详细的控制台日志和数据大小检查。

## 使用方法

### 用户 - 问题诊断

当遇到 CPU 100% 时：

1. 打开浏览器新标签页访问：`http://你的地址:5244/api/health/metrics`
2. 复制页面内容
3. 访问：`http://你的地址:5244/api/health/threads`
4. 复制页面内容
5. 导出容器日志：`docker logs alist-tvbox --tail=200 > logs.txt`
6. 将以上信息提交给开发者

详细说明见 `USER_GUIDE.md`

## 文件清单

### 后端新增文件

- `src/main/java/cn/har01d/alist_tvbox/web/HealthController.java`
- `src/main/java/cn/har01d/alist_tvbox/config/ApiMonitorInterceptor.java`
- `src/main/java/cn/har01d/alist_tvbox/config/WebMvcConfig.java`
- `src/main/java/cn/har01d/alist_tvbox/service/CircuitBreaker.java`

### 后端修改文件

- `src/main/java/cn/har01d/alist_tvbox/web/SubscriptionController.java`
- `src/main/java/cn/har01d/alist_tvbox/web/TvBoxController.java`

### 前端新增文件

- `web-ui/src/utils/api.ts`

### 前端修改文件

- `web-ui/src/views/SubscriptionsView.vue`

## 向后兼容性

- ✅ 完全向后兼容
- ✅ 不修改现有 API 接口
- ✅ 不修改数据库结构
- ✅ 不影响现有功能
