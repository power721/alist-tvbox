# AT 内置最小接入

## 2026-11-13 最新增量

当前内置JAR SHA256为`6e94768a090d502232953d517d4d5f740b5a9f88b44ccd7786cd8aef19fa662e`。
补齐独立工作台已交付的自动维护/中文交付/AT双模式发布，以及V65已保存版本受控
换版接线；下文旧包SHA与完整宿主回执保留为历史，不套用给新包。
本次4Java/3前端受影响检查通过、内置与独立各一次打包。
NAS复用既有完整AT镜像、只挂载本次JAR，通过平台API实际验证运行中旧worker
到新worker的切换，编译文本SHA与已验证候选一致，生产不变、隔离容器已清理。
权威`recovery/platform-at-activation-20261113/run-20261113-f5dc8e49e4c9/verification.json`。
这是新包的发布/受控换版证据，不是新模型修复、完整源联网、所有内置能力重验
或生产升级；参见[交付标准](spider-delivery-standard.md)及当前进度顶部。

## 已交付范围

`maintenance-embedded` 是显式启用的 AT Maven profile，不是外部侧车链接。
维护模块与 AT 同 JVM、主 DataSource 和实体管理器，业务代码只保留一份。
独立 JAR 仍可照常构建；profile 产物写入独立 target，不污染普通 AT 构建目录。

- 内置 AT JAR：`plugin-maintenance/at-embedded/target/alist-tvbox-1.0.jar`。
- 当前 SHA256：`e67cb93b07be1341c85ac0bcad7935b3dd119cfa5c353d418f4ac52a0d2a26b2`。
- 管理页面：`/plugin-maintenance/`；API 仍为 `/api/plugin-maintenance/**`。
- 先在 AT 页面登录管理员，再打开独立管理页面；复用原 localStorage 会话。
- API 只接受 AT ADMIN 会话，保留 CSRF；USER、API Key 对应 CLIENT 均被拒绝。
- ZIP 使用 Authorization 请求头下载，不把会话放入 URL。
- 独立 JAR 使用原 Basic/form login，18990实例不是内置 AT。

当前包还包含默认关闭的[AT 原生切换后端](at-native-deployment.md)，
以及内置模式每次写入前更新 CSRF 的修正。最终 Java 7 项、前端 9 项定向检查通过，
前端构建及内置打包成功；计划/候选使用可信测试替身，不表示真实产品发布链已验收。
回执 `recovery/native-deployment-20261112/verification.json`。
旧包 4b863442 的[AT 原生运行身份](at-native-runtime.md)验收单独保留：
实际 loader 回执、只读管理快照及 ADMIN 接口 4 项通过。
此前`765205b7...018be2`关闭AT主H2冷恢复，其原字节保留于
`recovery/native-runtime-identity-20261112/before.jar`。
完整宿主NAS启动验收仍对应旧`1c3d49e9...06c0a21b`，不能套用给当前包。
本轮未重做NAS宿主或真实站点运行验收。见[冷恢复说明](database-archive.md)。
未认证MySQL/PostgreSQL、Native Image或生产部署。不要直接覆盖生产镜像，
也不要对运行中的AT主库启动第二个JVM。

## 构建

