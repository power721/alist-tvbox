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
- 解析点(双层解析:凭证形态 `u-{username}-{vodSecret}` 经 `findUserByCredentialToken` 验真优先,
  裸 `u-{username}` 形态经 `findByUserVodToken` 回退——凭证形态整段含 '-' 不是合法用户名,顺序反了会误回落管理员,commit 1b4d8c58):
  - `SubscriptionService.checkToken`(内容接口放行;验真通过打 ThreadLocal `verifiedUserToken` 标记,
    凭证注入只认该标记。注意:异步/定时线程无该 ThreadLocal,`verifiedCredentialUidFor` 会静默降级 -1——
    行为正确(裸 u- 无熵不作凭证权威)但属双轨设计,新增消费点须自证线程上下文)
  - `SubscriptionService.playbackTokenForSubscription`(播放同步令牌归属)
  - `MediaSubscriptionService.resolveUid`(追剧订阅归属;另有 `resolveTokenUser` 不带首个 ADMIN 兜底,/p 代理用)
  - `LiveFollowService.resolveUid`(直播关注归属)
- `getTokens()` 对非 ADMIN 恒返回 `u-{username}`(不回退);「共享/空 token 回退首个 ADMIN」的回退语义只存在于
  resolveUid 系解析侧(消费侧),不下发侧。
- Tenant 的 include/exclude 以 token 字符串为键,用户隔离键即 `u-{username}`,管理员按此配置。

兼容性说明:此前"裸用户名即 token"的客户端 URL 在升级后失效,用户需在 web-ui 重新复制订阅地址。

## 3. 真正的多用户系统(2026-08-29 实现,口径见各节)

现状:uid 隔离覆盖追剧订阅、播放记录、偏好、用户级设置、盘线路 pid;其余均为全局共享。方向:

### 3.1 用户级配置(已实现,键级命名空间口径)

- 实现口径与原设计(Setting 表加 uid 列)不同:沿用 danmaku_config:u{uid} 键级命名空间先例,
  存 `{key}:u{uid}` 行,`SettingService.getUserSetting(key, uid)` 走「用户值→全局值」回退;
  findAll 一律剔除 `.*:u\d+` 行(不进设置页、不随配置下发)。
- 首批用户化:`msub_telegram_bot_token/chat_id`(TG 通知经 `MediaSubscriptionNotificationService`
  按订阅 uid 解析渠道)、`msub_pool_filter`(巡检 `poolFilterFor(subscription)` 按订阅 uid 回退)。
- 读写入口:`/api/user-settings/{name}`(ADMIN+USER,白名单 `SettingService.USER_SETTING_KEYS`);
  全局值仍走 `/api/settings`(仅管理员)。前端追剧设置对话框对 USER 只展示个人 TG 渠道+资源筛选,
  其余全局项隐藏;资源筛选未改动则跳过保存(防默认空配置覆盖全局门禁)。
- VOD 订阅(Subscription)加 `uid`(0=全局共享)——V38 已实现。

### 3.2 资源归属

- 云盘账户(DriverAccount/PanAccount 等)加 `owner uid`——§4 已实现(V37);`msub_transfer_root` 用户化待实现。
- 巡检 job 全局扫描不变(按订阅的 uid 分发上下文),通知走订阅人的渠道——已随 §3.1 落地。

### 3.3 播放与令牌(盘线路 pid 已实现)

- 盘线路 pid(365 天长效)加 `owner_uid` 列(V40,0=共享/存量):`fastDetail` 注册时带订阅 uid,
  同盘同路径已有行直接复用(归属不迁移,共享挂载共用路径是预期);`/p/{token}/{pid}` 校验
  token 用户与 pid 归属一致(用户级 token 解析 uid,全局/共享 token=管理级放行,共享行放行)。
- 按用户吊销:`/play-urls` GET/DELETE 收紧为登录态,USER 仅见/仅删自己的归属行,管理级全量。
- 播放令牌(PlaybackToken)已按 uid+scope 隔离;设备级粒度已在 `/api/playback/tokens` 具备。
- `DriveService.buildProxyUrl` 不再烘焙全局首个 token,按请求上下文的 currentToken 生成,杜绝分享外泄全局 token。

### 3.4 安全边界(已实现,首轮覆盖 admin 域)

- `@EnableMethodSecurity` 已开;admin 域 controller(User/Tenant/Log/Task/Site/ConfigFile/IndexFile/
  IndexTemplate/Plugin/System/Sync)类级 `@PreAuthorize("hasAnyAuthority('ADMIN','CLIENT')")` 兜底,
  例外:登录 `/api/accounts/login`、`/api/sync/validate` 方法级 permitAll;SettingController 因 GET 需对
  USER 开放白名单读取,写方法逐个方法级注解。USER 域(账号/订阅/追剧)靠 guard 隔离,暂不加注解(下轮)。
- `currentUid()` fail-closed:非管理级解析失败返回 `-1`(UNRESOLVED_UID 哨兵,canManage/canView/
  canUseCredentials 一律拒绝,canView 对共享账号也不可见),不再回落 uid=0 冒充管理归属;
  管理级(ADMIN/CLIENT)保持 0。`effectiveUid()` 供数据过滤场景(0=全量)。
- `History.uid` 默认值 1 已去掉,唯一构造点(PlaybackSyncService)显式 setUid。

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

