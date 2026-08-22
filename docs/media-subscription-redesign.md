# 追剧系统重新设计（v2）

> v1 设计见 [media-subscription-design.md](./media-subscription-design.md)，其中大部分决策仍然成立（固定挂载路径、候选池、三级递进巡检、搜索源与元数据分层）。
> 本文只记录 **v2 变更的部分**：诊断出的 8 个缺陷、新数据模型、选源算法、迁移与实施计划。

诊断基线：分支 `feat/media-subscription` @ `ad9b137a`。

---

## 1. 现有实现分析

`feat/media-subscription` 已交付约 11k 行、28 个 commit，覆盖了绝大部分需求：

| 能力 | 实现位置 | 状态 |
|---|---|---|
| 逐集选源 + 逐源回退 | `MediaSubscriptionService.playEpisode:747` | 已实现（转存 > 主源 > 补缺） |
| 固定挂载路径不断链 | `/追剧/{id}-{名称}`，换源只改 storage 配置 | 已实现，v2 保留为地基 |
| 候选池 | `media_subscription_resource` | 已实现 |
| 元数据分层 | `metadata/` 四个 Provider + `MetadataHealth` 熔断 | 已实现 |
| TVBox 内置源 | `csp_Media` + 逻辑链接 `msubep-{sub}-{ep}` | 已实现 |
| 多线路 | `buildTvBoxPlayLines`（逻辑线 + 分盘线） | 已实现 |
| 调度与退避 | 每小时 sweep + `next_check_time` + 短轮窗口 + BAD 冷却 | 已实现 |
| 自动转存 | `MediaSubscriptionTransferService`（pan:/ali: 多目标） | 已实现 |
| 通知 | 事件流（11 类）+ Telegram | 已实现 |

**没有实现的**：Episode 实体、Episode↔Resource 关系落库、资源状态机（只有 `OK/BAD/UNKNOWN` + `active`/`gap` 两个布尔）、观看进度、客户端播放失败反馈、追更任务视图。

---

## 2. 存在的问题

八个缺陷，按严重度排序。前三个（5、8、4）构成了线上观察到的死锁：**新源进不来（5+8）+ 死源不退役（4）**。

### 缺陷 5（致命）——剧名从未进入匹配名单，搜索结果 100% 被误杀

`seasonKeyword():556` 返回 `keyword ?: name`，**不拼接季号**——所以日志里的搜索词 `诛仙 第四季` 说明订阅的 `keyword`/`name` 本身就含季号。

`matchNames():1702` 构造匹配名单时取「按字符长度最长的空格分段」：
`"诛仙 第四季".split("\\s+")` → `["诛仙"(2), "第四季"(3)]` → **longest = "第四季"**。
名单变成 `["诛仙 第四季", "第四季"]`，**裸剧名「诛仙」不在其中**。中文剧名普遍短于「第N季」，这是系统性选错，不是偶发。

`matchesTitle` 归一化后（`collapseCjkSpaces` 删除 CJK 间空格）要求标题包含连续的 `诛仙第四季` 五字；`fuzzyChineseMatch` 容错仅 `max(1, 5/4) = 1` 字。结果：写「诛仙 S04」「诛仙4」「诛仙动画第四季」的候选**全部判为不相关**。

线上证据：`共 31 条结果, 过滤 31 条不相关结果` → `未找到可用资源`。

**结构性问题**：季信息在过滤链里出现两次——一次混在名称匹配（实现更差），一次在 `parseTitleSeason`（实现正确）。前者把后者**架空**了，资源活不到季判定。

### 缺陷 8（致命，与缺陷 5 互补）——片单追更硬编码 `season: 1`

`web-ui/src/views/MediaSubscriptionsView.vue:649`：

```js
const body: any = {name: item.vod_name, keyword: item.vod_name, season: 1}
```

