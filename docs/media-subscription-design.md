# 追剧系统(自动追更)设计

> **实现状态(2026-08-19):P0-P3 全部落地**(个别标注"可选/留待"的细项见末尾说明)。
>
> - **P0**:V20 四表、订阅 CRUD、定时巡检(重列主源/失效换源/候选池/退避)、固定挂载路径换源、事件流、web 管理页(菜单"追剧")、ShareService 清理豁免、TVBox `t=msub` 列表与单源播放。
> - **P1**:V21 元数据列;`MetadataProvider` SPI(豆瓣/TMDB/Bangumi + official 兜底,`service/metadata/`);官方集数触发补搜、播出日程调度(播出+15min 起 3 短轮)、官方状态自动完结;缺集补搜(整季→单集降级,临时挂载探测)与**多源合并播放**(主源优先,补缺源挂 `{mount}-补N`,主源补齐自动退役);集数清单接口/页签;索引模板联动(首次挂载建"追剧"增量模板);一键订阅(TVBox 详情页"追更"操作组 `$msub$/$munsub$` + spider 拦截[TgSearch,已构建拷回 spring.jar]、web 搜索页追更按钮、播放记录追更按钮、"我的追更"首页分类);dry-run 预览(打分明细);更新收件箱(近 3 天);导出/导入。
> - **P2**:`AListService.mkdir/copy/awaitCopyTasks`;`MediaSubscriptionTransferService` 增量转存(按源目录分组提交、事后校验、日限额 `maxTransfersPerDay`、失败自动降级 FOLLOW、:40 自愈扫描);`TaskType.SUBSCRIPTION` + Task 行;转存模式 UI(账号下拉/手动转存/进度);健康面板统计卡;批量操作(全选/全不选/反选 + 检查/暂停/恢复/删除)。
> - **P3**:版本升级提醒(4K 完整候选探测,事件不自动替换);归档清理(`msub_archive_days`,完结 N 天释放转存文件);多季联动(`/next-season` 一键续订下一季);Telegram Bot 通知(`msub_telegram_bot_token/chat_id`,新集/错误/完结/转存完成);指标(今日更新/搜索成功率/来源存活率,`/stats`);ERROR 每日自动重试 + 连续 7 天失败提示;官方视频平台 provider(**参考 atv-player `src/atv_player/metadata/providers/` 的已验证实现**:腾讯 pbaccess trpc 搜索+GetPageData 官方分集列表 publish_date 推算已播/下集播出、优酷 `search.youku.com/api/search` 的"更新至N/M集"文案、爱奇艺 `mesh.if.iqiyi.com` updateTime;`msub_official_url_template` 仅作最后兜底)。豆瓣搜索升级 `movie.douban.com/j/subject_suggest` 在线接口(subject id 直接对接 rexxar 集数),本地 movie 表兜底。实测(2026-08-19):三平台接口与豆瓣 suggest 均可达且字段符合解析。
>
> 关键类:`MediaSubscriptionService`(CRUD/内容/合并播放/收件箱/导出导入/动作)、`MediaSubscriptionCheckService`(巡检/换源/补缺/探测/打分/通知)、`MediaSubscriptionTransferService`(转存/归档)、`web/MediaSubscriptionController`、`web/TelegramController`(msub 分支/操作组端点/首页分类)、`service/metadata/*`、迁移 `V20__MediaSubscription` + `V21__MediaSubscriptionMeta` + `V22__MediaSubscriptionMetaFix`(V21 曾因带引号小写列名在 H2 上导致 Column not found,V22 自愈,详见 `MediaSubscriptionMigrationTest`)。
> 留待:集→源映射仅动态计算+接口固化展示(未落表,设计标注条件性);搜索成功率等指标在追剧页 `/stats`(未嵌入 SystemInfo 页);转存空间水位依赖事后校验发现(未做转存前预估)。
>
> **审查轮(2026-08-19)修复**:删除订阅连补缺挂载一起清理(原只删主源,-补N 泄漏);合并播放列表解析容忍 URL 内嵌 `#storageId=` 片段(原按 `#` 切分会截断,含回归测试);全部元数据 RestTemplate 统一带超时(`MetadataHttp`,防外部平台挂起卡死巡检线程),官方平台 provider 补 6h 缓存;V22 修复限定 H2(PG/MySQL 上旧 V21 小写列是正确约定,不可误删);spider 拦截抽取 `MediaSubscribeInterceptor` 并接入 TgWeb(同服务 /tg-search);导入批次内同名去重、归档事件独立类型 ARCHIVED、copy 轮询复用登录 token。
>
> **同日二轮**:元数据搜索"只有豆瓣有结果"根因 = 裸 RestTemplate 撞 Jackson3 转换器(见 memory),provider 全部改 String 收发 + 错误上抛(errors 映射,前端提示失败源);Bangumi 限定动画/真人类型(否则小说占首条);web 端封面统一 `/images` 代理;多网盘转存(V23 account_ids,多目标独立成败,全败才降级);筛选支持单集上限 maxEpisodeSizeMb(贯穿集数/补缺/转存链路)。用户文档:`docs/media-subscription-guide.md`。

> 解决网盘分享资源频繁失效的问题:
> 1. 自动从不同分享资源搜索并补齐缺失的集数(多源合并播放);
> 2. 用户订阅媒体 + 筛选条件,系统定时收集资源,可自动转存到用户网盘,或自动 fallback 到有效资源。

---

## 1. 背景与现状

