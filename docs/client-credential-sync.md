# 第三方插件网盘凭证同步开关(#1084)

## 背景

有用户反馈:使用第三方订阅线路期间,夸克网盘出现 `Aowu`、`Quarktemp` 等本人未点播过的转存目录。排查链路:

1. 订阅配置里的内置站点(以及合并进来的插件站点)在客户端初始化时,spring.jar 会以 ext 里的 `secret` 调服务端 `GET /cookies/{secret}`;
2. 服务端把网盘账号凭证(夸克/UC/115/百度/阿里/迅雷/天翼/123/139/光鸭/夸克TV/UCTV 及 B站 Cookie)打包返回;
3. spring.jar 把返回内容写入**进程内所有第三方插件的兼容位置**(饭太硬 spUtils、摸鱼 moyu/、潇洒 /sdcard/TVBox/、王二小 SharedPreferences、VOX /sdcard/VOX/、OK影视 /sdcard/TV/、嗷呜 aowu/),供这些插件免扫码登录;
4. 同进程的第三方 JAR 即使未被用户使用,也可能在加载时读取这些文件并执行后台转存。

关键事实:spring.jar 自身**不读**这些兼容文件,内置小雅的网盘解析/播放全部经服务端代理(凭证不出服务端)。`/cookies` 下发的全部内容(含 B站 Cookie)都纯粹是第三方插件兼容写入——因此**在服务端收口即可完整止血,且对所有已部署的存量 spring.jar 版本立即生效**。

## 控制:总开关 + 插件 × 网盘矩阵

入口:网页「网盘账号」页 → 「配置」→ 「凭证同步」tab。

### 总开关 `client_cookie_sync`(默认关,显式 `true` 才开启;无需重启)

注意措辞口径:开关只控制**后续**下发(关闭后各端点立即停发),**不会清理**客户端已写入的凭证文件,也不使其失效——彻底清除见文末清理指引。

关闭后收口的下发面:

| 下发面 | 位置 | 关闭后行为 |
|---|---|---|
| `/cookies/{secret}`(spring.jar 拉取主通道) | `ShareService.getCookies` | 合法密钥也返回空 `{}`;spring.jar 拿到空响应即不写任何兼容位置,且服务端不查询任何账号 |
| `/quark|uc|115|baidu/cookie/{secret}`(legacy 单盘直链) | `ShareService.getXXXCookie` | 返回空串;客户端缓存的旧注入直链随之失效 |
| `/ali/token/{secret}`、`/ali/open/{secret}` | `AccountService.getAliRefreshToken/getAliOpenRefreshToken` | 合法密钥返回 null(不下发) |
| `/bili/cookie/{secret}` | `BiliBiliService.getBiliBiliCookie` | 返回空串 |
| `/pg/lib/tokenm`(PG/猫源 drpy 的 tokenm.json) | `PgTokenController.tokenm` | 只回模板与 data/tokenm.json 覆盖段,不注入任何账号凭证 |
| 订阅配置 ext 注入(`/ali/token`、`/quark/cookie` 直链替换 tvfan 占位符、csp_Bili cookie 原文) | `SubscriptionService.replaceAliToken` | 整体跳过,占位符原样保留,第三方插件按未配置降级(扫码/手贴登录) |

注:系统设置页「Cookie地址」的链接指向上述直链端点,开关关闭后同样返回空(这是同一批端点,不是独立的手动通道)。

### 范围边界:只管被动注入,不管主动拉取

