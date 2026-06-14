# 订阅配置可视化编辑器 — 设计文档

- 日期: 2026-06-11
- 状态: 待评审(v2,新增目录端点)
- 相关代码: `SubscriptionService#subscription / overrideConfig / handleWhitelist / removeBlacklist / addSite`,`SubscriptionSourceService#findEnabledSources`
- 参考: FongMi TV `VodConfig` 数据模型 (Site/Parse/Rule/Doh/Style)

## 1. 背景与问题

订阅定制 (`Subscription.override`) 与全局配置 (`Setting:GLOBAL_SUBSCRIPTION_OVERRIDE`) 目前都靠用户手填一段 JSON。绝大多数用户不知道字段格式,无法使用。需要图形界面来配置,覆盖最常见操作:禁用/改名/排序站点(含内置源与插件的黑/白名单)、新增自定义站点、新增/禁用解析,以及壁纸、spider、flags、ads 等。

## 2. 目标 / 非目标

**目标**
- 用一个可复用的图形编辑器替代两处原始 JSON 输入(单订阅「定制」+「全局配置」)。
- 覆盖常用字段表单化;少用字段仍可在「原始JSON」标签编辑。
- 让用户从**真实站点/解析目录**里勾选,而非盲填 key;目录含上游、内置源、插件三类。
- 单订阅可把**任意站点(含内置源/插件)**加入黑名单或白名单。
- 向后兼容现有 override 数据;不破坏现有白/黑名单与全局配置行为。

**非目标**
- 不为 `rules` / `doh` / `replace` / `parse`(单默认解析) / `home` / `blacklist.lives` / `blacklist.rules` 做表单(仅 JSON 标签)。
- 不改内置源/插件的**改名/排序/启停管理本身**(仍在「订阅源管理」);编辑器对内置/插件仅做黑/白名单。
- 不引入新持久化表;仍存进 `Subscription.override` / 全局 Setting。

## 3. 关键决策(已与用户确认)

1. 布局:**独立标签页式大对话框**(站点 / 解析 / 基础 / 原始JSON),按钮打开;全局配置对话框内嵌同组件。
2. 站点目录范围:**全部已知站点**——上游(订阅 URL 带来)、内置源(`csp_AList` 等)、插件。三类都可加入黑/白名单;但**改名/排序仅限上游站点**(内置/插件经 override 会与 `addSite` 注入冲突,其名称/顺序在「订阅源管理」里改)。
3. 站点目录来源:**新增只读端点 `GET /api/subscriptions/{sid}/catalog`**,在应用本订阅 `override` 之前生成基础目录 → 全量、真实名称、不受当前黑/白名单影响。每项带 `origin`(upstream/builtin/plugin)、`key`、`name`。
4. 过滤/禁用输出格式:**优先 `blacklist:{sites,parses,...}` 对象**;顶层 `sites-blacklist` 仅读旧数据时兼容,保存迁移进 `blacklist.sites`。白名单无对象格式,仍用顶层 `sites-whitelist`。
5. 后端过滤:`subscription()` 第 618 行 `applySitesFilter(config)` → `handleWhitelist(config); removeBlacklist(config);`,并**删除**已无人调用的 `applySitesFilter / handleWhitelistFilter / handleBlacklistFilter`。
6. 复用:同一编辑器组件用于单订阅与全局;全局模式加「参考订阅」下拉(默认第一个订阅)提供目录。

## 4. 后端改动

### 4.1 黑/白名单解析与应用(行为性改动,核心算法)

把 `subscription()` 第 618 行 `applySitesFilter(config)` 替换为一次显式「全局 vs 订阅」解析 + 应用。全局与订阅各自的过滤配置**不合并,订阅完全替换全局**;白/黑名单互斥,白名单优先。

**输入**(均可选,"存在"=present 且非空):
- `globalWhitelist` = 全局 `sites-whitelist`
- `globalBlacklist` = 全局 `blacklist` 对象(或 `sites-blacklist`,归一)
- `subWhitelist` = 订阅 override `sites-whitelist`
- `subBlacklist` = 订阅 override `blacklist`(或 `sites-blacklist`,归一)

**解析(优先级)**:
```
IF subWhitelist 存在:      final = WHITELIST(subWhitelist)   // 忽略一切黑名单
ELSE IF subBlacklist 存在:  final = BLACKLIST(subBlacklist)   // 完全覆盖全局黑名单
ELSE IF globalWhitelist 存在: final = WHITELIST(globalWhitelist) // 忽略全局黑名单
ELSE:                       final = BLACKLIST(globalBlacklist) // 继承全局黑名单(可能为空)
```

