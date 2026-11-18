# 正式 T4 首页推荐合并增量

## 状态

2026-11-16，C6 首页推荐合并已落实正式 AT worker 与维护平台受管适配，并完成独立维护工作台的运行交付：本机 `18990` 现为 `0.75-SNAPSHOT`，45 条匹配的新 NAS 通道就绪，45 条旧通道已停止，旧日志保留。本次当前夹具验收为 36 个 HTTP 动作、两条会话，六次首页完整结果 SHA 全部匹配。

原 M1-M5 目标继续 active。C6 的独立维护端运行交付和完整内置 AT 原生 T4 首页增量 HTTP 均已关闭，不认证真实站点健康、R05、客户端、M2 全矩阵或完整目标。内置验收另有独立 owner 和回执，不借用独立维护端结论。

生产 AT、已有来源画像/配置/额度/调度和旧交付包未修改；没有执行生产来源或调用模型。AT416 复杂功能源仍排除；仅 AT519/520 已确认资料导航，不按名称或缺少播放链接猜测用途。

## 实现

- `src/main/resources/scripts/t4worker.py`：正式 `home` 顺序调用 `homeContent(filter)` 和可选的 `homeVideoContent()`。
- `plugin-maintenance/managed-source/egress_payload.py`：通过 `install_home_merge` 适配受管入口，复用固定 worker 的调用与 JSONL 错误协议，不修改冻结运行时文件或历史 pins。
- 两端保留首页分类、筛选和元数据；推荐 `list` 整体覆盖原列表，不拼接、不去重，保留顺序。显式 `[]` 清空，缺少方法、继承默认空实现、null 或没有推荐列表则保留首页列表。
- 支持字典和 JSON 字符串；字符串在 `backend_parse` 模式下使用 ATVP 现有卡片规范化，不重复添加详情前缀。
- 无效对象/列表、JSON 错误或回调异常继续返回失败，不降级为成功空列表，不自动重试；同一 worker 可继续后续动作。

该合同不等于来源健康判定，也不新增资料源必须提供推荐或播放的要求。新回调在真实任务中仍使用同一既有受管网络通道及任务预算。

## 源码阶段验证（已完成，不重复）

所有命令先在同一 PowerShell 进程点载 `plugin-maintenance/scripts/use_project_storage.ps1 -Quiet`，临时输出在 D 盘。

| 命令 | 结果与范围 |
| --- | --- |
| `py -3.14 -B -X utf8 plugin-maintenance/scripts/test_t4_home_runtime.py` | 8 项通过，20.435 秒；正式 native 与固定 worker + 当前 managed 适配两入口，实际子进程 JSONL；网络入口明确关闭，仅可信本地夹具 |
| `.codex/tools/apache-maven-3.9.11/bin/mvn.cmd -o -Pmaintenance-embedded -Dtest=PluginWorkerIdentityTest#homeMergesRecommendationsInTheActualWorkerAndKeepsItsGeneration+homeRecommendationFailureRemainsAnErrorWithoutRetryingOrLosingTheWorker test` | 2 项通过，0 失败/错误/跳过，45.166 秒；Java 服务实际启动 Python worker，验证列表合并、代次保留、异常传播和不重试；Java 使用 Android Studio JBR，`T4_PYTHON` 指向项目 `.venv/Scripts/python.exe` |
| `py -3.14 -B -X utf8 plugin-maintenance/scripts/test_managed_proxy_payload.py` | 14 项通过，1.285 秒；受影响适配文件的既有代理行为 |
| `py -3.14 -B -X utf8 plugin-maintenance/scripts/build_managed_runtime.py --write` | 生成当前 14 文件受管身份，`be7ad1dc127667d374a8e03f578f4cf2d482f9ef62a7c13d3e3e3deceeb21c05` |
| `git diff --check -- src/main/resources/scripts/t4worker.py` | 通过 |

总计 24 项不同用例，每组执行一次，没有全量回归或 Web 构建。Maven 编译了当前内置模式源码及测试，但只执行上述两个用例，没有打包。没有运行旧合同实验或修改 pins 以消除其预期的源码漂移。

## 输入身份

| 文件 | SHA-256 |
| --- | --- |
| 正式 `t4worker.py` | `d4807b15aefed39efd181f41769e54d98e416f0f52169b82999b9092411e46bb` |
| 当前 `egress_payload.py` | `88aa8c87fd1c1163a39f0986ade449096c699ccccf7ab00c23857404bd74a858` |
| 新增双入口测试 | `b2887d80adafccb56173eb86888d7fd68576b36827aab72b59ec5ac22e2de2e6` |
| 当前 `PluginWorkerIdentityTest.java` | `faa0195e477dbe0e219a75c12ec942820f7f7e44208ffdfb5c7b34efbcb958bb` |
| 未修改的固定 runtime manifest | `777721ccbdb2460100e06e2afab0f515a6610ac3c42831262af5c5a0d2aec10f` |

## 运行验收暴露的问题与修复

首次隔离任务 `38477b65-30a9-4358-9e94-d2882ff76b9d` 已执行两条 HTTP 子会话，但最终完整观察加入父日志后超过旧 65536 字节上限，报告 `MANAGED_JOURNAL_COMMIT_FAILED` / `CLEANUP_UNCONFIRMED`。原失败和清理回执保留，不修改成成功或原任务重放。

