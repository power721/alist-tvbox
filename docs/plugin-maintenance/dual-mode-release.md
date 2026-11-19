# 双模式容器交付：0.106.0-RC12 源码候选

本版提供独立服务容器及完整AT内置容器的封装源码。两种模式复用相同维护服务和管理页，不复制第二套业务实现。内置候选基于 AT 1.133.0 上游整理；NAS 内置测试实例已升级到 RC12，独立实例仍为 RC9.1。两种 RC12 JAR 均构建成功，不等于已公开稳定发行。

## 两种入口

| 模式 | 入口 | 登录与数据 |
| --- | --- | --- |
| 独立服务 | `/` | 独立管理员；`/data/maintenance.mv.db`、`/data/connections.json` |
| AT内置 | `/#/maintenance`，导航“源维护” | 复用AT管理员登录、AT数据库；维护API保留ADMIN和CSRF校验 |
| 内置页直接访问 | `/plugin-maintenance/` | 与同源AT共享登录；只允许同源框架嵌入 |

内置模式V72–V74迁移与独立模式V33–V35能力对应；不修改旧迁移。普通AT构建不显示维护导航；仅内置构建设置 `VITE_MAINTENANCE_EMBEDDED=true`。

RC10 将内置迁移资源放入 `db/maintenance/h2`，使用独立 `maintenance_schema_history`，避免与 AT 新增 V59 冲突。旧私有 RC 的共享历史不能直接覆盖升级，迁移与冷备边界见 [本次审阅说明](github-review-2026-11-19.md)。

## 构建

先构建维护Web及独立JAR，见 [独立发行说明](standalone-release.md)。AT Web在 `web-ui` 中执行：

```powershell
$env:VITE_MAINTENANCE_EMBEDDED = 'true'
npm ci --no-audit --no-fund
npm run type-check
npm run build-only -- --outDir '<交付目录>/at-web'
```

根目录使用内置profile打包，不覆盖原静态目录：

```powershell
mvn -Pmaintenance-embedded -Dmaintenance.at-ui-directory='<交付目录>/at-web' -Dmaintenance.embedded.build-directory='<交付目录>/embedded-build' -DskipTests package
```

使用 `plugin-maintenance/scripts/build_service_context.py` 分别指定 `--mode standalone|embedded`、`--jar`、固定摘要的 `--runtime-image` 和 `--output` 导出镜像上下文。运行底座必须具备Java21+、Python3；内置模式还须提供AT使用的 `/opt/alist/alist`、`/alist.json`。RC复用已经安装的AT运行底座，不宣称最小镜像。

解包后必须通过 `maintenance_image.py build` 构建，角色与模式一致，版本0.106.0-RC12，revision使用 `service-context.json` 的 `contextSha256`，传入 `--build-arg RUNTIME_IMAGE=<固定摘要>`。保存完整镜像ID和构建回执，不使用latest充当版本。

`plugin-maintenance/deployment/compose.yaml` 提供部署模板。填入两种已接受的镜像身份、独立管理员密码，选择 `--profile standalone` 或 `--profile embedded`。默认仅本机监听；管理员明确选择监听地址后才对局域网开放。没有生产目录或Docker socket挂载。两种实例不能共用数据库；内置AT的 `/data`、`/opt/alist/data`、`/www` 分别持久化。SQLite运行需要可执行的临时目录，模板已包含。

## 同机 T4 接入

RC4可由平台在首次联网巡检时按源自动准备隔离通道。用户不再逐源输入执行器链接，也不需要把NAS的SSH凭据交给工作台。这里复用维护平台已有的T4隔离装载、会话和HTTP验证模块，不把待修复代码放进生产AT的常驻Worker。

同机安装仍需管理员完成一次基础设施准备：Linux、Docker、systemd、Python3及已验收的执行/HTTP镜像和runtime目录。`--templates-file` 是已验收 `broker-service.json` 配置的JSON数组，不是生产插件清单或凭据文件。使用已有固定摘要和对应的runtime版本；此安装器不会下载或构建缺失镜像，也不适用于未准备执行器的空白NAS。

```sh
sudo python3 -I plugin-maintenance/scripts/install_local_t4.py \
  --root /opt/at-maintenance/t4 \
  --templates-file /secure/accepted-t4-templates.json \
  --unit at-maintenance-t4-main
export MAINTENANCE_T4_IPC_ROOT=/opt/at-maintenance/t4/api
docker compose -f plugin-maintenance/deployment/compose.yaml \
  -f plugin-maintenance/deployment/compose.t4.yaml --profile standalone up -d
```

