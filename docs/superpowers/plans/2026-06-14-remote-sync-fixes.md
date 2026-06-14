# 远程同步功能修复方案

## 概述
针对 PR #1026 代码审查发现的问题，提供分级修复方案。

## 🔴 Critical - 必须立即修复

### 1. 修复日志格式错误

**问题:** 
- `SyncService.java:1300` - 缺少格式占位符
- `SyncService.java:1177` - 缺少updated计数占位符

**修复:**

```java
// Line 1300
- log.error("从远端拉取失败: ", remoteUrl, e);
+ log.error("从远端拉取失败: {}", remoteUrl, e);

// Line 1177
- log.info("导入 Plugins 完成: 新增 {}, 更新 , 失败 {}",
+ log.info("导入 Plugins 完成: 新增 {}, 更新 {}, 失败 {}",
```

### 2. 修复 Share ID 竞争条件

**问题:** Share 实体手动分配 ID 不是原子操作

**方案 A - 推荐:** 修改 Share 实体使用自动生成 ID

```java
// Share.java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)  // 添加自动生成
private Integer id;
```

**方案 B - 如果不能改实体:** 使用同步块

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public synchronized SyncResult importShares(List<Share> shares, MergeStrategy strategy) {
    // ... 现有代码
}
```

**方案 C - 数据库层面:** 使用 SEQUENCE

```java
@SequenceGenerator(name = "share_seq", sequenceName = "share_id_seq", allocationSize = 1)
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "share_seq")
private Integer id;
```

---

## 🟠 High Priority - 强烈建议修复

### 3. 类型安全 - 使用类型化 getter

**问题:** importData() 方法中大量 unsafe cast

**修复:**

```java
// SyncService.java - importData() 方法
@SuppressWarnings("unchecked")  // 删除这行
public Map<String, SyncResult> importData(SyncData data, MergeStrategy strategy, boolean force) {
    // 版本校验
    String localVersion = settingRepository.findById("app_version")
            .map(Setting::getValue)
            .orElse("unknown");
    if (!localVersion.equals(data.getAppVersion()) && !force) {
        throw new VersionMismatchException(localVersion, data.getAppVersion());
    }

    Map<String, SyncResult> results = new HashMap<>();

    // 使用类型化的 getter 替代 unsafe cast
    if (data.getSettings() != null) {
        results.put("settings", importSettings(data.getSettings(), strategy));
    }
    if (data.getSites() != null) {
        results.put("sites", importSites(data.getSites(), strategy));
    }
    if (data.getAccounts() != null) {
        results.put("accounts", importAccounts(data.getAccounts(), strategy));
    }
    if (data.getDriverAccounts() != null) {
        results.put("driverAccounts", importDriverAccounts(data.getDriverAccounts(), strategy));
    }
    if (data.getPikpakAccounts() != null) {
        results.put("pikpakAccounts", importPikPakAccounts(data.getPikpakAccounts(), strategy));
    }
    if (data.getShares() != null) {
        results.put("shares", importShares(data.getShares(), strategy));
    }
    if (data.getPlugins() != null) {
        results.put("plugins", importPlugins(data.getPlugins(), strategy));
    }
    if (data.getPluginFilters() != null) {
        results.put("pluginFilters", importPluginFilters(data.getPluginFilters(), strategy));
    }
    if (data.getSubscriptions() != null) {
        results.put("subscriptions", importSubscriptions(data.getSubscriptions(), strategy));
    }

    return results;
}
```

### 4. Push 操作增加版本检查

**问题:** pull 检查版本，但 push 不检查

**修复:**

```java
// SyncService.java - push() 方法
public SyncResponse push(String remoteUrl, String username, String password,
                         List<String> modules, boolean force) {
    SyncResponse response = new SyncResponse();
    String token = null;

    try {
        // 登录远端（创建临时会话）
        token = remoteClient.login(remoteUrl, username, password);

        // 新增：获取远端版本
        SyncData remoteVersion = remoteClient.fetchRemoteData(remoteUrl, token, List.of());
        String localVersion = getLocalVersion();
        
        if (!force && !localVersion.equals(remoteVersion.getAppVersion())) {
            throw new VersionMismatchException(localVersion, remoteVersion.getAppVersion());
        }

        // 导出本地数据
        SyncData data = exportData(modules);

        // 推送到远端（远端使用覆盖模式）
        Map<String, SyncResult> results = remoteClient.pushToRemote(
            remoteUrl, token, data, "OVERWRITE", force);

        response.setSuccess(true);
        response.setResults(results);

        log.info("推送到远端成功: {}", remoteUrl);
    } catch (VersionMismatchException e) {
        log.warn("推送时版本不匹配: {} vs {}", e.getLocalVersion(), e.getRemoteVersion());
        throw e;  // 重新抛出让 Controller 处理
    } catch (Exception e) {
        log.error("推送到远端失败: {}", remoteUrl, e);
        response.setSuccess(false);
        SyncResult errorResult = new SyncResult();
        errorResult.setFailed(1);
        errorResult.getErrors().add(e.getMessage());
        response.addResult("error", errorResult);
    } finally {
        // 同步完成后清理远端的临时会话
        if (token != null) {
            cleanupRemoteSession(remoteUrl, token);
        }
    }

    return response;
}
```

**同时修改 Controller:**

```java
// SyncController.java - push() 方法
@PostMapping("/push")
public SyncResponse push(@RequestBody SyncRequest request) {
    log.info("推送到远端: {}, 模块: {}, force: {}",
        request.getRemoteUrl(), request.getModules(), request.isForce());
    
    try {
        return syncService.push(
            request.getRemoteUrl(),
            request.getUsername(),
            request.getPassword(),
            request.getModules(),
            request.isForce()
        );
    } catch (VersionMismatchException e) {
        // 版本不匹配，返回特殊响应让前端处理
        SyncResponse response = new SyncResponse();
        response.setSuccess(false);
        SyncResult errorResult = new SyncResult();
        errorResult.setFailed(1);
        errorResult.getErrors().add("VERSION_MISMATCH:" + e.getMessage());
        response.addResult("version_error", errorResult);
        return response;
    }
}
```

### 5. 敏感配置和凭据警告

**修复:** 在前端添加多层级警告提示

**修改 1: Settings 模块增加工具提示**

```vue
<!-- RemoteSyncDialog.vue -->
<el-form-item label="选择模块">
  <el-checkbox-group v-model="syncConfig.modules">
    <el-checkbox label="sites">外部站点 (Sites)</el-checkbox><br/>
    <el-checkbox label="shares">网盘分享 (Shares)</el-checkbox><br/>
    <el-checkbox label="accounts">
      阿里云账号 (Accounts)
      <el-tooltip content="包含 refresh_token 等认证信息" placement="top">
        <el-icon style="color: #E6A23C; margin-left: 4px;"><Warning /></el-icon>
      </el-tooltip>
    </el-checkbox><br/>
    <el-checkbox label="driverAccounts">
      网盘账号 (DriverAccounts)
      <el-tooltip content="包含各网盘的 Cookie、Token、密码" placement="top">
        <el-icon style="color: #E6A23C; margin-left: 4px;"><Warning /></el-icon>
      </el-tooltip>
    </el-checkbox><br/>
    <el-checkbox label="pikpakAccounts">
      PikPak账号 (PikPakAccounts)
      <el-tooltip content="包含 PikPak 用户名和密码" placement="top">
        <el-icon style="color: #E6A23C; margin-left: 4px;"><Warning /></el-icon>
      </el-tooltip>
    </el-checkbox><br/>
    <el-checkbox label="subscriptions">订阅配置 (Subscriptions)</el-checkbox><br/>
    <el-checkbox label="settings">
      系统设置 (Settings)
      <el-tooltip content="包含 API Key、Cookie、用户名/密码等敏感配置" placement="top">
        <el-icon style="color: #E6A23C; margin-left: 4px;"><Warning /></el-icon>
      </el-tooltip>
    </el-checkbox>
  </el-checkbox-group>
