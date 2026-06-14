# 配置远程同步功能设计文档

**日期**: 2026-06-14  
**功能**: 实现配置数据在不同 AList-TvBox 实例间的推送和拉取同步

---

## 一、需求概述

实现配置远程同步功能，允许用户在多个 AList-TvBox 实例之间同步配置数据。支持：
- **双向同步**：推送到远端、从远端拉取
- **模块选择**：用户可选择要同步的配置模块（外部站点、网盘分享、账号、订阅等）
- **合并策略**：拉取时支持覆盖或智能合并
- **版本校验**：默认仅允许相同版本同步，不同版本需 `force=true`
- **用户认证**：使用用户名密码登录远端（比 API Key 更友好）
- **临时操作**：不持久化远端配置，零时同步

---

## 二、架构设计

### 2.1 整体架构

采用**轻量级 JSON 同步**方案：

**核心组件**：
- `SyncController`：REST API（连接测试、导出、导入）
- `SyncService`：业务逻辑（导出、合并、版本校验）
- `RemoteClient`：HTTP 客户端（调用远端 API、认证）
- DTO 类：`SyncData`, `SyncRequest`, `SyncResponse` 等（位于 `cn.har01d.alist_tvbox.dto`）

**数据流**：

**推送流程**：
```
本地前端 → SyncController.push() 
  → RemoteClient 登录远端获取 token
  → 本地 SyncService.exportData() 导出数据
  → RemoteClient 调用远端 /api/sync/import
  → 远端 SyncService.importData() 合并数据
```

**拉取流程**：
```
本地前端 → SyncController.pull()
  → RemoteClient 登录远端获取 token
  → RemoteClient 调用远端 /api/sync/export
  → 本地 SyncService.importData() 合并数据
```

---

## 三、数据模型

### 3.1 同步数据结构

**SyncData** (`cn.har01d.alist_tvbox.dto.SyncData`)：
```json
{
  "appVersion": "1.0.0",
  "modules": {
    "sites": [...],           // List<Site>
    "shares": [...],          // List<Share>
    "accounts": [...],        // List<Account>
    "driverAccounts": [...],  // List<DriverAccount>
    "pikpakAccounts": [...],  // List<PikPakAccount>
    "subscriptions": [...],   // List<Subscription>
    "plugins": [...],         // List<Plugin>
    "pluginFilters": [...],   // List<PluginFilter>
    "settings": {...}         // Map<String, String>（白名单内的Setting）
  }
}
```

### 3.2 业务键定义

用于合并模式下判断记录是否已存在（更新 vs 插入）：

| 实体 | 业务键 | 说明 |
|------|--------|------|
| Site | `url` | 同一 URL 视为同一站点 |
| Share | `type:shareId` | 格式如 `5:46ce214f4ed7`（夸克分享） |
| Account | `nickname` | 昵称唯一 |
| DriverAccount | `type + username`（优先）<br>`type + name`（回退） | 优先用 username，无则用 name |
| PikPakAccount | `username` | 用户名唯一 |
| Subscription | `url` | 订阅源 URL 唯一 |
| Plugin | `externalId`（优先）<br>`url`（回退） | 兼容旧版本（无 externalId） |
| PluginFilter | `url` | 过滤器脚本 URL 唯一 |
| Setting | `name`（key） | Setting 本身以 name 为主键 |

### 3.3 Setting 白名单

**仅同步以下 Setting**（排除系统标识、运行时状态）：

**B站相关**：
- `bilibili_cookie`, `bilibili_qn`, `bilibili_dash`, `bilibili_heartbeat`, `bilibili_searchable`

**搜索相关**：
- `tg_search`, `tg_search_api_key`, `tg_drivers`, `tgDriverOrder`, `tg_timeout`, `tg_sort_field`
- `pan_sou_url`, `pan_sou_source`, `pan_sou_channels`, `pan_sou_username`, `pan_sou_password`
- `pan_sou_link_check_enabled`, `pan_sou_link_check_max_count`, `panSouPlugins`
- `search_excluded_paths`, `search_index_source`

**功能开关**：
- `merge_site_source`, `mix_site_source`, `replace_ali_token`, `clean_invalid_shares`
- `temp_share_expiration`, `validateSharesInterval`
- `video_cover`, `use_quark_tv`, `plugin_run_mode`

**开放API**：
- `open_token_url`, `open_api_client_id`, `open_api_client_secret`