- 分享资源(TG 搜索/索引里的夸克、115、阿里等分享链接)时效短,失效后当前行为是**直接删除**(`ShareService.cleanInvalidShares` → `cleanStorages()`,按 AList `/api/admin/storage/failed` 的失效状态删 `share` 行和挂载),播放历史随之中断。
- `TvBoxService.getPlaylist` 只有一个临时补挂逻辑(temp share 404 时用路径里的 shareId 重挂),没有"换一个源"的能力。
- `entity/Subscription` 已被 TVBox **配置订阅**占用(配置 URL/token),与本功能无关;新功能命名必须避开。
- 转存能力:`AListService` 目前只有 `listFiles/getFile/rename/move/remove`,**没有 mkdir / copy / 任务轮询**;AList(PowerList fork)本身支持 `/api/fs/copy`、`/api/fs/mkdir` 与 `/api/admin/task/copy/*`,只是 Java 侧未接。fork 还内置了 `quark_share_direct`、`ali_to_115` 等跨盘转存开关。
- 现成可复用的管道:搜索(`TelegramService.search` 三级回退:PanSou → tg-search API → t.me 网页抓取)、链接有效性预检(`RemoteSearchService.filterInvalidPanSouLinks`,PanCheck/tg-search check)、集数识别(`model/FileNameInfo`:S01E01/中文数字/上中下)、标题清洗(`util/TextUtils`)、任务跟踪(`Task`/`TaskType`)、定时框架(`@Scheduled` + `AutoUpdateExecutor.scheduleWithJitter`)。
- 多用户先例:`live_follow`(V19)、播放记录同步 —— 订阅 token → uid 的归属规则可直接照搬。

## 2. 目标与非目标

**目标**
- 订阅一部剧(关键词/豆瓣条目 + 筛选条件),系统定时检查并自动追更,新集数无需人工干预。
- 资源失效自动换源,**播放地址与观看进度不中断**(核心设计约束,见 §4.1)。
- 缺集时自动从多个分享补齐,多源合并成一个播放列表。
- 可选自动转存到用户自己的网盘(DriverAccount),彻底免疫分享失效。

**非目标(本期不做)**
- 全自动削刮/整理媒体库命名规范(Emby/Jellyfin 风格 nfo)。
- 电影/综艺以外更复杂的订阅类型(先做剧集,电影本质是其单集特例)。
- Telegram Bot 等外发通知通道(先做站内事件流,通道留接口)。
- 直接调用各网盘官方"分享转存" API(走 AList 统一 copy,官方 API 作为后续按盘优化)。

## 3. 总体架构

```
                 ┌────────────────────────────────────────────────┐
   web 管理端    │  MediaSubscriptionService (CRUD/手动检查/换源)   │
   TVBox 端 ────▶│  SubscriptionCheckService (定时巡检, @Scheduled) │
                 │    1. 重列主源(便宜) → 发现新集                  │
                 │    2. 失效 → 候选池换源(即时)                    │
                 │    3. 停滞/缺集 → 搜索补源(昂贵)                  │
                 ├────────────────────────────────────────────────┤
                 │  SearchAggregator (统一搜索源层,见 §4.6):         │
                 │    盘搜PanSou / TG-Search频道 / t.me网页抓取      │
                 │    + filterInvalidPanSouLinks 有效性预检         │
                 │  MetadataService (统一元数据层,见 §4.8):          │
                 │    豆瓣/TMDB/Bangumi/官方平台 → 匹配·集数·日程    │
                 │  ResourceRanker (硬过滤 + 用户偏好软打分 → 候选池)  │
                 │  EpisodeInventory (FileNameInfo → 集数清单)      │
                 │  PlaylistMerger (多源按集号去重合并)              │
                 │  TransferService (AList mkdir/copy + 任务轮询)   │
                 ├────────────────────────────────────────────────┤
                 │  底座: ShareService(挂载) / AListService(fs)     │
                 │        DriverAccount(用户网盘) / Task(任务)      │
                 └────────────────────────────────────────────────┘
```

两个核心资源策略(订阅级开关):

| 模式 | 说明 | 抗失效能力 | 依赖 |
|---|---|---|---|
| `FOLLOW`(挂载追更,默认) | 分享固定挂载在专属路径,失效自动换源,缺集多源合并 | 依赖换源速度(秒级,候选池预热) | 无需账号 Cookie |
| `TRANSFER`(自动转存) | 发现新集后 copy 到用户自己的网盘目录,之后只播放自有副本 | 一经转存永久有效 | DriverAccount 有效 Cookie + AList copy 任务 |

## 4. 核心设计决策

### 4.1 固定挂载路径 = 播放/历史不断链(本设计的关键)

每个订阅拥有一个**固定 AList 挂载路径** `/追剧/{id}-{名称}`。换源时只替换该路径背后 storage 的 `shareId/folderId` 配置(`ShareService.saveStorage` 更新同一 storage id),路径不变:

- TVBox 端 `vod_id`、播放 URL、`History.drivePath` 全部基于路径 → 换源对用户透明,续看不受影响;
- `Share.temp=false`(常驻),由订阅生命周期管理删除,不走 temp 过期清理;
- 集数排序复用 `FileNameInfo`(按集号排序),换源后集数顺序稳定,`History.episode` 索引不漂移。

TRANSFER 模式同理:目标路径 = `Storage.getMountPath(account) + "/追剧/{名称}"`,天然稳定。

### 4.2 候选池(candidate pool):把"失效后现搜"变成"失效前备好"

