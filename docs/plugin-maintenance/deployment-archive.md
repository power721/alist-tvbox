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

## 适用边界

镜像导入、数据恢复、宿主启动与业务恢复分别确认。工具不恢复整机服务，不保证任意挂载布局或跨平台镜像可用；不得把文件恢复成功等同于来源健康或生产部署完成。
