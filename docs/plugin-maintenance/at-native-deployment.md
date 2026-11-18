# AT 原生受控切换与回滚

仅供 AT 内置模式使用；独立工作台不提供这些宿主写接口。
功能默认关闭，通过 `MAINTENANCE_AT_NATIVE_DEPLOYMENT_ENABLED=true`
显式启用。启用功能不等于获准修改生产，生产操作仍须另行授权。

## 输入与范围

使用已有发布准备计划，不重新生成模型提案或重复执行已完成的隔离验收。
计划必须保持当前、候选已通过运行验证且 `localApplyEligible=true`，
冻结绑定的插件 ID 和基线源码摘要必须与目标 AT 插件一致。
当前只处理数据库正文为明文 Python 的 `.py` 插件；不改写或重新签名密文包，
不写外部插件文件，也不承诺 AT 全实例恢复。

所有端点只接受 AT ADMIN 会话，写请求需要现有 CSRF 令牌，响应禁止缓存。
摘要不返回源码、连接凭据或初始化配置。
内置 AT 的令牌会随认证请求轮换，管理前端在每次写入前重新获取令牌；
不会自动重发失败或响应丢失的写请求。独立工作台保持原有令牌缓存方式。

```text
POST /api/plugin-maintenance/at-native/plugins/{pluginId}/deployments
GET  /api/plugin-maintenance/at-native/release-plans/{planId}/target
GET  /api/plugin-maintenance/at-native/plugins/{pluginId}/deployments
GET  /api/plugin-maintenance/at-native/deployments/{id}
POST /api/plugin-maintenance/at-native/deployments/{id}/rollback
```

内置页面在每条维护计划的“当前 AT 部署”折叠区提供应用、状态刷新和恢复原版本。
目标接口直接读取计划冻结的 `repair.frozen.baseline.binding.plugin.id`，
返回当前正文摘要、实际装载代次、启用状态和持久操作记录；不返回正文或凭据。
独立工作台不显示这些宿主操作。部署功能未启用时仍可只读查看状态。

切换请求包含 `planId`、`expectedFrozenSha256`、`expectedPluginSha256`、
`expectedWorkerGeneration`、`interruptIssuedPlayback` 和 `requestKey`。
未运行时预期代次为 null；已运行时必须提供当前只读身份中的真实代次。
同一插件和 requestKey 重试只读取原意图，不重放已经发生的外部操作；
同键不同请求返回冲突。

回滚请求包含 `expectedWorkerGeneration` 和 `interruptIssuedPlayback`。
有正在执行的 Spider 动作时拒绝切换；已有播放取链或代理活动时，
必须显式确认允许中断。当前不实现旧播放会话的跨代次保活。

## 生效与恢复

H2 新增独立 V60 迁移，已有 V59 保持原字节。
先持久化 `PREPARED` 意图和基线，再取得维护窗口、确认旧代次及装载摘要、
停止旧 worker，原子保存候选正文和 `SAVED` 状态。
通过现有 T4 EXT 构造与实际 Python loader 初始化后，核对真实源码摘要，
再记录 `DEPLOYED` 和新代次；数据库保存成功本身不算生效。

初始化失败时尝试恢复精确基线正文并重新装载，成功记 `ROLLED_BACK`。
若正文或配置已被其他操作改动，不覆盖第三方修改，保留
`RECOVERY_REQUIRED`。HTTP 200 只表示返回了意图，必须检查 `state`；
不能把 `REJECTED`、`RECOVERY_REQUIRED` 当作切换成功。

中断后的 `PREPARED`、`SAVED`、`ROLLING_BACK`、`RECOVERY_REQUIRED`
意图会继续阻止该 siteKey 的普通动作与代理进入。重启不自动重放切换，
管理员读取原意图后显式回滚。每个插件同一时刻只保留一个活动部署，
成功部署的记录继续占用活动位供回滚，回滚完成后释放。
阻断期间如 worker 仍存活，状态仍保留其真实代次供显式恢复；
这不表示源码健康或解除普通动作的阻断。页面在写响应丢失时只读取原意图，
不会自动重发写入；明确收到的 `REJECTED` 或 `RECOVERY_REQUIRED` 也不显示为切换成功。

