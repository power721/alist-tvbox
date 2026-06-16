# 115网盘离线索引系统设计

**创建日期：** 2026-06-16  
**目标：** 实现类似webdavsim的离线索引机制，支持260万+文件高效搜索，规避115风控  
**最终方案：** 集成到telegram-search，提供独立搜索服务

---

## 1. 核心问题与解决方案

### 问题1：如何高效索引260万+文件？
**方案：** 复用telegram-search的bleve全文索引引擎
- 搜索速度：50-150ms
- 内存占用：100-300MB（可配置）
- 已有基础设施：telegram-search `internal/alist115/`

### 问题2：如何避免触发115风控？
**方案：** 混合模式 - 批量导入 + 限速增量更新
- 初始化：导入预生成索引文件（零API调用）
- 增量：限速扫描（凌晨3-5点，间隔≥2s，全深度递归，分批次执行）
- 失效检测：定期1%抽样验证

### 问题3：如何减少内存消耗？
**方案：** 流式处理 + bleve磁盘存储
- Java：流式读取txt，10k批次分批导入
- Go：bleve磁盘模式，缓存可配置100-200MB
- 总预算：1-2GB JVM + 300MB telegram-search

---

## 2. 架构设计（telegram-search方案）

```
┌─────────────────────────────────────────────────────────────┐
│ alist-tvbox (Java)                                           │
│  ├─ Index115Service         索引管理                         │
│  ├─ Index115Controller      API入口                         │
│  ├─ Index115Task (Entity)   任务状态                         │
│  └─ @Scheduled Jobs         定时扫描/验证                    │
└──────────────────┬──────────────────────────────────────────┘
                   │ HTTP
┌──────────────────▼──────────────────────────────────────────┐
│ telegram-search (Go standalone service)                      │
│  ├─ POST /api/external/115/import-batch  批量导入            │
│  ├─ GET  /api/external/115/search        搜索API            │
│  ├─ DELETE /api/external/115/clear        清空索引           │
│  └─ internal/alist115/                    115索引模块        │
│      ├─ service.go           bleve索引管理                   │
│      ├─ importer.go          批量导入逻辑                     │
│      ├─ searcher.go          搜索封装                        │
│      └─ model.go             数据模型                        │
└─────────────────┬───────────────────────────────────────────┘
                  │
           ┌──────▼──────┐
           │ data/indexes/115/  │  bleve索引文件（磁盘）
           └─────────────┘
```

**关键变化：**
- telegram-search → telegram-search（独立Go服务）
- 复用telegram-search的external API框架
- 新增`internal/alist115/`模块
- 索引路径：`data/indexes/115/`

---

## 3. 数据模型

### 3.1 索引文件格式

**标准格式（本项目）：**
```
/path/to/file.mkv\t1234567890\t1704067200
```
- 字段1：文件路径（相对根目录）
- 字段2：文件大小（字节）
- 字段3：索引时间戳（Unix timestamp，可选）

**webdavsim兼容格式：**
```
/🏷️我的115分享/path/to/file.mkv\t1234567890
```
- 字段1：文件路径（包含emoji前缀）
- 字段2：文件大小（字节）
- 字段3：无时间戳（导入时使用当前时间）

**路径映射规则：**
- `/🏷️我的115分享/` → `/我的115分享/`（去除emoji）
- `/path/` → `/path/`（其他路径不变）

**压缩格式：** `.txt.xz`（支持webdavsim生成的索引文件）

### 3.2 Java实体

**Index115Task：**
```java
@Entity
class Index115Task {
    Integer id;
    String sourceType;      // IMPORT_FILE | SCAN_API | VALIDATE
    String filePath;        // 导入文件路径（IMPORT时）
    Integer siteId;         // 扫描站点ID（SCAN时）
    String scanPath;        // 扫描路径
    TaskStatus status;      // PENDING | RUNNING | SUCCESS | FAILED
    Long indexedCount;      // 已索引数量
    Long totalCount;        // 总数量
    String errorMessage;    // 错误信息
    Instant createdAt;
    Instant updatedAt;
}
```

