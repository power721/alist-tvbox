# 离线部署包

`plugin-maintenance/scripts/deployment_archive.py` 1.0.0 在可信的[实例冷归档](instance-archive.md)外，再携带该实例记录的完整 Docker 镜像。解决只有镜像ID、离线目标机器无法取得镜像的问题；不替代整机备份，不包含Docker daemon、Java/Python安装、SSH信任、NAS服务配置或外部执行通道。

## 打包

先按实例归档说明得到停机实例ZIP及自己保留的可信SHA。容器实例必须在该归档中记录实际 `imageId`；直接JAR实例没有镜像ID时明确停止，不猜标签、不拉镜像。

```text
py -3.14 -B -X utf8 plugin-maintenance/scripts/deployment_archive.py pack --instance-archive D:/private/instance.zip --trusted-instance-sha256 <可信实例SHA> --output D:/private/deployment.zip
```

工具在当前Docker daemon中核对固定ID，以 `docker image save` 导出该镜像。包内固定包含 `instance.zip`、`image.tar`、两份恢复脚本和逐文件大小/SHA清单。只记录镜像ID、操作系统、架构，不导出环境变量值、不收集其他镜像或生产容器配置。完成后保留回执的 `archiveSha256`。

包携带数据库、既有私有配置和镜像全部文件，**未加密，必须私下保管**，不能提交Git、公开仓库或上游审阅包。压缩与恢复会在输出目录的父目录使用临时空间；目标盘应同时容纳镜像tar、部署包和恢复数据。Windows命令先点载项目存储脚本，全部输出放项目盘。

## 检查与恢复

```text
py -3.14 -B -X utf8 plugin-maintenance/scripts/deployment_archive.py inspect --archive D:/private/deployment.zip --trusted-sha256 <可信部署包SHA>
py -3.14 -B -X utf8 plugin-maintenance/scripts/deployment_archive.py restore --archive D:/private/deployment.zip --trusted-sha256 <可信部署包SHA> --output D:/owned-restored --java <Java路径> --docker <Docker路径>
```

`inspect`校验完整包，不调用Docker或Java。`restore`先校验外层及嵌套实例身份，再实际执行 `docker image load`，回读镜像ID/系统/架构，随后调用原有H2冷恢复及SQLite校验。必须只使用自己可信的包；原恢复逻辑会执行包内可信JAR的冷恢复入口。

恢复要求同架构Linux容器运行环境、可用Docker、Java和Python；没有安装或启动它们的副作用。输出目录必须不存在，不覆盖源实例或既有目标。数据库恢复失败时清理本次新建目标目录；已导入的镜像保留，不擅自删除Docker资源。

成功目录包含：

- `instance/instance.json`：已重定位的数据根及原挂载关系。
- `instance/state/`：原实例数据、静态文件和SQLite。
- `instance/runtime/application.jar`：原确切运行包。
- `runtime/`：可再次使用的两份归档工具。
- `deployment-recovery.json`：部署包SHA、实际镜像身份及冷恢复回执。

成功回执始终为 `applicationStarted=false`。恢复不自动启动AT/维护服务、不重投任务、不续模型预算或计划任务。原管理员凭据及私有配置按归档保留；环境凭据引用需另行绑定。先按原挂载关系在新目录隔离启动并核对状态，再另行决定启用服务，不能把镜像导入成功当业务或生产恢复成功。

## 验证范围

四项定向检查一次通过：镜像/实例/工具往返与路径重定位、错误身份/篡改/既有目标拒绝、H2失败清理、无镜像ID时明确停止。没有重复原实例归档套件或Java/Web/爬虫测试。

真实NAS打包、镜像导入及恢复启动的当前结果另见实现进度及对应运行回执；本说明本身不认证另一Docker daemon、整机服务恢复、生产部署或任意账号/挂载布局。

## NAS实证（2026-11-16）

在已停止的实验副本上完成真实打包、镜像导入和恢复启动，运行标识为
`run-20261116-d29933a07ab2`。原副本只读，35份文件先复制并逐文件核对，
只对本次目录中的可写副本冷备份；实例包37个文件，包含一份SQLite快照。

- 实际固定镜像：`sha256:61f3854a7380d2adc827be8a61ada8d50d9c54c93ed06169447501b2bc603ef3`，Linux amd64；实际执行了`docker image save/load`，没有拉取或构建镜像。
- 部署包424828254字节，SHA256为`90d3b007d81320ab5226cef0b289fbca23c752fe092d9371405989fc2ea01b64`。私有包仍在NAS本次实验目录中，未进入源码交付。
- 恢复到全新目录后，正常启动完整AT一次，14次登录及只读请求通过；原管理员、三版源码、COMPLETED巡检历史和唯一DIVERGED上游事件可读，独立管理页HTML/JS/CSS实际HTTP字节匹配。
- AT收到正常终止信号后退出143，验收容器退出0并自动移除；生产身份、镜像ID与原标签、原副本、本机状态及受保护交付物均保持，共六项收尾检查通过。

前序只读H2连接、未传入既有数据库密码及两处私有验收助手文件权限失败均保留；
最后一次只接续未完成的冷归档，不重做准备或复制。启动验收的首次清理观察
早于Docker自动移除完成，原观察保留，只读确认同一容器已移除后收尾，没有重启验收。
这些助手修正不计作Spider修复或模型自动修复成功。

权威回执为外层`recovery/offline-deployment-100-20261116/final-summary.json`；
助手`accept_offline_deployment_100_20261116.py`的已执行阶段全部终态，禁止重放。
本次没有源执行、模型调用、生产写入、镜像构建或本机换版。
本次认证同一Docker daemon上的镜像导出/导入及新目录恢复；
未认证另一台机器或全新Docker daemon，也未关闭整机、生产、任意布局恢复及原M1-M5整体目标。
