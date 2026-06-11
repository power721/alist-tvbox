# 订阅定制全局配置设计

## 需求背景

现有订阅定制功能是针对单个订阅的。需要增强为支持全局配置，订阅自己的定制可以覆盖全局配置。

**关键场景：**
- 全局黑名单：site1
- 订阅3希望包含 site1，其它订阅排除 site1
- 订阅1除了排除 site1，还希望排除 site2

## 设计方案

### 方案选择：简化的替换策略

**白名单/黑名单处理规则：**
```
IF 订阅有 sites-whitelist:
    使用订阅的 sites-whitelist（完全替换全局，白名单模式）
ELSE IF 订阅有 sites-blacklist:
    使用订阅的 sites-blacklist（完全替换全局，黑名单模式）
ELSE IF 全局有 sites-whitelist:
    使用全局的 sites-whitelist（白名单模式）
ELSE:
    使用全局的 sites-blacklist（黑名单模式）
```

**优势：**
- 逻辑最简单：订阅级别完全替换全局，不是合并或救回
- 支持白名单和黑名单两种模式
- 白名单优先级高于黑名单（互斥使用）
- 完全兼容现有逻辑

## 数据模型

### Setting 表新增配置

**配置键名：** `global_subscription_override`

**配置值结构（JSON）：**
```json
{
  "sites-whitelist": ["site1", "site3"],  // 全局白名单（与黑名单互斥）
  "sites-blacklist": ["site2"],           // 全局黑名单
  "sites": [...],
  "lives": [...],
  "rules": [...],
  "parses": [...]
}
```

**注意：** 
- 全局配置不支持 `spider` 字段（spider 仅在订阅源或订阅级别配置）
- `sites-whitelist` 和 `sites-blacklist` 互斥，白名单优先

### Subscription 实体

**现有字段保持不变：**
- `override` (TEXT)：订阅级别定制配置

**订阅 override 字段语义：**
```json
{
  "sites-whitelist": ["site1"],           // 完全替换全局白名单（白名单模式）
  "sites-blacklist": ["siteX", "siteY"],  // 完全替换全局黑名单（黑名单模式）
  ...其他现有字段
}
```

**注意：** 
- `sites-whitelist` 和 `sites-blacklist` 互斥，不能同时使用
- 白名单模式下只包含指定站点，黑名单失效
- 订阅级别配置完全替换全局配置，不是合并

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
- `globalWhitelist`：全局白名单（可选）
- `globalBlacklist`：全局黑名单（可选）
- `subscriptionWhitelist`：订阅白名单（可选）
- `subscriptionBlacklist`：订阅黑名单（可选）

**输出：**
```
IF subscriptionWhitelist 存在:
    finalWhitelist = subscriptionWhitelist  // 完全替换全局白名单
    finalBlacklist = 空  // 白名单模式下忽略黑名单
ELSE IF subscriptionBlacklist 存在:
    finalWhitelist = 空  // 黑名单模式下忽略白名单
    finalBlacklist = subscriptionBlacklist  // 完全覆盖全局黑名单
ELSE IF globalWhitelist 存在:
    finalWhitelist = globalWhitelist
    finalBlacklist = 空  // 全局白名单模式下忽略黑名单
ELSE:
    finalWhitelist = 空
    finalBlacklist = globalBlacklist  // 继承全局黑名单
```

**关键规则：**
- 白名单和黑名单互斥，白名单优先级更高
- 订阅级别配置完全替换全局配置（不是合并）
- 全局白名单存在时，全局黑名单被忽略

**实现位置：**
- 修改 `SubscriptionService.subscription()` 方法
- 修改 `handleWhitelist()` 和 `removeBlacklist()` 方法处理新逻辑

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
- 提供选项："使用白名单模式" 或 "使用黑名单模式"
- `sites-whitelist` 输入框：指定要包含的站点（完全替换全局）
- `sites-blacklist` 输入框：指定要排除的站点（完全替换全局）
- 两者互斥选择

## 实现步骤

1. **后端 - Service 层**
   - 新增 `getGlobalConfig()` 方法
   - 新增 `updateGlobalConfig()` 方法
   - 新增 `applyGlobalConfig()` 方法
   - 修改 `handleWhitelist()` 和 `removeBlacklist()` 方法实现新的替换逻辑

2. **后端 - Controller 层**
   - 新增 `GET /api/subscription/global-config` 接口
   - 新增 `PUT /api/subscription/global-config` 接口

3. **前端 - 管理界面**
   - 订阅管理页面增加"全局配置"入口
   - 实现全局配置编辑对话框
   - 订阅编辑页面增加白名单/黑名单字段（互斥选择）

4. **测试**
   - 单元测试：白名单/黑名单替换逻辑
   - 集成测试：全局配置 + 订阅配置组合场景

## 测试场景

### 场景1：全局白名单 + 订阅替换为黑名单
- 全局：`{"sites-whitelist": ["site1"]}`
- 订阅1：`{"sites-blacklist": ["site2"]}`
- 订阅1最终：排除 site2，其他全部包含
- 其他订阅最终：只包含 site1

### 场景2：全局黑名单 + 订阅替换为白名单
- 全局：`{"sites-blacklist": ["site1", "site2"]}`
- 订阅3：`{"sites-whitelist": ["site1"]}`
- 订阅3最终：只包含 site1
- 其他订阅最终：排除 site1 和 site2

### 场景3：全局黑名单 + 订阅自定义黑名单
- 全局：`{"sites-blacklist": ["siteX"]}`
- 订阅2：`{"sites-blacklist": ["site1", "site2"]}`
- 订阅2最终：排除 site1 和 site2（忽略全局的 siteX）

### 场景4：全局白名单优先于全局黑名单
- 全局：`{"sites-whitelist": ["site1"], "sites-blacklist": ["site2"]}`
- 订阅（无自定义）最终：只包含 site1（黑名单被忽略）

## 兼容性考虑

**向后兼容：**
- 不配置全局配置时，行为与现有逻辑完全一致
- 订阅的 `override` 字段继续支持现有所有字段
- `sites-whitelist` 和 `sites-blacklist` 均为可选字段

**优先级规则：**
1. 订阅 `sites-whitelist`：完全替换全局配置（白名单模式）
2. 订阅 `sites-blacklist`：完全替换全局配置（黑名单模式）
3. 全局 `sites-whitelist`：作为默认白名单
4. 全局 `sites-blacklist`：作为默认黑名单

**互斥约束：**
- 订阅的 `sites-whitelist` 和 `sites-blacklist` 不能同时使用
- 全局的 `sites-whitelist` 和 `sites-blacklist` 可以同时配置，但白名单优先
- 如果同时存在，白名单生效，黑名单被忽略

## 未来扩展

**可能的增强：**
- 全局白名单（所有订阅默认只包含白名单中的站点）
- 全局配置模板（预设多套全局配置供快速切换）
- 订阅组概念（多个订阅共享一套配置）