**Index115Meta（扩展Meta）：**
```java
// 复用现有Meta表，添加字段：
String source;        // IMPORT | SCAN | SHARE
Instant indexedAt;    // 索引时间
Boolean validated;    // 是否验证过有效性
Instant validatedAt;  // 验证时间
```

### 3.3 Go结构

**SearchNode115：**
```go
type SearchNode115 struct {
    Path      string  `json:"path"`
    Size      int64   `json:"size"`
    IndexedAt int64   `json:"indexed_at"`
}
```

**bleve索引字段：**
```go
mapping := bleve.NewIndexMapping()
docMapping := bleve.NewDocumentMapping()
docMapping.AddFieldMappingsAt("path", textFieldMapping)
docMapping.AddFieldMappingsAt("size", numberFieldMapping)
docMapping.AddFieldMappingsAt("indexed_at", numberFieldMapping)
```

---

## 4. 核心流程

### 4.1 批量导入流程

```
用户操作
   ↓
1. 上传 115-index.txt.xz 到 /api/admin/115/import
   ↓
2. Java 创建 Index115Task(IMPORT_FILE)
   ↓
3. 异步任务：
   - 解压 .xz
   - 流式读取（BufferedReader）
   - 分批10k条 → HTTP POST /api/external/115/import-batch
   - 更新 indexedCount
   ↓
4. telegram-search 接收批次：
   - 解析 SearchNode115[]
   - bleve.NewBatch()
   - batch.Index(uuid, node)
   - index.Batch(batch)
   ↓
5. 完成后更新 Task.status = SUCCESS
```

**关键代码（Java）：**
```java
@Async
public void importFromFile(Integer taskId, String filePath) {
    try (BufferedReader reader = newXzReader(filePath)) {
        List<SearchNode115> batch = new ArrayList<>(10000);
        String line;
        long currentTime = Instant.now().getEpochSecond();
        
        while ((line = reader.readLine()) != null) {
            SearchNode115 node = parseLine(line, currentTime);
            batch.add(node);
            
            if (batch.size() >= 10000) {
                sendToTgSearch(batch);
                updateTaskProgress(taskId, batch.size());
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            sendToTgSearch(batch);
        }
        updateTaskStatus(taskId, SUCCESS);
    }
}

private SearchNode115 parseLine(String line, long defaultTime) {
    String[] parts = line.split("\t");
    String path = parts[0];
    long size = Long.parseLong(parts[1]);
    long indexedAt = parts.length >= 3 ? Long.parseLong(parts[2]) : defaultTime;
    
    // 路径映射：移除emoji前缀
    path = path.replaceFirst("^/🏷️我的115分享/", "/我的115分享/");
    
    return new SearchNode115(path, size, indexedAt);
}
```

### 4.2 增量扫描流程

```
@Scheduled(cron = "0 0 3 * * ?")  // 凌晨3点
   ↓
1. 检查是否有启用的115站点
   ↓
2. 遍历站点配置的scanPaths
   ↓
3. 限速扫描（全深度递归）：
   - listFiles(path, page=1, pageSize=100)
   - sleep(2000 + random(0, 1000))
   - 对比Meta表indexed_at
   - 新增/变化 → 收集到batch
   ↓
4. 批量发送到telegram-search /import-batch
   ↓
5. 递归所有子目录（无深度限制）
   ↓
6. 达到dailyLimit=5000后暂停，次日继续
```

**风控规避参数：**
```java
@ConfigurationProperties("index115.scan")
class ScanConfig {
    int limitRate = 2;          // 请求间隔（秒）
    int pageSize = 100;         // 分页大小
    String scanWindow = "03:00-05:00";  // 扫描时间窗口
    int dailyLimit = 5000;      // 每日扫描上限（达到后暂停，次日继续）
    boolean randomDelay = true;  // 随机延迟
    int batchPauseInterval = 10; // 每100个文件暂停10秒
}
```

### 4.3 搜索流程

```
用户请求 GET /api/external/115/search?q=侏罗纪&page=1
   ↓
1. Java Controller 代理请求
   ↓
2. telegram-search /api/external/115/search
   - 构建bleve.MatchQuery
   - index.Search(request)
   - 返回 {nodes, total}
   ↓
3. Java 组装响应：
   - 添加下载链接（通过AList API）
   - 添加TMDB元数据（如有）
   - 返回给用户
```