每次搜索不只取最优结果,而是保留 **Top N(默认 5)个有效候选**(`media_subscription_resource` 表),巡检时顺带轻量校验。失效时直接激活次优候选(秒级),不必现场搜索(分钟级 + 可能风控)。搜索是整个系统里最贵、最易被风控的操作,所有流程都优先避免它。

### 4.3 巡检三级递进(控制搜索频率)

1. **重列主源**(最便宜,每次必做):"更新至N集"类分享链接不变、内容会长,直接重新 `listFiles` 对比集数清单即可发现新集 —— 大多数追更靠这一步完成,零搜索开销;
2. **失效换源**(事件触发):主源挂了 → 激活候选池;池空 → 触发一次搜索;
3. **搜索补源**(有条件):主源连续 N 轮(默认 3)无新增且未判定完结,或缺集存在,才发起搜索找更完整的源。

每轮巡检限量(默认每轮最多 10 个订阅,订阅间串行 + jitter),单订阅每检查周期(默认 6h,可配)最多 1 次完整搜索。

### 4.4 集数清单与缺集判定

- 对挂载目录递归列出(复用 `TvBoxService.dfs` 的遍历/过滤逻辑),每个文件名经 `FileNameInfo` 解析出 `(季, 集)`,按订阅的目标季过滤,得到**集数清单**(集号 → 文件大小;体积供偏好打分 §4.7 与花絮过滤用);
- 花絮/预告过滤:文件名排除 `PV|预告|花絮|NCOP|NCED|Sample` + 单集体积下限(默认 20MB);
- 期望集数来源(优先级):用户手填 > 官方元数据总集数/已播集数(§4.8:豆瓣集数 / TMDB 季集数 / Bangumi 章节)> 分享标题里 `全N集/更新至N集`(`TextUtils` 已有正则)> 观测最大值;
- `缺失 = 官方已播集 ∪ 观测最大集 − 当前清单` —— 官方数据让缺集检测从"被动等资源出现"变成"主动按官方确认去找";`完结 = 官方状态完结 或 清集 ≥ 期望集` → `ENDED` 停止巡检。

### 4.5 多源合并播放(需求 1 的实现)

PlaylistMerger 按集号合并多个源的清单:**转存副本(如有) > 主源 > 候选源按分数**;同集号取最高优先级源的播放地址。合并结果缓存(Caffeine,随巡检失效),TVBox 详情接口输出单一 `vod_play_url`。集 → 源的映射动态计算,不落库(P1 若需要历史级稳定性再固化到表)。

### 4.6 搜索源:盘搜 / TG-Search 频道 / 电报网页搜索

分享资源来自三类来源,均已接入现有代码,订阅系统零新增来源开发,只在其上加策略层:

| 来源 | 现有实现 | 特性 | 在本系统中的角色 |
|---|---|---|---|
| 盘搜 PanSou | `RemoteSearchService`(`appProperties.panSouUrl`) | 聚合多频道/站点,一条结果含多个链接(带密码/时间/来源),自带 `/api/check/links` 校验 | 已配置时的常规搜索首选;候选池广度的主要来源 |
| TG-Search 频道 | `TelegramService.searchTgSearchApi`(`{tgSearch}/api/search`) | 结构化最好:`cloud_types` 按盘类型过滤,`media` 元数据(title/year/episode/quality)可免挂载预打分 | 未配 PanSou 时的常规搜索首选;打分元数据的主要来源 |
| t.me 网页抓取 | `TelegramService.searchFromWeb`(Jsoup 并行抓 `telegram_channel` 表中 webAccess 频道) | 零外部依赖、开箱即用;慢、易风控,覆盖面取决于频道表 | 兜底来源;缺集补搜聚合模式的补充源 |

- **统一抽象 `SearchProvider`**:返回归一化 `Message` 列表,`link` 为天然去重键(同一分享在多源/多频道出现时自动合并);现有三个实现之外,未来来源("等" —— 其他盘搜站、聚合 API)按同一 SPI 插入。不改动 `TelegramService.search` 现有三级回退,聚合/策略层包在其上;
- **两种策略**:常规巡检用**回退链**(省额度:PanSou → TG-Search → 网页,任一来源结果够用即停,即现有 `search()` 行为);缺集补搜用**聚合模式**(多源全开、结果合并去重,最大化候选覆盖,代价可控因为只在补集时发生);
- **来源元数据进打分**(ResourceRanker):TG-Search 的 `media.quality/episode`、PanSou 链接的 `note/datetime/source`、网页结果的频道名(字幕组质量信号);
- t.me 网页搜索的召回质量依赖 `telegram_channel` 频道表 —— 已有完整管理面(`/api/telegram/channels` CRUD、`channels.json` 种子、`validateChannels`),用户增配影视发布频道即可提升召回,无需新开发。

### 4.7 用户偏好与打分模型(ResourceRanker)

不同用户对体积/码率的取舍不同(原盘党 vs 省流量党),偏好做**三级继承:订阅 `filter_config` > 用户默认(`user_preference`) > 系统默认**。订阅里留空的维度自动沿用上级,避免每个订阅重复填。

**偏好维度**:

| 维度 | 形式 | 说明 |
|---|---|---|
| 单集体积带 | min ~ max | 核心维度;经验值:4K remux ≈ 10GB+/集、1080P 高码 ≈ 2~4GB、省流 ≈ 0.5~1.5GB |
| 码率(估算) | 下限 Mbps | 无直接数据源,用 `单集平均体积 ÷ 单集时长` 估算(时长取豆瓣条目"单集时长",缺省 45 分钟);与体积带本质等价,二者填一即可 |
| 清晰度下限 | 4K / 1080P / 720P | 硬性门槛 |
| 盘类型偏好 | 有序列表 | 同分时按序;可设"仅限" |
| 关键词 | 包含 / 排除 | 字幕组偏好、排除"预告/枪版"等 |

**打分 = 硬过滤 + 软加权**:
- 硬过滤(不满足直接不入池): 排除词命中、低于清晰度下限、不在"仅限"盘类型、低于最小体积;
- 软打分(加权和排序): 单集平均体积贴近偏好带中心(高斯衰减 —— 过小扣"画质不足",过大扣"浪费空间")、估算码率达标度、盘类型序位、资源新近度、集数完整度。
- 输入数据:单集体积来自挂载列表时的文件大小快照(§4.4 清单已含),码率为估算值(见上);TG-Search `media.quality` 在挂载前即可先粗筛一轮,减少无效挂载。

**预设档位**(用户不必填数字):`极致画质`(体积上不封顶、码率优先)/ `均衡`(默认,1080P 2~4GB)/ `省流量`(≤1.5GB、720P 可接受)。档位只是参数预填,可继续自定义。

**降级阶梯**: 偏好内的源不存在或缺集时,按 `偏好内 > 降一档清晰度 > 任意可用` 逐级放宽;降级选择写事件(如 `第5集无符合偏好的来源,已选用1080P备选`),**不静默降级**。

### 4.8 元数据平台:豆瓣 / TMDB / Bangumi / 官方视频平台

统一抽象 `MetadataProvider` SPI,承担两个职责:**元数据匹配**(订阅 → 标准条目:规范名/别名/封面/年份/单集时长)与**最新集数查询**(已播集数/总集数/更新状态/下集播出时间)。官方已播集数是缺集检测的**权威触发源**,播出日程用于动态调度巡检。

| Provider | 数据 | 现状 | 用途 |
|---|---|---|---|
| 豆瓣 | 条目/评分/集数/单集时长/又名 | **已深度集成**(`Movie` 表、`DoubanService`、rexxar API) | 默认 provider;封面/刮削复用现有链路 |
| TMDB | 季集数/单集播出日期/状态/多语言别名 | **已集成**(`tmdb`/`TmdbMeta` 表、`tmdbService.scrape`,`tmdb_api_key` 配置已有) | 海外剧权威;播出日程最全 |
| Bangumi | 章节列表/播出日期/完结状态/中日译名 | **新增**(api.bgm.tv 公开接口,免 key) | 番剧/动画权威,集数与日程最准 |
| 官方视频平台 | 腾讯/爱奇艺/优酷页面"更新至N集/全N集" | **新增**(页面抓取) | 大陆独播剧集数兜底;反爬脆弱,默认关闭 |

- **匹配**:创建/编辑订阅时按关键词(+年份)跨 provider 搜索,默认按 `TextUtils.minDistance` 编辑距离预选最优、用户确认;`meta_provider + meta_id` 落库,豆瓣/TMDB id 另存关联缓存。匹配错的入口随时可"更换条目"。
- **别名扩展搜索**:规范名 + 别名(TMDB alternative titles / Bangumi 译名 / 豆瓣又名)作为候选搜索词 —— 分享命名混乱(台译/港译/简繁/英文名),单一关键词召回不足时按别名逐个降级尝试。
- **官方集数 = 缺集检测权威触发**:巡检时拉取已播/总集数/状态 —— 官方已播 > 本地清单 → 立即进入补源搜索(§6.4);**官方未更新 → 本轮跳过搜索**。把 §4.3 的"停滞猜测"升级为"官方确认",搜索额度进一步压缩。
- **播出日程动态调度**:有下集播出时间的条目,`next_check_time` 改为"播出时刻 +15min 起连查 3 短轮(间隔 1h),随后休眠至下个播出日" —— 追更及时且零浪费;无日程数据回退固定周期;日程统一换算北京时间。
- **完结判定**:官方状态完结(TMDB/Bangumi)且清集达标 → 自动 `ENDED`(§6.6)。
- **单集时长**:§4.7 码率估算的时长按 豆瓣 > TMDB runtime > Bangumi > 45min 缺省 取值。
- **缓存与限流**:条目元数据 Caffeine 24h,集数/日程 6h;每订阅每日至多 1 次完整刷新(巡检发现差异可加查)。
- **降级**:所有 provider 不可用时,期望集数回退"分享标题 `全N集/更新至N集` 解析 + 观测最大值"(§4.4 原路径),功能降级不中断。

## 5. 数据模型(Flyway V20)

仿 `V19__LiveFollow`:跨库幂等 **Java 迁移** `V20__MediaSubscription.java`(放 `src/main/java/db/migration/current/`,同时注册到 `META-INF/services/org.flywaydb.core.api.migration.JavaMigration` 与 `config/NativeFlywayMigrationConfig.java`)。

### media_subscription(订阅主表)

