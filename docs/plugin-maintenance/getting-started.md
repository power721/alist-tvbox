# 独立插件维护模块：启动与当前能力

## 当前入口（2026-11-18）

当前源码/本机工作台为 `0.105.2-SNAPSHOT`，独立 JAR 为 `plugin-maintenance/server/target/at-plugin-maintenance-0.105.2-SNAPSHOT.jar`；最新支持范围、阻断及发版条件见[发版与体量评估](release-readiness-2026-11-18.md)。内置/M5冻结审阅包仍为0.81，其安装顺序见[上游审阅包](upstream-review.md)，不等同当前独立版。
本机 18990 已在运行，54 来源、45 个隔离通道；不要直接执行下方历史启动命令或打开同一个 H2 库。
首页推荐增量已完成独立维护端及完整内置 AT 原生 T4 的指定 HTTP 验收，范围见[当前首页合同](t4-home-current-runtime-2026-11-16.md)，不泛化为整站健康或客户端实播。
构建或诊断先加载 `plugin-maintenance/scripts/use_project_storage.ps1`，启动/归档脚本已自动加载。

### 新安装副本

独立JAR需要Java21和可用Python；源码构建另需Maven与Node/npm，先构建 `plugin-maintenance/web-ui` 再打包 `plugin-maintenance/server/pom.xml`。打包现在只保留当前dist，不累积旧哈希资源。现有Python执行器镜像不是完整维护平台镜像。

新副本可用 `start_local.ps1 -Port 18992 -DataDirectory 'D:\维护平台数据' -PythonExecutable '<Python绝对路径>'` 启动独立控制面；这是新目录示例，不在当前18990库上重复启动。默认回环监听、账户密码admin/admin，外部监听前必须修改默认凭据。独立数据目录保存数据库及连接配置，不随JAR覆盖。

在“模型与连接”中配置获准AT连接、模型中转和外部隔离执行器。仅JAR启动成功不代表已经可以联网维护；未配置隔离执行环境时，不应将静态检查冒充巡检。模型默认关闭，成功候选的持久发布队列默认使用增量模式；自动发布还需显式启用并绑定获准AT实例，替换发布需选择确认。本机已启用自动增量策略，但最新无人值守完整真实交付仍待验收。

### 导入与维护范围

“从 AT 导入”支持已登记的自定义明文 Python 源，包括同源静态文件及公共 HTTPS 地址。
公共地址由匿名读取器访问，不转发 AT 会话凭据；预览与确认分别读取并核对源码 SHA，
内容改变时重新预览。加密包、页面插件及未获准的来源不作为 Python 原件执行。

资料导航按实际列表、搜索、详情及全局搜索交接用途确认，不因没有播放链接判坏；
磁力/网盘交接也不要求直播放链接。名称包含豆瓣或 TMDB 并不能决定来源用途。
复杂功能源可通过 `maintenance.at.excluded-plugin-ids` 明确排除；独立服务可设置
`MAINTENANCE_AT_EXCLUDED_PLUGIN_IDS`，本机启动器对应 `-ExcludedAtPluginIds`。
本项目启动器按用户约定默认排除插件416，服务自身默认空名单；连接其他 AT 实例前须核对 ID，
不能把这个数字当成跨实例身份。排除覆盖导入、绑定、巡检、模型、候选和发布，保留历史只读。

联网修复只能使用平台隔离执行器。候选的冻结必需项通过不自动证明媒体字节或客户端实播，
也不授权生产写入；交付包保留原件、提案、验证证据及中文迭代版本源码。

## 历史最小交付（2026-11-14，不作当前安装命令）

当时维护模块源码版本为 `0.43.0-SNAPSHOT`，两种模式均有独立 Web 页面。
使用已经打包的 JAR 不需要重新编译；从审阅补丁包安装时，
先按[上游审阅包](upstream-review.md)完成 Web 和对应 Java 模式的构建。
`start_local.ps1` 未指定 `-JarPath` 时按当前 POM 版本选择独立 JAR；
新副本还需准备 Java21 和可用 Python，通过 `-PythonExecutable` 指定解释器。
已有台账、画像、报告、候选和导出/本地回滚入口；生产 AT 只读接入、
真实隔离片库 7/7 及本地候选采纳/回滚的已验收范围，见
[开发计划检查点](../planning/plugin-maintenance/plan.md)。
后续已完成真实外部模型对片库的单次修复及 NAS 候选 7/7，候选未采用到生产；
这不是生产切源或全站点验收。AT 内置最小接线已另外完成并打包，
见[内置接入](embedded-integration.md)；完整 AT 宿主隔离启动已通过，不重复验收。

本机18990已部署 JAR：`plugin-maintenance/server/target/at-plugin-maintenance-chinese-names-20261114.jar`，
SHA `fdfa68820b19e9cde6f14704381d255a612b9c55d0765792a9cb9d7319be285a`。
该部署与新副本构建产物分开，不能以文件名相近推定运行身份一致。
2026-11-14最近验收时，同库52条源、18个NAS隔离通道及原历史、连接与预算保持；
当前进程/通道请用运行接口核对，不以本文历史PID判断。
旧`086f8f49`、`634e236d`、`0d13a4c1`、`97f285e1`及`fbb3dd68`均保留，没有覆盖。
该部署身份权威`recovery/platform-chinese-names-20261114/upgrade-verification.json`；
HTTP双会话强杀恢复只在独立验收实例进行，没有重启18990。
中文站名优先、域名转写与已发布改名见[中文交付名称](chinese-delivery-names-2026-11-14.md)；
插件569已改为「艾克斯视频_v1.0.1」，不改原件530或爬虫源码。
516/521已由平台联网发现新故障，516新模型候选已自动修复并通过所选合同，
图片/媒体/搜索等未覆盖项仍未验证；旧源回执不因本机升级迁移通过状态。

在仓库根目录启动本机管理页面，单独使用 18990 和独立数据库，不碰旧 8797：

```powershell
& 'C:\Users\wab201\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe' -NoProfile -File plugin-maintenance/scripts/start_local.ps1 -Port 18990 -DataDirectory 'D:\源主动维护项目\workspace\plugin-maintenance\server\data\mvp-18990' -JarPath 'D:\源主动维护项目\workspace\plugin-maintenance\server\target\at-plugin-maintenance-chinese-names-20261114.jar' -ManagedRunnerRoutes 'D:\源主动维护项目\workspace\plugin-maintenance\server\data\mvp-18990\runner-routes.json' -ManagedRunnerTunnels 'D:\源主动维护项目\workspace\plugin-maintenance\server\data\mvp-18990\runner-tunnels.json' -TunnelPythonExecutable 'C:\Users\wab201\AppData\Local\Python\pythoncore-3.14-64\python.exe' -AutomaticRepair -AutomaticSourceSetup
```

启动后访问 `http://127.0.0.1:18990/`，默认用户名和密码均为 `admin`。
服务只绑定 `127.0.0.1`；不要将默认密码用于对外监听的部署。
首页只保留爬虫列表、AT/文件导入、联网巡检、大模型修复与验证采用。
无联网执行器时巡检禁用，不自动降级静态检查；“语法预检”保留在高级区，
不能用其结果建立首页修复基线或宣称爬虫健康。联网摘要保留失败、策略限制和未覆盖项。
“模型与连接”默认打开模型中转站，可配置只读 AT 地址/实例/认证方式，以及 Chat Completions
中转 Base URL 或完整 endpoint、模型别名、API Key 与请求兼容选项。
配置保存到该数据目录的 `connections.json`，凭据不通过读取 API 回显；
留空表示保留已保存凭据。新数据目录的AT默认未配置，已有数据目录会保留已保存连接。
当前mvp-18990已保存获准的只读AT连接，插件列表和片库源码预览通过；生产未修改。
新目录模型默认关闭；当前mvp-18990保留已启用的获准模型通道，
本次页面升级没有调用模型。保存通道不会发送模型请求；
源码授权和预算继续有效，显式启动的自动修复由服务端递交和复测，不依赖页面操作。
早期回环隧道18991/19002/19003分别连接黄果、央视和516/521来源NAS可信broker；
后续来源通道由同一产品管理，以已保存路由和实际连接结果为准。
源码只在隔离worker执行，不在Windows宿主执行；全部爬虫验证、修复和测试都走平台。
显式启用`-AutomaticSourceSetup`后，未映射来源在首次一键维护时可自动建立独立通道；
解析源码AST中的运行时HTTP(S)字面来源，公网IPv4冻结后执行，保留原协议/端口，
HTTPS证书不降级，源码不在宿主运行。
524首轮分类修复失败，后续同任务补齐平台页面证据后通过所选9项合同；
轮间修改过平台，不能称整次零干预，更不是全量插件健康证明。
该助手使用项目虚拟环境，先按`plugin-maintenance/scripts/requirements-tunnel.txt`安装依赖；
隧道解释器可独立选择。保留已有known_hosts，不能通过关闭主机密钥校验解决连接问题。
“模型与连接 → 隔离执行 → 检查连接”
只检查运行身份，不执行源或调用模型。定时巡检和独立媒体工具仍关闭，手动联网巡检可用。
获准开始持续巡检后，可在原启动命令追加 `-ScheduledInspection`，
再为选定源保存联网巡检批准及启用策略；默认命令不会开启调度。
该开关不启用其他源、不单独授权模型或生产发布，也不创建系统计划任务。
本机启动器显式决定巡检开关，不用 `MAINTENANCE_SCHEDULING_ENABLED` 覆盖它。
当前运行实例没有追加该参数；不要为查看帮助启动第二个实例或同时打开原H2库。
2026-11-13已在前轮`0d13a4c1`包实际验证上述页面路径，返回“身份匹配，可接收任务”；
当前来源路由包已完成产品HTTP验收，尚未另做浏览器切源交互实测。
黄果导入候选仍显示尚未检查，不沿用其他实例的历史通过状态。
同时省略`-ManagedRunnerConfig`和`-ManagedRunnerRoutes`才保持源执行关闭；配置文件只含端点和固定运行身份，
不含SSH命令、密码或模型密钥。`-ManagedRunnerTunnels`随工作台启动配置中的固定隧道，
断线后只为新请求重连/核对所选通道，不重发已有任务；管理页面不因NAS离线而无法打开。
正常退出时回收本启动器的隧道子进程，详见[通道运维](managed-runner-operations.md)。
当前NAS broker由产品生命周期入口管理，黄果空日志及央视已完成任务日志可控重启已验；
另外已在独立可信样例验证broker在途强杀、显式端点恢复和原容器清理，
不等于HTTP驱动/多会话或NAS整机恢复。尚未配置OS自启；NAS重启后按实际归属恢复，
不要直接重跑私有setup或删除旧socket。
不为页面升级重跑已闭环黄果/片库或调用模型。
真实模型配置见[模型中转配置](model-proposals.md)；不从其他项目借用凭据。