### 4.4 失效验证流程

```
@Scheduled(cron = "0 0 4 * * 0")  // 每周日凌晨4点
   ↓
1. 随机抽样1% Meta记录（validated=false 或 validatedAt < 30天前）
   ↓
2. 限速调用AList fs/get API验证：
   - 200 OK → validated=true
   - 404/403 → disabled=true
   - sleep(2000)
   ↓
3. 更新Meta.validatedAt
   ↓
4. 失效资源标记后不删除（等待人工确认）
```

---

## 5. API设计

### 5.1 Java API（alist-tvbox）

**POST /api/admin/115/import**
```
上传索引文件并触发导入

Request:
  Content-Type: multipart/form-data
  file: 115-index.txt.xz

Response:
  {
    "taskId": 123,
    "status": "PENDING",
    "message": "Import task created"
  }
```

**GET /api/admin/115/tasks**
```
查询导入任务列表

Response:
  {
    "tasks": [
      {
        "id": 123,
        "sourceType": "IMPORT_FILE",
        "status": "RUNNING",
        "indexedCount": 50000,
        "totalCount": 2600000,
        "progress": 1.92,
        "createdAt": "2026-06-16T10:00:00Z"
      }
    ]
  }
```

**POST /api/admin/115/scan**
```
触发增量扫描

Request:
  {
    "siteId": 1,
    "path": "/",
    "force": false  // 是否强制全量扫描
  }

Response:
  {
    "taskId": 124,
    "status": "PENDING"
  }
```

**GET /api/external/115/search**
```
搜索115文件（代理到telegram-search）

Query:
  q: 关键词
  page: 页码（默认1）
  per_page: 每页数量（默认20）
  scope: 0=all, 1=folder, 2=file

Response:
  {
    "nodes": [
      {
        "path": "/电影/侏罗纪世界3.mkv",
        "name": "侏罗纪世界3.mkv",
        "size": 73290758167,
        "is_dir": false,
        "indexed_at": 1704067200,
        "download_url": "http://alist.local/d/xxx"  // Java组装
      }
    ],
    "total": 42
  }
```

### 5.2 Go API（telegram-search扩展）

**POST /api/external/115/import-batch**
```
批量导入节点到bleve索引

Request:
  {
    "nodes": [
      {
        "path": "/电影/xxx.mkv",
        "size": 1234567890,
        "indexed_at": 1704067200
      }
    ]
  }

Response:
  {
    "success": true,
    "indexed": 10000
  }
```

**GET /api/external/115/search**
```
搜索索引

Query:
  q: 关键词
  page: 页码
  per_page: 每页数量
  scope: 0|1|2

Response:
  {
    "nodes": [...],
    "total": 42
  }
```

**DELETE /api/external/115/clear**
```
清空索引（需要admin权限）

Response:
  {
    "success": true,
    "message": "Index cleared"
  }
```

---

## 6. 内存优化策略

### 6.1 Java侧（Xmx=512MB）

**流式处理：**
```java
// 不使用：
List<String> allLines = Files.readAllLines(path);  // ❌ 全部加载

// 使用：
try (BufferedReader reader = Files.newBufferedReader(path)) {  // ✅ 流式
    String line;
    while ((line = reader.readLine()) != null) {
        // 处理单行
    }
}
```

**批量大小：**
- 10k条/batch × 平均200字节/行 = 2MB缓冲
- 同时最多2个batch在内存 = 4MB

**异步线程池：**
```java
@Bean("index115Executor")
public ThreadPoolExecutor index115Executor() {
    return new ThreadPoolExecutor(
        1, 2,  // 最多2个并发导入任务
        60, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(10),
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
}
```

**内存预算：**
- 业务逻辑：300MB
- 数据库连接：50MB
- 索引任务缓冲：100MB
- Caffeine缓存：100MB
- 预留：450MB
- **总计：1GB（推荐）~ 2GB（性能优化）**

### 6.2 Go侧（telegram-search）