| 字段 | 说明 |
|---|---|
| id | @TableGenerator 主键 |
| uid | 归属用户(x_user),多用户隔离 |
| name / keyword / season | 展示名 / 搜索词(默认=名称) / 季 |
| meta_provider / meta_id | 元数据条目来源(`douban`/`tmdb`/`bangumi`/空,§4.8) |
| douban_id / tmdb_id | 关联缓存(封面/刮削复用现有链路) |
| official_episodes / official_total / official_status / next_air_time / meta_sync_time | 官方集数与日程快照(限流缓存,§4.8) |
| filter_config | TEXT(JSON):盘类型偏好顺序、清晰度(4K/1080P/720P)、包含/排除关键词、最小单集体积 |
| mode | `FOLLOW` / `TRANSFER` |
| account_id / target_folder | 转存目标 DriverAccount 与子目录(默认 `追剧/{name}`) |
| mount_path | 固定挂载路径(唯一) |
| share_id | 当前主源 share.id |
| expected_episodes / current_episodes / last_episode | 期望集数 / 当前集数(清单 JSON 另存或冗余) |
| episode_list | TEXT(JSON) 当前集数清单快照 |
| status | `ACTIVE` / `PAUSED` / `ENDED` / `ERROR` |
| check_interval_hours / next_check_time / last_check_time | 巡检调度(默认 6h;无变化时退避 ×1.5,上限 24h;有更新立即回落) |
| created_time / updated_time | |

### media_subscription_resource(候选池)

`subscription_id`(FK)、`link`(UNIQUE with subscription_id)、`type`(盘类型)、`source`(来源:`pansou`/`tg_search`/`tg_web`)、`share_id_str`/`folder_id`/`password`、`title`、`episodes_found`、`score`、`validity`(`OK/UNCERTAIN/BAD/UNKNOWN`)、`active`(是否主源)、`checked_time`。

### media_subscription_event(事件流 = 站内通知)

`subscription_id`、`type`(`NEW_EPISODE / SOURCE_INVALID / SOURCE_REPLACED / GAP_FILLED / TRANSFER_DONE / TRANSFER_FAILED / ENDED / ERROR`)、`detail`(TEXT)、`created_time`。前端小红点 + 时间线即由此驱动。

### user_preference(用户默认偏好)

`uid`(UNIQUE)、`config`(TEXT JSON,字段与订阅 `filter_config` 同构 + 当前档位名)。订阅 `filter_config` 各维度可空,空则继承本表;本表未设置则用系统默认(§11)。

## 6. 关键流程

### 6.1 创建订阅与首次搜索

1. web 端提交名称(+可选豆瓣条目、筛选条件、模式、转存目标);
2. 后端生成 `mount_path = /追剧/{id}-{清洗后名称}`,状态 `ACTIVE`,`next_check_time = now`;
3. 立即执行首轮检查(见 6.2,首次必搜索):Top1 激活为主源(挂载到固定路径),其余进候选池;
4. TRANSFER 模式首轮即全量转存。

### 6.2 定时巡检(SubscriptionCheckService)

`@Scheduled(cron = "0 20 * * * *")` 每小时第 20 分钟(避开 :00/:30 的分享校验高峰)→ `AutoUpdateExecutor.scheduleWithJitter` → 取 `next_check_time` 到期且 `ACTIVE` 的订阅(每轮 ≤10,串行)。单订阅检查:

0. **元数据刷新**(每订阅每日至多一次,`meta_sync_time` 限流):拉官方已播集数/状态/下集播出时间(§4.8)——官方无新增且主源无新集时,本轮直接跳过搜索;
1. **重列主源**:`AListService.listFiles` 递归 → 集数清单;对比快照:
   - 有新集 → 记 `NEW_EPISODE` 事件;TRANSFER 模式 → 对**缺失文件增量转存**(见 6.5);回退退避;结束;
   - 无变化 → 停滞计数 +1;
2. **失效检测**:list 报 404/失效 → `SOURCE_INVALID` → 换源(6.3);
3. **补源搜索**(条件:官方已播集数 > 本地清单(§4.8),或 池中无可用候选,或 停滞 ≥3 且未完结):回退链搜索(清洗后标题 + 季,PanSou → TG-Search → 网页,即现有 `TelegramService.search`),单一关键词召回不足时按元数据别名逐个降级尝试(§4.8)→ `filterInvalidPanSouLinks` 预检 → 打分入池 → 若出现更优(覆盖缺集/集数更多)则换源/补集。

`next_check_time` 调度优先用官方播出日程(§4.8 的"播出时刻 +15min 连查 3 短轮"),无日程数据的条目回退固定周期 + 退避。

并发与重试:同一订阅的定时巡检与手动检查互斥(per-id 锁,防并发双挂载);`ERROR` 状态每日自动重试一次,连续 7 天失败置"停滞"并通知(§10.6)。

### 6.3 失效自愈(两条触发路径)

- **计划内**:每小时 `ShareService.validateShares`/`cleanShares` 时段,由 `SubscriptionResourceValidator` 轻量校验主源+候选池(列表探测);
- **计划外(即时)**:播放请求 404 时(`TvBoxService.getPlaylist` 现有 404 分支扩展):若路径属于订阅挂载前缀 → 同步激活候选次优源重挂(限一次、限时 5s),失败则返回错误并置 `ERROR` 等下轮巡检;
- **换源动作** = 同一 storage id 下更新 share 配置(`shareId/folderId/password`)→ enable → 重列验证 → 更新主源指向 + `SOURCE_REPLACED` 事件。路径不变(§4.1)。
- `ShareService.cleanShares`/`cleanInvalidShares` **必须跳过** `mount_path` 前缀为 `/追剧/` 的 share(失效不删,交由订阅服务换源);`cleanTempShares` 同理跳过(temp=false 本就不在清理范围,双保险)。