**应用**:
- 白名单模式:按 key 仅保留 `finalWhitelist` 站点;**忽略所有黑名单(含 `blacklist.parses`)**。
- 黑名单模式:按 `finalBlacklist` 对象移除(sites 按 key、parses/lives/rules 按 name),并始终移除 key=`Alist1`(现有逻辑保留)。`finalBlacklist` 为空时**仅**移除 `Alist1`。

**实现**(复用用户指定的 `handleWhitelist`/`removeBlacklist` 作为应用原语):
- 新增 `static void resolveAndApplyFilters(Map<String,Object> config, Map<String,Object> globalConfig, Map<String,Object> override)`:先清除 config 内既有过滤键(`sites-whitelist`/`sites-blacklist`/`blacklist`,避免外泄/重复),按上表选定后**只置一种**过滤键并调用 `handleWhitelist(config)`(白名单)**或** `removeBlacklist(config)`(黑名单)。
- 把 `handleWhitelist`/`removeBlacklist` 改为 `static`(二者仅用 config + log)→ 便于 map 级纯函数单测。
- 调用处:`resolveAndApplyFilters(config, getGlobalConfig(), parseOverride(override));`(新增 `parseOverride` 私有方法,容错 null/非法 JSON → 返回空 map)。
- 删除死代码:`applySitesFilter`、`handleWhitelistFilter`、`handleBlacklistFilter`(仅彼此引用)。

> 过滤在 `addSite`(注入内置/插件)之后运行,故按 key 黑/白名单对内置源、插件同样生效。

### 4.2 行为不变性 / 防回归

- 叠加顺序:`applyGlobalConfig` → `overrideConfig`(单订阅)→ `addSite` → `resolveAndApplyFilters`。
- **不改 `applyGlobalConfig`**(降低风险):它仍可能把全局过滤键"缺则补"进 config,也可能被 `overrideConfig` 与订阅键合并——但 `resolveAndApplyFilters` **不读 config 里的过滤键**,而是从 `getGlobalConfig()` 与 `parseOverride(override)` 两个原始来源重新取值,并在应用前**清除 config 内既有过滤键**,故合并后的残留被丢弃重算,不影响结果、也不外泄到输出。
- 行为变化提示:现行 live 代码(`applySitesFilter`)在黑名单模式**不**移除 `Alist1`;改用 `removeBlacklist` 后黑名单/继承模式会移除 `Alist1`——此为用户要求的目标行为,需在测试中固化。白名单模式不调用 `removeBlacklist`,与现行 live 行为一致。

### 4.3 新增目录端点(只读)

- `SubscriptionController`: `GET /api/subscriptions/{sid}/catalog` → `subscriptionService.getCatalog(sid)`。
- `SubscriptionService.getCatalog(String sid)` 返回:
  ```json
  { "sites": [ {"key":"csp_Bili","name":"哔哩","origin":"upstream"},
               {"key":"csp_AList","name":"🟢 AList","origin":"builtin"},
               {"key":"我的插件","name":"我的插件","origin":"plugin"} ],
    "parses": [ {"name":"虾米"}, {"name":"Json并发"} ] }
  ```
- 生成逻辑(隔离实现,不动 `subscription()` 主路径,均为读操作):
  1. 空 `config`,按 `sub.getUrl()` 逗号拆分,对每段 `overrideConfig(config, fixUrl(u), prefix, getConfigData(u))` 做上游合并(沿用现有方法)。
  2. 上游站点 = `config.sites` 里 key 不属于内置/插件的项 → `origin=upstream`,取其 `key`/`name`。
  3. 内置/插件 = 遍历 `subscriptionSourceService.findEnabledSources()`:`builtin()` 项 → `key=siteKey()`(特例:`csp_Push`→`push_agent`),`origin=builtin`;`plugin()` 项 → `key=plugin.getName()`,`origin=plugin`;`name` 取 `source.name()`/`plugin.getName()`。
  4. 解析 = `config.parses` 的 `name` 列表(无则空)。
  - **不**应用本订阅 `override`、**不**应用过滤、**不**应用全局过滤键 → 目录为全量候选。

## 5. 前端架构

### 5.1 组件

- 新增 `web-ui/src/components/SubscriptionConfigEditor.vue`(主编辑器)。
- 自定义站点子表单:内联抽屉/子对话框;过大则拆 `SubscriptionSiteForm.vue`。
- 改 `web-ui/src/views/SubscriptionsView.vue`:
  - 订阅编辑对话框:`override` 文本框 → `[🎨 可视化编辑]` 按钮打开编辑器(编辑器内含「原始JSON」兜底)。移除现有临时白/黑名单输入逻辑(并入编辑器站点标签)。
  - 全局配置对话框:移除原始 JSON 文本框 + 临时白/黑名单输入,内嵌 `SubscriptionConfigEditor`(global 模式 + 参考订阅下拉)。

