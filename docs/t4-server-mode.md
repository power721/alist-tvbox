# T4 模式(全走服务端)

> 2026-10-27 实现;2026-10-29 重新设计(独立 token/独立地址/全局开关/代理策略)。
> `/sub` 保持 type 3 不动。

## T4 是什么(定位)

T4 = **控制面中转**:壳永远只跟本服务器说话——元数据、鉴权、站点请求全部由服务端代劳,
客户端只要会发 HTTP、会放流,零运行时依赖(不需要 jar/dex 加载器、不需要设备端 spider)。

由此得到两个等价的价值(用户口径):

- **通用性好/多平台**:T3 的 spider 要在设备上跑 jar(本质是设备端 Java 代码),PC 应用、
  网页壳、iOS/Node 壳没有这个运行环境;T4 的站点就是一组 URL,任何实现 TVBox 协议
  type 4 的客户端都能接。
- **老设备也能播**:没有 jar 下载/加载、没有 Chaquopy/QuickJS 冷启,老盒子弱壳直接可用。

媒体流是**数据面**,是中转模型里唯一可以不经服务器的部分(见「播放代理策略」);
控制面(凭证、风控头、解密、插件执行)恒在服务端。

## 入口

| URL | 形态 | 状态 |
|---|---|---|
| `/t4/{t4token}` | **独立 T4 配置(推荐,唯一入口)**——与订阅无关 | 新增 |
| `/t4/{t4token}/v/{site}` | T4 VOD 端点(FongMi type 4 五动作;site = 站点 key 去 csp_ 前缀) | 新增 |
| `/t4/{token}/{id}`、`/t4/{id}` | 旧订阅形态(vod token + 订阅号)/裸订阅形态 | **兼容保留,已废弃;`t4_standalone_only` 开启后 404** |
| `/t4cenc/{token}/{site}/**` | 插件密文流解密桥(token 段三形态通吃) | 不变 |

**为什么独立地址**:T4 配置与订阅零相关——上游订阅站点全部 type 3,`applyT4` 一律删除,
任何订阅号取出的 T4 配置等价(14 个内置站 + 就绪时的插件源),订阅号是死参数。

**T4 专用 token**(`t4_token`,Setting 键):实例级随机串,启动自动生成;设置页可**自定义**
(4-64 位字母/数字/中划线/下划线,字母或数字开头——纯数字拒绝,避免遮蔽旧 `/t4/{订阅号}`
单段形态;`u-` 前缀拒绝,用户 token 保留空间)或重新生成随机串。
独立于 vod token 体系:

- 只授权 `/t4` 配置与五动作这个面;接口地址被到处转发是 TVBox 常态,专用 token 泄露
  只废 T4,轮换不牵动主 token(反之主 token 也不嵌进 T4 配置地址)。
- 命中后服务端换算回背后 vod token(安全订阅开启时取首个共享 token)再进业务;
  play 响应内嵌的 `/p/{vodToken}` 与 T3 spring.jar 同口径(凭证不出服务器)。
- `/t4cenc` 桥地址嵌的是配置同款 token(T4 面);`/t4/{token}/v/{site}` 旧 vod token 形态继续可用。

设置页(系统配置 → T4接口):**启用开关**(默认开)、**播放代理策略**、地址展示/复制/重新生成。

## 配置变换(applyT4)

内置 csp_* 站点 → `type: 4` + `api: {host}/t4/{token}/v/{csp_Xxx}`,去掉 `jar`/`ext`;
`searchable/quickSearch/filterable/order` 等字段原样保留。**type 3 站点整体移除**(插件源/
WebHome/push/上游 spider——无 jar 壳子点开即废);type 0/1/2 的普通 HTTP 站点保留。
全局 `spider` 字段(spring.jar)一并移除——T4 壳子完全不下载 jar。

独立地址形态下以空上游生成自配置再变换(`SubscriptionService.standaloneT4`),
不再挂订阅号;全局覆盖配置(applyGlobalConfig)照常生效。

## 播放代理策略(t4_proxy_mode)

控制面恒走服务端;数据面分两档:

| 档位 | 行为 |
|---|---|
| `all`(默认,**智能代理**) | 需要服务端代劳的才代理:B站播放地址改写为服务端代理(`/proxy/{token}/{playId}` 短地址,回源解析延后到拉流,通用壳子对 CDN 零头要求);直播按 `web` 口径(见下);网盘凭证族 `/p` 中转。**不是"所有字节经服务器"**——免头可直连的本就直连(见「恒直连」) |
| `direct`(**直连优先**) | B站出原始 durl 直链 + 脱敏 header(Referer/UA,**剔除整串 Cookie**,凭证不出服务器);直播详情按直连口径(Twitch/SOOP 裸直链、酷狗/映客/LOOK/YY 走 dual 双线路=直连优先+代理兜底、虎牙恒 /p 不变);插件无凭证屏障盘直链直出。字节流尽量不经服务器,省带宽;依赖壳子按 play 响应 header 带头的能力,带头能力差的壳子可能播不出——所以是 opt-in |

