# 片单自定义栏目（栏目组合器）设计

状态：设计定稿，未实现。2026-09-18 与用户讨论定方向：数据源适配器 × 多份独立绑定 × 自定义栏目（多对多复用），不为一堆细分榜单各写爬虫。

## 1. 目标与结论

用户诉求：我的追更 / 片单导航的榜单筛选自由组合——例如「豆瓣日漫榜」「豆瓣国漫榜」「纯豆瓣导航」，把数据源与筛选参数封装成独立模块自由拼装。

**结论：可行，且工程量比预想小。** 两个关键现状比设计假设更有利：

1. 「我的追更」与「片单导航」的分类已同源（`PianDanService.category()` / `subscriptionCategory()`，后者就是前者的裁剪版），栏目定义注入一处、两处导航同时生效，不存在同步问题。
2. web 端 `PianDanBrowser` 按分类返回的 `class + filters` 通用渲染（新分类/新筛选自动出现，见记忆「榜单挂筛选网页对话框通用渲染自动出现」），后端输出自定义栏目后前端近乎零改动。

真正的增量在：绑定/栏目的存储与 CRUD、`list()` 前的「栏目 → 绑定 → 参数校验 → 查询」解析层、豆瓣 `recommend?tags=` 条件选片的完全体适配，以及 Filter 之外的内部参数描述结构。

## 2. 组合模型（用户定稿）

```
数据源:豆瓣 / TMDB
  ↓ 提供查询能力
查询类型:原站榜单 / 条件选片 / 趋势 / 本地库
  ↓ 保存独立参数
绑定实例:豆瓣日漫、豆瓣国漫、TMDB动画剧集……(独立 ID,可复用)
  ↓ 一个栏目引用一份或多份绑定
自定义栏目:我的追番、纯豆瓣导航……
  ↓ 选择显示位置
我的追更 / 片单导航
```

绑定三层次参数（定义于绑定，浏览时生效）：

| 层次 | 语义 | 浏览时可否覆盖 |
|---|---|---|
| 固定条件 | 定义绑定性质（动画、剧集、日本） | 否，强制覆盖 |
| 默认条件 | 初值（默认今年、按热度） | 是，可改可清 |
| 开放筛选 | 显示哪些筛选项/顺序/名称/候选值 | ——（面板本身就是它） |

同一绑定可被多栏目引用（改共享绑定影响所有引用栏目，「复制为独立绑定」断开）。源不支持的参数必须显式拒绝（400/校验错误），不静默忽略。

## 3. 上游能力边界（实测代码，2026-09-18 HEAD）

### 豆瓣（TelegramService）

| 查询类型 | 端点 | 可用参数 |
|---|---|---|
| 原站榜单 | `rexxar/api/v2/subject_collection/{type}/items`（`TelegramService.java:801`） | **无筛选参数**，纯固定列表 |
| 近期热播 | `subject/recent_hot/{movie,tv}`（`TelegramService.java:948`） | 仅 region；带 region 改走 recommend |
| 条件选片 | `rexxar/api/v2/{tv,movie}/recommend?tags=…&sort=U`（`TelegramService.java:956-961`） | tags 语法（豆瓣 discover 官方语法）：`类型,地区` 已在用，理论上支持 `电视剧,动画,日本` 多段组合 + 年代/题材词；排序走 query 参数 `sort=U/S/T`（tags 内排序 tag 已失效） |
| 本地库 | `getLocalMovieList` → JPA（`TelegramService.java:835`） | sort/year/genre/region 全支持 |

**关键事实：豆瓣条件选片的唯一组合通路是 `recommend?tags=`。** 2026-09-18 已实测（游客、带 Referer/UA 直连）：

