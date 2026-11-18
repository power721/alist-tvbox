# 插件主动维护平台：独立 JAR 候选版

当前源码版本0.106.0-RC9.1。这是本地发行候选，不是已经公开发布的稳定版。独立包包含完整服务容器的封装源码；内置AT宿主另行构建，不包含在本独立源码包。Python执行器与工作台进程隔离。

两种完整服务容器、内置导航、持久化与人工验收见 [双模式容器交付](dual-mode-release.md)。独立源码包仅附带该说明，不包含AT宿主源码。

## 包含与排除

包含Java控制面、独立Vue管理页、隔离执行器源码/冻结资源、必要测试、构建锁与启动工具。`release-manifest.json`记录每个输入的字节数和SHA256；来源源码、连接凭据、数据库、历史报告及缓存不随包交付。

上游署名与公开发行前未决许可事项见根目录 `NOTICE.md`。本候选不为上游代码重新授予许可。

## 从源码构建

需要Java21、Maven3.9、Node.js/npm和Python。使用Windows启动器需要PowerShell7。执行器另需受支持的Linux Docker环境，不在本机直接运行未知爬虫。

在解包根目录执行；先准备Web，再打独立JAR，不使用AT根POM：

```powershell
. .\plugin-maintenance\scripts\use_project_storage.ps1 -Quiet
Push-Location plugin-maintenance/web-ui
npm ci --ignore-scripts
npm run build
Pop-Location
mvn -f plugin-maintenance/server/pom.xml -DskipTests package
```

正式候选构建须使用清单中的 `buildTimestamp` 作为Maven的 `project.build.outputTimestamp`；固定源码/锁文件不等于跨工具链已验证的逐字节可复现二进制。

## 首次启动

选择新的数据目录及空闲端口，明确指定Python路径：

```powershell
pwsh -NoProfile -File plugin-maintenance/scripts/start_local.ps1 -Port 18992 -DataDirectory 'D:\维护平台数据' -PythonExecutable '<Python绝对路径>'
```

也可直接启动JAR，显式设置独立数据目录和端口；不要同时打开同一个H2数据库。默认仅回环监听，管理员账户密码为admin/admin；对外监听前必须更改默认凭据。

## 接入与发布

在“模型与连接”中配置获准AT地址/认证方式和模型中转Base URL、模型别名、API Key。密钥留空保留已保存值，不在读取接口回显。配置保存在所选数据目录的 `connections.json`，备份时作为私有文件保护。

联网巡检必须接入隔离执行器；仅JAR启动成功不代表可巡检。推荐按双模式说明安装同机T4准备服务，工作台通过内部IPC按来源准备执行环境，无需逐源填写链接。RC7提供固定镜像与runtime的私有离线安装包工具，不复制旧实例数据；接收端仍需管理员准备Linux、Docker、systemd和Python3.11+。也可按 `plugin-maintenance/examples/managed-runner-routes.json` 与 `managed-runner-tunnels.json` 配置已授权的外置通道。

启动器的 `-ManagedRunnerRoutes`、`-ManagedRunnerTunnels`、`-TunnelPythonExecutable` 选择管理员配置；依赖见 `plugin-maintenance/scripts/requirements-tunnel.txt`。选择 `-AutomaticRepair` 才启用自动递交，选择 `-AutomaticSourceSetup` 还需完整routes/tunnels；不填执行器参数时保持源执行关闭。

修复成功后可进入持久发布队列，默认增量，支持显式替换。自动发布还需 `-AutomaticPublication` 和获准AT连接对应的 `-AutomaticPublicationInstanceKey`；不要复制其他实例的身份。未开启自动发布时，成功候选仍需在平台发起发布。定时巡检与生产写入不是安装行为自动授予的权限。

新来源无需原插件绑定即可增量新增，不能替换已有插件。RC9在自动维修的本次联网验收成功后自动进入持久发布队列，仍须启用获准实例的自动发布策略；升级不会回放历史未绑定成功来源。用户也可显式入队。发布目标指纹绑定实例、来源及原始源码，完成后回读插件名称、路径和候选源码SHA。修复结果与发布结果分别记录，发布失败不伪装成修复失败。

RC9.1使旧候选复验与正常巡检共用来源环境自动准备：新运行器尚无来源路由时，先按原来源SHA准备隔离环境，再冻结当前策略并运行；失败保留原候选、历史和剩余轮次，不因基础设施问题调用模型。

## 升级与备份

停止旧进程，按现有归档工具备份独立数据目录，再用新JAR和原数据目录启动。不要覆盖 `connections.json`，也不要删除H2、运行器路由或私有凭据文件。升级验证只认证其对应旧版到当前版路径，不承诺任意历史版本直接升级。数据库升级后不能假定旧JAR可直接回退；回退应使用升级前冷备副本。

## 已知范围

资料导航、磁力和网盘按各自画像验收，不强制要求直播放链接；复杂功能源和需要凭据的来源应明确处理。网站访问受限、真实中转兼容、长期无人值守成功率、生产装载与播放器不由静态检查证明。

备用域支持无完整浏览器的公开HTTP取证，DNS准备失败可继续原任务；真实4K影视任务已完成检索及一次发现模型调用，但候选站点身份和业务结构未确认，未生成修复候选。Javxx/hanime原HTTP400失败仍保留，未追加调用。C10详情选集提供字段级反馈和一层有界JSON字符串结构证据；新增诊断不等于原来源已修复。最新从未知故障到自动发布的一次完整真实验收仍待完成，不应将本候选称为任意网站零干预修复稳定版。