榜单条目名原样填进 `name`/`keyword`，季号**硬编码为 1**。对「诛仙 第四季」这个条目，一次点击同时埋下缺陷 5 和缺陷 8 两颗雷。

**为什么恰好 31/31 全灭**——两个缺陷的值域互补覆盖了每一条候选：

| 候选标题形态 | 名称匹配（缺陷 5） | 季过滤（缺陷 8） |
|---|---|---|
| 不带季标记（「诛仙 4K 全10集」） | ❌ 不含连续「诛仙第四季」五字 | ✅ `titleSeason=null` 放行 |
| 带「第四季」（「诛仙 第四季 全10集」） | ✅ 含「诛仙第四季」 | ❌ `titleSeason=4 ≠ season=1` |

每一条必落其一。**因此只修缺陷 5 不足以恢复。**

`season` 错误还有另外两条下游杀伤路径：

- **集数识别**：`parseEpisode("诛仙 S04E01.mkv", 1)` → `SEASON_EPISODE` 命中 `s=4`，`season != s` → **`return -1`**（`:1213`）。即使正确资源挂上了，集数清单仍为空
- **播放列表合并**：`parseEpisodeFromTitle` 同一段逻辑，解析不出集号

**修复安全性**：`buildMountPath` 只用 `name` + `metaIdTag`，**不含 season**，且 `update()` 不重算挂载路径（`:1364` "仅在创建时定名"）。所以修正 `season` 不会动挂载路径、不会断链。缺陷 5 的修法（匹配时剥季号，不改 `name` 字段）同理。

### 缺陷 4（致命）——死掉的补缺挂载永久"冒领"集数

`fillGaps` 开头刷新 gap 覆盖快照：

```java
try {
    Set<Integer> coverage = walkEpisodes(...);
    if (!coverage.isEmpty()) { resource.setEpisodeList(...); }   // 只在非空时更新
} catch (Exception e) { log.debug(...); }                        // 失败静默
missingStill.removeAll(parseEpisodeList(resource.getEpisodeList()));  // 用旧快照扣缺口
```

无论 `walkEpisodes` **抛异常**还是**返回空**，旧快照都原封不动，随后被用来扣减缺口。一个标题为「10集全」的死资源会永久声称覆盖 1–10 集 → `missingStill` 变空 → 不触发补搜 → 池子永远补不上。

**系统认为订阅健康，实际一集都播不了。** 线上证据：`第 1 集暂无可用播放源(已尝试 1 个源; …-补1/…: failed link: failed get link: 参数错误)`。

根因是需求书 §6 点名要求区分的那件事：**"能列出目录" ≠ "能取到链"**。现在用列目录的结果回答取链的问题。

### 缺陷 1 ——损坏登记每集只能记住一个失效源

`broken_episodes` 是 `{集号: "源目录|时间戳"}`，`addBrokenEpisodes` 用 `put` 覆盖写。`playEpisode:773-783` 在候选循环里每失败一次登记一次：试 A 失败记 A，试 B 失败记 B **覆盖掉 A**。下次播放 A 不被跳过。**两个以上失效源时永不收敛。**

### 缺陷 2 ——播放失败不回灌巡检

`playEpisode` 失败后只写那个 7 天过期的 JSON：不标 resource BAD、不降分、不触发 `checkAsync`、不触发补搜。播放期是信噪比最高的失效信号源，现在被丢弃。

### 缺陷 3 ——补缺挂载失效无人管

`retireGapMounts` 只在 `doCheck:337` 的 `missing.isEmpty()` **else 分支**执行。有缺集时走 `fillGaps`，死掉的 gap 永远不退役，还占着 `maxGapMounts=3` 的名额。失效确认（`listEpisodes` → `onInvalid`）只覆盖主源。

### 缺陷 6 ——新集通知发在验证之前

`doCheck:330-333`：`applyInventory`（内部 `addEvent(NEW_EPISODE)` 并推 Telegram）**先于** `preheatEpisodes`（取链验证）。验证失败时再补发一条 ERROR。违反需求书 §11 与验收场景 7。

