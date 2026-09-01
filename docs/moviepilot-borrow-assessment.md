# MoviePilot 借鉴评估(对追剧系统)

> 调查日期:2026-09-01。样本:本地 `/home/harold/workspace/MoviePilot`(v3 分支,HEAD c9f339a57)。
> 结论先行:**A 级 4 项已于当日全部实现**(免打扰通知队列、搜索源统一退避、失败语义分档冷却、手动锁总集数+回落保护),B 级 5 项留触发条件,C 级明确不抄。
> MoviePilot 定位是 PT 站自动下载+本地媒体库整理,与我们「网盘分享挂载+TVBox 直看」场景重叠度有限,但订阅域的工程细节值得挑着拿。

## 0. 样本健康度提醒

本地 v3 分支 HEAD(c9f339a57,工作树 clean)存在 Python3 无法 import 的语法错误:`except TypeError, ValueError:` 这类 Python2 写法散布在 `app/scheduler/catalog.py:80`、`app/chain/search/pagination.py` 等 20+ 处,由本地 commit e57288b5e 引入(上游 jxxghp/MoviePilot 不应有——CI 会拦)。**该副本只能当代码阅读样本,不可运行;引用以上游为准。**

## 1. MoviePilot v3 概况

FastAPI + Vue3,聚焦订阅→搜索→下载→整理→刮削→通知。v3 把旧巨型类拆成 chain 编排层(`app/chain/subscribe/` facade+mixin)/纯算法层(`app/application/subscription/priority.py`,文件头自称「唯一算法来源,纯计算无 I/O」)/持久层三层。订阅检查默认 spider 模式:每天 7:00-23:00 生成 32 个随机 cron(约 20-40 分钟一次)爬站点首页缓存再匹配;元数据 6 小时刷新一轮并顺带「完成对账」。

与我们追剧系统的形态差异:它是**事件驱动+对账兜底**混合,我们是**周期巡检对账**;它的资源是种子(标题+副标题文本),我们是分享链接(要列目录探测);它的终结动作是下载到本地,我们是转存+挂载。**订阅匹配/缺集/完结的核心思路两边高度同构**——它能借鉴的是细节防御层,不是架构。

## 2. A 级:已实现(2026-09-01,全量 1296 绿)

> 实现落点:V46(manual_total_episodes)/V47(fail_kind)两个 Java 迁移;
> `MediaSubscription.manualTotalEpisodes`+`effectiveTotalEpisodes()` 统一口径(computeMissing/shouldAutoEnd/展示分母/通知卡片);`clampTotalShrink` 回落保护;
> 通知免打扰 `msub_notify_quiet_hours`(用户级回退全局,复用 outbox nextAttemptAt 推迟+sweep 到期捞起);
> `SearchSourceThrottle`(失败分类 delay 表,fillPool 走闸门/preview 豁免);资源 failKind 分档冷却(transientReprobeHours=24h vs badCooldownDays=7d)。

### 2.1 免打扰时段通知队列(配合凌晨巡检档)

- **MoviePilot 机制**:`MessageQueueManager`(`app/application/messaging/message.py:853`)配置允许发送时段,时段外通知进内存队列,后台线程周期冲刷;用户会话 `immediately=True` 例外。
- **我们的痛点**:4e49ed16 刚把 ENDED 剧巡检排到凌晨 03:15(nightCheckTimes),巡检发现新集/换源/完结的 TG 通知会在半夜发出。通知编辑同消息+outbox 重试(V36)已有,但都没有「什么时候发」的概念。
- **建议**:TG 通知出口加免打扰时段(默认如 08:00-23:00,凌晨档巡检产物排队到早上冲刷;可聚合为一条汇总)。实现小:内存队列+定时冲刷,outbox 语义不变(免打扰只影响投递时机,不影响落账)。

### 2.2 搜索源统一「租约+失败分类指数退避」

