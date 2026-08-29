# 多用户系统设计与加固记录

日期:2026-08-29。本文档分两部分:已落地的加固(第一、二节)与真正的多用户系统设计(第三节)。

## 1. 已修复的漏洞(commit 本轮)

### 1.1 权限收紧(WebSecurityConfiguration)

- `POST /api/token`、`/api/settings/**` 的写方法(POST/PUT/PATCH/DELETE)收紧为 ADMIN|CLIENT。
  此前仅 `authenticated`,普通 USER 可改全局设置(含 tokens、basic_auth)与重置全局 vod token,影响全实例。
- `/api/local/db-test` 经核查已有一次性 X-BACKUP-TOKEN 校验(LocalApiController.requireBackupToken),此前的越权报告为误报,未改动。

### 1.2 vod token 双空间(见第 2 节)

裸用户名不再是合法 vod token,消除了"知道用户名即可拉取其内容"的旁路。

### 1.3 播放记录同步

- `TokenFilter.PLAYBACK_SYNC_PATHS` 补 `/api/playback/sync`:此前客户端以 `Authorization: Bearer <播放令牌>`
  调用该 webhtv 兼容端点会被过滤器提前 401,与 controller 支持的鉴权形式矛盾。
- `PlaybackSyncService.trimHistory` 去重身份加入 syncScope:uid 级 trim 覆盖全部分区,原身份只含
  (kind,key,vodId),同一剧集在两个分区的行会被判重误删。pull 页去重仍用原身份(展示层跨分区去重是期望行为)。
- 复核删除越权报告:scoped 播放令牌发 `scope=all` 只清自己分区(deleteAll 按 token 的 syncScope 查询),
  uid 级令牌清全部是文档化语义,无需修改。

### 1.4 mountPath 跨用户冲突

`buildMountPath` 的元数据标签是 doubanId/tmdbId(全局共享),两个用户订阅同一部剧会生成完全相同路径,
巡检按 mountPath 归属主源会互相劫持。修复:创建时 `existsByMountPath` 检查,占用则追加 ` u{uid}` 段消歧;
仅影响新建订阅,存量路径不动(固定路径不动的设计不变量)。

## 2. vod token 双空间(已实现)

设计目标:多用户内容隔离靠"每个用户独立 vod token"实现,且与全局共享 token 永不撞车。

规则:

- 用户级 token 形如 `u-{username}`(前缀常量 `Constants.USER_TOKEN_PREFIX`)。
- 全局 tokens 在 `updateToken` 保存时静默过滤 `u-` 前缀(不报错——报错即预言机,会泄漏全局 token)。
- 解析点(共 4 处,全部走 `UserService.findByUserVodToken`):
  - `SubscriptionService.checkToken`(内容接口放行)
  - `SubscriptionService.playbackTokenForSubscription`(播放同步令牌归属)
  - `MediaSubscriptionService.resolveUid`(追剧订阅归属)
  - `LiveFollowService.resolveUid`(直播关注归属)
- `getTokens()` 对非 ADMIN 返回 `u-{username}`;共享/空 token 仍回退首个 ADMIN(全局订阅是共享资源)。
- Tenant 的 include/exclude 以 token 字符串为键,用户隔离键即 `u-{username}`,管理员按此配置。

兼容性说明:此前"裸用户名即 token"的客户端 URL 在升级后失效,用户需在 web-ui 重新复制订阅地址。

## 3. 真正的多用户系统(设计,待实现)

现状:uid 隔离覆盖追剧订阅、播放记录、偏好三块;其余均为全局共享。方向:

### 3.1 用户级配置(最高优先级)

- 设置命名空间:Setting 表加 `uid` 列(0=全局默认),读取链路 `用户值 → 全局值` 回退。
  首批用户化:`msub_telegram_bot_token/chat_id`(TG 通知按订阅人发送)、`msub_pool_filter`(候选打分偏好)。
- VOD 订阅(Subscription)加 `uid`(0=全局共享),共享订阅保留现行为,个人订阅仅本人 token 可见。

### 3.2 资源归属

- 云盘账户(DriverAccount/PanAccount 等)加 `owner uid`(0=ADMIN 全局);追剧订阅的 accountId 选择范围
  限定为本人可见账户。转存根目录 `msub_transfer_root` 用户化。
- 巡检 job 全局扫描不变(按订阅的 uid 分发上下文),通知走订阅人的渠道。

### 3.3 播放与令牌

- 盘线路 pid(365 天长效)加 uid 列,`/p/{token}/{pid}` 校验 token 用户与 pid 归属一致;支持按用户吊销。
- 播放令牌(PlaybackToken)已按 uid+scope 隔离;补充设备级粒度(令牌可命名/单独吊销)已在 `/api/playback/tokens` 具备。
- `DriveService.buildProxyUrl` 不再烘焙全局首个 token,按请求上下文的 currentToken 生成,杜绝分享外泄全局 token。

### 3.4 安全边界

- 引入方法级 `@PreAuthorize` 作为 URL matcher 的兜底(默认拒绝策略分阶段推进,先给 admin 域 controller 补注解)。
- `currentUid()` 解析失败一律拒绝请求,不再回落 uid=0(fail-closed)。
- `History.uid` 去掉默认值 1,强制显式赋值。

## 4. 网盘账号多用户化(2026-08-29 已实现)

修订(受"服务器带宽受限,用户主要用 TvBox 直连"约束,并核实消费端 spring.jar 自带
VideoStreamProxy 本地多线程分片代理):**直连优先,凭证不下发给非归属人**——
- play 响应默认直链+公开 header(UA/Referer 均为公开常量,不算凭证);阿里去掉 FongMi 强制代理分支。
- 唯一携带凭证的类别是夸克/UC 自有文件直链(需 Cookie):归属人(ownerUid==token uid)直连+Cookie;
  非归属人改走 `/p` 服务端代理。`credentialUid()`/`SubscriptionService.credentialUidFor(token)` 统一判定,
  共享 token=管理员设备=管理级。
- multiUrls 多账号分片(header 含各账号 Cookie)只对管理级下发;普通用户单 url。
- 落地清单:V37 迁移三表 owner_uid/shared(存量 0/true 无感)、AccountAccessGuard(管理/可见/凭证三级判定+脱敏)、
  三个账号控制器按 currentUid 隔离(USER 全类型可添加本人账号)、tokenm 与 ALI_TOKEN/QUARK_COOKIE
  配置注入按 token 归属过滤、转存目标强制本人账号(全局账号仅 admin 订阅)、删用户级联删个人账号、
  web-ui 三账号页加归属列+按钮门禁+配置入口仅管理员。`isLocalProxyEnabled` 全局代理开关保留作带宽兜底。

### 4.5 隔离矩阵(实现口径)

| 能力 | ADMIN/共享 token | USER(自己账号) | USER(全局 shared 账号) |
|---|---|---|---|
| 账号 CRUD | 全部 | 仅本人 | 只读脱敏 |
| 直连+Cookie | 可 | 可(泄漏给自己) | **禁止**,走 /p 代理 |
| multiUrls 分片 | 可 | 不可(单 url) | 不可 |
| 追剧转存目标 | 全部 | 本人 | 仅 admin 自己的订阅 |
| tokenm/配置注入 | 全部凭证 | 仅本人凭证 | 不可见 |