### 缺陷 7 ——没有观看进度

`MediaSubscriptionCheckService:1281` 把 `last_episode` 赋值为**挂载目录里的最大集号**，而需求书 §4 要求它是「最后观看集数」。字段名与语义背离，追剧系统内**无任何观看进度记录**。

进度实际存在于 `History` 表（`uid` / `vodId` / `episode` / `episodeUrl` / `position`），且 csp_Media 的 `vod_id = msub-{订阅id}`、播放条目为 `msubep-{sub}-{ep}` —— **逻辑链接把集号带进了播放历史**。

### 贯穿性诊断

缺陷 4、5 是同一个病根：**用猜测代替事实**。
标题过滤（猜）极严 → 好资源进不来；目录快照（猜）当权威 → 死资源不退役。而唯一能给出事实的环节——**挂载后实际列集数、实际取链**——要么没机会跑，要么结果被忽略。

v2 的核心原则由此确定：

> **标题匹配要宽（召回优先），可用性判定必须来自取链事实（精度靠探测）。**

---

## 3. 重新设计方案（决策汇总）

| # | 决策 | 结论 | 依据 |
|---|---|---|---|
| Q1 | 重写 vs 演进 | **靶向重构**：保留算法层，重做数据模型 | 错的是名词不是动词；算法已被真实数据校准 |
| Q3/Q7 | Media / Subscription 拆表 | **不拆**，Episode 挂 `subscription_id` | 单用户下纯成本；将来是纯加法迁移 |
| Q4/Q8 | Episode 落库 | **落库**，`episode_source` 存**分享内相对路径** | 播放期从 ≤7 次递归列目录降到 1 次取链；失败有明确归属 |
| Q5 | 搜索器抽象 | **按聚合站**（`SearchProvider`），网盘类型下沉为过滤/打分维度 | 搜索天然跨盘；按盘拆会 N 倍重复请求 |
| Q6/Q15 | 播放失败补救 | 异步：标失效 → **先查池** → 池空才搜索；**不限频** | 池命中毫秒级；盘搜自带缓存、tg-search 直查库 |
| Q9 | primary / gap 二分 | **取消**，统一"资源挂载生命周期" | 二分法在制造重复逻辑，缺陷 3 即其产物 |
| Q10 | 状态机 | resource 只表达生命周期位置，**可用性由 episode_source 聚合派生** | `AVAILABLE/PARTIAL` 是派生量，落列即制度化缺陷 4 |
| Q11 | 失败传染 | **二次探测判定** + MOUNTED 资源每轮抽 1 集主动取链 | 区分"整源死"与"单集坏"；抽检覆盖从未被播的集 |
| Q12 | 跨订阅共享 | 只共享 **失效黑名单** `dead_link` | 覆盖关系天然是 (订阅,资源) 二元的；`Share.path` 唯一约束禁止共享挂载 |
| Q13 | TVBox 线路 | 逻辑线 + 分盘线保留，分盘线**改由 `episode_source` 聚合** | 分盘线是逃生舱；但不能再依赖 active/gap 标志 |
| Q14 | 筛选表达 | 硬过滤保持少量固定字段 + 软偏好改**权重表** | 13 维里只有 2–3 维是硬过滤，其余是排序偏好 |
| Q16 | 多季 / SP / 电影 | `(subscription_id, season, number)`；**特别篇 season=0，电影 S1E1** | TMDB/Emby/Jellyfin 通行约定，与元数据源天然对齐 |
| Q17 | 任务落库 | **不落库**，任务视图从字段 + 事件流派生 | 队列是查询不是表；落表带来写放大与清理负担 |
| Q20 | 候选池配额 | **分层配额**：每主网盘保底 3 席 + 其它盘合计 3 席 | 主网盘加成是结构性的（+15/+15/+20），放大 N 只会让前 N 全是主网盘 |
| Q21 | 标题匹配 | 剥季号得裸名进名单；**删除"最长片段"启发式**；季判定只留 `parseTitleSeason` | 缺陷 5 的正解 |
| Q22 | 通知门槛 | 仅对**验证通过**的集通知，且 **已看集号 ≥ 本次新增前的最大集号** | 需求书 §11 / 验收场景 7 |
| Q23/Q29 | 通知渠道 | 网页事件流（已有）+ Telegram（已有）+ **TVBox `vod_remarks` 角标**（可选） | `vod_remarks` 是协议里唯一保证被渲染的文本位 |
| Q24 | 转存模式 | **维持 FOLLOW / TRANSFER 两态**，不加"整包转存" | 增量转存结果等价且风控友好 |
| Q25 | 前端 | 只加**逐集资源矩阵**，不新增路由 | 唯一新增的、有信息量的视图；任务页是给三个字段建宫殿 |
| Q28 | 观看进度 | **只读 `History`**（`uid` + `vodId=msub-{id}`，解析 `episodeUrl` 取真实集号，退回 `episode` 下标） | 零写放大；多端进度天然合并 |
| Q30 | season 兜底层次 | **后端 `create()` 兜底**：`season` 空或 1 而 `name` 含明确季标记时用 `parseTitleSeason(name)` 修正；`parseTitleSeason` 挪到 `util/TextUtils` | `navSubscribe` 不是唯一入口（链接直订、搜索页按钮、TVBox 操作组都调 `create()`）；"每入口各自决定 season"正是成因 |
| Q31 | 存量 season 修正 | **不做** | 功能尚未发布，无存量数据 |
| Q32 | 旧列是否保留 | **V29 直接 drop**，取消 V30 | 未发布 ⇒ 回退保险失去保户；留着会让新旧两套 `episode_list` 长期共存 |

