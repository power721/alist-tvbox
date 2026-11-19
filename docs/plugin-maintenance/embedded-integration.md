# AT 内置集成

## 入口

本 PR 只提供 AT 内置路径 `/#/maintenance`，由 AT 管理员菜单“源维护”打开。前端组件位于 `web-ui/src/maintenance`，直接挂载在 AT 路由中，不使用 iframe，不注册 `/plugin-maintenance/` 或单独的 HTML 页面。

沿用 AT 账号、导航、Vue 和 Element Plus。维护 API 为 `/api/plugin-maintenance/**`，只接受 ADMIN，会话通过 Authorization 请求头传递；每次写入前取得新的 CSRF，不将会话放入下载 URL。无独立维护账号、端口或登录页。

## 构建

Java 21、Node/npm、Maven。先在仓库根目录构建 AT 前端，再启用内置 profile：

```powershell
$env:VITE_MAINTENANCE_EMBEDDED = 'true'
npm.cmd --prefix web-ui ci
npm.cmd --prefix web-ui run build
mvn -Pmaintenance-embedded -Dmaven.test.skip=true package
```

产物为 `plugin-maintenance/at-embedded/target/alist-tvbox-1.0.jar`。使用 AT 原部署方式，不能对活动数据库启动第二个 JVM。普通 AT 构建不加载维护服务；不要把启用维护导航的前端用于未启用 profile 的服务端。

只构建一套 AT Web。内置 JAR 不应包含 `maintenance-ui/index.html`、独立启动类或独立登录安全链。`server/pom.xml` 仅用于共享控制面编译和测试，不能通过 java -jar 启动。

## 运行

维护代码与 AT 同 JVM、主 DataSource 和实体管理器。端口、数据库和认证由 AT 提供，不加载控制面测试目录的 application.yaml。模型、自动发布、巡检调度和隔离执行默认关闭。

在工作台设置模型中转站。AT 本机插件默认走内部仓储，不要求输入本机链接或凭据；“外置 AT 链接”仅作明确配置的外部只读取样。未知来源必须通过已授权的隔离执行器，不能在 AT 主进程执行。

需要联网执行时按[运行器运维](managed-runner-operations.md)设置已授权的同机 T4 IPC。示例部署在 `plugin-maintenance/deployment/compose.yaml`，只包含 embedded 服务；不挂载 Docker socket。

## 数据库

宿主先执行原迁移，维护迁移位于 `db/maintenance/h2` 并记录到独立的 `maintenance_schema_history`，同库保存两套历史，避免占用 AT 的 V59。原 SQL 字节和已有迁移编号不得改写。

旧私有 RC 曾把维护 V59-V74 写入宿主历史，不能直接覆盖启动。错误 `MAINTENANCE_LEGACY_SCHEMA_REQUIRES_EXPORT` 要求先冷备，再使用 `LegacyMaintenanceExport` 将只读原库复制到全新目标并验证业务摘要；不 repair、不删除旧历史。归档参见[数据库说明](database-archive.md)。

## 验证边界

定向前端检查位于 `web-ui/tests/maintenance`；`EmbeddedMaintenanceIntegrationTest` 覆盖 AT 会话、ADMIN/USER/CLIENT、CSRF、源码/ZIP、两套迁移历史、重启保持及 AT 静态资源，旧独立页面应返回 404。

内置适配不修改宿主合同 PR。MySQL/PostgreSQL、Native Image、真实未知故障无人值守成功到自动发布和长期多来源统计仍未完整验收，不称稳定版。