### 6.4 缺集补搜(需求 1)

1. 巡检发现 `缺失集 ≠ ∅`;
2. 先搜整季资源(`标题`,**聚合模式**:盘搜 + TG-Search + 网页多源全开合并去重,同集候选只挂载覆盖缺集最多者),对候选逐个(限 Top 3)挂 temp 列清单,选覆盖缺集最多者;
3. 整季搜索补不齐(如单集更新场景)→ 降级搜单集:`标题 第{N}集` / `SxxEyy`;
4. 结果进入合并播放(§4.5);`GAP_FILLED` 事件。temp 挂载用后即删,不留痕。

### 6.5 自动转存(需求 2 的 TRANSFER 模式)

`SubscriptionTransferService`:

1. `AListService.mkdir(site, account挂载路径 + "/追剧/{name}")`(**新增方法**,`/api/fs/mkdir`,照 `move()` 的 `postAdmin` 模板);
2. 列目标目录已有文件 → 差集 = 主源中"目标没有的新集文件";
3. 主源挂载(已固定)→ `AListService.copy(site, srcDir, dstDir, names=差集)`(**新增方法**,`/api/fs/copy`,返回任务 IDs)→ 轮询 `/api/admin/task/copy/undone|done`(3s×100 次上限),失败重试 1 次;
4. 完成后 `TRANSFER_DONE` 事件,合并播放自动优先自有副本;转存文件按"来源分享失效也无关"永久保留;订阅删除时**询问**是否保留已转存文件(默认保留)。

保护措施:每日转存任务数上限(默认 20,防风控/防配额);账号 Cookie 失效 → 事件 + `ERROR`,不自动降级模式(用户决策)。备用路径:若 fork 的 copy 对某分享驱动不成熟,可启用 fork 内置的 `quark_share_direct` 等定向开关(P2 按盘优化)。

### 6.6 完结判定

官方状态完结(TMDB season ended / Bangumi 已完结 / 豆瓣完结)且 `current_episodes ≥ expected_episodes` → 自动 `ENDED`(事件通知,停止巡检);无官方状态时连续 14 天无更新 → 建议 `ENDED`(前端一键确认,不自动)。

## 7. 与现有系统的集成点

| 系统 | 集成方式 |
|---|---|
| ShareService | 生命周期钩子:`cleanShares`/`cleanInvalidShares`/`cleanTempShares` 跳过 `/追剧/` 前缀;失效回调 `onShareInvalid(share)` → 订阅换源 |
| 搜索管道 | 直接复用 `TelegramService.search` + `RemoteSearchService.filterInvalidPanSouLinks`(有效性预检、`Message.type` 盘类型、PanSou/tg-search 回退) |
| 索引(IndexService) | 订阅创建时自动追加一条 index 模板行(路径 `/追剧/`,增量模式)→ 新集自动进入主索引/搜索/首页"最近更新" |
| 播放历史(History) | 不改动;靠固定路径 + 集号稳定排序天然续看 |
| 任务(Task) | 新增 `TaskType.SUBSCRIPTION`:手动检查/搜索补源/转存创建 Task 行,`/api/tasks` 可见;定时巡检只写事件不写 Task(避免刷屏) |
| 离线下载 | 不涉及;磁力/ed2k 候选直接丢弃(搜索过滤阶段排除,复用现有 `Message` 类型判断) |
| Web 安全 | `/api/media-subscriptions/**` 写操作要求 session 登录态(ADMIN/USER,数据按 uid 隔离;X-API-KEY 的 CLIENT 角色不开放管理接口);内容接口 `/tg-search/{token}?t=msub` 沿用订阅 token 鉴权 + permitAll(同 live-follow 模式) |
| Native image | V20 双注册(services 文件 + NativeFlywayMigrationConfig);新实体在 `entity/` 已被 Main.java 扫描,新 DTO 子包 `dto/subscription/` 若创建需确认在 dto 扫描列表;无新增资源文件 |

## 8. API 设计

### 管理端 `/api/media-subscriptions`(登录态,数据按 uid 隔离)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 订阅列表(含状态、集数进度、最近事件) |
| POST | `/` | 创建(触发首次检查,异步) |
| GET | `/meta/search?keyword=&provider=` | 元数据条目跨 provider 搜索(创建/编辑时选择,§4.8) |
| POST | `/preview` | dry-run 搜索预览(不落库,返回候选 + 打分明细 + 集数覆盖预估,§10.2) |
| GET / POST | `/export` / `/import` | 订阅导出/导入(JSON,重装迁移) |
| POST | `/{id}` | 修改(筛选条件/模式/周期) |
| DELETE | `/{id}` | 删除(?keepFiles=true 保留转存文件,默认 true) |
| POST | `/{id}/check` | 手动触发检查(创建 Task) |
| POST | `/{id}/pause` / `/{id}/resume` | 暂停/恢复 |
| GET | `/preference` | 当前用户默认偏好 |
| POST | `/preference` | 保存用户默认偏好(新订阅预填) |
| GET | `/{id}/events` | 事件时间线 |
| GET | `/{id}/resources` | 候选池(含各源集数覆盖) |
| POST | `/{id}/resources/{rid}/activate` | 手动换源 |
| POST | `/{id}/transfer` | 手动转存增量 |

