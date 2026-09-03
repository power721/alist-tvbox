# 追剧搜索按订阅生效盘定向(含磁力/ed2k)

> 状态(2026-09-02):**已全部实现(未提交)**——磁力/ed2k 候选收割(§4,ed2k 同权消费;§4a
> 站点源磁力产出:观影 downlist/盘链 links/盘聚 seed 两跳/6V磁力 详情表格)+
> 盘定向主体(§3:`SearchTargets` 值对象贯穿,TG-Search/盘搜 cloud_types 服务端定向,
> 站点源盘检前过滤,专项搜索扩 magnet+ed2k 双类型)。决策 2/3/4 均按推荐落地。

## 1. 问题

追剧搜索(`fillPool` / `preview` → `searchAllSources` 八路并发)目前全量召回,不做订阅级盘定向:

- **TG-Search**(`searchTgSearchApi`)与**盘搜 PanSou**(`RemoteSearchService.search`)的服务端都支持 `cloud_types` 过滤,但当前用**全局 `tg.drivers`** 口径,与订阅实际能用的盘无关。
- **站点源**(玩偶/盘链/观影/蜗牛/盘聚/6V磁力/123臻藏/123社区/夸父)与**电报网页源**无服务端定向能力,结果全量返回。
- 域外盘结果的三重浪费:
  1. tg-search 的 `limit`(searchSize)配额被域外盘结果吃掉,白名单盘反而可能被挤出;
  2. 站点源结果**全部**送盘检(`filterInvalidPanSouLinks`),域外盘烧盘检账号配额;
  3. `fillPool` 入池时才丢弃(audit `OFF_POOL`),为时已晚。
- **磁力**口径缺失:没开磁力兜底的订阅,magnet 结果是纯噪声;开了兜底的订阅,磁力结果其实是有效召回,通用搜索若按盘收窄后语义需要明确 magnet 的去留。

## 2. 定向集口径(单一事实源)

```
搜索定向集(仅追剧搜索侧生效)
  = 主网盘(订阅级 main_drives > 全局 msub_main_drives)
  ∪ 扩展网盘(全局 msub_extended_drives)
  ∪ (磁力兜底生效 ? {magnet, ed2k} : ∅)
```

- **磁力兜底生效谓词与 `magnetFallback` 同源**(CheckService 3514 行):
  `subscription.isMagnetOffline() && MODE_TRANSFER.equals(mode) && offlineDownloadService.isConfigured()`
  —— 开关开了但离线未配置/非转存模式的订阅,磁力结果同样不可消费,不并入。
- **候选池白名单 `allowedCandidateDrives`(主∪扩展)语义不变**:magnet/ed2k 不是盘,永不进入入池/探测/换源/补线的白名单判定。
- **ed2k 已是消费形态**(2026-09-02 代码已落地):离线链接谓词 `isOfflineLink` = `magnet:` ∪ `ed2k:`
  (115/迅雷/光鸭离线均支持 ed2k 提交),`submitFirstMatchedMagnet` 全链路(标题/预筛/提交三态)两种链接同权——定向集与消费形态对齐,一并并入。

新口径在 `MediaSubscriptionCheckService` 上收敛为一个方法(如 `searchTargetTypes(subscription)`),盘部分复用 `allowedCandidateDrives`,magnet 部分按上述谓词追加;表示为盘 key 集合 + `"magnet"` 伪 key。

## 3. 各搜索源怎么定向

| 源 | 服务端定向 | 本地过滤 |
|---|---|---|
| TG-Search | `cloud_types` 覆盖参数:盘白名单非空时**替换**全局 `tg.drivers` 口径;磁力兜底生效时追加 `magnet`+`ed2k` | 聚合出口统一过滤 |
| 盘搜 PanSou | `request.cloudTypes` 同上口径 | `addMergedMessages`/结果循环的 `tgDrivers` 门禁替换为定向集数值类型 |
| 站点源×9 | 无能力 | **盘检送检之前**按定向集剔除(省盘检配额,这是最大收益点之一);磁力见 §4a;123臻藏/123社区(含123)/夸父(含夸克)整源门控见 §4a 末尾 |
| 电报网页源 | 无能力 | 聚合出口统一过滤 |
| 磁力专项 `searchMagnets` | 现状单查 `cloudType=magnet`(见 §8 决策 4) | 不动(绕过所有盘门禁,round≥2 按集精确搜索) |

