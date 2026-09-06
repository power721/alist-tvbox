# 追剧系统(自动追更)设计

> **🆕 缺集占位上电视(2026-09-06;用户定规:缺失的集数应在 TVBox 显示,与采集兜底开关无关)**:`fastDetail` 逻辑线路此前只并集 LIVE 行(+覆盖层),缺集在电视上整集隐身——连播从 836 直接跳 838,洞要上网页才知道。现逻辑线路在 `rewriteEpisodeTitles` **之后**按 `missingEpisodes` 口径(与巡检 computeMissing/网页缺集同口径:base = max(观测 LIVE, 官方已播/期望夹总集数),季起始下界,MAX_EPISODE_ROWS 上限)对缺集 `putIfAbsent` 占位条目 `NN. 标题(缺源)$msubep-{id}-{集}`。要点:①放在改写器之后——`rewriteTitles` 会按元数据重建条目文本,(缺源)标记会被改掉;②真源/覆盖层条目优先,占位只补洞;③不受兜底开关影响:点击占位走 msubep 播放链路,兜底开着则 attempted==0 同步救活(原本不可达的同步兜底入口就此打通),关着则如实报「第 N 集暂无可用播放源(已尝试 0 个源)」;④连播代价:自动下一集会撞上占位集(兜底关=报错停住),这是显式洞可见性的对价,用户定规接受;⑤LIVE 行全空时 fastDetail 仍回落 legacy(missingEpisodes 的 currentEpisodes 兜底口径保证不凭空造洞)。单测 1 条(官方 4 集本地 2 集:覆盖层垫底集无标记、纯缺集带(缺源)占位、真源条目原样),全量 1527 绿。

> **🆕 播放期兜底补全:正常起播触发采集窗口补齐 + 覆盖层可见可播 + 前瞻含缺集(2026-09-06;起因:海贼王 sub48 播 836 后 837 集无源,两级兜底均无动作)**:①**正常起播触发窗口补齐**——`playEpisode` 的转存/集源行两条成功路径此前只 kick `preheatAheadAsync`,`fillWindowAsync`(当前集+后3集采集补齐,EpisodeFallbackService 类注释本就写明「当前集正常时后台补齐不阻塞起播」)只在「当前集被采集源救活」分支被调用过 —— 836 正常起播后 837 的洞永远不触发采集兜底。现两路径统一经 `kickCollectionFallback` 触发(窗口无缺口零开销:一次集号查询即返;开关/负缓存 30min/10min 限频在服务内自查)。②**覆盖层可见可播**——`msub_episode_fallback` 行原本只在 `resolveEpisodeFallback` 播放期被消费,`fastDetail` 逻辑线路/盘线路与网页集数清单都只并集 LIVE 集源行 → 缺集在 TVBox 上没有可点条目(同步兜底实际不可达),补上后网页也仍显示缺失。现 `EpisodeFallbackService.activeRows`(ACTIVE 且未过期)并入:fastDetail 逻辑线路 `putIfAbsent` 补洞(真源条目不被顶替、覆盖层不进盘线路)、`episodes()` 集数清单呈现「采集兜底:{线路}」来源 + 矩阵 `FALLBACK` 状态行(前端标签「采集直链」);72h TTL 到期自然回缺失。③**前瞻含缺集**——`preheatAhead` 只探测已有 LIVE 行(「未上架集自然跳过」),`rescueAheadDead` 只救「有行但死」;现按 `computeMissing` 口径补算前瞻窗口内从未上架的缺集,与死集一并走 2h 冷却的 `submitCheck` 补源(缺集事件 TYPE_GAP_FILLED「第N 集缺集,已提前触发巡检补源」push=false 不外发,补上与否由后续 GAP_FILLED 说话)。候选源侧本就每 6~12h 巡检补缺(海贼王 837 属全网无源,STALL_COUNT=12),此路只是把节奏提前到播放期。单测 5 条(真源起播触发窗口补齐且不走同步兜底/覆盖层并入逻辑线路真源优先/集数清单 FALLBACK 行/前瞻窗口缺集触发补源事件/2h 冷却只触发一次),全量 1526 绿。

> **🆕 缺集主源的完整性升级 + 候选行「转主源」一步直达(2026-09-04;起因:线上反馈《金色》2026——缺集的夸克源(93 分)当主源,三条 13集全百度源(110 分)停在候补从未被探测也无法变主源)**:①**自动转正**——`fillGaps` 探测循环里新增 `upgradePrimaryOverIncomplete`:主源活着但本轮仍有缺口时,**分数不低于主源、且独力覆盖「主源已供集 ∪ 剩余缺口」(完整超集)的已探测候选直接 `activate` 转正**(不止挂补缺),旧主源回候选池兜底、事件文案「主源换为更完整源」与失效换源区分。守卫:钉选主源跳过(用户锁定);分数低于主源不转正(画质/盘偏好编码在分数里,完整但低分仍走补缺挂载);非完整超集不转正(转正会让主源已供集失去供流);单集源被 `usableAsPrimary` 挡在主源外;转正失败(挂载/AList 侧)回落本轮补缺挂载不炸循环。此前主源未失效永不换源(1557 行注释口径),缺集只能靠补缺挂载兜着,主源清单永远是缺的;`detectUpgrade` 只在**无缺集**分支跑且只提醒不换、只认 4K,覆盖不了本形态。在播剧安全:换源只在「有缺口」时发生,完整超集转正后缺口全消,更新中源反超时同一规则再换回,无乒乓(路径只在 missingStill 非空时可达)。②**UI 候选行补「转主源」按钮**——此前该按钮只在已挂载补缺行(启用→转主源两步),或用语义不直白的「钉选」;工具栏提示文案「换主源用转主源」终于名副其实。手动 activate/钉选本就不走盘白名单(candidatesOrdered 门禁只限自动路径),盘外存量候选手动换源不受白名单影响。单测 4 条(完整超集转正+旧主源回池+不再补搜/低分不转正挂补缺/钉选不转正/非超集不转正),全量 1465 绿。

> **🆕 订阅自定义搜索词(2026-09-02,V50 `custom_keywords`;起因:用户要「追剧手动设置多个搜索词」——资源命名差异大(英文名/别名/简繁写法)时单一关键词召回不足)**:订阅可配 ≤5 个额外搜索词(换行/中英文逗号/顿号分隔,存储规范化为换行分隔 TEXT)。接入四路:①**填池**——`fillPoolAllKeywords`(ensureSource 首轮/失效换源/误挂异剧/主盘保障/停滞补池五个调用点):主词一路(调用方 force)后各自定义词补一路,但**自定义词一律 force=false(池闸门生效):主词已搜到候选入池即收手,仅当池仍枯竭(主词召回不足)才逐词扩展**——扩展召回面不为「更多备胎」翻倍烧请求;各词共享同词 10min 去重,`lastPoolSearch` 由单槽改 **(订阅 id → 词 → 时间) 多槽**,多词各自去重互不覆盖。补搜轮次(fillGaps)不走这里——轮转已含且缺口补上即清轮次短路。②**补搜轮转**——`gapSearchKeyword` 在整季主词(round 1)与单集降级之间插入自定义词轮(round 2..1+K):整季粒度的换写法召回优先于拆单集(单集词粒度细召回窄);单集降级起点相应推后 K 轮。③**磁力兜底阈值同步推后**——`magnetFallback` 的 minRound 比较加 K(转存优先:网盘侧多词未穷尽前磁力不提前烧离线配额),K=0 存量行为不变。④**标题归属匹配**——`matchNames(subscription)` 并入自定义词(`addName` 别名口径):自定义词召回的资源标题可能不含剧名本名,不并入会被剧名门禁整条误杀(搜索侧扩召回、匹配侧必须同步认识)。Service 层 create/update 透传+规范化(空串清除/null 不动,变更即 searchRelevant 下轮巡检立即生效),web 编辑对话框「搜索词」下方加多行输入。§3 backlog 的「别名扩展搜索」由本功能以手动形态覆盖(自动别名轮降级未做)。单测 8 条(解析多分隔符/剔主词/轮转插入与单集推后/matchNames 并入与门禁放行/磁力词轮内不触发/create 规范化/update 覆盖-清除-保持/变更触发即时巡检),全量 1414 绿。

> **🆕 候选源「启用」改为仅挂载补缺、不动主源(2026-09-01;起因:用户反馈候选源点「启用」就变成主源)**:此前候选行「启用」直连 `activateAsync` = 完整换主源(删旧主挂载 → 同路径挂新分享 → 顶替转正),用户想「先把这条源挂上来」时主源被动替换。拆成两个动作:**「启用」(CANDIDATE 行)走新端点 `POST .../resources/{rid}/mount`(`mountAsync` → `mountCandidate`)**——探测落集源行(`probeCandidateSafely` 统一失败分级:限流不退役/异剧不拉黑/瞬时累计)+ 挂为补缺源(`mountAux`,.sources/ 内部目录,常驻豁免),**订阅 mountPath/shareId/主源行全程不动**,挂载失败不退役(探测已证明链接活着,ERROR 事件回执、留池下轮补缺重探);挂载完成后同线程串行触发一轮巡检(集数清单/事件/转存排队同步刷新)。「转主源」(MOUNTED 非主源行)保留 `activate` 换源语义,直接换主源仍走「钉选」(候选行即可钉)。文案同步:添加资源对话框/抽屉工具栏明示「启用只挂补缺不动主源」,主源移除引导语与 4K 升级提示改指「钉选换源」。单测 4 条(挂为补缺且主源字段/旧挂载原样/探测失败不挂载/挂载失败不退役+归属校验),全量 1277 绿。

> **🆕 完结剧凌晨巡检档位(2026-09-01;起因:用户问「完结剧巡检是否应放凌晨避开高峰」)**:ENDED 订阅两条定时路径此前都不挑时段——①仍在追看的完结剧(7 天内有播放且未看完)每 24h 完整巡检,而 scheduleNext 尾部 `min(常规间隔, 下一高峰档)` 在退避爬到 24h 封顶后几乎恒取高峰档,稳定吸附在 11:15/19:15(午间/晚黄金档);②看完的完结剧 7 天轻查是裸 `now+7d` 滚动时刻,随机散布全天。高峰档位「新集上线多查一轮」的语义对完结剧是反向负载:资源维护巡检无上线时效(播放失败有 `markPlaybackFailure` 即时信号兜底,不依赖定时轮),却挤进晚间用户观看、在播剧播后短轮与网盘外部 API 三个高峰。落地:新增 `subscription.nightCheckTimes`(默认 `03:15`,北京时间,避开 06:00 清理与 22:00 索引构建;档位计算泛化 `nextSlotTime` 供 prime/night 共用):①scheduleNext 尾部 ENDED 分流 `nextNightCheckTime`,min 语义不变(不晚于常规退避封顶,短退避不被凌晨档拉长;退避未爬满时先按常规间隔散在白天普通时段,几轮后自然收敛到凌晨档);②7 天轻查轮改 `nextWeeklyLiteCheckTime` = 7 天后对齐下一个凌晨档(未配置回落裸 +7d)。存量订阅已排的 nextCheckTime 不重排,查完一轮自然收敛;ACTIVE 在播剧不受影响仍走高峰档。单测 +3(ENDED 落凌晨档且不被更早的高峰档抢/ACTIVE 保持高峰档反向门禁/每周轻查对齐凌晨时刻与顺延上限),@BeforeEach 与 Fixture 同步关闭 night 档保存量断言确定性,全量 1273 绿。

> **🆕 短中文名前缀异剧门禁 + 资源手动移除(2026-09-01;起因:线上《醒来》(2026,两字剧名)的补缺挂载被同名短剧「醒来就成了千古一帝」冒领 16/18/19 集位,且候选资源没有任何移除入口「删不掉」)**:①**`containsAsTitleWord`**——`matchesTitle` 的归一化包含对 ≤2 汉字短名(醒来/蝉)过松:汉字间空格被 `collapseCjkSpaces` 塌掉后没有词边界,「醒来就成了千古一帝」包含「醒来」直接放行,而 fuzzy 兜底要求名长 ≥3,短名没有第二道防线。修复:短名(名内汉字 ≤2)的包含命中须过「粘连剥离」——出现位置同词粘连串剥掉已知同剧词汇(更新至/全N集/完结/大结局/盘名/季部集序数/数字字母,`SAME_SHOW_GLUE`)后,剩余**未知汉字 ≥5 才拒**;阈值 5 是刻意权衡:容忍装饰词(「真彩」2 字、「高码率」3 字,悬案线上形态「悬案 4K 高码率 更17集」剥后剩 3 必须放行),≤4 字前缀异剧(悬案⊂悬案解码)维持留给年份门禁的既有分工。生效面:`matchesTitle` 全部调用方(入池 `fillPool`/候选视图 `candidatesOrdered`/归属复核 `belongsToShow`)——已挂的误挂补缺源下轮 `refreshAuxMounts` 归属复核不过自动卸载回候选池,存量自愈。②**资源手动移除**:资源行新增终态 `REMOVED`(墓碑,保留行防 `(subscription, link)` 去重后重新入池;`isBadCooled`/`releaseBadCooldown`/`candidatesOrdered` 均不认它,永不自动复活),`DELETE /api/media-subscriptions/{id}/resources/{resourceId}`(`removeResource`:主源拒绝——移除会掏空订阅主路径,引导换源/删订阅;卸载顺序=先 AList 侧 `unmountShareIfUnused` 成功再 `deleteByResourceId` 清集源行,失败中止可重试);误移除走 `POST .../resources/{id}/restore` 回候选池。web 候选资源抽屉新增「移除/恢复」按钮(主源行不显示移除)。单测:短名前缀异剧拒/整词与粘连变体放行/悬案解码仍放行(年份门禁兜底)/移除(卸载顺序+墓碑态)/主源拒绝/恢复。

> **🆕 季包↔连续集号对齐(2026-08-31,分支 feat/msub-resource-episode-start;起因:一念永恒这类年番网盘分享形态=全剧连续区间集号+分季目录,季包资源被门禁整批拦在池外、分季订阅集号错位)**:最终路线=**用户拍板「分季订阅」**——为每季建独立订阅(season 1-4,起始集号留空),三个口径归位:①**`effectiveMetaSeason`**:TMDB totalSeasons==1 的剧 season>1 时元数据回落第 1 季全剧口径(一念永恒 TMDB 只登记一季,S2-S4 订阅拿不到本季元数据);②**`ensureSeasonStartEpisode`**:无手动值时自动推导订阅级起始集号,数据源腾讯优先、豆瓣兜底(`TencentSeasonAligner`/`DoubanSeasonAligner`,service/metadata/)——腾讯 MbSearch 分季条目 totalEpisode 与绝对集号严格对齐(S1=52/S2=53/S3=107/S4=166),豆瓣分季表降级兜底(两边完结季归位同口径:已播数超出已登记各季之和 → 目标季 = 最大季 + 1;腾讯缺完结季条目时 `seasonStarts` 无该季起点,infer 返 null 自然回落豆瓣);③**`effectiveTitleSeason`**:完结季归位(标题宣称「第2季/第3季」的完结内容实际是最后一季的,归位到完结季号——第 4 季订阅收、第 2/3 季订阅拒,本季季包连年份门禁放行)。**season=1 单订阅路线保留兜底**:`ownSeasonPackTitle` 放行本剧季包入池(此前「它季资源」门禁把季包当异季整批拦掉)+ **`SeasonPackMap` 文件级季映射**(SINGLE 整包单季/MULTI 目录分季,文件名→绝对集号;`season_starts` 持久化在资源行,V44,`"1:1,2:53"` 编码)+ 订阅级手动起始集号(V42 `season_start_episode`)/资源级起始集号(V43 `start_episode`,同剧不同分享起点不同)。**补缺侧三件套**:探测候选按「与缺口区间相交」重排(`orderForGapProbes`,预算不再被无关高分季包烧光)、挂载槽满换血(`evictWeakestAuxMount`,LISTED 行本就可供播,挤掉最弱挂载而非中断探测)、**越界门禁**(`mappingOverflowsOfficial`,文件级映射不能豁免观测门禁,完结季标题混装前季内容不冒领未播集)。**官方集数纠偏**:`applyTencentOfficialNumbers` 用腾讯分季表之和覆盖 TMDB 登记滞后(总数取 max,防在播剧误判完结;已知假设:若腾讯登记的是预告总数会抬高 aired cap,无防线)。区间推断两个盲区:第一季标题形态、分季表缺行时的完结季兜底。播放侧 `effectiveStartEpisode` 同口径消费起始集号(集号下界)。单测:DoubanSeasonAligner/TencentSeasonAligner/CheckService 对齐+门禁+换血,分支全量 1227 绿。**未并 master**;排障先看事件文案是否含已删文案「按豆瓣分季集数自动对齐」=旧 build。

> **🆕 手动播出时刻校正 + 无日程订阅高峰档位兜底(2026-08-30;起因:用户提议「自定义多个检查时间点」,方向修正为播出时间驱动 + 数据实测)**:国产剧播出时刻无公开统计(灯塔/云合只做播放量),以 4567 实例 22 订阅(docker cp H2 副本只读)实测分布为据:12:00×8(爱奇艺午间档)、20:00×6(⚠️混默认值)、11:00×5 / 10:00×5(国漫晨间簇)、19:00×5、09:00×3、19:30×2 —— 双高峰 午间 12:00 与黄金 19:00-20:00,国漫 10-11 点独立簇;卫视黄金档 19:30 开播为行业惯例。两项落地:①**手动播出时刻 `custom_air_clock`(V41,VARCHAR(5) "HH:mm",不带周几)** —— 官方只给日期的剧 nextAirTime 按当日 20:00 硬编码(绿灯军团这类美剧实际北京时间早晨播),编辑对话框 el-time-select 15min 步进可清空;`normalizeAirClock` 归一("H:mm"/"HH:mm",非法 400),`applyCustomAirClock` 挂 **applyMetadataSnapshot 尾部**(每次刷新重写快照后重放:改写 schedule 全条目与 nextAirTime 的时分、日期不动,episode=0 条目保留,nextAirTime 重取改写后第一个未来条目),update 路径立即重放+scheduleNext 重排不等 24h 节流刷新;优先级 **手动 > PlayScheduleBridge 平台桥 > 默认 20:00**(挂尾部天然覆盖);只校正时刻不造日期,无日程剧改完仍无触发、交给档位兜底。下游零改动:播出前休眠/播后缺口轮询/airedTarget 直播径全部自动认改写后的时刻。②**高峰档位兜底 `primeCheckTimes`(默认 11:15/12:15/19:15/20:15,北京时间,含 15min 上线缓冲)** —— scheduleNext 尾部常规间隔分支(无日程/日程已过尽)取 min(常规间隔, 下一档位),档位只认 ≥now+1h(避开刚查完的冗余轮)、今天无可用档取明天首个;检查零成本(重列主源+缺集判定),无日程订阅新集发现不再纯等固定周期。测试:档位兜底两条+1h 地板、时刻改写(日程/裸 nextAirTime)、归一化格式,@BeforeEach 关闭档位保常规间隔断言确定性(生产默认不变)。单测 +5,全量 1086 绿,web `npm run build`(vue-tsc)过。

> **🆕 巡检排程重整:缺口驱动 + 播出时刻只做触发器(2026-08-30,线上:下集 11:00 播、08:24 检查后排到 20:25;用户定规「看当前已播出多少集、还缺多少集来搜索资源,下一集播出时间只是用来触发检查」)**:三处叠加缺陷。①**缺老集让位睡穿播出时刻**——`behindAiredEpisodes` 让位(盗妖行修复)落回常规间隔,但常规间隔(12h)可能远长于距播出时间(2.5h),08:25 排程 11:00 开播却排到 20:25,新集发现晚 9h;让位分支改为取 min(常规间隔, 播出+15min)。②**播后短轮依赖 refresh 节流的巧合**——`nextAirTime` 严格取未播集,播后检查一旦触发元数据刷新(24h 节流到期)air 即前移到下一集,原短轮分支(`air≤now+15min`)永远进不去,直接落播出前休眠/常规退避。③**搜索触发同样吃 refresh 滞后**——`computeMissing` 的 base 只认 `officialEpisodes`(:35 在播刷新 6h TTL,巡检内刷新 24h 节流),播后首查拿旧值判"不缺"、fillGaps 根本不搜新集,发现全靠主源分享自更新。修复核心:**当前已播集数的 live 口径** —— 新增 `airedTarget`(= max(officialEpisodes, schedule 快照里播出时刻已到的最大集号 `airedBySchedule`),被官方总集数夹住,瑞克 S1 污染夹紧同口径):`computeMissing` 的 base 接入(播后首查立即把新集算进缺口并搜索,schedule 里未到时刻的集不算缺);`scheduleNext` 全程缺口驱动 —— 播后 regime(`recentAiredTime` 锚点 = 最近一个已到时刻的播出,优先 schedule 快照不受 refresh 前移影响,退回 stale nextAirTime;未出 12h 窗)**仅当 `behindAiredEpisodes(sub, airedTarget)`(已播集仍有缺口)时小时轮**,首查(播出+15min 槽位或跨播出时刻的检查,lastCheckTime < 锚点+30min)+30min 快速重试一次(线上诉求:20:00 播、20:15 首查,未命中 20:45 再试一次),**追平即收工**落回下一集播出触发/常规间隔,不空转整窗;播出前休眠条件从 `air>now+15min` 收紧为 `air>now`(距播出 <15min 的检查直接睡到播出+15min)。缺口判定用 `currentEpisodes` 快照(本轮巡检尾部 applyInventory/补缺后刷新,零额外查询)而非 stallCount(本轮补了老集但新集仍缺时 stallCount=0 会漏快速重试)。多集/日剧快照锚点自动逐集对齐。单测 9 条新增/改写(computeMissing 直播径含未播集不算缺/airedTarget 合成+总集数夹紧/首查缺集 +30min/追平收工/快速重试仅一次/快照锚定优先于 air 前移/缺老集让位不睡穿播出时刻/距播出 <15min 睡到播出+15min/behind 双侧门禁),全量 1081 绿。