</el-form-item>
```

**修改 2: 步骤 1 增加安全提醒**

```vue
<!-- RemoteSyncDialog.vue - 步骤 1 -->
<div v-if="currentStep === 0" style="padding: 20px 0;">
  <el-alert
    title="安全提醒"
    type="warning"
    :closable="false"
    show-icon
    style="margin-bottom: 15px;"
  >
    <template #default>
      <div style="font-size: 13px; line-height: 1.6;">
        • 同步功能会传输账号凭据、API Key 等敏感信息<br/>
        • 请确保远端服务器是您信任的实例<br/>
        • 生产环境建议使用 HTTPS 连接
      </div>
    </template>
  </el-alert>
  
  <el-alert
    v-if="connectionForm.url && connectionForm.url.startsWith('http://')"
    title="HTTP 警告"
    type="error"
    :closable="false"
    show-icon
    style="margin-bottom: 15px;"
  >
    <template #default>
      您正在使用 HTTP 连接，数据将明文传输。生产环境请使用 HTTPS。
    </template>
  </el-alert>
  
  <el-form :model="connectionForm" label-width="100px">
    <!-- 现有表单项 -->
  </el-form>
</div>
```

添加 import:
```vue
<script setup lang="ts">
import { Warning } from '@element-plus/icons-vue'
// ... 其他 imports
</script>
```

---

## 🟡 Medium Priority - 建议修复

### 6. 移除手动 JSON 转义

**问题:** `RemoteClient.escapeJson()` 重复造轮子

**修复:**

```java
// RemoteClient.java - login() 方法
public String login(String remoteUrl, String username, String password) throws IOException {
    String normalizedUrl = normalizeUrl(remoteUrl);
    String loginUrl = normalizedUrl + "/api/accounts/login";

    log.info("尝试登录远端获取临时 token: {}", loginUrl);

    // 使用 ObjectMapper 构建请求体，替代手动拼接
    Map<String, String> loginRequest = new HashMap<>();
    loginRequest.put("username", username);
    loginRequest.put("password", password);
    String loginJson = objectMapper.writeValueAsString(loginRequest);
    
    RequestBody body = RequestBody.create(loginJson, MediaType.get("application/json"));

    // ... 其余代码保持不变
}

