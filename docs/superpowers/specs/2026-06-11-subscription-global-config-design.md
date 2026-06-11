# 订阅定制全局配置设计

## 需求背景

现有订阅定制功能是针对单个订阅的。需要增强为支持全局配置，订阅自己的定制可以覆盖全局配置。

**关键场景：**
- 全局黑名单：site1
- 订阅3希望包含 site1，其它订阅排除 site1
- 订阅1除了排除 site1，还希望排除 site2

## 设计方案

### 方案选择：简化的覆盖策略

**黑名单处理规则：**
```
IF 订阅有 sites-blacklist:
    使用订阅的 sites-blacklist（完全覆盖，忽略全局）
ELSE IF 订阅有 sites-whitelist:
    最终黑名单 = 全局黑名单 - 订阅白名单
ELSE:
    使用全局黑名单
```

**优势：**
- 逻辑更简单，只需两个字段：sites-blacklist（覆盖）和 sites-whitelist（救回）
- 场景1（订阅1排除 site1+site2）：直接用 sites-blacklist: ["site1", "site2"]
- 场景2（订阅3只救回 site1）：用 sites-whitelist: ["site1"]
- 完全兼容现有逻辑

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

**订阅 override 字段语义：**
```json
{
  "sites-blacklist": ["siteX", "siteY"],  // 完全覆盖全局黑名单（与全局互斥）
  "sites-whitelist": ["site1"],           // 从全局黑名单中救回（与全局配合）
  ...其他现有字段
}
```

**注意：** `sites-blacklist` 和 `sites-whitelist` 互斥，不能同时使用

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

### 黑名单处理算法

**输入：**
- `globalBlacklist`：全局黑名单
- `subscriptionBlacklist`：订阅黑名单（可选）
- `subscriptionWhitelist`：订阅白名单（可选）

**输出：**
```
IF subscriptionBlacklist 存在:
    finalBlacklist = subscriptionBlacklist  // 完全覆盖
ELSE IF subscriptionWhitelist 存在:
    finalBlacklist = globalBlacklist - subscriptionWhitelist  // 救回
ELSE:
    finalBlacklist = globalBlacklist  // 继承
```

**实现位置：**
- 修改 `SubscriptionService.subscription()` 方法
- 修改 `removeBlacklist()` 方法处理新的黑名单逻辑

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

// 5. 处理黑名单逻辑（修改现有逻辑）
handleWhitelist(config);
removeBlacklist(config);  // 修改此方法实现新的覆盖/救回逻辑
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
- 显示提示："该订阅继承全局配置"
- 提供选项："使用白名单（从全局黑名单救回站点）" 或 "使用自定义黑名单（完全覆盖全局）"
- `sites-whitelist` 输入框：选择要从全局黑名单救回的站点
- `sites-blacklist` 输入框：自定义黑名单（与白名单互斥）

## 实现步骤

1. **后端 - Service 层**
   - 新增 `getGlobalConfig()` 方法
   - 新增 `updateGlobalConfig()` 方法
   - 新增 `applyGlobalConfig()` 方法
   - 修改 `removeBlacklist()` 方法实现新的覆盖/救回逻辑

2. **后端 - Controller 层**
   - 新增 `GET /api/subscription/global-config` 接口
   - 新增 `PUT /api/subscription/global-config` 接口

3. **前端 - 管理界面**
   - 订阅管理页面增加"全局配置"入口
   - 实现全局配置编辑对话框
   - 订阅编辑页面增加白名单/黑名单字段（互斥选择）

4. **测试**
   - 单元测试：黑名单覆盖/救回逻辑
   - 集成测试：全局配置 + 订阅配置组合场景

## 测试场景

### 场景1：全局黑名单，订阅3救回 site1
- 全局：`{"sites-blacklist": ["site1", "site2"]}`
- 订阅3：`{"sites-whitelist": ["site1"]}`
- 订阅3最终黑名单：`["site2"]`
- 其他订阅最终黑名单：`["site1", "site2"]`

### 场景2：订阅1完全覆盖（排除 site1 + site2）
- 全局：`{"sites-blacklist": ["siteX"]}`
- 订阅1：`{"sites-blacklist": ["site1", "site2"]}`
- 订阅1最终黑名单：`["site1", "site2"]`（忽略全局）

### 场景3：订阅2同时救回 site1
- 全局：`{"sites-blacklist": ["site1", "site2"]}`
- 订阅2：`{"sites-whitelist": ["site1"]}`
- 订阅2最终黑名单：`["site2"]`

## 兼容性考虑

**向后兼容：**
- 不配置全局配置时，行为与现有逻辑完全一致
- 订阅的 `override` 字段继续支持现有所有字段
- 新增的 `sites-whitelist` 为可选字段

**优先级规则：**
1. 订阅 `sites-blacklist`：完全覆盖，忽略全局
2. 订阅 `sites-whitelist`：从全局黑名单中救回站点
3. 全局 `sites-blacklist`：作为默认值

**互斥约束：**
- 订阅的 `sites-blacklist` 和 `sites-whitelist` 不能同时使用
- 如果同时存在，`sites-blacklist` 优先（完全覆盖模式）

## 未来扩展

**可能的增强：**
- 全局白名单（所有订阅默认只包含白名单中的站点）
- 全局配置模板（预设多套全局配置供快速切换）
- 订阅组概念（多个订阅共享一套配置）