### 5.2 Props / 数据流

```
SubscriptionConfigEditor
  props: modelValue: string        // override JSON 串(空串表示无)
         mode: 'subscription'|'global'
         referenceSid: string      // 抓目录用;全局模式来自「参考订阅」下拉
  emits: update:modelValue(string) // 保存时回传 JSON 串(或 '')
```

- 唯一真源 = 解析后的 `config` 响应式对象;表单视图与「原始JSON」视图都是它的投影。
- 表单只读写**已建模键**;未建模键(`rules`/`doh`/`replace`/`parse`/`home`/`blacklist.lives`/`blacklist.rules`)原样保留。
- 打开时:`parse(modelValue)` → `GET /api/subscriptions/{referenceSid}/catalog` 取目录 → 客户端把当前 override 叠加到目录上,还原勾选/改名/排序状态。

### 5.3 标签页内容

**① 站点**
- 过滤模式 radio:`继承全局`(none,仅 subscription 模式) / `白名单` / `黑名单`。
- 站点目录表格,列:拖拽手柄(排序) · `启用` 复选 · `key`(只读) · `来源`标签(上游/内置/插件) · `名称`(输入,预填目录原名;**内置/插件行只读**) · `排序`(数字;**内置/插件行禁用**)。
  - `启用` 关 → 加入禁用集合;开 → 移出。**对全部三类生效**(走黑/白名单)。
  - `名称`(仅上游):改动过、或本就有改名 override 时才写入 `sites[].name`。
  - `排序`(仅上游) / 拖拽 → 写入 `sites[].order`(同样仅改动/已有时写)。
- `自定义站点` 列表 + `[添加自定义站点]` → 子表单(见 5.4)。
- 目录抓取失败 / 新订阅未保存 → 隐藏目录表格,仅显示「自定义站点」+ 提示;过滤模式仍可用(手动填 key,可选)。

**② 解析**
- 解析目录表格:`name`(只读) · `启用` 复选 → 关闭写入 `blacklist.parses`(按 name)。
- `自定义解析` 列表 + `[添加]` → 表单:`name` · `type`(下拉) · `url` · `ext.flag`(标签输入) · `ext.header`(键值对)。

**③ 基础**
- `wall`(壁纸 URL) · `spider`(jar URL;**global 模式禁用并提示**,因 `applyGlobalConfig` 跳过 spider) · `flags`(标签,追加到上游) · `ads`(标签,追加)。

**④ 原始JSON**
- 整个 override 的 JSON 文本框,双向同步 + 语法校验。高级字段(rules/doh/replace/parse/home 等)的出口。
- 切到此标签前把表单状态序列化进 `config`;离开/保存时若此处有合法编辑则解析回 `config`,非法则提示且不丢文本。

### 5.4 自定义站点子表单字段(来自 CatVod `Site`)

- `key`*(唯一) · `name` · `type`(下拉:0=CMS(xml) / 1=CMS(json) / 3=Spider(jar/drpy) / 4=外部)
- `api` · `ext`(文本域,字符串或 JSON 对象) · `jar`
- `searchable`(0否/1是/2聚合) · `quickSearch`(开关) · `filterable`(开关) · `changeable`(开关)
- `style`(`type`:rect/oval/list + `ratio`) · `indexs`(开关 0/1) · `timeout` · `order`
- 折叠「更多」:`categories`(标签) · `header`(键值对) · `playUrl` · `click`

### 5.5 内部状态 ↔ config 映射

打开(读)时:
- `filterMode`: 有 `sites-whitelist`→whitelist;有 `sites-blacklist` 或 `blacklist.sites`→blacklist;否则 none。
- `disabledSiteKeys` = `sites-blacklist`(兼容) ∪ `blacklist.sites`。
- `whitelistKeys` = `sites-whitelist`。
- `disabledParseNames` = `blacklist.parses`。
- 目录行 = catalog.sites(全量,带 origin/真实 name);叠加:`启用` = key 不在禁用集合(黑名单)/ key 在白名单(白名单模式);`名称` = override `sites[key].name` 优先,否则目录原名;`排序` = override `sites[key].order`。
- 目录里缺失但禁用集合/白名单里出现的 key(理论上少见)→ 合成行兜底(name 用目录补不到时取 key)。
- `config.sites` 中 key ∈ 目录 → 局部 override(改名/排序);key ∉ 目录 → 自定义站点。
- `customParses` = `config.parses`;`wall/spider/flags/ads` = 同名键。