> **🆕 TvBox「我的追剧」合并片单分类 + 一键追更(2026-08-30)**:csp_Media 此前只有 全部/追更中/已完结 三个自有订阅分类,电视端完全没有发现新剧的入口(搜索也只在已订阅里过滤),每次加剧都得开 web。现在 `/media` 分类在三个短 id 分类之后**整体并入 web 片单追更的 `PianDanService.subscriptionCategory()`**(豆瓣/TMDB 榜单+同源筛选,排除电影类目;type_id 带 `douban:`/`tmdb:` 前缀不撞车),`categoryContent` 的 extend 参数平铺透传为片单筛选。三个新行为:①**片单列表**(`/media?t=tmdb:xxx`)走 `pianDanService.list(ac="web")` 再统一 `absoluteClientCover` 重建客户端可用封面;已订阅条目 remarks 前缀「已追」(名称精确相等,与 web isNavSubscribed 同口径)。②**片单详情**(`/media?id=tmdb:tv:42` / `s:{标题}`):TMDB 走新 `PianDanService.tmdbDetail`(zh-CN 标题/年份/类型(type_name,空回落 电影/剧集)/演员(`append_to_response=credits` 同次请求带回 cast 前5,空回落 created_by)/评分/简介——类型塞 vod_actor 会被详情页按「演员」标签渲染,线上踩坑),豆瓣榜单条目无 subject id 只出标题;详情的选集固定两条目:**第一条目是无副作用的「📄 媒体信息」**(`msubinfo-` 前缀,**纯占位条目:静态 msg「媒体信息见详情页」,零网络零 DB**——元数据详情页已展示,此条目只负责占住第一条目防内核自动触发;`tmdbDetail` 自带 5min Caffeine 短缓存供详情页复用,控制器取拷贝防共享实例被就地改写污染)——部分播放器内核进详情会自动触发第一集播放,首条绝不能是订阅动作(线上实测误订阅);**其余条目是动作**:TMDB 多季剧(`tmdbDetail` 顺带解析 seasons[].season_number 滤 0 特典,经 ext 字段带回装配后置 null)按季展开——每季一条「➕ 追剧·第N季」/已追季「➖ 取消·第N季」,单季/电影/豆瓣条目则单条「➕ 加入追剧」或「➖ 取消追剧」;play id 载荷 `{vodId}|{剧名}|{季?}` 整体 URL form 编码,订阅时显式落季(create 的 resolveSeason 对显式 >1 不覆盖,幂等匹配按季区分——S5 与已有 S3 是两条订阅),取消带季号只撤该季 —— **爬虫端拼 GET 参数不编码,标题里的空格/&/# 会截断请求,必须在装配时 form 编码**(OkHttp/服务端各解一次后收到原始 vodId)。③**一键订阅**(`/play` 的 `msubadd-` 前缀):play id 内嵌剧名(`{vodId}|{名}` 整体 form 编码)——TMDB 条目订阅**零搜索零网络**(vod_id 里的 TMDB id 直接就是 metaId,标题从 play id 取,详情页那次 tmdbDetail 是唯一网络调用;无内嵌名的旧形态回落现拉 zh-CN 标题),直接绑元数据(官方集数/播出日程驱动追更);豆瓣条目绑 id **优先本地豆瓣库精确名匹配**(`localDoubanId`:Movie.id 即豆瓣 subject id,零网络),回落 suggest 名称精确匹配;**两级消歧口径一致:名称相等的结果恰好一个才绑** —— 同名翻拍多条时 suggest 同样无法消歧(片单条目无年份),取首条必赌错一半,不绑回落纯标题订阅,交给巡检名称桥接(有季号/年份上下文)消歧。**幂等是语义匹配非精确名**:`matchesTitle` 剥季号(`TextUtils.stripSeasonSuffix`)裸名相等 + 季号相容(任一方未标季即相容,双方都标须相等)——web TMDB 搜「末日地堡」订的 S3 与豆瓣片单「末日地堡 第三季」裸名同为「末日地堡」且都 S3,精确名匹配会开出第二条重复订阅(线上实测踩坑);已存在直接复用、不重复触发首轮巡检;回执走 `{"msg":...}`(FongMi `hasMsg()→onError(msg)` 提示通道)。**「已追」标注/取消追剧同口径**:`isSubscribedTitle`/`subscriptionIdsByTitle` 同用 matchesTitle;取消时条目标了季号只撤该季、不标季则同名各季一并撤。④**取消追剧**(`/play` 的 `msubdel-` 前缀):已追条目详情同线第二条目「➖ 取消追剧」,按 matchesTitle 语义匹配撤销(条目标季只撤该季、不标季同名各季一并撤,删除走服务级联);未订阅回「未在追剧中」——订错的撤销不再需要开 web。spider 侧 Media.java 零改动(纯透传 /media 与 /play)。单测 14 条新增(分类合并/片单列表已追标/两种详情伪线路含取消条目/msubadd 豆瓣与 TMDB 订阅/语义去重 S3 复用与 S1 不复用/幂等不重巡检/msubdel 多季级联取消/未订阅回执/非法条目)。

> **🆕 csp_Media 站内搜索并入 TMDB 全库(2026-08-31)**:上条遗留的「搜索也只在已订阅里过滤」痛点收口——此前 `/media?wd=` 只走 `contentList(uid,null,wd)`(订阅名/关键词包含),没追过的剧搜不到(返回空),全库搜索能力只存在于片单源 `/pian-dan`(`PianDanService.search`=TMDB multi)。现在 `MediaLibraryController.searchAll` 在本体合并两者(用户参考的外部 pansou-bridge 方案做进服务端,不再依赖 bridge):**已订阅命中在前 + `pianDanService.search(wd,pg,24)`(TMDB multi 全库,含电影)在后**;TMDB 侧封面统一 `absoluteClientCover`(/images 代理+绝对地址,与片单列表同款)、已追条目 remarks 前缀「已追 」(isSubscribedTitle 语义匹配,同口径);**翻页只翻 TMDB 侧**(订阅命中固定第一页,pg>1 不再注入)。闭环零新增:搜到的未追条目 vod_id 即 `tmdb:tv:42` 形态,点详情走既有 `pianDanDetail` → 「➕ 加入追剧」msubadd- 一键建订阅。TMDB 失败不拖垮搜索(`search` 内部 catch 返回空表,只剩订阅命中)。单测 3 条(合并顺序/已追角标+封面改写/pg=2 只出 TMDB),全量 1241 绿。

> **🆕 四位数集号支持 + 长寿剧登记滞后容差(2026-08-27,线上:名侦探柯南订阅只显示 1 集)**:`parseEpisode` 的集号可信域上限 999(`value >= 1 && value <= 999`),长寿动漫实际集号早已过千 —— 柯南(TMDB 登记总 1212)百度主源 189 个文件全部四位集号命名(`1173.mp4`/`1178国语.mp4`/`1245 4KHDR日语.mp4`),999 上限下零识别;唯一"识别"出的 1 集是剧场版子目录的电影文件 `2025.V2...TrueHD.5.1-Nest@ADE.mkv`(末号规则把声道位 `5.1` 的 1 当集号),订阅显示 currentEpisodes=1/maxEpisode=1。三层修复:①**可信域扩到 1000-9999,年份形态(1900-2099)继续排除**(新 `plausibleEpisodeNumber`,文件名四位数字里唯一常见噪声就是年份 2024/2025);SxxEyy/`parseEpisodeFromTitle`/`BRACKET_EPISODE_MARK`/`TITLE_PROGRESS`/`COMPLETE_PACK` 同步放宽到四位。②**集号门禁容差随登记体量放大**(`registrationLagTolerance` = max(2, officialTotal/10)):固定「未播完溢出 >2 即异剧」会把 TMDB 登记滞后数十集的正确主源整源误杀(柯南登记 1212 vs 实际 1270,溢出 58),`episodeNumbersForeign`/`titleProgressForeign` 两处同改;小体量区间(真人版 37 vs 动画版 26)容差仍 2,原判别力不变。③**TECH_TAGS 补剥 truehd/声道位(`5.1`)/版本号(`.V2`)**:剧场版电影名剥净后只剩年份形态 → 不再冒充「第1集」;声道位带数字边界(前后非数字),日期戳 `2026.8.21`/`2026.08.21` 里的 `6.0`/`8.2` 不在剥离范围,缺陷 10(日期戳当集号)不回归。同场发现(次日接线,见下条):订阅过滤 DTO 的 `minEpisodeSizeMb` 后端并未消费,UI 配了 200MB 也不生效。单测 5 条(四位集号全形态/年份形态拒识/声道剥离不伤日期戳/集号门禁长寿剧容差/标题宣称容差),全量 960 绿。

> **🆕 订阅最小单集体积接线(2026-08-27,线上:用户手填 200MB 不生效)**:过滤器 DTO 的 `minEpisodeSizeMb` 此前后端从未消费(集文件入库走全局 `subscription.minEpisodeSizeMb` 默认 20MB,`maxEpisodeSizeMb` 有接线)。接线为 `EpisodeSizePolicy` 三段策略(floor 硬底线=全局 20MB 垃圾防护,显式调低时覆盖 / preferred 偏好线=用户配的最小体积 / max 单集上限):**偏好层而非硬门** —— 同集择优时达标文件压过不达标文件(先到的大文件不被后来的小文件顶掉,反之小文件被顶换),该集只有不达标文件时照收(实在找不到合规资源才忽略限制,线上柯南 1173-1216 仅 130-160MB、1217+ 有 4K 版,硬门会把前段整段丢掉),低于硬底线的仍然硬拒。全部列举路径接线:巡检主源 `listEpisodeFiles`/探测 `probeShare`/激活 activate/辅助挂载 refreshAuxMounts/合并 `walkEpisodeFiles`/播放解析 `episodeFilesAt`(集→文件,播放与转存增量 copy)/转存目标核对 `listTargetEpisodes`(walkEpisodes/walkEpisodesAt 签名 long max → policy)。单测 2 条(策略计算/达标优先缺额兜底),全量 962 绿。

> **🆕 Telegram 通知升级:同剧编辑同一条消息 + outbox 重试(2026-08-27,借鉴 media-vault P5 publish_tasks/message_bindings;V36)**:旧 notifyTelegram 是 fire-and-forget sendMessage —— 追一部周更剧一季十几条独立消息刷屏,网络失败只打 debug 日志即丢。新结构两部分:①**消息绑定**(订阅行 `tg_message_id`/`tg_chat_id`,V36 加列):每订阅首条通知 sendMessage 落 message_id,之后所有事件 `editMessageText` 编辑同一条消息,内容为「标题+状态行+最近 5 条已推送事件」卡片(从事件流现算,重复投递幂等);chat 配置换绑后旧 id 失效自动重发,编辑目标被删/超龄(`message to edit not found` 等)也重发换绑,`message is not modified` 视为成功。②**outbox 重试**(新表 `media_subscription_notify_task`,V36):任务行只承载重试状态(attempts 平方退避 1/4/9/15min 封顶,5 次转 FAILED 留审计),内容不落行;入队去重(existsBy PENDING)+处理端按订阅合并(PENDING 一次全消);入队即试发(单线程 msub-notify 保持秒级到达)+ 每分钟兜底扫描(cron `15 * * * * *`)+ in-flight 集合防同订阅并发双发;通知未配置(token/chat 空)任务静默完成(与旧行为一致,事件仍进站内时间线);订阅已删的孤儿任务由处理端 deleteBySubscriptionId 回收;rebind 前 findById 重取防删行复活(无 @Version 实体 save 复活坑)。入口收敛:addEvent push=true → MediaSubscriptionNotificationService.onEvent(类型门禁不变:NEW_EPISODE/ERROR/ENDED/TRANSFER_DONE)。迁移双注册(SPI+NativeFlyway)。单测 12 条(首发落绑定/编辑同消息/未变成功/失效换绑/chat 变更/退避/超限 FAILED/未配置静默/入队去重/类型门禁/孤儿回收/卡片构建)。

> **🆕 手动钉选主源(2026-08-27,借鉴追更助手 exportManual:用户指定压过自动判定;V35 pinned 列)**:换源/巡检全自动,用户此前没有「钉死这条源」的手段 —— 归属门禁误杀(如真人版判异剧)后用户手动启用正确源,下轮巡检复核又把它换走。资源行加 `pinned` 标记(V35,nullable Boolean,null=未钉选;实体 `MediaSubscriptionResource.pinned`):**钉选 = 立即激活为主源 + 永久优先**,三层语义 —— ①换源候选序置顶(primaryCandidates 首层,压过待看集层与分数序);②主源归属复核豁免(doCheck 复核抽 `shouldReplacePrimary`:异剧判定不换走钉选主源,空壳不豁免——挂不上内容的钉选无意义且换季重置依赖它;ENDED 异剧重开路径同豁免);③失效换源不受影响,钉选行保留(RETIRED 冷却后经 candidatesOrdered 回池仍置顶),恢复可用优先回归。每订阅一个钉选位,`applyPin` 钉新清旧。API:`POST .../resources/{rid}/pin|unpin`(pin=applyPin+TYPE_PINNED 事件+activateAsync;unpin 只清标记);事件 TYPE_PINNED push=false(用户自发动作不外发)。前端候选源抽屉:钉选/取消钉选按钮 + 角色「钉选」红标。单测 4 条(shouldReplacePrimaryForms/activateTopsPinnedCandidateRegardlessOfScore/reopenEndedKeepsPinnedAlienPrimary/applyPinClearsOtherPinsAndUnpinRestores)。

> **🆕 待看集覆盖入主源排序(2026-08-27,借鉴追更助手 coversExpectedEpisode)**:换源候选序此前只按分数(盘偏好/画质/新近度),不感知观看位置 —— 主源刚失效时用户要续看的正是 watched+1 那集。`primaryCandidates`(activateNextCandidate 入口,ensureSource 首轮挂载同经此路)在分数序之上把**集源行已知含待看集**的候选整体提前:已知覆盖的确定性优先于高分候选的未知覆盖(此前为补缺/线路探测过的候选都有 LIVE 行,天然有此数据)。零侵入边界:观看进度未知(无播放记录,watchedEpisode 读 History 的 msubep 逻辑链接)/无人已知覆盖/全部已知覆盖 → 原分数序不变;单集链接不会被此提前顶上主源(usableAsPrimary 照旧拦截);补缺 fillGaps 与分盘线路 ensureDriveLines 的候选序不受影响(它们有自己的覆盖判定,不该被观看进度扰动)。命中重排时 info 日志记一条(N 个已知覆盖待看第 M 集)供排障。单测 activatePrefersCandidateCoveringNextWatchEpisode / activateKeepsScoreOrderWithoutWatchProgress。

> **🆕 非剧本内容豁免集数门禁(2026-08-27,借鉴 Node.js 追更助手 shouldUseTmdbReferenceScoring)**:三个集数门禁(集号范围 `episodeNumbersForeign`/文件级噪声剔除 `stripForeignEpisodeNoise`/标题宣称 `titleProgressForeign`)全部锚定 officialTotal,此前只处理「官方总集数未知→放行」——综艺/纪实类内容 TMDB 登记的季总集数天然不可靠(随录随播、加更/删减常态),登记 12 实播 20 时三门禁会把正主资源整套误杀(宣称「全20集」入池即拒、探测集号超界判异剧退役、缺号断裂被当噪声剔除)。现 `nonScriptedContent(genres)` 识别非剧本内容(genres 命中 综艺/真人秀/脱口秀/访谈/纪录/纪实/新闻 及对应英文类型名)后三门禁整体豁免,**只认 genres 正向证据、不做标题词兜底**(「新闻女王」是剧本剧,标题词必误伤),genres 缺失(豆瓣纯源)不豁免、门禁维持 —— 与「真人版」版本词门禁同款零误伤口径。落点七处全接线:fillPool/candidatesOrdered(标题宣称)、probeShare/activate/belongsToShow(集号)、refreshAuxMounts/listEpisodeFiles/probeShare/activate(噪声剔除);季过滤/时长门禁不依赖 officialTotal,继续生效。单测 `varietyShowEpisodeGatesRelaxed`(含剧本内容对照组)。

> **🆕 入池落选审计写入事件流(2026-08-27,借鉴 Node.js 追更助手的 selection-audit)**:POOL_FILLED 事件此前只报「过滤 N 条不相关结果」一个总数,配额挤掉/死链/同集去重/已在池这些 drop 是黑盒,线上「为什么没选它」只能翻 DEBUG 日志。`fillPool` 全程挂 `PoolDropAudit`:落选分 12 桶计数 —— 剧名不符/年份不符/异剧形态/它季资源/命中排除词(标题类,留缩略样例前 2 条)+ 非白名单盘/非网盘结果/死链/盘席满/总配额满/同集去重/已在池(机械类,只计数);以「;拦截:剧名不符 3(例:xx、yy)、盘席满 4」后缀写进事件详情,零落选不加后缀。分层配额 break 截断从静默改为记余量整段入「总配额满」。纯观测增强,不参与任何入池决策;单测 `fillPoolFiltersIrrelevantResults` 补事件断言,全量 935 绿。

> **🆕 移动云盘服务端转存接入(2026-08-26,契约来自用户真实抓包)——分享盘族全数接齐(十族)**:移动(类型码 6)。PowerList `139_share` 实现 `driver.ShareSaver`:走分享站网页端「保存至云盘」同款 **`IBatchOprTask/createOuterLinkBatchOprTask`**(加密信封与取链 IOutLink 同款 AES-CBC/PVGDwmcvfs1uV3d1):`taskType=1` 转存,文件/目录分列 `contentInfoList`/`catalogInfoList`(目录整棵递归)一次批量提交,**`newCatalogID`=目标目录**(配 newCatalogName 展示名),`needPassword`=分享是否带码,msisdn/commonAccountInfo=接收账号;鉴权复用账号驱动(`Basic` Authorization + 账号侧同款 mcloud 请求头,新增 `Yun139.PostEncryptedShare` 签名按密文计算)。提交返回异步 taskID(`sk*数字`),轮询 **`queryBatchOprTaskDetail`** 至 `taskStatus=2`(有界 2min),**新对象 id 取任务明细 `catalogList/contentList.idRspInfo` 的 srcId→rstId 映射(reason=0000)**——十族中唯一任务直接回报新旧 id 映射的,差集都免了。Java 侧 `serverSavable` 加 6,十族齐:阿里0/迅雷2/123_3/夸克5/UC7/115_8/天翼9/百度10/光鸭12/移动6。单测:Go 三条(content/catalog 分列+目标参数/错误上抛/非 139 目标拒绝)+ Java `pan139EpisodesSavedServerSide`,全量 933 绿。**部署提醒:需重建 PowerList 镜像生效。**

> **🆕 阿里云盘服务端转存接入(2026-08-26)——服务端保存族集齐九族**:阿里(类型码 0),阿里 0/迅雷 2/123 3/夸克 5/UC 7/115 8/天翼 9/百度 10/光鸭 12 全部网盘侧秒传,仅剩移动云盘(6)未接(copy 兜底)。PowerList `aliyundrive_share`(Java 阿里分享源挂的 AliyunShare 即此驱动)实现 `driver.ShareSaver`:走 `api.alipan.com/adrive/v4/batch` 信封内嵌 `/file/copy`(与 `aliyundrive_share2_open` 取链兜底 `saveFile` 同款,那边硬编码临时目录),`to_parent_file_id` 直达目标目录、`to_drive_id` 目标账号 DriveId、`auto_rename` 防同名;阿里 copy 对文件夹 file_id 整棵保存;响应 `responses[0].body.file_id` 即新对象 id(逐对象提交,无需差集)。**鉴权双 token**:用户侧用目标账号 `AccessToken2`(网页系 token,open refresh_token 经 auth.alipan.com 换得,`RefreshAliToken` 维护),分享侧 `X-Share-Token`;双 token 失效自愈(AccessTokenInvalid→RefreshAliToken/ShareLinkTokenInvalid→getShareToken)照搬既有 request 模式,新增 `requestAli` 走包内 limiter。目标断言 `*aliyundrive_open.AliyundriveOpen`(Java 阿里目标走独立账号表 `ali:{id}` 分支,挂载资源盘)。Java 侧 `serverSavable` 加 0(阿里跨盘秒传 Setting `ali_to_115/123` 早已存在,同盘转存即服务端)。单测:Go 三条(逐对象循环+新 id/错误上抛/非阿里目标拒绝,`aliShareCopyOne` var 桩)+ Java `aliEpisodesSavedServerSide`(ali: 分支目标),全量 932 绿。**部署提醒:需重建 PowerList 镜像生效。**

> **🆕 光鸭云盘服务端转存接入(2026-08-26)**:服务端保存族第八族——光鸭(类型码 12)。PowerList `guangyapan_share` 实现 `driver.ShareSaver`(`SaveTo`):与取链兜底 `restoreShare` 同原语(`/userres/v1/restore_share`),`parentId` 直达目标目录(那边硬编码临时目录)、`fileIds` 一次批量携带;`code=219`(目标已存在)视为成功、分享 token 失效自动重取重试一次(既有逻辑照搬);提交后 `waitTaskDone` 轮询异步任务(status 2=完成/-1,3=失败,既有 20×500ms 有界等待);新 id 任务不批量回报,经转存前后目标目录差集解析。restore_share 未见目录递归转存语义,**目录对象直接回退字节中转**(其下文件可逐个转存补齐);`ShareAccessToken` 空时先 `getShareAccessToken`。目标断言 `*guangyapan.GuangYaPan`(Java GUANGYA 恒挂 guangyapan)。Java 侧 `serverSavable` 加 12。单测:Go 三条(批量参数+差集+目录拒绝/任务错误上抛/非光鸭目标拒绝,`saveGuangyaTask`/`listGuangyaTarget` 可替换桩)+ Java `guangyaEpisodesSavedServerSide`,全量 931 绿。**部署提醒:需重建 PowerList 镜像生效。**至此八族齐(迅雷/123/夸克/UC/115/天翼/百度/光鸭),仅剩移动云盘/阿里未接(均有 copy 兜底)。

> **🆕 123 网盘服务端转存接入(2026-08-26,契约来自用户真实抓包)**:服务端保存族第七族——123(类型码 3),主流盘八族里仅剩移动云盘/阿里/光鸭未接。`123_share` 的 SaveTo 双分支:①**cookie 版(123Pan)走分享站「保存到网盘」同款 goapi** `yun.123pan.com/api/restful/goapi/v1/file/copy/save`(分享站子域与主域同网关):`fileList` 批量携带(fileID/size/etag/type 目录=1/fileName/driveID=0),**目标目录=每个条目的 parentFileID**(抓包实证:转存落在用户自选目录),sharePwd 空传 null,`currentLevel:0/superAdmin:null` 透传;提交返回异步 taskID,轮询 `copy/save/get?taskID=` 至 **status=2 且 errorCode=0**(失败 errorCode≠0 带 reason),有界等待 2min;签名即驱动既有 `GetApi/signPath`(抓包的 `{crc32时间签名}={ts}-{随机<1e7}-{crc32}` 查询参数与 signPath 输出同构),`pan123.Request` 自带 Bearer+auth-key+401 自动重登;新 id 任务响应不回报,经转存前后目标目录差集解析。②**开放平台版(123 Open)按 Etag(MD5)纯 hash 秒传**:`123_open` 新增 `ReuseTo(parentFileID,...)`(`Reuse` 委托之,123_rapid 零改动),MD5 走 file/create(etag)、SHA1 走 sha1_reuse,duplicate=1;目录无 hash、秒传未命中(服务端无同 hash 文件)回退字节中转。Java 侧 `serverSavable` 加 3(PAN123/OPEN123 都有服务端路径,relayOnly 不动)。单测:Go 四条(goapi fileList/目标目录/差集、Open 秒传参数+目录拒绝、任务错误上抛、非 123 目标拒绝)+ Java `pan123EpisodesSavedServerSide`,全量 930 绿。**部署提醒:需重建 PowerList 镜像生效。**