**恒代理、不可策略化**(没有直连可能或凭证硬规):

- 网盘/TG/盘搜 `/p`(「凭证不出服务器」硬规);存储 **302 模式**是现成的直连开关
  (302 下 `/p` 只发重定向不耗服务器带宽),T4 不另造。
- CENC 解密桥(解密只可能在服务端)。
- **凭证屏障盘**(直连形态 header 携带账号 Cookie 的盘):非 `x-referer=raw` 标记的
  夸克/UC——T4 一律 `/p` 中转(Cookie 不下发,中转由 AList 侧自带凭证),任何代理策略下
  都不直连。

**恒直连、两档策略都不代理**(免头直连本就可播,任何壳子拿到 URL 直接拉流;套代理只烧
服务器带宽,兼容性零增益):

- 直播直连族:**网易CC、B站直播、快手、抖音、AcFun、六间房**(实现上 client 被忽略,
  恒裸直链);斗鱼无租约形态时亦直连。
- 网盘非凭证族:`/p` 对它们恒 302 重定向,字节本就直连 CDN。

### 插件播放的直连延伸(t4_play_type)

插件源(服务端 Python 运行时)的播放链路经 `/play` 取网盘直链,`t4PluginExtend` 向插件
payload 注入 `t4_play_type`(Atvp 透传为 `/play` 的 type 参数),`TvBoxService` 据此分流:

| 身份 | 行为 |
|---|---|
| `t4`(智能代理,默认) | 凭证屏障盘强制 `/p`(旧形态在未配本地代理的实例上会直连+Cookie 下发,已收口);其余盘与既有口径一致(本地代理开关开着则 `/p`,否则直链+非密 header)。**multiUrls 不下发**(分片 header 含各账号 Cookie,只给 spring.jar VideoStreamProxy 消费) |
| `t4-direct`(直连优先) | 凭证屏障盘强制 `/p`;其余盘一律直链直出 + 非密 header(UA/Referer)——本地代理开关不再强制 `/p`(T4 壳子没有 VideoStreamProxy,`/p` 对这些盘也只是 302 一跳),字节流直连 CDN 省带宽 |

自解析型插件流(如红果的 CDN 直链)本就原样透传不经 `/p`;CENC 密文流仍走解密桥,
两者与代理策略无关。

## 本地加速与 T4 的关系(社区生态,2026-10-29 群聊定稿口径)

T4 协议本身不含客户端多线程加速——社区主流是**壳子自带**:安卓端在 T4 配置里挂
jar 跑本地代理(如 不夜 T4+jar、猫源 index.js),其他平台靠壳子/播放器自身加速
(uz 等)。带 jar 能力的客户端「T4 元数据 + jar 本地加速」的形态即所谓「半T4」。

本系统的对应划分:

- **要本地加速(分片/多线程/进度回传)→ 用 `/sub`(T3)**:spring.jar 的
  VideoStreamProxy 就是社区说的「jar 本地加速」,现成且更完整。
- **要跨端轻量零运行时 → 用 `/t4`(T4)**:配置不带 jar(带 jar 等于把 jar 下载与
  jar 加载器依赖请回来,T4 的存在意义就没了);有能力多线程的壳子拿到直链后自行加速,
  与协议不冲突。
- 两者站点同 key,观看历史互通,同一台设备可并存。

「直连网盘挺快」对应的是存储 **302 模式**(字节流直连 CDN,零服务器带宽),
与 `t4_proxy_mode` 正交——网盘直连不归 T4 策略管。

协议谱系备注:TVBox 配置协议有 T1(xml)/T2(json爬虫链接)/T3(jar 本地爬虫)/
T4(HTTP API 服务端爬虫);T4 是影视系(FongMi)接口协议的一种。猫源(nodejs)本质是
T3 本地包形态(爬虫被转译成 nodejs 在端上跑),在线猫源接口(豆佬式)服务端只提供 js
下载,仍是 T3。

## VOD 端点契约(T4Controller,FongMi SiteApi 对齐)