### cloud_types 组装规则(防"离线-only 收窄"事故)

```
pan 部分 = 白名单非空 ? 白名单映射 : 全局 tg.drivers 映射(= 现状)
最终列表 = pan 部分 ∪ (磁力兜底生效 ? {magnet, ed2k} : ∅)
仅当 pan 部分非空时发送 cloud_types
```

关键护栏:**pan 部分为空时绝不发送 `[magnet, ed2k]` 纯离线列表**——那会把网盘结果全部裁掉。pan 不限时服务端本就返回磁力/ed2k,口径由本地门禁保证即可。两个 API 的类型映射表已存在且一致(`PanSouClient.cloudType` / `TelegramService.getCloudType`:`quark/189→tianyi/139→mobile/duck→guangya/…`,`magnet→magnet`、`ed2k→ed2k`)。

### 本地门禁的替换语义

- `TelegramService.filterAndSort` 与 `RemoteSearchService` 的 `isEnabledDriver` 现状:**磁力/ed2k 恒放行**(pansou merged 路径)+ 全局 `tg.drivers` 门禁。定向模式下以定向集为准:magnet/ed2k 是否放行**完全看订阅磁力兜底开关**。
- 订阅白名单比全局 `tg.drivers` 宽时(如 `tg.drivers` 只配了夸克、订阅扩展盘有 UC):定向集**替换**全局口径,UC 能召回——现状会被全局门禁误杀,这是语义修正而非回归。
- `fillPool` 入池处的 `PAN_TYPES`(NON_PAN)与 `driveAllowed`(OFF_POOL)过滤**保留**:magnet 行被路由到磁力候选收割(§4)后照常跳过入池,盘白名单过滤作为纵深防御与审计口径不动。

## 4. magnet/ed2k 结果的消费 —— 方案 A,已实现(未提交)

> 2026-09-02 代码已落地(与磁力兜底同批工作树改动),as-built 如下;方案 B 作废。

- **收割**:`fillPool` 结果循环的 NON_PAN 分支,`isOfflineLink`(`magnet:`/`ed2k:`)命中即 `collectMagnetCandidate`——
  按订阅内存缓存(上限 50 条,link 去重,超出截最旧),不入池、audit 仍记 NON_PAN。
- **消费**:`submitMagnetForEpisode` 先 `submitFirstMatchedMagnet(fromPool)`,无可用项才发专项 `searchMagnets`。
- **共用门禁**:`isOfflineLink` 谓词、`magnetTitle`(磁力 dn= 优先,ed2k 取 `|file|文件名|` 段)、`magnetExcluded`/
  `matchesTitle`/`MagnetResolver` 文件列表预筛、提交三态与三档配额——magnet 与 ed2k 同权,测试覆盖
  (`MediaSubscriptionMagnetFallbackTest.ed2kCandidateIsCollectedAndSubmitted`)。
- **缓存生命周期**:跨轮留存(无轮末清理),封顶 50 自然滑窗;兜底未生效的订阅收了也无人消费,无害——
  定向集(§2 生效谓词)落地后,域外 magnet/ed2k 在搜索侧就不召回,该路径自然消失。
- 收益窗口有限的事实不变:`fillPool` 有可用候选即跳过搜索,兜底轮大多仍走专项 `searchMagnets`。

### 4a. 站点源的磁力产出(2026-09-02 追加,已实现未提交)

> 站点源此前只产出可挂载网盘分享,磁力在源头即被丢弃;磁力兜底开启后这些源的磁力
> 资源应进入 §4 的收割管道。atv-spiders/py 的对应爬虫(观影/盘链/盘聚/6V磁力)是契约参考。