开关的设计边界是**被动注入**——spring.jar 初始化时拉 `/cookies` 把凭证写进进程内**所有**插件的兼容位置,哪怕用户从未使用那些插件(#1084 的原问题:凭证因此流向未选用的插件并产生 Aowu/Quarktemp 转存)。

以下**主动拉取**通道**不在开关范围内**(用户自行添加对应订阅/站点即视为授权):

- 饭太硬 `/tvfan/config?token=…`(`TvFanController.config`,主动请求 token/ali/quark/baidu/uc/ucTv)
- 真心 `/zx/config?token=…`(`ZxConfigController.config`,主动请求 aliToken/quark/115/baidu/uc/ucTv/189/123/139)
- 猫源配置的 `ALI_TOKEN`/`ALI_OPEN_TOKEN`/`QUARK_COOKIE` 占位符替换(`SubscriptionService` 猫源模板填充)

即:关掉开关后,这些接口仍按原样返回凭证——插件是用户自己选择安装使用的,其配置接口取用凭证属于既定功能,不是本开关要拦截的注入行为。

### 矩阵 `client_cookie_scope`(总开关开启时生效的细粒度策略)

设置值形态(Setting 键 `client_cookie_scope`,JSON,默认行保存时自动剔除):

```json
{"fty": {"enabled": false}, "xs": {"drives": ["quark", "bili"]}}
```

- **行键(第三方插件)**:`fty`(饭太硬)/`fish`(摸鱼)/`xs`(潇洒)/`wex`(王二小)/`vox`/`ok`(OK影视)/`aowu`(嗷呜)/`pg`(PG,猫源 tokenm)。
- **行规则**:`enabled`(默认 true)+ `drives` 允许同步的网盘白名单(**空 = 全部**)。行缺失 = 允许全部(现行为)。
- **网盘词表**(即 `/cookies` 顶层键):`quark/uc/baidu/115/ali/xunlei/189/123/139/guangya/quarkTv/ucTv/bili`,另 `pikpak` 仅 pg 行有效(tokenm 特有)。
- **执行位置**:
  - 前 7 行由 spring.jar 执行:服务端把矩阵作为 `/cookies` 响应的 `syncPolicy` 键下发(只发非默认行;全默认/未配置则不下发),spider 按行裁剪每个 CookieTarget 的写入视图(裁剪对 target 透明:所有 target 只从传入 JsonObject 取值)。旧版 spring.jar 忽略未知键 → 矩阵需客户端更新到新版 spring.jar(服务端升级重启后,客户端重新拉取订阅即可获得新 jar)。
  - `pg` 行由服务端 `PgTokenController` 直接执行(tokenm 按 `syncDrive(drive)` 逐盘判定),保存后立即按新范围执行。
- **不归矩阵管的通道**:legacy 单盘直链(`/quark/cookie` 等 4 个)、`/ali/token`、`/bili/cookie`、`replaceAliToken` 注入——它们只受总开关控制(无法按插件归属)。播放器私有 `files/bili`(通用兼容位置,无插件归属)同样只跟总开关。

## 不受影响的部分

- 内置小雅的网盘解析与播放(全走服务端 `/p` 代理与 `/vod` API)。
- 内置 B站站点的登录态功能(走服务端 `/bilibili/{token}` API,Cookie 在服务端侧使用)。
- 用户自行添加的插件的主动配置接口:饭太硬 `/tvfan/config`、真心 `/zx/config`、猫源占位符替换(见上节范围边界)。
- 播放记录同步、追剧等所有其它功能。

## 已写入客户端凭证的清理指引(开关不做自动清理)

关闭开关只停止**后续**下发,客户端已写入的文件不会被自动删除(部分文件可能是用户自己在第三方插件里扫码登录的真实会话,自动删除会误伤;矩阵收窄后不在白名单内的旧文件同理保留)。彻底清除:

1. 删除播放器应用数据(最彻底),或手动删除以下路径:
   - 外部存储 `/sdcard/TVBox/`(潇洒:quark_cookie.txt、uc_cookie.txt、baidu.txt、bili_cookie.txt、uc_token.txt、xunlei.txt、cloud189.txt、guangya.txt、aliyun.txt)
   - `/sdcard/TV/`、`/sdcard/VOX/`
   - 播放器私有目录下 `files/aowu/`、`files/moyu/`、`files/TV/`、`files/bili`
   - 饭太硬:spUtils 的 `quark_ck/uc_ck/quarkSt/ucSt`;wex2:`NewWexFnw_preferences` 的 `Wex_*` 键
2. 若怀疑凭证已被第三方插件滥用(如出现 `Aowu`、`Quarktemp` 等陌生转存目录):**重新登录对应网盘账号**使旧 Cookie 失效,并删除陌生目录。

## 实现要点(代码导航)

- `AppProperties.clientCookieSync/clientCookieScope`;`SettingService` 装载/更新(坏矩阵 update 400,启动装载回落空)。
- `dto/ClientSyncRule`:`SPIDER_PLUGINS`/`PLUGIN_PG` 常量 + `allowsDrive/isDefault` 语义。
- `ShareService.buildSyncPolicy` 只发非默认行;spider 端 `Init.writeCookies/filterCookies`(CatVodTVSpider)按 `CookieTarget.id()` 裁剪。
- 测试:`ShareServiceClientCookieSyncTest`(服务端)+ `InitTest.writeCookiesAppliesSyncPolicyMatrix`(spider)。
- 已知无关问题:系统设置里的「订阅替换阿里token地址」(`replace_ali_token`)开关历史上从未被消费(死设置),本开关与其无关,未顺手改动以免改变存量行为。