| 验证项 | 结果 |
|---|---|
| 多词组合 | ✅ 类型+题材+地区+年代全维度叠加，最多测到 4 词（`电视剧,悬疑,美国,2025` → total=45）；`动画,日本` 与 `电视剧,动画,日本` 等价（动画词自带剧集语义） |
| 排序 | ✅ 四态：`T` 综合排序（默认）/ `U` 近期热度 / `R` 首播时间 / `S` 高分优先（sort=S 实测高分老片在前，语义正确） |
| 年代词 | ✅ 单年（`2025`）与年代段词（`2010年代`）有效；**范围式 `2022-2024` 不支持**（total=0） |
| 题材叠加 | ✅ `动画,日本,科幻` 有效 |
| 无效词 | ✅ 显式返回 total=0，可检测（不静默） |
| 分页 | ✅ start 按服务端页大小步进；**服务端 limit 恒 20**（传 3/24/30 均返 20 条）；total 封顶 500（25 页）；start=480 深翻页正常 |
| 官方词表 | ✅ 响应自描述 `recommend_categories`：电视剧 21 题材词、综艺 4 词、地区 24 词；年代词不在自描述中但可程序生成（近 20 单年 + 年代段词） |
| movie 侧 | ✅ `/movie/recommend` 同样支持（`动画,日本,2024` → total=123） |

**现存 bug（A/C 均须修）**：`getDoubanItems` 的 `start=(page-1)*size` 按请求 size 步进，而服务端页大小恒 20；web/TVBox 共用 `pianDanList` 固定 size=24（`MediaLibraryController.java:198`，browse 无 size 参数）→ 现有「豆瓣热播+地区」翻页每页漏 4 条（21-24 条永不可见）。修法：recommend 路径 size 钳 20 并按 20 重算 pagecount。

## 3.5 轻量方案 A：豆瓣筛选增强（已实现，2026-09-18）

不建表、不做编辑器，把豆瓣现有分类接上条件筛选——完整栏目组合器的前置子集。已落地：

1. **`getRecommendList` 完全体化**（`TelegramService`）：tags 拼装 类型+地区+题材+年代 多词叠加（LinkedHashSet 去重，固定词序保证缓存 key 稳定）；`sort` 白名单 T/U/R/S 四态、乱值回落 U；**页大小恒 20**（服务端忽略 limit），start 步进与 pagecount 都按 20 算（修复旧 start 按请求 size=24 步进每页漏 4 条的 bug）；沿用传 `URI` 跳过模板编码。
2. **`listDouban` 的 year 参数改为 String**：旧 `parseYear` 数字化会把「2020年代」等年代段词静默丢弃（筛选完全失效）。local 本地库分支内部 `toYear` 转数字，非数字回落不筛选；`/tg-db` 外部 API 的 Integer year 契约不变。
3. **category 六类目带筛选切 recommend**（同 TMDB `upgradeFixedListFilters` 手法）：不带筛选维持 `subject_collection` 原语义。映射表：

   | 类目 id | 映射 tags |
   |---|---|
   | tv_animation | `动画`（叠地区→日漫 `动画,日本` / 国漫 `动画,中国大陆`） |
   | tv_japanese / tv_korean / tv_american | `电视剧,日本` / `电视剧,韩国` / `电视剧,美国` |
   | tv_domestic | `电视剧,中国大陆` |
   | tv_variety_show | `综艺` |

3. **筛选下发**（`addDoubanHotFilters` / `addDoubanCategoryFilters`）：hot_tv/hot_movie 与六类目、lite 模式 `db:category`（分类单选后叠四组）统一下发 地区/题材/年代/排序 四组。词表按官方 `recommend_categories` 硬编码：电视剧 21 题材词、电影 21 词、综艺 4 词、动漫类目用电视剧词表去「动画」；地区 12 词（原 8 词超集）；年代近 20 单年 + 2020/2010/2000 年代段词（范围式不收录）。
4. **类目默认地区**：tv_domestic/tv_american/tv_korean/tv_japanese 带筛选且用户未选地区时回落类目默认（国产剧→中国大陆、欧美剧→美国），显式选择覆盖，与默认相同去重。
5. **billboard 榜单类目不动**（榜单语义）；suggestion/local 分支行为不变（local 年份非数字回落不筛选）。
6. web/TVBox 端零前端改动（filters 下发自动渲染）；追更侧 `subscriptionCategory` 同源自动生效。

测试：TelegramServiceTest 14→21 例（日漫映射/无筛选维持原站/默认地区回落+覆盖去重/排序白名单+服务端分页 pagecount=3/题材+年代叠加/年代段词透传），PianDanServiceTest 断言更新（hot/六类目/lite-category 四组下发），全量 1856 绿（13 skipped 环境门控）。

