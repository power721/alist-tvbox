# 维护库与 AT 内置 H2 冷备份

需要一并恢复AList SQLite、本地插件/静态文件和当前JAR时，使用外层
[实例冷归档](instance-archive.md)。它复用下述H2校验，不替换或放宽原单库入口；
恢复到新目录，不自动启动实例或重置凭据。

## AT 内置主库适配（0.40）

内置包新增独立入口 `cn.har01d.atmaintenance.tools.EmbeddedDatabaseArchiveTool`，
复用原有冷归档实现，不启动 Spring、AT、AList、模型或 Spider。
独立工作台入口、文件类型和原有1GiB限额保持不变；AT模式只从内置JAR提供。

AT模式接受显式选择的任意名称`.mv.db`文件，按实际数据库结构识别内置主库，
不强迫运行库必须叫`atv.mv.db`。要求AT核心表、维护表和成功的V59维护迁移均存在；
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

## 独立工作台历史说明

版本：`0.23.0-SNAPSHOT`。日期：2026-11-09。范围仅为独立维护工作台的 H2 文件，不适用于 AT 业务库。

## 合同与边界

`DatabaseArchiveTool` 是 JAR 内独立 main，通过 `PropertiesLauncher` 调用，不启动 Spring、任务执行器或网络客户端。备份前必须停止使用该独立库的全部实例；工具以 H2 独占连接执行 `BACKUP TO`，运行中占用返回 `DATABASE_IN_USE`，不退回直接复制活动数据库。

备份包含原数据库的全部表、源码、画像、绑定、探测报告、修复会话与候选、发布准备、导入原包、上游协调、额度及 Flyway 历史。工具同时记录 PUBLIC 表行数与迁移身份。恢复只接受本工具的两个固定 ZIP 条目，检查可信外部 SHA256、大小、CRC、数据库 SHA、严格 JSON、H2 版本、行数与迁移历史，随后以只读 H2 核验。目标必须是不存在的新目录，成功前不发布，失败清理临时文件；不会覆盖旧目录或自动启动应用。

这是含私有数据的**未加密备份**，数据库登录密码不等于备份加密。ZIP 不可公开发布、提交 Git 或作为普通脱敏报告流转。密码仅读取 `MAINTENANCE_DB_PASSWORD`，不写入回执，也不在命令行传递。

SHA256 只能核对完整性，不能认证来源。`-TrustedSha256` 必须来自自己保留的可信备份回执，不可把陌生 ZIP 当前算出的哈希当作可信依据。只读打开仍会解析 H2 元数据，不是执行隔离；禁止恢复陌生数据库。`inspect` 不打开 H2，只检查包与字节身份。

本版本只验收相同 H2 版本、V18 独立维护库及相同产品版本的备份/恢复。不是跨 H2 版本迁移工具、AT 主库备份、发布回滚或完整离线安装包。工作台密码、AT/模型密钥环境、Python/Java 与依赖不由数据库备份恢复。

## 操作

在项目根目录，先使用 Java 21 构建当前 JAR。下例中的备份父目录必须已存在，ZIP 与恢复目录必须不存在；不要对运行中的 AT 数据文件操作。

```powershell
$pwsh = 'C:\Users\wab201\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe'
& $pwsh -NoProfile -File plugin-maintenance/scripts/database_archive.ps1 `
    -Operation backup `
    -InputPath 'D:\maintenance-data\maintenance.mv.db' `
    -OutputPath 'D:\maintenance-backups\owned-cold.zip'
```

保留成功 JSON 回执及其中的 `archiveSha256`，与备份包分开保管。回执还包含数据库 SHA、大小、表行数、迁移身份和工具 class SHA。输出不会包含源码或数据库密码；备份本身仍是私有数据。

只读检查和恢复：

```powershell
$trustedSha = '<自己保留的备份回执中的64位小写SHA256>'
& $pwsh -NoProfile -File plugin-maintenance/scripts/database_archive.ps1 `
    -Operation inspect -InputPath 'D:\maintenance-backups\owned-cold.zip' `
    -TrustedSha256 $trustedSha
& $pwsh -NoProfile -File plugin-maintenance/scripts/database_archive.ps1 `
    -Operation restore -InputPath 'D:\maintenance-backups\owned-cold.zip' `
    -OutputPath 'D:\maintenance-restored' -TrustedSha256 $trustedSha
```

成功后新目录包含 `maintenance.mv.db` 和 `recovery-manifest.json`。恢复工具保持数据库条目原字节，不运行 Flyway；应用首次启动仍按正常 Flyway/JPA 流程核验。保留原库，不复制覆盖它。先确认旧实例已停止，再以本机受限启动器检查恢复副本：

```powershell
& $pwsh -NoProfile -File plugin-maintenance/scripts/start_local.ps1 `
    -DataDirectory 'D:\maintenance-restored' -Port 8798
```

启动器只绑定回环，并显式关闭 AT、模型、自动递交、容器、巡检与媒体；清空 AT token/身份路径及 Engine 路径/端点/镜像。不要用普通启动命令继承旧外部配置来代替这一步。

## 恢复后的状态

- 完成的源码、报告、候选及冻结证据保留，不把历史结果提升为当前源健康或发布资格。
- 在途静态、修复、模型和媒体任务沿用现有启动恢复规则中断，不自动重发。未知的旧容器清理保持 `CONTAINER_CLEANUP_UNCONFIRMED`，不能声称旧主机已清理。
- 巡检和模型等已用额度保留到快照时刻，不因恢复清零；UTC 周期及既有时钟回退规则继续适用。
- **历史备份不能保留备份之后发生的额度或外部费用。** 重新启用外部操作前必须核对后续调用/账单、避免旧新实例同时工作，并确认所需额度与授权。本功能不是跨备份的全局计费账本。
- 原 AT 绑定和发布准备只是保留的历史身份，必须重新核验连接、运行时、候选和外部状态。恢复不部署插件、重放发布或证明已回滚。

## 验证

专项单测覆盖原字节/全表往返、密码失败、文件锁、身份不符、禁止覆盖、陌生业务表、非法/重复条目、CRC、JSON 与 manifest 篡改。跨进程验证入口：

```powershell
& .\.venv\Scripts\python.exe -X utf8 plugin-maintenance/scripts/smoke_test.py `
    --java 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' `
    --python "$PWD\.venv\Scripts\python.exe" `
    --jar plugin-maintenance/server/target/at-plugin-maintenance-0.23.0-SNAPSHOT.jar `
    --previous-jar ..\recovery\baseline-rebuilt-0.22.1.jar `
    --database-backup-roundtrip
```

该入口只使用临时数据库、内置可信 worker 与本机合成 HTTP，不触碰当前工作台或 AT 数据。实际通过项、失败记录和最终 JAR 身份见 [0.23 验证记录](database-archive-result.json)；这项增量不使 M1–M5 整体完成。