### 内容端(TVBox/web 播放共用,token → uid 同 live-follow)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/tg-search/{token}?t=msub` | 订阅列表(MovieList:名称/封面/进度 remarks `已更新至N集/共M集`) |
| GET | `/tg-search/{token}?ac=detail&ids=msub:{id}` | 合并播放列表(vod_play_url 由 PlaylistMerger 生成) |
| POST | `/tg-search/{token}/msub/{action}` | TVBox 操作组:action = follow/unfollow/next,幂等(§10.1) |

`/tg-search` 首页分类首位插入"我的追更"(仅该 token 有订阅时显示),与直播"关注"分类同款交互。

## 9. 前端设计(web-ui)

- 新页面 `web-ui/src/views/MediaSubscriptionsView.vue` + 路由 `/media-subscriptions`,菜单名"追剧"(注意:现有"订阅"菜单是 TVBox 配置订阅,勿混);`App.vue` 桌面导航 + 移动 drawer 两处注册,gate `account.authenticated`(USER 亦可用)。
- 列表:卡片/表格混合 —— 封面(豆瓣)、名称、进度(`第 12 集 / 共 24 集` + 进度条)、模式徽章(挂载/转存)、状态(追更中/已完结/异常/已暂停)、最近更新时间;操作:立即检查、暂停/恢复、换源(抽屉)、删除。
- 详情抽屉:三个页签 —— 集数清单(缺失集高亮 + 该集来源盘)、候选资源表(分数/有效性/覆盖集数/一键启用)、事件时间线。
- 新建对话框:名称 + 元数据条目搜索选择(全部/豆瓣/TMDB/Bangumi 源页签,显示来源徽章/总集数/更新状态,按编辑距离预选最优,可"更换条目")、筛选条件(预设档位单选 + 自定义:盘类型偏好排序、清晰度下限、单集体积带、码率下限、关键词包含/排除;留空的维度继承用户默认偏好 §4.7)、模式单选(选"转存"时出现 DriverAccount 下拉,复用 AccountsView 数据源)、检查周期。
- 页头「偏好设置」入口:编辑用户默认偏好(档位 + 自定义参数),所有新订阅预填。
- 体验细节(操作组/预览/收件箱/批量操作等)见 §10。
- 验证:web-ui lint 已知损坏,统一用 `npm run build`(含 vue-tsc)。

## 10. 体验优化与能力补遗

### 10.1 订阅入口零门槛(最重要的体验)

- **TVBox 播放页一键追更**:复用 live-follow 的播放轨道约定 —— 详情页末尾追加"追更"操作组,固定选集 `订阅追更$msub$...`、`暂停追更$munsub$...`、`换下一个源$mnext$...`(后端幂等;spider 拦截 `$msub$` 等前缀 POST 到内容接口,反馈用 Android 原生 Toast —— FongMi 系不支持 `toast://`,同 live-follow 结论;spider 改动在 CatVodTVSpider 仓库,build.sh 构建后拷回 spring.jar);
- **web 端入口**:TG 搜索结果/豆瓣条目/播放页的"追更"按钮(预填用户偏好);播放历史页对未完结剧给"一键追更"推荐;
- **订阅即所见**:从正在播放的资源直接订阅时,当前源直接成为主源(跳过重新搜索排序),避免"订阅后换了个源、进度对不上"的突兀感;后续巡检再按偏好择机升级。

### 10.2 创建前预览(dry-run)

新建对话框"预览"按钮:按关键词 + 当前偏好即时跑一遍搜索(标记预览、不落库),展示候选源 + 打分明细 + 集数覆盖预估 —— 订阅前就知道能追到什么画质、缺不缺集,避免"订阅完才发现搜不到"。

### 10.3 进度感知与更新收件箱

- 列表 remarks 增强:`已看 8/12 集 · 3小时前更新`(结合 History 进度)、`缺第 5-6 集`、`已完结`;详情页最近 72h 新集置顶标记;
- **今日更新收件箱**:页头聚合视图,全部订阅的新集事件按天时间线/封面墙展示,一键直达;
- 通知分级:新集(全部)/ 换源失效(摘要)/ ERROR(即时)。

### 10.4 决策透明

- 候选池每行可展开**打分明细**(体积带贴近度/码率/盘序位/新近度各项得分),"为什么选这个源"可解释;
- 换源事件附带新旧源对比(旧源失效原因 + 新源评分)。

### 10.5 管理效率

- **健康面板**:统计卡(追更中/今日更新/异常/搜索成功率),异常订阅一键过滤;
- **批量操作**:列表提供 全选/全不选/反选 + 批量 检查/暂停/恢复/删除;
- 订阅**导出/导入**(JSON);详情页操作区:更换条目 / 强制刷新元数据 / 立即搜索。

### 10.6 稳健性能力

- **单订阅互斥**:定时巡检与手动检查 per-id 互斥(参考 `TaskService.isTaskRunning` 防重模式);
- **ERROR 自愈**:ERROR 每日自动重试,连续 7 天失败置"停滞"并通知;
- **空间水位**:转存前检查目标盘剩余空间,不足 → 事件 + 暂停转存模式(自动降级 FOLLOW,追更不断);
- **用户配额**:每用户订阅数上限 + 每日搜索额度(§11),防滥用拖垮全局搜索预算。

### 10.7 进阶 backlog(P3)

- **版本升级提醒**:偏好内更优版本出现(已有 1080P、出现 4K 源)→ 提醒可重转存/换源,不自动替换;
- **归档策略**:完结 N 天后自动释放转存文件(可配置,默认保留);
- **多季联动**:S1 完结且元数据显示 S2 开播/预订 → 事件提醒一键开订下一季;
- 指标(巡检耗时/搜索成功率/来源存活率)进 SystemInfo 页。