### 明确不做（需求书条目的评估结论）

| 需求书条目 | 结论 | 原因 |
|---|---|---|
| §4 Media/Subscription 拆表 | 不做 | 单用户零收益，将来纯加法迁移 |
| §6 六态资源状态机 | 不做 | 派生量落列会与 episode_source 打架 |
| §7 按盘拆 Searcher | 不做 | 与搜索源的实际形态相反 |
| §8 13 维筛选 DSL | 降级为权重表 | 需写解析器 + UI 生成器，且易把池筛空 |
| §9「转存整个资源」 | 不做 | 与增量转存结果等价，更吃配额与空间 |
| §13 独立任务页 | 不做 | 任务不落库，队列即查询 |
| §15.2 SP/OVA 完整支持 | 降级 | 网盘文件名不写 `S00E01`，资源侧无法可靠识别 |
| §15.8 客户端播放失败回传 | 部分做 | 原生 TVBox 无播放失败回调；服务端取链失败已是可靠信号 |

---

## 4. 数据模型

### 新增 `msub_episode`

| 列 | 说明 |
|---|---|
| `subscription_id` | 归属订阅 |
| `season` | 季号；**特别篇 = 0**，电影 = 1 |
| `number` | 集号 |
| `title` | 集标题（元数据侧） |
| `air_time` | 播出时间（元数据侧） |
| `aired` | 是否已播出 |

唯一约束 `(subscription_id, season, number)`。

**可用性不落列**，由 `msub_episode_source` 聚合派生。

### 新增 `msub_episode_source`

| 列 | 说明 |
|---|---|
| `episode_id` | → `msub_episode` |
| `resource_id` | → `media_subscription_resource` |
| `rel_path` | **分享内相对路径**（不存绝对路径，换挂载点不失效） |
| `file_size` | 体积（花絮过滤/打分） |
| `state` | `LISTED` / `VERIFIED` / `FAILED` / `MISSING` |
| `success_count` / `fail_count` | 播放成功率统计 |
| `last_verified_time` | 最后取链验证时间 |

