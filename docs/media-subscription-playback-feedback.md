# 追剧验证链强化:字节级探测 + 瞬时故障分级 设计(v3)

> 状态:**已实现(2026-08-22)**。方案一(verifyStream 三接入点)、方案二(classifyProbeFailure + streak)、
> 方案三(dead_link 90 天窗口)全部落地,单测 11 个新增,全量 723 绿。实现偏差:
> ① 判定矩阵把 500/其它 2xx/3xx 也归 INCONCLUSIVE(更保守);② GONE 词表补入 AList 真实报错
> "参数错误"/"不存在"/"object not found"(现有传染测试的样本消息);③ streak 达上限时**本次**即按失效处理
> 并清零重新计时。代码基线 `12fc43a9`:两级状态机(6cec78c9)与体验层(12fc43a9)已落地,
> `msub_episode_source` 成为可用性唯一记录点,v2 稿的**大部分内容被该架构结构性取代**。
> 本稿重新评估后聚焦剩余缺口,**无新表、无新列、无迁移**,改动集中在验证质量与误判防护。
> 机制来源:FongMi 端插件《豆瓣TMDB追更助手 v93》(字节级验活 :1970-2109 / :15481-15504、
> 失败分级 TTL :1943-1960)。上游设计:`docs/media-subscription-redesign.md`。

---

## 1. v2 → v3 重评结论

### 1.1 新架构已覆盖(本稿不再包含)

| v2 稿条目 | 被什么取代 |
|---|---|
| 归因表 `msub_play_log` + play_confirm/play_fail 资源列 | `msub_episode_source` 行级 `successCount/failCount`——取链时即知行,归因问题不存在 |
| effectiveScore 四点替换 | `playCandidates` 排序已消费运行时信号:VERIFIED > LISTED > 资源分 > 成功率 |
| demoteFailedSource 改造 | `recordPlayFailure`:行 FAILED + 资源降分 + 传染判定,已是想做的形态 |
| 播放确认钩子(≥5s) | 砍掉。取链回灌 + 字节级探测(本稿方案一)从服务端就分清"解析过"和"真出流",客户端进度确认的边际价值归零;"用户看没看"已由 `watchedEpisode`(History 只读)承担 |
| 半衰衰减 | 行随(集,资源)生命周期消亡,低 churn,不需要 |
| 周期复验的"24h 确认跳过" | `sampleMounted` 只挑 LISTED 行,VERIFIED 行天然不重探——结构性覆盖 |
| 失败阶梯(两集死→换源) | `contagion` 已实现(二次探测→整源死→retire→换源) |

### 1.2 剩余缺口(本稿内容)

1. **验证深度**:三个取链验证点(`preheatEpisodes`/`sampleMounted`/`contagion`)全部用
   `aListService.getFile` —— **解析级**。和谐资源的常见形态是"目录在、解析过、CDN 拉流 403/HTML",
   解析级探不出来,VERIFIED 的含金量停在"取到过链接"。
2. **瞬时故障误判**:全链路只有 THROTTLED 特判(不下结论);其余任何异常一律按失效处理。
   `dead_link` 是**跨订阅共享且无过期**的全局黑名单——传染探测/候选激活/补缺探测撞上一次网络抖动,
   代价从"单订阅 7 天冷却"升级为"所有订阅永久拉黑"。分类器的价值随黑名单的引入变大了。
3. **黑名单无自愈**:`dead_link` 只有 `time`/`fail_count`,查询不过期;判死一次即终身(顺带小项)。

---

## 2. 现状地图(12fc43a9,集成点速查)

- **集源行** `msub_episode_source`:(episode, resource) 唯一;`relPath`(换挂载点不失效);
  `state ∈ LISTED/VERIFIED/FAILED/MISSING`;`successCount/failCount/lastVerifiedTime`。
  "全系统唯一记录可用性的地方"——资源级可用性是派生量(整源死 = 全行 FAILED/MISSING)。
- **资源行** `media_subscription_resource`:`state ∈ CANDIDATE/MOUNTED/RETIRED/REJECTED`,
  只表达挂载生命周期;主源 = 挂订阅固定路径的 MOUNTED,补缺 = `.sources/` 下的 MOUNTED。
- **判决写入点**(只有取链事实能写 VERIFIED/FAILED):
  - `preheatEpisodes`(:860):新集首候选 `aListService.getFile` → `markVerified` / `markFailed`+传染;
  - `sampleMounted`(:940):每轮每挂载源抽 1 集 LISTED 行取链(最久未验者优先);
  - `contagion`(:909):失败后对同资源另一集二次取链——双死=整源 `retireResource`(全行 FAILED
    + `markDeadLink` 全局黑名单),单活=仅单集损坏;
  - `playEpisode`(MediaSubscriptionService:765):候选序 = 转存盘实时目录 → `playCandidates`
    (VERIFIED>LISTED>资源分>成功率);成功 `recordPlaySuccess`、失败 `recordPlayFailure`(立即
    markFailed + 传染,用户在场信噪比最高)。