保存(写)时(原地改同一 `config`,保留未知键):
- 删顶层 `sites-blacklist`(迁移);whitelist 模式写 `sites-whitelist`,否则删。
- 重建 `config.blacklist`:`sites`=禁用集合(仅 blacklist 模式) · `parses`=禁用解析名(与站点模式无关,始终写) · `lives`/`rules` 保留原值;整体为空则删 `blacklist`。
- 重建 `config.sites` = 各局部 override(`{key,name?,order?}`,仅上游) + 自定义站点;为空删键。
- `config.parses` = 自定义解析;为空删键。
- `wall/spider/flags/ads`:非空写,空删。
- 其余键不动。`JSON.stringify(config)`;空对象回传 `''`。

## 6. 涉及文件

- 新增 `web-ui/src/components/SubscriptionConfigEditor.vue`
- (可选)新增 `web-ui/src/components/SubscriptionSiteForm.vue`
- 改 `web-ui/src/views/SubscriptionsView.vue`(接入按钮 + 全局内嵌 + 移除旧临时白/黑名单逻辑)
- 改 `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`(过滤替换 + 删死代码 + 新增 `getCatalog`)
- 改 `src/main/java/cn/har01d/alist_tvbox/web/SubscriptionController.java`(新增 catalog 端点)
- 测试:后端 `SubscriptionServiceTest`(过滤 + catalog);前端 `SubscriptionsView.test.mjs` / 编辑器测试

## 7. 实施分期

1. **后端过滤修复(先做,独立)**:替换 618 行 + 删死代码 + 单测,确保不破坏现有行为。
2. **后端 catalog 端点**:`getCatalog` + controller + 单测。
3. **编辑器组件**:数据模型 + 四标签 + JSON 往返。
4. **接入**:订阅编辑按钮 + 全局内嵌 + 参考订阅下拉。
5. **测试与打磨**。

## 8. 测试

**后端 `SubscriptionServiceTest`**(`resolveAndApplyFilters` 做 map 级纯函数测试,覆盖真值表)
- 单 whitelist:`sites-whitelist` 仅保留白名单;`sites-blacklist`(顶层)与 `blacklist.sites` 移除;`blacklist.parses` 按 name 移除解析。
- 真值表(global × subscription):
  1. subWhitelist 存在 → 仅用 subWhitelist,忽略 subBlacklist/global/`blacklist.parses`。
  2. 仅 subBlacklist → 用 subBlacklist,忽略 global。
  3. 仅 globalWhitelist → 用 globalWhitelist。
  4. 仅 globalBlacklist → 用 globalBlacklist。
  5. subWhitelist + subBlacklist 同存 → whitelist 胜。
  6. globalWhitelist + globalBlacklist 同存 → whitelist 胜。
  7. 全空 → 仅移除 `Alist1`。
  8. sub 任意存在时,完全替换 global(非合并)。
- 内置/插件 key 可被黑/白名单(过滤在 addSite 后)。
- catalog:返回上游 + 内置 + 插件三类、带 origin,且**未**套用 override/过滤。

**前端**(扩展 `SubscriptionsView.test.mjs`,Node `node:test` + 源码断言风格;复杂逻辑可抽纯函数单测)
- JSON 往返:载入 override → 表单 → 保存,语义等价。
- 保留未知键:含 `rules`/`doh`/`replace` 的 override 经表单编辑后不丢。
- 过滤模式映射:三模式 ↔ `sites-whitelist`/`blacklist.sites` 互转;旧 `sites-blacklist` 载入后保存迁移为 `blacklist.sites`。
- 内置/插件行 `名称`/`排序` 禁用、`启用` 可用。
- 自定义站点子表单字段正确序列化(type/style/ext)。

## 9. 边界与风险

- 内置/插件经 override 改名会因 `addSite` 在 `overrideConfig` 之后注入而产生重复 key → 通过「改名/排序仅上游」规避;内置/插件仅黑/白名单(过滤在最后,安全)。
- catalog 端点需联网解析上游,可能慢 → loading 态 + 失败降级为手动添加。
- 全局模式 `spider` 不生效(`applyGlobalConfig` 跳过)→ 字段禁用并提示。
- `push_agent`/`csp_AList` 等内置 key 的最终形态需与 `addSite` 一致(catalog 做同样映射),否则黑/白名单 key 对不上。

## 10. 未决问题

无(设计阶段问题均已确认)。