| 源 | 磁力形态(契约) | 实现要点 |
|---|---|---|
| 观影 | 详情 `/res/downurl` 同一响应的 `downlist.list.{m,t}`:btih 哈希+种子名平行数组 | `magnetsFromDetail`:哈希小写、长度 ≥8(py 同口径),折 `magnet:?xt=urn:btih:{hash}&dn={种子名}`(种子名剥开头杂符+压空白,py `_clean_name`);**零额外请求** —— 顺手解析同一详情响应 |
| 盘链 | `search_pan_links` 的 `data.{group}.links[]`:`url` 为 `magnet:`/`ed2k:` 直链,或 token 经 `/api/go.php` 302 解出 | `messagesFromGroups` 非数字 type 分支产出离线 Message;资源标题(`title` 字段,剥「介绍:」尾巴与 HTML 标签,py `_clean_link_title`)进 `content` —— 磁力标题门禁的回落口径 |
| 盘聚 | 详情页 `.seed-list .seeds` 行(href 含 `seed_id=`)→ 站内中转页(两跳)脚本里的磁力 | `parseSeedRows`(seed_id 去重、href 剥裸 Unicode 的 movie_title)+ `resolveSeedLink`(明链正则优先,`const data="base64"` 密文解码兜底,py `_extract_download_link`);**每行一次真实请求** —— `search(keyword, includeOffline)` 按磁力兜底开关门控,预算独立(`MAX_SEED_RESOLVES=3`/详情页,不挤占网盘中转配额) |
| 6V磁力 | 帝国 CMS 站(xb6v.com,免登录):POST `/e/search/11index.php`(py 版 `1index.php` 已随改版 404)→302→ result 页卡片 → 详情页下载表格;**磁力为主、少量网盘资源同页混排** | `Xb6vSearchService`:磁力行(a[href^=magnet:],自带 `dn=` 种子名解码优先,回落行文本)+ 网盘行(parseType 数字盘型,提取码多内嵌 `?pwd=`),所在资源组组头(前向最近 strong 行,如「幕兰之战 年番4」)并入 content 供集数分组打分;磁力上限 `xb6vMaxMagnets=50`/详情页(长番磁力可达数百条) |
| 123臻藏 | WordPress+Zibll 站(123.qsxy.top,详情正文需 Cookie,含 wordpress_logged_in;「评论后可见」自动发评论解锁):搜索页 `?s=` 匿名卡片 → 详情正文 `wp-posts-content`;**123 盘为主、混少量其它盘/磁力/ed2k**(属性 href/data-clipboard-text + 裸 URL 正则,golink= base64 中转解码,提取码折 `?pwd=`/115 `password=`,付费/推广地址块清单剔除) | `ZhenCangSearchService`:Setting `zencang_host`/`zencang_cookie`(无 Cookie 静默关闭,py 的每日签到不搬);**整源门控按盘定向而非磁力开关**——`targets.drives()` 显式包含 `123` 才搜(123 主题站,订阅不定向 123 时产出几乎全被闸门裁掉;白名单空的不限盘口径同理不搜) |
| 123社区 | Xiuno BBS「123分享社区」(123panfx.com/pan1.me 双站探活):搜索 `/search.htm?keyword=` AJAX JSON(手机 UA+XHR,**匿名可用**)→ 帖子 `thread-{id}.htm` 楼层 div.message 前 3 层;**纯 123 盘产出** —— share123 正则+通用 URL 过滤,全部规范化收敛 `https://123pan.cn/{s\|123pan}/{key}?pwd=`(镜像域名统一,提取码 URL 自带优先否则 key 后 60 字符窗口) | `Pan123CommunitySearchService`:Setting `pan123community_host`/`pan123community_cookie`(Cookie **可选**——匿名可搜可提取非隐藏帖;「请回复后再查看」帖配 Cookie 才自动回复解锁:POST `/post-create-{tid}-1.htm`,**站点发帖间隔冷却内直接跳过不发**(py 的 sleep 等够不搬:阻塞共享搜索线程不值,下轮巡检自愈),每搜索至多回复一次,成功后 1.2s 重取);**整源门控同 123臻藏**(`drives` 含 `123` 才搜,两源同一谓词同一用户定规);登录墙帖(无权访问文案)静默跳过 |
| 夸父 | Xiuno BBS「夸父资源社」(kfzy.net):搜索 `GET /search-{kw}-1.htm` HTML 页(帖子列表,置顶/屏蔽词过滤)→ 帖子详情;**夸克为主、混 UC/阿里/天翼/123/115/百度/迅雷** —— 链接提取<b>四级回退</b>(jar k()):①正文 alert 块(剥免登流量提示语,待登录/立即回复/VIP会员等提示语不算)→ ②alert 只剩纯码时去 a[href] 配对 → ③整页正则(锁贴真实链接常泄漏在 JSON-LD [ttreply],**匿名可抓**;quark/uc/ali/189/115 规范重建,123 系按 key 回原文匹配,百度/迅雷为 py 移植版补充)→ ④仍空扫 div.message a[href] 网盘域+父文本补码 | `KuafuSearchService`:Setting `kuafu_host`/`kuafu_cookie`(Cookie **可选**——第③级匿名可抓锁贴泄漏链接;回复解锁同 123社区 冷却跳过口径,正文「待登录」=Cookie 失效该帖跳过);屏蔽词表(jar 静态表,福利/写真/成人词)标题/角标命中整条丢弃;提取码折 `?pwd=`(115 `password=`);**整源门控 `drives` 含 `quark` 才搜**(夸克主题社,同 123 系门控范式,用户定规按主题盘定向) |

