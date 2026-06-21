# 数据库迁移指南

AList-TvBox 主应用数据库支持 H2、MySQL、PostgreSQL。迁移使用 JSON 备份/恢复完成，JSON 与数据库无关，适合在不同数据库之间迁移。

> H2 的 `data.sql` / SQL 备份不是跨数据库格式，不能直接导入 MySQL 或 PostgreSQL。

## 支持的迁移方向

- H2 -> MySQL
- H2 -> PostgreSQL
- MySQL -> PostgreSQL
- PostgreSQL -> MySQL
- MySQL/PostgreSQL -> H2

迁移只处理 AList-TvBox 主应用数据库。内置 AList 运行时数据库是另一套数据，需要单独处理。

## 菜单入口

运行 `bash alist-tvbox.sh` 进入主菜单，选择：

```text
m. 数据库迁移
```

该菜单用于 H2 / MySQL / PostgreSQL 迁移与数据库配置，包含迁移向导、配置/切换主应用数据库、导出 JSON、导入 JSON、回退到迁移前数据库。

## 迁移前准备

1. 使用官方工具备份当前使用的MySQL/PostgreSQL 数据库。
2. 使用最新版 `alist-tvbox.sh` 脚本。
3. 如果需要现场导出数据，确认当前容器正在运行。若 JSON 数据已经导出，容器停止也可以继续迁移。
4. 目标 MySQL/PostgreSQL 数据库需要提前创建好库和账号。
5. 目标库建议为空库，首次启动时由 Flyway 建表。
6. 迁移过程中会重建容器，原数据库文件或外部数据库不会被删除。
7. 容器未运行时复用已有 JSON 备份，脚本默认只自动使用今天生成的备份；更早的备份必须在交互模式确认后才会继续。
8. 容器运行时，脚本会通过应用 JDBC 做真实登录测试；容器未运行时无法调用应用接口，只能做 TCP 可达性检查。若已找到可复用 JSON 备份，TCP 检查不可用时也允许继续离线迁移，实际数据库连接问题会在重建容器时暴露。

## 交互式迁移

执行：

```bash
sudo ./scripts/alist-tvbox.sh migrate-db
```

脚本会要求选择目标数据库类型：

```text
请选择目标数据库类型：
  1) MySQL
  2) PostgreSQL
  3) H2（内置默认）
请选择 [1/2/3]:
```

必须输入数字，直接回车不会默认选择。

选择 MySQL 或 PostgreSQL 后，脚本会提示输入主机、端口、数据库名、用户名和密码。数据库主机默认值会使用本机 IP，而不是 `localhost`，因为 bridge 网络里的容器访问 `localhost` 不是宿主机。

## 非交互迁移到 MySQL

```bash
sudo ./scripts/alist-tvbox.sh migrate-db \
  --jdbc-url jdbc:mysql://192.168.50.60:3306/atv \
  --username atv \
  --password '你的密码'
```

脚本会自动识别 MySQL，并生成外部数据库配置到：

```text
<数据目录>/atv/config/application.yaml
```

容器内对应路径为：

```text
/data/atv/config/application.yaml
```

## 非交互迁移到 PostgreSQL

```bash
sudo ./scripts/alist-tvbox.sh migrate-db \
  --jdbc-url jdbc:postgresql://192.168.50.60:5432/atv \
  --username atv \
  --password '你的密码'
```

如果 JDBC URL 没有 `options=` 参数，脚本会自动补充：

```text
options=-c%20TimeZone=Asia/Shanghai
```

最终类似：

```text
jdbc:postgresql://192.168.50.60:5432/atv?options=-c%20TimeZone=Asia/Shanghai
```

脚本重建容器时也会注入：

```text
TZ=Asia/Shanghai
```

新镜像入口还会设置：

```text
-Duser.timezone=Asia/Shanghai
```

这些设置用于避免 PostgreSQL 报错：

```text
FATAL: invalid value for parameter "TimeZone": "PRC"
```

## 迁移回内置 H2

从 MySQL 或 PostgreSQL 迁回内置 H2：