内置部署改选 `--profile embedded`。每个实例使用独立的安装root、systemd unit、Compose项目和数据卷，不让两个工作台共用同机准备目录。安装root必须是新目录，不能覆盖已有实例；保留既有目录时核对身份后恢复原服务，不重复安装。

## 私有离线安装包

RC7的 `scripts/t4_install_bundle.py` 可从已接受的执行器目录和镜像制作一次性安装目录，供同架构Linux主机接收。制作端需要Docker中已有固定镜像，运行目录中的依赖必须已备齐；不从生产数据目录复制来源、会话、账本或凭据。此包包含第三方运行依赖，未经许可审核只能用于获准的私有交付，不等同于公开源码包。

```sh
sudo python3 -I plugin-maintenance/scripts/t4_install_bundle.py pack \
  --runtime-root /secure/accepted-runtime \
  --templates-file /secure/accepted-templates.json \
  --image-imports-file /secure/source-image-imports.json \
  --service-image sha256:<独立服务镜像完整ID> \
  --service-image sha256:<内置服务镜像完整ID> \
  --version 0.106.0-RC7 --output /secure/maintenance-offline
```

`pack`输出清单SHA，须与安装目录分开记录核对。接收方管理员准备Docker、systemd、Python3.11+，将整个目录放到私有位置，执行：

```sh
sudo python3 -I /secure/maintenance-offline/t4_install_bundle.py install \
  --bundle /secure/maintenance-offline --expected-sha256 <清单SHA256> \
  --root /opt/at-maintenance/t4 --unit at-maintenance-t4-main
```

安装器先核对全部文件，再导入固定镜像、建立新的runtime和T4服务，复用原安装器而非另一套服务实现；不覆盖原实例。离线镜像以完整ID导入，服务Compose也使用这些ID，不依赖可变tag。安装成功后按上节导出 `MAINTENANCE_T4_IPC_ROOT` 启动所选工作台；模型和AT连接另由用户授权配置。安装验证与网站修复成功是两项独立证据，不能相互替代。

工作台只挂载只读IPC目录，以65532用户访问内部socket；Docker socket、root管理脚本、执行账本和SSH凭据不进入工作台容器。root服务负责实际隔离容器创建及清理。准备服务和工作台分别重启均不应重放来源任务；活动执行器依赖镜像及runtime目录必须纳入镜像保留清单。

未挂载同机覆盖文件的部署仍可使用原外置受管T4通道。内部连接正常只证明准备服务可通信，不等同于某个来源健康；来源必须经过实际联网执行和对应节点验收。NAS双模式的RC4实测边界见 [集成验收记录](t4-integration-rc1064-2026-11-18.md)。

## 人工验收

RC6允许本地新来源向明确配置的AT实例增量新增，无需伪造原插件绑定。新来源不支持替换，不匹配同名旧插件，也不标损坏旧插件；目标指纹绑定AT实例、来源和原始源码。自动扫描仍不接管未绑定来源，须显式加入持久队列后由平台执行。已有原件绑定的来源保留增量/替换流程。

1. 独立页登录后检查来源列表及“模型与连接”，能配置模型中转和AT连接。
2. AT管理员登录，点击“源维护”；无需再次登录，同页可操作维护工作台。
3. 直接访问内置页，检查列表、连接设置、持久发布队列；增量为默认选项，替换须明确选择。
4. 新建仅用于验收的来源，重启本测试容器，确认来源和设置保留；不要使用生产容器做这项检查。
5. 已安装同机T4时，首次联网巡检应自动准备来源通道；未安装时仍需获准外置执行器。模型修复另需有效模型连接。未接入时界面必须明确提示，不能将空列表或页面200当作全链通过。

容器首次安装默认不启用模型自动递交、定时执行或生产自动发布。启用这些动作须使用该实例的获准连接和执行器身份。测试容器不导入生产凭据、来源或旧任务历史。

## 发行边界

本次容器验收覆盖服务安装、双入口、鉴权、写入和重启持久化，不替代真实未知故障的无人值守巡检→模型→复测→增量发布。公开发行前仍需核实上游许可材料；当前无公开仓库/镜像推送。验收后按镜像生命周期规则保留当前、一份已接受回滚和所有运行依赖，限定角色清理废弃镜像，不全局prune。