// 删除 escapeJson() 方法 (line 458-465)
```

### 7. HTTPS 安全提示

**修复:** 在前端添加 HTTP 连接警告

```vue
<!-- RemoteSyncDialog.vue - 步骤 1 -->
<div v-if="currentStep === 0" style="padding: 20px 0;">
  <el-alert
    v-if="connectionForm.url && connectionForm.url.startsWith('http://')"
    title="安全警告"
    type="warning"
    description="您正在使用 HTTP 连接，用户名和密码将以明文传输。生产环境请使用 HTTPS。"
    :closable="false"
    show-icon
    style="margin-bottom: 15px;"
  />
  
  <el-form :model="connectionForm" label-width="100px">
    <!-- 现有表单项 -->
  </el-form>
</div>
```

### 8. 添加数据库索引

**修复:** 创建 migration 脚本

```sql
-- src/main/resources/db/migration/V7__Add_sync_indexes.sql

-- Account 按 nickname 查询
CREATE INDEX IF NOT EXISTS idx_account_nickname ON account(nickname);

-- DriverAccount 按 type + username 查询
CREATE INDEX IF NOT EXISTS idx_driver_account_type_username ON driver_account(type, username);

-- DriverAccount 按 type + name 查询（fallback）
CREATE INDEX IF NOT EXISTS idx_driver_account_type_name ON driver_account(type, name);

-- Share 按 type + share_id 查询
CREATE INDEX IF NOT EXISTS idx_share_type_shareid ON share(type, share_id);

-- Subscription 按 url 查询
CREATE INDEX IF NOT EXISTS idx_subscription_url ON subscription(url);

-- Plugin 按 externalId 查询
CREATE INDEX IF NOT EXISTS idx_plugin_external_id ON plugin(external_id);

-- PluginFilter 按 url 查询
CREATE INDEX IF NOT EXISTS idx_plugin_filter_url ON plugin_filter(url);
```

---

## 📋 实施步骤

### 阶段 1: Critical Fixes (立即执行)
1. ✅ 修复日志格式错误 (5分钟)
2. ✅ 修复 Share ID 竞争条件 (选择方案并实施，15分钟)

### 阶段 2: High Priority (本次迭代完成)
3. ✅ 重构 importData 使用类型化 getter (20分钟)
4. ✅ Push 操作增加版本检查 (30分钟)
5. ✅ 敏感配置警告 (15分钟)

### 阶段 3: Medium Priority (可选，下次迭代)
6. ⚪ 移除手动 JSON 转义 (10分钟)
7. ⚪ HTTPS 安全提示 (10分钟)
8. ⚪ 添加数据库索引 (10分钟 + 测试)

---

## 测试验证

### 单元测试

```java
// SyncServiceTest.java - 新增测试
@Test
void testPushWithVersionMismatch() {
    // Given
    when(settingRepository.findById("app_version"))
        .thenReturn(Optional.of(new Setting("app_version", "1.0")));
    when(remoteClient.login(anyString(), anyString(), anyString()))
        .thenReturn("test-token");
    SyncData remoteData = new SyncData();
    remoteData.setAppVersion("2.0");
    when(remoteClient.fetchRemoteData(anyString(), anyString(), anyList()))
        .thenReturn(remoteData);

    // When/Then
    assertThrows(VersionMismatchException.class, () -> {
        syncService.push("http://remote:4567", "admin", "pass", List.of("sites"), false);
    });
}

