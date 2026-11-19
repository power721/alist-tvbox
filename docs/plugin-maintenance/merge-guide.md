# AT 内置源维护合入指南

## 合入目的

为 AT 管理员提供“联网巡检 → 证据诊断 → 模型候选 → 隔离复验 → 平台发布”的源维护入口。能力、目录责任与已验边界见[贡献说明](github-review-2026-11-19.md)。本 PR 仅 AT `/#/maintenance`，不保留独立工作台页面或服务发行入口。

## 1. 先合宿主合同

先审阅上游 #1095：T4 装载身份、参数透传与可选插件写入协调。宿主在未安装维护模块时仍可独立运行，无模型、维护数据库或维护页面依赖。本模块 PR #1096 依赖该合同。

建议 #1095 保留原提交合入，再刷新 #1096 差异，核对共享宿主提交已从剩余差异中消失。若前置采用 squash，应由贡献者整理等效重叠后再合本 PR，不重复覆盖宿主代码。不要把本系列中间提交当作可独立运行产品。

## 2. 分层审阅模块

审阅顺序为隔离执行器、共享控制面、AT 工作台、内置适配/迁移及文档。检查以下边界：

- 默认构建不接入维护控制面；入口导航与服务端 profile 必须配套启用。
- AT 内部插件读取走宿主接口；外置 AT 链接仅在明确配置时取样。
- ADMIN 与 CSRF 校验保留；不新增维护账号，不在 URL 放会话。
- 未知 Python 只进入隔离执行器；模型生成候选，验收与发布由平台负责。
- 持久队列默认增量；替换需显式选择；失败候选不能发布。
- 维护迁移不占用宿主版本历史，不改写旧 SQL 字节或历史账本。

本 PR 正式待审阅，不请求跳过这些检查直接合并；提交不等于稳定发行或生产换版。

## 3. 构建唯一 AT Web

环境要求 Java 21、Maven、Node/npm。在仓库根目录执行，PowerShell 示例：

```powershell
$env:VITE_MAINTENANCE_EMBEDDED = 'true'
npm.cmd --prefix web-ui ci
npm.cmd --prefix web-ui run build
mvn -Pmaintenance-embedded -Dmaven.test.skip=true package
```

前端写入 AT 原 `src/main/resources/static`，产物为 `plugin-maintenance/at-embedded/target/alist-tvbox-1.0.jar`。只构建这一套 Web，不构建 `plugin-maintenance/web-ui`，不运行共享库 `server` JAR。内置 JAR 应仅包含 AT 的启动入口与静态页面，无 `maintenance-ui/index.html`、独立维护启动类及独立登录安全链。

普通 AT 构建应使用新工作区或恢复未启用维护导航的前端，再执行原构建流程。不要复用启用了维护导航的 static 给未启用维护服务的后端。

Linux shell 使用 `VITE_MAINTENANCE_EMBEDDED=true npm --prefix web-ui run build`；Maven 参数一致。若将前端输出到独立目录，打包时显式传入 `-Dmaintenance.at-ui-directory=<绝对输出目录>`；profile 会保留宿主非生成静态资源，仅替换根 `index.html` 与生成的 `assets`，不能丢失 `static/spring.jar`。

## 4. 按改动范围验证

```powershell
npm.cmd --prefix web-ui run test:maintenance
mvn -Pmaintenance-embedded '-Dtest=EmbeddedMaintenanceIntegrationTest#embeddedWorkflowUsesAtDatabasePermissionsAndHostWeb' test
```

前端测试需要支持 `--experimental-strip-types` 的 Node。集成测试使用临时数据和可信夹具，不触碰生产、不调用真实模型或站点。已通过且输入未变化的检查可复用，修正后只续受影响项。普通 AT 宿主测试继续按其原流程运行。

验收时以 AT 管理员登录，打开“源维护”或 `/#/maintenance`；核对无 iframe、无额外登录、普通用户不可访问维护 API、无 CSRF 写入被拒绝、旧 `/plugin-maintenance/` 返回 404。源码/ZIP 下载应使用 AT 会话请求，不把 token 拼到下载 URL。

## 5. 数据与隔离执行配置

先对自己持有的实例做冷备并使用全新测试副本，不能对活动 AT 库启动第二个 JVM。宿主 Flyway 先运行，维护使用同库独立 `maintenance_schema_history`；备份须包含两套历史。

旧私有 RC 混合 V59-V74 历史会被明确拒绝；按[数据库说明](database-archive.md)与 `LegacyMaintenanceExport` 工具移交至全新副本，不覆盖原库、不 repair。首次启动不自动重放旧任务或重置累计账本。H2 为当前验证范围，其他数据库及 Native Image 另行验收。

联网执行需要管理员安装/授权的 T4 IPC 或固定受管通道及其依赖镜像，不因合入自动安装或取得 Docker 权限。按[运行器运维](managed-runner-operations.md)配置；同机 IPC 示例在 `plugin-maintenance/deployment/compose.t4.yaml`。模型中转地址、模型名与密钥在 AT 工作台设置，私有配置不提交 Git。

先手动联网验收一个获准来源，再按明确授权启用周期巡检、自动修复和发布策略。磁力与资料导航按来源合同验收；需要凭据、访问挑战或网络异常的来源不得仅凭 HTTP/静态结果判为源码损坏。

## 6. 发布与回退

人工接受测试实例后，才按 AT 原部署流程替换已备份实例。测试阶段采用增量发布；替换原插件必须显式选择。平台分别保存维修、验证与发布结果，不把模型答复视为发布成功。

数据库升级后回退必须连同升级前 JAR、冷库及实例文件一起恢复到新目录，不能把旧 JAR 直接指向新迁移库。保留当前与上一接受回滚镜像，按项目标签退役无引用镜像，不全局 prune。操作步骤参见[实例归档](instance-archive.md)及[镜像生命周期](image-lifecycle.md)。

本次贡献不执行合并、生产部署、模型续轮、稳定 Release 或标签变更。真实未知故障无人值守成功到自动发布、长期统计、非 H2 和 Native Image 仍作为后续验收项，不在 PR 中伪称完成。