- **MoviePilot 机制**:`SubscriptionSiteBudget`(`app/application/subscription/sitebudget.py`)——每站点唯一在途租约(900s,防同站并发搜索);失败分类(`app/modules/indexer/__init__.py:71-85`:429→rate_limited、403→forbidden、未登录→login_invalid、超时→timeout)后按类退避:rate_limited/forbidden/login_invalid 基数 900s、timeout 300s、其他 180s,均 ×2^n 封顶(6h/2h/1h,n=连续失败数封顶 5);成功后下次同站随机 60-300s×恢复系数(1+0.5×min(连续失败,3)),即失败越久恢复越慢。另有站点级请求间隔预约(全局锁+每站下次时间原子预约,`app/chain/search/provider.py:33-71`)。
- **我们的现状**:只有百度一族有熔断+退避(PowerList 熔断 af3499eb、tg-search 显式 rate_limited、盘检批量降级兜底)。盘搜 10 站、玩偶聚合 11 站、盘聚、TG 系没有统一退避;源越加越多,逐源手写不可持续。
- **建议**:抽象一个 per-source 的退避状态(连续失败数+下次允许时间+失败分类),所有搜索源出口统一过;delay 表可直接抄上面那组基数/封顶。候选探测落集源的路径也过同一闸门。

### 2.3 资源失败语义分档冷却

- **MoviePilot 机制**:`DownloadFailure` 表(`app/db/models/downloadfailure.py`),指纹 = sha256(媒体键+季+集+站点+资源键) 唯一;TTL 按错误语义分两档——「种子不存在/deleted/404」等**资源性失败冷却 24h**,其他瞬时失败冷却 1h(`app/chain/download/failure.py:25-38`);冷却期内订阅候选直接跳过只记日志。
- **我们的现状**:BAD 冷却是资源级统一冷却(失效确认+BAD 冷却已有)。缺「按失败原因分 TTL」:网盘瞬时抖动(网关 5xx、列目录超时)和死链(分享被封)不该同冷却时长。
- **建议**:BAD/失效记录带上失败语义(分享死/网盘瞬时/转存失败),冷却时长分档(死链类长、瞬时类短)。与 2.2 配套,是同一个「失败分类」枚举的两个消费方。

### 2.4 手动锁总集数(manual_total_episodes)+ 总集数回落保护(已实现)

- **MoviePilot 机制**:订阅字段 `manual_total_episode`(`app/db/models/subscribe.py:106`),置 1 后元数据刷新不再自动改总集数;配合 6h 元数据对账的「总集数回落保护」(总数缩水时只允许回落到旧范围内已确认下载存在的最高集号,`app/chain/subscribe/refresh.py:329-397`)。
- **我们的痛点**:瑞克 S9 官方污染案(桥接把 officialEpisodes 污染成 11>真总数 10)我们靠夹紧(base 被 officialTotal 夹住)防上冲,但「官方总数本身错/反复横跳」时用户没有逃生舱,只能等修桥接。
- **实现**:订阅级「总集数锁定」字段(manualTotalEpisodes,编辑对话框「期望集数」旁,0=跟随官方),实体 `effectiveTotalEpisodes()` 统一 six 消费点口径(缺集计算/自动完结/清单占位上界/详情 total/缺口行/通知分母);观测真实文件不受夹(与既有「观测不夹」同规)。回落保护 `clampTotalShrink` 一并实现:官方总数回落只允许落到旧范围内已持有最高集号,记 ALIGN 事件;腾讯完结对齐(刻意修正路径)不经此闸。

## 3. B 级:选抄,有明确触发再做

### 3.1 多解析器交叉校验+多数派投票

MoviePilot 的集数定位规则(`app/application/formatting.py:244-1523`)对非标命名目录用 anitomy+兜底正则+原生 MetaInfoPath 三路解析交叉校验、多数派投票合成集模板,还有 SP/NCOP/NCED 特典样本排除。**我们不搬模板系统**(SeasonPackMap+season_starts 已覆盖季包对齐),但 `findNumberAndSource` 有区间盲区史(修过两处);若再现「整目录集号错位」案,解法应是加第二解析器+投票,而不是继续加特判。触发信号:下一个集号解析错案。

### 3.2 订阅级识别词(替换词)