@Test
void testImportSharesNoRaceCondition() throws InterruptedException {
    // Given
    Share share1 = new Share();
    share1.setType(1);
    share1.setShareId("share1");
    
    Share share2 = new Share();
    share2.setType(1);
    share2.setShareId("share2");
    
    // When - 并发导入
    CountDownLatch latch = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    
    executor.submit(() -> {
        syncService.importShares(List.of(share1), MergeStrategy.MERGE);
        latch.countDown();
    });
    
    executor.submit(() -> {
        syncService.importShares(List.of(share2), MergeStrategy.MERGE);
        latch.countDown();
    });
    
    latch.await();
    
    // Then - 验证两条记录都成功插入且 ID 不冲突
    List<Share> all = shareRepository.findAll();
    assertEquals(2, all.size());
    assertNotEquals(all.get(0).getId(), all.get(1).getId());
}
```

### 集成测试

```bash
# 1. 启动两个实例
java -jar target/alist-tvbox-1.0.jar --server.port=4567
java -jar target/alist-tvbox-1.0.jar --server.port=4568

# 2. 在 4567 创建测试数据
curl -X POST http://localhost:4567/api/sites -H "Authorization: $TOKEN" \
  -d '{"name":"test-site","url":"http://test"}'

# 3. 测试 Push（版本匹配）
curl -X POST http://localhost:4567/api/sync/push \
  -H "Authorization: $TOKEN" \
  -d '{"remoteUrl":"http://localhost:4568","username":"admin","password":"password","modules":["sites"],"force":false}'

# 4. 验证 4568 已同步
curl http://localhost:4568/api/sites -H "Authorization: $TOKEN2"

# 5. 测试 HTTP 警告（前端手工测试）
# 访问 http://localhost:4567，打开远程同步，输入 http:// 地址，检查是否显示警告
```

---

## 回归测试检查清单

- [ ] 所有现有单元测试通过 (`mvn test`)
- [ ] Push 操作正常（版本匹配时）
- [ ] Push 操作版本不匹配时弹出确认框
- [ ] Pull 操作版本不匹配时弹出确认框
- [ ] 强制同步绕过版本检查
- [ ] 并发导入 Share 不产生 ID 冲突
- [ ] Settings 模块显示敏感数据警告图标
- [ ] HTTP 连接显示安全警告
- [ ] 索引创建成功且查询性能改善

---

## 风险评估

| 修复项 | 风险等级 | 影响范围 | 回退策略 |
|--------|---------|---------|---------|
| 日志格式 | 低 | 仅日志输出 | 无需回退 |
| Share ID | 中 | Share 导入逻辑 | 回退到方案 B (同步块) |
| 类型安全 | 低 | importData 方法 | 使用 SyncData getter 是安全的 |
| Push 版本检查 | 中 | Push 流程 | 增加了一次远程调用，可能轻微影响性能 |
| 前端警告 | 低 | UI 展示 | 纯展示逻辑，无业务影响 |
| 数据库索引 | 中 | 查询性能 | 索引可以删除，数据不受影响 |

---

## 预期收益

- ✅ 消除 2 个 critical bugs（日志、竞争）
- ✅ 提升类型安全，减少运行时错误
- ✅ Push/Pull 对称的版本检查逻辑
- ✅ 用户对敏感数据同步有明确认知
- ✅ 查询性能提升 10-100x（取决于数据量）

---

## 参考资料

- Spring Transaction Propagation: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
- JPA ID Generation Strategies: https://docs.jboss.org/hibernate/orm/6.0/userguide/html_single/Hibernate_User_Guide.html#identifiers
- OkHttp Best Practices: https://square.github.io/okhttp/
