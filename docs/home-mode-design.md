# 家庭模式设计文档

## 概述

家庭模式是基于 **Subscription（订阅配置）** 的质量管理功能，目标是为家庭用户提供长期稳定、低维护、可自动修复的 TVBox 订阅配置。

### 核心理念

- **不是聚合最多的源**，而是提供真正可用的源
- **自动质量监控**：持续检测订阅配置的可用性
- **自动发行**：候选池 → 观察期 → 稳定版
- **自动降级**：失效订阅自动移除或降级
- **固定源保护**：用户可固定重要订阅，不被自动降级

---

## 架构设计

### 1. 数据模型

#### 1.1 订阅质量追踪表

```sql
-- 订阅配置质量追踪
CREATE TABLE subscription_quality (
  subscription_id INT PRIMARY KEY REFERENCES subscription(id) ON DELETE CASCADE,
  
  -- 家庭模式状态
  home_mode_enabled BOOLEAN DEFAULT false, -- 是否启用家庭模式追踪
  release_mode VARCHAR(20) DEFAULT 'disabled',
  -- disabled: 未启用
  -- candidate: 候选观察中
  -- stable: 稳定版
  -- pinned: 用户固定（不自动降级）
  
  -- 质量指标（滚动窗口：最近20次检测）
  recent_checks JSONB DEFAULT '[]'::jsonb,
  -- [{
  --   time: "2026-06-16T10:00:00Z",
  --   success: true,
  --   valid_sites_count: 8,
  --   total_sites_count: 10,
  --   latency_ms: 450,
  --   error: null
  -- }]
  
  success_rate DECIMAL(5,2) DEFAULT 0,
  avg_valid_sites INT DEFAULT 0, -- 平均可用源数量
  fail_streak INT DEFAULT 0,
  last_check_at TIMESTAMP,
  
  -- 发行时间戳
  promoted_to_stable_at TIMESTAMP,
  pinned_at TIMESTAMP,
  
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX idx_subscription_quality_mode ON subscription_quality(release_mode);
CREATE INDEX idx_subscription_quality_enabled ON subscription_quality(home_mode_enabled);
```

#### 1.2 家庭模式全局配置表

```sql
-- 家庭模式全局配置（单行表）
CREATE TABLE home_mode_config (
  id INT PRIMARY KEY DEFAULT 1,
  enabled BOOLEAN DEFAULT false,
  
  -- 质量检测策略
  check_interval_hours INT DEFAULT 2,
  observation_checks INT DEFAULT 10, -- 需要10次检测数据
  min_success_rate DECIMAL(5,2) DEFAULT 80.00,
  min_valid_sites INT DEFAULT 5, -- 订阅至少包含5个可用源
  
  -- 自动发行策略
  max_fail_streak INT DEFAULT 3,
  stable_limit INT DEFAULT 8, -- 稳定版最多包含8个订阅
  auto_promote_enabled BOOLEAN DEFAULT true,
  
  -- 生成配置
  stable_subscription_id INT REFERENCES subscription(id), -- 指向生成的稳定版订阅
  last_published_at TIMESTAMP,
  
  CHECK (id = 1)
);

INSERT INTO home_mode_config (id, enabled) VALUES (1, false);
```

---

### 2. 核心服务

#### 2.1 SubscriptionQualityService（订阅质量检测）

**职责**：
- 检测订阅配置的可用性
- 抽样测试订阅内的源（默认测试前3个）
- 推算全部可用源数量
- 更新质量记录（滚动窗口最近20次）

**核心方法**：
```java
// 检测订阅质量
QualityCheckResult checkSubscriptionQuality(Subscription subscription)

// 测试单个源是否可用
boolean testSite(JsonNode site)

// 更新质量记录（滚动窗口）
void updateQualityRecord(SubscriptionQuality quality, QualityCheckResult result)
```

**检测逻辑**：
1. 获取订阅配置内容（JSON）
2. 解析 sites 数组
3. 抽样测试前3个源
4. 按比例推算全部可用源数量
5. 记录检测结果和延迟

---

#### 2.2 HomeModeService（家庭模式核心）

**职责**：
- 订阅的家庭模式注册
- 生成稳定版订阅配置（合并所有稳定版订阅）
- 手动提升/降级/固定订阅