### TMDB（PianDanService.listTmdb）

discover 全参数天然支持：`with_genres`（16=动画）、`with_origin_country`、`first_air_date_year`、`sort_by`、`vote_average.gte/vote_count.gte` 等，装配逻辑在 `addTmdbQueryFilters`（`PianDanService.java:710`）。「TMDB 动画剧集」绑定 = `discover_tv` + `with_genres=16` + `with_origin_country=JP/CN`，零上游适配。固定榜单带筛选时已有 `upgradeFixedListFilters` 降级到 discover 的机制（`PianDanService.java:621`）可复用。约束：TMDB 深分页 500 页上限（`TMDB_MAX_PAGE` 已有）。

### 评分语义

豆瓣评分（5–10，万人级样本）与 TMDB `vote_average`（0–10，计数差异大）**不可当同一套排名比大小**。混合模式只允许「轮流取条 / 来源优先」，不允许跨源按分数排序；「TMDB 动漫中豆瓣评分>8」属跨源关联查询（二期能力，借 RatingBridge 思路补分，不靠拼接参数）。

## 4. 数据模型

两张新表，uid 归属（同 watchlist 模式），下一个迁移号 **V57**。

```
pd_binding（绑定）
  id, uid, name
  source        VARCHAR  douban | tmdb
  query_type    VARCHAR  subject_collection | recommend | discover | trending | local
  fixed_params    TEXT(JSON)  固定条件
  default_params  TEXT(JSON)  默认条件
  open_filters    TEXT(JSON)  开放筛选拓扑(见 §5)
  created_time, updated_time

pd_column（栏目）
  id, uid, name
  display_mode  VARCHAR  grouped | switch（一期）; mixed 二期
  binding_ids   TEXT     有序逗号分隔/JSON 数组,引用完整性靠删除时校验(引用中的绑定禁删,提示先移出)
  scope         VARCHAR  subscription | nav | both
  sort_order    INT
```

- 绑定多对多复用：bindingIds 列表存引用，不建 join 表（仓库习惯：watchlist 单表简单列；删除校验在服务层）。
- 栏目 type_id 前缀 `col:`（如 `col:12`；分组模式展开 `col:12:0`、`col:12:1`）。避开既有 `douban:` / `tmdb:` / `dbs:` / `s:` 前缀。

## 5. 参数描述结构（Filter 的内部真身）

现状：`Filter` 只有 `key/name/value`（展示定义，`model/Filter.java:8`），参数合法性校验靠 `allowedValue` + 散落的常量白名单（`DOUBAN_CATEGORY_VALUES`、`TRENDING_MEDIA` 等，`PianDanService.java:100-105`）。

新增内部结构（服务端内部，不进 TVBox 协议）：

```
ParameterSpec {
  key           参数名(对内)
  upstream      映射到上游的参数名/装配器引用(douban tags 段序 | tmdb query param)
  type          enum | year | sort | free_text(禁)
  candidates    候选值(枚举)或范围(年份)
  fixedAble     可否进固定条件
}
```

- 每个 `source × query_type` 一张 spec 表（代码内静态注册，不是数据库配置——源能力是代码事实）。
- 绑定的三层参数在保存时按 spec 校验：固定条件只能取 `fixedAble` 参数的合法值；不支持的参数显式拒绝。
- `open_filters` 决定栏目浏览时下发哪些 `Filter`（投影自 spec：候选值可再收窄、可改名、可定序）。
- 编辑器双模式：普通=表单（按 spec 渲染）；高级=编辑经校验的参数 JSON，**不允许任意 URL / 执行代码**。

## 6. 展示模式

| 模式 | 行为 | TVBox 形态 | 分页 |
|---|---|---|---|
| 切换 switch | 一个分类，filter 切换绑定 | 一个 `col:{id}` + binding 单选 filter | 单绑定原生分页，简单 |
| 分组 grouped | 同栏目内分别展示各绑定 | 每绑定一个分类 `col:{id}:{idx}` | 同上 |
| 混合 mixed | 多绑定合成一个列表 | 一个 `col:{id}` | 二期专用聚合 |

