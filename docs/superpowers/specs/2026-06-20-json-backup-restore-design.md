# JSON-first 备份/恢复（部署脚本）

Date: 2026-06-20

## 背景 / 动机

- `scripts/alist-tvbox.sh` 现状：
  - 菜单 8→a `restore_database`：列 `backup/*.zip`，**全部当 SQL** 处理（复制到 `database.zip` + 删 `atv.mv.db` + 重启）。
  - 菜单 8→b `backup_database_now`：打包裸 `atv.mv.db` 为 `database-<date>-<time>.zip`。**RunScript 无法执行**（非 SQL），属不可恢复格式。
- 后端已有两套备份（写入共享 `${BASE_DIR}/backup`，容器内 `/data/backup`）：
  - SQL：`SettingService.backupDatabase()`（`SCRIPT TO 'script.sql' TABLE <黑名单>`）→ `database-<date>.zip`，每日 06:00，**仅 H2**（mysql profile 返回 null）。
  - JSON：`SettingService.backupJsonDatabase()`（JPA per-module）→ `database-json-<date>.zip`，每日 06:30，**DB 无关**（H2/MySQL/未来 PostgreSQL）。
- 恢复（容器内 `scripts/init.sh`）：若 `/data/database-json.zip` 存在 → **跳过 SQL 恢复**，由 `StartupJsonRestoreRunner` 做 OVERWRITE 恢复 + `exit(85)` 干净重启；否则 `/data/database.zip` → H2 `RunScript -options compression zip` 执行其中 `script.sql`。
- **下一步支持 PostgreSQL**：JSON 走 JPA 天然可移植；SQL 走 H2 `SCRIPT TO` 方言、不可移植。故 JSON 必须为主、SQL 为 fallback —— 备份层在接入 PostgreSQL 时零改动。

## 目标

1. 菜单 8→a 支持 JSON 与 SQL 两种恢复，**JSON 优先**。
2. 菜单 8→b 改为产出**可恢复的 JSON 备份**（统一格式），SQL 为 fallback。
3. 不破坏现有自动备份、`init.sh` 既有行为；diff 最小。

## 设计

### 恢复 `restore_database()`（纯脚本改动）

- 列 `backup/*.zip`，按**文件名**探测类型：含 `-json-` → JSON，否则 SQL。标注 `[JSON]`/`[SQL]`，**JSON 排前**。
- 选择后：
  - **JSON（主）**：复制 → `${BASE_DIR}/database-json.zip`；`docker restart`（**不删** `atv.mv.db`）。容器启动 → `init.sh` 跳过 SQL → `StartupJsonRestoreRunner` OVERWRITE + `exit(85)` → entrypoint 循环干净重启。
  - **SQL（fallback）**：复制 → `${BASE_DIR}/database.zip`；删 `atv.mv.db`+`atv.trace.db`；`docker restart`。容器启动 → `init.sh` `RunScript` 执行 `script.sql`。
- 提取 `restore_json_backup <file>` / `restore_sql_backup <file>` 两个 helper，`restore_database` 负责列单+探测+派发。

### 备份 `backup_database_now()`（脚本 + 后端各一处）

- 问题：`/api/settings/export-json` 需 ADMIN/CLIENT；`api_key` 仅存 H2（不在磁盘），宿主拿不到 → 无法直接调用。
- 方案：新增**本地 permitAll 端点** `POST /api/local/backup`（仿 `/api/local/admin/password`，写 token 文件 + header 校验），触发 JSON 备份并写入 `/data/backup/database-json-<date>-<time>.zip`。
  - 宿主流程：生成 token → `docker exec` 写 `/data/atv/backup_token` → `docker exec` curl `http://127.0.0.1:4567/api/local/backup`（带 `X-BACKUP-TOKEN`）→ 删 token → 打印产物路径。
- Fallback 链：JSON 失败 → SQL 备份（端点 `?type=sql`，仅 H2 产出 `script.sql` zip）→ 容器不可用 → 退回裸 `atv.mv.db` 复制（兜底）。
- 鉴于「JSON 优先、SQL fallback」，`backup_database_now` 默认 JSON。

### 后端改动（最小）

- 新增 `BackupLocalController`（或并入既有 local 端点）：`POST /api/local/backup`，permitAll + token 文件守卫，调用 `settingService.backupJsonDatabase()`（主）/`backupDatabase()`（fallback，`?type=sql`）。
- 无 API 破坏；不动既有 `/api/settings/export*`。

### 不在范围

- 前端 `ConfigView.vue`（已有 JSON 导出/导入 UI）不动。
- 后端每日定时任务不动。
- PostgreSQL 接入本身（后续独立工作）。

## 风险 / 边界

- 文件名探测假设 JSON 备份名含 `-json-`：对后端产出（`database-json-*`）成立；用户手改文件名则归类到 SQL（SQL fallback 仍可尝试 RunScript，失败有日志）。
- `backup_database_now` 需容器运行；未运行时走 atv.mv.db 兜底（与现状一致）。
- token 文件守卫与 admin-reset 同模式，避免 permitAll 端点被滥用。

## 验证

- `bash -n scripts/alist-tvbox.sh`。
- `ALIST_TVBOX_SOURCE_ONLY=1` source 后对 `restore_*` helper 做隔离测试（造 SQL zip / JSON zip，断言探测与目标文件名）。
- 后端：新增端点的 token 守卫单测 + `@SpringBootTest`（`@ActiveProfiles("test")`，见 [[yaml-backup-restore-impl]] 测试隔离）。