唯一约束 `(episode_id, resource_id)`。这张表就是需求书 §5 要求的 **Episode↔Resource 多对多**。

**状态语义**

- `LISTED` — 列目录发现，尚未取过链
- `VERIFIED` — 取链成功过（最强信号）
- `FAILED` — 取链失败（被和谐 / 分享失效）
- `MISSING` — 曾存在，重列后文件消失

**派生规则**

- 某集可播 = 存在 `state ∈ {VERIFIED, LISTED}` 的行
- 整源死 = 该 resource 下所有行 ∈ `{FAILED, MISSING}`

### 新增 `dead_link`

| 列 | 说明 |
|---|---|
| `link` | 分享链接（唯一） |
| `reason` | 判死原因 |
| `fail_count` | 累计判死次数 |
| `time` | 最后判死时间 |

任何订阅判死即写入，任何订阅入池/挂载前先查。跨订阅共享的**唯一**内容。

### 改造 `media_subscription_resource`

- 新增 `state`：`CANDIDATE`（池内未挂载）/ `MOUNTED`（已挂载）/ `RETIRED`（已卸载，保留记录防重复入池）/ `REJECTED`（硬过滤不合格或链接非法）
- 废弃 `validity` / `active` / `gap` 三个标志（Q9 取消 primary/gap 二分后，"主源"退化为排序第一的 `MOUNTED` 资源）

### 改造 `media_subscription`

- `last_episode` **改名 `max_episode`**（它一直是资源侧最大集号，见缺陷 7）
- 观看进度**不新增字段**，运行时读 `History`
- `broken_episodes` 废弃（由 `episode_source.state = FAILED` 取代）
- `episode_list` / `schedule` 保留（v29 不 drop，见迁移方案）

---

## 5. 资源选择算法

### 入池（`fillPool`）

```
搜索结果
  → 类型白名单 PAN_TYPES
  → dead_link 黑名单
  → excludeKeywords 硬过滤
  → 标题归属匹配（裸剧名，宽）          ← 缺陷 5 修复点
  → 季标记判定（parseTitleSeason，唯一的季关卡）
  → 打分（权重表）
  → 分层配额入池                        ← Q20
       每主网盘保底 3 席
       其它盘合计     3 席
       同集单集链接一席（沿用 takenEpisodes）
```

### 选源（播放期）

```
episode_source WHERE episode_id = ? AND state IN (VERIFIED, LISTED)
  ORDER BY
    state 权重          (VERIFIED > LISTED)
    转存副本优先
    resource 打分
    success_count 降序 / fail_count 升序
  → 取第一条 → resource.mountPath + rel_path → 取链
  → 失败：该行置 FAILED → 触发二次探测（Q11）→ 落下一条
  → 全部失败：异步补救（标失效 → 查池 → 池空才搜索）
```

### 失败传染（Q11）

```
某集取链失败
  → 对同一 resource 的另一集再取一次链
       也失败 → 整源死：该 resource 全部 LISTED 行降级，resource → RETIRED，触发换源，写 dead_link
       成功   → 仅该集 FAILED
```

MOUNTED 资源每轮巡检**抽 1 集主动取链**（复用 `preheatEnabled` / `preheatMaxPerRound` 能力），覆盖"从未被播的集永远停在 LISTED"的盲区——缺陷 4 里那 9 集正是死于此。

---

## 6. 任务系统设计

不落任务表（Q17）。调度沿用现状并补齐视图：

- **队列** = `SELECT … WHERE status='ACTIVE' ORDER BY next_check_time LIMIT maxChecksPerRound`
- **执行中** = `inFlight` 内存 Set
- **最近执行 / 下次执行** = `last_check_time` / `next_check_time`
- **成功 / 失败 / 失败原因** = 事件流（`TYPE_ERROR` 等）

新增的异步补救路径（Q15）与巡检共用单线程 executor 与 per-id 互斥锁。

