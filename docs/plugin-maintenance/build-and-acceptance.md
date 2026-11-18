# 本机开发与 NAS 封装验收

## Windows 项目存储入口（2026-11-15）

在项目 PowerShell 中构建、安装依赖或运行诊断前，先执行：

```powershell
. ./plugin-maintenance/scripts/use_project_storage.ps1
```

临时文件统一到工作区 `.codex/tmp`；npm、pip、uv、Playwright、Maven 缓存统一到
`.codex/cache`，Maven 沿用现有 `cache/m2`。这些变量仅对当前 PowerShell 及子进程生效，
不修改系统环境、全局工具配置或其他项目。退出后新开终端需要重新加载。
`start_local.ps1` 与 `database_archive.ps1` 已自动加载，并显式传入 Java 临时目录。
已经运行的进程不因此改变路径，本次不为路径调整重启工作台或旧服务。
项目运行库继续放在 `plugin-maintenance/server/data`，操作记录与交付物放在项目根的 `recovery`。
不要把共享 Codex 活动数据库、会话和全局运行时直接搬入项目或删除。
本次源码/进度与磁盘实测见 [项目盘点](source-progress-storage-audit-2026-11-15.md)。

## 固定分工与历史部署记录

以下实例身份为各轮历史记录；当前身份以[最新盘点](source-progress-storage-audit-2026-11-15.md)与实际进程为准。

当前18990为JAR`634e236d`，同库6源，产品隧道34944同时连接黄果/央视。
两NAS broker均使用`managed_broker_service.py`，空journal及选定已完成真实任务journal的
可控停启均已验收，详见[运维说明](managed-runner-operations.md)。
管理页面路径和来源切换接线已单独验证；以下旧进程/单源说明只是对应历史回执，
不作为当前运行入口，不重启旧setup或按旧owner文件发信号。

2026-11-12 用户确认采用以下固定流程：

- 本机维护唯一开发工作区，修改源码、构建前端/JAR、执行受影响的定向检查。
- NAS 接收白名单构建上下文，封装 Linux 镜像，并在独立实验容器/数据目录完成真实验收。
- 回传 JAR/上下文 SHA、镜像 ID、任务结果、清理结果；生产 AT 仅作获准的只读取样。
- 成批完成必要改动后统一检查，不因移交构建地点而重做已经闭环的源或宿主验收。

不迁移开发工作区或本机数据库，不在 NAS 安装 Node/Maven，不开启新的独立容器产品分支。
当前 Spider 执行器镜像、独立工作台 JAR、AT 内置镜像是三种不同产物，不能混称。
现有脚本覆盖内置镜像和隔离执行器；完整独立服务容器仍按原计划后续开发。

## 操作入口

当前 HTTP 验收镜像改用[产品源码入口](../../plugin-maintenance/http-acceptance/README.md)：
根 POM `maintenance-http-acceptance` 生成专用 JAR，再由
`build_managed_http_context.py` 导出白名单上下文。运行时不依赖历史实验回执或测试配置。
2026-11-13 已通过本机 HTTP 夹具及打包，当前NAS镜像`b87d792b`的片库原样模型
候选7/7及R04/R05也已独立核验；证据见
`recovery/simple-samples-20261110/product-loader-b0869b64f19d/current-http-verification.json`。
不重做该验收，也不沿用其他镜像的回执。此包不是完整独立维护服务容器或AT内置运行包。

源码变化后按[内置构建说明](embedded-integration.md#构建)在本机产生 JAR。
只修改前端时只重建前端和对应 JAR；没有行为变化时不重跑旧全量。
不要对正在运行的 JAR 原地打包，也不要同时打开同一个 H2 库。

本电脑已有 NAS 适配器位于工作区外的 `D:/源主动维护项目/recovery/`，
复用既有 SSH 主机校验和 `FN_NAS_*` 凭据环境，不将账号、密码、密钥写入上下文或回执。
在该目录、凭据已按现有授权加载到进程环境后执行：

```powershell
py -3.14 -X utf8 nas_embedded_host.py --package-only
```

该入口只上传当前内置 JAR 和固定启动/探针文件，以已固定摘要的 NAS 基础镜像离线封装；
校验上传 JAR SHA，记录 image ID 和生产前后身份，不创建或启动验收容器。
`receipt.json` 的 `acceptanceScope=image_packaging`、`fullHostAcceptance=false`，
不能用 `completed=true` 冒充完整业务验收。每次使用新的实验目录和标签，不覆盖旧证据或生产标签。

只有实际变化需要完整宿主验收时，才执行原入口：

```powershell
py -3.14 -X utf8 nas_embedded_host.py
```

它在新隔离目录完成完整 AT 启动、API/Web/数据库重开与清理。
受管源联网和巡检仍走既有批准的 broker/AT/T4 链，不给内置宿主实验开放生产网络或挂载。
封装只是一层产物检查；真实模型、源运行、调度与播放器的结论分别记录。

## 已有证据与接续

本次现场只读核对：NAS 为 Linux/amd64，已验收镜像
`sha256:27a7ad5e8372c5001ee47ad9a9007483e2c5db8a48f2ba8ab343644600b798a6`
仍在；旧片库控制容器 `83341d9a...4278b` 已停止，NAS 回环 18907 无监听，
生产 AT/T4/AList 身份前后相同。本机 18990 与旧 8797 保持原进程，不重启。

完整宿主验收继续复用 `recovery/embedded-host-20261112/verification.json`，
当前包没有业务变化，不再次构建或启动验收。新增封装分支仅做无网络、无 Docker 的定向控制流检查。
原 M1–M5 目标保持 active；首次受管巡检已于2026-11-12完成真实投放、预算结算、
清理及停用计划/报告/额度跨重启核验，详见[当前进度](implementation-status.md)顶部。
本次检测出原件5/7，不代表源健康或连续试运行；不重做该实验。
上述为2026-11-12的历史环境检查。随后真实模型、黄果图片及片库隔离宿主采用
已分别闭环，见[当前进度](implementation-status.md)顶部，不再重跑。
前轮本机升级与黄果候选移交仍见`recovery/workbench-delivery-20261113/verification.json`。
当前18990为独立新包`0d13a4c1...ab3846`，同库/5源/全部历史/连接/预算保持，
经回环18991的固定SSH socket转发接通NAS隔离broker，实际身份检查ready。
6个Java/7个前端定向检查和前端构建通过，单次skipTests package完成；
不重跑旧源或模型。本轮只认证通道接线，NAS journal任务为0，未新增业务验收。
当前配置沿用黄果冻结出站策略，不代表其他源已接通；隧道/broker尚未配置重启自启。
页面已HTTP交付，未进行浏览器交互实测。权威
`recovery/workbench-runner-link-20261113/verification.json`，下一长期真实使用，
原M1-M5保持active，8797/生产/冻结ZIP不变。