| 动作 | 客户端请求 | 适配 |
|---|---|---|
| home | `GET api?filter=true` | 裸调用对应端点 + homeVideo 合并 `list`(推荐位) |
| 分类 | `?ac=detail&t={tid}&pg={页}&ext={base64UrlSafe(JSON筛选)}` | ext 解码:网盘族→`f` JSON;B站→FilterDto;直播→platform;豆瓣→sort/year/genre/region;片单/追剧→flattened Map |
| 详情 | `?ac=detail&ids={id}` | ids→id 参位(TG/盘搜/追剧族);TG 族自动补 `title`(标题缓存);片单导航(db:/tmdb:/s:/douban: 条目)委托 /media 片单详情=服务端代搜(indexs=1 仅 FongMi 遵守,通用壳子由服务端代劳搜片) |
| 搜索 | `?wd={词}&quick={bool}&pg={页}` | 透传;TG 族记录最近搜索词 |
| 播放 | `?play={id}&flag={线路}` | 网盘/TG/盘搜/追剧→`/play?id`;B站→`bvid=`(动作前缀由 /play 兜底归位,**X-CLIENT: t4/t4d** 身份触发代理/直连形态);Emby/Jellyfin/飞牛→`-play?id&t=0`;直播→follow$/unfollow$/offline 服务端等价复刻,直链透传(`jx/parse=0`+移动端头) |