**bleve配置：**
```go
config := bleve.NewIndexMapping()
// 禁用in-memory模式
indexConfig := map[string]interface{}{
    "store": map[string]interface{}{
        "kvStoreName": "boltdb",  // 磁盘存储
    },
}

// 配置缓存大小
index, _ := bleve.NewUsing(
    indexPath,
    config,
    "scorch",  // 使用scorch引擎（更节省内存）
    "boltdb",
    indexConfig,
)
```

**批量写入优化：**
```go
// 每批索引后强制flush
func (a *Alist115Searcher) BatchIndex(nodes []SearchNode115) error {
    batch := a.index.NewBatch()
    for _, node := range nodes {
        batch.Index(uuid.NewString(), node)
    }
    if err := a.index.Batch(batch); err != nil {
        return err
    }
    // 可选：手动触发segment merge
    return nil
}
```

**内存预算：**
- telegram-search基础：100MB
- bleve缓存：100-200MB（可调整）
- 批量处理缓冲：50MB
- **总计：250-350MB**

---

## 7. 风控规避详细设计

### 7.1 扫描限速策略

**基础参数：**
```java
// application.yaml
index115:
  scan:
    limit-rate: 2          # 基础间隔2秒
    random-jitter: 1000    # 随机抖动0-1000ms
    page-size: 100         # 每页100条（降低到正常值）
    max-depth: 3           # 最大递归3层
    scan-window: "03:00-05:00"  # 仅凌晨扫描
    daily-limit: 5000      # 每日上限
    retry-backoff: 60      # 失败后退避60秒
```

**实现：**
```java
private void scanWithRateLimit(String path, int currentDepth) {
    // 无深度限制，全递归扫描
    
    // 检查每日限额
    if (dailyScannedCount.get() >= config.getDailyLimit()) {
        log.info("Daily limit {} reached, will resume tomorrow", config.getDailyLimit());
        return;
    }
    
    // 检查时间窗口
    if (!isInScanWindow()) {
        log.info("Outside scan window, skip");
        return;
    }
    
    // 限速
    long delay = config.getLimitRate() * 1000L;
    if (config.isRandomDelay()) {
        delay += ThreadLocalRandom.current().nextInt(config.getRandomJitter());
    }
    Thread.sleep(delay);
    
    // 调用AList API
    FsResponse response = alistService.listFiles(path, page, config.getPageSize());
    dailyScannedCount.addAndGet(response.getContent().size());
    
    // 递归所有子目录
    for (FileItem dir : response.getDirs()) {
        scanWithRateLimit(dir.getPath(), currentDepth + 1);
    }
}
```

### 7.2 行为模拟

**目录随机化：**
```java
// 打乱目录顺序，避免固定模式
List<FileItem> dirs = response.getDirs();
Collections.shuffle(dirs);
for (FileItem dir : dirs) {
    scanWithRateLimit(dir.getPath(), depth + 1);
}
```

**周期性休眠：**
```java
// 每扫100个文件休眠10秒
if (scannedInBatch.get() % 100 == 0) {
    log.debug("Batch completed, sleep 10s");
    Thread.sleep(10_000);
}
```

**请求头模拟：**
```java
HttpHeaders headers = new HttpHeaders();
headers.set("User-Agent", "Mozilla/5.0 ...");  // 浏览器UA
headers.set("Referer", alistUrl);
headers.set("X-Requested-With", "XMLHttpRequest");
```

### 7.3 失效检测策略

**抽样比例：**
- 初次导入：0%（信任源数据）
- 30天内验证过：0%
- 30-90天：1%抽样
- 90天+：5%抽样

**验证间隔：**
```java
@Scheduled(cron = "0 0 4 * * 0")  // 每周日凌晨4点
public void validateLinks() {
    List<Meta> sample = metaRepository.findSampleForValidation(
        Instant.now().minus(30, ChronoUnit.DAYS),
        0.01  // 1%
    );
    
    for (Meta meta : sample) {
        try {
            Thread.sleep(2000);  // 限速
            FsInfo info = alistService.get(meta.getPath());
            meta.setValidated(true);
            meta.setValidatedAt(Instant.now());
        } catch (NotFoundException e) {
            meta.setDisabled(true);  // 标记失效
        }
        metaRepository.save(meta);
    }
}
```