在工作区根目录，配置 Java21 后执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:MAINTENANCE_PYTHON = 'D:\源主动维护项目\workspace\.venv\Scripts\python.exe'
npm.cmd --prefix plugin-maintenance/web-ui run build
& '.\.codex\tools\apache-maven-3.9.11\bin\mvn.cmd' -B -Pmaintenance-embedded '-Dmaven.repo.local=D:\源主动维护项目\workspace\.codex\cache\m2' '-Dtest=EmbeddedMaintenanceIntegrationTest' clean package
```

内置配置只补 maintenance.python 和模型名环境变量别名；不加载独立 JAR 的
application.yaml，因此不会覆盖 AT 端口、数据库或鉴权。模型/媒体/巡检和隔离
执行默认关闭；需要的运行器与模型配置仍按现有流程显式提供。

## 数据库

构建按固定顺序连接独立 V1–V22 原始 SQL，生成
`db/migration/h2/V59__PluginMaintenance.sql`。由 AT 原 Flyway 历史顺序执行，
不另建迁移历史、不改独立库历史。V59 SHA256：
`2f2497bd99e446439316de2a62cddd88ce39018d45d7681b9b5c57b3038acfd3`。

这是当前固定 AT V58 基线的下一迁移；换上游基线后先处理版本归属，不机械复制
V59。已应用的 V59 必须保持不变，后续新增 schema 使用新的 AT 迁移。
原生部署意图使用新的 `V60__MaintenanceAtDeployment.sql`，不改写 V59。
独立维护库冷备份入口仍不能用于内置AT主库；内置包另提供明确AT类型的
`EmbeddedDatabaseArchiveTool`，复用共享归档实现，完整恢复AT和维护表但不启动宿主。
单源导出仍可正常使用。

## 验收与下一步

最终 `EmbeddedMaintenanceIntegrationTest` 通过，文件 H2 两次启动、真实
AT SessionTokenService/TokenFilter、68仓储、59项迁移、权限、CSRF、页面资源、
源码/ZIP与会话撤销均已验证。测试不启动完整 AListApplication，不加载 AList、
外部同步和完整后台任务，不执行上传的源码。

前端 `node --experimental-strip-types --test tests/embeddedApi.test.mjs` 5/5，
`npm run build` 成功；最终内置构建日志：
`plugin-maintenance/at-embedded/embedded-package-final.log`。
独立包增量核对及工作台启动回执：
`recovery/embedded-mvp-20261112/verification.json`。

## 完整宿主隔离启动（2026-11-12）

当时使用内置 JAR `1c3d49e9356599cd96ad807b379d3838f9f8fc39b261db868bbabdd106c0a21b`
构建独立镜像，并在 NAS 上以网络 `none`、非 root、只读根、1.5 GiB/2 CPU/256 PID
运行真实 `cn.har01d.alist_tvbox.AListApplication`。AT H2 与 AList SQLite 使用新建实验目录；
没有生产挂载或网络出口。JAR 相对先前内置包仅变化于维护连接设置代码、内置配置及 Web 资源；
AT 核心、依赖和固定 V59 SQL 字节未改变。

完整宿主两次启动/干净关闭通过：ADMIN 登录、身份、匿名401、维护页面及 JS/CSS、
连接设置、CSRF 写保护、隔离源码导入/原字节读取/ZIP导出、AList `/ping` 200。
主 H2 与 SQLite 两个连接池每次均成功打开；重启后相同源码 ID/SHA 仍可读取。
最终退出143、非 OOM；容器已移除，生产 AT、T4、AList 容器身份保持不变。
首轮实验发现 `/tmp` 默认 noexec 阻止 SQLite 原生库加载，该轮只保留为未通过历史；
实验 tmpfs 显式设为 exec 后重新执行完整验收，最终日志没有 `UnsatisfiedLinkError`。

权威回执：`recovery/embedded-host-20261112/verification.json`；
原始两轮证据位于 `D:/源主动维护项目/recovery/embedded-host/`。
核验后已移除两轮实验生成的临时管理员凭据/配置密钥，并清空含启动凭据的 NAS 日志；
原始启动日志不再保留，仅保存此前已核验的脱敏统计与回执。实验数据库保留，
清理回执为 `recovery/embedded-host-20261112/privacy-cleanup.json`。
不包含真实模型、源联网、受管巡检、生产部署、外部 AT 服务或客户端播放验收。

真实模型修复及选定源首次受管巡检随后也已闭环，见[当前进度](implementation-status.md)顶部；
不重复构建或重跑完整宿主检查。M5 已开始按运行器、控制面、Web、内置接入拆分本地
审阅补丁，入口见[上游审阅包](upstream-review.md)。原 M1–M5 保持 active，
长期试运行与正式发布/恢复仍未完成，生产只读边界不变。