统一闸门:五源产出的 magnet/ed2k Message(type=`magnet`/`ed2k`)走既有 `retainTargetTypes`
(兜底未开即剔除,盘检 `selectCheckable` 本就跳过离线类型)+ fillPool 的 NON_PAN 收割,
与 TG 源磁力完全同管道;`preview`(offlineIncluded=false)不受影响。观影/盘链磁力与网盘
链接同响应零额外请求,故不设开关参数;盘聚 seed 是真实网络成本,由 `searchAllSources` 传
`targets.offlineIncluded()` 决定是否发起;**6V 整源门控**——磁力为主、网盘只是少量顺手
产出,订阅磁力兜底未生效时整路不搜(不为几条网盘链接白付一路搜索 + N 个详情页请求);
**123臻藏/123社区整源门控按盘定向**(`targets.drives().contains("123")`,用户定规「订阅
包含 123 网盘才搜索」)——123 主题站,订阅不定向 123 时整路不搜;**夸父同理按夸克主题
门控**(`drives` 含 `quark`,夸克主题社混多盘,产出仍过 retainTargetTypes 闸门)。

### 4b. 手动磁力补缺(2026-09-03 追加,已实现)

> 网页端贴磁力/ed2k 直接触发离线补缺——用户自己找到的链接不必等自动兜底轮次。入口:
> 集数清单抽屉与媒体详情抽屉的「磁力补缺」按钮,共用对话框(磁力链接 + 可选集号)。

- **端点**:`POST /api/media-subscriptions/{id}/magnet`,body `{url, episode?}` →
  `MediaSubscriptionCheckService.submitManualMagnet`。鉴权/归属与其它订阅端点同口径(ADMIN/USER,uid 隔离)。
- **候选搜索/解析**(同日追加):`GET /{id}/magnet/search?keyword&episode`(关键词空=订阅 seasonKeyword,
  集号拼进搜索词)——**五路并发**:TG-Search 专项 `searchMagnets` + 磁力站点源 6V/观影/盘聚/盘链
  (复用 `searchAsync` 并发池+90s 硬顶,`respectBackoff=false` 手动点击不过巡检退避闸门;盘聚 seed
  解析无条件开,凭证缺失/源关闭自然空结果,**不做剧名/集号/排除词门禁——用户自己挑**,只滤
  `isOfflineLink` + 跨源 link 去重 + source 标注)与 `POST /{id}/magnet/resolve`(body `{url}`,
  `MagnetResolver` 拉种子解文件列表,文件名过 `parseEpisode` 按本剧季口径标集号;失败 resolved=false
  带原因,结果走 7 天缓存)。前端「入库」直接复用 submitMagnet 端点。