---

## 7. TvBox 集成方案

不改协议，沿用 `csp_Media` + `msubep-{sub}-{ep}` 逻辑链接。变更三处：

1. `buildTvBoxPlayLines` 的分盘线路改由 `episode_source` 按 `resource.type` 聚合生成，不再读 `active`/`gap`；主网盘线路固定展示，非主网盘需覆盖齐全部集。
2. `playEpisode` 改走 `episode_source` 索引选源（§5），失败触发异步补救。
3. `vod_remarks` 增加 `🆕` 角标（Setting `msub_tvbox_badge`，默认开）；条件同通知门槛，该集被播放后自动消除。

---

## 8. 前端页面设计

在现有 `MediaSubscriptionsView.vue` 的集数页签内新增**逐集资源矩阵**：

```
Episode 17
  百度网盘 A   ✓ VERIFIED   成功 12 / 失败 0
  夸克网盘 B   ✓ LISTED     未验证
  夸克网盘 C   ✗ FAILED     参数错误 · 2026-08-22
```

这是 `episode_source` 落库后唯一新增的、有信息量的视图，也是让缺陷 4/5 那类"系统自以为健康"一眼可见的可观测性产出。

任务信息并入列表页一列（下次检查 / 最近检查 / 状态）。不新增路由。

---

## 9. 迁移方案

**V29 已落地**：`media_subscription.last_episode` → `max_episode`（该列一直存的是挂载目录里的最大集号，属资源侧指标；旧名字读起来是"最后观看集数"，需求文档就是照字面理解写的）。可重复执行，已改名/全新库都安全。

下一个可用版本 **V30**（Java migration，`src/main/java/db/migration/current/` + `META-INF/services/org.flywaydb.core.api.migration.JavaMigration` 注册）。

**铁律：`mount_path` 与 `share_id` 一个字节都不能变** —— 那是不断链设计的地基。

**V29 已落地**：`last_episode` → `max_episode`（见上）。

**V30 内容（第二段）**

1. 建表 `msub_episode` / `msub_episode_source` / `dead_link`
2. `media_subscription_resource` 加 `state` 列，按 `active`/`gap`/`validity` 推导初值
3. 回填**仅 `msub_episode` 骨架**（集号 + 播出时间，取自 `schedule` 快照）
4. **`msub_episode_source` 一行都不建**，由首次巡检按真实目录重建
5. **直接 drop 旧列**：`episode_list` / `broken_episodes` / `validity` / `active` / `gap`

**为什么不回填 `episode_source`**：`episode_list` 本身就可能是缺陷 4 那样的陈旧脏数据（死资源仍声称覆盖 1–10 集）。回填等于把脏状态固化进新模型，而新模型正是为消灭这类脏状态而建。

**为什么直接 drop 旧列**（Q32）：功能尚未发布，旧列的唯一价值——回退保险——已经没有保户。继续保留只会让新旧两套 `episode_list` 长期共存，每个读代码的人都要问一次"哪个是真的"。

**为什么不合并重整 V20–V29**：未发布消除的是**数据包袱**，不是 **Flyway 校验和包袱**。改 V20 会让本地正在运行的实例校验和失配，且清空 `media_subscription` 会在 `/追剧/` 下留一批孤儿 storage。收益只是"文件数变少"的观感。

**存量 season 修正**：不做（Q31）——功能未发布，无存量数据。`create()` 的兜底覆盖此后所有新建订阅。

---

## 10. 实施计划

### 第一段：止血 + 召回修复（已完成，全量 691 测试通过）

**9 个缺陷全部修复**，全部不依赖新表，改动在第二段重构后依然保留：