复杂导入、能力与运行配置、诊断节点、巡检、媒体工具和资料均默认折叠。
手工补丁JSON与回放不是普通用户的必经步骤。网址生成仍属后续能力，当前没有伪入口。

“导入维护 ZIP”也接受已经导出的候选包。导入只保存基线、候选补丁与历史来源，
不会执行源码或恢复历史通过状态。为新环境完成检查、建立修复会话后，
可使用“复用已导入候选”，无需再次请求模型。
当前本机共52条源，包含原有片库/黄果候选归档、央视及生产AT导入的明文自定义插件。
46条历史静态记录不算46源联网健康；新增联网原件报告为516/521，
另有较早已知黄果样本，已通过的源不为刷结果重复执行。
黄果原模型提案、候选SHA和ZIP字节均保持，平台可查看/导出；本机没有为该条目
创建探测或修复会话，不能显示为本机复测通过。原有条目及历史保留。
一条旧模型验证会话因分析器升级显示`repair_tools_changed`，
仅新鲜度提示变化，原状态/轮次/冻结内容未改；不要重置预算或移植旧通过结果。

### 多来源执行通道（管理员配置）

源码支持按来源 UUID 选择可信执行通道，不要求所有站点共用黄果的出站策略。
示例见 `plugin-maintenance/examples/managed-runner-routes.json`，示例身份都是占位值，
使用前须替换为已经安装的 broker、worker 镜像、出站策略及可选 HTTP 驱动身份。
一个通道可绑定多个来源；同一来源不能重复绑定。普通用户仍只需选源并点击检查，
“模型与连接 → 隔离执行”显示当前来源的通道标签，不增加逐次填写参数的步骤。

管理员在原启动命令中增加 `-ManagedRunnerRoutes '<通道配置JSON绝对路径>'`；
也可直接设置 Spring 属性 `maintenance.managed-source.routes-file`。
只传此参数、不传 `-ManagedRunnerConfig` 时，仅已映射来源具备执行通道，
未映射来源保持联网不可用；同时传入两者时，未映射来源使用显式配置的默认通道。
两个参数都省略仍关闭源执行。默认配置只在启动时读取，不自动准备基础设施。
显式`-AutomaticSourceSetup`使用管理员已有SSH环境和已安装runtime/镜像，
可新增独立broker和回环通道，不改生产或已有映射、不向源容器传凭据。

每个任务冻结实际通道策略；切换来源配置会令相关报告、巡检批准和采用计划失效，
不会挪用其他通道的通过记录。先按原归属完成未结束任务的清理，再调整映射；
原策略通道未注册时，恢复会明确阻断，不会把未结束任务转交新通道。
当前18990已使用上述三通道配置。较早央视来源任务`601dcac9-5a58-456f-bb49-7bd08362ee3f`
经平台提交到对应NAS隔离执行器，冻结必需项11/11、当前journal及清理核对通过。
这是该来源的装载合同检查，不等于R04/R05、客户端播放或全站健康。
本次516新故障自动修复见[自动修复](automatic-repair.md)；较早黄果5/5和片库7/7未重跑，
生产AT只读且身份保持。

### 本机开发，NAS封装与验收

用户已确认采用此分工：本机构建前端/JAR，NAS只接收最小上下文，负责镜像封装与
隔离真实验收。保留本机18990，生产AT和旧8797不动；不迁移Node/Maven、不新开产品分支。
现有`plugin-maintenance/container/Dockerfile`仍是Spider执行器，不是完整Web维护服务。
已有完整宿主和源验收直接复用，仅在相关变化后做定向验证。
具体入口和封装/验收的结论边界见[构建与验收流程](build-and-acceptance.md)。

## 历史能力与启动记录

以下保留 0.26 及之前的交付描述，不作为当前版本或当前待办。

本版本为 `0.26.0-SNAPSHOT`，新增[受保护配置指纹](../security/plugin-maintenance-protected-filter-identity.md)。新临时 ADMIN 路由使用服务实例内存密钥和明确 scope 的 HMAC，工作台严格校验并保存独立回执；旧私有回执与历史不回写，H2 仍为 V18。后端 319 项、Python 192 项、新服务/HTTP 专项、升级/H2/整库恢复 37 项及完整候选 234 项全部通过，原 121 个观察不变；164 个类、34 个资源、3 个前端文件与 JAR 一致，见[0.26 独立核验](protected-filter-api-result.json)。不同 scope 的指纹不可比较，本增量不使旧历史、源码或整库包自动可公开。

历史 0.25 的[有序过滤器装载回执](runtime-filter-api.md)及[独立核验](runtime-filter-api-result.json)保持原身份：当时后端 310 项、Python 180 项、整库 37 项和完整候选 224 项通过，原 121 个行为观察不变。其原始配置 SHA 不是隐私保护指纹，仍按私有证据保管。

0.24 的[ADMIN 装载身份候选及工作台联通](runtime-api-candidate.md)保留：仅临时 AT 副本提供只读管理快照；产品保存编译文本/原字节/未声明的 SHA 含义。未将实验候选部署到生产或当前 8797 实例。

后续[原 AT 仓储联通实验](at-repository-lab.md)在独立文件 H2 上执行原 V1–V58、真实 Plugin/Session 等仓储、原内容端点与鉴权，并联通最终 0.24 JAR。完整 204 项及 Python 143 项通过，产品版本/JAR未变。它不启动完整 AT 主应用，不读取生产数据，不替换当前工作台；正式迁移分配、完整 AT 重启和隔离/媒体/发布仍待验。

新增[非空过滤器仓储实验](at-filter-repository-lab.md)补齐数据库选择/排序/作用域、原过滤器内容端点、detail/player 与 strict/skip、数据库更新和热缓存的分别观测；完整 212 项、Python 154 项通过，原 121 个观察不变。仍只运行固定可信样本和临时文件库，不操作生产或常驻实例，不执行远端导入/刷新、陌生源、模型或发布；没有新增产品版本、绑定合同或源节点 pass。

再增[启动审查与独立 JVM 恢复实验](at-jvm-recovery-lab.md)：默认 3 项专项，`--full` 重跑原 212 项及新增 3 项，最终共 215 项和 Python 169 项通过。两条恢复路径共五次子 JVM 启动，覆盖已提交状态、冷装载和会话撤销持久化；原 121 个观察不变。它不是完整 AT 启动、OS 恢复、自动孤儿清理或 journal/媒体恢复。原服务入口的多数据源、固定路径和启动副作用须先有实际隔离，本机未安装 Docker/WSL、未启动完整 AT；0.24 JAR 与 8797 原实例均不替换。

0.23 的[独立维护库冷备份与恢复](database-archive.md)保留：停机备份、可信身份/有界 ZIP 校验、只读核验后恢复到新目录，以及默认关闭外部操作的恢复启动。含私有数据且未加密，不接受陌生数据库；不是 AT 发布回滚。0.22.1 的 runtime identity 整数修正与本机恢复保留，详见[接收机恢复记录](receiving-machine-recovery-2026-11-08.md)。

0.22 新增的[AT runtime identity 只读回执](runtime-identity.md)继续保留：在管理员显式配置回执路径后读取实际 `siteKey`、源码 SHA、runtime revision、装载状态和可选 worker generation，并将回执绑定到当前 AT 快照。未配置时保持管理元数据行为；回执不刷新插件、不执行爬虫、不证明媒体或客户端。[有界 HLS AES-128 TS 取样](hls-aes.md)继续保留：KEY 声明、默认/显式 IV、密钥轮换和 NONE；key 取得与片段请求共用白名单和持久预算，key 原字节和哈希不写入证据。完整片段检查 padding，前缀只解完整块并保留未测边界。网络默认关闭，仍是人工 player 的辅助诊断，不参与源健康或发布晋级；MAP/BYTERANGE/SAMPLE-AES 继续阻塞。

0.20 的[上游源码协调](upstream-coordination.md)保留：管理员声明共同基线并登记上游字节，查看三方 SHA 状态、显式采用或保留本地，持久保存幂等回执并导出比较包。有效版本指独立台账指针，尚未自动获取上游、合并、执行或部署。

0.19 的 [TS 节目表与 PES 前缀检查](ts-inspection.md)：M05 在有界取样内重组单节目 PAT/PMT、检查 CRC 并观察声明媒体 PID 的 PES 前缀，缺证据的截断取样保持未测；旧报告保留原结论并提示引擎失效。仍不证明解码或播放成功。0.18 的[离线源码证据包恢复](source-import.md)支持校验预览后显式恢复为新本地源，原包和幂等回执持久保存，EXT 配置重新绑定，旧报告仅归档，不恢复 AT 绑定、巡检或模型授权。此入口只接受单源导出包，不接受完整工程源码 ZIP。0.17 的[模型金额硬预算](model-cost-budgets.md)：在管理员明确确认计量合同后，按请求字节输入上界、最大输出 token 和冻结费率，在全局、源、模型三层调用前原子预占潜在金额；历史金额未知或额度不足不会按零放行。0.16 的[provider 用量与参考费用](model-usage-costs.md)继续记录已验证的 token 用量并生成非账单估算。0.15 的[按模型每日预算](model-named-budgets.md)继续提供全局、源与模型名称三层同事务预占。同名换地址或切回模型不重置额度。0.14 的[持久媒体预算与主机间隔](media-budgets.md)继续提供全局/每源 UTC 日额度、源间隔、逐请求持久许可及跨任务主机间隔，取消/重启不退款。0.13 的[有界媒体诊断](media-diagnostics.md)继续使用人工直连 player 输出，检查有限 HLS/TS 或 MP4 Range 样本。网络执行默认关闭，结果不计入源健康或发布资格。0.12 的[发布准备计划](release-preparation.md)保留：候选仍需隔离运行、AT 和媒体验证，未执行换版。