- **门禁:仅全局离线下载已配置**(用户定规,不限订阅 mode/磁力兜底开关——手动是明确意图,
  与自动兜底 `magnetFallbackEnabled` 的 TRANSFER+开关门禁分开);不受轮次/冷却/三档配额闸,
  但正常消耗月计数(task 行带 subscription_id)。
- **三态**:`submitMagnet` 同管道——COMPLETED 立即 `harvestCompletedProduct` 入账
  (响应带覆盖集列表;门禁拒入账时 episodes 空并在 message 说明);SUBMITTED 发
  `TYPE_MAGNET_SUBMITTED` 事件(文案带「手动」)等巡检收割;FAILED 透传网盘错误。
  重贴同一磁力 = urlHash COMPLETED 短路后再收割一次,可重试此前未入账的产物。
- **PENDING 感知收割**(顺带救自动路径):`doCheck` 开头(季对齐/清理之后、refreshMetadata 前)
  增 `isConfigured() && hasPendingTask(subscriptionId)` → `harvestOfflineProducts(sub, Set.of())`
  ——该订阅有未收割 PENDING 离线任务就先扫产物目录,不再受 magnetFallback 的 round/冷却门控
  (那些防的是自动提交烧配额,不是防收割);没开磁力兜底、主源挂不上 early return 的订阅也能收割手动产物。
- **null-episode 结算**:手动集号留空(整季/多集种子按文件名自动识别)时 task 行 episode=null,
  不被 `settlePendingTask`(按 episode 匹配)结算 → pending 闸门永久占位。`registerOfflineResource`
  收割结算循环后补 `settleManualPendingTask`(该订阅最新一条 episode=null PENDING 结算到本次产物)。
- 测试:`MediaSubscriptionManualMagnetTest`(三态/门禁/非 TRANSFER 允许/收割接线/搜索关键词回落与过滤/
  多源合并去重+source 标注/单源失败容忍/解析集号标注,17 例)、
  `OfflineDownloadServiceTest.settleManualPendingTask*/hasPendingTask`。

## 5. preview(候选预览)

无订阅上下文:定向集 = 全局 主∪扩展,**不含 magnet/ed2k**(preview 是分享候选预览;磁力有独立的提交/配额语义,不适合进 preview 打分)。"磁力预览"如需要另议。

## 6. 兼容性

- **主/扩展均未配置**(白名单空):盘侧完全不定向、不发送 cloud_types(= 现状);magnet 侧仅由本地门禁按开关放行——行为对存量部署无变化。
- **观影路径不受影响**:TG 观影搜索(`TelegramController /search`)、盘搜观影(`searchPg`)继续走无定向参数的旧签名;`telegramService.search`/`searchAggregated`/`remoteSearchService.search` 旧签名保留,新定向参数以重载/追加参数形式提供(实现下沉私有核心方法,避开 spy 自调用委托坑)。
- **磁力专用搜索与离线配额/三态语义零改动**:`searchMagnets`、`submitMagnet` 三态、三档配额、收割对账全部不动。
- 磁力兜底产物行(`source=magnet`, shareId=null)与 Share 语义隔离不变。

## 7. 实现改动面(已全部落地)