---

## 8. 部署与配置

### 8.1 目录结构

```
alist-tvbox/
├─ data/
│  ├─ 115_index/           # bleve索引文件（Go管理）
│  │  ├─ index_meta.json
│  │  └─ *.bleve
│  ├─ imports/             # 待导入的txt文件
│  │  └─ 115-index.txt.xz
│  └─ database.db          # H2/SQLite数据库
├─ logs/
│  └─ index115.log
└─ config/
   └─ application.yaml
```

### 8.2 配置示例

```yaml
# application.yaml
index115:
  enabled: true
  
  # telegram-search连接
  tg-search:
    base-url: http://localhost:5244
    
  # 扫描配置
  scan:
    enabled: true
    limit-rate: 2
    random-jitter: 1000
    page-size: 100
    scan-window: "03:00-05:00"
    daily-limit: 5000
    batch-pause-interval: 10  # 每100个文件暂停10秒
    
  # 验证配置
  validate:
    enabled: true
    sample-rate: 0.01
    min-interval-days: 30
    
  # 导入配置
  import:
    batch-size: 10000
    max-concurrent: 2
    compression: xz
```

### 8.3 环境要求

**内存：**
- 最小：1GB JVM + 250MB telegram-search = **1.25GB**
- 推荐：2GB JVM + 350MB telegram-search = **2.35GB**
- 说明：更大内存可提升批量导入速度和搜索性能

**磁盘：**
- 索引文件：260万文件 × 150字节 ≈ **400MB**
- 数据库：Meta表 ≈ **200MB**
- 日志：≈ **100MB**
- **总计：≈700MB**

**CPU：**
- 批量导入：2核心
- 增量扫描：1核心
- 搜索查询：1核心

---

## 9. 监控与日志

### 9.1 关键指标

**索引任务：**
- 导入速度：条/秒
- 当前进度：%
- 失败计数
- 内存占用

**搜索性能：**
- 查询延迟：P50/P95/P99
- QPS
- 索引大小

**风控监控：**
- 每日API调用次数
- 失败率
- 封禁检测

### 9.2 日志级别

```java
// 正常运行：INFO
log.info("Import task {} started, total: ", taskId, totalCount);
log.info("Indexed {}/{} records", indexedCount, totalCount);

// 限速/风控：DEBUG
log.debug("Rate limit: sleep {}ms", delay);
log.debug("Outside scan window, skip");

// 错误：ERROR
log.error("Import task {} failed: {}", taskId, e.getMessage(), e);
```

---

## 10. 扩展性考虑

### 10.1 支持其他云盘

设计接口：
```java
interface CloudStorageIndexer {
    List<FileNode> scan(String path, ScanConfig config);
    boolean validate(String path);
}

class Pan115Indexer implements CloudStorageIndexer { ... }
class QuarkIndexer implements CloudStorageIndexer { ... }
```

### 10.2 索引版本管理

```java
// 索引格式升级时
@Entity
class IndexVersion {
    Integer version;       // 当前版本号
    String migrationSql;   // 升级SQL
    Instant createdAt;
}

// 自动检测并迁移
if (currentVersion < latestVersion) {
    migrateIndex(currentVersion, latestVersion);
}
```

### 10.3 分布式支持

未来可考虑：
- Redis作为任务队列
- 多个worker并发导入
- Elasticsearch替换bleve（更大规模）

---

## 11. 测试策略

### 11.1 单元测试

```java
@Test
void testParseLineStandard() {
    String line = "/path/file.mkv\t1234567890\t1704067200";
    SearchNode115 node = Index115Service.parseLine(line, 0);
    assertEquals("/path/file.mkv", node.getPath());
    assertEquals(1234567890L, node.getSize());
    assertEquals(1704067200L, node.getIndexedAt());
}

@Test
void testParseLineWebdavsim() {
    // webdavsim格式：无时间戳
    String line = "/🏷️我的115分享/电影/xxx.mkv\t1234567890";
    long now = Instant.now().getEpochSecond();
    SearchNode115 node = Index115Service.parseLine(line, now);
    
    // 验证路径映射
    assertEquals("/我的115分享/电影/xxx.mkv", node.getPath());
    assertEquals(1234567890L, node.getSize());
    assertEquals(now, node.getIndexedAt());
}

@Test
void testPathMapping() {
    assertEquals("/我的115分享/test", 
        mapPath("/🏷️我的115分享/test"));
    assertEquals("/other/path", 
        mapPath("/other/path"));
}

@Test
void testRateLimit() {
    long start = System.currentTimeMillis();
    service.scanWithRateLimit("/test", 1);
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(elapsed >= 2000);  // 至少2秒
}
```

