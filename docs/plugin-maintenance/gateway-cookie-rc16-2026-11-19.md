# RC16.3 网关与宿主 Cookie 轻量复用验收

## 结论

2026-11-19，NAS 测试实例的 `0.106.0-RC16.3` 经平台联网巡检，分别完成 TRAWL 和 flaresolverr-rs-alt 的网关浏览器到宿主普通 HTTP 会话复用。两次均只访问网关一次，后续请求以配套 Cookie/UA 经普通 HTTP 获得非挑战业务内容，会话清理确认。

这里的“跨出口”指网关浏览器与宿主固定公网 HTTP 两条传输路径，不证明两个出口使用不同公网 IP，也不保证所有站点都接受迁移。`COMPLETED` 表示任务结束，不表示源维修成功。

## 实现与归属

- 继续使用单个 `evidenceGateway` 配置入口，兼容 `trawl`、`flaresolverr`、`flaresolverr-rs-alt`；不增加网关专用页面。
- `scripts/evidence_gateway.py` 统一适配协议、会话、错误分类及有界恢复；`source_egress.py` 在可信宿主实际 HTTP 传输层注入独立状态，隔离爬虫不能读取网关 Cookie。
- `HostCookieSession` 只在内存保存 Cookie 和配套 UA，限定任务、精确 origin、Path/Secure/有效期，最长 30 分钟；任务结束清空。不进入模型、日志、普通源 CookieJar 或持久文件。
- 匿名任务采用网关快照，同名 Cookie 轮换留在独立宿主状态。显式宿主凭据、源内 Cookie、Authorization、专用签名、POST 和 Range 请求不迁移；不把已有账号凭据发送给网关。
- 普通 HTTP 拒绝后清除轻量状态；明确挑战可继续用原网关会话。请求数、字节、截止时间及来源 429 账本保持，不通过换会话规避限流。
- 网关忙碌、连接/读超时、目标 DNS、损坏会话分别归因；冷却到期后的后续请求最多恢复两次，不在原请求内无限重试。未确认清理的导航不能重建重叠会话。
- `ManagedSourceRuntime` 仅白名单投影状态和计数。`cookiesExported=false` 指未向爬虫或证据载荷输出 Cookie，并非网关未将状态传入可信宿主。

该能力接在维护平台隔离爬虫及受管资源 HTTP 链上，不自动改写生产 AT/T4 网络。脚本辅助取证仍复用网关浏览器；渲染 HTML 不冒充原始脚本或媒体字节。应用 JAR 与 15 文件宿主执行包必须配套更新。

## 平台实站结果

两次均从平台 `POST /sources/{sourceId}/probe-runs` 创建 `mode=managed_source` 任务，使用原插件，不手改样本或另造成功夹具。

| 项目 | TRAWL | flaresolverr-rs-alt |
| --- | --- | --- |
| 平台运行 ID | `4088fbe4-fc89-41bd-93a1-255a3ff74506` | `33e79545-0a58-4f89-a74e-347b3563d8ad` |
| 网关会话 / 请求 / 响应 | 1 / 1 / 1 | 1 / 1 / 1 |
| 网关文档 | 200，9602 字节 | 200，10232 字节 |
| 后续普通 HTTP | 200，11769 字节 | 200，11769 字节 |
| `lightweightCounts` | attempts=1，httpAccepted=1，rejected=0 | attempts=1，httpAccepted=1，rejected=0 |
| 网关执行信息 | 实际返回 tier3；缓存状态未知 | tier 与缓存状态均未知 |
| 网关及执行任务清理 | 已确认 | 已确认 |

后续 HTTP 事件有固定公网连接证据及 `lightweightCookieReuse=http_accepted`，没有 `external_evidence_gateway` 标记；区别于只取得网关 200 或仅有 `hostCookieState=available`。

原插件必需项均为 7/9：C10 失败、R01 未进入，`sourceHealthEligible=false`、`publishEligible=false`。旧第四轮候选仍为 8/9、维修 4/4 且 STOPPED。本次只验收传输能力，没有新增模型轮次或生产发布。

## 失败记录与修正

RC16/16.1/16.2 的旧回执保留。早期网关成功，但普通 HTTP 复用尝试为零；最终通过有限跳过原因确认匿名同名 Cookie 冲突，RC16.3 修正为匿名快照接管及后续私有轮换隔离，才取得真实 HTTP 复用结果。

RC16.1 曾因 `REPAIR_REVALIDATION_NOT_READY` 返回 409，未启动 rs-alt 候选复验；误关联旧 TRAWL 结果的回执已明确作废，不纳入本次验收。最终两种适配器均有独立的新平台巡检与结果身份，不绕过原候选重复复验门禁。

## 包与验证

- 运行 JAR SHA-256：`31d59b924e79c15d8d6400291aa565636e5b6e30cffd4658b5f8f355e0ce2278`。
- 15 文件宿主 bundle SHA-256：`97149b56be18bf6450f58245282691f1b0436f0de6063a8a582c7367ce3e5cd5`。
- 镜像 ID：`sha256:543c5595154b5cc00b2acc8ad2cb21473e05a0eeea809589acd373e449faf302`。规范版本标签与项目、角色、版本、构建身份、保留策略标签均已写入。
- 复用已通过的网关、预算、隔离与恢复定向测试；Cookie 新增及受影响项按修改续验。`ManagedSourceRuntimeTest` 38 项已有通过记录，最后只续验状态投影；不重新运行未变化的全量测试或 Web 构建。
- 最终回读容器运行状态、镜像与 JAR SHA、无重启、冷库复制校验、7 来源有效源码及原历史账本、生产身份。新增模型调用、追加轮次、生产发布均为 0。

私有原始回执在项目 `recovery/gateway_cookie_rc163_20261119/`，最终对账为 `delivery.json`；不公开私有网关地址、Cookie、UA、样本源码、活动数据库或部署助手。旧失败回执不覆盖，已完成阶段不重放。

## 待续业务

当前闭环是平台传输能力，不是 CF 通用破解、不同公网 IP 的普遍兼容、原签名重新封装、未知故障无人值守维修到生产自动发布。普通 AT/T4 运行时如需该能力，应另做宿主接线及验收。
