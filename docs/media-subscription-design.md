# 追剧系统(自动追更)设计

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

### 4.6 搜索源:盘搜 / TG-Search 频道 / 电报网页搜索 / 玩偶聚合站 / 盘链 / 观影 / 蜗牛

分享资源来自七类来源,前三个已接入现有代码;玩偶聚合站、盘链、观影、蜗牛(均 2026-08-22)分别是 atv-spiders `py/玩偶聚合.py`、`py/盘链.py`、`py/观影.py`、`py/蜗牛.py` 的 Java 移植:

| 来源 | 现有实现 | 特性 | 在本系统中的角色 |
|---|---|---|---|
| 盘搜 PanSou | `RemoteSearchService`(`appProperties.panSouUrl`) | 聚合多频道/站点,一条结果含多个链接(带密码/时间/来源),自带 `/api/check/links` 校验 | 已配置时的常规搜索首选;候选池广度的主要来源 |
| TG-Search 频道 | `TelegramService.searchTgSearchApi`(`{tgSearch}/api/search`) | 结构化最好:`cloud_types` 按盘类型过滤,`media` 元数据(title/year/episode/quality)可免挂载预打分 | 未配 PanSou 时的常规搜索首选;打分元数据的主要来源 |
| t.me 网页抓取 | `TelegramService.searchFromWeb`(Jsoup 并行抓 `telegram_channel` 表中 webAccess 频道) | 零外部依赖、开箱即用;慢、易风控,覆盖面取决于频道表 | 兜底来源;缺集补搜聚合模式的补充源 |
| 玩偶聚合站 | `WanouSearchService`(11 个玩偶系 MacCMS 网盘站:玩偶/多多/木偶/欧歌/至臻/蜡笔/二小/虎斑/小斑/快映/闪电) | 网页抓取:并行按站搜索 → 卡片标题粗匹配 → 抓详情页提取网盘分享链接(行内提取码折 `?password=`);域名由监控服务下发最新可达地址,逐域名 failover + 成功粘滞;CF 挑战页识别为失败 | 候选池补充源:`fillPool`/`preview` 与 TG 三级回退结果按 link 去重合并(`searchAllSources`),实测 7+ 站可达、单剧 30+ 分享 |
| 盘链 | `PanLianSearchService`(需登录的分享聚合站,JSON API 搜索/取链) | **凭证必须用户自配**(Setting `panlian_username`/`panlian_password` 或 `panlian_cookie`,站点 `panlian_host` 可覆盖,追剧设置对话框编辑;**不内置共享账号**,未配置时源静默关闭);账号密码 multipart 登录(Cookie 内存缓存、响应"请先登录"自动重登、失败 5min 冷却防撞墙);链接两态:直链与 token(token 经 `/api/go.php` 302 解析真实分享链,手机 UA+`skip_go_warning`);结构化 password 按盘折 `pwd=`(百度/迅雷/123)/`password=`(115) | 候选池补充源:同经 `searchAllSources` 按 link 去重合并 |
| 观影 | `GuanYingSearchService`(需登录的分享聚合站,8 个电影名中文域名镜像:教父.com/星际穿越.com 等) | **凭证必须用户自配**(Setting `guanying_username`/`guanying_password` 或 `guanying_cookie`,站点列表 `guanying_host` 可覆盖;未配置时源静默关闭);反爬两道:PoW 工作量证明(`y=x^(2^t) mod N`,BigInteger.modPow;挑战特征判定中 `_obj.` 在场优先视为正常数据页)与登录态(nologin/未登录 自动重登,失败 5min 冷却);多镜像逐个 failover+成功粘滞;搜索 = HTML 内嵌 `_obj.search` 平行数组,空则回退 `/res/search_suggest`(**匿名可用**);盘链取 `/res/downurl/{type}/{rid}` 的 `panlist.url/name/p`,提取码折 `?password=` | 候选池补充源:同经 `searchAllSources` 合并;实测未登录可拿 suggest 条目(难哄 tv/g22b),downurl 需真实登录 |
| 蜗牛 | `WoniuSearchService`(service.sitesearch,MacCMS 衍生影视站,双线路 wn4k.com/zmi.kdns.fr 并发测速取最快+粘滞,失败换线) | **凭证必须用户自配**(Setting `woniu_username`/`woniu_password` 或 `woniu_cookie`〔须含 user_check〕,站点 `woniu_host` 可覆盖测速;未配置时源静默关闭);**游客可搜但网盘链接打码成 `https://******(登录后可见)`,必须登录取链**;登录 POST `/user/login.html`(user_name/user_pwd)→ code=="1",只保留 user_check/user_id/user_name 最小凭证集;详情页打码即登录态失效 → 自动续期一次,失败 10min 冷却;搜索 `/vodsearch/-------------/?wd=`(翻页走路径槽位),卡片 `a.video-card`;盘链候选 = `pan-link-btn@href` + `pan-link-meta` 文本,含 `*`/未知盘过滤 | 候选池补充源:同经 `searchAllSources` 合并;实测 wn4k 可达(zmi 本机不可达自动跳过),假 Cookie 详情全打码正确返回空 |

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