**B站智能代理形态**(t4):playerContent 顶部短路直出 `/proxy/{token}/{playId}` 短地址,
回源解析延后到拉流(`client="t4p"`,每次拉流取新鲜 durl,url 时效+PCDN 回避),
`t4ProxyUrlCache` 按 playId 键短缓存(TTL 跟随直链 deadline,不越过失效点);
代理端对 bilivideo.com/.cn/szbdyd.com 域族自动补 `Referer: bilibili.com`(#1075 风控口径)。
设备端(T3)spring.jar VideoStreamProxy 的服务端等价物。

**B站 DASH 高画质**:跟随 B站设置「强制dash视频格式」(`bilibili_dash`)——开启后 t4/t4d
不再强制单文件 durl(封顶 1080P),改出 DASH(1080P+/4K/HDR):t4 跳过短地址短路(其契约只装
一条 URL,装不下分离的音视频两轨),出**代理化 MPD**(data: 形态,BaseURL 走 /live-proxy,
壳子零头要求不变);t4d 出原始两轨直链+脱敏头。t4p 恒单文件。要求壳子内核支持音视频分离
(exo 系),系统播放器/ijk 播不出——默认关。服务端**零转码、不需要 ffmpeg**:DASH 两轨本就是
独立的 fMP4 直链,服务端只代理地址(Range 转发+补 Referer);需要 ffmpeg 的只有「弱壳也要
高画质」的服务端实时合成(双倍带宽过服务器+封装延迟+seek 难,不做)。

**直播智能代理形态**(web):Twitch/SOOP 仅 web 形态走 `/live-proxy`,酷狗/Inke/看看/YY web
走纯 proxy 模式;虎牙恒 `/p`。播放直链透传(`jx/parse=0`+移动端头,与 spring.jar Live.play 同构)。

逐参数对齐 spring.jar 各 spider(CatVodTVSpider)的回源调用;msg 动作(msubadd-/msubcheck-/
巡检/B站点赞投币收藏等)本就由 `/play/{token}` 服务端拦截,T4 天然全量可用。

## 服务端 Python 插件运行时

插件源(spider 插件)在运行时就绪时同样 T4 化:api 指回 `/t4/{token}/v/{插件站点 key}`,
T4Controller 转投 PluginWorkerService(t4worker.py 行协议,常驻 worker 串行、闲置回收、
死亡自愈)。缺 python3/依赖时插件源不进 T4 配置(优雅降级)。插件 extend 剔除
local_proxy_config 与 preheatUrl(设备端专属字段),播放出 `/p` 服务端形态。

## 红果密文流服务端解密(CENC 桥)

红果短剧/电影的媒体流是整段 CENC(cenc-aes-ctr)加密 MP4,设备端壳子无法解密。
插件 playerContent 返回的 url 带 `#hg-cenc={base64(spade_a[,audio][,audio_spade_a])}`
片段——T4 服务端识别并就地解密,链路:

1. T4Controller play 动作多带 `proxy_base` 参数(`/t4cenc/{token}/{site}` 桥地址,
   嵌配置同款 token);
2. worker(t4worker.py)play 后处理:url 含 `#hg-cenc=` 且有 proxy_base 时,用
   spade_a 派生内容密钥(derive_content_key,算法 vendor 自 atv-player `cenc.py` →
   `scripts/t4cenc.py`)建 `CencRangeReader` 流式解密会话,lazy 起 127.0.0.1 随机端口
   HTTP 服务,把 url 改写为 `proxy_base + /cenc/{token}.mp4`;
   分离音频(payload.audio,电影 DASH 形态)另建会话并附 `audio_url` 字段;
3. 客户端按 Range 拉流:请求 `/t4cenc/{token}/{site}/cenc/{sess}.mp4` →
   T4Controller 校验 token → `PluginWorkerService.forward` 转到 worker 本地端口
   (行协议 port 方法取端口),Range 透传,206/200 流式回传——seek 即拉即解。

细节:Range 末端超媒体总长须钳制(播放器常发 bytes=0-65535)而非 416;worker 闲置
回收时 CENC 会话随进程消亡,播放中会话有流量不受影响(60s 读超时兜底)。
桥查找键与 pluginVod 注册 worker 的键严格同源:URL 路径段原样透传,勿补 csp_ 前缀。

## 源可见性与多入口分站点集

### 订阅白/黑名单(不同入口返回不同站点,复用 T3 机制)

T4 订阅形态 `/t4/{token}/{订阅号}` **天然继承 T3 订阅 override 的站点过滤**:
`subscriptionT4` 先跑完整 `subscription()`(含 `resolveAndApplyFilters` 白/黑名单)再
`applyT4` 变换,顺序保证过滤先于 T4 化。要「不同 token(地址)返回不同站点」:建多个订阅,
各自在「覆盖配置」里写——

```json
{"sites-whitelist": ["csp_AList", "csp_PianDan", "插件站点key"]}
```
或
```json
{"sites-blacklist": ["私有插件key1", "私有插件key2"]}
```

- 优先级:订阅白名单 > 订阅黑名单 > 全局白名单 > 全局黑名单;白名单模式忽略黑名单(同 T3)。
- key = 站点 key:内置源 `csp_*`(订阅源管理里的固定 key),插件源 = 配置里的站点 key
  (externalId,无则 `plugin-{id}`,从 `/sub` 配置 JSON 可查)。
- 语义同 T3:这是**配置层定制**(不同地址下发不同站点列表),不拦五动作直连——硬隐藏见下。

### 订阅源管理「T4」开关(硬边界)

每个源(内置源与插件源)有独立的「T4」开关(plugin.t4_visible,V57 迁移,默认开)。
关闭后该源不出现在**任何** T4 配置——独立地址 `/t4/{t4token}` 与订阅形态走同一
applyT4 变换(内置站点直接删,插件源不 T4 化随 type 3 移除);插件源同时关闭五动作直连
(`/t4/{token}/v/{key}` 404,防配置隐藏后仍被直连调用)。TVBox 订阅(T3)不受影响,
插件在 T3 照常下发。自定义网页源(csp_WebHome)本就无 T4 适配,开关置灰。

三层关系:**「T4」开关(硬边界,插件含五动作)> 订阅白/黑名单(每入口定制,配置层)>
独立地址 `/t4/{t4token}`(无订阅维度,恒全量,只受「T4」开关)**。

## 关闭 T4

设置键 `t4_enabled`(默认开)。关闭后 `/t4/**`、`/t4cenc/**` 全部 404(配置、五动作、
解密桥、旧订阅形态一并关闭)。

## 仅独立地址(部分用户可用 T4)

设置键 `t4_standalone_only`(默认关)。开启后旧订阅形态——`/t4/{vod token}/{订阅号}`、
`/t4/{订阅号}` 及其五动作 `/t4/{vod token}/v/...`、CENC 桥——全部 404,T4 只经
`/t4/{t4token}` 独立地址开放。用途:T4 token 只发给允许用 T4 的少数人(发谁谁能用,
可随时重新生成轮换),其余用户持有 vod token/订阅号也只能走 `/sub`。服务层
`subscriptionT4` 同步拒绝(控制器守卫的双保险);`standaloneT4` 不受影响。

## 已知取舍(T4 vs T3)

- **播放进度回传(PlaybackSyncer)没有**:它是 spring.jar 里的设备端组件;T4 无设备端代码。
  跨端壳子本来就没有,不算回退;要进度同步请用 `/sub`。
- **巡检/取消追剧的弹窗确认、原生 Toast 没有了**:动作直接执行,回执走 msg 通道显示。
- **本地分片代理(VideoStreamProxy)没有**:智能代理模式下播放地址为服务端 `/p` 代理直链,
  本就无需客户端代理;直连优先模式下字节流直接走 CDN。
- **直播弹幕(LiveDanmaku)、B站 DASH 本地换线没有**(T4 族恒单文件 durl)。

## TG/盘搜族标题缓存

spring.jar 端爬虫持有 titleCache(浏览时捕获 vod_id→vod_name,详情回带 title 兜底)。服务端
等价:T4Controller 用 Caffeine(5k 条/2h)旁路捕获响应 list 条目,详情自动补 `title`;
另记最近搜索词作 `wd` 兜底。