## 11. 配置项

`AppProperties`(前缀 `app.subscription`)+ Setting 运行时可改:

| 配置 | 默认 | 说明 |
|---|---|---|
| enabled | true | 总开关 |
| checkIntervalHours | 6 | 默认检查周期 |
| maxChecksPerRound | 10 | 每轮巡检订阅数上限 |
| candidatePoolSize | 5 | 候选池大小 |
| stallRoundsBeforeSearch | 3 | 停滞几轮触发搜索 |
| maxTransfersPerDay | 20 | 转存任务日限额 |
| minEpisodeSizeMb | 20 | 单集体积下限(过滤花絮,区别于偏好体积带) |
| defaultPreferenceProfile | balanced | 用户未设置偏好时的系统默认档位(ultra/balanced/lite) |
| metaRefreshIntervalHours | 24 | 每订阅官方元数据(集数/日程)刷新周期,集数/日程缓存 6h |
| maxSubscriptionsPerUser | 50 | 每用户订阅数上限 |
| userSearchQuotaPerDay | 100 | 每用户每日搜索额度(全局搜索预算保护) |
| keepTempSharesHours | 复用现有 | 补集临时挂载沿用 `tempShareExpiration` |

## 12. 风险与对策

| 风险 | 对策 |
|---|---|
| 搜索风控(t.me 网页/TG-Search/PanSou 各自限额) | 三级递进巡检(§4.3)把搜索降到最低;回退链优先走自建实例(PanSou/tg-search)少碰 t.me 网页;候选池预热;jitter;周期退避;复用现有 Caffeine 缓存 |
| AList copy 对分享驱动兼容性 | share 驱动只读作源、账号盘作目标是标准用法;逐盘验收,不兼容的盘降级为 FOLLOW 模式并提示;fork 定向开关兜底 |
| 转存配额/风控/空间(夸克、115 转存次数;目标盘容量) | 增量只转缺失文件;日限额;转存前空间水位检查,不足→暂停转存降级 FOLLOW(§10.6);失败事件显式暴露,不静默重试 |
| 换源后集数排序漂移导致 History.episode 错位 | 统一 FileNameInfo 按集号排序;合并列表同规;文档明示风险,详情页集名一致 |
| 偏好过严导致选不到源/补不齐缺集 | 硬过滤项尽量少且可空(继承链有默认值);降级阶梯逐级放宽并写事件,不静默降级(§4.7) |
| 手动检查与定时巡检并发双跑 | 单订阅 per-id 互斥锁;参考 TaskService.isTaskRunning 防重(§10.6) |
| 元数据 API 限流/不可用(TMDB key、豆瓣反爬、Bangumi 限流) | 缓存(条目 24h/集数日程 6h)+ 每订阅每日 1 次限流;条目可切换 provider;全部失效回退"标题解析 + 观测最大值"路径,降级不中断 |
| 元数据匹配错条目(同名剧/别名歧义) | 创建时编辑距离预选 + 用户确认;详情页提供"更换条目"入口 |
| 官方视频平台抓取脆弱(反爬/页面改版) | 定位为大陆独播剧集数兜底,默认关闭、按站点逐个启用(P3) |
| 多订阅同时挂载压垮 AList | 候选池不挂载(只存链接),仅主源常驻;补集 temp 用后即删 |
| 播放时同步换源超时 | 限时 5s 一次重挂,失败走异步巡检;用户可手动换源 |
| SSRF/恶意链接 | 候选链接挂载前过 `ShareService.isValidShareLink` 白名单,与现有分享同规 |
| 表膨胀 | 事件表保留 90 天(随 06:00 清理任务);候选池按 subscription 裁剪 |

## 13. 分期实施

| 阶段 | 内容 | 交付物 |
|---|---|---|
| **P0( MVP)** | V20 四表 + 订阅 CRUD + 定时巡检(重列主源/失效换源/候选池)+ 固定路径挂载 + 事件流 + 管理页(列表/新建(豆瓣条目匹配,复用现有)/手动检查/换源)+ ShareService 清理豁免 + TVBox `t=msub` 列表与单源播放 | 失效自动换源、追更基本可用 |
| **P1** | MetadataProvider SPI(豆瓣已有,新接 TMDB/Bangumi)+ 官方集数触发补搜 + 播出日程调度 + 自动完结 + 缺集补搜 + 多源合并播放列表 + 集数清单页签 + 索引模板联动(新集进主索引)+ 一键订阅(TVBox 操作组/web 入口)+ dry-run 预览 + 更新收件箱 + 导出导入 | 完整覆盖需求 1,追更时效由官方数据驱动 |
| **P2** | 自动转存(TransferService + AListService.mkdir/copy/任务轮询 + 日限额 + 空间水位)+ 转存模式 UI + TaskType.SUBSCRIPTION + 健康面板/批量操作/指标 | 完整覆盖需求 2 + 管理效率 |
| **P3(可选)** | 官方视频平台(腾讯/爱奇艺/优酷)"更新至N集"抓取兜底、Telegram Bot/webhook 通知、按盘官方转存 API 优化、集→源映射固化、版本升级提醒、归档清理策略、多季联动 | 体验完善 |

P0/P1 不依赖任何 AList 侧新能力(纯 Java + 现有挂载),风险最低;P2 的 `mkdir/copy/任务轮询` 是唯一需要新增 AList API 接入的部分,已隔离在单一服务内。