**核心方法**：
```java
// 为订阅启用家庭模式追踪
void enrollSubscription(Integer subscriptionId)

// 生成稳定版订阅配置（合并所有stable/pinned订阅）
Integer generateStableSubscription()

// 手动提升到稳定版
void promoteToStable(Integer subscriptionId)

// 固定订阅（不自动降级）
void pinSubscription(Integer subscriptionId)

// 取消固定
void unpinSubscription(Integer subscriptionId)
```

**稳定版生成逻辑**：
1. 获取所有 `stable` 和 `pinned` 状态的订阅
2. 按成功率降序排序
3. 遍历每个订阅，提取 `sites` 数组
4. 去重合并（按 `site.key` 去重）
5. 保存或更新到稳定版订阅（`/home-stable`）

---

#### 2.3 HomeModeScheduler（定时任务）

**职责**：
- 定期质量检测（每2小时）
- 自动发行（每天22:00）
- 自动降级失效订阅

**核心任务**：

##### 质量检测任务
```java
@Scheduled(cron = "0 0 */2 * * ?") // 每2小时
void runQualityChecks()
```

逻辑：
1. 获取所有启用家庭模式的订阅
2. 逐个执行质量检测
3. 更新质量记录
4. 记录日志

##### 自动发行任务
```java
@Scheduled(cron = "0 0 22 * * ?") // 每天22:00
void autoPromote()
```

逻辑：
1. **提升候选订阅**：
   - 检查观察期样本数 >= `observation_checks`
   - 检查成功率 >= `min_success_rate`
   - 检查平均可用源数 >= `min_valid_sites`
   - 检查稳定版容量 < `stable_limit`
   - 满足条件则提升到 `stable`

2. **降级失效订阅**：
   - 遍历所有 `stable` 订阅（跳过 `pinned`）
   - 连续失败次数 >= `max_fail_streak` 则降级到 `candidate`

3. **重新生成稳定版**：
   - 调用 `generateStableSubscription()` 合并配置

---

### 3. REST API

```java
@RestController
@RequestMapping("/api/home-mode")
public class HomeModeController {
    
    // 获取全局配置
    @GetMapping("/config")
    Result<HomeModeConfig> getConfig()
    
    // 保存全局配置
    @PostMapping("/config")
    Result<?> saveConfig(@RequestBody HomeModeConfig config)
    
    // 获取所有订阅质量
    @GetMapping("/qualities")
    Result<List<SubscriptionQualityDTO>> getQualities()
    
    // 添加订阅到候选池
    @PostMapping("/enroll/{subscriptionId}")
    Result<?> enrollSubscription(@PathVariable Integer subscriptionId)
    
    // 手动提升到稳定版
    @PostMapping("/promote/{subscriptionId}")
    Result<?> promoteToStable(@PathVariable Integer subscriptionId)
    
    // 固定订阅
    @PostMapping("/pin/{subscriptionId}")
    Result<?> pinSubscription(@PathVariable Integer subscriptionId)
    
    // 取消固定
    @PostMapping("/unpin/{subscriptionId}")
    Result<?> unpinSubscription(@PathVariable Integer subscriptionId)
    
    // 从家庭模式移除
    @DeleteMapping("/remove/{subscriptionId}")
    Result<?> removeFromHomeMode(@PathVariable Integer subscriptionId)
    
    // 立即重新生成稳定版
    @PostMapping("/publish")
    Result<?> publishStableConfig()
}
```

---

### 4. 前端集成

#### 4.1 在订阅管理页面添加标签

```vue
<!-- web-ui/src/views/SubscriptionList.vue -->
<el-tabs v-model="activeTab">
  <el-tab-pane label="订阅管理" name="list">
    <!-- 现有订阅列表 -->
  </el-tab-pane>
  
  <el-tab-pane label="🏠 家庭模式" name="homeMode">
    <HomeModePanel />
  </el-tab-pane>
</el-tabs>
```

#### 4.2 家庭模式面板组件

```vue
<!-- web-ui/src/components/HomeModePanel.vue -->
```

**主要功能**：
1. **全局开关**：启用/禁用家庭模式
2. **稳定版订阅地址**：显示并提供复制按钮
3. **订阅质量列表**：
   - 显示每个订阅的状态（候选/稳定/固定）
   - 显示质量指标（成功率、平均可用源数）
   - 显示观察进度（候选池）
   - 提供操作按钮（提升/固定/移除）
