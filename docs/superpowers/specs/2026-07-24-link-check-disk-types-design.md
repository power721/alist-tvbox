# 链接检测 — 按网盘类型开启 (Design)

Date: 2026-07-24
Status: Approved

## Goal

盘搜搜索结果的「链接检测」目前是单一开关：开 = 检测所有网盘类型。新增**按网盘类型多选**，让用户选择对哪些类型做有效性检测。空选 = 检测全部（向后兼容）。

## Scope

UI 位置：`PlayConfig.vue` → 基础配置 tab → 链接检测区。仅盘搜 (PanSou) 搜索链路 (`filterInvalidPanSouLinks`)。不涉及 TG/电报链路。

## Non-goals

不改 `pan_sou_link_check_enabled` / `_max_count` 语义。不改外部 PanSou `/api/check/links` 契约。不做 DB 迁移（settings 为 key/value 表）。

## 支持的 9 种类型（外部 API 实际支持）

`baidu` 百度 · `aliyun` 阿里 · `quark` 夸克 · `tianyi` 天翼 · `uc` UC · `mobile` 移动 · `115` · `xunlei` 迅雷 · `123`

## Behavior

| 主开关 | 类型多选 | 结果 |
|---|---|---|
| 关 | — | 不检测（不变） |
| 开 | 空 | 检测全部 9 种（当前行为） |
| 开 | 选了若干 | 仅检测 选中 ∩ 结果中存在的类型 |

**pikpak / guangya**：当前 `getPanSouCloudType` 映射含二者，会被发给外部 API（不在 9 种内，外部大概率 error/uncertain）。封顶到 9 种后，**二者不再被检测**。（已确认接受）

## Config model

新 setting：`pan_sou_link_check_types`，逗号分隔字符串 ↔ `List<String>`（同 `tg_drivers` / `panSouPlugins` 约定）。null/空 = 全部。

## Edit sites

**Backend**

1. `config/AppProperties.java:47` 后 — 加 `private List<String> panSouLinkCheckTypes;`（Lombok getter/setter）。
2. `service/SettingService.java:115` 后 — load：`parseList(findById("pan_sou_link_check_types").orElse(""))`。
3. `service/SettingService.java:516` 后 — update：`"pan_sou_link_check_types"` → `parseList(value)` → setList。
4. `service/RemoteSearchService.java` `filterInvalidPanSouLinks` (~256-268) — `checkable` 增加过滤：cloud type ∈ enabledSet。enabledSet = SUPPORTED_9 ∩ config（config 空则 SUPPORTED_9）。加常量 `PAN_SOU_CHECK_TYPES = Set.of(9 种)`。
5. `service/sync/SyncService.java:42` — backup 列表加 `"pan_sou_link_check_types"`。

**Frontend (`web-ui/src/components/PlayConfig.vue`)**

6. ref：`const panSouLinkCheckTypes = ref<string[]>([])`（~42 行）。
7. load (~451)：`panSouLinkCheckTypes.value = data.pan_sou_link_check_types ? data.pan_sou_link_check_types.split(',') : []`。
8. `updatePanSouLinkCheck` (~237)：追加 POST `pan_sou_link_check_types` = `panSouLinkCheckTypes.value.join(',')`。
9. 模板 (~497 开关后)：`el-checkbox-group`（`v-if="panSouUrl"`，与上方开关一致），9 项中文标签，hint「留空=检测全部」。未选/非 9 种的类型跳过检测但保留在结果中（不判失效）。

**Settings dump**：`SettingService.java:366` 的 `toMap(name,value)` 已自动覆盖新 row，无需改动。

## Data flow

搜索 → PanSou 结果聚合 → `filterInvalidPanSouLinks`：master 开关? → 数量阈值? → 按 enabledSet 过滤 checkable → 分组(batch 10) → 外部 `/api/check/links` → 过滤 bad/uncertain。

## Testing

- 单测 `RemoteSearchServiceTest`（若不存在则新建）：构造含多类型 link 的 messages，设 `panSouLinkCheckTypes` 子集，断言仅子集类型被检测（mock `checkPanSouLinks` 计数/入参）；空 config → 全部。
- 前端：手测多选持久化 + 刷新回显。

## Compat / Migration

旧 setting 不动。新 setting 缺失 → 空 → 全部 9 种 = 当前行为。settings 表无需 schema 变更。
