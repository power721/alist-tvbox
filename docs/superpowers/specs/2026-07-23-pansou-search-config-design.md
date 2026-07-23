# PanSou 盘搜 — 新增搜索配置项

**Date:** 2026-07-23
**Author:** design via brainstorming
**Status:** approved → awaiting implementation plan

## 1. Goal

The 盘搜 (PanSou) playback-config panel (`PlayConfig.vue`) currently exposes only a subset of the upstream `/api/search` parameters (URL, `src`, `channels`, auth, `plugins`, link-check, and `cloud_types` indirectly via `tgDrivers`). The upstream API supports more knobs that are either absent from the request DTO or hardcoded. This change exposes four of them as configurable options and gives PanSou its own dedicated tab.

## 2. Scope — four new configurable params

| Param | Today | After |
|---|---|---|
| `conc` (并发数) | not in DTO | new DTO field; configurable; blank/0 = omit → upstream auto (channels+plugins+10) |
| `refresh` (强制刷新) | not in DTO | new DTO field; configurable switch; default off (cache preserved) |
| `res` (结果类型) | DTO field, hardcoded `"merge"` | configurable select: `merge`/`results`/`all`; default `merge` |
| `filter` (关键词过滤) | DTO field exists but `setFilter(...)` commented out in `search()` | activated; global `include`/`exclude` lists sent upstream (OR semantics), layered on top of local filtering |

## 3. Out of scope

- `ext` (扩展参数) — remains hardcoded `referer`.
- Explicit `cloud_types` override — remains derived from `tgDrivers`.
- Per-subscription-override of PanSou params — PanSou stays **global-only** (consistent with all existing `pan_sou_*` settings).
- Per-query exposure of these params on the search-results page — global config only.
- 网盘顺序 / 排序字段 stay in `基本配置` (shared TG/general config).

## 4. Approach

Follow the existing flat `pan_sou_*` setting pattern (rejected alternative: a nested `PanSouSearchConfig` sub-object — diverges from the established pattern, over-engineered for 4 params).

## 5. Backend changes

### 5.1 `dto/pansou/SearchRequest.java`
Add two fields:
```java
private Integer conc;        // null → omit from JSON
private Boolean refresh;     // null → omit from JSON
```
`res` and `filter` already exist. **Required:** add `@JsonInclude(JsonInclude.Include.NON_NULL)` **on the two new fields only** (`conc`, `refresh`) so a null value is omitted from the JSON entirely. The upstream API doc keys auto-behavior on "param not provided" (absent), not on `"conc": null`, so absence must be guaranteed — setting the field to null is not enough. Use field-level (not class-level) annotation so the existing nullable fields (`plugins`, `cloudTypes`, `filter`, `ext`) keep their current serialization unchanged.

### 5.2 `config/AppProperties.java`
Add (alongside existing `panSou*` fields):
```java
private Integer panSouConc;                 // null/0 = auto
private Boolean panSouRefresh = false;
private String panSouRes = "merge";
private List<String> panSouFilterInclude;
private List<String> panSouFilterExclude;
```

### 5.3 `service/SettingService.java`
Add key mappings for `pan_sou_conc`, `pan_sou_refresh`, `pan_sou_res`, `pan_sou_filter_include`, `pan_sou_filter_exclude` in **both**:
- the bootstrap load block (parse to the correct types; `include`/`exclude` comma-split → `List<String>`, mirroring `panSouPlugins`), and
- the update switch (write/normalize; `include`/`exclude` comma-split; `conc` numeric; `refresh` boolean; `res` one of `merge|results|all`).

### 5.4 `service/RemoteSearchService.search()`
After building the request, set the four params with skip-when-empty semantics:
```java
if (appProperties.getPanSouConc() != null && appProperties.getPanSouConc() > 0) {
    request.setConc(appProperties.getPanSouConc());
}
if (Boolean.TRUE.equals(appProperties.getPanSouRefresh())) {
    request.setRefresh(true);
}
request.setRes(StringUtils.defaultIfBlank(appProperties.getPanSouRes(), "merge"));
List<String> inc = appProperties.getPanSouFilterInclude();
List<String> exc = appProperties.getPanSouFilterExclude();
if (!CollectionUtils.isEmpty(inc) || !CollectionUtils.isEmpty(exc)) {
    request.setFilter(new SearchRequest.Filter(
        CollectionUtils.isEmpty(inc) ? List.of() : inc,
        CollectionUtils.isEmpty(exc) ? List.of() : exc));
}
```
This replaces the currently-commented-out `setFilter(...)` line (192). Local post-filtering (`isMatched(result, keyword)`, `tgDrivers`, link-check) is untouched.

