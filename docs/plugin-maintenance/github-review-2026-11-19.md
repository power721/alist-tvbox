# AT 内置源维护：贡献与验收说明

## 目的与范围

本模块把 Python 爬虫的维护流程放进 AT：联网发现故障、收集诊断证据、模型生成候选、隔离联网复验，再由平台发布成功候选。面向不熟悉爬虫代码的管理员，降低逐个手工修源的成本；不是播放器，也不宣称所有站点都能零干预修复。

本 PR 只提供 AT `/#/maintenance`。`MaintenanceView` 直接挂载 `web-ui/src/maintenance/MaintenanceWorkbench.vue`，复用 AT 登录、导航、Vue 和 Element Plus。没有 iframe、独立 HTML、独立前端包、独立端口、独立账号或 `/plugin-maintenance/` 页面映射。控制面启动类及独立登录配置只留在测试目录，不进入发行 JAR。

原双模式开发资料与原开发区保留，不因本次上游收敛被覆盖。正式提交请求代码审阅，不等于合并、生产部署或稳定 Release。

## 能力

| 环节 | 当前实现 |
| --- | --- |
| 导入与展示 | 内部读取 AT 自定义插件、导入明文源码、折叠导入抽屉、来源与版本列表；可选外置 AT 只读取样 |
| 联网巡检 | 手动和周期任务、冻结画像、节点结果、退化与多轮失败提示；静态结果不冒充联网健康 |
| 故障诊断 | 来源、运行环境、网络/代理、凭据与挑战分别归因；记录详情、取链、媒体采样及清理证据 |
| 模型修复 | 可配置中转站、模型与凭据，模型生成候选；持久任务、累计账本、重试/暂停、重复候选抑制 |
| 验收 | 平台在隔离执行器复验候选，不在 AT 主 JVM 执行未知 Python；失败、未完成或清理未确认不进入成功发布 |
| 发布 | 持久发布队列，默认增量、显式替换；中文文件名与版本、原件保护、成功修复与发布结果分别记录 |
| 运维 | 过程日志查看/导出、冷归档、独立维护迁移历史；加强诊断及访客浏览器为按需外部依赖 |

纯磁力来源按磁力合同验收，不强求直连视频；资料导航与视频来源区分。豆瓣/TMDB复杂助手按明确身份排除自动修复。需要凭据的来源先补凭据；挑战或网络不可达不直接判定源码损坏。不得绕过访问控制。

## 结构与边界

| 路径 | 责任 |
| --- | --- |
| `src/main/.../t4` 及宿主插件接口 | #1095 前置：装载身份、参数透传和可选写入协调；不依赖维护控制面 |
| `plugin-maintenance/managed-source/`、`container/`、`scripts/` | 隔离运行、受管 HTTP、出站观察、任务和清理协议 |
| `plugin-maintenance/server/src/main/` | Controller → Service → Repository 控制面、来源/画像/任务/模型/发布台账；共享库而非独立服务 |
| `plugin-maintenance/at-embedded/` | AT 会话及 ADMIN/CSRF、实体/仓储集成、独立 Flyway 历史与内置集成测试 |
| `web-ui/src/maintenance/` | AT 路由中的工作台组件，前端只触发/观察服务端任务 |
| `web-ui/tests/maintenance/` | API、组件状态、证据归属、轮询、调度和发布交互验证 |
| `plugin-maintenance/reverse-engine/`、`http-acceptance/` | 按需诊断引擎与可信运行依赖，不是新增独立管理页面 |
| `docs/plugin-maintenance/` | 构建、合入、使用、运维、交付标准及历史验证边界 |

普通 AT Maven 构建不加载维护服务；仅显式 `maintenance-embedded` profile 接入。工作台使用 AT 的会话，写入前取得 CSRF；无另一套用户体系。执行器通过获准 T4 IPC 或固定受管通道连接，Web 不挂载 Docker socket。模型与自动操作须另行配置/授权，代码合入不自动授权来源外发或生产替换。

## 合入与构建

先审阅并合入宿主 #1095，再审阅本 PR。共享宿主提交在前置按原提交合入后会从差异中消失；若前置采用 squash，需协调本分支清除等效重叠，不能再次覆盖宿主。具体顺序、命令、数据及回滚步骤见[合入指南](merge-guide.md)。本次不改 #1095、不重写历史、不合并上游。

本系列基于 AT `1e05d65c4930d7db65efb2fd2a4a42672d4ea539`（1.133.0 / Boot 4.1.2），保留上游追剧、数据库与构建更新；提交时另行回读上游状态，不把该固定基线称为永远最新。

维护迁移使用同一 H2 DataSource、独立 `maintenance_schema_history`，不占用宿主 V59。宿主迁移先运行，再运行 `db/maintenance/h2`。旧私有 RC 的混合历史禁止直接覆盖启动，使用 `LegacyMaintenanceExport` 只读原冷库并导出到全新副本，不 repair 或删除旧历史。

## 本次页面收口验证

- AT 前端类型检查和 Vite 构建通过，只构建一套 AT Web。
- 前端 198 个迁移用例最终通过：31 项入口/接口等检查，加其余 167 项；其中 9 项夹具缺字段/回调，补齐后仅续验这 9 项。另补 3 项下载回归，连同受影响入口/API 共 12 项通过，总计 201 个不同用例有通过记录。
- Python 25 个不同打包/审阅范围用例最终通过；首轮 12 项旧清单引用失败，修正后仅续验该范围。
- 两项真实 Spring/HTTP 定向集成通过：`EmbeddedMaintenanceIntegrationTest#embeddedWorkflowUsesAtDatabasePermissionsAndHostWeb` 与 `MaintenanceIntegrationTest#apiRequiresAuthenticationAndCsrfAndNeverReturnsSourceInListing`。前者覆盖 AT 会话、ADMIN/USER/CLIENT、CSRF、两套迁移历史、宿主 Web 与旧独立页面 404；后者为测试目录启动夹具，不是发行入口。
- 内置 JAR 构建/内容审计通过，无 `maintenance-ui`、独立启动类或独立登录配置；保留 AT 静态资源和 `spring.jar`。
- 单独前端输出目录也保留宿主非生成静态资源，仅替换根 `index.html` 和生成的 `assets`；最终按字节核对 `spring.jar`，不把仅能打开界面视为完整打包。
- 隔离浏览器桌面/手机空列表检查：一个工作台、零 iframe、无横向溢出。使用模拟 API，不等于真实业务验收。

首次失败与后续通过分别保留；不重跑未变化的全量检查。本轮未部署 NAS、未调用模型/爬虫、未改生产，未改变任何真实维修账本。

## 真实业务与未决项

既有 RC12 的爱看原维修仍为必需项 8/9，R01 `PLAYER_URL_INVALID` 未解决。授权第 4 轮已消费，重复候选被拒绝，未发布，不得复用授权追加第 5 轮。详见[RC12 真实结果](evidence-followup-rc12-2026-11-19.md)和[取链诊断](player-output-diagnostics-2026-11-19.md)。旧双模式构建/部署记录只认证其当时身份，不是当前安装入口。

仍缺真实未知故障无人值守修复至平台自动发布的一次完整正向验收；MySQL/PostgreSQL、Native Image、长期多来源统计未完整验收。可正式提交上游审阅，不能称稳定版已闭环。

仅向既有 fork 与上游 PR 提交源码，保留原署名，不另造上游许可证。未确认独立再分发许可；不公开站点源码、Cookie、密钥、活动数据库、私有验收脚本或二进制镜像。不创建稳定 Release 或移动标签。