修复仅涉及宿主 broker 日志与 Java 控制回复：日志容量为 524288 字节；`status`、`cancel`、`cancel_acceptance`、`start_http` 回复允许 525312 字节；普通 RPC 仍为 65536 字节。大日志保存及重启读取、控制帧、原 RPC 上限和真实写入失败阻断由四项不同 Python 定向及两项 Java 实际 TCP 用例覆盖。首项夹具错误修正后仅续该项，其余通过项未重跑。

日志修复不改变 egress payload，复用已经构建并逐文件核对的三类 source 镜像和 HTTP 镜像，没有为该修复重建镜像。新受管身份为 `de158cff0465e1a79277a874002c123ec618b3e939bcfdf57b69f3823c8d43a8`。

## 当前运行交付

在新 `journal-fix` 回执目录建立新 runtime、broker roots 和隔离任务，不复用旧可变日志。当前任务 `2deb6ec3-cdfe-44b0-8956-e8edcc13bb1a` 完成 36 HTTP / 2 会话 / 6 首页结果核对，期望 SHA 为 `37eeb371ba446addc457b8feb5648eb98d55c7d100a5d9def87968c2fc53b52d`。

可信无网络夹具保留首页分类、筛选和 marker，以 JSON 字符串返回 B/A/B 推荐，验证整体覆盖旧列表、保序且不去重；父日志实际 69730 字节，已超过旧上限并成功提交。父任务及两条子任务均 `COMPLETED`，清理确认；临时平台、验收 broker、隧道和隔离容器已收尾。

| 交付项 | 当前结果 |
| --- | --- |
| 独立 JAR | `fea3c9c365c4660e5da85e0ba44aeb0723f0da6d4f9c850913079875720c6503`，已运行 |
| 内置 JAR | `d488bb441cd1fa32fe80e13b019073fc287e303e89f8630a1c0ce619fb4974bd`，当前原生 T4 首页 HTTP 已验，未部署生产 |
| 工作台进程 | Java `11516` / launcher `36344`；54 来源、45 通道 ready |
| 页面交付 | 首页及引用的静态资源实际 HTTP 字节与新 JAR 一致 |
| 状态保持 | 来源摘要、模型连接、额度、调度、连接文件、旧 broker 日志和生产容器身份保持 |
| 镜像生命周期 | 四类镜像按规范名称/标签接受；旧 source 版作为 rollback；精确删除本批 9 个弃用镜像，无冲突，无 force/global prune |
| 外部业务 | 真实来源执行 0、模型调用 0、生产写入 0；未重跑已闭环来源或静态/Web 全量 |

本次操作命令均为 `py -3.14 -B -X utf8 <recovery>/rollout_t4_home_20261116.py <stage>`：先 `journal_fix_init`，再对 `prepare`、`acceptance`、`start_channels`、`switch`、`finalize` 显式追加 `journal-fix`。所有阶段已执行终态，不得重放；`observe_acceptance journal-fix` 为只读状态观察。原 HTTP 封装失败和首次日志失败均保持。

权威运行回执为外层 `recovery/t4-home-rollout-20261116/journal-fix/{acceptance-verification,switch-completed,final-summary,accepted-images}.json`；源码阶段旧回执仍为 `recovery/t4-home-current-runtime-20261116/verification.json`，不向旧回执移植新证明。

## 完整内置 AT 当前增量

复用现成0.75内置JAR与基础镜像 `61f3854a`，从已经接受的V71隔离副本克隆新目录，仅新增可信无网络夹具。没有重建镜像/JAR或重放旧升级、上游获取、认证/恢复综合实验；管理员仅用于既有HTTP登录和插件扫描，不设置新凭据。

run=`run-20261116-44e890022b65`，容器=`91bf460e118b4ce739b59b0bf3b030e44472b3e81679780031eaef4a5cc74a38`。正常 `java -jar` 启动真实 `AListApplication`，通过原生 `/t4/{token}/v/{site}` 的GET、表单POST、JSON POST分别获取首页，三次完整结果均匹配上述 `37eeb371...`；不是替身控制器或模拟仓储。

实际ADMIN/no-store loader观察证明：源码SHA `5921aad9f4d08a196e7870ae06bd8594c4359723b00b8b781009a8e32c1c1f57`、正式worker `d4807b15`、ATVP `d3311ea9` 与冻结输入一致，三次worker代次均 `59ca5fd1ad537cdabae69ecff9cb227c`。保留分类、筛选、marker、B/A/B顺序与重复，独立管理页面可达。

Java经SIGTERM关闭，记录退出143；自动移除容器后核对原副本三个关键文件SHA、生产容器身份、本机进程/54来源/通道配置/模型/额度/调度及内置JAR均保持。第一次回执读取误将格式化JSON按JSONL解析，只修助手后只读续查原结果，没有重启容器或重复请求。故容器退出码未重新观察，记录null，不冒称已抓到退出0。

权威外层 `recovery/embedded-home-075-20261116/{terminal,verification,final-summary}.json`，全部完成。命令为 `py -3.14 -B -X utf8 <recovery>/accept_embedded_home_075_20261116.py <prepare|start|observe|verify>`；已执行阶段终态，禁止重放。真实来源/模型/生产写/新增镜像均0，只有该C6增量被认证；下一继续原M2/M4/M5具体开放门槛，不再重做两种入口的本批验收。长期调度、正式PR及独立容器分支仍须另行授权。
