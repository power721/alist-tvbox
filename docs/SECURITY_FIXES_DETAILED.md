# 🔍 AList-TvBox 安全修复详细文档

> **重要提示:** 本项目主要为内网部署、单用户使用场景设计。本文档中的修复分为两类：
> - ✅ **稳定性修复（必须）**: 防止应用崩溃、数据损坏
> - ⚠️ **安全防护（可选）**: 仅在公网暴露时需要

---

## 📋 目录

1. [部署场景分析](#部署场景分析)
2. [必须保留的修复（9个）](#必须保留的修复)
   - [SQL注入](#1-sql注入)
   - [命令注入](#2-命令注入)
   - [HTTP资源泄漏](#3-http资源泄漏)
   - [线程池泄漏](#4-线程池泄漏)
   - [竞态条件](#5-竞态条件)
   - [中断异常处理](#6-中断异常处理)
   - [空指针异常](#7-空指针异常)
   - [Zip Slip路径遍历](#8-zip-slip路径遍历)
   - [执行器服务泄漏](#9-执行器服务泄漏)
3. [可选的安全防护](#可选的安全防护)
4. [不推荐的过度防护](#不推荐的过度防护)

---

## 部署场景分析

### 典型部署环境
- 🏠 **内网部署**: 家庭/个人服务器
- 👤 **单用户使用**: 个人影音库管理
- 🔒 **无公网暴露**: 仅局域网访问
- 🛡️ **信任环境**: 家庭网络

### 威胁模型

| 威胁类型 | 可能性 | 影响 | 优先级 |
|---------|--------|------|--------|
| 外部攻击者 | ❌ 极低 | 高 | 低 |
| 暴力破解 | ❌ 极低 | 中 | 低 |
| 误操作数据损坏 | ✅ 中等 | 高 | **高** |
| 应用崩溃/资源泄漏 | ✅ 高 | 高 | **高** |
| 局域网未授权访问 | ⚠️ 低 | 低 | 中 |

**结论:** 稳定性和数据完整性是最重要的，安全防护次之。

---

## 必须保留的修复

这9个修复主要解决**稳定性和数据完整性**问题，即使在内网单用户环境也非常重要。

---

### 1. SQL注入

#### 📍 位置
`src/main/java/cn/har01d/alist_tvbox/service/AListLocalService.java:96`

#### 🔴 问题

**原始代码:**
```java
public void setSetting(String name, String value) {
    String sql = "REPLACE INTO x_settings VALUES('" + name + "', '" + value + "')";
    executeUpdate(sql);
}
```

**为什么在内网环境也需要修复:**
1. **防止误操作**: 用户输入包含单引号时会破坏SQL语句
2. **数据完整性**: 损坏的数据库需要重新导入，损失大
3. **代码质量**: PreparedStatement是Java标准做法

**实际场景示例:**
```java
// 用户设置一个包含单引号的值
setSetting("movie_path", "D:/Movies/Tom's Collection");

// 生成的SQL（错误）:
// REPLACE INTO x_settings VALUES('movie_path', 'D:/Movies/Tom's Collection')
//                                                           ^ 单引号打断字符串！

// 结果: SQL语法错误，设置失败
```

#### ✅ 修复方案

```java
public void setSetting(String name, String value) {
    String sql = "REPLACE INTO x_settings VALUES(?, ?)";
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setString(1, name);
        stmt.setString(2, value);
        stmt.executeUpdate();
    } catch (SQLException e) {
        log.error("Failed to set setting: {} = {}", name, value, e);
        throw new RuntimeException("Database operation failed", e);
    }
}
```

**修复效果:**
- ✅ 任何字符都能正确保存（单引号、双引号、特殊字符）
- ✅ 防止SQL语法错误
- ✅ 代码更安全、更专业

---

### 2. 命令注入

#### 📍 位置
`src/main/java/cn/har01d/alist_tvbox/service/IndexService.java:1029`

#### 🔴 问题

**原始代码:**
```java
private void updateXiaoyaIndexFile(String url) throws IOException {
    Runtime.getRuntime().exec("wget -O " + path + " " + url);
}
```

**为什么在内网环境也需要修复:**
1. **防止误操作**: 用户从网上复制粘贴URL时可能包含危险字符
2. **文件安全**: 可能意外删除重要文件
3. **更好的实现**: Java API比shell命令更可靠

**实际危险示例:**
```bash
# 用户从论坛复制了这个URL（不知道有恶意）:
url = "http://example.com/file.txt; rm -rf /opt/atv/data"

# 实际执行:
wget -O /data/index/file.txt http://example.com/file.txt; rm -rf /opt/atv/data
                                                            ^^^^^^^^^^^^^^^^^^^^^^^
                                                            删除所有数据！
```

#### ✅ 修复方案

```java
private void updateXiaoyaIndexFile(String url) throws IOException {
    // 1. 验证URL格式
    URI uri;
    try {
        uri = new URI(url);
    } catch (URISyntaxException e) {
        throw new IllegalArgumentException("Invalid URL format", e);
    }
    
    // 2. 白名单协议检查
    if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https")) {
        throw new IllegalArgumentException("Only HTTP/HTTPS protocols allowed");
    }
    
    // 3. 安全提取文件名
    String fileName = Paths.get(uri.getPath()).getFileName().toString();
    if (fileName.contains("..") || fileName.contains("/")) {
        throw new IllegalArgumentException("Invalid filename");
    }
    
    Path targetPath = Paths.get("/data/index", fileName);
    
    // 4. 使用Java API下载（不用shell）
    try (InputStream in = uri.toURL().openStream()) {
        Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("Downloaded index file: {}", fileName);
    }
}
```

**修复效果:**
- ✅ 完全避免shell命令执行
- ✅ 防止文件被意外删除
- ✅ 更可靠的错误处理

---

### 3. HTTP资源泄漏

#### 📍 位置
`src/main/java/cn/har01d/alist_tvbox/service/TelegramService.java:141`

#### 🔴 问题（稳定性问题）

**原始代码:**
```java
private String getHtml(String url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    // ... 使用connection
    return response.toString();
    // connection从未关闭！
}
```

**影响:**
- 每次调用都泄漏一个HTTP连接
- 长期运行后连接池耗尽
- 新请求失败: "Too many open files"
- **需要重启应用才能恢复**

**监控方法:**
```bash
# 查看打开的连接数
lsof -i -n | grep java | wc -l

# 正常: <100
# 泄漏: 持续增长，达到>1000
```

#### ✅ 修复方案

```java
private String getHtml(String url) throws IOException {
    HttpURLConnection connection = null;
    try {
        connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    } finally {
        // 确保连接总是被关闭
        if (connection != null) {
            connection.disconnect();
        }
    }
}
```

**修复效果:**
- ✅ 连接数稳定
- ✅ 长期运行稳定
- ✅ 不需要频繁重启

---

### 4. 线程池泄漏

#### 📍 位置
`src/main/java/cn/har01d/alist_tvbox/service/TelegramService.java:35`

#### 🔴 问题（稳定性问题）

**原始代码:**
```java
public List<Result> search(String query) {
    ExecutorService executor = Executors.newFixedThreadPool(10);
    
    // 提交任务...
    // 等待结果...
    
    return results;
    // executor从未关闭！每次搜索创建10个线程，永不回收
}
```

**影响演示:**
```java
// 用户搜索100次
for (int i = 0; i < 100; i++) {
    telegramService.search("movies");
}

// 线程数量:
// 第1次: 30个线程
// 第10次: 120个线程
// 第100次: 1020个线程！

// 系统影响:
// - CPU上下文切换开销激增
// - 内存: 1000线程 × 1MB = 1GB
// - 最终: OutOfMemoryError
// - 应用无法优雅关闭
```

#### ✅ 修复方案（复用线程池）

```java
@Service
public class TelegramService {
    // 在Bean初始化时创建，在销毁时关闭
    private final ExecutorService executor;
    
    public TelegramService() {
        this.executor = Executors.newFixedThreadPool(10);
    }
    
    public List<Result> search(String query) {
        // 复用同一个线程池
        List<Future<Result>> futures = new ArrayList<>();
        for (String source : sources) {
            futures.add(executor.submit(() -> searchSource(source, query)));
        }
        
        // 收集结果...
        return results;
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down TelegramService executor");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

**修复效果:**
- ✅ 线程数稳定在10个
- ✅ 内存使用正常
- ✅ 应用可优雅关闭
- ✅ 性能更好（线程复用）

---

### 5. 竞态条件

#### 📍 位置
`src/main/java/cn/har01d/alist_tvbox/service/ShareService.java:105`

#### 🔴 问题（数据完整性问题）

**原始代码:**
```java
private int shareId = 1;  // 非线程安全

public void createShare(ShareDto dto) {
    Share share = new Share();
    share.setId(shareId++);  // 竞态条件！
    shareRepository.save(share);
}
```

**为什么单用户也会有问题:**
- 现代浏览器会**并发发送多个请求**
- 可能同时创建多个分享链接
- 导致ID冲突和数据覆盖

**竞态条件演示:**
```
时间线:
线程1: 读取 shareId=100
线程2: 读取 shareId=100  （还是100！）
线程1: shareId++ = 101
线程2: shareId++ = 101  （重复！）
线程1: 保存 share.id=100
线程2: 保存 share.id=100  （冲突！）

结果: 两个Share记录有相同的ID，后者覆盖前者
```

#### ✅ 修复方案

```java
// 使用AtomicInteger保证原子性
private final AtomicInteger shareId = new AtomicInteger(1);

@PostConstruct
public void init() {
    int maxId = shareRepository.findMaxId().orElse(0);
    shareId.set(maxId + 1);
}

public void createShare(ShareDto dto) {
    Share share = new Share();
    share.setId(shareId.getAndIncrement());  // 原子操作
    shareRepository.save(share);
}
```

**修复效果:**
- ✅ ID严格递增，无重复
- ✅ 无数据覆盖
- ✅ 并发安全

---

### 6. 中断异常处理

#### 📍 位置
`src/main/java/cn/har01d/alist_tvbox/service/TelegramService.java:58`

#### 🔴 问题（代码质量问题）

**原始代码:**
```java
try {
    results.add(future.get());
} catch (InterruptedException e) {
    log.error("Interrupted", e);  // 吞掉中断，不做任何处理
}
```

**影响:**
- 应用关闭时无法停止正在运行的任务
- 需要 `kill -9` 强制杀进程
- 线程池污染

#### ✅ 修复方案

```java
try {
    results.add(future.get());
} catch (InterruptedException e) {
    // 恢复中断状态
    Thread.currentThread().interrupt();
    
    // 取消未完成的任务
    for (Future<Result> f : futures) {
        f.cancel(true);
    }
    break;
}
```

**修复效果:**
- ✅ 应用可优雅关闭
- ✅ 响应取消请求

---

### 7. 空指针异常

#### 📍 位置
`src/main/java/cn/har01d/alist_tvbox/service/AListService.java:150`

#### 🔴 问题

**原始代码:**
```java
List<FileItem> files = response.getContent().getFiles();
for (FileItem file : files) {  // files可能为null！
    // ...
}
```

**影响:**
- 当AList返回空响应时应用崩溃
- 用户体验差

#### ✅ 修复方案

```java
List<FileItem> files = response.getContent().getFiles();
if (files == null) {
    log.warn("Received null files list from AList");
    return Collections.emptyList();
}

for (FileItem file : files) {
    // ...
}
```

**修复效果:**
- ✅ 防止应用崩溃
- ✅ 优雅降级

---

### 8. Zip Slip路径遍历

#### 📍 位置
`src/main/java/cn/har01d/alist_tvbox/service/IndexService.java:890`

#### 🔴 问题

**原始代码:**
```java
ZipEntry entry = zipInputStream.getNextEntry();
File file = new File(destDir, entry.getName());  // 危险！
```

**为什么需要修复:**
- 即使不是恶意攻击，损坏的ZIP文件也可能破坏系统
- 可能覆盖系统文件

**攻击示例:**
```
ZIP文件中的路径:
../../../../etc/passwd

实际写入位置:
/data/index/../../../../etc/passwd
= /etc/passwd（覆盖系统文件！）
```

#### ✅ 修复方案

```java
ZipEntry entry = zipInputStream.getNextEntry();
File file = new File(destDir, entry.getName());

// 验证文件在目标目录内
if (!file.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
    throw new IOException("Zip Slip detected: " + entry.getName());
}
```

**修复效果:**
- ✅ 防止路径遍历
- ✅ 文件系统安全

---

### 9. 执行器服务泄漏

#### 📍 位置
`src/main/java/cn/har01d/alist_tvbox/service/IndexService.java:450`

#### 🔴 问题

**原始代码:**
```java
ExecutorService executor = Executors.newFixedThreadPool(5);
// 使用executor...
// 从未关闭
```

**影响:** 同第4个问题（线程池泄漏）

#### ✅ 修复方案

```java
ExecutorService executor = Executors.newFixedThreadPool(5);
try {
    // 使用executor...
} finally {
    executor.shutdown();
    try {
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```

**修复效果:**
- ✅ 资源正确释放
- ✅ 应用稳定

---

## 可选的安全防护

以下修复在**公网暴露**场景下有意义，内网部署可选择性保留。

### 10. 硬编码凭证（可选）

**位置:** `TokenFilter.java`

**修复:** 从数据库加载Basic Auth凭证

**内网场景:**
- 默认密码 `alist:alist` 风险较低
- 可以继续使用默认值

**公网场景:**
- **必须修改**默认密码
- 建议定期更换

### 11. SSRF防护（简化版）

**位置:** `PluginService.java`

**简化修复:** 只阻止 `localhost`, `127.0.0.1`, `169.254.x.x`

```java
private void validateUrl(String url) {
    URI uri = new URI(url);
    String host = uri.getHost();
    
    if (host.equals("localhost") || 
        host.equals("127.0.0.1") || 
        host.startsWith("169.254.")) {
        throw new BadRequestException("Access to localhost not allowed");
    }
}
```

### 12. 密码日志泄漏（推荐保留）

**位置:** Entity类的 `@ToString` 注解

**修复:** 排除敏感字段

```java
@ToString(exclude = {"password", "token", "cookie"})
public class User {
    // ...
}
```

**为什么保留:**
- 成本低（简单修改）
- 防止误贴日志到论坛求助时泄漏密码

### 13. 基本安全头部（简化版）

**保留的头部:**
```java
response.setHeader("X-Content-Type-Options", "nosniff");
response.setHeader("X-XSS-Protection", "1; mode=block");
```

**移除的头部:**
- CSP (Content-Security-Policy) - 内网不需要
- X-Frame-Options - 内网不需要

---

## 不推荐的过度防护

以下修复在内网单用户场景下**不推荐**使用，会增加复杂度或影响功能。

### ❌ 时序攻击防护

**原因:**
- 需要数千次网络测量
- 内网延迟不稳定，无法测量
- 单用户无人暴力破解

**建议:** 移除，使用简单的 `equals()` 比较

### ❌ 速率限制

**原因:**
- 单用户不会暴力破解自己
- 影响用户体验（合法快速操作被阻止）

**建议:** 移除 `RateLimitFilter.java`

### ❌ 安全审计日志

**原因:**
- 单用户无需审计追踪
- 增加日志量和性能开销

**建议:** 移除 `SecurityAuditFilter.java`

### ❌ 错误消息净化

**原因:**
- 详细错误信息有助于调试
- 隐藏错误反而增加问题排查难度

**建议:** 移除 `sanitizeErrorMessage()` 方法

### ❌ JSON请求大小限制

**原因:**
- DoS攻击在内网不适用
- multipart限制已经足够

**建议:** 移除 `RequestSizeLimitFilter.java`，保留 `application.yaml` 中的multipart配置

### ❌ 开放重定向防护

**原因:**
- 钓鱼攻击在内网不适用

**建议:** 移除此修复

---

## 总结

### 推荐方案

#### 内网部署（默认）
**必须保留:** 9个稳定性修复
**可选保留:** 密码日志泄漏修复
**可以移除:** 所有过度安全防护

#### 公网暴露（高级）
**必须保留:** 全部20个修复
**额外配置:**
- 修改默认密码
- 配置HTTPS
- 配置防火墙

### 修复数量对比

| 场景 | Critical | High | Medium | 总计 |
|------|----------|------|--------|------|
| 原始方案 | 5 | 7 | 8 | 20 |
| 内网推荐 | 4 | 5 | 1 | **10** |
| 公网推荐 | 5 | 7 | 8 | 20 |

### 用户配置建议

```markdown
# AList-TvBox 安全配置

## 内网部署（推荐）
✅ 可以使用默认密码
✅ 应用已内置稳定性保护
✅ 定期备份数据库

## 公网暴露（不推荐）
⚠️ 修改默认密码
⚠️ 配置HTTPS
⚠️ 限制访问IP
```

---

## 附录：修复对照表

| 提交 | 修复内容 | 类型 | 推荐保留 |
|------|---------|------|---------|
| 56c22589 | SQL注入 | Stability | ✅ 是 |
| 734692c9 | 硬编码凭证 | Security | ⚠️ 可选 |
| 3dd43f19 | 命令注入 | Stability | ✅ 是 |
| 4ec9627b | HTTP资源泄漏 | Stability | ✅ 是 |
| 60fa03a4 | 线程池泄漏 | Stability | ✅ 是 |
| 65efe4e4 | 竞态条件 | Stability | ✅ 是 |
| 6ceffda2 | 中断异常 | Quality | ✅ 是 |
| 50e3186a | 空指针 | Stability | ✅ 是 |
| 7a2fde98 | SSRF | Security | ⚠️ 简化 |
| 01ca3119 | Zip Slip | Stability | ✅ 是 |
| e63f8bea | 开放重定向 | Security | ❌ 移除 |
| aa349bbb | 执行器泄漏 | Stability | ✅ 是 |
| 96f848a2 | 密码日志泄漏 | Quality | ⚠️ 可选 |
| 3be46fe1 | 时序攻击 | Security | ❌ 移除 |
| 8d0af65a | 请求大小限制 | Security | ❌ 移除 |
| 970a0ae5 | 安全头部 | Security | ⚠️ 简化 |
| d86f1aaa | 错误消息净化 | Security | ❌ 移除 |
| b6f58d2b | 审计日志 | Security | ❌ 移除 |
| 94a48e15 | 速率限制 | Security | ❌ 移除 |

**图例:**
- ✅ 是：必须保留（稳定性）
- ⚠️ 可选：根据场景选择
- ❌ 移除：内网环境不需要

---

*文档版本: 1.0*  
*最后更新: 2026-06-16*
