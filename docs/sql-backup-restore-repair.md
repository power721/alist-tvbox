# SQL 备份恢复链路修复（database.zip 反复删库重建却恢复不生效）

2026-09-12。用户反馈：`database.zip`（SQL 备份）恢复会反复删除 db 文件再重建但无法有效恢复；JSON 恢复正常。本文记录根因与修复。

## 根因（双重断裂，两个都足以让恢复失败）

### ① zip 条目名不匹配 —— RunScript 必然失败（死循环源头）

- 导出端 `SettingService.backupDatabase()`：`SCRIPT TO '<tmp>.sql' TABLE ...` 产出纯文本 SQL，再用 `ZipOutputStream` 打成 zip 归档，**条目名用的是临时文件名**（`atv-backup-xxx.sql`）。
- 恢复端 `restore_database()`（docker init 脚本）：`RunScript -script /data/database.zip -options compression zip`。
- H2 的 `COMPRESSION ZIP` 模式**硬编码只认 zip 内名为 `script.sql` 的条目**（`ScriptBase.openInput`），条目名不符直接报 `File not found: "script.sql in ./database.zip"`（实测复现）。
- 于是每次启动：`rm -f atv.mv.db`（原库被删）→ RunScript 失败 → `check_success` 返回 1 → `set -e` 杀掉 init 脚本 → 容器重启 → zip 还在 → **无限循环「删库→重建→失败」**，且原库已删、备份又灌不进去。

### ② Flyway 迁移历史断裂 —— 即使导入成功，应用也起不来

- 导出黑名单排除了 `FLYWAY_SCHEMA_HISTORY`，恢复出的库**有全部业务表但没有迁移历史**。
- 应用启动时 Flyway 面对非空库 + 无历史表，`baseline-on-migrate: true` 把库 baseline 到版本 1，然后重放 V2..V52 —— 这些增量迁移跑在已演进的结构上，V5（unique index 已存在）即崩（复现测试 `FlywayRepairConfigTest.replayingMigrationsOnSqlRestoredSchemaFailsWithoutRepair`）。
- 另外黑名单也排除了 `MOVIE/META/ALIAS`（豆瓣大表，有意），恢复出的库缺这三张表，V1 被 baseline 跳过、`ddl-auto: validate` 不建表 → 校验失败。

JSON 链路不受影响的原因：`database-json.zip` 恢复**不删 db 文件**（保留初始 db，迁移历史与表结构完好），启动正常后由 `StartupJsonRestoreRunner` 走 JPA OVERWRITE 覆盖数据，完成后 exit 85 干净重启。

## 修复（四处）

| 位置 | 修复 |
|---|---|
| `SettingService.backupDatabase()` | ① zip 条目名固定为 `script.sql`；② 黑名单移除 `FLYWAY_SCHEMA_HISTORY`（迁移历史随备份走，恢复后 Flyway 只补备份版本之后的增量，这正是 Flyway 的设计场景） |
| `config/FlywayRepairConfig.java`（新增） | `FlywayMigrationStrategy`：启动时检测「业务表在 + 无 flyway_schema_history」（= SQL 重放的断裂库，救**存量旧备份**）→ baseline 到当前应用最高版本（迁移变 no-op）；migrate 后补建缺的 `movie/meta/alias`（DDL 对齐 V1+V7 与实体列宽，`IF NOT EXISTS` 幂等，正常库零影响） |
| `docker/scripts/lib/database.sh` `restore_database()` | ① 改用 `unzip` 解出 SQL 后以纯文本 RunScript 导入——同时兼容旧条目名备份与 H2 原生 `script.sql` 产物；② 旧库移为 `atv.mv.db.bak` 而非裸删，导入失败回滚原库继续启动；③ 失败包改名 `database.zip.failed` 隔离，打断 set -e 重启死循环（改回原名可重试） |
| `scripts/alist-tvbox.sh` `restore_sql_backup()` | 删库改为 `mv atv.mv.db atv.mv.db.bak`（恢复失败时容器侧自动回滚；成功后由容器清理） |

## 跨版本语义

- **新 SQL 备份（本次修复后导出）**：历史表随备份走。旧版本备份恢复到新版本应用：Flyway 只补跑备份版本之后的增量迁移，在恢复出的旧结构上正常演进。降级（新备份+旧应用）不支持，SQL 物理快照天然如此，跨版本请用 JSON。
- **存量 SQL 备份（修复前导出，无历史表）**：导入成功后由 `FlywayRepairConfig` 自愈 baseline；前提是备份与应用版本相近（结构 ≈ 当前）。
- **JSON 恢复**：行为不变（保留 db 文件 + JPA 覆盖数据）。

## 验证

- `FlywayRepairConfigTest`（3 用例）：无修复时断裂库 migrate 崩溃（复现）；修复后自愈（历史表/豆瓣表补齐、baseline 落在当前版本）；全新库/正常库零影响。
- `SettingServiceSqlBackupTest`：SCRIPT TO 语句含 `FLYWAY_SCHEMA_HISTORY`、仍排除 MOVIE/META/ALIAS。
- 本地 H2 2.3.232 端到端：模拟导出（条目名 script.sql / 旧条目名两种形态）→ unzip + 纯文本 RunScript → 数据完整恢复（两种形态 ROWS=2）；旧路径 `-options compression zip` + 旧条目名 → `File not found: script.sql` 精确复现。
