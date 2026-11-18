# AT 原生运行身份

这是 AT 内置模块的只读运行身份入口，本接口不执行发布或自动重启。
独立工作台不包含 AT 宿主服务，因此不提供此端点。

## 管理接口

```text
GET /api/plugin-maintenance/at-native/plugins/{pluginId}/runtime-identity
Authorization: <AT ADMIN 会话>
Cache-Control: no-store
```

接口沿用内置管理 API 的 ADMIN 权限，不接受 USER、CLIENT 或匿名读取。
只查询当前插件记录和内存中已完成的 worker 装载快照，不探测 Python、
重建 EXT、下载源码、请求 worker、刷新空闲时间或重启进程。
网页源返回 `BLOCKED`，不存在的插件为404，未运行的插件为 `NOT_LOADED`。

已加载时返回实际编译文本的 `sourceSha256`、`sourceIdentityKind`、
worker/ATVP 资源组成的 `runtimeRevision` 和实际 `workerGeneration`。
未取得有效装载快照时保持 `UNKNOWN`，不使用数据库 content 推测运行版本。
响应不包含源码、URL、包摘要、EXT 或其摘要、过滤器配置和连接凭据。
有效过滤器快照通过 `protectedFilterIdentity` 返回有序装载状态、编译文本摘要和
受保护的配置指纹；缺失快照仍为未知，不补为空列表。

## 身份语义

原生 Python worker 在现有加载函数边界记录实际读取的包文本及编译的源码文本，
仅在 init 成功完成后随原初始化结果交给 Java。没有第二次下载或源码执行。
Java 验证版本、摘要和代次后保存私有副本，读取方不能改写该快照。
worker 资源摘要在启动时取得，ATVP 摘要来自实际传给 compile 的字节。

`utf8_compilation_text` 指 loader 文本经 UTF-8 编码后的摘要，
不等于 HTTP 原字节、加密包字节或完整依赖身份。
源/过滤器的远端新鲜度、当前保存配置和媒体健康未被本接口验证。
过滤器摘要来自同一次实际 loader 初始化：正常装载为 `LOADED`，
下载、构造或初始化未完成为 `NOT_LOADED`，保留原配置中的位置。
未装载项明确返回 `sourceSha256: null`，兼容工作台现有读取合同。
配置不以明文或普通 SHA 外发，采用已有
`hmac_sha256_loader_configuration_v1` 合同，绑定服务作用域、插件、siteKey 和槽位。
指纹只在相同作用域中可比较，服务实例重启会轮换作用域。
这不改变过滤器自身的错误策略，也不证明过滤器业务效果。

保存新源码后，活跃 worker 仍可能运行旧代码；管理读取会继续返回旧装载摘要和代次。
只有后续真实装载才会产生新快照，不能把 `LOADED` 当作候选已发布或切换成功。
内部 `runtimeStatus` 支持显式比较期望 EXT；不提供期望值时仅报告已加载，
不将“没有比较”误称为“配置不匹配”。

## 后续发布接线

另行提供默认关闭的[受控切换与回滚后端](at-native-deployment.md)，
使用持久意图、预期旧代次、维护窗口、新装载核对及失败回滚。
它不改变本接口的只读语义，也不表示生产发布已获准或已验收。
本机可信样例验证不替代 NAS、生产切换或未知源运行验收。
本轮不修改冻结的四模块审阅 ZIP；该后续源码增量另行审阅。

## 本机接线验证

2026-11-13 的过滤器增量已完成真实本机 Python worker、H2/ADMIN HTTP 和
工作台 `AtReadClient` 消费验证：9 个不同定向用例通过，补充合同后只续跑
受影响的 HTTP 方法并成功打包。新内置 JAR 为 `965b5438...4c4eb1`，
原包保存在 `recovery/embedded-filter-identity-20261113/previous-embedded.jar`。
权威证据为同目录 `verification.json`；测试代码首次编译错误日志保留。
当时尚未在 NAS 验证；后续结果见下节。未升级 18990/8797、修改生产或重跑
已闭环的真实源/模型。

## NAS完整宿主验证

2026-11-13 06:39，`run-20261113-60690134c402`完成当前内置包的隔离验收。
T4 extend复用已有过滤器排序/作用域后，新JAR为`249432bf...1e20c4`，
相对本机965b5438包仅`SubscriptionService.class`变化；单项T4构建检查通过，
未重跑其余本机测试。旧包保留，冻结审阅ZIP不重打。

完整AT/H2/T4/Python及ADMIN HTTP实际验证5条配置选中3条、部分初始化失败、
工作台读取、配置保密和真实过滤效果。初始输出`baseAC`；保存变更后旧worker
仍为`baseAC`，重载后新代次输出`baseBC`，装载摘要相应变化。
这是真实宿主中的可信样例，不是外站过滤效果、模型修复或客户端播放认证。

宿主退出0、无OOM，实验容器移除；独立只读核验当前镜像、5个远端输入摘要和
生产身份不变。权威`recovery/embedded-filter-identity-20261113/nas-verification.json`。
过滤器身份NAS项已关闭；长期观察、完整实例恢复和正式生产采用仍分别待办。
