# Release Notes - 1.11.0

## 新增

- 支持 PostgreSQL 作为主应用数据库，包含 Spring 配置、JDBC 驱动、Flyway 迁移脚本与原生镜像资源配置
- 部署脚本新增 `config-db`，可在 H2、MySQL、PostgreSQL 之间配置和切换主应用数据库
- 部署脚本新增 `migrate-db`，支持 H2 -> MySQL、H2 -> PostgreSQL、MySQL/PostgreSQL 互迁，以及外部数据库迁回内置 H2
- 内置 AList 运行时支持 PostgreSQL 数据源配置
- 新增数据库迁移指南，说明迁移入口、JSON 备份流程、MySQL/PostgreSQL 配置与常见问题

## 优化

- 数据库迁移统一使用 JSON 备份恢复，容器未运行但已有 JSON 备份时也可继续迁移
- 离线迁移优先使用当天 JSON 备份；更早备份需要交互确认，避免误用旧数据
- 外部数据库配置自动禁用 Spring SQL 初始化，避免 MySQL/PostgreSQL 误执行 H2 `data.sql`
- Docker 启动参数支持外部配置目录与固定时区，减少 PostgreSQL 时区和配置加载问题
- Flyway 迁移按数据库类型拆分为 H2、MySQL、PostgreSQL 与 common 目录，降低跨数据库兼容风险

## 修复

- 修复 MySQL 迁移中 TEXT/BLOB 字段建索引失败的问题
- 修复 MySQL 不支持部分 H2/PostgreSQL DDL 语法导致迁移失败的问题
- 修复 PostgreSQL 继承 H2 方言配置导致连接和元数据初始化失败的问题
- 修复 `TaskService` 启动时按 profile 判断数据库类型导致 MySQL/PostgreSQL DDL 执行错误的问题
- 修复部署脚本生成数据库配置时属性绑定不稳定的问题