## 6. Frontend changes — `web-ui/src/components/PlayConfig.vue`

Add a new tab and move all PanSou-* items out of `基本配置` into it:

```html
<el-tab-pane label="盘搜" name="pansou">
  <!-- moved: PanSou地址, 用户名/密码, 数据源, 频道列表, 插件, 链接检测+上限 -->
  <!-- new "搜索行为" group: conc, refresh, res, filter(include/exclude) -->
</el-tab-pane>
```

- **网盘顺序 / 排序字段 / 搜索超时 / TG-Search** stay in `基本配置`.
- New refs: `panSouConc`, `panSouRefresh`, `panSouRes`, `panSouFilterInclude` (string, comma-sep), `panSouFilterExclude` (string, comma-sep).
- Controls:
  - `conc` — `el-input-number`, placeholder `自动`.
  - `refresh` — `el-switch`.
  - `res` — `el-select` (聚合 `merge` / 仅结果 `results` / 全部 `all`).
  - `filter` — two `el-input` (包含词 / 排除词), comma-separated.
- The four new params share **one** `更新` button (`updatePanSouSearch`) that POSTs all four settings to `/api/settings` (conc blank/0 → empty string).
- Load: extend the existing `GET /api/settings` handler to populate the new refs (`pan_sou_conc`, `pan_sou_refresh`, `pan_sou_res`, `pan_sou_filter_include`, `pan_sou_filter_exclude`).

## 7. Data flow

`PlayConfig.vue` → `POST /api/settings` → `SettingService` (validate + normalize + persist to `Setting` table + mutate `AppProperties`) → `RemoteSearchService.search()` reads `AppProperties` → builds `SearchRequest` (skip-when-empty) → POST upstream `{panSouUrl}/api/search` → results served via `/pansou` TvBox endpoint.

## 8. Backward compatibility (critical)

When all new fields are unset, the upstream request is **byte-identical to today** (modulo the now-activated `filter` being absent):
- `conc` null/0 → omitted → upstream auto.
- `refresh` false → omitted → caching preserved.
- `res` unset → `"merge"` (current).
- `filter` both lists empty → omitted → current behavior.
- Existing settings keys/behavior unchanged.

## 9. Error handling

- Invalid `conc` (non-numeric / negative) → frontend `el-input-number` constrains; backend clamps to `>0` or treats `<=0` as omit.
- Invalid `res` value → normalized to `"merge"`.
- Upstream errors already bubble up via the existing `try/catch` → `IllegalStateException` in `search()`; no new error path needed.

## 10. Testing

- **Backend unit:** `RemoteSearchServiceTest` — verify `SearchRequest` JSON omits `conc`/`refresh`/`filter` when unset (backward-compat), and includes them with correct values when set. Verify `res` defaults to `merge`.
- **SettingService:** round-trip of the 5 new keys (incl. comma-split for include/exclude).
- **Frontend:** extend `PlayConfig.test.mjs` — new refs render in the 盘搜 tab, `updatePanSouSearch` posts the 4 settings, load populates them.
- **Native image note:** no new DTO packages; `SearchRequest.Filter` already in scanned `dto/pansou`. No reflect-config/resource changes needed.

## 11. Resolved decisions

- Approach A (flat `pan_sou_*` pattern). ✅
- Global-only config, not subscription override. ✅
- PanSou config extracted into a dedicated `盘搜` tab; 网盘顺序/排序字段 stay in `基本配置`. ✅
- `filter` = global include/exclude sent upstream, layered over local filtering. ✅
- New params grouped under one `搜索行为` 更新 button. ✅