MoviePilot 识别词四件套(屏蔽/替换/前<>后定位+偏移/复合,`app/domain/meta/words.py`),落在解析最前置的字符串改写层,支持正则、订阅级携带(`subscribe.custom_words`,匹配时按订阅重识别)。**对我们**:「混淆剧名目录」(夸克年番分享形态)的通用逃生舱——订阅上配「混淆名 => 真名」,替代逐案硬编码放行(ownSeasonPackTitle)。成本在 UI+匹配链路挂点;触发信号:下一个混淆目录案例。只抄「替换词」一种,偏移/屏蔽不需要。

### 3.3 TMDB 季感知年份匹配

MoviePilot TMDB 匹配(`app/modules/themoviedb/tmdbapi.py:654-671`):TV 候选要求标题命中**且**(首播年==候选年 或 指定季 air_date 年==候选年),专门解决「动画每季年份不同导致首播年对不上」。「多季长篇年份差」正是我们已知三形态之一;若出现按首播年门禁误拒多季候选的案子,把年份门禁升级成季感知。候选匹配是归一化精确相等+年份硬过滤(不是打分),这个低误判策略与我们 containsAsTitleWord 门禁同路数,可对照自查。

### 3.4 完结判定前兜底刷新总集数

MoviePilot 完成判定前强制 fresh 一次总集数(`app/chain/subscribe/refresh.py:490-546`)防旧 total 误完结。我们的 shouldAutoEnd 三路判定若吃的是巡检周期内缓存的 total,存在同类风险(ENDED 短路六层教训的近亲)。实现小(判定前带 TTL 缓存强刷一次),收益是少一族误完结。可与 2.4 同一改动顺手做。

### 3.5 JobSpec 式调度目录(overlap/timeout/recovery 三策略)

MoviePilot 用数据化 JobSpec+JobCatalog(重复 ID 拒绝、防重叠、durable queue 恢复,`app/application/scheduling.py`)管理约 20 个定时任务。我们的调度(nextSlotTime/sweep/nightCheckTimes)是手写的;若继续加定时任务(免打扰冲刷、退避恢复)可考虑收敛成一个统一注册表。纯内部质量项,不紧急。

## 4. C 级:明确不抄

| 项 | 理由 |
|---|---|
| 洗版/按集质量水位(`episode_priority` 推 100) | 我们已选「钉选换源」手动路线(bfc80c40 文案同步),不做自动换源升级 |
| 过滤规则组表达式引擎(`规则A > 规则B` 层级 pri_order) | 权重是用户定规固定档位(玩偶22>蜗牛20>…,「别再拉档差」);分辨率/体积/免费促销在网盘分享场景弱 |
| 下载器/整理/硬链接/刮削/NFO/媒体服务器 | 场景不同:网盘挂载只读、TVBox 直看、豆瓣元数据直用零网络 |
| 插件市场、工作流 DAG 编辑器、多通知渠道、离线 doctor | 单容器系统过重;TG 单渠道已够;自检已有自己的路子 |
| 共享识别(众包 title→ID 映射) | 引入外部社区依赖,违背全源零门槛惯例(TVDB 评估同因拒绝) |
| 站点用户上传量当隐性排序权重 | PT 生态特有,网盘无对应物 |
| Rust 加速器、CookieCloud、认证仿真 | 量级不需要;盘搜源免登录或用户自配凭证 |
| AList 存储抽象(`app/modules/filemanager/storages/alist.py`) | 我们内嵌 AList/PowerList,这块我们更强(仅可作 API 用法对照) |
| 新订阅 1 分钟缓冲 | 新建订阅首轮巡检语义(已挂主源即继续补缺)已覆盖;启用语义刚按用户反馈重定义,不动 |

## 5. 已互证等价、无需动的部分

对账式缺集计算(每轮重查不依赖上轮状态)、总集数随元数据增长 lack 增量、订阅完成写历史+删行同事务、搜索结果按站点权重排序、失败资源不重复撞(BAD 冷却等价)、同消息编辑通知(V36 已实现,正是从 media-vilot 同款思路来的)、绝对集号对齐(我们 SeasonPackMap/season_starts vs 它 TMDB Episode Groups,它依赖 TMDB 剧集组、我们自己换算,网盘场景我们方案更适用)。