**配置**：
- `local_proxy_config`, `offline_download_config`, `global_subscription_override`

**其他**：
- `user_agent`, `tmdb_api_key`, `debug_log`

**排除字段**（不同步）：
- 系统标识：`api_key`, `system_id`, `install_mode`
- 版本信息：`app_version`, `alist_version`
- 运行时状态：Plugin/PluginFilter 的 `lastCheckedAt`, `lastError`；Account 的 `checkinTime`, `checkinDays`

---

## 四、API 设计

### 4.1 连接测试

**端点**：`POST /api/sync/connect`

**请求**：
```json
{
  "url": "http://192.168.1.100:4567",
  "username": "admin",
  "password": "password"
}
```

**响应**：
```json
{
  "success": true,
  "appVersion": "1.0.0",
  "token": "session-token-xxx",
  "message": "连接成功"
}
```

**说明**：
- 调用远端 `/api/login` 或使用 Basic Auth 获取 session token
- token 保存在内存中（临时使用，不持久化）
- 返回远端版本号用于版本校验

---

### 4.2 导出数据

**端点**：`GET /api/sync/export?modules=sites,shares,accounts`

**请求头**：`Authorization: Bearer <token>`

**查询参数**：
- `modules`：逗号分隔的模块列表

**响应**：
```json
{
  "appVersion": "1.0.0",
  "modules": {
    "sites": [
      {"id": 1, "name": "站点1", "url": "http://example.com", ...}
    ],
    "shares": [
      {"id": 1, "type": 5, "shareId": "46ce214f4ed7", "path": "/夸克分享", ...}
    ],
    ...
  }
}
```

**说明**：
- 供远端调用（拉取时用）
- 需要认证（token 或 API Key）
- 根据 `modules` 参数导出对应模块
- Share 导出时包含业务键计算

---

### 4.3 导入数据

**端点**：`POST /api/sync/import`

**请求头**：`Authorization: Bearer <token>`

**请求体**：
```json
{
  "appVersion": "1.0.0",
  "strategy": "merge",
  "force": false,
  "data": {
    "modules": { ... }
  }
}
```

**参数说明**：
- `strategy`：`"overwrite"`（覆盖）或 `"merge"`（合并）
- `force`：是否强制同步（版本不匹配时）
- `data`：SyncData 对象

**响应**：
```json
{
  "success": true,
  "results": {
    "sites": {"imported": 5, "updated": 3, "failed": 0},
    "shares": {"imported": 10, "updated": 2, "failed": 1, "errors": ["5:xxx 导入失败: 业务键冲突"]},
    "accounts": {"imported": 1, "updated": 0, "failed": 0},
    ...
  }
}
```

**说明**：
- 供远端调用（推送时用）
- 需要认证
- 按模块独立处理，部分失败不影响其他模块

---

### 4.4 本地调用端点

**推送**：`POST /api/sync/push`

**请求体**：
```json
{
  "remoteUrl": "http://192.168.1.100:4567",
  "username": "admin",
  "password": "password",
  "modules": ["sites", "shares", "accounts"],
  "force": false
}
```

**拉取**：`POST /api/sync/pull`

**请求体**：
```json
{
  "remoteUrl": "http://192.168.1.100:4567",
  "username": "admin",
  "password": "password",
  "modules": ["sites", "shares"],
  "strategy": "merge",
  "force": false
}
```

**响应**：同 `/api/sync/import` 的响应格式

---

## 五、业务逻辑

### 5.1 合并策略

**覆盖模式（overwrite）**：
1. 删除本地选中模块的所有数据
2. 插入远端数据（ID 由本地数据库重新生成）

**合并模式（merge）**：
1. 遍历远端数据
2. 根据业务键查找本地记录
   - **存在**：更新本地记录（保留本地 ID，更新其他字段）
   - **不存在**：插入新记录（ID 自动生成）
3. 本地独有的记录：保留不变

**业务键匹配示例（DriverAccount）**：
```java
DriverAccount existing = null;
if (StringUtils.isNotBlank(remote.getUsername())) {
    existing = driverAccountRepository
        .findByTypeAndUsername(remote.getType(), remote.getUsername())
        .orElse(null);
}
if (existing == null && StringUtils.isNotBlank(remote.getName())) {
    existing = driverAccountRepository
        .findByTypeAndName(remote.getType(), remote.getName())
        .orElse(null);
}

if (existing != null) {
    // 更新：保留 ID，更新其他字段
    existing.setCookie(remote.getCookie());
    existing.setToken(remote.getToken());
    // ...
    driverAccountRepository.save(existing);
} else {
    // 插入：ID 自动生成
    remote.setId(null);
    driverAccountRepository.save(remote);
}
```