4. **添加订阅对话框**：从现有订阅中选择添加到候选池
5. **策略配置表单**：
   - 质量检测间隔
   - 观察期检测次数
   - 最低成功率
   - 最少可用源数
   - 连续失败降级次数
   - 稳定版最多订阅数
   - 自动提升开关

---

## 状态流转图

```
[未启用] ──启用家庭模式──> [候选池]
                            ↓
                      ┌─ 观察期 ─┐
                      │   10次   │
                      │   检测   │
                      └─────────┘
                            ↓
            ┌───────────────┼───────────────┐
            ↓               ↓               ↓
    [质量不达标]      [达标提升]      [手动提升]
         ↓               ↓               ↓
    保持候选池        [稳定版] ←─── [用户固定] = [固定订阅]
                         ↓                         ↓
                   连续失败3次                   永不降级
                         ↓
                   [降级到候选池]
```

---

## 质量判断规则

### 提升到稳定版条件（全部满足）

1. **观察期样本数** >= `observation_checks`（默认10次）
2. **成功率** >= `min_success_rate`（默认80%）
3. **平均可用源数** >= `min_valid_sites`（默认5个）
4. **稳定版容量** < `stable_limit`（默认8个）

### 降级到候选池条件

1. **连续失败次数** >= `max_fail_streak`（默认3次）
2. **不是固定订阅**（`release_mode != 'pinned'`）

---

## 质量检测详细逻辑

### 检测策略

1. **抽样检测**：只测试订阅中前3个源（避免超时）
2. **比例推算**：根据抽样成功率推算全部可用源数
3. **滚动窗口**：只保留最近20次检测记录
4. **连续失败追踪**：记录 `fail_streak` 用于降级判断

### 单源检测（MacCMS）

```java
private boolean testSite(JsonNode site) {
    int type = site.path("type").asInt();
    String api = site.path("api").asText();
    
    if (type == 1) { // MacCMS
        String response = HttpUtil.get(
            api + "?ac=detail&wd=测试",
            Collections.emptyMap(),
            5000
        );
        JsonNode json = objectMapper.readTree(response);
        return json.path("list").size() > 0;
    }
    
    // 其他类型（JAR、直播）暂不检测
    return true;
}
```

### 质量记录结构

```json
{
  "time": "2026-06-16T10:00:00Z",
  "success": true,
  "valid_sites_count": 8,
  "total_sites_count": 10,
  "latency_ms": 450,
  "error": null
}
```

---

## 稳定版配置生成

### 合并逻辑

1. 获取所有 `stable` 和 `pinned` 状态的订阅
2. 按成功率降序排序
3. 遍历每个订阅，提取 `sites` 数组
4. 按 `site.key` 去重（先出现的保留）
5. 合并到新的 TVBox 配置
6. 保存到稳定版订阅（`/home-stable`）

### 示例输出

```json
{
  "sites": [
    {
      "key": "jisuzy",
      "name": "极速资源站",
      "type": 1,
      "api": "https://jszyapi.com/api.php/provide/vod/from/jsm3u8/at/json/",
      "searchable": 1,
      "quickSearch": 1,
      "filterable": 1
    },
    // ... 其他去重后的源
  ],
  "parses": [],
  "lives": []
}
```

---

## DTO 定义

### HomeModeConfig

```java
@Data
public class HomeModeConfig {
    private Integer id;
    private Boolean enabled;
    
    // 质量检测策略
    private Integer checkIntervalHours;
    private Integer observationChecks;
    private BigDecimal minSuccessRate;
    private Integer minValidSites;
    
    // 自动发行策略
    private Integer maxFailStreak;
    private Integer stableLimit;
    private Boolean autoPromoteEnabled;
    
    // 生成配置
    private Integer stableSubscriptionId;
    private LocalDateTime lastPublishedAt;
}
```

### SubscriptionQualityDTO

```java
@Data
@Builder
public class SubscriptionQualityDTO {
    private Integer subscriptionId;
    private String subscriptionName;
    private String releaseMode; // candidate | stable | pinned | disabled
    
    private BigDecimal successRate;
    private Integer avgValidSites;
    private Integer failStreak;
    private Integer checkCount; // recent_checks.size()
    
    private LocalDateTime lastCheckAt;
    private LocalDateTime promotedToStableAt;
    private LocalDateTime pinnedAt;
}
```

