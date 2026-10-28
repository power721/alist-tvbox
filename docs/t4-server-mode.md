# T4 模式(全走服务端)

> 2026-10-27 实现。目标:给没有 jar 加载能力的跨平台壳子(纯 HTTP TVBox 客户端、
> Node 版 TVBOX、iOS 壳等)一条零插件接入路;`/sub` 保持 type 3 不动。

## 入口

| URL | 形态 |
|---|---|
| `/t4/{token}/{id}` | T4 订阅配置(与 `/sub/{token}/{id}` 同源,多做一次站点变换) |
| `/t4/{token}/v/{site}` | T4 VOD 端点(FongMi type 4 五动作全打到这;site = 站点 key 去 csp_ 前缀,如 `AList`、`Media`;带前缀的 `csp_AList` 兼容) |

例:`http://192.168.50.60:4567/t4/Harold/0`,站点 api 形如
`http://host/t4/Harold/v/AList`。裸订阅(无 token)同 `/sub` 有 `/t4/{id}` 形态;
站点 api 里 token 段为 `-`。token 归一化、u- 凭证形态、个人订阅归属隔离与 `/sub` 同口径
(`SubscriptionService.subscriptionT4`)。

## 配置变换(applyT4)

内置 csp_* 站点 → `type: 4` + `api: {host}/t4/{embedToken}/v/{csp_Xxx}`,去掉 `jar`/`ext`;
`searchable/quickSearch/filterable/order` 等字段原样保留。**type 3 站点整体移除**(插件源/
WebHome/push/上游 spider——无 jar 壳子点开即废);type 0/1/2 的普通 HTTP 站点保留。
全局 `spider` 字段(spring.jar)一并移除——T4 壳子完全不下载 jar。

## VOD 端点契约(T4Controller,FongMi SiteApi 对齐)

| 动作 | 客户端请求 | 适配 |
|---|---|---|
| home | `GET api?filter=true` | 裸调用对应端点 + homeVideo 合并 `list`(推荐位) |
| 分类 | `?ac=detail&t={tid}&pg={页}&ext={base64UrlSafe(JSON筛选)}` | ext 解码:网盘族→`f` JSON;B站→FilterDto;直播→platform;豆瓣→sort/year/genre/region;片单/追剧→flattened Map |
| 详情 | `?ac=detail&ids={id}` | ids→id 参位(TG/盘搜/追剧族);TG 族自动补 `title`(标题缓存);片单导航(db:/tmdb:/s:/douban: 条目)委托 /media 片单详情=服务端代搜(indexs=1 仅 FongMi 遵守,通用壳子由服务端代劳搜片) |
| 搜索 | `?wd={词}&quick={bool}&pg={页}` | 透传;TG 族记录最近搜索词 |
| 播放 | `?play={id}&flag={线路}` | 网盘/TG/盘搜/追剧→`/play?id`;B站→`bvid=`(动作前缀由 /play 兜底归位,**X-CLIENT: t4** 身份触发服务端代理地址形态,见下);Emby/Jellyfin/飞牛→`-play?id&t=0`;直播→follow$/unfollow$/offline 服务端等价复刻,直链透传(`jx/parse=0`+移动端头) |

**B站服务端代理形态**:T4 播放请求以 `X-CLIENT: t4` 下发,`BiliBiliService.getPlayUrl` 据此把
MPD 每个 Representation 的 BaseURL(dash)或单文件直链(durl)改写为 `/live-proxy/{token}?u=...`
服务端地址;代理端对 bilivideo.com/bilivideo.cn/szbdyd.com 域族自动补 `Referer: bilibili.com`
(#1075 风控口径),客户端对 B站 CDN 零头要求,也免疫 FongMi type 4 用站点头覆盖播放响应头的问题。
设备端(T3)spring.jar VideoStreamProxy 的服务端等价物。

**直播服务端代理**:T4 直播详情按 `platform=web` 口径取地址——Twitch/SOOP 仅 web 形态走
`/live-proxy`,酷狗/Inke/看看/YY web 走纯 proxy 模式;虎牙恒 `/p`。播放直链透传(`jx/parse=0`
+移动端头,与 spring.jar Live.play 同构)。

逐参数对齐 spring.jar 各 spider(CatVodTVSpider)的回源调用;msg 动作(msubadd-/msubcheck-/
巡检/B站点赞投币收藏等)本就由 `/play/{token}` 服务端拦截,T4 天然全量可用。

## 已知取舍(T4 vs T3)

- **播放进度回传(PlaybackSyncer)没有**:它是 spring.jar 里的设备端组件;T4 无设备端代码。
  跨端壳子本来就没有,不算回退;要进度同步请用 `/sub`。
- **巡检/取消追剧的弹窗确认、原生 Toast 没有了**:动作直接执行,回执走 msg 通道显示。
- **本地分片代理(VideoStreamProxy)没有**:播放地址为服务端 `/p` 代理直链,本就无需客户端代理。
- **直播弹幕(LiveDanmaku)、B站 DASH 本地换线没有**。
- 插件源在 T4 配置里保留但无 jar 能力的壳子用不了(预期;等服务端插件运行时另议)。

## TG/盘搜族标题缓存

spring.jar 端爬虫持有 titleCache(浏览时捕获 vod_id→vod_name,详情回带 title 兜底)。服务端
等价:T4Controller 用 Caffeine(5k 条/2h)旁路捕获响应 list 条目,详情自动补 `title`;
另记最近搜索词作 `wd` 兜底。

## 红果密文流服务端解密(CENC 桥)

红果短剧/电影的媒体流是整段 CENC(cenc-aes-ctr)加密 MP4,设备端壳子无法解密。
插件(红果短剧.py)playerContent 返回的 url 带 `#hg-cenc={base64(spade_a[,audio][,audio_spade_a])}`
片段——T4 服务端识别并就地解密,链路:

1. T4Controller play 动作多带 `proxy_base` 参数(`/t4cenc/{token}/{site}` 桥地址);
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