### 5.2 版本校验

**规则**：
- 导出数据时包含 `appVersion`（从 `Setting` 表的 `app_version` 读取）
- 导入前检查：`if (!remoteVersion.equals(localVersion) && !force)`
- 版本不匹配且未强制：抛出 `VersionMismatchException`
- 前端捕获异常后弹窗确认，用户选择"强制同步"则 `force=true` 重试

**版本不匹配提示**：
```
版本不一致：
  本地：1.0.0
  远端：1.1.0
强制同步可能导致数据不兼容，是否继续？
```

### 5.3 Share 业务键处理

**导出时**：
```java
// 为每个 Share 计算业务键
String businessKey = share.getType() + ":" + share.getShareId();
// 前端或导入端可使用此键匹配
```

**导入时**：
```java
// 解析业务键
String[] parts = businessKey.split(":");
Integer type = Integer.parseInt(parts[0]);
String shareId = parts[1];

// 查找本地记录
Optional<Share> existing = shareRepository.findByTypeAndShareId(type, shareId);
```

### 5.4 同步顺序

按依赖关系排序，避免外键约束问题：

1. `settings`（系统配置，无依赖）
2. `sites`（外部站点，无依赖）
3. `accounts`（阿里云账号，无依赖）
4. `driverAccounts`（网盘账号，无依赖）
5. `pikpakAccounts`（PikPak账号，无依赖）
6. `shares`（网盘分享，可能关联 Site）
7. `plugins`（插件，Subscription 可能引用）
8. `pluginFilters`（过滤器，可能关联 Plugin）
9. `subscriptions`（订阅配置，最后处理）

---

## 六、异常处理

### 6.1 错误分类

**连接阶段**：
- 网络不可达：`无法连接到远端服务器，请检查地址和网络`
- 认证失败：`用户名或密码错误`
- 远端不支持：`远端服务器不支持同步功能（可能不是 AList-TvBox）`

**版本校验**：
- 版本不匹配：`版本不一致（本地 1.0.0，远端 1.1.0），可能导致数据不兼容。是否强制同步？`

**同步阶段**：
- 部分模块失败：继续执行其他模块，最后汇总结果
- 业务键冲突：记录具体冲突项（如 `DriverAccount type=5, username=test 导入失败`）
- 数据库约束违反：捕获并转换为友好提示

### 6.2 事务处理

**模块级事务**：
- 每个模块使用独立事务（`@Transactional(propagation = REQUIRES_NEW)`）
- 单个模块失败不影响其他模块
- 记录每个模块的成功/失败状态