0.11 增加[按源每日模型预算](model-source-budgets.md)：源级与全局额度同事务预占，升级回填历史调用，并在修复页面展示只读预算。0.10 增加[模型候选来源与显式单次自动递交](model-submission.md)。专用人工递交绑定建议 SHA；可选自动递交需全局和单次调用同时启用，共用原三候选预算和静态/运行时门槛。已有模型协议、源码授权和预占额度见 [0.9 说明](model-proposals.md)。测试仅调用本机假服务。保留默认关闭的容器可信示例通道；真实 Docker/Linux 镜像尚未构建运行，未知源码与修复候选不能进入该通道。模块使用独立 H2/JPA/Flyway，不打开或修改 AT 的数据库。自动巡检仍只做已有静态检查或内置可信夹具，不判断线上源健康。M1、M2、M3、M4 均未整体完成，具体检查结果与后续工作见 [实现状态](implementation-status.md)。

代码位于仓库的 `plugin-maintenance/`。完整路线见 [功能规划](../planning/plugin-maintenance/plan.md)。

本启动说明描述当前可本机运行的 Spring Boot JAR。产品目标还包括 AT 内置模块和独立服务容器两种部署形态，并要求两者都保留独立 Web 管理入口；当前 Spider 执行器的 `container/Dockerfile` 不是维护服务容器镜像。各形态的已验证范围见[功能规划](../planning/plugin-maintenance/plan.md#部署目标2026-11-12-用户确认)。

另有独立的 [M2 AT/T4 合同实验 v1](t4-contract-lab.md)，执行固定基线的真实控制器/worker 和可信源，复现参数、热缓存及首页推荐差异。它不增加工作台探测 mode 或节点通过数，维护服务及 JAR 仍为 0.8。

[参数修正候选与对照实验 v2](t4-parameter-candidate.md) 在临时 AT 控制器副本修正 flag、搜索页码与插件 JSON POST，原基线和候选各通过 48 项验证；文档和两份 v2 冻结结果作为历史保留。

[过滤器配置候选 v4](t4-filter-candidate.md) 的原基线、原参数候选、过滤器候选三组各 93 项和 24 项启动器边界检查保留为历史结果；其 C3 配置下发、HTTP detail/player、错误策略、token 与设备字段边界均有对照。

历史实验为 [首页推荐候选 v5](t4-home-candidate.md)：原基线、参数候选、过滤器候选、首页候选四组各 103 项，另有 30 项启动器边界检查。新候选只在临时 Java/资源副本继承已有修正并增加 C6，覆盖可选/空推荐、双包装模式、JSON 字符串、错误传播与原内置合并对照。前述 v1–v4 冻结结果保留原字节。M2 v5 不增加产品 mode/pass，维护服务仍为 0.11；C4、过滤器装载失败和完整部署/媒体/客户端仍待验。

历史实验 v6 增加 [T4 装载身份与待生效状态候选](t4-identity-candidate.md)：worker 在实际包/源码/过滤器装载边界生成回执，冻结启动 worker 与编译 ATVP 的资源哈希；Java 内部只读方法区分未运行、配置一致和待重启，不调用或刷新 worker。十二项新实验覆盖远端 URL 可变、过滤器装载/初始化失败、非法配置、返回副本及初始化期间文件改写；五组各 52 项局部实验与 63 项原 AT 回归，共 115 项，启动器边界 37 项通过。各组原 40 个观察与 v5 对应实现逐项一致；新候选只增加 C4 可见性，不将 C4 列为修正。没有自动换版、媒体桥会话保留、外部管理端点或产品接入；0.11 服务/UI/JAR、原 AT、旧候选/报告保持原字节。

历史实验为 [v7 媒体桥活动与维护退出候选](t4-bridge-candidate.md)：仅在临时副本增加 RPC/桥活动保护、HEAD 转发和有界退出确认。内部退出须核对 generation、无活动且未发桥地址；退出未确认时保留 closing worker。最终六组各 127 项、44 项启动器边界通过；每组原 52 项观察与 v6 对应实现一致。C4 的 generation 媒体路由、完整换版/回滚和产品接入仍未实现；本机合成字节转发不代表真实媒体或客户端通过。

历史实验为 [v8 桥地址 generation 绑定候选](t4-generation-candidate.md)：从实际 worker 身份生成版本化桥前缀，在转发前拒绝伪造、错代和跨源代次。旧格式保留且没有代次保证；缺少旧代次时拒绝，不自动冷启动。最终七组各 135 项、边界 51 项通过；各组原有 64 个观察与 v7 对应实现一致。旧会话保留、完整切换/回滚、真实媒体和 Linux 隔离仍待完成。

实验 v9 增加 [T4 显式维护窗口换版候选](t4-maintenance-candidate.md)：核对旧 generation、活动和桥中断决定后退出旧进程；新初始化回执须匹配冻结源码/EXT/运行时/过滤器身份。失败保留阻断，普通调用、空闲回收与旧退出入口不能绕过，恢复须显式重试。新候选、直接父候选 v8 和原基线各 147 项（84 实验 + 63 原 AT 回归）通过，零失败/错误/跳过；56 项启动器/候选边界通过。三组原有 72 个观察与对应 v8 逐项一致。该版阻断仅限当前 JVM，尚无持久发布、缓存迁移、自动回滚或产品接入，C4 整体未完成。

历史实验为 [v10 持久维护记录候选](t4-journal-candidate.md)：使用独立文件 H2、JPA 与 Flyway；意图和核验结果分别独立提交，重建服务后保留阻断。候选、直接父候选 v9 和原基线各 160 项（97 实验 + 63 原 AT 回归）通过，零失败/错误/跳过；62 项启动器边界通过，三组各保留原有 84 项观察。J01–J13 仅在 v10 候选执行持久日志路径，其他组保留对应未实现观察。候选尚无跨重启协调恢复入口，不能直接用于可持续部署。没有新增工作台模式、源健康 pass 或修复发布能力；该实验交付时独立产品为 0.14；当前产品版本见本文开头。

当前实验 v11 增加 [T4 显式协调恢复与独立缓存候选](t4-recovery-candidate.md)：旧 PID 与维护意图同事务提交，替代启动前后持久保存 checkpoint/PID；重建服务后以活动 attempt ID、同一配置运行范围、桥失效确认及已退出 PID 显式恢复，重新通过八字段身份与完成事务后才接纳调用。存活/复用 PID、未知启动、旧记录缺元数据继续阻断，不按历史 PID 杀进程；每尝试使用独立缓存并保留旧字节。候选、直接父候选 v10 和原基线各 172 项（109 实验 + 63 原 AT 回归）通过，零失败/错误/跳过；69 项启动器边界通过。基线/父组保留原 97 项观察，候选保留其中 96 项，仅 J03 的显式恢复实现标记有意改变。首轮 4 失败/1 错误保留；修正恢复入口未初始化运行时及服务重建夹具后，12 项恢复专项和三组全套通过。该范围未证明完整 AT/JVM/OS 重启、子进程树、未知启动协调、Linux 隔离、真实媒体、发布幂等或回滚，C4 和产品接入仍未完成。见 [v11 冻结记录](t4-recovery-verification.json)。

当前实验 v12 增加 [T4 必需过滤器装载失败候选](t4-filter-load-candidate.md)：只在固定临时副本的 `Atvp.py` 装载 owner 中传播 strict 过滤器的空 source、下载、编译、模块执行、构造和 init 失败；默认/skip、snake/camel 策略规范化和正常 Decorator/模块过滤器保持原行为。候选、直接父 v11 和原基线各 184 项（121 实验 + 63 原 AT 回归）通过，零失败/错误/跳过；77 项启动器边界通过。候选只改变 I06/F06/G05 三个既有负对照，并新增 L01–L12；没有产品节点 pass、JAR、迁移或 AT 业务源码变更。完整实验仍不等于部署修正；`inner.init` 和前序过滤器初始化副作用不回滚，完整 C4、真实仓储/客户端、Linux 隔离、发布/回滚继续待验。见 [v12 冻结记录](t4-filter-load-verification.json)。

## 1. 构建与启动

接收机已经准备好项目内 Maven、Python 环境和 Java 21。已构建后，在仓库根目录执行：

```powershell
& 'C:\Users\wab201\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe' -NoProfile -File plugin-maintenance/scripts/start_local.ps1
```

本机启动器根据 POM 选择 JAR，默认监听 `http://127.0.0.1:8797/`，数据库为 `plugin-maintenance/server/data/local/maintenance.mv.db`。默认关闭 AT 连接、模型、自动递交、容器、巡检与媒体网络；已保存连接沿用，`-AutomaticRepair` 和 `-ScheduledInspection` 分别显式开启自动递交和巡检协调器。用户名为 `admin`，本机默认密码见本文顶部；不要将生产密码粘贴到项目文档。按 Ctrl+C 停止前台实例。

2026-11-09 当前 `8797` 常驻实例仍使用 0.22.1；0.24/0.25 已完成当时验收，0.26 已构建并完成临时库、完整候选和独立产物核验，未自动切换运行中的服务。不要同时打开同一个 H2 文件，也不要在 8797 原进程仍运行时直接执行默认启动命令。恢复副本使用 `-DataDirectory '<不存在目标经 restore 创建的新目录>' -Port 8798` 显式选择目录；启动前核对目录确为自己的恢复库，操作见[冷备份说明](database-archive.md)。

需要 Java 21、Maven、Node.js 20.19+ 或 22.12+、Python 3.10+。静态探测只使用 Python 标准库；离线 worker 另需 `requests`、`pycryptodome`、`lxml`。JAR 携带固定 worker 源码，仍需本机 Python 和这些依赖；源码构建另需 Maven/npm 依赖缓存或网络。

在仓库根目录构建：

```powershell
npm.cmd --prefix plugin-maintenance/web-ui ci
npm.cmd --prefix plugin-maintenance/web-ui run build
mvn -f plugin-maintenance/server/pom.xml verify
```

先构建前端，再构建后端，前端产物会打入 JAR；资源规则排除 Python 字节码缓存。本机为保留常驻和历史 JAR，不对维护模块 clean；需要干净构建时使用独立副本。单独构建后端也可使用 API，但未构建前端时不包含工作台页面。

在 `plugin-maintenance/server/` 下启动：

```powershell
$env:MAINTENANCE_PYTHON = (Get-Command python.exe).Source
java -jar target/at-plugin-maintenance-0.26.0-SNAPSHOT.jar
```

默认访问 `http://127.0.0.1:8797/`，用户名为 `admin`。未设置密码时，Spring Security 在启动日志中生成本次密码；可通过 `SPRING_SECURITY_USER_PASSWORD` 设置持久密码。界面使用标准登录会话，API 支持 HTTP Basic；写入保留 CSRF 检查。

| 环境变量 | 用途 |
|---|---|
| `MAINTENANCE_PYTHON` | Python 可执行文件路径，Windows 建议显式设置；默认 `python3` |
| `MAINTENANCE_MEDIA_ENABLED` | 默认 false；仅人工媒体诊断，不进入自动巡检 |
| `MAINTENANCE_MEDIA_ALLOWED_ORIGINS` | 逗号分隔获准测试的完整 origin；初始 URL、跳转、variant 和分片逐个核对 |
| `MAINTENANCE_MEDIA_ALLOW_HTTP` | 默认 false；本机 HTTP 测试或明确允许的 HTTP 目标需另设 true |
| `MAINTENANCE_MEDIA_DAILY_PROBE_LIMIT` / `DAILY_REQUEST_LIMIT` / `DAILY_RETAINED_BYTES` | 完整前缀均为 `MAINTENANCE_MEDIA_`；默认全局每日 24 任务、288 次预占请求和 12582912 字节响应体容量 |
| `MAINTENANCE_MEDIA_SOURCE_DAILY_PROBE_LIMIT` / `SOURCE_DAILY_REQUEST_LIMIT` / `SOURCE_DAILY_RETAINED_BYTES` | 同前缀；默认每源每日 4 任务、48 次预占请求和 2097152 字节响应体容量 |
| `MAINTENANCE_MEDIA_SOURCE_INTERVAL_SECONDS` / `MAINTENANCE_MEDIA_HOST_INTERVAL_SECONDS` | 默认 21600/1800 秒，最少 1800 秒；UTC 换日、取消、重启或改源码不提前截止时间 |
| `MAINTENANCE_PORT` | 默认 8797 |
| `MAINTENANCE_BIND` | 默认仅监听 127.0.0.1；容器部署可配置为 0.0.0.0 |
| `MAINTENANCE_DATA_DIR` | 默认相对启动目录的 `./data`，生成独立 `maintenance.mv.db` |
| `MAINTENANCE_DB_PASSWORD` | 独立 H2 数据库密码 |
| `SPRING_SECURITY_USER_PASSWORD` | 工作台管理密码，与 AT 订阅 token 不相同 |
| `MAINTENANCE_SCHEDULING_ENABLED` | 默认 false；true 时启动持久巡检协调器，每源还需单独启用 |
| `MAINTENANCE_SCHEDULING_DAILY_LIMIT` | 全局每日自动投放次数，UTC，默认 48，范围 1–1000；只计自动任务 |
| `MAINTENANCE_CONTAINER_ENABLED` | 默认 false；仅手动 `container_fixture`，不启用未知源或自动巡检容器执行 |
| `MAINTENANCE_CONTAINER_EXECUTABLE` | Docker CLI 的现存绝对文件路径；启用时计算二进制 SHA，执行前复核 |
| `MAINTENANCE_CONTAINER_ENDPOINT` | 留空使用本机默认 npipe/unix socket；仅接受本地端点，禁止 tcp/ssh/context |
| `MAINTENANCE_CONTAINER_IMAGE_ID` | 已存在本地 Linux 镜像的 `sha256:<64个小写十六进制字符>` ID；运行禁止拉取 |
| `MAINTENANCE_AT_ORIGIN` | 可选的 AT 管理 origin，只能为协议、主机、端口；不允许路径、query、fragment 或 userinfo；留空禁用 |
| `MAINTENANCE_AT_INSTANCE_ID` | 启用时必填的公开实例标识，1–100 个 ASCII 字母、数字、下划线或连字符 |
| `MAINTENANCE_AT_REVISION` | 公开的版本声明，默认 unverified；不是服务端版本或运行时回执 |
| `MAINTENANCE_AT_SESSION_TOKEN` | 启用时必填的 AT 原始管理会话 token，只驻留控制面内存；不加 Bearer 前缀，不使用订阅路径 token |
| `MAINTENANCE_AT_ALLOW_HTTP` | 默认 false，HTTPS 为默认要求；本地明确允许明文 HTTP 时设 true |
| `MAINTENANCE_AT_RUNTIME_IDENTITY_PATH` | 默认留空；显式配置同一 AT origin 下的只读路径模板，例如 `/api/plugins/{pluginId}/runtime-identity`；禁止 query、fragment、userinfo 和 `..` |

Windows 中文路径通过原始 UTF-8 字节保存。添加文件要求 UTF-8，最多 1 MiB；原有 BOM/CRLF 参与内容哈希，不通过 PowerShell JSON 往返改写源码。

独立 H2 文件使用 `WRITE_DELAY=0`，避免保存刚返回后强停 JVM 丢失最近的延迟写入；冒烟测试使用同一设置验证进程强停后的恢复。若自行覆盖 `spring.datasource.url`，应保留该参数。本次证据为进程重启，不是断电恢复测试。

## 2. 在页面使用

1. 点击“添加 Python 源”，导入文件或粘贴源码，可选填写稳定业务 ID。
2. 在“能力与配置”声明运行器、公开版本号及必需/可选节点；可输入 EXT JSON 计算指纹。
3. 点击“静态探测”，查看 44 个节点中的已测、未测与本次必需项覆盖情况。
4. 在“修复与验证资料”查看该次报告冻结的相关技能规则、原文摘录及来源 SHA。
5. “保存新版本”建立新的不可变 artifact；旧源码与报告继续保留。源码或画像的编辑基线过期时返回冲突。
6. 下载当前或历史源码的离线包，包中包含实际源码、文件哈希、能力矩阵、技能快照、当前画像与对应报告的冻结画像。

静态模式执行 S01 的独立台账身份、S02 的源码字节身份、S03 的 AST/Spider 类与重复方法检查，其余 41 个节点未测。存在 AT 绑定时，S01 附加该次任务冻结的管理元数据；其通过范围仍为独立台账，不代表已核对实际装载源码或 runtime siteKey。

可以点击“添加离线示例”，再点击“离线夹具探测”。仅源码字节 SHA 与本版内置示例一致、画像运行器为 `t4` 且未保存 EXT 指纹时允许执行；其他源码、运行器和无法恢复的 EXT 配置都返回明确阻塞。即使保存过 `{}` 的 EXT 指纹也不能从指纹恢复执行配置，应新建未配置 EXT 的示例记录。

离线模式使用单独临时目录、缓存及环境变量白名单，通过固定 `t4worker.py → Atvp.py → demo.py` 的真实 JSONL 调用传递输入。全部断言通过时增加 S06、C01、C07、C10、R01，共 8 项通过、36 项未测；详情含无效 ID 负例，已发布的一条线路/一个选集全采样。只检查分类和搜索第 2 页，不将 C03/C05/C06 完整节点标通过；正常协议请求不能代替异常协议全集，S07 保持未测。

`bootstrap_observed` 装载回执由独立启动器观察实际传入 inner loader 的源码文本哈希，另核对源码字节、运行时文件与冻结清单 SHA，并记录解释器/依赖版本。它不是 AT 原生回执，不证明某个部署实例装载了该源。示例 URL 使用 `example.invalid`，从未作为媒体访问。

上传的未知源码仅做 AST 分析，不会被执行。可信示例的网络 hook、Python `-I` 和进程树清理不是安全沙箱；当前没有执行未知爬虫的入口。若 Python 不可启动、超时或执行器失败，报告标记阻塞，不返回运行成功。

任务队列为单执行线程、最多等待 32 个任务。可以在报告选择器旁点击“取消探测”：排队任务直接取消并释放队列名额；运行中任务先显示“正在停止并清理”，在分析器及其解释器子进程退出、临时输出清理完成后显示“已取消”。清理不能确认时显示失败与 `INSPECTOR_CLEANUP_FAILED`，不能按成功取消处理。取消中的任务继续轮询状态。

取消接口幂等：重复请求不改变已有终态；取消已完成任务会原样保留报告。取消已被接受后，迟到的成功结果不能覆盖它。当前静态任务没有逐节点检查点，未完成的结果不作为成功报告发布；源码与冻结画像仍保留。重启后排队、运行中及取消中的任务均记为中断，不自动重发；自动巡检协调后按原策略和退避创建后续新任务，保留已用预算。

### 上游协调

在“上游协调”选择本源已有源码为共同基线，再登记管理员取得的上游 UTF-8 文件。登记保留原字节并提示三方状态，不切换有效版本。无本地冲突时可显式采用；双方分歧时可保留本地，并将上游设为后续比较基线。比较包供人工审阅，不可交给单源恢复入口。遇到版本冲突后点击“刷新协调状态”，核对身份再提交；输入保留，不自动重投。流程和 API 见[上游协调说明](upstream-coordination.md)。

### 低频巡检

在源的“低频巡检”页签选择模式、启用本源并保存；全局开关也需启用。每源默认关闭、静态模式、6 小时间隔、每日最多 4 次；间隔允许 0.5–168 小时，每日允许 1–24 次。截止时间加 0–20% 正向抖动，首次启用需等待完整间隔；编辑、关闭再启用和恢复均不会提前已有截止时间，也不清零次数或连续失败。

协调器启动后 5 秒首次执行，之后每 60 秒轮询；每轮最多读取 20 个到期源并投放 4 个任务，检查最多 100 个活动源。每源最多一个自动任务。页面通过“刷新巡检状态”读取最新策略、活动/上次任务、预算日和结果，不持续自动刷新新巡检任务列表。关闭策略只停止后续自动投放，已有任务通过原取消入口停止。

全局与每源按 UTC 日统计自动投放次数；预算扣减、任务与冻结策略、活动关联在同一事务保存后才入队。取消、队列拒绝、崩溃、重启、编辑和恢复均不退款；日期回退不重新发放预算。状态中的 `budgetDay` 是计数实际所属日期，未投放过可能显示初始或旧日期；换日容量在下一次投放尝试时更新。手动任务不占这项次数，但仍受同一有界队列限制。这项预算不是 HTTP 请求、域名、字节、媒体或模型预算。

必需节点未测、失败或输入已变均不能记为成功，连续退避为 30 分钟、2 小时、6 小时、24 小时，并以配置间隔为下限。执行范围不支持、EXT 无法恢复或清理未确认时暂停本源自动任务；修正问题后显式点击恢复。`declared_checks_passed` 仅表示已声明的必需项在本次输入下通过，默认仅要求静态三项，不表示源在线、媒体或播放器可用。自动任务不调用 AT 元数据观察，不执行未知源码或调用模型。

### 能力画像与配置身份

画像为不可变 JSON 快照，保存 `runtime`、`runtimeRevision`、`requiredNodes`、`optionalNodes` 与 `extFingerprint`，使用 SHA-256 标识。同内容去重，更新需提交当前画像的 `expectedSha256`。运行器可选 `t4`、`atvp` 或 `fongmi`；`authority=declared` 表示人工声明，不能当作运行器实际装载回执。

默认画像把 S01–S03 设为固定必需项，其余 41 项可选；运行器声明默认为 `t4 / unverified`，EXT 尚未提供。节点可设为必需、可选或未声明，拒绝未知节点、重复项及必需/可选重叠。未声明仍保留未测试状态，不自动视作不适用或通过。例如把 C01 首页分类设为必需后，当前报告为“必需 4、通过 3、未测 1”，结论为 `required_incomplete`。

EXT 仅接受 JSON 对象，规范化后的 UTF-8 字节最多 64 KiB。对象键递归排序，数组保留顺序；结合每源随机盐计算 SHA-256。正文与键名不保存，盐仅存于 H2，不进入 API、报告、技能上下文或 ZIP。省略 EXT 或发送 `null` 保留原指纹，发送 `{}` 表示空配置；页面保存成功后清空输入。指纹只用于判断配置是否变化，不能恢复配置或直接运行爬虫。

任务提交时冻结源码、画像和当前 AT 绑定，离线/容器模式另冻结运行时清单，容器模式增加 `executionPolicySha256 / executionPolicy`；后续编辑不改写旧报告。查询报告的 `freshness` 包含 `current`、`source_changed`、`profile_changed`、`source_and_profile_changed`、`runtime_changed`、`execution_policy_changed`、`binding_changed`、`at_connection_changed` 和 `legacy_unbound`；`staleInputs` 同时列出源码、画像、运行时、`execution_policy`、`at_binding` 及 `at_connection` 变化，避免多项变化被单一摘要遮蔽。这里的 `current` 仅说明已绑定输入仍匹配，不是站点有效期或健康结论。

从 0.1.0 升级时，Flyway 自动执行 V2–V7。旧源读取返回确定性的默认声明，不在读请求中写库；未绑定画像的旧报告标记 `legacy_unbound`。V3 为既有报告补充 `executionMode=static`，不伪造运行时清单或回执；V4 的旧报告绑定保持 null，不凭当前绑定补写历史。V5 将旧任务保留为 `triggerKind=manual`，巡检快照字段保持 null，不自动启用任何源。V6 增加修复会话/候选表，不补造静态合同或修复历史；V7 仅增加可空的容器执行配置字段，旧报告仍为 null，旧故障指纹不因新增 null 字段而重置预算。升级前应停止旧实例并备份其独立数据目录；本轮覆盖的升级路径见下文，未验证直接降级给旧 JAR。

### 实验容器可信示例

添加“离线示例”后点击“容器可信示例”。默认生成持久 `BLOCKED / CONTAINER_EXECUTION_DISABLED` 报告，只保留三个静态节点通过；不会回退宿主 worker。当前配置与每次报告冻结配置可分别展开，前者的 GET 不调用 Engine，也不声明镜像存在。容器模式暂不进入自动巡检。

启用后仍只接受本版固定 demo 字节、t4 画像及空 EXT 身份。控制器不向容器提交源码、任意命令、路径或配置；镜像入口核对固定六文件清单并复制到临时目录。宿主 CLI 使用独立空 Docker 配置，剥离管理/模型凭据与 DOCKER_HOST/CONTEXT。Engine 创建参数复核通过才启动；入口再观察 uid/gid、权限、seccomp、cgroup v2 内存/CPU/PID、网络接口、根只读与 tmpfs 等实际值。预期限制为 256 MiB、无 swap、0.5 CPU、64 PID、32 MiB `/work` tmpfs、网络 none、非 root、cap-drop ALL 和 no-new-privileges。CLI 两个输出流共享上限，运行阶段 192 KiB；入口内固定夹具另有时间与输出限制。

每任务只投放一次，attempt 标签由持久随机任务 UUID 派生。正常结束、超时和 Java 二次清理均核对任务/attempt 归属，删除后再次查询；重启只对 RUNNING/CANCELLING 容器任务执行清理，并核对冻结的 CLI 路径与端点指纹。关闭执行开关不跳过旧任务清理。创建没有取得确认、宿主控制进程被中断或删除无法确认时，记录 `FAILED / CONTAINER_CLEANUP_UNCONFIRMED`，不能显示取消成功。需在真实 Engine 上继续验证创建/删除竞态与崩溃后的迟到响应；本版不允许未知源。

源码包包含 `container/Dockerfile`、固定依赖版本及无凭据构建上下文生成器。例如在仓库根目录，先填写自己已核实的 Linux Python 基础镜像 digest，再只导出上下文：

```powershell
$baseImage = '<repository>@sha256:<64位真实digest>'
python plugin-maintenance/scripts/build_container_context.py --base-image $baseImage --output fixture-container-context.zip
python plugin-maintenance/scripts/build_container_context.py --check-bundle
```

该命令不构建、不拉取、不运行镜像，拒绝覆盖已有 ZIP。ZIP 内 `build-context-manifest.json` 列出基础镜像、三个构建参数和全部文件哈希；在隔离测试机上手动构建后，使用该本地镜像 ID 配置通道。构建会下载固定版本的 Python 依赖；包中不含基础镜像、wheel、Docker/Java/Python 运行环境，不代表离线可完成镜像构建。当前依赖版本来自可信宿主夹具，Linux 可安装性与兼容性未验。镜像必须含匹配的 runtime/entry 标签，入口检查失败不放宽控制值。

新增 [M1 容器验收工具](container-acceptance.md)：先用 `python plugin-maintenance/scripts/container_acceptance_test.py --check` 核对本地字节，不接触 Engine；在真实 Linux 测试机准备好固定镜像后，再显式 `--run`。工具保存创建意图 / ACK 日志，支持 `--recover` 仅清理本次归属任务。当前 27 项协议和本机可信子进程检查不能替代真实 Linux 验收；产品仍为 0.17，复用原 JAR。

0.20 之后补充 [M1 容器资源验收](container-resources.md)，产品与 JAR 仍为 0.20。先执行 `python plugin-maintenance/scripts/container_resource_acceptance_test.py --check`；真实 Linux 测试机准备好同一固定镜像后，可显式 `--run`，依次执行原验收流程及固定 OOM/PID/CPU/超时负载。独立 journal 冻结全部新旧脚本身份，支持同工具 `--recover`；恢复成功只表示清理成功。本机仅验证协议，未执行压力负载或开放受管源及候选。

### 静态修复候选

点击“添加静态修复示例”，等待静态探测完成，再进入“修复候选”页签，从当前报告创建会话并填入建议模板。该示例是带有重复方法的静态数据，顶层含禁止执行的异常标记，只供 AST 分析；它不同于可执行的“离线示例”。提交后可查看回合、合同差异、未测项及下载候选维护包。自己的源需先得到本版分析器生成的报告；旧报告没有合同字段时应重新静态探测。

会话要求报告已完成、输入仍匹配、源码 SHA 等于当前有效版本，且缺陷仅为重复方法。语法错误、重复 Spider 类或截断的合同观察尚不支持。会话冻结报告、画像、绑定、运行时身份、工具版本和匹配技能资料；相同源的相同故障指纹复用原会话，重测或重复创建不能重置三轮限制。静态分析解释器版本也进入故障指纹；解释器升级后需重新探测形成新基线，不能用旧合同继续验证。

建议协议为 `exact_edits_v1`，JSON 字段包括 `schemaVersion=1`、`expectedBaselineSha256`、`targetFile="source.py"`、`diagnosis`、`expectedEffect`、`validationTargets=["S03"]` 和 `edits=[{"oldText":"唯一原文","newText":"替换文本"}]`。最多 20 个编辑，序列化后 UTF-8 最多 32 KiB；候选不超过 1 MiB。每个 oldText 必须在同一冻结基线唯一匹配，编辑区间不得重叠，原始 BOM/CRLF/中文保留。每轮重新从该基线生成完整候选，不在上一轮坏候选上叠加修改。只允许 `source.py`，建议中的额外策略字段不能改变服务端限制。

每个故障最多接受三个候选，次数、候选字节和活动关联在同一事务提交后才入队；这是候选次数预算，不是模型调用、token 或每日成本预算。静态修复使用独立单线程队列和 8 个等待名额；普通探测仍为单线程和 32 个等待名额。取消、队列拒绝、中断或恢复不退次数。重复已验证补丁/候选、重复失败或第三轮失败会停止；无变化补丁也停止。提交前匹配、重叠及大小错误不保存候选、不扣次数。

验证只运行可信 AST 分析器，不导入或执行候选。对照基线有效方法的输入签名、同步/异步类型、装饰器、Spider 类头、导入 AST 和分析解释器版本，变化会拒绝；这些对照不证明方法体语义不变。原先通过的运行节点全部重新标为 `not_tested`。静态通过只进入 `STATIC_VALIDATED / AWAITING_RUNTIME_VALIDATION`，`publishEligible=false`；没有应用、发布或切换当前源码的入口。

排队取消直接结束；运行取消先进入 `CANCELLING`，清理确认后才为 `CANCELLED`，清理失败仍显示失败。重启把未完成候选记为 `INTERRUPTED`，不自动重发；修正问题后可显式恢复，预算保留。静态通过后取消再恢复仍等待运行时验证，不能重新开放补丁入口。源码、画像、绑定或工具变化会使会话失效，验证期间变化也阻断最终候选。

候选 ZIP 包含 `baseline.py`、`candidate.py`、`proposal.json`、`candidate.json`、`repair.json`、`rounds.json`、冻结输入、基线报告、画像、技能上下文及策略。`rounds.json` 保留全部回合建议和结果；导出在同一数据库读取快照生成，manifest 列出每文件字节数/SHA 及故障、基线、候选、建议和冻结输入的身份。后续画像变化不改写冻结输入。此包供审阅，没有恢复执行或发布功能。

### AT 只读观察与本地绑定

未配置连接时其余功能照常使用。配置上述 AT 环境变量并重启后，在某个源的“AT 绑定”页签点击“读取 AT 插件”，再选择插件并保存绑定。只读取管理员配置 origin 的 `GET /api/plugins`；浏览器不提交 origin 或凭据，也不自动登录、刷新插件、下载源正文或执行远程代码。若另行配置 `MAINTENANCE_AT_RUNTIME_IDENTITY_PATH`，可在绑定后显式点击“读取 runtime identity 回执”，只读取同 origin 的白名单 JSON，并将回执写入当前绑定快照。

客户端禁止重定向，连接超时 3 秒、整个读取最多 8 秒、流式响应最多 2 MiB、最多 5,000 个插件；严格拒绝重复 JSON 字段、尾随载荷和身份冲突。成功观察复用 30 秒；失败尝试也有 30 秒冷却，返回 `AT_READ_COOLDOWN`。这只是单进程的人工读取保护，不是持久巡检预算或调度。

观察只返回白名单元数据：插件 ID、名称、externalId、enabled/t4Visible、版本声明、类型、推导的 siteKey、地址 SHA 与有无错误；不保存原始地址、EXT、正文、错误全文或 configSchema。快照标记 `metadata_observed`；`derivedSiteKey` 按固定 AT 基线推导，订阅覆盖后的 key、实际源码 SHA 与运行时装载身份仍未核验。runtime identity 回执只有在专用路径配置并通过状态/siteKey/SHA 校验后才标记 `runtime_observed`；它会改变绑定 SHA，使旧报告进入 `binding_changed`，仍不增加媒体或发布通过。

绑定只能引用服务器当前观察，观察最多有效 5 分钟，重启后必须重新读取；本版允许明确识别为 `.py` 或 `.txt` 的插件，扩展名不区分大小写，并按 AT 基线处理含空格或方括号的本地文件名。网页、未知类型、externalId 冲突及跨源重复占用同一实例的 Plugin ID 被拒绝。改绑需携带当前绑定 SHA，数据库唯一约束和行版本处理并发冲突。解除操作只移除本地当前绑定，已有任务快照继续保留；未进入任务的旧绑定不单独保存历史。

实例标识与 origin 的 SHA 共同形成连接身份。更换 origin/实例标识或停用连接后，当前及历史冻结绑定会标为连接不匹配；解除当前绑定后，旧任务仍核对其冻结连接。绑定不会证明本地源码等于 AT 源码，也不会使任何运行节点额外通过。

### 离线源码恢复

点击“导入源码 ZIP”选择旧单源证据包，先校验预览，再填写新名称和 `.py` 文件名并确认。包最多 4 MiB，展开总量最多 8 MiB，源码最多 1 MiB；路径白名单和 manifest/CRC/SHA 验证失败不会创建记录。恢复结果可在“源码版本 → 已恢复的离线包”持久查看，并下载保存的原 ZIP。

恢复不执行 Python，也不把旧报告记为新源通过。配置指纹不跨环境复用；请重新配置和探测。同一页面请求失败重试保留原键，相同键重放不覆盖后续本地编辑。并发竞争可能返回 `409 SOURCE_CONFLICT`，可使用原键重试；改变名称/文件名或换包使用新键。详见[恢复合同与 API](source-import.md)。

## 3. 技能怎样作为修复、探测和验证资料

已纳入 12 条审阅过的规则，来源为：

- `adaptive-video-spider-workbench` 的结构与播放合同；
- `alist-tvbox-compatibility-check` 的加载、筛选、包装与方法覆盖规则；
- `video-spider-reachability-matrix` 的媒体、时长与输入来源要求。

规则包含适用节点、故障标签、运行器、诊断建议、验证步骤，以及技能路径、原始文件 SHA、行号和摘录。发布包携带 JSON 快照，不依赖用户机器上安装 Codex 技能，也不会执行技能脚本、接受技能中的远程权限声明或加载整个个人技能目录。

`GET /api/plugin-maintenance/knowledge?node=M06&failure=range&runtime=t4` 可按节点与故障检索。上下文最多 12 条、规则正文合计最多 16 KiB；默认最多 8 条。重复方法的专项建议不会被套到不匹配的明确故障上。

每个新探测报告绑定源码 SHA、画像 SHA、分析器 SHA、能力矩阵 SHA、Python 版本及技能目录快照 SHA。资料按该次冻结画像的运行器检索，相关原文随 run 保存，升级资料后也能解释旧报告。资料是 `data_only / reference_only`，不能替代运行证据。

修复会话已冻结这份结构化上下文，并接受人工/夹具建议进行精确补丁与静态对照。可选模型通道在确切源码授权后消费同一参考输入，已有调用/请求字节/预占输出上限，来源与单次递交见 [说明](model-submission.md)。已有按源和按模型每日预算，见[0.15 记账合同](model-named-budgets.md)。0.16 记录 provider 自报输入/输出 token，并在费率配置齐全时给出冻结费率的参考金额；0.17 增加显式计量合同下的潜在金额准入预算，缺失 usage/费率不会推断成零。启用任一金额上限必须设置完整费率和 `MAINTENANCE_MODEL_INPUT_TOKEN_BOUND=request_utf8_bytes_v1`，由管理员确认 provider 符合该计量与费率合同；不符合时不能把请求字节当作 token 上界。返回 usage 超出预占会保留证据并持久阻断该 UTC 日及之前的新调用。真实 provider 合同核验、账单对账、候选隔离运行晋级和自动发布尚未实现。

### 更新本地技能快照

显式执行以下命令构建；不要把任意目录作为自动加载入口：

```powershell
python plugin-maintenance/scripts/build_knowledge.py --skills-root "$env:USERPROFILE/.codex/skills"
python plugin-maintenance/scripts/build_knowledge.py --skills-root "$env:USERPROFILE/.codex/skills" --check
```

第一条根据选定锚点重建快照，第二条核对快照与当前技能字节是否一致。锚点缺失、歧义或矩阵节点数不符时失败。检查生成差异后再构建 JAR；运行中不会静默更新。

## 4. API 范围

`GET /api/plugin-maintenance/model/named-budget` 返回当前配置模型的周期、模型级预占、潜在金额、provider 用量与参考金额；全局和源级预占分别由 `/model/budget` 和 `/sources/{id}/model-budget` 返回。`GET /api/plugin-maintenance/model/connection` 显示当前配置的 USD/百万 token 费率和三层金额上限。0.15 预算和 V14 迁移见[按模型预算](model-named-budgets.md)，0.16 用量/费用说明见[provider 用量](model-usage-costs.md)，0.17 金额预算见[模型金额硬预算](model-cost-budgets.md)。

API 前缀均为 `/api/plugin-maintenance`，只属于独立模块，不是已合入 AT 的接口。

| 方法与路径 | 用途 |
|---|---|
| `GET /csrf` | 获取当前管理会话的 CSRF token/headerName，写请求需带上并保留会话 Cookie |
| `GET /at/connection` | 已配置状态、公开实例/版本声明及 origin 指纹；不返回 origin 原文或凭据 |
| `POST /at/observations` | 显式读取 AT 白名单插件元数据，需本模块鉴权和 CSRF；对 AT 仅发送 GET |
| `GET /sources/{id}/at-binding` | 当前本地绑定及连接匹配状态；未绑定返回 204，未知源返回 404 |
| `POST /sources/{id}/at-binding` | 字段 expectedSha256（首次为 null）、observationSha256、pluginId；冲突返回 409 |
| `POST /sources/{id}/at-binding/runtime-identity` | 字段 expectedSha256；仅在配置只读回执路径后读取实际 siteKey、源码 SHA 与装载状态，成功后更新绑定 SHA |
| `POST /sources/{id}/at-binding/unbind` | 以 expectedSha256 解除本地当前绑定，成功返回 204 |
| `GET /scheduling` | 全局启用状态、每日自动投放上限、预算所属 UTC 日期与次数 |
| `GET /sources/{id}/schedule` | 当前策略、SHA、截止时间、计数、活动任务、退避与暂停状态；未配置返回默认关闭声明 |
| `POST /sources/{id}/schedule` | expectedSha256（首次 null）、enabled、mode、intervalSeconds、dailyLimit；需鉴权与 CSRF，基线冲突 409 |
| `POST /sources/{id}/schedule/resume` | 以 expectedSha256 显式解除暂停；不重置预算/连续失败，不提前截止时间 |
| `GET /sources`、`GET /sources/{id}` | 台账、当前源码 SHA 与画像 SHA；不返回源码正文 |
| `POST /sources` | 新建 Python 源，字段 name、filename、content，externalId/originUrl 可选 |
| `GET /sources/{id}/profile`、`GET /sources/{id}/profiles` | 当前画像与历史快照；未保存过时返回默认声明 |
| `POST /sources/{id}/profiles` | 以 expectedSha256 保存声明；字段 runtime、runtimeRevision、requiredNodes、optionalNodes、ext（可省略） |
| `POST /sources/{id}/artifacts` | 以 expectedSha256 和 content 保存版本 |
| `GET /sources/{id}/artifacts` | 版本清单 |
| `GET /sources/{id}/artifacts/{sha}` | 获取特定版本的 UTF-8 原文 |
| `GET /sources/{id}/upstream`、`GET /sources/{id}/upstream/events` | 三方状态（未初始化 204）与最近 20 个不可变协调回执 |
| `POST /sources/{id}/upstream/initialize` | requestKey、expectedArtifactSha256、baselineSha256；管理员显式选择本源已有共同基线 |
| `POST /sources/{id}/upstream/observations` | requestKey、expectedRevision、expectedArtifactSha256、content；只登记上游，不切换本地 |
| `POST /sources/{id}/upstream/resolve` | requestKey、expectedRevision、expectedArtifactSha256、decision（FAST_FORWARD / KEEP_LOCAL）；有本地冲突拒绝快进，KEEP_LOCAL 仅用于分歧 |
| `GET /sources/{id}/upstream/export` | 三份原始源码、协调状态及逐文件哈希；不是恢复或发布包 |
| `POST /sources/{id}/probe-runs` | 返回 202；空 body、`{}` 或 `{"mode":"static"}` 保持静态；另支持 `offline_fixture` 和默认关闭的 `container_fixture`；其他 mode 返回 400 |
| `GET /runtime` | 本版预期运行时清单及 SHA；仅 `expected_bundle`，不声明已执行或部署可用 |
| `GET /container-runtime` | 预期容器配置、SHA 与配置阻塞原因；仅 `expected_configuration`，不调用 Engine，不暴露 CLI/端点原文 |
| `GET /fixtures/demo` | 本版固定可信示例原文与 SHA |
| `GET /fixtures/repair` | 重复方法坏例与确定性建议，标记 data_only，不执行 |
| `GET /sources/{id}/repairs`、`POST /sources/{id}/repairs` | 最近 20 个会话；创建需 baselineRunId 与 expectedArtifactSha256，相同故障复用 |
| `GET /repairs/{id}`、`GET /repairs/{id}/candidates` | 冻结输入、状态/次数/失效及全部回合 |
| `POST /repairs/{id}/proposals` | exact_edits_v1 建候选并异步静态验证，返回 202；队列满 429，仍保留已接受次数 |
| `POST /repairs/{id}/cancel`、`POST /repairs/{id}/resume` | 取消/显式恢复；恢复需 expectedFaultSha256，不重置次数 |
| `GET /repair-candidates/{id}`、`GET /repair-candidates/{id}/content` | 候选状态/补丁/对照报告及独立 UTF-8 原文 |
| `GET /repair-candidates/{id}/export` | 含基线、候选、建议、全部回合及冻结证据的 ZIP |
| `GET /model/connection`、`GET /model/budget` | 模型及自动递交开关、配置 SHA、已预占额度；不返回 endpoint/key |
| `GET/PUT /sources/{id}/model-consent` | 查询或按旧授权 SHA 更新源码外发授权，确切源码 SHA 绑定 |
| `GET/POST /repairs/{id}/model-calls` | 历史调用或创建建议；原幂等字段另支持 autoSubmit，默认 false |
| `GET /model-calls/{id}`、`POST /model-calls/{id}/cancel` | 模型/递交结果及取消；关联已提交时应取消修复会话 |
| `POST /model-calls/{id}/submit` | 以 expectedProposalSha256 递交已审阅建议，持久绑定唯一候选；重复请求返回原候选 |
| `GET /sources/{id}/probe-runs` | 最近 30 个报告，完整历史继续保存在库中 |
| `GET /probe-runs/{runId}` | 报告、节点证据和冻结的资料上下文 |
| `POST /probe-runs/{runId}/cancel` | 请求取消，返回当前状态；需管理鉴权和 CSRF，未知任务返回 404，已有终态保持不变 |
| `GET /knowledge`、`GET /capability-nodes` | 技能参考与全部节点定义 |
| `GET /sources/{id}/export?sha=...` | 当前或指定历史源码包 |
| `POST /source-imports/preview` | archiveBase64；只读校验单源证据 ZIP，不创建记录 |
| `POST /source-imports` | archiveBase64、expectedArchiveSha256、requestKey、name、filename；显式恢复新本地源，返回 201 |
| `GET /source-imports/{id}`、`GET /sources/{id}/imports` | 历史导入回执或本源最近 20 条恢复记录 |
| `GET /source-imports/{id}/archive` | 下载持久保存的原 ZIP，不重建或改写包内字节 |

导出包的 manifest 对每个载荷文件列出 SHA 和字节数。它是源码与已取得证据包，不包含 Java/Python 运行环境，不代表断网可以访问外部站点。`knowledgeSha256` 与 `nodeCatalogSha256` 指向导出时的目录快照；`probeKnowledgeSha256` 指向所附报告当时的资料版本。升级资料后，两者可能不同；历史匹配规则原文保留在 `probe.json.context`，不会用新资料重新解释旧报告。

`profile.json` 为导出时当前画像，manifest 的 `profileSha256` 对应其原始字节；`probe-profile.json` 为报告冻结的画像，对应 `probeProfileSha256`。导出历史源码时也遵循此规则，两份画像可能不同。未绑定画像的旧报告不生成 `probe-profile.json` 或 `probeProfileSha256`。

离线报告另携带 `probe-runtime.json`，字节 SHA 对应 `probeRuntimeSha256`；它是预期清单，实际观察证据在 `probe.json.result.runtimeEvidence`。单源 ZIP 不包含运行时程序，完整源码 ZIP 与 JAR 才包含固定 worker/loader/启动器源码；任何包均不附送 Python 解释器或第三方依赖。

容器报告另携带 `probe-execution-policy.json`，字节 SHA 对应 `probeExecutionPolicySha256`，仅表示当时预期配置。成功执行时的 Engine、入口与删除观察应位于 `runtimeEvidence.containerEvidence`；本轮没有取得真实容器成功报告，协议假件不能代替它。旧报告及其他模式不补造该文件。

存在绑定时，`at-binding.json` 是导出时当前快照，对应 `bindingSha256`；`probe-at-binding.json` 是报告冻结快照，对应 `probeBindingSha256`，两者可能不同。若显式读取过 runtime identity，两个文件会保留脱敏的只读回执及其身份 SHA；它仍不等于媒体、客户端或发布成功。旧报告或未绑定时不生成对应文件和字段。

已保存巡检策略时，`schedule-policy.json` 对应 manifest 的 `scheduleSha256`，`schedule-state.json` 记录导出时的计数/截止/暂停等状态。自动报告另带 `probe-schedule-policy.json`，对应 `probeScheduleSha256` 及报告的 `scheduleSha256`；策略更改不改写旧报告，两份策略可以不同。旧报告或手动任务没有冻结巡检策略；状态快照仅用于审阅，不是导入或恢复命令。

## 5. 验证与接续实现

0.21 AES 媒体引擎升级专项（H2 schema 保持 V18，夹具需 pycryptodome）：

```powershell
python plugin-maintenance/scripts/hls_aes_smoke_test.py --java java --python python --previous-jar .codex/artifacts/at-plugin-maintenance-0.20.0-SNAPSHOT.jar --jar plugin-maintenance/server/target/at-plugin-maintenance-0.21.0-SNAPSHOT.jar
```

此专项只调用本机合成 HTTP 媒体，不执行导入源，验证 key 轮换/无效 key/前缀、旧报告策略失效、持久预算、导出脱敏和重启不重放。完整边界见[说明](hls-aes.md)。

0.20 上游协调及 V17→V18 文件库升级专项：

```powershell
python plugin-maintenance/scripts/upstream_smoke_test.py --java java --python python --previous-jar .codex/artifacts/at-plugin-maintenance-0.19.0-SNAPSHOT.jar --jar plugin-maintenance/server/target/at-plugin-maintenance-0.20.0-SNAPSHOT.jar
```

三个临时 JVM 验证旧报告/画像保留、上游登记不采用、显式快进后的报告失效、历史请求重放不覆盖后续编辑、分歧拒绝、三方原字节、普通恢复包兼容及强停恢复。没有访问远端上游、执行提交源码或接入 AT/模型。V18 不为旧源推断上游关系；旧迁移字节不改。直接降级给旧 JAR 未验。

0.19 TS 引擎升级专项（H2 schema 保持 V17）：

```powershell
python plugin-maintenance/scripts/ts_inspection_smoke_test.py --java java --python python --previous-jar .codex/artifacts/at-plugin-maintenance-0.18.0-SNAPSHOT.jar --jar plugin-maintenance/server/target/at-plugin-maintenance-0.19.0-SNAPSHOT.jar
```

该检查使用三个临时 JVM 和四个回环 HTTP 地址，验证旧报告不改写、策略失效、真假 TS 分别判定、206 缺表不假报通过及重启不重发；不绕过默认源/主机间隔。夹具是合成结构，不是可解码视频。

0.18 文件库恢复专项：

```powershell
python plugin-maintenance/scripts/source_import_smoke_test.py --java java --python python --previous-jar .codex/artifacts/at-plugin-maintenance-0.17.0-SNAPSHOT.jar --jar plugin-maintenance/server/target/at-plugin-maintenance-0.18.0-SNAPSHOT.jar
```

该检查只启动本机 JVM，验证 V16→V17、重启和空库恢复，不调用生产 AT 或模型。历史版本专项命令仍保留对应旧 JAR 路径。

```powershell
python plugin-maintenance/scripts/test_static_inspector.py
mvn -f plugin-maintenance/server/pom.xml clean verify
$javaExecutable = (Get-Command java.exe).Source
$pythonExecutable = (Get-Command python.exe).Source
python plugin-maintenance/scripts/test_container_protocol.py
python plugin-maintenance/scripts/smoke_test.py --java $javaExecutable --jar plugin-maintenance/server/target/at-plugin-maintenance-0.11.0-SNAPSHOT.jar --python $pythonExecutable
python plugin-maintenance/scripts/repair_smoke_test.py --java $javaExecutable --jar plugin-maintenance/server/target/at-plugin-maintenance-0.11.0-SNAPSHOT.jar --python $pythonExecutable
python plugin-maintenance/scripts/container_smoke_test.py --java $javaExecutable --jar plugin-maintenance/server/target/at-plugin-maintenance-0.11.0-SNAPSHOT.jar --python $pythonExecutable
python plugin-maintenance/scripts/model_smoke_test.py --java $javaExecutable --jar <保留的0.9-JAR> --python $pythonExecutable --previous-jar <0.8.0的JAR>
python plugin-maintenance/scripts/model_submission_smoke_test.py --java $javaExecutable --jar <保留的0.10-JAR> --python $pythonExecutable --previous-jar <保留的0.9.0-JAR>
python plugin-maintenance/scripts/model_source_budget_smoke_test.py --java $javaExecutable --jar plugin-maintenance/server/target/at-plugin-maintenance-0.11.0-SNAPSHOT.jar --python $pythonExecutable --previous-jar <保留的0.10-JAR>
python plugin-maintenance/scripts/media_budget_smoke_test.py --java $javaExecutable --jar plugin-maintenance/server/target/at-plugin-maintenance-0.14.0-SNAPSHOT.jar --python $pythonExecutable --previous-jar <保留的0.13-JAR>
```

冒烟脚本的 `--java`、`--python` 接受实际可执行文件路径或 PATH 中的命令。原冒烟启动六次临时实例，检查 H2 文件重启、画像、配置指纹、失效状态、终态取消、真实 worker 回执、双绑定导出、连接停用和真实后台调度。测试只使用本机虚构服务和临时数据库。原冒烟与容器冒烟可增加 `--previous-jar <旧JAR路径>`；0.8 历史检查覆盖 0.1.0/0.7.0→0.8.0，不补造历史绑定、合同或执行配置。0.9 模型脚本覆盖模型建议、在途强停和预算/幂等恢复；0.10 脚本要求保留的 0.9 JAR，覆盖 V8→V9、来源和明确自动递交。0.11 脚本要求保留的 0.10 JAR，覆盖 V9→V10 源级回填与额度恢复。已执行项目见实现状态，不推定全部旧冒烟已重跑。

0.16 的 `model_usage_costs_smoke_test.py` 使用保留的 0.15 JAR 验证 V14→V15、历史 usage 回读但金额未知，以及新调用费率结算；0.15 的 `model_named_budget_smoke_test.py` 和 V13→V14 历史检查仍保留。0.17 使用以下脚本检查 V15→V16、三层金额预占、未知历史、旧键幂等和跨进程超额阻断：

```powershell
python plugin-maintenance/scripts/model_cost_budget_smoke_test.py --java java --python python --previous-jar .codex/artifacts/at-plugin-maintenance-0.16.0-SNAPSHOT.jar --jar plugin-maintenance/server/target/at-plugin-maintenance-0.17.0-SNAPSHOT.jar
```

旧 JAR 需使用保留的 0.16 交付，源码 ZIP 不内嵌构建后的 JAR。脚本仅启动回环假 provider、临时文件 H2 和六个临时 JVM，不接受真实 API 地址。实际结果见 [0.17 冻结记录](model-cost-budgets-result.json)。

0.14 媒体预算脚本使用四个临时 JVM，覆盖 V12→V13、历史预算未知、真实本机 HLS、源/主机间隔、日额度、在途强停后许可/额度保留、重启不重发和配置变化后的冻结导出。0.13 的 `media_probe_smoke_test.py` 用于其保留的历史 JAR；其连续提交多个媒体任务的成功预期不适用于 0.14 默认间隔，不应通过关闭预算来复现旧脚本的成功数。

容器冒烟覆盖默认禁用、固定源门槛、执行配置冻结/导出/变化、文件库重启及清理未确认的失败恢复。其 `--previous-jar` 升级夹具要求 0.7.0 的修复 API，另验证旧故障指纹、冻结证据、候选和已用次数不变。只操作自己停止后的临时 H2 模拟取消中崩溃，不运行 Docker。Python 容器协议测试中的 Engine 为假件，子进程传输测试只运行本地 Python；Java 容器测试中的执行服务亦禁用或显式模拟。三者均不证明 Linux 内核限制、镜像或安全隔离。

修复冒烟单独启动三次临时实例，禁用 AT 与调度：先拒绝签名退化，再接受正确的静态候选，检查当前源码不变、全部回合与哈希导出、两次文件库重启、次数保留、重复补丁停止、取消恢复门槛和画像失效。可加 `--archive-out <ZIP路径>` 保存实际候选示例包；示例数据不产生模型调用或运行时修复证据。

调度冒烟只在停止自己的临时实例后，用最终 JAR 内的 H2 Shell 将临时库该源设为到期；不修改产品最短间隔或真实库。随后由真实 `@Scheduled` 投放并完成可信 worker，再重启验证已用预算与活动关联，最后改当前策略并核验双策略导出。

Java 测试另覆盖运行/排队取消、队列容量释放、取消与完成竞态、清理失败、取消中的重启恢复、嵌套真实 Python 进程终止与总超时。解释器子进程清理只用于本模块的可信程序；这不是任意爬虫的安全沙箱。

开发用的直接 worker 夹具需要 `requests`、`pycryptodome`、`lxml` 和相同布局的 AT 源码（当前仓库或解压后的完整离线源码包）：

```powershell
python plugin-maintenance/scripts/test_at_runtime_fixture.py
python plugin-maintenance/scripts/build_runtime.py --check
```

它只运行本模块编写的可信 `fixtures/demo.py`，阻断 socket/DNS/requests 网络入口，沿真实 worker/ATVP 的首页→分类→详情→取链及搜索路径传递输入。报告同时记录当前文件字节 SHA、规范 LF SHA 和基线提交；固定哈希已与该提交的 Git 对象核对，忽略 CRLF 展开差异，仍拒绝源码变化。夹具重放不需要 Git 历史。此脚本不提供任意爬虫的安全沙箱，不能改成直接执行未知源码。

`build_runtime.py` 在固定 AT 基线规范 LF 哈希一致时重建运行时快照；`--check` 只核对。模块 `.gitattributes` 固定 Python 和清单为 LF，避免 Windows Git 自动展开为 CRLF。更新维护启动器或示例后必须重建清单并审阅差异。JAR 启动时逐文件复核清单，任务执行前再次核对冻结身份。

固定基线的 T4 HTTP 局部合同实验现可独立运行：

```powershell
python plugin-maintenance/scripts/run_t4_contract_lab.py --check
python plugin-maintenance/scripts/test_t4_contract_lab.py
python plugin-maintenance/scripts/run_t4_contract_lab.py --maven mvn.cmd --offline
python plugin-maintenance/scripts/run_t4_contract_lab.py --maven mvn.cmd --offline --candidate t4-parameters-v1
python plugin-maintenance/scripts/run_t4_contract_lab.py --maven mvn.cmd --offline --candidate t4-filters-v1
python plugin-maintenance/scripts/run_t4_contract_lab.py --maven mvn.cmd --offline --candidate t4-home-v1
python plugin-maintenance/scripts/run_t4_contract_lab.py --maven mvn.cmd --offline --candidate t4-identity-v1
python plugin-maintenance/scripts/run_t4_contract_lab.py --maven mvn.cmd --offline --candidate t4-bridge-v1
python plugin-maintenance/scripts/run_t4_contract_lab.py --maven mvn.cmd --offline --candidate t4-generation-v1
python plugin-maintenance/scripts/run_t4_contract_lab.py --maven mvn.cmd --offline --candidate t4-maintenance-v1
python plugin-maintenance/scripts/run_t4_contract_lab.py --maven mvn.cmd --offline --candidate t4-journal-v1
python plugin-maintenance/scripts/run_t4_contract_lab.py --maven mvn.cmd --offline --candidate t4-recovery-v1
```

`--offline` 要求已准备原 AT 的 Maven 依赖；首次可省略以获取依赖。实验只监听本机随机端口，v10 另使用独立临时文件 H2 执行 JPA/Flyway 验证，不启动 AT 主应用或连接运行中的 AT 数据库。它继承原 POM 编译全部 803 个 Java 文件，v10 候选另加 3 个 Java 类型。当前固定脚本每组包含 109 项局部实验与 63 项原 AT 回归；J 系列持久路径在 v10/v11 执行，Q01–Q12 仅在 v11 执行恢复路径，其他组明确记录未实现。整套总期限为 300 秒，单动作及退出等待期限保持原值。输出均在自身 `at-contract-lab/target/`，完整源码包解压后也可运行。已知差异的复现断言通过不能解释为完整 AT 兼容通过。真实执行、模拟依赖、Python hook 和全部未验范围详见实验说明。

后续继续在真实 Linux cgroup v2 Engine 构建镜像并验收资源控制、超时/取消/崩溃清理；独立推进已复现的 T4 兼容修正、配置过滤器、完整 AT/实际装载身份和候选运行时对照，继续把媒体额度接到受管源出站，补注册域名/精确流量控制、媒体矩阵、精确模型成本及按模型细分预算、受控发布。当前主机无可用 Docker/Podman/已配置 WSL，未知源执行仍待隔离环境验证。当前未声明 MySQL/PostgreSQL 或 Native Image 已验证。独立模块已列出原生资源匹配项，但未提供 Native 构建承诺；未来合入 AT 时仍需把实体/DTO 纳入 AT 的 `Main.java` 反射扫描并执行单独原生验证。

0.11 增加的源级额度、预算 API、UTC 日期与升级回填见[预算合同](model-source-budgets.md)。上面的 0.9/0.10 模型冒烟命令只用于各自保留的历史 JAR；当前模型检查使用新的 source-budget 冒烟。当前请求配置 SHA 因预算版本改变，不再满足历史脚本的“默认配置 SHA 不变”断言。