### 11.2 集成测试

```java
@SpringBootTest
class Index115IntegrationTest {
    @Test
    void testFullImportFlow() {
        // 1. 创建测试数据
        createTestFile("test-index.txt", 1000);
        
        // 2. 触发导入
        Integer taskId = service.importFromFile("test-index.txt");
        
        // 3. 等待完成
        await().atMost(30, SECONDS).until(() -> 
            taskRepository.findById(taskId).get().getStatus() == SUCCESS
        );
        
        // 4. 验证搜索
        List<SearchNode> results = service.search("测试");
        assertEquals(1000, results.size());
    }
}
```

### 11.3 性能测试

**导入性能：**
- 目标：≥5000条/秒
- 测试：100万条数据导入耗时≤200秒

**搜索性能：**
- 目标：P95 < 200ms
- 测试：并发50 QPS，持续5分钟

**内存稳定性：**
- 目标：堆内存≤512MB
- 测试：导入260万条后GC表现

---

## 12. 风险与缓解

### 风险1：115 API变更
**缓解：**
- 解耦设计（通过AListService抽象）
- 支持批量导入（不依赖API）
- 社区共享索引文件

### 风险2：索引文件损坏
**缓解：**
- 导入前校验格式
- 支持断点续传
- 定期备份索引

### 风险3：内存溢出
**缓解：**
- 严格批量大小控制
- 流式处理
- 监控内存使用

### 风险4：搜索结果过期
**缓解：**
- 显示索引时间
- 定期抽样验证
- 用户可手动刷新

---

## 13. 交付物清单

### Java侧
- [ ] `Index115Task` 实体
- [ ] `Index115Service` 服务
- [ ] `Index115Controller` 控制器
- [ ] `Index115Config` 配置类
- [ ] Flyway迁移脚本
- [ ] 单元测试

### Go侧（telegram-search）
- [ ] `/internal/search/alist115/` 目录
- [ ] `search.go` - searcher实现
- [ ] `importer.go` - 批量导入
- [ ] `config.go` - 配置
- [ ] API路由注册
- [ ] 单元测试

### 文档
- [ ] API文档
- [ ] 用户手册（如何生成索引文件）
- [ ] 运维手册（监控/故障排查）

---

## 14. 里程碑

**Phase 1：基础功能（2周）**
- telegram-search bleve集成
- 批量导入API
- 基础搜索功能

**Phase 2：增量更新（1周）**
- 限速扫描实现
- 风控规避策略
- 定时任务

**Phase 3：优化与监控（1周）**
- 性能优化
- 内存调优
- 监控指标

**Phase 4：测试与发布（1周）**
- 集成测试
- 性能测试
- 文档完善

---

## 15. 未解决问题

1. **索引文件来源：** 
   - ✅ 兼容webdavsim生成的索引文件（xy115-all.txt.xz等）
   - ✅ 用户可使用webdavsim的get115list.py自行生成
   - 未来：alist-tvbox提供导出功能（限速扫描后导出为txt）
2. **多租户支持：** 如果多个用户各有115账号，如何隔离索引？（建议：按siteId创建独立索引目录）
3. **实时性要求：** 增量更新延迟可接受的范围是多久？（当前设计：每日扫描5000条，完整更新需数月）
4. **Elasticsearch迁移：** 如果未来规模扩大到千万级，何时考虑迁移到ES？（建议：单索引超过500万条时）
5. **路径映射配置化：** 当前硬编码emoji替换，是否需要支持自定义映射规则？

---

## 16. 方案对比：telegram-search集成 vs telegram-search集成

### 方案A：telegram-search集成（当前方案）