**示例**：
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public SyncResult importSites(List<Site> sites, MergeStrategy strategy) {
    // 导入 Site 模块
    // 失败会回滚此模块，不影响其他模块
}
```

### 6.3 日志记录

```java
log.info("开始同步，方向: {}, 模块: {}, 策略: {}", direction, modules, strategy);
log.info("模块 {} 同步完成: 新增 {}, 更新 {}, 失败 {}", module, imported, updated, failed);
log.error("模块 {} 同步失败", module, exception);
```

---

## 七、前端交互设计

### 7.1 UI 入口

**位置**：设置页面（`/settings`）添加"远程同步"按钮

**交互**：点击按钮弹出 Element Plus Dialog，展示三步流程

---

### 7.2 步骤 1：连接远端

```
┌─────────────────────────────────────┐
│ 远程同步                        ✕   │
├─────────────────────────────────────┤
│ 步骤 1/3：连接远端服务器             │
│                                     │
│ 远端地址：[http://192.168.1.100:4567]│
│ 用户名：  [admin                   ] │
│ 密码：    [••••••••               ] │
│                                     │
│          [取消]  [连接并继续 →]     │
└─────────────────────────────────────┘
```

**操作**：
- 填写远端地址、用户名、密码
- 点击"连接并继续"调用 `/api/sync/connect`
- 成功：显示远端版本，进入步骤 2
- 失败：显示错误提示（认证失败、网络错误等）

---

### 7.3 步骤 2：配置同步

```
┌─────────────────────────────────────┐
│ 远程同步                        ✕   │
├─────────────────────────────────────┤
│ 步骤 2/3：配置同步选项               │
│                                     │
│ 远端版本：1.0.0  ✓ 版本匹配         │
│                                     │
│ 同步方向：                          │
│  ○ 推送到远端  ○ 从远端拉取         │
│                                     │
│ 合并策略（仅拉取时）：               │
│  ○ 覆盖本地  ○ 智能合并             │
│                                     │
│ 选择模块：                          │
│  ☑ 外部站点 (Sites)                 │
│  ☑ 网盘分享 (Shares)                │
│  ☑ 阿里云账号 (Accounts)            │
│  ☑ 网盘账号 (DriverAccounts)        │
│  ☑ PikPak账号 (PikPakAccounts)      │
│  ☑ 订阅配置 (Subscriptions)         │
│  ☑ 系统设置 (Settings)              │
│                                     │
│      [← 上一步]  [取消]  [执行同步]  │
└─────────────────────────────────────┘
```

**说明**：
- **订阅配置**：包含 Subscription, Plugin, PluginFilter 三个表（作为一个逻辑模块）
- **系统设置**：仅包含白名单内的 Setting
- 版本不匹配时显示警告图标，用户仍可选择继续（后端会校验 `force` 参数）

---

### 7.4 步骤 3：同步结果

```
┌─────────────────────────────────────┐
│ 远程同步                        ✕   │
├─────────────────────────────────────┤
│ 步骤 3/3：同步完成                   │
│                                     │
│ ✓ 外部站点：新增 3，更新 2           │
│ ✓ 网盘分享：新增 10，更新 5          │
│ ✓ 阿里云账号：新增 1，更新 0         │
│ ✓ 网盘账号：新增 2，更新 1           │
│ ✗ PikPak账号：失败 1 条              │
│   - username重复: test@example.com   │
│ ✓ 订阅配置：新增 0，更新 3           │
│ ✓ 系统设置：已更新 15 项             │
│                                     │
│               [关闭]                │
└─────────────────────────────────────┘
```

**说明**：
- 显示每个模块的同步结果（成功/失败、新增/更新数量）
- 失败的模块展开显示具体错误
- 用户点击"关闭"完成同步流程

---

## 八、实现细节

### 8.1 核心类结构

**SyncService** (`cn.har01d.alist_tvbox.service.SyncService`)：
```java
// 导出数据
SyncData exportData(List<String> modules);

// 导入数据（返回各模块结果）
Map<String, SyncResult> importData(SyncData data, MergeStrategy strategy, boolean force);

// 推送到远端
SyncResponse push(String remoteUrl, String username, String password, List<String> modules);

// 从远端拉取
SyncResponse pull(String remoteUrl, String username, String password, 
                  List<String> modules, MergeStrategy strategy, boolean force);
```

**RemoteClient** (`cn.har01d.alist_tvbox.service.RemoteClient`)：
```java
// 登录获取 token
String login(String remoteUrl, String username, String password);

// 调用远端导出 API
SyncData fetchRemoteData(String remoteUrl, String token, List<String> modules);

// 调用远端导入 API
Map<String, SyncResult> pushToRemote(String remoteUrl, String token, SyncData data, 
                                      MergeStrategy strategy, boolean force);
```

**SyncController** (`cn.har01d.alist_tvbox.web.SyncController`)：
```java
@PostMapping("/connect")
ConnectionResult connect(@RequestBody ConnectionInfo info);

@GetMapping("/export")
SyncData export(@RequestParam List<String> modules);

@PostMapping("/import")
SyncResponse importData(@RequestBody SyncRequest request);

@PostMapping("/push")
SyncResponse push(@RequestBody SyncRequest request);

@PostMapping("/pull")
SyncResponse pull(@RequestBody SyncRequest request);
```

---

### 8.2 DTO 类清单

所有 DTO 位于 `cn.har01d.alist_tvbox.dto` 包：

- **SyncData**：同步数据容器（appVersion + modules）
- **SyncRequest**：推送/拉取请求（remoteUrl, username, password, modules, strategy, force）
- **SyncResponse**：同步响应（success, results）
- **SyncResult**：单个模块结果（imported, updated, failed, errors）
- **ConnectionInfo**：连接测试请求（url, username, password）
- **ConnectionResult**：连接测试响应（success, appVersion, token, message）
- **MergeStrategy**：枚举（OVERWRITE, MERGE）

---

### 8.3 Repository 扩展

需要为部分实体添加按业务键查询的方法：

**ShareRepository**：
```java
Optional<Share> findByTypeAndShareId(Integer type, String shareId);
```

**SiteRepository**：
```java
Optional<Site> findByUrl(String url);
```

**AccountRepository**：
```java
Optional<Account> findByNickname(String nickname);
```

**DriverAccountRepository**：
```java
Optional<DriverAccount> findByTypeAndUsername(DriverType type, String username);
Optional<DriverAccount> findByTypeAndName(DriverType type, String name);
```

**PikPakAccountRepository**：
```java
Optional<PikPakAccount> findByUsername(String username);
```

**SubscriptionRepository**：
```java
Optional<Subscription> findByUrl(String url);
```

**PluginRepository**：
```java
Optional<Plugin> findByExternalId(String externalId);
Optional<Plugin> findByUrl(String url);
```

**PluginFilterRepository**：
```java
Optional<PluginFilter> findByUrl(String url);
```

---

## 九、安全考虑

### 9.1 认证

- 所有同步端点（`/export`, `/import`）需要认证（Bearer token 或 API Key）
- 推送/拉取操作通过用户名密码登录获取临时 token
- Token 仅保存在内存，不持久化

### 9.2 敏感数据

**保留同步**（用于快速迁移）：
- 凭证类：token, password, cookie 等

**排除同步**（保持独立）：
- 系统标识：`api_key`, `system_id`, `install_mode`
- 运行时状态：`lastCheckedAt`, `lastError`, `checkinTime` 等

### 9.3 数据验证

- 版本校验：防止不兼容版本导致数据错误
- 业务键去重：避免重复记录
- 模块级事务：失败回滚，不影响其他模块

---

## 十、测试要点

### 10.1 单元测试

- `SyncService.exportData()`：验证各模块导出正确
- `SyncService.importData()`：
  - 覆盖模式：删除旧数据，插入新数据
  - 合并模式：业务键匹配、更新 vs 插入
- `RemoteClient.login()`：登录成功/失败场景
- 版本校验：相同版本通过，不同版本 `force=false` 拦截

### 10.2 集成测试

- 推送流程：本地 → 远端成功接收
- 拉取流程：远端 → 本地成功合并
- 部分失败：一个模块失败，其他模块继续
- 网络错误：连接超时、认证失败处理

### 10.3 手动测试

- 用户名密码登录：正确/错误凭证
- 版本不匹配：弹窗确认、强制同步
- 模块选择：选择不同组合，验证正确同步
- 合并策略：覆盖 vs 合并效果
- 前端交互：三步流程、错误提示、结果展示

---

## 十一、后续优化方向

1. **增量同步**：仅同步变更的记录（需要时间戳或版本号）
2. **批量操作优化**：大数据量时分批导入（避免内存溢出）
3. **冲突解决 UI**：显示冲突列表，用户手动选择保留哪一方
4. **配置模板**：保存常用的远端配置和模块选择
5. **定时同步**：支持周期性自动同步（需持久化配置）
6. **双向合并**：推送时也支持合并策略（目前推送=远端覆盖）

---

## 十二、关键决策记录

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 认证方式 | 用户名密码 | 比 API Key 更用户友好 |
| 版本校验 | 默认强制，可绕过 | 避免不兼容导致数据错误 |
| 合并策略 | 基于业务键 | 不同系统 ID 冲突，业务键更可靠 |
| 配置存储 | 不存储（临时） | 零时同步，不增加持久化复杂度 |
| Setting 同步 | 白名单模式 | 安全可控，避免系统标识被覆盖 |
| 事务粒度 | 模块级独立事务 | 部分失败不影响其他模块 |
| 数据格式 | JSON | 简单直观，易于调试和扩展 |
| 订阅配置 | 包含 Plugin/PluginFilter | 作为逻辑模块一起同步 |

---

## 附录：模块代码清单

**新增文件**：
- `SyncController.java`
- `SyncService.java`
- `RemoteClient.java`
- `dto/SyncData.java`
- `dto/SyncRequest.java`
- `dto/SyncResponse.java`
- `dto/SyncResult.java`
- `dto/ConnectionInfo.java`
- `dto/ConnectionResult.java`
- `dto/MergeStrategy.java`（枚举）
- `exception/VersionMismatchException.java`
- `web-ui/src/components/RemoteSync.vue`

**修改文件**：
- Repository 接口：添加按业务键查询方法
- 设置页面：添加"远程同步"按钮

**预计代码量**：600-800 行（Java + Vue）