- **误判入口**(非 throttle 异常一律最重处理):`activateNextCandidate`(激活失败→retire+黑名单)、
  `fillGaps` 的 `probeShare` catch(→retire+黑名单 quiet=true)、`contagion` 二次探测 catch(→retire+黑名单)。
- **复活机制**:FAILED 行换文件或 7 天回 LISTED(`syncInventory` :652-659);RETIRED 资源
  `badCooldownDays` 后允许重探(`isBadCooled`);**dead_link 无任何过期**。
- **通知/角标**:通知门槛 = 取链验证通过 + 已追平(§11);角标 🆕N 只认 VERIFIED 且未观看。

---

## 3. 方案一:字节级流探测(verifyStream)

### 3.1 语义

VERIFIED 的含义从"解析成功"升级为"**真实拉到媒体字节**"。不加新状态、不加新列:解析级验证过的
存量行无需迁移,语义只增强不回退;新验证一律字节级。

### 3.2 探测函数

```java
// CheckService 内;HTTP 抽象为可注入接口(单测):
interface StreamProbeClient {
    ProbeResult fetch(String url, String userAgent, int maxBytes, int timeoutSeconds);
}
record ProbeResult(int status, String contentType, byte[] body, long totalBytes) {}

/** @return VERIFIED(字节通过)/ FAILED(确证死链)/ LISTED(无结论,维持弱信号)/ TRANSIENT(瞬时故障,交方案二) */
String verifyStream(MediaSubscriptionResource resource, MediaSubscriptionEpisodeSource row)
```

步骤:

1. `aListService.getFile(site(), mountPath + "/" + relPath)` —— 与现有三验证点同源,拿 `FsDetail`;
   解析抛异常 → 异常分类(方案二),不在此判死;
2. `rawUrl = fixHttp(fsDetail.getRawUrl())`;**rawUrl 为空(代理型驱动,如阿里 open)→ 退回解析级结论**
   (与现状等价,不误判);
3. `Range: bytes=0-{streamProbeMaxBytes(4096)}` GET,UA 常量,超时 `streamProbeTimeoutSeconds(8)`;
   **不做**重定向跟随(重定向 URL 直接当结果判定)。

### 3.3 判定矩阵(保守取向:错放不错杀)

| 结果 | 判定 | 理由 |
|---|---|---|
| 200/206,Content-Type 非 `text/html` | **VERIFIED** | 真出流 |
| 200/206,`text/html` 且 body 含 `<html` | **FAILED** | 和谐登录页/风控页,最强死信号 |
| 404 / 410 | **FAILED** | 文件确已不存在 |
| 401 / 403 | **LISTED(无结论)** | 可能是 referer/UA/防盗链要求(夸克 `#x-referer` 一类),不是死信号;留给播放事实判定 |
| 超时 / 连接失败 / 5xx / DNS | **TRANSIENT** | 方案二处理 |
| 其它 2xx/3xx | **LISTED(无结论)** | 未知形态不下结论 |

错判保护:即使 FAILED 误判,现有复活机制兜底(换文件或 7 天回 LISTED);传染二次探测同样走
verifyStream,活源字节通过即翻案。FAILED 的代价(该集从该源下线 7 天)决定了矩阵必须保守。

### 3.4 接入点(三处替换,播放路径不动)

| 验证点 | 现调用 | 改为 |
|---|---|---|
| `preheatEpisodes` :879 | `aListService.getFile(...)` → markVerified/markFailed | `verifyStream` → 按返回值 mark;通知门槛随之升级为字节级 |
| `sampleMounted` :953 | 同上 | 同上(VERIFIED 含金量提升,角标/选源随之受益) |
| `contagion` :924(二次探测) | 同上 | 同上——整源判死必须基于字节级证据 |
| `playEpisode` :793 | `getPlayUrl`(取链即回) | **不加探测**:播放路径延迟敏感,且其成败由后续 preheat/sample 复验纠偏 |

预算不变:沿用现有 1 集/源·轮 + `preheatMaxPerRound`;字节探测在解析之后追加一次 4KB GET,
调用量与解析同级(仅对拿到 rawUrl 的盘多一次轻量 GET)。

### 3.5 风险

- **计次盘**(百度分享验链):调用量与现有解析级验证相同,无新增类别;
- **需要特定 header 的直链**:403 矩阵已置为"无结论",不会误杀;HTML 判死是唯一激进项,但有 7 天复活兜底;
- **代理型驱动退回解析级**:阿里等走本地代理的盘维持现状语义,不倒退。

---

## 4. 方案二:瞬时故障分级(classifyProbeFailure)

### 4.1 分类器

```java
enum ProbeFailure { THROTTLED, TRANSIENT, GONE }
static ProbeFailure classifyProbeFailure(Throwable e)   // 消息文本匹配,与 isThrottleError 同源方式
```

判定顺序:

1. **THROTTLED**:`isThrottleError(message)`(现有正则)—— 维持现状(盘退避,不下结论);
2. **TRANSIENT**:超时(`timeout`/`timed out`/`SocketTimeout`)、连接(`Connect`/`Connection refused`/
   `UnknownHost`/`NoRouteToHost`)、`502/503/504`、AList 整体不可用(`isAListHealthy()` 旁证,
   与主源列目录失败的既有防护同款);
3. **GONE**(默认):明确失效词(分享不存在/已失效/已取消/链接错误/提取码错误/expired/not exist/
   cancel/invalid)或其余未识别错误。

默认方向论证:TRANSIENT 误判的代价是晚一轮再探;GONE 误判的代价是 RETIRED + **全局黑名单**。
未识别错误默认 TRANSIENT,streak 封顶防僵尸(见 4.3)。

### 4.2 行为矩阵(五个误判入口)

| 入口 | 现行为(非 throttle) | TRANSIENT 时 | GONE 时 |
|---|---|---|---|
| `preheatEpisodes` catch | markFailed + 传染 | **不 markFailed**,跳过该集(下轮再来),streak+1 | 现行为(markFailed+传染) |
| `sampleMounted` catch | markFailed + 传染 | **不 markFailed 不传染**,streak+1 | 现行为 |
| `contagion` 二次探测 catch | markFailed + retire + 黑名单 | **不下结论**:返回 false(整源悬置),二次探测的 streak 单独计;第一行已落的 FAILED 维持(若它来自播放失败,信噪比高;若未落,本就无罪推定) | 现行为 |
| `activateNextCandidate` catch | retire + 黑名单(quiet) | **不 retire 不拉黑**,跳过该候选本轮,streak+1 | 现行为 |
| `fillGaps` probeShare catch | retire + 黑名单(quiet) | 同上 | 现行为 |
| `recordPlayFailure`(播放) | 立即 markFailed + 传染 | **维持立即 markFailed**(用户在场是最强信号;保护只作用于其触发的传染二次探测) | 现行为 |

### 4.3 streak(防僵尸候选)

连续 TRANSIENT 不应无限重试(每轮吃探测/激活预算)。内存计数:

```java
private final Map<Integer, Integer> transientStreak = new ConcurrentHashMap<>(); // resourceId → 连续次数
```

- TRANSIENT → `merge(id, 1, Integer::sum)`;达 `probeTransientStreak(3)` → 本次按 GONE 处理
  (走 markFailed/retire/黑名单,计数清零);
- 任何成功(验证通过/激活成功/探测成功)→ 清零;
- 重启归零可接受(内存态先例:`gapSearchRounds`/`mainDriveSearchTime`)。

---

## 5. 方案三(可选小项):dead_link 时间窗口

`dead_link` 无过期 → 判死一次终身拉黑。用现有 `time` 列(无 DDL):`isDeadLink` 查询只认
`deadLinkTtlDays(90)` 内的判死;过期记录保留(`fail_count` 承载历史),该链可被重新入池试错一次,
再判死则时间刷新。理由:链接失效是双向漂移(死链偶被分享主复活/网盘审核回滚),90 天窗口把
黑名单从"永久"改为"长冷却",与 BAD 冷却 7 天形成两级自愈。

---

## 6. 配置(AppProperties.Subscription,默认值即可,不进设置 UI)

```java
streamProbeMaxBytes     = 4096  // Range 探测字节上限
streamProbeTimeoutSeconds = 8   // 探测超时
probeTransientStreak    = 3     // 连续 TRANSIENT 升级为 GONE
deadLinkTtlDays         = 90    // 黑名单窗口(方案三)
```

## 7. 实施与测试

**改动面**:CheckService(verifyStream/分类器/三接入点/五误判入口/streak)、AppProperties 四项、
StreamProbeClient 接口 + 默认 OkHttp 实现。**无迁移、无实体变更、无 DTO/API 变更、无 web-ui 变更。**

提交切分(同分支顺序提交,各自可回滚):

1. verifyStream(字节探测 + 判定矩阵 + 三接入点替换 + StreamProbeClient 注入);
2. classifyProbeFailure(分类器 + 五入口矩阵 + streak);
3. dead_link 窗口(可选,与 2 独立)。

测试(`MediaSubscriptionCheckServiceTest` 增补,mock StreamProbeClient):

- 判定矩阵六分支(200/206、HTML、404、403 无结论、超时 TRANSIENT、rawUrl 空退回解析级);
- 三接入点行为:preheat 失败不通知/通知升级;sample 不误杀;contagion 字节翻案(二次探测通过=整源活);
- 分类器:THROTTLED/TRANSIENT/GONE 样例消息;TRANSIENT 在五入口的保护行为;
  streak 3 次升级;成功清零;
- 黑名单窗口:90 天内拦截、过期放行、再判死刷新时间(方案三)。

## 8. 后续候选(仍不做)

缺集区间标注(线路 remark "缺少 E02-E05、E08 等",插件 :14043-14102)、拒绝原因分桶审计
(:1106-1139)、successCount/failCount 半衰(行 churn 低,暂无必要)。
