# 播放字段值对应证据

## 范围

基于 AT 内置维护模块待审提交 `2ed6b0a9c119279a7c503b000d0c020f81fe01ff`，在本地分支 `codex/maintenance-player-provenance-20261119` 迭代。只修改 `plugin-maintenance/`、`web-ui/*/maintenance/` 和本说明，不改宿主功能，不恢复独立页面，也不更新上游 PR 或生产。

R01 返回不透明标识时，既有证据只描述字符串形态，不能判断它与本次 API 的哪个字段相同。新增 `playerContext` 绑定本次已成功的详情与取链调用；`jsonStructure` 中的字符串可带 `playerValueMatches`，分别记录与选集标识、返回地址的原值相等，或响应字段经过一次标准百分号/Base64 解码后相等。嵌套 JSON 只沿用既有的一层结构解析。

匹配使用宿主已持有的完整响应缓存，不重放 POST，不额外请求媒体，不执行来源代码或自定义解码器。原始地址、字段值、请求头及凭据不进入新增投影；私密字段及其子树不参与匹配。既有 8 次资源预算、18000 字节总文本预算和 2048 字节单结构预算保持不变，截断仍显式标明。

Java 校验成功调用、调用顺序、动作和输入摘要、完整 HTTP 响应及资源身份。诊断进一步要求调用编号与 R01 的 `playerContract` 一致，然后输出响应摘要、调用、外层字段路径和嵌套相对路径。普通及加强模型请求都能获得该材料，AT 内置诊断面板显示中文匹配说明。数值相等不被提升为已确认数据流、故障原因、必需解码算法或可播证明。

没有修改节点门槛、维修轮次、续轮授权、语义续轮签名、候选判定或发布行为。新证据不会自行增加预算或触发已停止的维修。

## 验证

环境继续使用项目 D 盘缓存及 Android Studio JBR；没有重跑全项目测试或构建镜像。

- Python：在 `plugin-maintenance/scripts` 执行 `py -3.14 -B -X utf8 -m unittest test_managed_loader -q`，86 项通过，包含新增值匹配、嵌套字段及调用隔离检查。
- Java：在 `plugin-maintenance/server` 执行离线 Maven `test`，`-Dtest=ManagedResourceEvidenceTest,RepairDiagnosticsTest#playerValueCorrespondenceKeepsRelativeEmbeddedPathsAndRequiresTheSampledPlayerCalls+playerDiagnosticsReachRepairContextWithoutCopyingRawUrlsOrChangingAcceptance,ModelClientTest#playerOutputShapesReachOrdinaryAndEnhancedRequestsWithoutRawValues`，14 项通过、1 项旧条件用例跳过、0 失败。
- 设置 `MAINTENANCE_PLAYER_VALUE_REPLAY` 生成 Python 宿主回执，Java 使用 `-Dmaintenance.player-values-replay=...` 原样投影通过。该项是跨语言夹具，不是真实站点验收。
- 前端：`node --test tests/maintenance/enhancedDiagnostics.test.mjs`，17 项通过；`node_modules/.bin/vue-tsc --noEmit` 通过。
- `git diff --check` 通过。

## 初次本地实现边界

初次本地实现阶段没有联网执行来源、调用模型、部署 NAS 或发布插件。爱看当时保持原 8/9、R01 `PLAYER_URL_INVALID`、维修 `STOPPED/REPEATED_CANDIDATE`、累计 4/4，不能视为已修复或复用第四轮授权。

下一步应在测试实例部署当前模块和配套运行器后，由平台复验原候选，取得新的字段对应证据；旧回执不能事后补出原值比较。该能力可缩小实际提取或透传的位置，但不会凭空恢复未观测的私有协议和站点解析逻辑。若仍缺实际解析脚本或接口证据，继续补该证据，不手改来源、不盲增模型轮次。

## RC13 真实测试验收

2026-11-19 已一次构建当前内置 Web、JAR 和配套执行器，并部署到 NAS 内置测试实例 `18992`，模块版本 `0.106.0-RC13`。JAR SHA256 为 `0c0da8e9bb737b478fb4166e243089a18ddb73107fd9b61690ab64cd88965e66`，运行器清单 SHA256 为 `06409aae58c93de190f5c9d7a8e4626576e1c0f7c9593e804e5d98fe38de44f0`；实际运行文件一致，5 个内置 Web 文件通过 HTTP 逐字节回读，原独立页面路径返回 404。

原测试数据库停机冷复制到全新目录，原库哈希及全部来源、维修、候选、模型账本保持。当前内置镜像 `sha256:85e7328df03bc0bf2129824dfabfe2d32bbf34988ad3ebf0be2c75f9ffa50e1b`；RC12 镜像及原库保留回滚。精确退役更旧的内置 RC9.1 容器与镜像各 1 个，未删除数据、未全局清理、未动独立测试端或生产。

平台对第四轮候选 `3db13a53-4ae0-4d16-9d13-baef0cf89c84` 执行一次运行时复验。其源码 SHA256 仍为 `9472d9e0cc73ddfdbe1a4cf0a373121d634ceca7bfbbfd80c6a05dcda81ca50b`，与第三轮原候选完全相同；运行任务为 `687ecfa3-02b7-444f-8d1e-e6901aeffbe9`。未创建候选、未追加模型调用或轮次，仍为累计 4/4，任务容器清理确认。

真实回执中 `detailCallId=5`、`playCallId=7` 成功绑定。详情 API 的 `data.list[0].resData` 内嵌 JSON 字段 `[0].url` 与本次播放器返回值为 `exact` 原值相等。返回值为 55 字节不透明字符串、无主机、声明直连，R01 仍 `PLAYER_URL_INVALID`，必需项仍 8/9，`repairSuccessful=false`。部分结构被既有预算截断，诊断保留 `truncated=true`，不据此宣称已穷尽响应。

新证据定位到了对应字段，但仍未证明转换算法、协议、因果关系或可播性；也不能把其他请求的 403 直接认定为取链根因。平台续轮入口返回 `RESPONSE_STRUCTURE_ALREADY_USED`，未绕过既有证据与轮次规则。

本轮真实执行 1 次、模型调用新增 0、生产发布 0；独立 `18991` 和本机 `18990` 保持。内置测试实例在本次诊断期间停用自动巡检、自动递交和自动生产发布，避免扩大执行范围。上游 PR 未更新，本次仍是模块本地迭代，不是稳定发行。

私有验收回执保存在项目外层 `recovery/player_values_rc13_20261119/` 的 `delivery.json`、`diagnostics.json`、`revalidation-final.json` 和 `retirement.json`。各部署与复验阶段已终态，禁止重放。下一步应通过平台补取实际消费该字段的解析脚本或接口证据，不重复同结构模型修复，也不人工改源冒充自动修复。
