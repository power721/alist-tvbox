# 已有候选跨部署移交

独立工作台与 AT 内置模块共用候选导入和复用流程，无需复制数据库或重复请求模型。
输入是修复记录中下载的**候选维护包**（`RepairExportService`），不是发布准备计划 ZIP。
原发布准备计划、冷备份和生产部署接口的合同不变。

## 操作

1. 在目标工作台的更多导入中选择“导入维护 ZIP”，预览并确认候选维护包。
2. 新建本地源保留精确基线、能力声明和原 ZIP；重新绑定目标环境需要的 AT / EXT 配置。
3. 在目标环境建立当前基线检查并开始修复。具备同一基线和故障目标时，选择“复用已导入候选”。
4. 补丁经过目标环境的静态检查；运行验证和宿主采用仍通过原有入口显式执行。

导入不执行 Spider。对于仅在运行时失败的真实源，须通过既有授权隔离运行器建立
新环境基线；静态报告不能替代 R04/R05。已有原环境验收保留为历史记录，
不恢复为新环境的探测、修复、发布资格或有效宿主版本。

## 身份和来源

- 核对 ZIP 条目、CRC、逐文件长度和 SHA，以及基线、候选、画像、冻结快照和精确补丁之间的关系。
  补丁必须逐字重建包内候选；哈希一致性不等于认证作者或证明历史模型/运行结果可信。
- 原 ZIP 保存在现有导入记录中，允许下载回查；新源使用独立本地 ID。
  AT 绑定、模型授权、调用额度、巡检和旧通过状态不随包激活。
- 复用请求在修复行锁内核对导入归属、包摘要、精确基线、当前故障目标和输入新鲜度，
  同一修复对同一导入只建立一条候选、使用一轮本地检查额度。
  响应丢失后的重复请求返回已有候选，不重复占用轮次或请求模型。
- 新候选 `modelCallId=null`，`sourceImportId` 关联原导入。
  再导出的 `provenance.json` 标明 `origin=imported_candidate`、
  原候选 ID / 模型调用 ID、归档摘要及 `localModelCall=false`，不冒充本机的新模型调用。

## API

复用现有 ADMIN / CSRF 导入接口：

```text
POST /api/plugin-maintenance/source-imports/preview
POST /api/plugin-maintenance/source-imports
GET  /api/plugin-maintenance/sources/{sourceId}/imported-candidates
POST /api/plugin-maintenance/repairs/{repairId}/imported-candidates
```

最后一个请求只接受 `importId` 与 `expectedArchiveSha256`，不接受客户端传入通过状态、
历史运行结果、模型身份或任意新补丁。基线/补丁从已保存包读取，当前验证沿用原修复服务。
独立库 V23、内置库 V61 仅新增候选的导入关联；既有 AT V59/V60 不改写。

本功能不提供已通过发布计划的跨库直接恢复，不自动切换生产插件，
也不替代隔离源执行、真实宿主装载、连续媒体或完整实例恢复的验收。