> **🆕 迅雷云盘服务端转存接入(2026-08-26)**:服务端保存族第六族——迅雷(类型码 2),同盘转存六族齐:迅雷2/夸克5/UC7/115_8/天翼9/百度10。PowerList `thunder_share` 实现 `driver.ShareSaver`(`SaveTo`):与取链兜底 `saveFile` 同原语(`share/restore`),`parent_id` 直达目标目录、`file_ids` 一次批量携带全部对象(那边硬编码临时目录+单文件);新对象 id 直接取响应 `params.trace_file_ids` 的「源 id→新 id」映射(`RestoredFileID`,无需差集兜底)。`ShareToken` 空时先 `getShareInfo` 补票——空 pass_code_token 本就是「提取码错误/分享被屏蔽」的既有报错信号。目标断言 `*ThunderBrowser`(Java THUNDER 恒挂 ThunderBrowser personal 型)。Java 侧仅 `serverSavable` 加 `dstType == 2`。单测:Go 三条(restore 表单拼装+trace 映射/错误上抛/非迅雷目标拒绝,`thunderShareRestore` 可替换桩)+ Java `thunderEpisodesSavedServerSide`,全量 929 绿。**部署提醒:需重建 PowerList 镜像生效。**

> **🆕 天翼云盘服务端转存接入 + ShareSaver 契约升级(2026-08-26)**:服务端保存族第五族——天翼(类型码 9),同盘转存五族齐:夸克5/UC7/115_8/天翼9/百度10。**契约升级**:`driver.ShareSaver.SaveTo` 参数从裸 `ids []string` 改为完整 `objs []model.Obj`(端点处理器本就持有完整对象)——天翼 SHARE_SAVE 的 taskInfos 需要 `fileName`/`isFolder`,裸 id 无法承载;夸克/UC/百度/115 四驱动各自 `GetID()` 派生,行为不变。PowerList `189_share` 实现 SaveTo:与取链兜底 `Transfer` 同原语(`CreateBatchTask("SHARE_SAVE")` + `WaitBatchTask`,那边硬编码临时目录),`targetFolderId` 直达目标目录、一次任务批量携带全部对象;**不复用 WaitBatchTask**(无超时会挂死同步调用),自轮 `CheckBatchTask` 有界等待 2 分钟,状态 2(同名冲突)视为已完成;新对象 id 取任务状态 `successedFileIdList`(目录转存可能不回报),空则回退转存前后目标目录差集(按文件 id)。目标断言 `*Cloud189PC`(Java 侧 CLOUD189 恒挂 189CloudPC personal 型);`getShareInfo` 走既有 shareTokenCache 免重复网络。Java 侧仅 `serverSavable` 加 `dstType == 9`。单测:Go 四条(taskInfos 拼装含 IsFolder/差集兜底/任务错误上抛/非天翼目标拒绝,`shareSaveTask`/`list189Target` 可替换桩+`shareTokenCache` 预填免网络)+ Java `cloud189EpisodesSavedServerSide`,全量 928 绿。**部署提醒:需重建 PowerList 镜像生效。**

> **🆕 115 网盘服务端转存接入(2026-08-26)**:服务端保存族再扩 115(类型码 8),追剧「夸克/UC/百度/115」四族同盘转存全部网盘侧秒传。PowerList `115_share` 实现 `driver.ShareSaver`(`SaveTo`):走 `webapi.115.com/share/receive`(`share_code/receive_code/file_id` 逗号拼接/`cid` 直达目标目录,参数契约取自 p115client 参考实现;webapi 同族 `share/snap` 是该 client 既有调用先例),**仅 cookie 版 `115 Cloud` 账号可转存——115 开放平台无分享接收接口**(p115client `P115OpenClient` 无 share_receive 佐证)。分享对象 id 形态二分(文件 `fid-sha1` 复合、目录裸 cid),`file_id` 只取 fid 部分(`SplitN("-")`,与 Link 同款拆法);响应体不带新 fid 的稳定字段,新 id 经转存前后目标目录清单差集解析(文件按 sha1、目录按名称,`listTargetIndex` 可替换桩);列举失败不阻断转存只放弃 id 解析。Java 侧:`serverSavable` 加 `dstType == 8`;`TransferTarget.tv` 字段更名 **`relayOnly`**(仅字节中转目标)并把 `OPEN115` 纳入——开放平台账号直接走 copy 回退,不再白打一轮 share/save 400。单测:Go 三条(receive 表单拼装+Referer/差集解析/非 115 目标拒绝)+ Java 两条(PAN115 服务端转存/OPEN115 回退 copy);全量 927 绿。**部署提醒:需重建 PowerList 镜像生效。**

> **🆕 巡检完成即联动转存(2026-08-26,线上:「建了订阅根本没转存」)**:TRANSFER 模式的转存此前只有两个入口——每小时 :40 自愈 sweep(`MediaSubscriptionTransferService.sweep`)和手动「转存」按钮(`POST /{id}/transfer`),**巡检发现新集/新建订阅首轮挂载完成后并不触发**。新建订阅首轮巡检(建订阅即 `checkAsync`,搜索+挂载要几分钟)把源挂齐后,要空等最长一小时才轮到 :40 sweep 转存,期间用户查网盘「根本没有转存」。与 §6.5「发现新集后 copy」的设计口径不符。修复三入口:①`MediaSubscriptionCheckService` 构造器尾参注入 `@Lazy MediaSubscriptionTransferService`(`@Lazy` 代理破与它的构造循环——TransferService 构造注入 CheckService;旧 23 参签名保留为重载委托 null,存量裸实例测试零改动),`check()` 尾部(`saveUnlessDeleted` 后)与 `activateAsync` 换源落库后调 `scheduleTransferAfterCheck`:mode=TRANSFER 即排队 `transferAsync`。②`MediaSubscriptionService.update()`:编辑切入 TRANSFER(挂载模式改转存)或转存目标账号变化,事务 `afterCommit` 后立即排队 `transferAsync`(`TransactionSynchronizationManager` 注册;直调无事务的兜底同步执行——update 是 `@Transactional`,`transfer()` 入口 findById 要读已提交的新 mode,提交前抢跑会读到旧 FOLLOW 静默跳过)。幂等安全:`transferAsync` 入口重取最新行,目标已齐空手而归不占日配额;与 :40 sweep 的并发由转存单线程执行器串行化。ENDED 七天轻查提前 return 与 ERROR catch 分支不触发(sweep 每小时兜底)。单测:`transferModeSubscriptionQueuesTransferAfterCheck`/`nonTransferModeSubscriptionDoesNotQueueTransfer` + `MediaSubscriptionUpdateTransferTest` 四条(切模式/换账号触发,改名/无关编辑不触发);全量 925 绿。

> **🆕 百度网盘服务端转存接入(2026-08-26)**:追剧转存的服务端保存族从夸克(5)/UC(7)扩到百度(10)。PowerList 侧 `baidu_share2` 实现 `driver.ShareSaver`(`SaveTo`):把取链兜底用的 `/share/transfer` 调用抽成 `transferShare`(目标目录参数化,原来硬编码临时目录),`SaveTo` 断言目标为 `BaiduNetdisk` 账号存储、`Token` 空时先 `Validate` 补 sekey,一次请求批量转存全部 fs_id(目录对象网盘侧整棵递归),返回 `extra.list` 的 `to_fs_id`;非百度目标直接拒绝(端点 400 → Java 侧回退字节中转 copy)。`/api/fs/share/save` 契约与 `/share/transfer` 语义(目标目录须已存在、`ondup=newcopy`、`sekey` 为解码后的 randsk)与取链路径同源。Java 侧 `serverSavable` 白名单加 `dstType == 10`(`MediaSubscriptionTransferService`),百度分享→百度账号从此走网盘侧秒转,不再字节中转;跨盘路由不变(`transferDriveName` 无 `baidu`,PowerList 也无 `baidu_to_*` 秒传配置,百度跨盘仅靠订阅「跨网盘转存」开关走 copy)。单测:Go `TestBaiduShare2SaveTo` 三条(批量参数拼装/errno 上抛/非百度目标拒绝)+ Java `baiduEpisodesSavedServerSide`;全量 919 绿。**部署提醒:需重建 PowerList 镜像(`mylocalbuild.sh`)才生效。**

> **🆕 站点搜索四源公共支撑收口 + 偏好三级继承文档修正(2026-08-25,全分支评审遗留两项)**:①评审轮「站搜抽基类另行安排」落地——盘链/观影/蜗牛三源的成片复制(Config 凭证判定 `hasCredentials/canLogin`、登录失败冷却骨架 `loginFailed`+冷却字段、提取码折叠、host 归一化、Cookie 拼接、盘型数字判定、Setting 读取)收口为 `service.sitesearch` 包级四件:`SiteCredentials`(接口,各源 record Config 实现,凭证判定收默认方法)、`LoginCooldown`(登录失败进入冷却+告警一处)、`Resp`(HTTP 原语顶层化,服务覆写 `http()` 打桩口径不变)、`SiteSearchSupport`(静态工具)。**取组合不取继承基类**:四源登录舞步/反爬机制各异(PanLian multipart、GuanYing PoW+多镜像、Woniu 打码续期),抽基类只会 Refused Bequest。行为保持:盘链按盘折参数(百度/迅雷/123→`pwd=`、115→`password=`)与密码 URL 预编码原样、玩偶锚点(`#`)不折保留;顺带把玩偶防重折守卫从 `pwd=/password=` 补齐到 `passcode=`(与其余源同口径)。既有四测试类 41 条零改动全过(匿名子类经包作用域直接解析顶层 `Resp`),全量 916 绿。②§4.7「偏好三级继承:订阅 `filter_config` > 用户默认(`user_preference`) > 系统默认」经核实系死代码——`user_preference` 表与 GET/POST `/preference` 仅存储(preferenceRepository 全局仅此两处,订阅创建/筛选解析零消费),前端零调用、页头偏好 UI 未做,「预设档位单选」同样未实现;按评审结论**改文档不改代码**(删接口即 API break):§4.7/§5/§8/§9 与 guide 对应表述全部改为「现状:订阅 `filter_config` 留空直达系统默认,三级继承与档位未实现、`/preference` 仅存储」,并录入「留待」清单。

> **🆕 完结订阅播放失败自愈:播放失败标记越过 ENDED 轻查短路(2026-08-25)**:完结≠看完——ENDED 判定只看剧集完结,与观看进度无关,而 `check()` 对 ENDED 订阅短路(轻查只刷元数据比对集数,不列源不搜索,手动「检查」同样被短路),能发现资源失效的完整巡检(`doCheck` 里的失效确认/换源/补搜)对 ENDED 永不执行;详情页 `resyncPrimaryInventory` 又「只列不判」(失败静默跳过)。结果:完结剧没看完、分享失效后系统零感知,播放列表/挂载/集源行全显示正常,用户每次点开同一集都撞死源,且 `playEpisode` 失败触发的 `checkAsync` 也被短路空转。修:播放期是信噪比最高的失效信号——`playEpisode` 全候选失败(attempted>0)时 `markPlaybackFailure`(内存 Set)先于 `checkAsync` 打标;`check()` 的 ENDED 分支据此越过轻查短路,回 ACTIVE(与加更/换季残留/异剧三条重开路口径一致)+清 stallCount+RESUMED 事件「播放失败,重开完整巡检检查资源」,走完整 doCheck(失效退役→池内换源→池空补搜);巡检尾部 `shouldAutoEnd` 在资源恢复正常后重新完结,状态自动归位。标记消费即摘除(check 开头 remove,单次生效),`forget`/`onDeleted` 同步清理;对 ACTIVE 订阅无额外作用(下轮巡检本来就跑,标记被无害消费)。单测两条(无标记维持轻查短路不列目录+次日复查/标记命中回 ACTIVE 跑完整巡检+RESUMED 事件)。
> **🆕 同日追加:仍在追看的完结剧每日完整巡检**:播放失败是被动的(用户撞了死源才触发),主动侧补「仍在追看」判定 `watchingRecently`——近 7 天(`RECENT_PLAY_WINDOW_MS`)有播放记录(`History.updatedAt`,缺失回落 `createTime`)且未看完(观看进度 < 本地可用集数,进度复用 `watchedEpisode` 的 msubep 逻辑链接解析)的 ENDED 订阅,轻查短路放行,**保持 ENDED 状态直接跑完整 doCheck**(与播放失败路不同:不翻转 ACTIVE——每日跑若翻 ACTIVE 会被 shouldAutoEnd 反复重新完结,事件刷屏;`!ENDED` 守卫天然防重)。看完或越窗(7 天没再看)回落每日轻查,不为闲置完结剧花巡检开销。单测两条(3/12 集近播→完整巡检列目录/看完 12/12 或 30 天未播→维持短路),全量 914 绿。

> **🆕 多季并行体验优化:挂载目录季后缀 + 换季确认弹窗(2026-08-25)**:同时追多季的两障碍——①挂载目录名不含季号(`buildMountPath` = 剧名 slug + 元数据 id 标签),同一 TMDB tv id 跨季共用,并行订阅 S1/S2(含「多季联动」next-season 入口)生成同一路径互相覆盖挂载;②编辑改季是整体重置(清旧季挂载/集源/进度,按新季重搜)但前端无提示,用户无感知丢进度。修:`buildMountPath` 第 2 季起追加 ` Sxx` 后缀(首季/未标注不加,保持既有形态;仅创建时定名,存量订阅路径不动);前端 `save()` 编辑态季号有变先 `ElMessageBox.confirm` 告知后果(originalSeason 在 handleEdit/handleAdd 维护),取消不落库。纯增量,全量 910 绿,web-ui build 过。

