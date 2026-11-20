# 维护库与 AT 内置 H2 冷备份

需要一并恢复AList SQLite、本地插件/静态文件和当前JAR时，使用外层
[实例冷归档](instance-archive.md)。它复用下述H2校验，不替换或放宽原单库入口；
恢复到新目录，不自动启动实例或重置凭据。

## AT 内置主库适配

内置包新增独立入口 `cn.har01d.atmaintenance.tools.EmbeddedDatabaseArchiveTool`，
复用原有冷归档实现，不启动 Spring、AT、AList、模型或 Spider。
旧独立库归档类型和原有1GiB限额仅作历史工具兼容；当前发行只提供 AT 内置 JAR，不构建独立服务。

AT模式接受显式选择的任意名称`.mv.db`文件，按实际数据库结构识别内置主库，
不强迫运行库必须叫`atv.mv.db`。要求AT核心表、维护表和成功的维护迁移均存在；当前维护历史与宿主历史分开记录。
备份完整PUBLIC表，包含AT保存的插件正文、维护源码/候选、模型额度与巡检/发布台账。
单库上限64GiB，按流读取；不会把大AT库套入独立工作台的1GiB限额。
ZIP类型为`alist-tvbox-at-embedded-h2`，内含规范名称`atv.mv.db`；
它与独立维护库ZIP不能互相混用。

先停止使用该库的全部实例，仅对自己持有的离线副本操作。数据库密码只通过
`AT_DB_PASSWORD`环境变量提供，不沿用独立工作台密码变量，也不放命令行或回执。
脚本新增`-Deployment at-embedded`，`-JarPath`可显式选择已经构建的内置包：

```powershell
& $pwsh -NoProfile -File plugin-maintenance/scripts/database_archive.ps1 `
    -Deployment at-embedded -Operation backup `
    -InputPath 'D:\at-lab\data.mv.db' -OutputPath 'D:\at-backups\owned-at.zip'
& $pwsh -NoProfile -File plugin-maintenance/scripts/database_archive.ps1 `
    -Deployment at-embedded -Operation inspect `
    -InputPath 'D:\at-backups\owned-at.zip' -TrustedSha256 $trustedSha
& $pwsh -NoProfile -File plugin-maintenance/scripts/database_archive.ps1 `
    -Deployment at-embedded -Operation restore `
    -InputPath 'D:\at-backups\owned-at.zip' -OutputPath 'D:\at-restored' -TrustedSha256 $trustedSha
```

`$pwsh`指PowerShell7路径，`$trustedSha`必须取自自己留存的成功备份回执。
恢复结果仅写入新目录下的`atv.mv.db`和`recovery-manifest.json`，
核对归档及数据库SHA、CRC、H2版本、全表行数和迁移身份，且保持归档数据库原字节。
不覆盖现存库、不运行Flyway升级、不自动启动或恢复联网。
来源库名不同时，只在隔离副本的启动配置中显式指向新的`atv`路径，不修改原实例配置。
首次启动副本前仍须禁用模型/巡检/外部执行，并核对快照之后的预算和外部操作。

**这是AT主H2数据库恢复，不是完整NAS实例恢复。**
AList SQLite、静态文件/本地插件、外部连接密钥、挂载目录、运行器和镜像不在包内。
AT原JSON备份仍不包含插件正文，也未在本次改动中改写；本工具不依赖其恢复处理器。
备份含管理凭据、订阅/会话等私有数据，未加密，禁止公开或进入上游补丁。
正式发布切换、外部文件恢复、跨H2版本迁移、MySQL/PostgreSQL和生产验收仍单列未完成。

## 旧私有 RC 混合历史

当前内置维护迁移使用独立 `maintenance_schema_history`。若原冷库将维护 V59-V75 与宿主历史混在 `flyway_schema_history`，只能使用匹配版本的 `cn.har01d.atmaintenance.service.LegacyMaintenanceExport` 导出到不存在的新文件，不直接启动或覆盖原库。

工具接收原 `.mv.db` 路径、新 `.mv.db` 路径和可信原文件 SHA256 三个位置参数；账号与密码仅读取 `MAINTENANCE_EXPORT_DB_USER`、`MAINTENANCE_EXPORT_DB_PASSWORD`。它只读原冷库，核对完整历史和业务签名，在副本中分离迁移历史并保留原始历史表，再校验新历史。不执行 Flyway repair，不清空任务或费用，也不接受不完整的混合历史。

导出后以新目录隔离启动，再进行正常维护迁移。升级回退须同时恢复配套 JAR、冷库和实例文件，不能用旧 JAR 直接读取已升级库。