**架构：**
```
alist-tvbox (Java) ←→ telegram-search (Go embedded)
                        └─ bleve索引
```

**优势：**
✅ telegram-search已内嵌，零额外部署  
✅ bleve基础设施现成  
✅ 内存共享（1进程）  
✅ 同域部署，无跨域问题  
✅ 直接集成AList API，路径天然对齐  

**劣势：**
❌ telegram-search是文件管理器，职责耦合  
❌ 索引与AList主索引混在一起（需隔离）  
❌ Go代码需修改telegram-search源码  

**内存占用：**
- 总计：1.25-2.35GB（Java 1-2GB + telegram-search 250-350MB）

**部署复杂度：** ⭐（最简单，已嵌入）

---

### 方案B：telegram-search集成（新方案）

**架构：**
```
alist-tvbox (Java) ─HTTP→ telegram-search (Go standalone)
                           └─ SQLite FTS5 / 新增bleve
```

**优势：**
✅ 独立服务，职责清晰（专注搜索）  
✅ telegram-search已有搜索基础设施  
✅ 已有external_search API框架  
✅ 内存隔离，进程独立  
✅ 可复用给其他项目（通用搜索服务）  
✅ 扩展性强（支持多种数据源）  

**劣势：**
❌ 需独立部署（多1个服务）  
❌ 跨进程HTTP调用（延迟+10-50ms）  
❌ 需维护API契约  
❌ Docker部署需配置网络  

**内存占用：**
- alist-tvbox：1-2GB
- telegram-search：200-400MB（含索引）
- **总计：1.2-2.4GB**

**部署复杂度：** ⭐⭐（需配置HTTP端点）

---

### 方案C：独立Go服务（纯粹方案）

**架构：**
```
alist-tvbox (Java) ─HTTP→ 115-index-service (Go new project)
                           └─ bleve索引
```

**优势：**
✅ 最小化职责（只做115索引）  
✅ 无历史包袱，轻量  
✅ 可开源为独立项目  

**劣势：**
❌ 从零开发  
❌ 需独立部署+维护  
❌ 无现成基础设施  

**内存占用：**
- alist-tvbox：1-2GB
- 115-index-service：100-200MB
- **总计：1.1-2.2GB**（最省）

**部署复杂度：** ⭐⭐⭐（全新服务）

---

### 关键对比

| 维度 | telegram-search集成 | telegram-search集成 | 独立Go服务 |
|------|--------------|---------------------|-----------|
| **搜索速度** | 50-150ms | 50-200ms（+HTTP） | 50-150ms |
| **开发成本** | ⭐⭐（修改现有） | ⭐（复用基础） | ⭐⭐⭐（全新） |
| **部署复杂度** | ⭐（已嵌入） | ⭐⭐（独立服务） | ⭐⭐⭐（新服务） |
| **内存占用** | 1.25-2.35GB | 1.2-2.4GB | 1.1-2.2GB |
| **职责清晰度** | ❌ 耦合 | ✅ 清晰 | ✅ 最清晰 |
| **复用性** | ❌ 绑定AList | ✅ 通用API | ✅ 可开源 |
| **扩展性** | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| **维护成本** | ⭐⭐（改他人代码） | ⭐（熟悉项目） | ⭐⭐ |

---

### 推荐决策

**1秒搜索延迟可接受** → HTTP开销（10-50ms）不是问题

**推荐：方案B（telegram-search集成）**

**理由：**
1. ✅ 职责清晰：telegram-search专注搜索，符合架构
2. ✅ 已有external_search API框架，开发最快
3. ✅ 内存隔离，不影响alist-tvbox稳定性
4. ✅ 可复用：未来其他项目可调用同一搜索服务
5. ✅ 你已熟悉telegram-search代码，维护成本低
6. ✅ 1秒内响应充裕（实际200-300ms）

**实施建议：**
- telegram-search新增 `/api/external/115/search` 端点
- 复用existing external_search框架
- 添加bleve索引目录：`data/indexes/115/`
- alist-tvbox通过HTTP调用
- Docker compose统一编排

**需要确认：**
- telegram-search是否适合承担通用搜索服务角色？
- 是否介意多部署1个服务？

---