## 维护归属与原有更新

活动部署（包括成功的 `DEPLOYED`）继续拥有该插件，直到精确回滚完成或部署被拒绝。
插件刷新、编辑、删除、文件正文同步和订阅配置写入返回
`409 AT_PLUGIN_MAINTENANCE_ACTIVE`；仓库导入跳过已受管插件，普通插件仍按原规则更新。
远端同步的合并不改受管行，覆盖在删除前检查所有现存插件；
包含插件模块的 AT JSON 备份恢复同样在任何删除或恢复前拒绝活动维护归属。
这只是恢复入口的写入保护，不增加完整实例或维护日志的 JSON 恢复能力。

部署创建、正文保存和这些写入口使用同一插件行锁；锁定后重新读取当前数据库值，
避免较早查询的旧实体把候选或回滚正文重新写回。排序仍然允许，但只在锁定后的
当前实体上更改顺序。QQ 音乐自动凭据保活在维护期间跳过该插件，
保存时再次检查归属和原 EXT，避免已发出的刷新请求覆盖维护配置。
精确回滚释放活动位后，原有刷新和编辑恢复；不新增自动重试或自动部署。
这不保护人工 SQL、直接数据库文件替换或外部静态文件本身，生产操作仍须另行授权。

## 证据边界

### 真实模型候选采用已通过（2026-11-13）

真实片库归档候选原样导入内置平台，新基线保留HTTP故障，候选当前必需7/7、
36 HTTP/2会话/12真实proxy/1482270字节通过。随后沿同一数据库、候选和
发布计划`6bc42190-00f2-42ec-9c01-7fa4ba906c86`完成真实宿主T4首页装载、
管理HTTP采用和精确回滚，三次loader代次不同，正文/实际装载SHA一致。
部署`eabf5953-0c87-4d71-a39c-e7d3d550d6b2`最终ROLLED_BACK；
本次没有模型新调用或人工爬虫补丁，不再使用合成候选通过状态。

专用内置JAR `bd22349c...9698c1`相对旧`0b59a384`只替换loader回执校验类：
调用摘要显式禁用AT默认JSON美化；20项定向检查及旧回执离线反例通过。
冻结片库bundle仍`473c3f14...fffcf2`，不混用黄果运行器。
首轮源验收成功后，私有入口缺请求上下文及续跑缺内存连接分别拒绝；
失败保留，仅补入口并复用7/7证据，没有重复源验收或改SQL状态。

权威`recovery/pianku-native-adoption/cycle-7f1a2f430c85/verification.json`。
当前父子journal/HTTP投影已独立核对，宿主退出0/无OOM、容器和worker清理、
broker停止、生产身份不变。收集器首轮本地缺键失败前已清实验凭据和日志，
失败记录保留，最终报告不再声称当前可复核启动日志计数。
18990/8797/生产不变；不认证原生宿主外网全链、客户端长播或生产发布。

### 早期可信样例范围（2026-11-12）

2026-11-12 已补内置页面入口和真实计划服务接线，修正了原实现多读一层
`binding.snapshot` 而拒绝真实计划的问题。本机检查实际使用源码/绑定/探测/修复/
候选/发布计划服务及 H2、HTTP、Python worker；只有元数据观察与隔离验收回执为合成输入。
这不等于真实片库候选的宿主切换、生产部署或客户端连续播放验收。
历史模型、片库 R04/R05、完整宿主回执仍绑定各自原 JAR，不复用于新包。

同日22:35已完成NAS隔离宿主补验：完整AT主类与真实源码/绑定/探测/修复/
候选/计划服务，经HTTP完成原版本→候选→精确回滚，三次装载代次不同，
宿主退出0、容器移除、生产身份不变。可信样例的候选运行验收状态是显式合成输入，
不等于真实片库候选的宿主采用或生产发布。
内置JAR `0c0d3d64...da49dc`；核验
`recovery/native-plan-acceptance/verification.json`，
原始回执 `recovery/embedded-host/run-20261112-5a1b0b1e7640/receipt.json`。
首次失败因验收样例缺lastCheckedAt，失败回执保留、容器已移除；
仅修私有样例与失败收尾，不改AT原清单接口，不重跑已闭环模型或源。
