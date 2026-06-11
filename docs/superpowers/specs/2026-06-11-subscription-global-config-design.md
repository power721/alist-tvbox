# 订阅定制全局配置设计

## 需求背景

现有订阅定制功能是针对单个订阅的。需要增强为支持全局配置，订阅自己的定制可以覆盖全局配置。

**关键场景：**
- 全局黑名单：site1
- 订阅3希望包含 site1，其它订阅排除 site1
- 订阅1除了排除 site1，还希望排除 site2

## 设计方案

### 方案选择：黑名单合并策略

**黑名单合并公式：**
```
最终黑名单 = (全局黑名单 - 订阅白名单) ∪ 订阅额外黑名单
```

**优势：**
- 逻辑清晰，精确解决黑名单场景
- 订阅白名单可以"救回"全局黑名单中的站点
- 支持订阅级别的额外排除

## 数据模型

### Setting 表新增配置

**配置键名：** `global_subscription_override`

**配置值结构（JSON）：**
```json
{
  "sites-blacklist": ["site1", "site2"],
  "sites": [...],
  "lives": [...],
  "rules": [...],
  "parses": [...]
}
```

**注意：** 全局配置不支持 `spider` 字段（spider 仅在订阅源或订阅级别配置）

### Subscription 实体

**现有字段保持不变：**
- `override` (TEXT)：订阅级别定制配置

**订阅 override 新增支持字段：**
```json
{
  "sites-whitelist": ["site1"],           // 从全局黑名单中救回
  "sites-blacklist-extra": ["site2"],     // 额外排除
  ...其他现有字段
}
```

## 配置应用逻辑

### 处理流程

```
1. 加载订阅源配置（apiUrl）
   ↓
2. 应用全局配置（除了 spider）
   ↓
3. 应用订阅级别配置（覆盖全局）
   ↓
4. 处理黑名单合并逻辑
   ↓
5. 白名单过滤
```

### 黑名单合并算法

**输入：**
- `globalBlacklist`：全局黑名单
- `subscriptionWhitelist`：订阅白名单
- `subscriptionBlacklistExtra`：订阅额外黑名单

**输出：**
```
finalBlacklist = (globalBlacklist - subscriptionWhitelist) + subscriptionBlacklistExtra
```

**实现位置：**
- 修改 `SubscriptionService.subscription()` 方法
- 修改 `removeBlacklist()` 方法处理新的黑名单合并逻辑

### 代码层面的关键点

**在 `subscription()` 方法中的插入点：**
```java
// 1. 加载订阅源配置
for (String url : apiUrl.split(",")) {
    overrideConfig(config, fixUrl(url.trim()), prefix, getConfigData(url.trim()));
}

// 2. 应用全局配置（新增）
applyGlobalConfig(config);

// 3. 排序
sortSites(config, sort);

// 4. 应用订阅级别配置
if (StringUtils.isNotBlank(override)) {
    config = overrideConfig(config, override);
}

// 5. 处理黑名单合并（修改现有逻辑）
handleWhitelist(config);
removeBlacklist(config);  // 修改此方法实现合并逻辑
```

## API 设计

### 管理接口

#### 获取全局配置
```
GET /api/subscription/global-config
```

**响应：**
```json
{
  "sites-blacklist": ["site1", "site2"],
  "sites": [...],
  "lives": [...],
  "rules": [...],
  "parses": [...]
}
```

#### 更新全局配置
```
PUT /api/subscription/global-config
Content-Type: application/json

{
  "sites-blacklist": ["site1"],
  "sites": [...]
}
```

**响应：** 200 OK

### 前端集成

**订阅管理页面：**
- 增加"全局配置"按钮/入口
- 打开配置编辑对话框（JSON 编辑器或表单）

**订阅编辑页面：**
- 显示提示："该订阅继承全局配置，可通过白名单/额外黑名单定制"
- `sites-whitelist` 输入框：选择要从全局黑名单救回的站点
- `sites-blacklist-extra` 输入框：选择要额外排除的站点

## 实现步骤

1. **后端 - Service 层**
   - 新增 `getGlobalConfig()` 方法
   - 新增 `updateGlobalConfig()` 方法
   - 新增 `applyGlobalConfig()` 方法
   - 修改 `removeBlacklist()` 方法实现黑名单合并逻辑

2. **后端 - Controller 层**
   - 新增 `GET /api/subscription/global-config` 接口
   - 新增 `PUT /api/subscription/global-config` 接口

3. **前端 - 管理界面**
   - 订阅管理页面增加"全局配置"入口
   - 实现全局配置编辑对话框
   - 订阅编辑页面增加白名单/额外黑名单字段

4. **测试**
   - 单元测试：黑名单合并逻辑
   - 集成测试：全局配置 + 订阅配置组合场景

## 测试场景

### 场景1：全局黑名单，订阅3救回 site1
- 全局：`{"sites-blacklist": ["site1", "site2"]}`
- 订阅3：`{"sites-whitelist": ["site1"]}`
- 订阅3最终黑名单：`["site2"]`
- 其他订阅最终黑名单：`["site1", "site2"]`

### 场景2：订阅1额外排除 site3
- 全局：`{"sites-blacklist": ["site1"]}`
- 订阅1：`{"sites-blacklist-extra": ["site3"]}`
- 订阅1最终黑名单：`["site1", "site3"]`

### 场景3：订阅2同时救回和额外排除
- 全局：`{"sites-blacklist": ["site1", "site2"]}`
- 订阅2：`{"sites-whitelist": ["site1"], "sites-blacklist-extra": ["site3"]}`
- 订阅2最终黑名单：`["site2", "site3"]`

### 场景4：订阅完全自定义（不继承全局）
- 全局：`{"sites-blacklist": ["site1"]}`
- 订阅4：`{"sites-blacklist": ["siteX"]}`（使用传统 sites-blacklist）
- 订阅4最终黑名单：`["siteX"]`（忽略全局）

## 兼容性考虑

**向后兼容：**
- 不配置全局配置时，行为与现有逻辑完全一致
- 订阅的 `override` 字段继续支持现有所有字段
- 新增的 `sites-whitelist` 和 `sites-blacklist-extra` 为可选字段

**优先级规则：**
1. 订阅 `sites-blacklist`（传统字段）：完全覆盖，不继承全局
2. 订阅 `sites-whitelist` + `sites-blacklist-extra`：合并逻辑
3. 全局 `sites-blacklist`：作为默认值

## 未来扩展

**可能的增强：**
- 全局白名单（所有订阅默认只包含白名单中的站点）
- 全局配置模板（预设多套全局配置供快速切换）
- 订阅组概念（多个订阅共享一套配置）