> **🆕 删除与巡检竞态:取消标记 + 阶段中止 + 残留回收(2026-08-25,线上 #40)**:订阅创建即触发首轮巡检(controller `create` 直接 `checkAsync`,五路搜索+挂载+列目录可达数分钟),期间删除订阅,`doCheck` 从取实体到尾部 save 全程无感知删除——继续搜索、把已删剧的挂载重新建回 AList(常驻非 temp 享清理豁免,永久孤儿)、资源行/集源行成孤儿,尾部 `subscriptionRepository.save` 对已删行抛 `Row was already updated or deleted by another transaction`(用户贴的 WARN);更险的是实体**无 @Version**,detached merge 在行已删时会把整行 **INSERT 复活**,此后每轮 sweep 继续搜索挂载(「删了还在跑」的第二机制)。三层修复:①**取消标记先行**:`delete()` 在 `getOwned` 后第一时间 `checkService.onDeleted(id)`(内存 Set 打标+清巡检内存态),早于任何卸载/删行;巡检感知删除不再依赖 DB 查询(`existsById` 方案否决:Mockito 默认返回 false 会击穿全部现存 doCheck 测试,且主键查询也无必要)。②**doCheck 八道阶段中止检查点**(`stopIfDeleted`:开头/元数据刷新后/列目录换源块后/集源同步后/流探测前/缺集补搜前/采样前/尾部挂载三阶段间),命中即 `cleanupDeleted` 收工;`check()` 尾部与 ENDED 分支的 save 走 `saveUnlessDeleted` 门禁(防复活),catch 里行已不存在(failed==null)识别为删除竞态——降为 info 并顺手回收,不再按 ERROR 误标;同类门禁覆盖 activateAsync(挂载后 save + catch 不走 `orElse(subscription)` 退役路径——retireResource 会把资源行 INSERT 复活成孤儿)、refreshMetadataAsync/checkUpdateAsync/refreshAiringDue(网络往返窗口)、ensureDriveLinesAsync(挂载循环后)、preheatAheadAsync(探测后)。③**残留回收** `cleanupDeleted`:清本轮巡检在 delete 清库后写入的资源行(逐个卸 share)/集源行/episode 行/事件行 + forget,幂等;标记不摘除(id 不复用,防御 cleanup 与 delete() 并发期间其它任务漏拦)。取消信号是内存态:单实例部署完备,多实例不覆盖(嵌入式应用现状单实例)。单测三条(`stopIfDeletedCleansOrphanResourcesAndMounts` 卸挂/四表清理、`stopIfDeletedKeepsSilentForLiveSubscription` 存活静默、`checkAbortsWhenSubscriptionDeletedMidFlight` 巡检中止不 save 不挂载不列目录),全量 910 绿。

> **🆕 片单追更改对话框补充(2026-08-25)**:web 管理页「片单追更」此前点「追更」即按默认值直接创建(季=1/挂载模式/无盘偏好),用户没机会补充季/网盘/过滤等信息。改为点击打开**新建订阅对话框预填**:`navSubscribe` 先 `handleAdd()` 重置表单再填剧名/搜索词/季兜底 1(后端 `create()` 仍按名称 `resolveSeason` 改写);TMDB 条目(`tmdb:tv:{id}`)直接绑定 `metaProvider/metaId` 并置 TMDB tab;豆瓣条目按标题**预搜 suggest**(对话框内豆瓣 tab 展示结果),名称严格相等+年份一致的严格匹配项自动选中(同时填 `doubanId` 与 `metaProvider/metaId`,后端两条绑定口径等价),匹配不到时结果仍列出供手动改选/换源,留空提交退回纯标题订阅;原「缺 TMDB 标识即中止」改为不绑定继续走标题订阅。创建成功(`save()` 且 `!form.id`)后片单条目标记「已追更」;`watch(formVisible)` 关闭即解除关联(取消/改手动新建不误标);`selectMeta` 取消选中豆瓣条目时同步清 `doubanId`(预填/链接解析的绑定两处字段并落,防"看着解绑了实际还绑着")。按钮去掉一键创建的 loading/disabled 态。纯前端改动,web-ui build(vue-tsc)过。

> **🆕 换季重置 + 季号门禁四层(2026-08-24,线上末日地堡 S1→S3 触发;同日二轮补「检查」自愈)**:用户把《末日地堡》订阅从第 1 季改成第 3 季后点「检查」,候选池仍全是第一季资源、主源还是「2季全」合集——根因是改季只 `setSeason`,存量世界零清理,四层修复:①**编辑改季全量重置**(`update` 检测 season 值变化):委托 `checkService.resetInventoryForSeason`(事务内纯 DB:删全部资源行/集源行/episode 日历行,重置 `shareId/coverUrl/metaSyncTime/currentEpisodes/maxEpisode/stallCount/caughtUpEpisode`,ENDED 回 ACTIVE,forget 内存态+事件),afterCommit 异步卸载全部挂载 share(行锁不横跨远程 HTTP,与 delete 同规)+ 立即 `checkAsync` 按新季重搜重挂;旧季的集源行不清会继续冒领集号,`computeMissing` 判「已齐」永不搜索新季,这是「点检查没反应」的最深一层。②**`belongsToShow` 加季号门禁**:标题明标其它季(第N季/Sxx/Season N,`parseTitleSeason` 单值)对本订阅就是「异剧」——主源复核/补缺挂载复核/换源路径自然淘汰(同剧不同季资源名字剥季缀后匹配、集号也不超官方总集数,标题/年份/集号三门禁全放行,只有季号拦得住);裸标题/跨季区间(第1-2季)无从判定放行,交内容层。③**巡检开头 purge 存量池**:每轮 `doCheck` 先清标题明标其它季的资源行(有挂载先卸 share、行删除不拉黑 link——资源没死,别的订阅追其它季照常可用),兜住一切路径漏进来的旧季行(入池过滤只挡新搜索结果);主源被清则 `shareId` 置空走 `ensureSource` 重挂。④**空壳主源换源**:主源挂载列不出**任何**本季可识别文件(换季残留合集常态:「第一/二季」目录被 `otherSeasonDir` 拒入、S01Eyy 被 `parseEpisode(season)` 拒收)列目录不报错、失效探测也正常,唯一信号是文件集为空——并入误挂异剧同路换源;`activate` 的「无可识别剧集文件」异常改带 `FOREIGN_SHOW_MARK`(挂上即空的旧季活链接走异剧分流退役**不拉黑**,按瞬时故障累积会烧成跨订阅黑名单)。**二轮(播放实锤后):⑤改季残留检测 `staleSeasonInventory`**:用户实际播放验证发现①只救「之后再编辑」的路径——已改季的存量订阅(改季发生在重置功能上线前)再点「检查」仍播 S01E01:逻辑线路顶着 S3 分集标题、`msubep-39-1` 解析到「2季全」合集里的 S01 文件,机制是**集源行挂旧季 episode 行(season 列)而可用性聚合(`findNumbersBySubscription...`)不按季过滤**;`doCheck` 开头新增检测(LIVE 集源行挂 season≠订阅季 且 season>0 的 episode 行即命中,特别篇 season=0 不算),命中就地卸载全部挂载+`resetInventoryForSeason` 全量重置,随后 shareId=null 自然落 `ensureSource` 按本季重搜重挂——**存量订阅点一次「检查」即彻底恢复,无需删订重订或切季触发**。**三轮(实测仍无效):⑥ENDED 短路挡住全部自愈**:该订阅 S1 追完 10 集 + 元数据刷成 S3(total=10)后停在 ENDED,而 `check()` 对 ENDED 订阅在 `reopenEnded` 失败后**提前 return,doCheck 从未执行**(日志只有 RatingBridge 元数据刷新、无任何巡检动作)——`shouldReopen` 被冒领的 currentEpisodes=10 堵死(本地=官方永不满足"官方>本地"),主源复核对「2季全」裸标题也放行;ENDED 分支补 `staleSeasonReopen`(残留检测命中即回 ACTIVE 走完整巡检,doCheck 开头同一检测完成重置+重搜),点「检查」对 ENDED 的换季残留订阅同样自愈。单测:`belongsToShowSeasonGateForms`(门禁表+双向+关态)/`purgeForeignSeasonResourcesRemovesStaleSeasonRows`(删行/卸挂/事件/主源引用断开)+`purgeForeignSeasonResourcesSkipsWhenSeasonUnknown`/`activateEmptyFilesRejectedAsForeignShow`(分流不拉黑)/`staleSeasonInventoryForms`(本季行/旧季行/特别篇/无行/无季五态)/`staleSeasonReopenForms`(命中回 ACTIVE/无残留维持 ENDED)/`resetInventoryForSeasonClearsWorld`(三表清理+字段重置+ENDED 回 ACTIVE+事件)/FollowTest `seasonChangeResetsPoolAndTriggersRescan`(委托+卸载+重搜端到端)+`sameSeasonUpdateSkipsReset`,全量 **907 绿**。

> **🆕 评审修复轮:SSRF 白名单/follow 幂等/转存配额/全链唯一哈希(2026-08-24,五条评审意见)**:①**P1 元数据链接解析 SSRF**:`resolveMetaLink` 的链接来自 USER 输入,平台正则只做子串匹配不锚定 host——`http://127.0.0.1/b23.tv` 会让 `expandShortLink` 先代发请求、`https://内网地址/#youku.com/show/id_x` 会让 `fetchPageTitle` 打到任意 host(认证后可探测内网服务)。新增白名单 `META_LINK_DOMAINS`(b23.tv/bilibili.com/youku.com/iqiyi.com 及其子域)+ **强制 https**:`isShortLink` 短链精确 host 判定(替换 `contains("b23.tv")`),`expandShortLink` 每一跳重过白名单(跳向其它域不再请求),`fetchPageTitle` 入口校验不过直接 BadRequest;豆瓣/TMDB/Bangumi/腾讯链路只提取 id 不请求用户 URL,行为不变。②**P2 follow 幂等**:`handleAction` follow 对同名订阅 `create` 复用既有行后,资源插入不查重——重试/另一入口重复 follow 必插重复行,`save` 落地时撞 `(subscription_id, link)` 唯一索引把整个事务打挂;先 `findBySubscriptionIdAndLink` 查重,已存在视为 no-op(不再 save/activate)。③**P2 转存配额逐目标复查**:`quotaLeft` 只在多目标循环前查一次,单订阅双盘都缺集时各起一个任务、各计一次 `todayCount`,吃穿 `maxTransfersPerDay`(剩 1 名额 → 2 任务);循环头复查,耗尽即 break(已成功目标不受影响,不误降级)。④**P2 MySQL 全链唯一**:V20 的 `uk_msub_resource` 在 MySQL 是 link 前 760 字符前缀唯一(InnoDB 3072 字节键长妥协)——两条前缀相同的长分享链被误判重复拒绝,而入池前 `findBySubscriptionIdAndLink` 预查两行都找不到、拦不住碰撞。**V34** 加 `link_hash` 列(小写 hex SHA-256/UTF-8):实体 `@PrePersist/@PreUpdate` 回调统一计算(两条入池路径 TVBox follow/巡检 fillPool 零改动)、迁移内回填存量行(同算法独立实现,不引实体类)、唯一索引换 `(subscription_id, link_hash)`(三库统一,260 字节远低于 3072 上限,不再需要前缀);已注册 SPI 与 NativeFlywayMigrationConfig。⑤**P3 编辑保留已绑源**:`handleEdit` 先赋 `row.metaProvider` 又立即重置 `'tmdb'`(handleAdd 的防串注释被复制粘贴残留),后续元数据搜索走错源、易把豆瓣/Bangumi 订阅误绑成 TMDB;删重置行,仅新建对话框强制 TMDB。单测:`MediaSubscriptionResolveLinkTest` 六条(白名单判定表+端到端被拒链路全离线)/`MediaSubscriptionFollowTest` 三条/`MediaSubscriptionTransferQuotaTest` 两条(名额 1+双盘 → 恰 1 任务)/`MediaSubscriptionResourceTest` 两条(回调计算+同前缀不同哈希)/`MsubSchemaContractTest` 钉 `link_hash` 列+(subscription_id, link_hash) 唯一+「先迁到 V33 插存量行再放行 V34」回填链,全量 **895 绿**,web-ui build(vue-tsc)过。
> **🆕 详情页背景图高清 + 多图轮播(2026-08-24,参考 atv-player;尺寸迭代 w780→original→w1280、固定高改 16:9 自适应)**:web 端「媒体详情」头部横幅此前只有单张 TMDB `w780`(糊)且静态。①**多图**:TMDB 详情请求加 `append_to_response=images` 零额外请求带回 `images.backdrops`;新增 `MetadataDetails.backdrops`(随 media_metadata payload 持久化,无需迁移):官方主图恒置顶,其余按 atv-player 同款打分(`vote_average×1000 + min(vote_count,1000)×2 + min(width,3840)/20 − |ratio−16/9|×140`)排序去重取 8 张;豆瓣桥接沿用(豆瓣无多图,backdrops 来自 TMDB 回填)。②**尺寸口径(两轮迭代后定为 `w1280`)**:初版 `w780`→`original` 后用户实测加载慢——original 常见 1920/3840 宽动辄数 MB,还走 web 端 `/images` 代理转发,首屏拖沓;`w1280`(预生成最大尺寸,1280×16:9,约两三百 KB)对详情抽屉(~58% 视口宽 ≈1100px)已超采样,清晰度肉眼无损,收敛 `BACKDROP_BASE=w1280`,存量改写 `upgradeBackdropUrl` 同时把 w780 与已落库的 original 统一改写 w1280(纯 URL 改写零网络免刷新),`backdrops` 输出 = 候选 ∪ 主图去重代理化(存量单图订阅也得单元素列表,前端零分支);新候选要等下次刷新元数据/在播 6h TTL 重拉落库。③**前端轮播**(`MediaSubscriptionsView.vue`):候选层叠绝对定位 + opacity 0.9s 淡入淡出,4.5s 定时取模切换(atv-player 同节奏),>1 张才启动定时器,后续帧 `new Image()` 预加载防切图闪白,drawer 关闭/组件卸载清理;容器高度由固定 350px(用户上调 400px 仍裁图)改 `aspect-ratio: 16/9` 按宽自适应,TMDB 背景图 16:9 完整展示不裁。单测:`TmdbMetadataProviderBackdropTest` 六条(端到端 w1280/主图置顶投票降序/16:9 惩罚垫底/上限 8/无 images 回落主图/空载荷)+ `MediaSubscriptionFastDetailTest` 三条(存量 w780 收敛+候选合并去重代理/单图兜底数组/URL 改写只动 TMDB 段含 original 降档),全量 **880 绿**,web-ui build(vue-tsc)过。

> **🆕 角标改「追平门槛」口径 + 计数去重(2026-08-24,线上诛仙 第四季 msub:32 触发)**:用户在看第 1 集、盘上已有 3 集,旧口径「取链验证过且未看的集数」显示 🆕1(第 2 集,第 3 集未验证不计)——数字既不是 0(用户觉得没有新集)也不是 2(实际未看 2 集),三种直觉都对不上。①**口径重定义**:角标只对**追平过**的订阅显示——观看进度追上资源侧最新集(「看到最新播出集」)那一刻,把当时最新集号登记为追平标记(新列 `media_subscription.caught_up_episode`,null=从未追平,V33 迁移,读路径惰性维护、只升不降、定向 update 防全实体 save 与巡检互踩);此后**新播出且未看**的集计 🆕N,播出口径改 LISTED/VERIFIED 均算(与详情列表能点到的集一致,不再等 preheat 验证——旧口径数字会随验证进度跳变:看完第 2 集角标先消失、第 3 集验证后又出现)。落后补看途中不亮灯(与通知「差十集的人不为最新一集响铃」同哲学);没开始看(watched=0)依旧无角标;Setting `msub_tvbox_badge=false` 关闭不变。②**计数去重(真 bug)**:`findNumbersBySubscriptionAndStatesIn` 按 集×资源 行粒度返回,旧代码直接 `.count()` ——同一集在多个盘 VERIFIED 会重复计数(诛仙第 1 集当时就在两个资源上 VERIFIED),现按集号 `distinct()`。同口径常量收敛:本文件内四处拼写的 LIVE 集合统一为 `LIVE_EPISODE_STATES`。数据实证(拷库只读查证):watched=1、ep2 VERIFIED×1、ep3 四盘全 LISTED → 旧口径 🆕1 的构成。单测 `MediaSubscriptionRemarksTest` 角标段按新语义重写 9 条(追平后新播出才亮/落后途中不亮不登记/追平登记与幂等/标记只升不降/多资源同集去重/LISTED 计入/再追平消除/未开始不亮/开关),`MsubSchemaContractTest` 列清单钉 `caught_up_episode`,全量 **870 绿**。存量库升级:V33 幂等加列,旧行 null=从未追平,不回填(追平是播放行为,无法从存量推断,用户下次看到最新集自然登记)。

> **元数据三修复:Bangumi 章节端点/B站独播时刻与外链/分集标题桥接(2026-08-24,线上盗妖行 tmdb 315088 触发)**:①**Bangumi provider 章节拉取线上一直是坏的(严重)**:代码用 `/v0/subjects/{id}/episodes`,该路径 **404**(实测返回 Not Found;v0 正确端点是 `/v0/episodes?subject_id=&limit=100&offset=`),异常落外层 catch → Bangumi 详情的分集/总集数/日程/状态全空且误计熔断失败(连续 3 次开 60s 熔断,Bangumi 源在线上等效不可用);修正端点 + 字段适配(v0 章节是 `airdate` 非 `air_date`、**无 status 字段**、响应裹 `{"data":[...]}`、`name_cn` 常空串回落 `name`——盗妖行 608049 的中文标题在 name),已播/日程口径改为与 TMDB `applySeasonEpisodes` 同款的**播出时刻判定**(airdate 当日 20:00,抽出可测的 `applyEpisodes(details, episodes, now)`;当日待播参与 nextAir、昨日/今日已播进 upcoming、无 airdate 章节只进分集列表不进统计),分页拉全(每页 100 上限 5 页);`fetchEpisodePages` 静态抽出供标题桥共用。②**B站独播时刻/外链此前只挂豆瓣链路**:`BilibiliScheduleRefiner` 只在 DoubanMetadataProvider 接入,TMDB/Bangumi 订阅只走 `PlayScheduleBridge`(只认爱优腾)——B站独播国创(盗妖行 周二/四 **9:00** 更新)时刻停在默认 20:00、links 无 B站官方链接;refiner 补挂 Tmdb/Bangumi 两 provider(ratingBridge 之后、平台桥之前,命中让位与豆瓣链同规),且**定位到 ss 号即把 `bilibili` id 登记进 externalIds**(时刻取不到也登记),`appendMetaLink` 展开 `https://www.bilibili.com/bangumi/play/ss{xx}/`(盗妖行=ss148433)。③**新增 `BangumiEpisodeBridge`(分集标题桥)**:TMDB 中文分集标题常是「第 N 集」占位或滞后缺失(盗妖行 60 集日程全、标题全占位,用户侧只见到 41 集前有标题);挂在 TmdbMetadataProvider 的 ratingBridge 之后(externalIds 已带 bangumi id;豆瓣订阅的分集来自 TMDB 6h 缓存深拷贝,自动受益),按剧名桥接门禁(整词同名+年份)拿到的 subject 拉章节:**占位/空标题回填**(`第\s*N\s*[集话話]` 正则,非占位不覆盖)、**集号超源列表上界的分集整行补齐**(TMDB 滞后未建行而 Bangumi 已排播到收官,20:00 约定落位,total/aired/upcoming 延展,nextAir 只在源侧为空时给出),补入行落在时刻校正之前同享 B站/平台 HH:mm 校正;bangumi 自源跳过、失败负缓存 6h。**数据源事实(实证)**:Bangumi 分集标题随播随编(今日只编到 55,B站官方季 API 领先一集给未上线集 long_title,TMDB 全占位)——42-55 现在即有标题,56-60 待播出前后陆续补上,快照按在播 6h TTL 自然跟进。全链端到端实测(盗妖行):nextAirTime 8/25 20:00→**8/25 09:00**、ep1 标题「来世,你可以找我报仇」、externalIds={tmdb,douban 37464007,bangumi 608049,bilibili ss148433}。存量生效:在播订阅 6h TTL 自动重拉,详情页「刷新元数据」立即。构造加参:Tmdb(+2)/Bangumi(+1),测试构造点 3 处同步;新增 `BangumiEpisodeBridgeTest` 五条 + Bangumi 日程测试重写(v0 端点/分页/时刻口径/无日期章节)+ `DaoyaoxingChainDiagTest`(@Disabled 真实网络端到端诊断),全量 **865 绿**。

> **合并前全分支 review 修复轮(2026-08-24,六线并行深度审查:巡检引擎/订阅服务+实体+迁移/元数据 provider/站搜/前端/周边回归)**:修复严重 2 + 中 20 + 低/建议一批,并落实 **MySQL 部署支持**。①**探测失败分级统一(严重)**:fillGaps/主盘/分盘线路三路 `probeShare` 调用方的 catch 是漂移的复制品,只保护 TRANSIENT——限流(THROTTLED)直接落入 `retireResource`+`markDeadLink`(好源烧成 90 天黑名单),异剧标记消息被再次退役+拉黑(击穿"异剧退役不进黑名单"不变量);抽 `probeCandidateSafely` 统一入口(异剧→就地退役不拉黑/限流→throttleDrive 退避+本轮跳盘/瞬时→streak/其余→退役拉黑),手动换源 `activateAsync` 同步补限流分支;`retireAlienCandidate` 清 transientStreak(防 21 天后 streak 达标仍被拉黑的慢路径)。②**存量毒行误判(严重)**:`belongsToShow` 复核用 DB 旧行,噪声剔除上线前落库的 26+142 毒行把主体正确的主源判成异剧,且"暂无候选"分支 return 在 syncInventory 之前、毒行永不清洗→每轮强制全量搜索+错误事件推送死循环;加三参重载(doCheck/refreshAuxMounts 传本轮清洗后的文件集复核),无候选路径先 syncInventory 洗行再 return,refreshAuxMounts 改为先列目录后复核。③**MySQL 支持**:V20 `uk_msub_resource(subscription_id, link VARCHAR(1024))` 与 V30 dead_link 内联 UNIQUE 在 utf8mb4 下 4096 字节超 InnoDB 3072 键长上限、CREATE/ALTER 直接 ERROR 1071(实测复现)——createIndexIfMissing 加 MySQL 末列前缀索引(760 字符,其余库忽略),dead_link 内联约束拆出独立建;新增环境变量门控的 `MediaSubscriptionMigrationMysqlTest`(MSUB_MYSQL_URL,真实 MySQL 8 全链 V20→V32+幂等+前缀索引+唯一性四断言,**实测通过**);checksum 兼容性(4567 实例实测):Boot fat-jar 形态下 Flyway 对 Java 迁移不落 checksum(flyway_schema_history 里 V2-V32 的 Java 行 checksum 全为 null,SQL 行才有),validate 对 null 不比对 → 就地修改 V20/V30 类不会引发 checksum mismatch,存量库直接换新 jar 启动即可;若其它部署形态启动报 "Migration checksum mismatch",用 MSUB_REPAIR_URL 门控的 `FlywayRepairH2Test`(停应用+备份后一条 mvn 命令,repair 仅对齐 flyway_schema_history 的 checksum、不重跑迁移不动业务数据)修复。④**实体/迁移与事务**:meta_id(VARCHAR 64)装 official 源剧名未截断(链接直订回落 bindByTitle 直接 500)→ abbreviate 64 四处;转存任务名拼超 Task.name(255)被 catch 记"转存全部失败"连锁静默降级 FOLLOW → abbreviate 200;delete() 去 @Transactional(远程卸载 N 次 HTTP 不再坐在事务里持行锁);importSubscriptions 的 checkAsync 移到 afterCommit(异步线程新事务读不到未提交行,首轮巡检此前静默丢失);rel_path/cover_url/link/dead_link.link 四处外部字符串补截断;`forget()` 清理订阅删除后的内存 Map;applyMetadataSnapshot 对 officialEpisodes/officialTotal 做 null 保留(防 provider 降级洗掉已知值连带门禁全关);Basic Auth 打 msub/playback 接口的 ClassCastException 500(instanceof 防御+principal 回落)。⑤**元数据链**:Tmdb `get()` 吞异常→熔断器永不打开且 search 把网络故障记成功(404 单独放行,其余上抛;details 熔断短路+空壳不缓存);TMDB/Bangumi/Official search 失败上抛(searchReport 的 errors 映射从死功能变活,豆瓣保留本地表兜底);Official "没搜到"不再计熔断失败(常态连续 3 次误开 60s 熔断);豆瓣搜索补 {movie,tv,episode} 白名单(与 RatingBridge 同规,book/music 干扰项不再进对话框)+本地表 year 空值守卫(防清空 rexxar 年份旁路评分桥门禁);RatingBridge 缓存键加 season(S1/S2 门禁结论不再串用);`mergeTmdbDetails` 对 episodes/upcoming 元素级深拷贝(豆瓣链尾 applyScheduleClock 原地改写共享引用污染 TMDB 6h 缓存);TMDB 缺 air_date 分集照进 episodes 详情(total 与分集行数对齐);Bangumi nextAirTime 纳入当日待播集(原严格未来日期,当日 20:00 播的集不触发 RETURNING/播出前休眠);搜索结果未开分不再显示 0.0。⑥**平台排播桥**:优酷 `LocalTime.parse` 异常不再打穿整条 fetchClock(vendor 链可落下一家);YK showId 正则兼容小写 %3d;**B站 refiner 命中后平台桥让位**(B站独播番的爱优腾协力位滞后跟进,无条件覆盖会改错更权威的 B站时刻,refine 改返回 boolean)。⑦**周边**:TelegramController 热路径移除只服务死代码的 resolveUid 查询+死方法/死 import;`/tg-search/*/msub/*` permitAll 死规则随端点下线删除;MetadataHttp build 后自设 Simple factory 收回 10s/15s 超时(全局 customizer 60s 地板会静默覆盖 builder 声明值);"分享无法访问"经 PowerList 源码查证为迅雷驱动终态诊断(提取码错误/违规屏蔽),维持即删不改。⑧**站搜**:观影登录页 GET 的 Set-Cookie 并入(原先丢会话 Cookie 致账号密码登录可能永远失败);蜗牛 probeHosts 换 ConcurrentHashMap(裸线程竞态);盘链 go.php 改单跳读 Location(自动跟会把盘链会话 Cookie 带到目标网盘域);玩偶 matchKeyword 归一化空串不再放行(防白吃详情预算)。⑨**前端**:设置对话框加载失败不再打开空表单(保存会把 19 项配置含凭证覆写为空)+保存防连点+部分失败汇总;「追更」按钮防连点+后端 create 同名同季幂等(重复订阅两条 score=1000 候选抢主源);抽屉/片单请求加序号防旧响应覆盖;9 处延时刷新统一 onBeforeUnmount 清理;时间展示对齐北京时间(与后端分桶同口径);动态抽屉补 loading;导入校验数组;菜单权限三处取齐(USER 可见,与「播放」同款);PlaybackSyncController 同步修 CCE。单测新增 `probeThrottleRetiresNothingAndSkipsDrive`/`probeForeignRejectionRetiresWithoutBlacklistViaUnifiedEntry`/`belongsToShowPrefersObservedFilesOverStaleRows`+MySQL 迁移四条,Bangumi nextAirTime 用例同步新口径,全量 **852 绿**+web `npm run build`(vue-tsc)通过。未动:巨类拆分(CheckService 3960 行/站搜抽基类/前端拆组件)属结构重构另行安排;`MetadataService.isLegacySnapshot` 对"合法无评分"的误判保留(后果仅多余回写,正确判据需考证各 provider 扩展前形态)。

> **详情播放列表 DB 直装配(2026-08-24,修"TvBox 打开追剧详情要逐网盘线路列 AList 文件列表,很慢")**:旧路径 `contentDetail` 对主挂载做 depth-3 dfs 列目录,`mergeGapPlaylists` 再对每个转存目标 + 每个补缺挂载(上限 6)各做一次完整列举——最坏 ~8 个挂载点串行 HTTP(10s+),全部只为拼播放列表;而播放侧 `playEpisode` 早已集源行索引直查。本轮把 TVBox 请求(空 ac)的详情列表改为 `fastDetail` **纯 DB 装配**:①数据源 = 集源行(LISTED/VERIFIED)× MOUNTED 资源(`findNumberAndSource` 一次全行内存过滤,装配序 转存→主源→其余按分降序,同盘同集先到先得与旧路径语义一致)+ TRANSFER 模式各转存目标 `episodeFilesAt` 并入(自有盘、走 AList 列表缓存,失败只丢转存线路不拖垮快路径);②线路形态不变:「我的追剧」逻辑线路 msubep-{id}-{集}(集号并集,标题 `NN. 元数据分集标题(大小)`(`episodeTitles` 读 media_metadata 零网络,无标题兜底"第N集",大小取该集 LIVE 行最大 fileSize 做画质代理)+ 盘线路每盘一条,条目 `文件名(大小)$1@{pid}`——pid 经 `ProxyService.generateProxyUrl` 注册(纯 DB,7 天复用),消费端 `PlayController` 只读前两段,点击时才解析真链;`rewriteEpisodeTitles`/`buildTvBoxPlayLines`(线路排序主盘居前)/`kickDriveLines` 全复用;③集清单空(首轮巡检前/全源失效)或装配异常 → 回落 `legacyDetail`(旧 dfs 路径原样保留);非空 ac(TG 源/web)仍走 legacy。**权衡**:播出后~下轮巡检之间上屏的新集暂不可见(旧版实时列目录可见),由 12h 短轮 + 播后短轮 + 管理页「检查」兜底,换详情 10s+→毫秒级;`msub_episode_titles` 美化开关只影响 legacy 盘线路,逻辑线路默认已是元数据标题(重写幂等)。构造注入 ProxyService+SiteRepository 两参,测试构造点 2 处同步补 null。**pid 长效注册(次日补)**:盘线路 `1@{pid}` 依赖 PlayUrl 行存活,默认 7 天有效期 + 每小时 clean 会把行删掉——播放历史/跨端同步绑定的物理地址变成死链(手动切盘线路的续看 7 天后失效);`ProxyService` 新增 `generateProxyUrl(site, path, ttl)` 长效重载(原 7 天方法不动):行不存在新建、剩余寿命超 ttl 一半直接复用不写库、不足(含已过期)原地续满——快路径盘线路按 `DRIVE_LINE_PID_TTL`=365 天注册,每次打开详情自动续期,剧完结停止回放一年后由 clean 自然回收;legacy 路径产的 7 天行也会被快路径的同路径续期救活。单测 `MediaSubscriptionFastDetailTest` 十条(端到端三线路装配+FAILED/退役行滤除/元数据标题/转存并入+转存失败降级/同盘同集去重主源胜出/空清单与装配异常双重 fallback/非空 ac 跳快路径/无资源占位/标题格式)+ `ProxyServiceTest` 长效注册四条(新建满窗/充足复用零写库/剩半续满/过期原地续期不新建),全量 849 绿。**播放期字幕门禁(同日补,线上盗妖行日志)**:播放链路仅剩的一次目录列举来自 `getPlayUrl(getSub=true)` → `getSubtitle` 列文件所在目录找外挂字幕——网盘分享的外挂字幕几乎只出现在非华语资源(国产剧内嵌),`playEpisode` 的 getSub 改按 `wantsSubtitles` 门禁:元数据地区明确非中国(TMDB ISO 码/豆瓣中文形态,港台同判)才查,中国/港台/未绑元数据/无地区数据都不查,播放链路对国产剧彻底零目录列举(取链 fs/get 除外);初始化记录字幕文件方案不做(schema+巡检同步改动重,非华语剧保留运行时查找正是需要字幕的场景)。单测 `MediaSubscriptionSubtitleGateTest` 四条(国产跳过/欧美查找端到端捕获 getSub/无元数据与无地区跳过/中国地区形态),全量 856 绿。

> **元数据信号增强:标题宣称集数/单集时长/版本词三路门禁(2026-08-23 深夜,用户提议"集数、播出时间、单集长度、类型、演员等信息应该能帮助判断资源匹配度")**:前两轮集号门禁只在挂载探测后生效,本轮把元数据信号前移/补盲。①`titleProgressForeign`(标题宣称集数,TITLE_PROGRESS 各形态最大值 vs officialTotal,判据同集号门禁:已播完超出即拒/未播完容差+2)挂 fillPool+candidatesOrdered —— 真人版「全37集」包在**入池层**就拦,省一轮挂载试错;带季标记/「合集」词跳过(多季合一包宣称的是跨季总数,交探测层季过滤)。②`episodeDurationForeign`(单集时长,EpisodeFile 加 duration 字段透传 AList FsInfo.duration;夸克等驱动返回、百度返回 0)挂 probeShare+activate —— **时长是内容属性不受码率影响**(线上:真人版单集 2727s vs 动画版官方 20min),差异>50% 判异剧,补集号门禁的**未播完容差盲区**(真人版 1-28 集号合法但时长必不符);中位数抗单集加长,双侧齐备才判(元数据无 runtimeMinutes/文件 duration 覆盖不足半数或<3 个文件 → 跳过零误伤)。③`liveActionForeign`(版本词**单向**门禁):动画订阅(genres 含动画/动漫,正向证据)拒标题显式「真人版/真人连续剧」的资源 —— 同名 IP 双形态且集数/时长信号全缺时的最后防线;反向不做(genres 缺失的豆瓣订阅会误伤动画资源)。元数据取值收敛 `metaDetails()`(metaYear 抽出共用,provider 缓存+media_metadata 持久层,完结剧零网络)。**不做的信号**:播出时间(年份门禁 ±1 已覆盖,标题不带完整播出日期)、演员(负向不可枚举——不知道对面剧的演员表;正向标题写本剧 cast 太罕见)、体积代理时长(受码率干扰,同剧不同盘可差 3 倍)。单测四条(`titleProgressForeignForms`/`episodeDurationForeignForms`/`liveActionForeignForms`/`probeShareRejectsLiveActionByDuration` 夸克 2727s×37 端到端),多季合集放行回归照绿,全量 833 绿(含并发会话 PlayScheduleBridge 6 条)。

> **平台排播时刻桥接(2026-08-23,修"师兄太稳健 12:00 更新却按 20:00 等";同日补腾讯路)**:TMDB/豆瓣/Bangumi 的播出日程只有日期,时刻统一按当日 20:00 硬编码,而各剧实际排播时刻不同(师兄太稳健双平台 12:00)——时间轴展示/已播判定/播出前休眠/播后短轮全部偏晚最多 8 小时。新增 `PlayScheduleBridge`(`service/metadata/`,与 `BilibiliScheduleRefiner` 互补:B站路按剧名搜番剧推众数,平台路走豆瓣条目播放源)**挂在三个 provider 的 `fetchDetails` 尾部(ratingBridge.enrich 之后)**:豆瓣 subject id 取自 `externalIds`(豆瓣源自带;TMDB/Bangumi 订阅经 RatingBridge 桥接后带上,故必须挂在评分桥之后)→ rexxar `vendors[]`「在哪儿看」(游客可用)逐平台取真实 HH:mm——**爱奇艺**:vendors url 是 www 域名播放页但 www 对无 JS 客户端只回空壳页,换 `m.iqiyi.com` 同路径即完整 SSR,分集条目 `type=1` 正片(3=预告/花絮)的 `issueTime` 取最近 8 集时刻众数(免费转免线更新时刻略早且数量少,被众数压掉;与 B站 refiner 同口径);**优酷**:vendors url 是豆瓣小程序 scheme(`showId` 以明文/URL 编码形态嵌 path),抠出后请求 `youku.com/show/id_{showId}.html` 302 落到播放页(游客直出 `__INITIAL_DATA__`),`pageMap.extra.videoPublishTime` 即排播时刻;**腾讯视频**(花开锦绣 36810153 实测):vendors url 小程序 scheme 抠 `cid` → pbaccess `GetPageData`(游客 POST,`page_id=vsite_episode_list`)分集列表 `module_params.sub_title` 更新文案(「会员周一至周三18点更新1集,周四至周日18点更新2集,SVIP抢先看1集,点映礼抢先看大结局」)抽 HH:mm —— 分集条目的 `publish_date` 已普遍不回填(花开锦绣 0/56、庆余年S2 0/34 实测,OfficialSite 依赖它的推算路同步失效)不可依赖,PC/m 播放页纯 SPA 对 curl 零数据,完结剧文案无时刻词(「会员看全集」)自然跳过;时刻只在文案字段内找不扫全文(duration 等数字形态会误命中)。校正复用 `BilibiliScheduleRefiner.applyScheduleClock`(static):只换时分、TMDB 日期不动,airedEpisodes/nextAirTime 按校正后时刻重数,自然流入订阅的 `nextAirTime`+`schedule` 快照与巡检调度。芒果等平台页面结构未验证暂不接。命中/未命中各 Caffeine 缓存 6h(负缓存防完播剧反复 refresh),失败静默保留 20:00,不依赖任何 provider 防构造环(与 RatingBridge 同规);构造加参三处(Tmdb 5 参/Douban 8 参/Bangumi 4 参),测试构造点 14 处同步传 null。存量快照:在播订阅按 airing TTL 自然重拉带出;完结订阅点详情页「刷新元数据」即得。单测 `PlayScheduleBridgeTest` 八条(爱奇艺 vendor 换 m 域+预告滤除+时刻/nextAirTime/upcoming 重数端到端/优酷小程序 scheme 抠 showId/**腾讯更新文案抽 18:00+完结剧无时刻跳过**/externalIds 无豆瓣 id 跳过/无日程跳过/失败静默+负缓存/纯预告不造时刻),全量 835 绿。**官方播放地址入 links(2026-08-24 增量,commit 398a4152 之后)**:同一份 rexxar vendors 顺带产出各平台播放页 URL 写入 `MetadataDetails.playLinks`(新字段,随 media_metadata payload JSON 持久化无需迁移)——爱奇艺剥豆瓣引流参数(vfm/fv)升级 https、优酷 showId 拼 show 页(浏览器 302 落播放页)、腾讯 cid 拼 cover 页;`MediaSubscriptionService.detail` 的 links 装配区 `putIfAbsent` 并入(标签=爱奇艺/优酷/腾讯视频,前端 `v-for links` 遍历自动渲染零改动)。**链接不依赖时刻命中**:完结剧(「会员看全集」无文案)、无日程纯豆瓣详情、平台时刻路失败,links 照常带出 —— refine 守卫从「无日程直接跳过」放宽为只查 doubanId,缓存值升级 `PlaySources(clock, playLinks)`(clock 可 null);时刻路失败不打断链接收集。存量完结订阅表永久命中,点「刷新元数据」即得。**咪咕视频(2026-08-24 再增量,悬案 36624136 实测)**:vendors url 本就是 https 播放页(m 站 detail 页)原样入 links;分集数据是低代码平台 XHR 异步拉(壳页零数据、网关未逆向)时刻路不接 —— 咪咕同播剧爱优腾路已覆盖;clock 分发改显式 case(原 default 兜腾讯会把咪咕的 cid= 误喂腾讯接口)。单测九条(增悬案例:咪咕直链+优酷抠 showId 双源,时刻从优酷取),全量 871 绿。

> **文件级噪声剔除:目录混入不相干文件撑爆集号(2026-08-23 深夜,线上 142 集)**:上一轮门禁上线前的探测残留 —— 正确的百度补缺资源(动画版 1-26)目录里被分享者塞了《都市仙医》`S01E142 - 第 142 集.mp4`,`parseEpisode` 从 S01E142 解析出 142(S01 与目标季一致,季过滤放行)→ 142 落集源行 present=true,详情分集列表被撑到 1-142(27-141 全空槽)。**更深的坑**:资源级门禁 `episodeNumbersForeign` 判"资源 max 集号",单个毒文件会把主体正确的资源整体误杀(且毒文件一直在目录里,重探永远进不来)。新增 `stripForeignEpisodeNoise`(文件级,挂在 listEpisodeFiles/activate/probeShare/refreshAuxMounts 四个落库路径的 collect 之后):超出官方总集数且**与范围断裂**的跳号(26→142)剔为噪声;从 total+1 **连续衔接**的超范围尾部保留(真人版 1-37 / TMDB 登记滞后的真集),交资源级门禁判真伪 —— 两形态互补:真人版整体是异剧(衔接链完整 → 门禁拒),毒文件是孤立噪声(断裂 → 剔除后资源干净通过)。官方总集数未知不剔(零误伤);剔空则走既有"无可识别剧集文件"失败路径。存量自愈:refreshAuxMounts 重列时 142 行进 leftover 落 MISSING → detail 的 base(sources 最大集号)回落 26,142 不再显示。单测:`stripForeignEpisodeNoiseForms`(断裂剔/衔接留/混合/官方未知)+ `probeShareKeepsCorrectResourceWithPoisonFile`(线上形态端到端:1-26+毒 142 → 资源不退役、142 不落行),全量 823 绿。

> **集号范围门禁:同名异剧(真人版顶动画版)(2026-08-23 晚,线上「仙剑奇侠传三」)**:动画版订阅(tmdb 233295,官方 26 集 26/26 已播完)被真人版资源「仙剑奇侠传三 2160P」(2009 年剧,恰好 37 集)顶成主源 —— 标题**无年份无类型词**,标题归属/年份门禁全部放行(年份门禁的"同名作整词命中放行"本意是鬼灭全系列包标首季年代,同名真人版/动画版在标题层面确实无法区分);后果链:maxEpisode=37 → `endedBySeasonAired` 误判 ENDED(37≥26)→ 补缺逻辑去找 27-37 集 → **更糟的是 ENDED 重开条件(官方>本地)被 37>26 反向堵死,订阅永不自愈**。唯一可靠信号是**探测出的集号范围**:新增 `episodeNumbersForeign`(官方总集数基准;`isSeasonAiredOut` 已播完超出即拒、未播完容差 +2 防 TMDB 登记滞后;官方未知/无集号放行零误伤)挂四处:①`probeShare` 探测点(就地退役 RETIRED 冷却重探 + 抛「疑似同名异剧」标记消息,fillGaps/主盘/线路/升级探测四路调用方零改动);②`activate` 换源挂载(抛错前先卸刚挂的分享,固定路径不残留异剧目录;`activateNextCandidate`/手动换源 catch 按 `isForeignShowRejection` 分流退役);③`belongsToShow` 归属复核(存量已挂主源/线路:巡检自动换源/卸载);④`reopenEnded`(ENDED 订阅主源异剧 → 重开 ACTIVE 走完整巡检,打通污染自愈)。**异剧退役不进跨订阅失效黑名单**(链接没死,真人版订阅可能正用着;官方集数修正后冷却期满重探自愈)。误伤面已由季过滤收缩(`otherSeasonDir` 目录级 + `parseEpisode` 文件级拒异季集号,多季合集形态进不来超范围集号)。局限:真人版集数 ≤ 动画版官方数(同名且集数更少)时标题/集号均无信号,只能用户手动换源。单测六条(`episodeRangeGateForms` 三态/probeShare 就地退役不拉黑/activate 抛错清挂载/换源链跳异剧挂次选/belongToShow 集号复核/reopenEnded 异剧重开+正常完结对照),全量 821 绿。

> **线上实测三修复(2026-08-23 晚,盗妖行刷新后仍只有 tmdb 分)**:①豆瓣 suggest 的 `type` 把剧集/番剧统一归 **movie 大类**(实测盗妖行 37464007、凡人修仙传各季条目全 `type=movie`,`episode` 字段才是集数),只认 `type=episode` 的过滤等于把几乎所有条目滤掉——改为**影视白名单 `{movie,tv,episode}`**(滤 book/music 同名原著/原声带;真电影条目误入由 rexxar tv 接口无此条目兜底丢弃);②Bangumi 条目 `name_cn` **常为空串**(盗妖行 608049 中文名空、`name` 才是「盗妖行」),`asText(default)` 节点存在但值为空不走 default,须显式回落 `name` 字段;③**未开分条目只并外链不造分**:盗妖行豆瓣 value=0/count=0(未开分)但条目真实存在,Rating.score 可为 null —— externalIds 照并(links 有豆瓣入口),ratings 不放 null 不造 0 分;Bangumi score=0 同理;rexxar 响应以 `title` 非空判条目存在(空=无此条目走负缓存)。三条全由线上数据实证并在 `RatingBridgeTest.unratedDoubanGivesLinkWithoutScoreAndBlankNameCnStillMatches` 回归(suggest type=movie+未开分+name_cn 空串+5.4 分端到端);`rexxarMissSkipsDoubanEntirely` 补无此条目不并外链;全量 815 绿。

> **订阅详情三源评分/外链桥接(2026-08-23,修"TMDB 搜到的订阅只有孤零零一个 TMDB 分"、"豆瓣订阅 links 只有豆瓣+TMDB 缺 Bangumi")**:跨源桥接此前只有「豆瓣→TMDB」单向,通过 TMDB 搜索添加的订阅 `metaProvider=tmdb` 单源到底——TMDB 对国产剧/国创动画投票覆盖差,详情页评分/外链只有 TMDB 一项;豆瓣订阅经名称桥接带出 TMDB 后 links 也只差 Bangumi 一角。新增 `RatingBridge`(`service/metadata/`)**挂在三个 provider 的 `fetchDetails` 尾部**(构造注入+null 容忍):按源剧名定位同剧条目后**只补 `ratings`/`externalIds`**(详情页多源评分 + 条目外链,`MediaSubscriptionService.detail`/`appendMetaLink` 现成透传,豆瓣/TMDB/Bangumi 三源链接齐),条目身份(名称/封面/日程/集数)仍以源 provider 为准。**源自身/已带该源外链时跳过对应搜索**:TMDB 订阅补豆瓣+Bangumi、豆瓣订阅只补 Bangumi、Bangumi 订阅只补豆瓣。两条评分路均**免 cookie 免 key**:①豆瓣 `movie.douban.com/j/subject_suggest`(游客可用)定位 subject id(**只认 `type=episode`** 条目——id 要喂 rexxar tv 接口)→ `m.douban.com/rexxar/api/v2/tv/{id}` 的 `rating.value`(rexxar 无 cookie 可用,与豆瓣 provider 同源事实;未开分 value=0 视为无分);②Bangumi `api.bgm.tv/v0/search/subjects` 搜索结果**自带 `rating.score`**(国创动画同样有分,filter type=2/6),无需二跳。匹配与豆瓣名称桥接同规:归一化整词同名(匹配集=源中文名/原名/别名+剔季缀基名,候选含 sub_title/剔季缀;同名异剧/子串模仿者拦)+ 年份门禁(±1,候选缺年份放行;多季合一 TMDB 条目 TMDB 年份是 S1 首播年,**season≥2 放行**);**原名搜不到再按剔季缀基名补搜一轮**(豆瓣「诛仙 第四季」→基名「诛仙」);命中与未命中各 Caffeine 缓存 6h(**负缓存**防完播剧反复 refresh 打爆外网),失败静默不炸详情主链。依赖注:`RatingBridge` 只依赖 `MetadataHttp` 直连接口、内联 Bangumi 搜索(不注入任何 provider——挂豆瓣/Bangumi 侧时若注入会与既有注入方向 Douban→Tmdb 成构造环);构造加参三处:`TmdbMetadataProvider`(4 参)、`DoubanMetadataProvider`(7 参)、`BangumiMetadataProvider`(3 参,挂点按单季过年份门禁——bangumi 无季概念),测试构造点(`DoubanMetadataProviderTest`×10/`TmdbMetadataProviderScheduleTest`/`BangumiMetadataProviderScheduleTest`/`MetaSearchDiagTest`)同步传 null。存量快照:在播订阅按 airing TTL 自然重拉带出;完结订阅表永久命中,点详情页「刷新元数据」即得。单测:`RatingBridgeTest` 八条(双源并入+身份字段不动/movie 干扰项与子串拦截/年份门禁拦同名异剧/多季放行/失败静默+缓存免重试/未开分跳过/**豆瓣订阅只补 Bangumi 且基名补搜**(线上诛仙第四季 subject 37472443 形态)/**Bangumi 订阅只补豆瓣**),全量 814 绿。

> **v2 重设计进行中(2026-08-22)**:本文记录 v1 已落地的设计,其中大部分决策(固定挂载路径、候选池、三级递进巡检、搜索源与元数据分层)在 v2 中保留。
> 诊断出的 8 个缺陷、新数据模型(`msub_episode`/`msub_episode_source`/`dead_link`)、选源算法与迁移计划见 **[media-subscription-redesign.md](./media-subscription-redesign.md)**。
>
> **实现状态(2026-08-20):P0-P3 全部落地**(个别标注"可选/留待"的细项见末尾说明)。
>
> - **P0**:V20 四表、订阅 CRUD、定时巡检(重列主源/失效换源/候选池/退避)、固定挂载路径换源、事件流、web 管理页(菜单"追剧")、ShareService 清理豁免、TVBox `t=msub` 列表与单源播放。
> - **P1**:V21 元数据列;`MetadataProvider` SPI(豆瓣/TMDB/Bangumi + official 兜底,`service/metadata/`);官方集数触发补搜、播出日程调度(播出+15min 起 3 短轮)、官方状态自动完结;缺集补搜(整季→单集降级,临时挂载探测)与**多源合并播放**(主源优先,补缺源挂 `{mount}-补N`,主源补齐自动退役);集数清单接口/页签;索引模板联动(首次挂载建"追剧"增量模板);一键订阅(TVBox 详情页"追更"操作组 `$msub$/$munsub$` + spider 拦截[TgSearch,已构建拷回 spring.jar]、web 搜索页追更按钮、播放记录追更按钮、"我的追更"首页分类);dry-run 预览(打分明细);更新收件箱(近 3 天);导出/导入。
> - **P2**:`AListService.mkdir/copy/awaitCopyTasks`;`MediaSubscriptionTransferService` 增量转存(按源目录分组提交、事后校验、日限额 `maxTransfersPerDay`、失败自动降级 FOLLOW、:40 自愈扫描);`TaskType.SUBSCRIPTION` + Task 行;转存模式 UI(账号下拉/手动转存/进度);健康面板统计卡;批量操作(全选/全不选/反选 + 检查/暂停/恢复/删除)。
> - **P3**:版本升级提醒(4K 完整候选探测,事件不自动替换);归档清理(`msub_archive_days`,完结 N 天释放转存文件);多季联动(`/next-season` 一键续订下一季);Telegram Bot 通知(`msub_telegram_bot_token/chat_id`,新集/错误/完结/转存完成);指标(今日更新/搜索成功率/来源存活率,`/stats`);ERROR 每日自动重试 + 连续 7 天失败提示;官方视频平台 provider(**参考 atv-player `src/atv_player/metadata/providers/` 的已验证实现**:腾讯 pbaccess trpc 搜索+GetPageData 官方分集列表 publish_date 推算已播/下集播出、优酷 `search.youku.com/api/search` 的"更新至N/M集"文案、爱奇艺 `mesh.if.iqiyi.com` updateTime;`msub_official_url_template` 仅作最后兜底)。豆瓣搜索升级 `movie.douban.com/j/subject_suggest` 在线接口(subject id 直接对接 rexxar 集数),本地 movie 表兜底。实测(2026-08-19):三平台接口与豆瓣 suggest 均可达且字段符合解析。
> - **媒体库单入口(2026-08-20)**:**`csp_Media`「我的追剧」内置源** —— 订阅配置 sites 自动出现,用户视角就是一个媒体库:封面/元数据来自订阅绑定的豆瓣/TMDB(`/media/{token}` 端点,分类 全部/追更中/已完结,按名称搜索,封面走绝对地址 `/images` 代理)。集 id 为**逻辑链接** `msubep-{subId}-{episode}`(分隔符用 `-`,冒号在部分播放器/代理链路会被当 URL scheme 截断;发布前定稿,无兼容包袱)(spider `Media.java` 已构建拷回 spring.jar),播放时 `PlayController` → `playEpisode` 实时选源(**已转存集优先走用户网盘 → 主源 → 补缺按评分序**)并**逐源回退**:某源取链接失败即登记损坏集换下一源,用户无感;FongMi 续看/播放同步绑定逻辑 id,换源不断播。`PlaybackSyncService`「网页播放」筛选纳入 `csp_Media`。
>
> **元数据 id 标记直读(2026-08-20)**:削刮/匹配链路直接从目录名解析 `metaIdTag` 标记——`TextUtils.parseMetaIdTag/stripMetaIdTags` 统一支持 `[dbid-x]`/`[tmdbid-x]`/`[bgmid-x]`(追剧转存目录,metaIdTag 生成)与 `{tmdbid-x}`(削刮命名)两种括号。`DoubanService.getByName` dbid 直读本地 movie 表精确命中(修转存目录 `剧名 [dbid-x]` 被当搜索词致 0 命中的问题),id 未命中也先剥标记防污染;`TmdbService.getByName` 同理(tmdbid 直读本地库,先 tv 后 movie),削刮 `handleIndexLine` 的 pattern 兼容方括号。bgmid 仅工具支持(Bangumi 无本地表,在线 provider 按需消费)。
>
> **片单追更入口(2026-08-20)**:追剧管理页新增「片单追更」对话框,复用 csp_PianDan 片单导航(豆瓣/TMDB 榜单):分类下拉 + 筛选下拉(与 TVBox filter 同源:地区/年代/排序/类型等,随分类切换)+ 海报网格 + 分页,一键创建订阅——TMDB 条目(`tmdb:tv|movie:{id}`)自动绑定元数据(官方集数/播出日程驱动追更),豆瓣条目按标题搜 suggest 自动绑定 subject id(名称+年份双匹配防同名误绑;匹配不到退回纯标题订阅,封面走 coverOf 元数据链路);同名已订阅剧标识禁用。管理端代理端点 `/api/media-subscriptions/navigation[/list]`(登录态鉴权,免 vod token——`/pian-dan` 空 token 在开启 token 校验的实例会被 `checkToken` 拒绝;ac 固定 web,豆瓣封面走 `/images` 代理)。
>
> **入口收敛(2026-08-20)**:TVBox 端追剧入口收敛到 csp_Media「我的追剧」(`/media/{token}`)——TG 源首页"我的追更"分类注入、TG 详情页"追更"操作组(`$msub$/$munsub$`)、旧 `t=msub` 列表 / `msub:{id}` 详情 / 操作组回调端点(`/tg-search/{token}/msub/{action}`)全部下线(TelegramController 内注释保留,可回滚);`appendFollowTrack`/`mediaSubscriptionDetail` 成为死代码一并保留。web 管理端(搜索页/播放记录"追更"按钮 `/api/msub/follow`、追剧管理页)与 `$msubep-` 播放链不受影响。spider 侧 `MediaSubscribeInterceptor`(CatVodTVSpider)因注入消失不再触发,无需改动。
>
> **自动追更优化轮(2026-08-20,六项)**:①播出短轮窗口 3h→12h(`shortPollWindowHours`,窗口内每小时一查;网盘资源常在播后 1~12h 才上线,原 3h 窗口刚过即退避 6h+,首集发现延迟可观);②追更中(官方 RETURNING)退避封顶 24h→12h(`returningBackoffCapHours`;重列主源零成本,不该隔天才发现;完结/无元数据维持 24h);③ENDED 自动重开(每日轻量元数据复查不列源不搜索,官方已播/手填期望超本地 → ACTIVE + `RESUMED` 事件;修「官方加更永不重开」盲区,web-ui 事件标签已配);④主源失效确认(列目录失败先静默重试一次,仍失败再探测 AList 健康:整体故障只本轮跳过、15min 后重试,不误标 BAD 污染候选池);⑤BAD 候选冷却重探(超 `badCooldownDays`=7 天重新参与候选序列,重探失败重新计时;修池仅 5 席被误标耗尽);⑥补搜节制(播出窗口内且缺口只含官方已播最新一集 = 资源大概率未上线:保持整季关键词不降级单集、隔轮限频;出窗恢复逐集降级)。gapSearchRounds 维持内存态(重启只多一次整季搜索,收益/成本比不足落库)。单测:`MediaSubscriptionCheckServiceTest` 覆盖调度/节制/重开/冷却/失效确认。
>
> **搜索结果匹配度增强(2026-08-20)**:`fillPool`/`preview` 增加标题归属校验层——此前只做网盘类型/排除词过滤与打分,标题是否真是本剧完全依赖搜索召回,同名剧/噪声资源占满候选池后靠挂载试错被动淘汰。现在:①**归属匹配**(`matchesTitle`):候选标题归一化(小写、剥技术标签、标点/防审查点号转空格、汉字间空格塌缩)后须包含剧名/搜索词/元数据别名任一;全部未命中时对中文名做编辑距离滑窗兜底(容差 max(1, len/4),救"漫氦的季节"这类单字防审查变形,不救两字以上差异的别剧)。别名快照落库:`media_subscription.aliases`(V27,换行分隔,`refreshMetadata` 每日刷新,TMDB/Bangumi provider 已产别名;纯标题订阅退化为剧名+关键词匹配,行为不变)。②**季标记硬过滤**(`parseTitleSeason`:中文"第N季"/Sxx/SxxEyy/Season N,跨季区间与多季号歧义返回 null 不判定):标题明确标注其它季的直接丢弃(追第 2 季不会被"第一季 全36集"占池)。③**软打分**:标题归属+15、季标记匹配+10、集数领先+8/落后-8(`parseTitleProgress` 解析"更新至N/更至N/全N集/第A-B集/第N集/EPn/SxxEyy"最大值对比本地集数)。事件流报告过滤数(`候选池新增 N 个资源(关键词,过滤 M 条不相关结果)`),dry-run 预览同规则可见。单测:匹配器/季解析/进度解析/fillPool 过滤集成/Migration V27 链路。
>
> **豆瓣 cookie 详情页 + IMDb→TMDB 桥接(2026-08-21)**:豆瓣 provider 补齐别名与播出日程两条短板。Setting `douban_cookie`(追剧设置对话框,空=关闭;`isSecretKey` cookie 后缀自动对非管理员脱敏)配置后 `DoubanMetadataProvider.fetchSubjectPage` 带 Cookie 抓 `movie.douban.com/subject/{id}/`(Jsoup `#info` 块):「又名」→aliases(直接喂标题归属匹配),「IMDb tt号」→ `TmdbMetadataProvider.detailsByImdb`(`/find?external_source=imdb_id` 定位 tv 后复用全量详情)合并**单集播出时间(upcoming)/已播集数/续订状态/TMDB 别名**进豆瓣 details(`mergeTmdbDetails`:豆瓣保名称/封面/rexxar 集数,TMDB 只补豆瓣没有的字段,豆瓣又名在前)。防护:全局串行限速 `PageRateLimiter`(≥8s/次)、页面 Caffeine 缓存 24h、失败 30min 退避、ban 页检测("有异常请求"/sec.douban.com);未配置 cookie 完全不发详情页请求(匿名抓取易风控连累 suggest)。details 缓存 key 升级 `id:season`(跟随 TMDB provider 形态)。豆瓣订阅经桥接即得 TMDB 级日程,**无需改绑**。单测:`DoubanMetadataProviderTest`(又名/IMDb 解析、合并优先级、限速、cookie 开关)。
>
> **合并播放列表集号解析修复(2026-08-20)**:多版本资源(如 HQ.DV.60fps / SDR.50fps 两个季文件夹同挂载)客户端只显示 HQ 的集数、SDR 独有集全丢——根因:`parsePlayEntries` 解析的是 `getPlaylist` fixName 剥公共前后缀后的**显示标题**,而集数清单/播放解析用的是**原始文件名**。SDR 组 mp4/mkv 混排致公共后缀为空,标题残留 `2026/50fps` 等未被 TECH_TAGS 覆盖的数字,文件名规则的"末个 ≤999 数字"把 SDR 全组解析成 50(集号后的技术数字盖过集号)。修复:新增 `parseEpisodeFromTitle`(显示标题专用):剥前缀后集号必在最前,取**首个** 1-999 数字即返回;SxxEyy 幸存时仍精确命中并做季过滤。`parseEpisode`(原始文件名,末号规则)不变。单测:`MediaSubscriptionPlaylistParseTest.dualVersionSeasonFoldersKeepLaterEpisodes` 用线上双版本形态复现。
>
> **TVBox 多线路备用(2026-08-20,次日按盘分线定稿)**:csp_Media 详情改返回多线路——首条「我的追剧」仍为 msubep 逻辑线路(默认,续看/同步绑定逻辑 id,播放时实时选源+逐源回退),**其余线路按网盘分组**(百度网盘/夸克网盘/阿里云盘/115…,`DRIVE_NAMES`):同盘聚合该盘全部源(转存>主源>补缺,按集 putIfAbsent),不同盘独立成线——盘间独立失效,用户按"信任哪个盘"手动切换(合并线路失败、或百度限速想切夸克时)。盘 key 来源:主源=active 资源行 `type`→`DriveId.toDrive`;补缺=资源行 type;转存目标=`TransferredTarget.drive`(`DriverType`→盘 key 映射,record 加列)。备用线路条目 id 为通用网盘播放 id(`siteId@proxyId@folderId@fileId`),spider 透传 `/play` 即可解析,播放历史按规范网盘路径绑定(与网盘直放同链路);spider 侧 `parseEpisodeIndexes` 按 URL 建索引,备用线路 URL 与 msubep 键不冲突,播放同步不受影响。web 端单线路行为不变。**同族修复**:`mergePlaylistFrom` 原硬编码 `ac="detail"` → `getPlaylist` 对 detail/web 固定 depth=1,嵌套目录结构的补缺/转存挂载列空(合并静默丢集);改显式 depth=3 且 ac 透传(TVBox 空ac 出紧凑播放 id 供备用线路直连,web 出代理地址)。单测:`buildTvBoxPlayLines` 按盘装配/空盘跳过/未知 key 原样。
>
> **主网盘主动补齐(2026-08-21)**:全局主网盘(如 百度+夸克)不能被动等池——①**打分偏向**:`score()` 对主网盘候选 +15(主网盘要维持完整覆盖,池里得先有该盘资源;与盘偏好 driveTypes 解耦后搜索召回原本无任何主网盘偏向);②**主动搜索**:`ensureMainDrives` 对覆盖不全且池内无可探候选的主网盘,强制 `fillPool`(整季关键词)——限频每检查周期一次(`mainDriveSearchTime` 内存表,与补缺搜索叠加至多 2 次/轮)。换源激活沿分数序,主网盘候选自然优先成为主源(其覆盖即计入该盘)。单测:`fillPoolBoostsMainDriveCandidates`(夸克 85/百度 100/115 60 精确分值)。

> **单集链接治理(2026-08-21,修"115 会员追剧满池单集+单集挂主源")**:线上案例——百度整季主源过期换源时,搜索召回的 5 个候选全是 115 每集一链的分享("📺 悬案 (2026) S01E16 ✨4K"),VIP+15/已配置账号+8/4K+25 抬过集数落后-8,最高分被激活为主源,`applyInventory` 把 17 集观测清单打回 1 集,且新挂分享链接本身过期(errno 4100018)。三层修复:①**识别**(`singleEpisodeOf`):TITLE_PROGRESS 裸标记组(第N集/EPn/SxxEyy)= 单集链接,整季形态(更新至N/全N集/第A-B集)不算;②**打分**:本地 ≥2 集时单集链接 -40(VIP/4K 加分不再能抬它,仅配补缺);③**入池去重**:同集单集链接一席,席位留给不同集/整季资源,防每集一链刷满 Top5;④**主源门槛**(`usableAsPrimary`):本地 ≥2 集时单集资源(标题判定或探测 episodeList 仅 1 集)不挂主源——主源承载整季清单与固定挂载;新剧首集/电影(current<2)不受限。单集资源仍可作补缺挂载/主网盘补齐来源。单测:singleEpisodeOf 七形态/usableAsPrimary 四场景。

> **阿里转存支持(2026-08-21)**:转存目标从 DriverAccount 单一来源扩展为 `TransferTarget` 抽象(key/name/mountPath/shareType)——`resolveTargets` 解析订阅 `account_ids` 为 `"pan:{id}"`(网盘账号,Storage.getMountPath)与 `"ali:{id}"`(**阿里独立账号表 Account**,挂载根 `/📁我的阿里云盘/{昵称|id}/资源盘`,与 `AccountService.enableMyAli`/`Storage(Account,type)` 同规则;`showMyAli/master/唯一账号` 才挂载,未挂载的账号静默跳过)。转存/进度/归档/`transferredTargets`/播放盘线路(`drive()="ali"` → 阿里云盘线路)全链路贯通;跨盘路由 shareType=0 天然参与同盘/秒传判定(`ali_to_*`/`*_to_ali`)。旧数据兼容:account_ids 整数 JSON 与单值 accountId 一律视为 pan。DTO/Request `accountIds` 改 List<String>;UI 转存下拉 pan/ali 两组(阿里未挂载禁选)。PikPak 同构可后续照搬。单测:accountIds 混合解析/裸数字补前缀。

> **主网盘冗余(2026-08-21,同日改显式全局配置+订阅覆盖)**:**全局 Setting `msub_main_drives`**(逗号分隔分享类型码,取前 2,追剧设置对话框编辑,随 TG 通知/归档/VIP 同处)为默认主网盘;**订阅级 `main_drives` 列(V28)可覆盖**(空 = 跟随全局,清空即回归;订阅对话框"主网盘(覆盖)"字段,placeholder 展示当前全局值)。`mainDrives()` 解析:订阅覆盖 > 全局 Setting > 无。①**巡检保障完整覆盖**(`ensureMainDrives`,doCheck 尾声执行):观测全集(主源∪补缺快照)按盘核算,主网盘缺口从候选池**同盘**资源探则挂(与 fillGaps 同机制按盘约束,主源所在盘天然计为已覆盖;每轮挂载/探测预算独立计 maxGapMounts/maxGapProbesPerRound);池内无该盘资源不强制搜索,靠常规搜索周期补池,转存副本不计入(自有事后校验)。②**退役豁免**(`retireGapMounts`):主网盘补缺挂载即使被主源全覆盖也保留,主源换盘/失效时该盘线路不断供。③**线路规则**(`buildTvBoxPlayLines` 加 mains 参数):主网盘线路固定展示(允许暂不完整),非主网盘须覆盖齐 merged 全部集才上线路。④免登录/账号:分享挂载均为游客态(免登录),需登录态才稳的盘探测失败自然落 BAD 退出候选,添加网盘账号的盘可转存更稳(盘选项"已加账号"标注合并三源:`/api/pan/accounts` DriverAccount + 阿里独立表 `/api/ali/accounts` + PikPak 独立表 `/api/pikpak/accounts`)。限制:ENDED 订阅停止巡检即停止保障(追更阶段语义);ensureMainDrives 不感知转存目录。单测:mainDrives 解析(覆盖/全局回退/序列化)/线路规则/V28 迁移链。
>
> **玩偶聚合搜索源(2026-08-22)**:atv-spiders `py/玩偶聚合.py` 移植为 `WanouSearchService`,成为追剧搜索源之一(§4.6 第四源)。11 个玩偶系 MacCMS 网盘站并行搜索(站点优先级玩偶>多多>木偶>…),卡片标题归一化粗匹配(剥 4K/站名噪声与集数/季/年份标记,双向包含,精确过滤仍由 `matchesTitle` 把关)后抓详情页(`module-row-info p` 文本;虎斑/小斑走 `module-row-text@data-clipboard-text`),提取分享链接(修 `hhttps://` 复制瑕疵、URL 停在中文/全角字符、行内提取码折 `?password=` 供 `ShareService.parsePassword` 消费),`Message.parseType` 复用盘型识别产出与 TG 同构的候选。**域名治理**:静态表仅种子兜底,`pan-site-monitor`(默认 `https://pan-site-monitor.douer.me/api/data`,`app.subscription.wanou-monitor-url`)每 6h 下发按延迟排序的可达地址(监控键为中文名,欧歌↔欧哥),拉取失败 10min 重试;请求逐域名 failover+成功粘滞,CF 挑战页(200+challenges.cloudflare.com)识别为失败保证轮转,全域名失败站点冷却 30min。接入点:`fillPool`/`preview` 经 `searchAllSources` 与 TG 三级回退结果按 link 去重合并(玩偶源失败仅告警不影响 TG)。配置:`wanou-enabled`(默认开)/`wanou-max-detail-pages`(单站详情页上限,默认 3)/`wanou-timeout-seconds`(总超时 45s)。单测:`WanouSearchServiceTest`(解析/盘型/failover 粘滞/监控刷新/跨站去重/CF 判定);实测冒烟:7+ 站可达,「难哄」召回 34 条(夸克/UC/阿里/115/天翼),20s 内完成。

> **盘链搜索源(2026-08-22)**:atv-spiders `py/盘链.py` 移植为 `PanLianSearchService`(§4.6 第五源)。需登录的分享聚合站(默认 `https://www.xn--vzy265d.cc`,Setting `panlian_host` 可覆盖),JSON API:`/api/get_videos.php`(搜索)/`/api/search_pan_links.php`(取链,vod_id+keyword 去语言后缀)。**凭证必须用户自配**(用户要求,Python 里的混淆内置共享账号不移植):Setting `panlian_username`+`panlian_password`(multipart 登录 `/api/login.php`,先取登录页 PHPSESSID)或直接 `panlian_cookie`(优先使用不触发登录),追剧设置对话框新增「盘链搜索源」分组编辑(password/cookie 后缀命中 isSecretKey 自动脱敏);未配置任何凭证 → 源静默关闭(日志提示一次)。登录 Cookie 内存缓存,响应 code=-1/success=false 含"登录"自动重登一次,登录失败 5min 冷却。链接两态:直链(结构化 password 折参数:百度/迅雷/123 → `pwd=`、115 → `password=`,已有参数不重复折)+ token(`/api/go.php?t=` 302 跟随解析,手机 UA+`skip_go_warning=1`,仍在本站的跳转视为失败丢弃);`Message.parseType` 定型,磁力/电驴/未知盘丢弃。接入 `searchAllSources`(mergeSource 抽出,wanou/panlian 共用)。实测:站点 TLS 正常,未登录搜索返回 code=-1"请先登录"(与重登判定吻合),假账号登录被正确受理拒绝(message 字段)。单测 9 个:URL 清洗/提取码折叠/host 归一化(中文域名 IDNA,注意 URI.create 拒绝非 ASCII 主机须手动解析)/无凭证关闭/登录取链全流程/登录冷却/配置 Cookie 直用。

> **观影搜索源(2026-08-22)**:atv-spiders `py/观影.py` 移植为 `GuanYingSearchService`(§4.6 第六源)。需登录的分享聚合站,8 个电影名中文域名互为镜像(教父.com/星际穿越.com/楚门的世界.com 等,静态表存中文名启动 IDNA 转punycode,`guanying_host` 可覆盖,逗号/竖线/换行分隔多站点)。**凭证必须用户自配**(与盘链同策略):Setting `guanying_username`+`guanying_password`(FormBody 登录 `/user/login`:code/siteid=1/dosubmit=1/cookietime/username/password;captcha 响应提示改配 Cookie)或 `guanying_cookie`(种子进共享 Cookie 状态);追剧设置对话框「观影搜索源」分组;未配置 → 源静默关闭。**反爬两道**:①PoW——响应呈挑战特征(JSON code=419 / refresh=1+验证 / 浏览器验证已过期 / pow.worker / filejin;**`_obj.` 在场优先判为正常数据页**,否则 nologin 壳页里的 filejin 静态域名会误判)时 `/res/pow` 取 {N,x,t} 算 `y=x^(2^t) mod N`(`BigInteger.modPow`,与 Python pow 对照测试)提交换 browser_verified;②登录态——nologin/未登录 响应自动重登一次,失败 5min 冷却。请求管线:多镜像逐个 failover(404/5xx/4xx 换下家,成功镜像粘滞),Set-Cookie 全镜像共享合并(Max-Age<=0/deleted = 删除)。搜索:HTML 内嵌 `_obj.search={l:{i,title,d,year,info}}` 平行数组,**空则回退 `/res/search_suggest`(实测匿名可用,未登录也拿得到条目)**;盘链 `/res/downurl/{type}/{rid}` 的 `panlist.url/name/p` 平行数组,提取码折 `?password=`(该站约定统一 password,与盘链按盘折 pwd= 不同);downlist 磁力与未知盘丢弃。单测 11 个:PoW 四组对照值/挑战判定(含 _obj 优先序)/解析双路径/PoW 恢复重试/登录/假 Cookie 失效路径。**坑:`Map.of` 迭代顺序 JVM 间随机(ImmutableCollections SALT),拼查询串必须 LinkedHashMap**,否则桩测试偶发 miss(全量首跑即翻车,改保序+按路径匹配后三连绿)。实测冒烟:8 镜像中 2 个 DNS 已死其余互通,suggest 匿名返回「难哄」tv/g22b,downurl 假 Cookie 403 全镜像轮转后正确返回空。

> **蜗牛搜索源(2026-08-22)**:atv-spiders `py/蜗牛.py` 移植为 `WoniuSearchService`(service.sitesearch,§4.6 第七源)。MacCMS 衍生影视站(wn4k),双线路 `wn4k.com`/`zmi.kdns.fr` 并发测速(4s 探测超时)取最快粘滞,请求失败换线;`woniu_host` 配置单地址则跳过测速。**游客可搜索但网盘链接打码成 `https://******(登录后可见)`(pan-link-btn href 同空),必须登录取链** → 凭证用户自配(同盘链/观影策略):`woniu_username`+`woniu_password`(POST `/user/login.html` form user_name/user_pwd → code=="1",Set-Cookie 只保留 user_check/user_id/user_name 最小凭证集且必须有 user_check)或 `woniu_cookie`(归一化:剥 Cookie: 前缀、按;/换行拆、只留含=段);追剧设置对话框「蜗牛搜索源」分组;无凭证静默关闭。详情页 `.pan-link-meta` 含 `*` 即登录态失效 → 自动续期一次,失败 10min 冷却(`RELOGIN_COOLDOWN`=600s 同 py)。搜索 `/vodsearch/-------------/?wd=`(翻页走路径槽位,只取第 1 页),卡片 `a.video-card`(@title → .video-title 文本 → img@alt 回退链,vod_id 正则 `/voddetail/(\d+)`,角标 video-score · video-episode 合并);盘链候选 = `a.pan-link-btn@href` + `.pan-link-meta` 文本,采集即按盘规则过滤(parseType 数值型),无 `.pan-link-item` 时整表 `.pan-link-list` 兜底。单测 11 个:vod_id/卡片/盘链过滤/打码判定/Cookie 归一化/登录取链(打码→续期→重取)/登录冷却/选线(最快者/全不可达 null)/配置 Cookie 直用。实测冒烟:zmi 本机不可达自动选 wn4k(472ms),「难哄」3 卡片,假 Cookie 详情全打码正确返回 0。注意:该站资源无提取码字段,链接原样使用。

> **盘聚搜索源(2026-08-30)**:atv-spiders `py/盘聚.py` 移植为 `PanjuSearchService`(service.sitesearch,§4.6 第八源)。seedhub 系网盘聚合站,**免登录**(同玩偶模式,区别于盘链/观影/蜗牛三源)。**Cloudflare 指纹门禁是核心风险**:OpenSSL 系指纹(curl/urllib3 1.x)被 403,py 版靠 CloudflareTLSAdapter 伪装 ClientHello 过盾;Java 侧实测 **JDK/JSSE 指纹(OkHttp)天然可过,无需 TLS 伪装**——但 CF 规则收紧即断,挑战页标记(Just a moment/challenge-platform/cf-turnstile/cf-mitigated 头等)识别为该域名失败走 failover,全域名失败静默返回空不拖垮其它源。**域名轮换**:官方域名频繁变更,内置 6 域名种子(sidhub/seedog/seeduck/hubdog/哈巴狗 IDNA/seedhub)+ `/worker-json-hosts/` 动态下发(每 6h,只试队首 2 域名防首搜阻塞,失败 10min 重试),逐域名 failover+成功粘滞。**两跳取链**:详情页 `.pan-links` 行(隐藏容器)只有站内中转链 `/link_start/?redirect_to=pan_id_X`,`data-link` 属性标盘型域名(**免请求预判盘型**);真实分享链在中转页脚本 `var panLink = "..."`(百度 pwd 提取码内嵌,window.open/location.href/裸盘链兜底)。盘型三级判定(data-link 域名 → href 域名 → 标题文本「夸克/百度」等),按**候选池价值排序**(夸克/UC/阿里转存主力盘在前,与 py 版百度最前不同——解析有配额,排序决定配额花在哪)选择性解析中转页,`panju-max-resolves`(默认 8)上限截断;去重按 `redirect_to` 目标而非 href 全串(movie_title 参数各异会漏)。**坑(真机踩过)**:href 的 movie_title 值是裸 Unicode(▶️/中文),带着发请求 `URI.create` 炸 Referer 头构造 → 解析行时即剥掉 movie_title 只留 redirect_to;`rootOf` 手工切串不走 URI.create。搜索页 `.cover` 卡片(img@alt 标题),`/s/{keyword}/?page=1`。接入 `searchAllSources` 第六路(池扩 5→6 线程),配置 `panju-enabled`(默认开)/`panju-max-detail-pages`(2)/`panju-max-resolves`(8)/`panju-timeout-seconds`(45)。单测 8 个:卡片/网盘行/中转解析(打桩 fetch)/盘型双路/粗匹配/挑战判定/端到端/开关关闭。实测冒烟:「末日地堡」4.9s 召回 15 条夸克候选,资源标题自带集数进度(更09集/附前两季/全3季)。

> **分盘线路自动保障 + DV 绿屏治理(2026-08-22)**:线上案例——「悬案」候选池有 百度×3(MOUNTED 主源)/夸克×3/115×3(CANDIDATE),但 TVBox 只有「我的追剧+百度网盘」两条一样的线路:主源已集齐无缺集 → 夸克/115 永不被探测挂载;旧线路规则「非主网盘须集齐才上线路」再把半覆盖盘拦掉。四层修复:①**分盘线路保障**(`ensureDriveLines`,doCheck 尾声 + 详情触发):候选池里每个网盘(除主源盘)探测挂载至少一个覆盖观测集的源——整季源挂 1 个即满覆盖,单集源(115 每集一链)逐集挂至 `drive-line-mounts-per-drive`(默认 3);与补缺共用 `max-gap-mounts` 挂载预算与探测限额(缺集先行)。②**详情触发补线**(`ensureDriveLinesAsync`,限频 10min):TVBox 打开详情发现池里有未出线盘即异步补挂,下次刷新可见,不必等巡检周期。③**线路规则放开**(`buildTvBoxPlayLines`):盘线路非空即上,主网盘居前、其余按覆盖数降序——单集源盘线路即该盘可用集清单,合并线路仍是完整权威清单;盘显示名收敛到 `DriveId.displayName`(服务/巡检共享)。④**挂载回收改同盘冗余清理**(`retireCoveredAuxMounts`):不再"主源已覆盖即整批退役"(那会把线路挂载全部回收,线路随巡检消失),改为同盘按分数序保留至多 N 个"各有独占集"的挂载,覆盖是同盘已保留挂载子集的纯冗余仍退;主网盘豁免沿用。配置:`drive-lines-enabled`(默认开)/`drive-line-mounts-per-drive`;`max-gap-mounts` 默认 3→6(线路挂载共享预算)。事件类型 `DRIVE_LINE`(前端「分盘线路」,primary 色)。**DV 绿屏治理**:线上「悬案」主源实为 `Season 1（HQ.DV.60fps）`14 集 + `Season 1（SDR.50fps）`17 集**两个季文件夹**、文件名不带画质标记——先到先得(组序/列举序均未定义)选了 HQ.DV 组的前 14 集 → 杜比视界 Profile 5(单层 IHLP)在不支持的设备解码整屏泛绿。核心:`TextUtils.picturePenalty`(DV/DoVi/Dolby Vision/杜比视界=2 > HDR(10)=1 > 无标记/SDR=0;**逐段从文件名向外扫描,取最近一段带标记的目录判定**——标记常在版本目录名上;显式 SDR 段即终答;同段 DV&SDR 混标跳过)。四个落点:①`TvBoxService.dfs` 版本文件夹按惩罚稳定排序(DV/HDR 组靠后,无标记组序不变)——修播放列表组序,合并线路/分盘线路/普通网盘浏览的消费方先到先得自然取到非 DV 版;②`collectEpisodeFiles` 同集多版本择优(惩罚带目录上下文比较,兼容性差者被替换,集源行 rel_path 随 syncInventory 原位更新);③`parsePlayEntries` 同集重复条目择优(平铺混排包 01.DV/01.SDR 同组并列的防御);④`playCandidates` 同分 tie-break。**行级自愈不等巡检**:详情触发 `ensureDriveLinesAsync` 先 `resyncPrimaryInventory` 重列主源同步集源行(画质择优换文件立即生效;完结订阅退避 24h、ENDED 后不再完整巡检,等巡检等于不自愈)。若某源只有 DV 版仍会播(比没有强),用户可切其它盘线路(SDR 包)逃生——分盘线路与画质治理互补。单测:picturePenalty 三档/最近目录段回退/SDR 终答/混标跳过/DVDRip 不误伤/同盘冗余清理(线路挂载保留、纯冗余退)/线路部分覆盖出线与排序。

> **流探测误杀事故(2026-08-22 20:09,同日修)**:线上「悬案」点了一次「检查」,主源被退役删挂载、详情 404。三层根因与修复:①**「参数错误」同文案两义**——既是真死链的 AList 报错,也是百度游客取链撞反爬瞬时窗口(该主源半小时前还在正常拉流);`verifyStream` 取链失败路径把它按 GONE 判死,且样本探测+传染二次探测在 2.4s 内撞同一个反爬窗口(相关性失败,非独立证据)→ 整源退役+删挂载+90 天黑名单。修复:verifyStream 单独把「参数错误」降级为 TRANSIENT 不下结论(误杀=删挂载/黑名单,误留=行降 FAILED/缺集重开/列目录失效路径仍兜底,代价不对称);明确过期措辞(链接已过期 errno 4100018/分享已失效)仍判死。②**判死路径不换源**:onInvalid(列目录失败)自带 activateNextCandidate,而采样/传染判死只退役不重挂 → 固定路径空到下轮巡检(退避可达 24h)。修复:doCheck 在 sampleMounted 后检查 shareId 悬空即同轮 ensureSource 重挂固定路径(置于 ensureMainDrives 前,新主源优先占最佳候选)。③**列得出 ≠ 播得了**:`probeShare` 补缺/主盘/线路三处探测只列目录,115 单集分享(errno 4100018 分享页活/文件链死)照样挂载、下轮采样才发现。修复:probeShare 临时挂载窗口内抽一行 verifyStream,明确 FAILED 以「链接已过期(文件不可播)」上抛 → 调用方按 GONE 退役+黑名单,不再占挂载名额;瞬时/无结论不拦(代理型驱动无直链=VERIFIED 不受影响)。单测:参数错误→TRANSIENT/明确过期→FAILED/probeShare 链死拒挂+临时挂载即删/传染撞参数错误不判死。

> **多源搜索五路并发(2026-08-22 同日)**:线上观测 fillPool 37s —— TG 聚合(PanSou/TG-Search/网页,内部已并行)返回后,玩偶→盘链→观影→蜗牛四个站点源**逐个串行排队**,总时长=各源之和。`searchAllSources` 改五路 `CompletableFuture` 并发(专用 5 线程池 `msub-search`,@PreDestroy 关闭):总时长=最慢一路;各源内部超时之外加 90s `orTimeout` 硬顶;单源失败静默为空不影响其它源;合并顺序与去重语义不变(TG 在前先见先得)。preview 路径(50 条、cached)同步受益。

> **标题归属年份门禁(2026-08-22 20:31 续,同日按动漫形态修正)**:误杀事故的次生灾害 —— 主源被判死后 `ensureSource` 自动换源,顶上固定路径的是「悬案解码 第一季 Dept. Q (2025)」(英剧 9 集):标题「悬案**解码**」包含关键词「悬案」,`matchesTitle` 归一化包含(≥2 字)被子串命中骗过,季过滤也拦不住(都是第一季)。修复:**年份门禁 + 词边界豁免** —— `metaYear()` 从已绑元数据(`MetadataDetails.year`,provider 缓存)取基准年;标题提取年份(`(?<!\d)(19[89]\d|20[0-2]\d)(?!\d)`,边界防 1080p/60fps 误配),**年份全不符时看剧名命中方式**:整词命中(归一化标题独立词 == 剧名/别名)放行 —— 动漫全系列包常标**第一季年代**(「鬼灭之刃 (2019)」装全部季),同名作年代歧义交给季过滤/探测定夺;仅子串嵌在更长词里(悬案⊂悬案解码)才是前缀异剧,拒。元数据未绑/无年份、或标题不标注年份 → 放行,零误伤。落点:①fillPool 入池;②`candidatesOrdered` 统一过滤(补缺/主盘/线路/换源共用视图,错资源连探测都不进)。score() 软加分不动。单测:门禁六形态/动漫首季年代全系列包放行/同名翻拍放行/候选过滤(2025 前缀异剧出局)。限制:未绑元数据无门禁;空格分词的「悬案 解码」写法可绕过(罕见,接受);标题谎标年份的整词命中资源放行(名字层面无法区分)。
>
> **误挂异业主源复核(2026-08-22 21 时段)**:年份门禁只拦候选,不动已挂主源 —— 线上「悬案解码 (2025)」顶在固定路径上,列目录/流探测全正常,巡检无天然失效信号,点「检查」也无人纠正它(教训:只堵入口不纠存量,已挂错的资源巡检永远"健康")。补 `belongsToShow`(标题归属+年份门禁,与入池同规)两处复核:①doCheck 在主源集源同步前复核,不符即 `activateNextCandidate` 自动换源 —— activate 自动把旧主源降级回候选池(行落 MISSING,**不进跨订阅黑名单**:链接没死只是不属于本剧);暂无同剧候选则保留现主源兜底可用性、补池后下轮再换;换源成功后重列目录继续本轮。②`refreshAuxMounts` 对补缺/线路挂载同复核,误挂异剧就地卸载回候选池(同样不拉黑),防其行向"本地已有集"冒领错误集号。单测:主源归属复核五形态(异剧/年份相符/无年份/无标题/未绑元数据)。
>
> **豆瓣名称桥接 TMDB(2026-08-22,修"播出时间轴只有 TMDB 源订阅")**:线上反馈 8 个订阅时间轴只显示凡人修仙传一个 —— 豆瓣订阅的播出日程完全依赖 IMDb→TMDB 桥接,而该链要求配置 `douban_cookie`(匿名不抓详情页)、页面解析出 IMDb、且 TMDB 收录该 IMDb,任一不满足日程即空。新增 `bridgeTmdbByName` 兜底:IMDb 桥接未带出日程时,用豆瓣名**剔季缀**(`第N季/第N部` 中文数字可、尾缀单数字「剧名2」)搜 TMDB,**归一化整词同名**(剧名/别名/剔缀基名;rexxar `aka` 现也并入 aliases)+ **年份门禁**(±1,候选缺年份放行;同名候选年份全不沾=同名异剧放弃,拦「悬案」2018 旧片)才桥接;**多季长篇(有效季>1)放行年份**(「诛仙 第四季」TMDB 首播 2022 必对不上豆瓣 2026);有效季 = 订阅季(用户显式选择优先)或标题季标(「瑞克和莫蒂 第九季」season=1 → S9);TMDB 无该季(totalEpisodes 空)宁缺毋滥不合并。rexxar 详情顺手捕获 `year`(名称桥接门禁基准)。**刷新门控放宽**:`refreshMetadata` 对日程全空(next_air_time 与 schedule 均空)的订阅跳过 24h 限流 —— 桥接能力升级后下一轮巡检(≤6h)即补上时间轴,detailsCache 6h 防打爆;拿到日程后恢复每日一次。实测(2026-08-22 线上 7 部):诛仙S4/重器/师兄太稳健 命中且窗口内带出将播集,悬案(已收官)/瑞克S9(已播完)/杀人者S2(已播完)正确判无待播,九门命中(全 30 集已有播出日期)。单测:`DoubanMetadataProviderTest`(季标/中文数字/剔缀、年份门禁四形态、子串模仿者不桥、尾数字季、TMDB 无季拒并、IMDb 已命中跳过)。

> **完结判定季级口径(2026-08-23,修"多季剧本季播完仍显示在播")**:线上「瑞克和莫蒂 第九季」10/10 集全播完详情页仍「在播」、订阅恒 ACTIVE 每 6h 空巡检 —— 根因:官方 `status` 是**剧级**字段(TMDB `tv.status`,多季剧本季播完时整部剧仍是 Returning Series,"还会回归"≠"本季在播"),自动完结与展示却只认它。新增**季级口径** `MediaSubscription.isSeasonAiredOut()`:`officialEpisodes ≥ officialTotal > 0` 且 `nextAirTime` 空(TMDB `next_episode_to_air` 已按季过滤,本季播完必空;播出日期缺失时 aired 计数为 0 天然不触发)。①自动完结(`shouldAutoEnd` 静态抽出便于测试):期望达标 / 剧级 ENDED 集齐之外补第三条"本季播完且集齐"——国产剧(剧=季)不受影响,缺集不完结(播完≠收齐);ENDED 后每日 `reopenEnded` 复查,官方加更(aired 上调超本地)照常自动重开。②详情接口 `media.status` 季口径覆盖剧级值,前端「在播/已完结」标签即正确,web-ui 零改动。单测:`seasonAiredOutEndsSubscriptionEvenWhenShowStillReturning`/`seasonAiredOutRequiresFullAiredSeasonWithoutNextAir`。

> **候选盘白名单:扩展网盘(2026-08-23,Setting `msub_extended_drives` 默认空)**:此前候选池默认收录所有网盘的分享源——用户只配了主网盘也会被塞进 115 等别的盘的候选。现改为**全局 Setting `msub_extended_drives`**(逗号分隔分享类型码,追剧设置「通用」页「扩展网盘」多选)配置后才收录:候选白名单 = 主网盘(订阅级覆盖∪全局 `msub_main_drives`)∪ 扩展网盘,**未配置扩展盘时候选/补缺/换源/分盘线路只有主网盘的源**;主/扩展均未配置时不限盘(兼容未配主网盘的旧用户,PoolQuota 退化单一档位同此口径)。落点:①`fillPool` 入池过滤(事件文案计「拦 N 条非白名单盘」);②`candidatesOrdered` 统一视图过滤——探测/补缺/主盘保障/分盘线路/换源全部不再使用白名单外的存量候选(已挂载资源不受影响,照常供流,抽屉仍展示可手动停用);③`resources()` 候选源抽屉与 `resourceCount` 同口径收敛(白名单外的非挂载行不再展示,避免"躺着没用的源"误导);④`preview` dry-run 同规(取全局白名单,所见即能入池)。无 type 的旧候选资源在白名单启用后视为域外(判不了盘不给进)。订阅级不设扩展盘覆盖(只全局)。单测:`fillPoolAdmitsExtendedDrivesCandidates`/`candidatesOrderedFiltersOffPoolDrives`/配额与主盘加分用例同步更新。

> **TVBox 分集标题美化开关(2026-08-23,Setting `msub_episode_titles` 默认关)**:TVBox 剧集列表原本显示文件名(fixName 剥前后缀后的原始标题),开启后改写为 `NN. 分集标题(大小)`(两位补零,百集以上不补)。接入点 `mergeGapPlaylists` TVBox 分支的 `rewriteEpisodeTitles` —— 逻辑线路(msubep)与按盘线路的条目标题一并就地改写,URL 部分不动,spider 零改动(`Media.java` 只透传标题);web 请求不受影响。数据源:分集标题读 `media_metadata` 快照零网络(TMDB/桥接产分集,豆瓣纯源无 → 该集保留原文件名);**大小直接从原条目标题尾部提取**(TvBoxService 装配 `fixName+"("+byte2size+")"`,如 `47 4K.mp4(796.08 MB)`,不另查集源行,提取后剥出重拼避免重复,size≤0 残留的空 `()` 一并剥掉)。标题先洗 `$`/`#`(播放列表分隔符,残留截断条目)。开关在追剧设置对话框「通用」页;历史/续看绑定 msubep 逻辑 id,改标题无副作用。单测:`episodeDisplayTitleFormatsNumberTitleAndSize`/`rewriteTitlesKeepsUrlPartIntact`。

> **播出时间轴"昨天/今天"空档(2026-08-23,修"昨天更新的剧不进时间轴")**:线上「慕兰之战」第12集 8/22 20:00 已播(已有主源),时间轴「昨天」分组却为空——根因:三个 provider 的 `upcoming` 日程**只收严格未来播出日**(TMDB/官网按 `airDate.isAfter(today)` 日期粒度,播出日当天凌晨刷新即判"已播";Bangumi 状态一翻转就 `continue`),而 `schedule` 快照(`applyMetadataSnapshot`,昨日 00:00 ~ +14 天窗口)每日元数据刷新时整个重写,刚播出的集随即从快照消失;剧季集数已齐时 `nextAirTime` 亦为空,`schedule()` 的兜底不触发。同理还有一个隐蔽形态:**今晚播出的集**(air_date=今天)从不进日程,「今天」分组在播出日刷新后也会空。修复:`TmdbMetadataProvider`/`OfficialSiteMetadataProvider.applyEpisodeDates`/`BangumiMetadataProvider` 统一把**昨日+今日**档期(已播状态或当日待播)也收进 `upcoming`,与快照窗口起点对齐——任何刷新时刻 T,时间轴窗口 [T-1 日 00:00, T+8 日] 内的条目都能在快照里存活;`nextAirTime`/已播计数/完结判定语义不变(仍严格未来)。快照过期前旧数据天然兼容(严格未来的条目照旧)。单测:`TmdbMetadataProviderScheduleTest`/`BangumiMetadataProviderScheduleTest`(MockRestServiceServer 桩 provider 全请求链)/`OfficialSiteMetadataProviderTest.episodeDatesKeepRecentAiredForTimeline`/`MediaSubscriptionRemarksTest.schedulePutsYesterdayAiredEpisodeInYesterdayBucket`。

> **夸克 4K 转码标注污染集号(2026-08-23,修"三集迷你剧变 45/60/72 集且追剧线路不可播")**:线上「邻人可疑」(2026,三集迷你剧,上/中/下)——文件名「上集：喜迁新居，竟遇"诡"邻 [322155_maxplus_50fps_tv_6.72GB].mkv」(夸克 4K 转码命名):`parseEpisode` 末号规则取到**体积 6.72 的 72**,三集各成 45/60/72(maxEpisode=72,集源行与官方 3 集对不上,详情页分集全缺失);显示标题侧 fixName 剥公共后缀 `GB].mkv` 后残留**未闭合**技术段 `[322155_maxplus_50fps_tv_6.72`,`parseEpisodeFromTitle` 首号规则把模板 id 322155 拆成 3221+**55**,三集全部撞成 55 去重塌成一集——「我的追剧」线路只剩 `msubep-35-55`,播放报"第 55 集暂无可用播放源(已尝试 0 个源)"。修复:①`stripTechBrackets`(两个解析器共用):方括号段含技术信号(fps/maxplus/体积 `\d+(\.\d+)?[GMTK]B?`/≥5 位长数字 id,叠加既有 TECH_TAGS)整段剔除,含**显式集号标记**(第N集/SxxEyy/EPn)或纯内容段(`[01]`)保留,未闭合尾段同样处理;②`chapterNumber` 兜底:数字解析无果时按「上/中/下(+集/篇/部)」推定集序 1/2/3(与 TMDB 三集迷你剧 S1E1-3 标题一一对应),显式集号优先(第05集 上部不误判)。数据自愈:下一轮巡检 `syncInventory` 新集号落 LISTED、旧行 45/60/72 翻 MISSING,无需迁移。同批:③`mergeTmdbDetails` 补 `overview` 合并(豆瓣侧 rexxar/本地库/详情页均无简介字段,桥接命中时 TMDB 简介补上——豆瓣订阅详情页"没有简介"修复);④web 详情页「下一季」按钮按元数据 `totalSeasons` 收敛(已知总季数且当前季已是最后一季时隐藏,单季迷你剧完结后不再出死按钮;总季数未知保留入口由后端探测)。单测:`MediaSubscriptionCheckServiceTest.bracketTechAnnotationIsNotMistakenForEpisode`/`chapterFallbackOnlyWithoutExplicitNumber`/`bracketWithExplicitEpisodeMarkKept`、`MediaSubscriptionPlaylistParseTest.quarkTranscodeBracketTitlesParseToChapterNumbers`(线上 fixName 残缺形态端到端)、`DoubanMetadataProviderTest.mergeTmdbFillsOverviewWhenDoubanBlank`。

> **已播集数按播出时刻判定(2026-08-23,修"官方已播 33 集实际 28 集")**:线上点映礼形态——大结局 5 集(29-33)同日 20:00 上架,TMDB 季 air_date 全标今天;`TmdbMetadataProvider` 季循环与 `OfficialSiteMetadataProvider.applyEpisodeDates` 的已播判定是**日期粒度**(`!airDate.isAfter(today)`),播出日当天 0 点起刷新即把当日集算进已播 → `officialEpisodes=33/officialTotal=33`(实际 28),详情页头部"已播 33"与分集行 29-33 `aired:false` 自相矛盾;`checkUpdateAsync` 顺带报"官方已播至第 33 集,本地缺 29-33"触发徒劳补搜(`computeMissing` base 同源)。修复:已播判定改**播出时刻粒度**(air_date 当日 20:00,与 airTime 展示同口径):`airMoment <= now` 才算已播——播出日 20:00 前当日集落"未来支路"(nextAirTime=当日 20:00、进 upcoming 时间轴「今天」分组、status RETURNING),20:00 一过自然转已播。海外剧实际北京次日凌晨播出的,旧口径会在播出前最多 20h 虚报,新口径只可能晚报数小时(保守方向,巡检 22:00 轮自愈)。时间轴昨日窗口收录(2026-08-23 上一条)不受影响:已播支路仍按昨日窗口进 upcoming。落点:`TmdbMetadataProvider.applySeasonEpisodes`(季循环抽出 static 包可见,注入 now 直测,applyUpdateText 先例)/`OfficialSiteMetadataProvider.applyEpisodeDates` 双参重载。Bangumi 用 API 显式 `status==0` 判已播、豆瓣不推算已播,均不受影响。存量错误快照(officialEpisodes 虚高)在下次元数据刷新/手动检查更新后自愈。单测:`TmdbMetadataProviderScheduleTest.massReleaseNotAiredBeforeAirHour`/`massReleaseAiredAfterAirHour`(线上点映礼形态 28+5)/`upcomingKeepsYesterdayAiredEpisodes`(全链路改墙钟稳定形态)、`OfficialSiteMetadataProviderTest.episodeDatesNotAiredBeforeAirHourOnAirDay`/`episodeDatesAiredAfterAirHourOnAirDay`/`episodeDatesKeepRecentAiredForTimeline`(注入晚间时刻)。

> **vod_remarks 集数进度格式 + 标题季标去重(2026-08-23,同日补总数兜底链)**:①`buildRemarks` 追更中文案与 web 列表(已是 `N / M 集`)统一——**总数口径:手填期望(expected=0 表示跟随官方)> 官方总集数 officialTotal > 无**(首轮只看 expectedEpisodes,而线上订阅几乎都不填期望、总数在元数据 officialTotal 里,表现为"改了没变化";web 列表本来就是 officialTotal 优先兜底):已知总数时「已更新至 10 集 · 缺 20 集」→「**10/30集**」(分数自带缺口信息,缺 N 集后缀删除);完结「全30集 · 已完结」→「**30集完结**」(取 max(current,total));完结判定三条与 `shouldAutoEnd` 对齐:状态 ENDED(自动/手动完结权威口径)/ 手填期望达标 / 本季播完且收齐(`isSeasonAiredOut` 且 current≥total——年番"官方已知集数已收齐但季未播完"不误标,仍显示 188/188集);总数未知维持「已更新至 N 集」;已暂停/检查失败/🆕 角标前缀不变。②`displayName` 标题季标去重:订阅名来自豆瓣条目时常自带季标(「瑞克和莫蒂 第九季」),`resolveSeason` 从名字解析出 season=9 后 `displayName` 再追加「第9季」→「瑞克和莫蒂 第九季 第9季」重复;现用 `TextUtils.parseTitleSeason`(中/英/Sxx 四模式)识别条目名已带的季号,与订阅 season 一致时不再追加;名字无季标或季号不同(订阅的是别的季)仍追加。转存目录名 `-第N季` 后缀是磁盘命名(迁移逻辑依赖),不受影响。单测:`MediaSubscriptionRemarksTest.remarksShowProgressFraction`/`remarksShowCompletedTotal`/`remarksKeepUpdatedTextWithoutExpectedTotal`/`remarksFallBackToOfficialTotal`/`remarksShowCompletedByStatusOrSeasonAiredOut`/`remarksInProgressSeasonNotMarkedEnded`/`titleSeasonSuffixNotDuplicated`/`titleSeasonSuffixStillAppendedWithoutTitleMark`。

> **播放后前瞻验证(2026-08-23,连播前提前发现死集)**:此前只有正在播的那一集在播放时验证(`recordPlaySuccess/Failure`),后面的集要等常规巡检(6~12h 一轮)的 `sampleMounted` 每源每轮抽验 1 集慢慢覆盖——用户连着看时,可能播到下一集才发现源已失效当场卡住(全部候选失败才触发 `checkAsync` 补救)。现 `playEpisode` 成功取链后(转存/分享源两处返回点)fire-and-forget 触发 `preheatAheadAsync`:后台对**已上架的接下来 N 集**(`preheatAheadEpisodes` 默认 3,`findNumbersBySubscriptionAndStatesIn` 取 LIVE 集号筛 `> playedEpisode`)各取最优候选行做 `verifyStream` 字节级探测(与 `preheatEpisodes` 新集预热同构:VERIFIED 刷新新鲜度;FAILED 走传染判定,整源死退役换源;限流/403 不下结论)——未上架集无行自然跳过。节流:per-订阅 inFlight 去重 + 限频窗口(`preheatAheadIntervalHours` 默认 1h,连播不重复打探测),复用 msub-check 池不新建线程。探测后有集已无任何可播候选(含被传染退役牵连)→ `rescueAheadDead` 写事件「第X集链接验证失败(疑似被和谐),已自动补源」并提交完整巡检补源(带 2h 冷却;巡检自身有 inFlight 锁与补搜节制,不烧搜索配额)——用户还没播到那集,后台已自愈。状态机语义不变(LISTED 只由列目录写,VERIFIED/FAILED 由取链写)。TRANSFER 模式转存目录有效性不额外探测(TransferService 每小时 sweep 自愈),前瞻对其分享源后备行照常生效。单测:`preheatAheadVerifiesUpcomingEpisodes`/`preheatAheadFailedRowTriggersRescue`/`preheatAheadThrottledWithinWindow`/`preheatAheadSkipsUnairedEpisodes`。
>
> **B站排播时刻校正(2026-08-23,修"凡人修仙传 airTime 偏晚 9 小时")**:线上凡人修仙传(豆瓣订阅,名称桥接 TMDB 补日程)第 189 集 airTime 显示 8-29 20:00,而官网(bilibili md28223043)每周六 **11:00** 更新——TMDB air_date 只有日期,provider 统一默认填当日 20:00,时刻对不上实际排播,时间轴展示/已播判定/nextAirTime 全部偏晚。新增 `BilibiliScheduleRefiner`(豆瓣桥接链尾部接入):按剧名搜 B站番剧(**search.bilibili.com/bangumi SSR 页,游客可用**;api 搜索接口游客被 412 风控不可用),media-card 块「ss 主链接 + 封面 alt 标题」经 HTML unescape + 去高亮标签后与剧名(或剔季缀基名)**归一化整词匹配**(同名异剧/番外篇拦截)定位 ss 号;再取官方分集 `api.bilibili.com/pgc/view/web/season?season_id=`(游客可用)——**已上线集(status=13)pub_time 是精确播出时刻**(实测 186-188 集全部周六 11:00),**未上线集(status=2)pub_time 是占位值**(上一集上线后 +15 分钟)不参与,取最近 8 集时刻众数为排播规律(早年老集 status 非 13 且时段不同的不稀释)。校正:`applyScheduleClock` 把 episodes/upcoming 的 airTime **只换时分、日期不动**(TMDB 排播日与官方一致),airedEpisodes/nextAirTime 按校正后时刻重数(与 applySeasonEpisodes 播出时刻判定同口径——周六 11:00 更新的剧当天 11:00 即判已播,不再等到 20:00)。定位/时刻各 Caffeine 缓存 6h,失败静默保留 20:00 不炸详情链;HTTP 一律 byte[] 收包 UTF-8 解码(StringHttpMessageConverter 对无 charset 的 text/html 默认 ISO-8859-1,中文标题会乱码)。单测:`BilibiliScheduleRefinerTest`(线上形态端到端/失败静默/标题不匹配不发 season 请求/最近 8 集众数/时刻口径联动重数)。
>
> **缺老集补全链路三修复(2026-08-23,修"盗妖行只找到10集且不补充")**:线上「盗妖行」新订阅首轮换到 score 最高的百度分享——标题「更54集」实际目录只留尾部 10 集(33-38,40,53-55),缺 45 个老集,但用户侧表现为"只找到10集、没有补充",且下一轮巡检排到 24h 后。三个环节叠加,逐一修复:①**首轮巡检提前收工**——`doCheck` 对无主源订阅在 `ensureSource` 挂上主源后直接 `scheduleNext+return`,缺集检测/补缺/分盘线路全部跳过;改为挂上主源继续走完整流程(挂不上才 return),首轮即探测补缺(单测 `firstRoundFillsGapsAfterMountingPrimary`:主源尾部5集+全集候选,首轮 check 后全集资源 MOUNTED、currentEpisodes=55)。②**播出前休眠让位**——`scheduleNext` 见"下一集后天播"把 nextCheckTime 排到 min(播出+15min, now+24h)=24h 后,该假设只对"追新集"成立,忽略老集缺口;新增 `behindAiredEpisodes`(officialEpisodes>currentEpisodes 快照,官方无数据/本地未知不判缺)判定,缺官方已播老集时不停留休眠分支、落到常规退避间隔(6h 起),让 fillGaps 尽早跑(单测 `scheduleNextPreAirSleepYieldsToAiredGap`/`scheduleNextPreAirSleepKeptWhenAiredCaughtUp`/`behindAiredEpisodesRequiresBothSides`)。③**分盘线路落行后轻刷集数快照**——详情触发的 `ensureDriveLinesAsync` 挂夸克/UC 备用线路时 probeShare 已把全集行(1-55)落库,但 `currentEpisodes` 停在首轮 activate 写的 10,列表 remarks「10/60集」要等下轮巡检才追平(数据齐了、显示没齐);尾部接 `refreshEpisodeCounters`(行并集口径轻刷 currentEpisodes/maxEpisode,已一致不写库,单测 `refreshEpisodeCountersSyncsFromLiveRows`)。播放列表装配本就实时列举 主源∪补挂载,分盘挂载后刷新详情即可见全集,不受本条影响。全量 806 绿。

> 关键类:`MediaSubscriptionService`(CRUD/内容/合并播放/收件箱/导出导入/动作)、`MediaSubscriptionCheckService`(巡检/换源/补缺/探测/打分/通知)、`MediaSubscriptionTransferService`(转存/归档)、`web/MediaSubscriptionController`、`web/TelegramController`(msub 分支/操作组端点/首页分类)、`service/metadata/*`、迁移 `V20__MediaSubscription` + `V21__MediaSubscriptionMeta` + `V22__MediaSubscriptionMetaFix`(V21 曾因带引号小写列名在 H2 上导致 Column not found,V22 自愈,详见 `MediaSubscriptionMigrationTest`)。
> 留待:集→源映射仅动态计算+接口固化展示(未落表,设计标注条件性);搜索成功率等指标在追剧页 `/stats`(未嵌入 SystemInfo 页);转存空间水位依赖事后校验发现(未做转存前预估);用户默认偏好三级继承/预设档位/页头偏好 UI(`/preference` 仅存储未接入,§4.7)。
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

### 4.6 搜索源:盘搜 / TG-Search 频道 / 电报网页搜索 / 玩偶聚合站 / 盘链 / 观影 / 蜗牛 / 盘聚

分享资源来自八类来源,前三个已接入现有代码;玩偶聚合站、盘链、观影、蜗牛(均 2026-08-22)与盘聚(2026-08-30)分别是 atv-spiders `py/玩偶聚合.py`、`py/盘链.py`、`py/观影.py`、`py/蜗牛.py`、`py/盘聚.py` 的 Java 移植:

| 来源 | 现有实现 | 特性 | 在本系统中的角色 |
|---|---|---|---|
| 盘搜 PanSou | `RemoteSearchService`(`appProperties.panSouUrl`) | 聚合多频道/站点,一条结果含多个链接(带密码/时间/来源),自带 `/api/check/links` 校验 | 已配置时的常规搜索首选;候选池广度的主要来源 |
| TG-Search 频道 | `TelegramService.searchTgSearchApi`(`{tgSearch}/api/search`) | 结构化最好:`cloud_types` 按盘类型过滤,`media` 元数据(title/year/episode/quality)可免挂载预打分 | 未配 PanSou 时的常规搜索首选;打分元数据的主要来源 |
| t.me 网页抓取 | `TelegramService.searchFromWeb`(Jsoup 并行抓 `telegram_channel` 表中 webAccess 频道) | 零外部依赖、开箱即用;慢、易风控,覆盖面取决于频道表 | 兜底来源;缺集补搜聚合模式的补充源 |
| 玩偶聚合站 | `WanouSearchService`(11 个玩偶系 MacCMS 网盘站:玩偶/多多/木偶/欧歌/至臻/蜡笔/二小/虎斑/小斑/快映/闪电) | 网页抓取:并行按站搜索 → 卡片标题粗匹配 → 抓详情页提取网盘分享链接(行内提取码折 `?password=`);域名由监控服务下发最新可达地址,逐域名 failover + 成功粘滞;CF 挑战页识别为失败 | 候选池补充源:`fillPool`/`preview` 与 TG 三级回退结果按 link 去重合并(`searchAllSources`),实测 7+ 站可达、单剧 30+ 分享 |
| 盘链 | `PanLianSearchService`(需登录的分享聚合站,JSON API 搜索/取链) | **凭证必须用户自配**(Setting `panlian_username`/`panlian_password` 或 `panlian_cookie`,站点 `panlian_host` 可覆盖,追剧设置对话框编辑;**不内置共享账号**,未配置时源静默关闭);账号密码 multipart 登录(Cookie 内存缓存、响应"请先登录"自动重登、失败 5min 冷却防撞墙);链接两态:直链与 token(token 经 `/api/go.php` 302 解析真实分享链,手机 UA+`skip_go_warning`);结构化 password 按盘折 `pwd=`(百度/迅雷/123)/`password=`(115) | 候选池补充源:同经 `searchAllSources` 按 link 去重合并 |
| 观影 | `GuanYingSearchService`(需登录的分享聚合站,8 个电影名中文域名镜像:教父.com/星际穿越.com 等) | **凭证必须用户自配**(Setting `guanying_username`/`guanying_password` 或 `guanying_cookie`,站点列表 `guanying_host` 可覆盖;未配置时源静默关闭);反爬两道:PoW 工作量证明(`y=x^(2^t) mod N`,BigInteger.modPow;挑战特征判定中 `_obj.` 在场优先视为正常数据页)与登录态(nologin/未登录 自动重登,失败 5min 冷却);多镜像逐个 failover+成功粘滞;搜索 = HTML 内嵌 `_obj.search` 平行数组,空则回退 `/res/search_suggest`(**匿名可用**);盘链取 `/res/downurl/{type}/{rid}` 的 `panlist.url/name/p`,提取码折 `?password=` | 候选池补充源:同经 `searchAllSources` 合并;实测未登录可拿 suggest 条目(难哄 tv/g22b),downurl 需真实登录 |
| 蜗牛 | `WoniuSearchService`(service.sitesearch,MacCMS 衍生影视站,双线路 wn4k.com/zmi.kdns.fr 并发测速取最快+粘滞,失败换线) | **凭证必须用户自配**(Setting `woniu_username`/`woniu_password` 或 `woniu_cookie`〔须含 user_check〕,站点 `woniu_host` 可覆盖测速;未配置时源静默关闭);**游客可搜但网盘链接打码成 `https://******(登录后可见)`,必须登录取链**;登录 POST `/user/login.html`(user_name/user_pwd)→ code=="1",只保留 user_check/user_id/user_name 最小凭证集;详情页打码即登录态失效 → 自动续期一次,失败 10min 冷却;搜索 `/vodsearch/-------------/?wd=`(翻页走路径槽位),卡片 `a.video-card`;盘链候选 = `pan-link-btn@href` + `pan-link-meta` 文本,含 `*`/未知盘过滤 | 候选池补充源:同经 `searchAllSources` 合并;实测 wn4k 可达(zmi 本机不可达自动跳过),假 Cookie 详情全打码正确返回空 |
| 盘聚 | `PanjuSearchService`(service.sitesearch,seedhub 系网盘聚合站,免登录;域名轮换:6 种子 + `/worker-json-hosts/` 动态下发,逐域名 failover+粘滞) | **Cloudflare 指纹门禁**:OpenSSL 系指纹被 403,JDK/JSSE(OkHttp)实测可过,挑战页标记识别为失败走 failover、全失败静默为空;**两跳取链**:详情页 `.pan-links` 行只有站内中转链,`data-link` 属性免请求预判盘型,中转页脚本 `var panLink="..."` 出真实分享链(百度 pwd 内嵌);盘型三级判定,按候选池价值排序(夸克/UC/阿里在前)选择性解析,`panju-max-resolves`(8)截断,按 `redirect_to` 去重;中转链的 movie_title 裸 Unicode 会炸 Referer 头构造,解析时剥掉 | 候选池补充源:`searchAllSources` 第六路(池 5→6 线程),`panju-enabled`(默认开)/`panju-max-detail-pages`(2)/`panju-max-resolves`(8)/`panju-timeout-seconds`(45);实测「末日地堡」4.9s 召回 15 条夸克候选,资源标题自带集数进度 |

- **统一抽象 `SearchProvider`**:返回归一化 `Message` 列表,`link` 为天然去重键(同一分享在多源/多频道出现时自动合并);现有三个实现之外,未来来源("等" —— 其他盘搜站、聚合 API)按同一 SPI 插入。不改动 `TelegramService.search` 现有三级回退,聚合/策略层包在其上;
- **两种策略**:常规巡检用**回退链**(省额度:PanSou → TG-Search → 网页,任一来源结果够用即停,即现有 `search()` 行为);缺集补搜用**聚合模式**(多源全开、结果合并去重,最大化候选覆盖,代价可控因为只在补集时发生);
- **来源元数据进打分**(ResourceRanker):TG-Search 的 `media.quality/episode`、PanSou 链接的 `note/datetime/source`、网页结果的频道名(字幕组质量信号);
- t.me 网页搜索的召回质量依赖 `telegram_channel` 频道表 —— 已有完整管理面(`/api/telegram/channels` CRUD、`channels.json` 种子、`validateChannels`),用户增配影视发布频道即可提升召回,无需新开发。

### 4.7 用户偏好与打分模型(ResourceRanker)

不同用户对体积/码率的取舍不同(原盘党 vs 省流量党)。**现状:偏好只看订阅 `filter_config`,留空维度直接用系统默认**——设计的**三级继承:订阅 `filter_config` > 用户默认(`user_preference`) > 系统默认**未实现:`user_preference` 仅 `/preference` 存储接口、无消费方、无前端入口(§5);接入后订阅留空维度自动沿用上级,避免每个订阅重复填。

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

**预设档位**(用户不必填数字;**未实现**,当前仅逐项自定义):`极致画质`(体积上不封顶、码率优先)/ `均衡`(默认,1080P 2~4GB)/ `省流量`(≤1.5GB、720P 可接受)。档位只是参数预填,可继续自定义。

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

### user_preference(用户默认偏好;未接入)

`uid`(UNIQUE)、`config`(TEXT JSON,字段与订阅 `filter_config` 同构 + 当前档位名)。**现状仅存储**:表与 `/preference` 读写在,但订阅筛选解析不消费、前端无入口;设计意图是订阅 `filter_config` 空维度继承本表、本表未设置再走系统默认(§11)——接入前订阅空维度一律系统默认。

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

保护措施:每日转存任务数上限(默认 20,防风控/防配额);账号 Cookie 失效 → 事件 + `ERROR`,不自动降级模式(用户决策)。备用路径:若 fork 的 copy 对某分享驱动不成熟,可启用 fork 内置的 `quark_share_direct` 等定向开关(P2 按盘优化)。**触发时机**(2026-08-26 补,见顶部状态):巡检完成(`check()` 尾部)与手动换源(`activateAsync`)后对 TRANSFER 订阅自动排队 `transferAsync`;编辑切入 TRANSFER 或转存目标账号变化在 `update()` 事务 `afterCommit` 后立即排队;每小时 :40 自愈 sweep 与手动按钮兜底。

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
| GET | `/preference` | 当前用户默认偏好(仅存储,未接入订阅筛选) |
| POST | `/preference` | 保存用户默认偏好(仅存储;新订阅预填未实现) |
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
- 新建对话框:名称 + 元数据条目搜索选择(全部/豆瓣/TMDB/Bangumi 源页签,显示来源徽章/总集数/更新状态,按编辑距离预选最优,可"更换条目")、筛选条件(自定义:盘类型偏好排序、清晰度下限、单集体积带、码率下限、关键词包含/排除;留空的维度走系统默认——用户默认偏好继承与预设档位未实现 §4.7)、模式单选(选"转存"时出现 DriverAccount 下拉,复用 AccountsView 数据源)、检查周期。
- 页头「偏好设置」入口(未实现):编辑用户默认偏好(档位 + 自定义参数),所有新订阅预填——`/preference` 接口已备,前端入口与预填逻辑待做。
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

### 11.1 全局资源筛选(msub_pool_filter,2026-08-28)

Setting 表单行 JSON(`dto/MediaSubscriptionPoolFilter`),web-ui「追剧设置-资源筛选」标签页编辑;巡检即读即用(无缓存,下轮巡检生效),坏配置回落空对象不炸巡检:

| 字段 | 语义 |
|---|---|
| includeKeywords | 硬门禁:非空时候选标题须至少含其一才入池(区别于订阅级 includeKeywords 仅加分);空 = 不限 |
| excludeKeywords | 硬拒绝:与订阅级排除词取**并集** |
| minQuality | 清晰度门槛(hd/fhd/uhd):仅拒标题**明确标注**低于门槛的,未标注放行(挂载前无从判断,避免误杀召回) |
| minEpisodeSizeMb | 单集体积下限:替换部署默认 20MB 底线;订阅级显式配置优先(调低覆盖底线,调高仍是偏好层) |
| maxEpisodeSizeMb | 单集体积上限:订阅级显式配置优先,否则回退全局;0 = 不限 |

消费点四路:fillPool 入池三道门(落选审计 EXCLUDED/缺包含词/清晰度不足,计数与样例进 POOL_FILLED 事件)、preview 同规(「预览看到的即能入池的」)、candidatesOrdered 存量复筛(配置收紧后池内已有资源不再被选为主源,行不删除;已挂载主源不经此路径,自然失效后按新规则换源)、episodeSizePolicy/maxEpisodeBytes 单集文件体积策略全局回退。

同批修复:订阅级 `filter.qualities`(编辑对话框「清晰度」多选)此前后端从未消费,现已接线为加分(命中 `quality.prefer` 默认 +10,权重表可调)。

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
