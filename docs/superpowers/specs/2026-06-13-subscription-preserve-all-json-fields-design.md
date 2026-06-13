# 订阅定制：保留所有用户输入的 JSON 字段 + homePage 支持

Date: 2026-06-13

## 背景

订阅定制视觉编辑器 (`SubscriptionConfigEditor.vue`) 把 override JSON 读入行 (row)、让用户编辑建模字段、再通过 `web-ui/src/utils/subscriptionConfig.mjs` 的 `serialize()` 写回。

问题: `serialize()` 与各 `build*Rows` 加载器都用**固定白名单**重建每个对象型 section。任何不在白名单里的字段——`homePage`、`hide`、`extensions` 或任意未知字段——在「读-改-写」一次循环后会被静默丢弃。用户明确要求:**所有用户输入的 JSON 字段都要保留**，且 `homePage` 需要有 GUI 入口。

## 约束 / 非目标

- 纯前端改动。后端 override 合并 (`SubscriptionService.overrideCollection` / `applySiteOverride`) 已是泛化 `putAll`，任何写入 override 的字段都会透传到最终订阅输出；`/catalog` 故意只返回 `{key,name,origin}`。
- **不改动** 后端、DB、entity、catalog、迁移脚本。
- 现有黑白名单 / 全局配置 / 上游字段不被破坏: override 只叠加非空字段，不会冲掉上游已有值。
- 测试逻辑保持纯函数、放在 `subscriptionConfig.mjs`，用 `node:test` (沿用既有约定)。

## 数据流确认

- 上游站点完整对象在生成订阅时由后端从上游 config 合并，编辑器不加载它。编辑器只关心 **override** 中用户已定制的条目。
- `serialize()` 重建以下 7 个 section (字段丢失风险点): `sites`、`parses`、`headers`、`lives`(+groups/channels)、`doh`、`proxy`、`rules`。
- 已安全 (无需改): `flags`/`ads`/`hosts` (原样字符串数组)、标量 `wallpaper`/`logo`/`notice`、所有顶层未知键 (`serialize()` 入口 `JSON.parse(JSON.stringify(baseConfig))` 深拷贝保留)。

## 设计

### 核心机制: 通用 raw 透传袋 (`_extra`)

在 `subscriptionConfig.mjs` 新增纯函数:

```js
// 返回 obj 中不在 modeledKeys 内的所有键 (浅拷贝值)
export function pickExtra(obj, modeledKeys) { ... }
```

- **加载侧**: 每个 `build*Rows` 在映射原始条目时附加 `row._extra = pickExtra(original, MODELED_KEYS_FOR_SECTION)`。
- **序列化侧**: 每个 `build*` 发射 `{ ...row._extra, ...modeledFields }`——`_extra` 在前，建模字段在后 (建模字段权威；其 skip-empty / clear-then-delete 语义不变)。

各 section 建模键 (其余全部透传):

| section | 建模键 (GUI 权威) |
|---|---|
| site (non-custom override) | `name`, `order` ∪ `ADVANCED_OVERRIDE_KEYS` ∪ `homePage` |
| site (custom) | 见下「自定义站点」 |
| parse | `name`, `type`, `url`, `ext` (ext 内建模 `flag`,`header`) |
| header | `host`, `header` |
| live | `LIVE_KEYS` |
| live.group | `name`, `pass`, `channel` |
| live.channel | `CHANNEL_KEYS` |
| doh | `name`, `url`, `ips` |
| proxy | `name`, `hosts`, `urls` |
| rule | `name`, `hosts`, `regex`, `script`, `exclude` |

`ADVANCED_OVERRIDE_KEYS` / `LIVE_KEYS` / `CHANNEL_KEYS` 既有的内容保持不变，仅 site 两处新增 `homePage`。

### 自定义站点 (custom site)

自定义站点行在 `buildRows` 里由 `...s` 整体展开，已携带全部原始字段，故不用 `_extra`。改 `buildCustomSite` 为「发射 row 上除内部 UI 键外的所有非空键」:

内部 UI 键黑名单: `isCustom`, `origin`, `enabled`, `originalName`, `hadNameOverride`, `hasAdvancedOverride`, `styleType`, `styleRatio`, `_extra`。

保留 `homePage` 等所有业务字段；`style` 从 `row.style` 发射，`styleType/styleRatio` 被黑名单排除。

### 非自定义站点 `_extra` 捕获

`.vue` 的 `buildRows` 中，非自定义站点行目前只经 `applyAdvancedOverride` 拷贝建模键。改为同时 `row._extra = pickExtra(ov, MODELED_OVERRIDE_KEYS)`，其中 `MODELED_OVERRIDE_KEYS = { name, order, key } ∪ ADVANCED_OVERRIDE_KEYS`。

`serialize()` 非自定义分支: `const o = { ...(row._extra || {}) }`，再叠加 name/order/advanced (沿用既有 skip-empty 与「仅改过的才发 name」逻辑)，最后补 `key`。

### homePage GUI

`SubscriptionConfigEditor.vue`:
- 自定义站点表单 (`siteForm` / `confirmSiteForm` / 模板): 加 `homePage` 输入框。
- 高级设置弹窗 (`openSiteAdvanced` / 保存回调 / 模板): 加 `homePage` 输入框。
- `resetSiteToDefault` (恢复默认): 清 `homePage` **并清空 `row._extra`** —— 「恢复默认」语义为移除该站点全部 override，含 raw 透传字段。
- `ADVANCED_OVERRIDE_KEYS` 与 `CUSTOM_SITE_KEYS` 各加 `homePage`。

### 嵌套 lives (groups/channels)

`buildLiveRows` 的 `normalizeGroup` / `normalizeChannel` 在加载时用固定键，会丢 extras。需在 group/channel 行也附 `_extra`，并在 `buildGroupsArray` / `buildChannelItem` 发射 `{ ..._extra, ...modeled }`。这是本设计里最繁琐的一处。

## 测试 (`subscriptionConfig.test.mjs`, node:test)

对每个 section (sites/custom-sites/parses/headers/lives/groups/channels/doh/proxy/rules) 验证:
1. 未知字段 round-trip 后保留 (如 site 的 `extensions`/`hide`/`click`；live/parse 的任意键)。
2. `homePage`: 非自定义 + 自定义设置后能发射；raw JSON 里已有 `homePage` 能 round-trip。
3. 清空某建模字段不会删掉透传字段；override 仅叠加非空值。
4. 未触碰的 section 原样保留。
5. 顶层未知键保留 (既有行为，回归保护)。

`pickExtra` 单独单测。

## 风险 / 边界

- 自定义站点「发射除内部键外所有键」需确保不泄漏 UI 内部键 → 靠黑名单 + 单测。
- `_extra` 内若含与建模键同名的键: 建模在后覆盖，符合预期。
- lives 嵌套捕获实现量较大，是主要工作量。
- 上游已有 `homePage` 的站点: 编辑器显示为空，用户不填则 override 不写，上游值保留 (不冲掉)。

## 无遗留问题