### QualityCheckResult

```java
@Data
@Builder
public class QualityCheckResult {
    private boolean success;
    private int totalSitesCount;
    private int validSitesCount;
    private long latencyMs;
    private String error;
    
    public static QualityCheckResult success(int total, int valid, long latencyMs) {
        return QualityCheckResult.builder()
            .success(true)
            .totalSitesCount(total)
            .validSitesCount(valid)
            .latencyMs(latencyMs)
            .build();
    }
    
    public static QualityCheckResult failure(String error, long latencyMs) {
        return QualityCheckResult.builder()
            .success(false)
            .error(error)
            .latencyMs(latencyMs)
            .build();
    }
}
```

---

## 实施计划

### Phase 1: 数据层（2小时）
- [ ] 创建 Flyway 迁移文件
- [ ] 创建实体类（`HomeModeConfig`, `SubscriptionQuality`）
- [ ] 创建 Repository

### Phase 2: 服务层（3小时）
- [ ] `SubscriptionQualityService`
  - [ ] `checkSubscriptionQuality()`
  - [ ] `testSite()`
  - [ ] `updateQualityRecord()`
- [ ] `HomeModeService`
  - [ ] `enrollSubscription()`
  - [ ] `generateStableSubscription()`
  - [ ] `promoteToStable()` / `pinSubscription()`

### Phase 3: 定时任务（1小时）
- [ ] `HomeModeScheduler`
  - [ ] `runQualityChecks()` 每2小时
  - [ ] `autoPromote()` 每天22:00

### Phase 4: API 层（1小时）
- [ ] `HomeModeController`
  - [ ] 配置管理接口
  - [ ] 订阅管理接口
  - [ ] 手动操作接口

### Phase 5: 前端（4小时）
- [ ] `HomeModePanel.vue` 组件
  - [ ] 全局开关和配置表单
  - [ ] 订阅质量列表
  - [ ] 添加订阅对话框
  - [ ] 手动操作按钮
- [ ] 集成到订阅管理页面

### Phase 6: 测试（2小时）
- [ ] 质量检测功能测试
- [ ] 状态流转测试
- [ ] 自动发行测试
- [ ] 前端交互测试

**预估总工作量**：13小时

---

## 配置示例

### application.yml

```yaml
# 家庭模式配置（生产环境默认禁用）
home-mode:
  enabled: false

# 如需启用，在 application-local.yml 中覆盖：
# home-mode:
#   enabled: true
```

---

## 关键优势

1. ✅ **最小侵入**：只新增2张表，不修改现有表
2. ✅ **插件式设计**：通过 `@ConditionalOnProperty` 按需启用
3. ✅ **复用基础设施**：利用现有 `Subscription`、`HttpUtil`、定时任务
4. ✅ **数据轻量**：滚动窗口（最近20次），不会无限增长
5. ✅ **用户可控**：支持固定订阅、手动提升、调整策略
6. ✅ **渐进式迭代**：先实现 MacCMS 检测，后续可扩展 JAR 审查

---

## 未来扩展

### 可选功能（后续迭代）

1. **JAR 安全审查**：
   - 集成 jadx 反编译
   - 检测恶意代码模式
   - 隔离环境测试

2. **多维度质量评分**：
   - 响应速度
   - 资源丰富度
   - 更新频率

3. **通知机制**：
   - 降级时发送邮件/Telegram
   - 质量报告定期推送

4. **家庭成员管理**：
   - 为不同家庭成员生成不同订阅
   - 支持儿童模式（内容过滤）

---

## 注意事项

1. **默认禁用**：生产环境默认不启用，避免影响现有用户
2. **性能考虑**：质量检测只抽样测试（前3个源），避免超时
3. **固定源保护**：用户固定的订阅永不自动降级
4. **容量限制**：稳定版最多包含 `stable_limit` 个订阅，避免过度膨胀
5. **滚动窗口**：只保留最近20次检测记录，控制数据量

---

## 参考资料

- home-spider-forge 项目: `/home/harold/workspace/home-spider-forge/`
- 源注册表设计: `source-registry.json`
- 发布策略配置: `release-policy.json`
- TVBox 配置爬虫: `tvbox-seed-scraper.mjs`

---

**文档版本**: v1.0  
**创建时间**: 2026-06-16  
**状态**: 设计完成，待实施