| 缺陷 | 改动 | 落点 |
|---|---|---|
| 5 剧名从未进匹配名单 | `matchNames` 加入裸剧名 + 删除"最长片段"启发式 + 排除纯季号词 | `MediaSubscriptionCheckService.matchNames/addName` |
| 8 片单硬编码 `season=1` | `create()` 用剧名兜底季号；季号解析迁入工具类 | `TextUtils.resolveSeason/stripSeasonSuffix/isBareSeasonMarker` |
| 4 死源冒领集数 | gap 覆盖刷新失败或列空 → 快照置空 + 标 BAD，不再用旧快照扣缺口 | `fillGaps` |
| 3 死源占 `maxGapMounts` 名额 | `retireDeadGapMounts` 无条件执行、不给主网盘豁免，名额退役后重算 | 同上 + 新增方法 |
| 2 播放失败不回灌 | 全候选失败 → 资源降分 + 触发 `checkAsync` 异步补救 | `MediaSubscriptionService.playEpisode/demoteFailedSource` |
| 9 网盘限流被当失效 | `isThrottleError` 识别限流不标 BAD + 同盘熔断 + 15min 退避 | `activateNextCandidate` |
| 1 损坏登记每集只记一个源 | `broken_episodes` 改多值 `{集: [源, ...]}`，兼容旧标量格式 | `parseBroken/addBrokenEpisodes/isBroken` |
| 6 通知发在验证之前 | `applyInventory` 不再发通知，改由 `notifyNewEpisodes` 在 preheat 之后发 | `doCheck` |
| 7 没有观看进度 | V29 `last_episode`→`max_episode`；进度改为只读 `History` | `watchedEpisode` |

配套改进：

- **候选池分层配额**（Q20）：每主网盘保底 3 席 + 其它盘合计 3 席，备用盘不再被主网盘包圆
- **多源聚合搜索**：盘搜 / TG-Search / 电报网页**同时**跑并按 link 合并（`TelegramService.searchAggregated`）。回退链"够用即停"会让配了盘搜的部署永远调不到另外两个源——而资源不够时重复搜同一个源没有意义，结果不会变
- **池枯竭补救**：无可用候选时释放旧 BAD 冷却（本轮刚判死的除外）
- **入池归一化 validity**：只有明确判失效的落 BAD，其余 UNKNOWN；"已验证可用"只留给挂载成功那一刻

**缺陷 5 与缺陷 8 必须同时修**：两者值域互补，只修其一仍然 100% 全灭。

**顺带修复**：`MediaSubscriptionCheckServiceTest` 的 `Fixture` 少传构造参数导致测试模块编译失败（`529e42b9` 新增 `GuanYingSearchService` 时漏改，后在 amend 中修正）。

### 第二段：模型重构

V30 迁移 + `msub_episode` / `msub_episode_source` / `dead_link` + 资源两级状态机 + 选源算法走索引 + 取消 primary/gap 二分 + 失败传染二次探测与主动抽检。

### 第三段：体验层

TVBox `🆕` 角标（Q29）、逐集资源矩阵 UI（Q25）、权重表筛选（Q14）。

---

## 11. 验收场景对照

| 场景 | v2 保障机制 |
|---|---|
| 1 正常播放 | `episode_source` 索引直取，1 次取链 |
| 2 主资源单集失效 | 该行 → `FAILED`，选源落下一条；二次探测确认非整源死 |
| 3 主资源全部失效 | 二次探测判定整源死 → `RETIRED` + `dead_link` + 换源 |
| 4 资源不完整（A 1–10 / B 11–20） | 多对多天然覆盖；取消 primary/gap 二分后无需特殊路径 |
| 5 新剧集播出 | 元数据 → 巡检发现 → 补搜 → 探测 → `episode_source` 入库 → 取链验证 → 通知 |
| 6 新集暂无资源 | 缺口保留（缺陷 4 修复后不再被陈旧快照吞掉）→ 后续轮次继续补搜，不判失败 |
| 7 用户已追平最新集 | 通知门槛 = 取链验证通过 **且** `History` 中已看集号 ≥ 本次新增前最大集号 |