**一期的切换/分组都不需要新执行管道**：`col:` 解析层把栏目翻译成「底层 type（`db:recommend` / `tmdb:discover_tv` 等）+ 合成 filters」，走既有 `PianDanService.list()` 分发（`PianDanService.java:559`），并天然进现有 `listCache`（缓存 key 已含 type+page+size+filters，`col:` 翻译结果与手工筛选同 key 复用缓存）。

合并规则（用户定稿，二期实现）：分页每绑定独立游标，轮流取条或来源优先；排序不跨源比分；去重键 = `来源:媒体类型:id(+季)`，无可靠映射不凭标题合并；一个源失败标注结果不完整，空结果不算失败；公共筛选只开放所有绑定都能正确执行的条件，专属条件留在绑定内。

## 7. 消费端接线（本仓库 HEAD 行号）

| 落点 | 位置 | 改动 |
|---|---|---|
| 共用执行入口 | `PianDanService.list()` `PianDanService.java:559` | 前置 `col:` 解析：栏目 → 绑定 → spec 校验 → 翻译成底层 type+filters 后走既有分发 |
| 分类产出 | `PianDanService.category()` / `subscriptionCategory()` `PianDanService.java:138-153` | 注入 `scope` 匹配的自定义栏目（switch=1 分类+binding filter；grouped=每绑定 1 分类），`col:` 前缀 |
| TVBox 路由识别 | `MediaLibraryController.isPianDanId()` `MediaLibraryController.java:122` | 加 `col:` 前缀（列表 + 详情 id 分发处同步） |
| 固定条件执行 | 解析层 | 固定条件强制覆盖用户 extend 值；默认条件作初值；extend 里的非开放参数丢弃 |
| REST CRUD | 新 Controller | `/api/piandan/bindings`、`/api/piandan/columns`，currentUid 归属校验（同 WatchlistController 模式） |
| web 编辑器 | web-ui | PianDanBrowser 加自定义栏目入口 + 管理对话框（普通表单/高级 JSON 双模式）；浏览侧零改动（通用渲染） |
| 存储 | 新表 V57 | 见 §4；**不得**放 SubscriptionSourceService 的全局 Setting（那是全局 extend，非 per-uid）也不塞 UserPreference.config（uid 单行 JSON，多特性互相覆盖） |
| 豆瓣适配 | `TelegramService.getRecommend`（现 `getDoubanItems` 内联） | tags 多段拼装 + `sort=U/S/T` 三态 + year，完全体化 |

## 8. 分期

**一期（核心闭环，可独立发布）**
- V57 两表 + 实体 + CRUD + 归属校验
- `col:` 解析层 + category()/subscriptionCategory() 注入 + MediaLibraryController 路由
- switch / grouped 两模式；固定/默认/开放三层参数；spec 校验显式拒绝
- 豆瓣 recommend 完全体（先实测 tags 组合与 sort=S）
- web 编辑器普通表单模式

**二期**
- mixed 混合模式（§6 合并规则全套）
- 跨源关联查询（TMDB 条目补豆瓣评分，RatingBridge 思路）

**三期（可选）**
- 高级 JSON 编辑模式、TG Bot 栏目浏览（TG 走同一 category 管道，栏目自动出现，交互另行设计）

## 9. 风险与前置验证

1. ~~豆瓣 tags 多词组合未实测~~ **2026-09-18 已实测通过**（§3 表格）：多词叠加/四态排序/年代词全部成立；范围式年代（2022-2024）不支持，候选值不收录即可；无效词 total=0 显式可检测。剩余风险=非官方接口的长期稳定性与词表演变（筛选候选值硬编码，接口变更时表现为 total=0 空列表而非错乱数据）。
2. 豆瓣 tags 双重编码坑已有注释（`TelegramService.java:969`）：新拼装沿用传 `URI` 跳过模板编码。
3. TMDB discover 组合（genres+country+year+sort）均已验证在用，无新风险。
4. 多用户：绑定/栏目 uid 隔离；TVBox 端 browse 按 currentUid（watchlist 同款管道）。
5. 缓存：`col:` 翻译后进现有 listCache，注意固定条件参与 key（不同栏目同参数共享缓存是正确行为）。