```bash
sudo ./scripts/alist-tvbox.sh migrate-db --type h2
```

流程：

1. 从当前运行数据库导出 JSON。
2. 删除外部数据库覆盖配置。
3. 将旧的 H2 文件移到备份目录：
   ```text
   <数据目录>/backup/h2-before-migrate-<时间>/
   ```
4. 重建容器，让 Flyway 创建新的 H2 表。
5. 导入 JSON 数据。

旧 H2 文件包括：

```text
<数据目录>/atv.mv.db
<数据目录>/atv.trace.db
```

## 迁移流程说明

自动迁移执行三步：

1. 准备 JSON 数据到：
   ```text
   <数据目录>/migration-export.zip
   ```
   容器运行时会自动导出；容器未运行时会复用已存在的 `<数据目录>/migration-export.zip`、`<数据目录>/database-json.zip`，或 `backup/database-json*.zip` 中最新的非空文件。
   - 备份日期为今天：自动继续。
   - 备份日期早于今天：交互迁移会要求确认；非交互迁移会中止，避免误用旧数据。
2. 切换数据库配置并重建容器，让 Flyway 在目标库建表。
3. 将 JSON 复制为：
   ```text
   <数据目录>/database-json.zip
   ```
   然后重启容器，由启动恢复逻辑导入数据。

恢复完成后应用会以 exit code 85 自动退出一次，容器重启后加载恢复后的数据。

查看日志：

```bash
docker logs -f xiaoya-tvbox
```

或纯净版：

```bash
docker logs -f alist-tvbox
```

## 手动导出和导入

导出 JSON：

```bash
sudo ./scripts/alist-tvbox.sh migrate-db export
```

导入 JSON：

```bash
sudo ./scripts/alist-tvbox.sh migrate-db import /path/to/database-json.zip
```

跨机器迁移时，在源机器执行 export，把 zip 文件复制到目标机器，再在目标机器执行 import。

## 常见问题

### MySQL 报 Host is not allowed

示例：

```text
Host '172.17.0.x' is not allowed to connect to this MySQL server
```

原因是 MySQL 用户没有授权容器网段访问。可以给用户授权宿主机局域网地址或容器网段，例如：

```sql
CREATE USER 'atv'@'%' IDENTIFIED BY '你的密码';
GRANT ALL PRIVILEGES ON atv.* TO 'atv'@'%';
FLUSH PRIVILEGES;
```

如果已经存在用户，只需要调整授权即可。

### PostgreSQL 报 TimeZone PRC

示例：

```text
FATAL: invalid value for parameter "TimeZone": "PRC"
```

使用最新版脚本重建容器：

```bash
sudo ./scripts/alist-tvbox.sh config-db apply
```

新版脚本会设置 `TZ=Asia/Shanghai`，PostgreSQL JDBC URL 也会补充时区参数。

### H2 data.sql 不能导入 MySQL/PostgreSQL

错误示例：

```text
CREATE CACHED TABLE "PUBLIC"."ALIAS" ...
```

这是 H2 SQL dump，只能给 H2 使用。迁移到 MySQL/PostgreSQL 必须使用 JSON 备份。

### Flyway 提示 failed migration

如果某次迁移中断，Flyway 可能记录失败版本。确认数据库里没有需要保留的半成品数据后，可以按提示清理失败记录或重建目标空库，再重新迁移。

如果是新建目标库，最简单方式通常是删除目标库后重新创建，再执行迁移。

### Hibernate dialect 配置

基础配置使用 H2 方言。迁移到外部数据库时，脚本会显式写入 `spring.jpa.database-platform` 覆盖 H2 方言，否则 Spring 配置合并后可能仍保留 `H2Dialect`。

MySQL 使用：

```text
org.hibernate.dialect.MySQLDialect
```

PostgreSQL 使用：

```text
org.hibernate.dialect.PostgreSQLDialect
```

MySQL 使用 `MySQLDialect` 是为了避免自动识别访问 `information_schema.SEQUENCES`，也避免旧的 `MySQL8Dialect` 废弃警告。
