# GitHub 贡献分层与发行边界

## 基线与分层

本次从 AT 上游 `1e05d65c4930d7db65efb2fd2a4a42672d4ea539`（1.133.0）整理，不回退上游 Spring Boot 4.1.2，也不覆盖上游追剧、MySQL 和镜像构建更新。开发工作区及历史私有候选仍保留原身份。

1. 宿主合同：原生 T4 装载身份、过滤器身份及可选插件写入协调接口。没有维护模块时接口为空实现，不增加模型、定时任务、数据库迁移或 Web 入口依赖。可独立审阅和合入。
2. 隔离执行器：受管 Python、网络观察、运行与清理协议。
3. 控制面：来源、联网巡检、诊断、模型候选、复验、持久发布队列。
4. 独立 Web：来源列表、巡检与维修状态、模型设置、可导出日志。
5. 可选内置适配：仅 `maintenance-embedded` Maven profile 加载；沿用 AT 管理权限，独立管理页 `/plugin-maintenance/`，AT 导航需 `VITE_MAINTENANCE_EMBEDDED=true`。

后四层有顺序依赖，不能将各中间提交作为独立产品验收。后续模块 PR 依赖宿主合同 PR；上游可先合宿主层，模块保持草稿，避免强迫一次合入全部平台。

## 数据库隔离

最新上游已拥有 V59 Java 迁移。内置平台不再占用宿主 `flyway_schema_history` 的版本号：先完成宿主迁移，再将 `db/maintenance/h2` 迁移到独立 `maintenance_schema_history`，仍使用同一 H2 数据库，备份保留两套历史。

上游 4.1.2 classic starter 的 gRPC 自动配置引用未安装的 `GrpcServiceDiscoverer`，在启用 CSRF 的管理安全链真实启动时失败。内置 profile 只排除未使用的 `spring-boot-grpc-server`，保留宿主默认依赖、ADMIN 权限和 CSRF 校验，不通过降级版本或关闭安全校验规避。

旧私有 RC 的 V59-V74 曾写入宿主历史，不能直接用新版本覆盖启动。遇到该历史明确报 `MAINTENANCE_LEGACY_SCHEMA_REQUIRES_EXPORT`，不执行 repair、删除或伪造迁移记录。先保留冷备份，再向新的测试实例移交来源与候选。旧实例与当前 NAS RC9.1 不因本次源码整理被升级。MySQL、PostgreSQL 和 Native Image 尚未验收。

## 真实业务进度

2026-11-19，平台使用原维修剩余第 3 轮完成模型生成、自动递交与隔离联网验证，没有手改爬虫、重置历史或扩轮。C10 从失败恢复为通过，30 组线路、31 个选集全部解析。冻结必需节点 8/9 通过，R01 仍为 `PLAYER_URL_INVALID`，成功门禁为 false，没有发布该失败候选。

因此尚缺一次真实未知故障无人值守成功至平台自动发布的正向验收。可提交可审阅源码和草稿 PR，但不能把源码上传称为稳定版闭环；不创建稳定 Release 或移动既有标签。

## 分发范围

优先更新用户已有的 `wab201/alist-tvbox` GitHub fork 并向原仓库贡献，保留 AT 原署名，不另造上游许可证。AT 上游未声明根许可证，GitHub 平台上的 fork/PR 不等于站外镜像、JAR 或独立项目再分发授权。独立公开发行及第三方许可材料仍须确认，见 [NOTICE](standalone-notice.md)。不公开私有验收库、真实来源源码、连接配置、访问令牌或 NAS 操作脚本。

## 本轮验证

宿主层在不启用维护 profile、没有维护模块源码的独立副本中执行 `mvn -o -Dmaven.test.skip=true compile` 通过。最新 AT Web 和独立维护 Web 各构建一次通过。

内置 profile 的 114 项定向用例首轮 112 项通过，2 项测试夹具失败；修正 Flyway 测试类命名和归档子进程 stderr 读取后，按失败范围续验，最终 114 项均有通过记录，未重复已通过的宿主测试。迁移测试覆盖 AT 与维护各自版本历史、重启幂等及旧私有历史拒绝；冷备覆盖两套历史与活动库拒绝。

另执行 `EmbeddedMaintenanceIntegrationTest#nativeRuntimeEndpointUsesAdminAndSavedLoaderSnapshot` 一项真实 Spring/HTTP/T4 夹具集成。首次发现上文 gRPC 缺类，排除未使用组件后该项通过，内置 JAR 打包成功。测试不执行真实站点、不调用模型，不等于 NAS 新版本验收。真实维修的失败回执和首次测试结果均保留在私有项目盘，不进入公开源码。

## RC11 后续补强

补齐 R01 取链返回值的有界脱敏形态和详情/取链调用关联，贯通普通/加强模型请求与管理页；不修改爬虫、不自动解码地址、不放宽验收。76 个不同 Java 用例、84 个不同 Python 用例、16 个 Web 用例最终通过，另有 6 个条件跳过；只续失败夹具范围，不重跑全量。具体证据与未部署边界见 [取链返回值诊断](player-output-diagnostics-2026-11-19.md)。原真实维修仍 8/9、3/3 轮，没有新增模型调用或失败候选发布，不能称稳定版闭环。