| 文件 | 改动 |
|---|---|
| `DriveId` | `toTypeLeniently`(未知标识返 null,坏配置不炸搜索) |
| `domain/SearchTargets` | 定向集值对象:`drives`(空 = 不限盘)+ `offlineIncluded`;严格门禁(站点源)/全局口径门禁(TG 聚合出口)两套语义 |
| `MediaSubscriptionCheckService` | `searchTargetTypes`(`allowedCandidateDrives` ∪ 兜底生效?{magnet,ed2k});`magnetFallbackEnabled` 谓词(magnetFallback 改为复用);`searchAllSources` 增定向集参数;站点源 `retainTargetTypes`(盘检送检前剔除);`preview` 传主∪扩展(不含离线) |
| `TelegramService` | `searchAggregated` 改四参签名(唯一调用方是追剧);`search` 增定向重载(核心下沉 `doSearch`,观影走旧签名);`filterAndSort` 定向版;`searchTgSearchApi` 增 cloud_types 覆盖参数;`searchMagnets` 扩 magnet+ed2k 双类型(决策 4) |
| `RemoteSearchService` | `search` 增定向重载(核心下沉 `doSearch`);`resolveCloudTypes`(白名单映射/全局口径 + 离线追加 + pan 空不发送护栏);`mergedTypeAllowed`/`resultTypeAllowed`(targets==null 逐字保留存量差异) |
| 站点源三文件(§4a) | `GuanYingSearchService.magnetsFromDetail`(downlist 磁力哈希→magnet+dn);`PanLianSearchService.messagesFromGroups` 磁力/ed2k 分支(title 进 content);`PanjuSearchService.parseSeedRows`/`resolveSeedLink`/`search(keyword, includeOffline)`(seed 两跳按开关门控);`searchAllSources` 给盘聚传 `offlineIncluded` |
| `ZhenCangSearchService`(123臻藏) | 新站点源(2026-09-02,commit 84d8de33):契约照 atv-spiders/py/123臻藏.py;`searchAllSources` 整源门控 `drives` 含 `123` 才搜,searchExecutor 7→8,权重 `source.zencang=12`,AppProperties `zencangEnabled/zencangMaxDetailPages/zencangTimeoutSeconds`,web-ui 追剧设置加「123臻藏」tab(zencang_host/zencang_cookie)+ 权重表;测试 `ZhenCangSearchServiceTest` ×10 |
| `Pan123CommunitySearchService`(123社区) | 新站点源(2026-09-02,commit 6f201ae1):契约照 atv-spiders/py/123社区.py;与 123臻藏共用 `drives` 含 `123` 门控谓词,searchExecutor 8→9,权重 `source.pan123community=12`,AppProperties `pan123communityEnabled/MaxDetailPages/TimeoutSeconds`,web-ui 追剧设置「123社区」tab(pan123community_host/pan123community_cookie)+ 权重表;测试 `Pan123CommunitySearchServiceTest` ×9 |
| `KuafuSearchService`(夸父) | 新站点源(2026-09-02,同批工作树):契约照 atv-spiders/py/夸父.py;整源门控 `drives` 含 `quark`,searchExecutor 9→10,权重 `source.kuafu=12`,AppProperties `kuafuEnabled/MaxDetailPages/TimeoutSeconds`,web-ui 追剧设置「夸父」tab(kuafu_host/kuafu_cookie)+ 权重表;测试 `KuafuSearchServiceTest` ×10 |
| 测试 | 21 处 `searchAggregated` 桩补参;新增 `SearchTargetsTest`(口径真值表)、`RemoteSearchServiceTest` ×3(cloud_types 请求体 + 离线-only 护栏 + merged 结果门禁)、`MediaSubscriptionCheckServiceTest` ×4(定向集传参/兜底开关并离线/站点源盘检前剔除/站点源磁力收割+兜底未开零收割);三源磁力用例 ×7(观影 downlist 纯函数+端到端、盘链磁力/ed2k+标题清洗、盘聚 seed 解析/开关门控/离线端到端) |

## 8. 决策点(全部落定)

1. ~~方案 A 还是 B~~ —— **方案 A,已实现(含 ed2k)**。
2. **生效谓词**(`magnetFallbackEnabled`:开关 + TRANSFER + 离线已配置,与兜底触发同源)。
3. **preview 不含 magnet/ed2k**(无订阅上下文,分享候选预览)。
4. **专项 `searchMagnets` 已扩为 magnet+ed2k 双类型**(cloud_types 覆盖参数传双元素列表)。
