# 隔离执行通道运维

## 当前范围

本 PR 仅提供 AT 内置工作台。同机部署优先复用已安装的 T4 私有 IPC；跨机时由管理员独立管理已安装 NAS broker 的固定隧道，不依赖 `recovery` 实验脚本或独立工作台启动器。
只绑定 `127.0.0.1`，SSH 使用已有可信主机记录；配置只保存环境变量名和固定运行身份，
不保存密码或任意远端命令。源脚本仍只进入原隔离 worker，隧道不负责安装、重启或清理 broker。
NAS broker 的可控停启由下文独立 root 运维入口负责，不通过页面或隧道开放远端命令。

历史双通道示例为黄果 `18991`、央视 `19002`；实际通道数量和身份以当前路由配置及连接观察为准。
管理入口为现有 AT `/#/maintenance`，不新增管理端口。后文历史验收的本机独立实例不属于当前安装路径。

## 宿主媒体依赖

可信 broker 的 HLS 解析和公开 AES-128 资源取样依赖
`plugin-maintenance/scripts/requirements-host.txt`。安装到本项目独占、管理员维护的
固定 runtime 的 `vendor` 目录；broker 现有入口会加载该目录，不修改 NAS 全局 Python
或生产 AT 镜像。Linux 的 AES 库须使用匹配 NAS 架构及 ABI 的发行包，不能复制 Windows 二进制。
记录发行包版本、SHA256 和安装回执，后续 runtime 克隆沿用该依赖目录。

受管媒体按真实清单读取采样分片及其 KEY/MAP，支持 identity AES-128、声明轮换、
显式或媒体序号 IV、METHOD=NONE、fMP4 初始化段及显式/连续隐式 BYTERANGE。
同 URI 的不同密钥声明不合并；加密 MAP 使用其声明时的密钥且要求显式 IV。
每次媒体范围最多 64 KiB，沿用本次任务的出站预算；只验证有界字节及解密前缀，
不代表客户端解码、seek 或长播。SAMPLE-AES/DRM 等其他加密不冒充支持。

## 自动发现初始来源

启用自动来源准备后，平台合并源码中的固定网址、当前画像的公开 `sourceConfig`
网址和已登记配套模块的网址。源码固定表达式支持拼接、常量别名、类/实例常量和
基础f-string，不执行源码来猜主机；配置网址追加去重，保留源码来源顺序。

例如爬虫原生读取 `base_url` 时，可在公开配置中设置
`{"base_url":"https://catalog.example.test/path"}`。字段名仍由该爬虫的init协议决定，
平台不会把所有来源改成同一种配置格式，也不会自动判断来源用途。
公开配置不接受Cookie或密钥，凭据仍走平台现有的独立凭据入口。

准备请求复用执行器的配置验证和规范摘要；方案、远端身份及回执绑定配置SHA，
计划只保存origin和摘要，不保存完整配置、URL路径或查询值。失败后配置变化生成
独立准备意图，旧失败保持。已有显式通道不会因修改配置而自动重建或扩大网络范围。
公网解析、原HTTP/HTTPS协议和端口检查沿用现有策略；准备成功不等于来源业务健康。

## 自动选择依赖镜像

启用自动来源准备后，平台按原件及已登记配套模块的显式传递导入选择现有镜像，
不执行原件来猜依赖，也不自动安装第三方包。与路由配置同目录的 `source-image-imports.json`
采用 `{"schemaVersion":1,"images":{"sha256:<不可变镜像ID>":["requests","bs4"]}}` 结构；
模块清单由管理员在实际镜像中核对后登记，不能仅凭标签名称填写。

选择结果的 `imageId` 和 `requiredModules` 冻结进准备计划。已知依赖不匹配时明确停止，
不退回缺包镜像；未登记的模块仍由真实 Loader 判断，不把该清单当成任意模块可用证明。
配套模块登记继续使用独立的 `source-companions.json`，不因选择镜像扩大网络访问范围。
这两份文件属于实例运行配置，不进入源码审阅包，迁移时由管理员单独配置。

## 启动

隧道使用的 Python 需要安装 `plugin-maintenance/scripts/requirements-tunnel.txt`。
示例配置为 `plugin-maintenance/examples/managed-runner-tunnels.json`，
`ssh` 中的值为环境变量名，不是实际主机或凭据；已有主机公钥可以从默认
`~/.ssh/known_hosts` 读取，也可用绝对路径 `knownHostsFile` 指定。
未知主机不会自动信任。`sudoPasswordEnv` 仅在已安装 socket 需要 sudo 访问时设置。

每个通道固定本地端口、远端 `executor.sock`、instance/image/bundle/出站策略及可选
HTTP 驱动身份；对应的 `managed-runner-routes.json` 仍负责来源 UUID 到本地端口的映射。
端口及身份必须与已安装通道一致，不能用示例占位身份直接执行。

配置文件保存在实例私有目录，凭据通过配置中指定的既有环境变量传入。跨机隧道由管理员在单独进程或服务中运行，不随 AT 自动安装或退出：

```powershell
py -3.14 -X utf8 plugin-maintenance/scripts/managed_runner_tunnel.py run --config 'D:\at-data\maintenance\runner-tunnels.json'
```

在现有 AT 启动参数中加入 `--maintenance.managed-source.enabled=true` 和
`--maintenance.managed-source.routes-file=<私有路由文件绝对路径>`。每个路由使用上述已安装通道的固定本地端口与身份。
需要自动准备远端来源时，另设 `maintenance.managed-source.setup.enabled`、`setup.python`、`setup.script`
和 `setup.tunnels-file`；脚本为 `scripts/managed_source_setup.py`，必须先按其合同安装运行依赖。
同机 T4 改用 `maintenance.managed-source.setup.socket`，示例在 `deployment/compose.t4.yaml`。
配置不改变模型授权或已有来源账本；不重复启动已占用端口，不用示例身份执行任务。

## 断线和退出

SSH 断线后的**下一条新请求**建立新连接并核对所选 broker 身份。
已经发出的请求不自动重发，即使没有收到响应，也交由平台按原任务日志取消或恢复；
隧道不重新提交源码、不刷新预算。某个 broker 不在线，不要求其他来源也停用。

NAS 暂时离线或环境变量缺失时，管理页面仍可打开，隔离执行检查显示未连接；
连接恢复后的新请求可重新建立通道。不要用“隧道进程存在”代替实际连接检查。

AT 退出不会管理独立隧道服务或 NAS broker。管理员应通过持有的服务管理器或前台中断停止隧道；
工具正常退出回收自己的本地监听，不停止 NAS broker。强杀、整机断电或 NAS 重启不在正常退出保证内。
本 PR 不自动安装 Windows 计划任务、系统服务或 NAS 自启项。

NAS broker 非正常退出、遗留 socket 和未完成任务，仍需按原身份处理。
本工具不会删除 socket、重放任务、修改 journal 或自动修复未知状态。

## NAS Broker 可控停启

### 显式旧通道筛选

`managed_broker_retirement.py` 提供只读的旧通道筛选，供获准运维流程复用：
`superseded_roots(previous, current)` 只取相同 SSH 配置下上一快照不再引用的目录；
`broker_processes(retire_roots, keep_roots)` 从这些目录的 `service-owner.json`
取 PID，再核对对应 `/proc/<pid>/cmdline` 和启动时间，不枚举全机进程。
当前目录与待回收目录相交时拒绝；owner 缺失、PID 命令不匹配或僵尸进程只记录跳过，
不会扩大搜索。此模块不发 RPC、不停止服务、不清理文件，也不授予回收权限。
调用方仍须在停止前核对目录归属、配置/生命周期摘要、实时 serviceId、空闲状态、
已确认清理的 journal 及 PID 启动时间，并通过已有 `stop`/退出确认合同执行。
历史快照的缺失或被替换，不能用“扫描所有 broker”来补齐授权范围。
2026-11-15 的[范围修正记录](broker-retirement-scope-2026-11-15.md)仅认证本机筛选与接线，
尚无本版 NAS 回收验收。

产品脚本 `plugin-maintenance/scripts/managed_broker_service.py` 在 NAS 本机运行，
配置示例为 `plugin-maintenance/examples/managed-broker-service.json`。
root 所有且不可被组/其他用户写入的配置固定 broker 目录、既有 runtime 目录、
instance/image/bundle/策略及可选 HTTP 驱动身份；没有凭据或任意命令字段。
原 runtime 内容按冻结清单逐文件 SHA 校验，不要求改变其既有目录归属或权限。
运维脚本独立于 worker bundle，不重建镜像或迁移旧 journal 身份。

正常启动同时持有服务锁和原 journal 生命周期锁；创建原 executor socket 和
仅 root 可访问的 `operator.sock`。`status` 读取实时 `serviceId`、PID、运行身份和活动摘要。
`stop` / `restart` 必须携带该次 `serviceId`，旧进程的回执不能停止新进程。
在原接单锁下确认 source/HTTP/session 均无活动、journal 无 RUNNING 且无待领取 RPC
结果后才关闭准入；繁忙直接拒绝，不取消任务、不清预算。
等已接收的请求线程和 broker 退出后，只删除本次记录的相同 inode socket，
最后才释放锁。`restart` 确认停止后使用同一配置新启动，不重放源码或历史任务。

当前黄果、央视两个实验通道均已接管；分别使用各自目录内的脚本与配置：

```text
/usr/bin/python3 -I /vol1/1000/at-plugin-maintenance-lab/w-131f2e4544f3/managed_broker_service.py status --config /vol1/1000/at-plugin-maintenance-lab/w-131f2e4544f3/broker-service.json
/usr/bin/python3 -I /vol1/1000/at-plugin-maintenance-lab/w-77784b346f8c/managed_broker_service.py status --config /vol1/1000/at-plugin-maintenance-lab/w-77784b346f8c/broker-service.json
```

管理员在 NAS 上以 root 执行；先读取实时 `serviceId`，再将 `status` 换为 `restart`
并增加 `--service-id <本次serviceId>`。只看返回的 `state=RUNNING` 不足以证明平台连接，
还需页面“检查连接”核对实际身份。原 `owner.json` / `owner-recovery.json` 是安装历史，
不再是当前 PID 的权威来源；当前归属以各自产品 `status` 为准。

首次旧版接管是单独的一次性实验迁移：pidfd 核对 PID、启动时间、命令和身份，
冻结旧进程全部线程后再次确认 journal 只有锁文件，才终止该明确空闲进程；
退出及 journal 锁释放后核对 socket inode 再移除。回执
`recovery/broker-lifecycle-20261113/legacy-handover.json` 保留，
这不是日常停止方法，也不提供任意有历史/活动任务的旧 broker 自动接管。
随后央视单条已完成 loader 任务另作精确接管：冻结后要求仅主线程存活、
同一 journal SHA/完整状态/清理保持且没有 RPC、session 或 HTTP 驱动任务，
再按同一 pidfd/生命周期锁/socket inode 方式接管。回执单列为
`recovery/broker-history-restart-20261113/legacy-handover.json`。

异常退出不会自动删除遗留 socket，也不会解除 `CLEANUP_UNCONFIRMED`；
未完成任务仍按原恢复合同处理。新增的显式端点恢复见下一节，不自动接管旧通道。
SIGTERM 只在空闲时申请同一停机，繁忙时记录拒绝并继续服务。
未安装系统服务、NAS 自启或计划任务，未验证 NAS 整机重启或长期持续运行。

## 异常退出后显式恢复

新版本运维脚本在每次成功启动时保存 `service-owner.json`：该次serviceId、
配置摘要和两个socket的device/inode/ctime。只有同时取得服务锁和journal锁、
确认没有存活服务后，`recover --config <配置文件> --service-id <原serviceId>`
才释放完全匹配的遗留socket。任一端点身份变化则拒绝，不猜PID、不直接删文件。
RECOVERING/RECOVERED持久意图支持删除两个端点之间退出后的原操作续行。

该命令**仅恢复端点**，不清任务、不重放源码。随后`start`加载原journal；
原RUNNING任务变为`CLEANUP_UNCONFIRMED`且仍阻止接单。先`stop`停止该自有服务，
再使用原运行包`managed_source_broker.py`的离线入口，
传入`--recover-job`、精确`--container-id`及当前`--journal-sha256`，
连同相同root/instance/image核对并清理该任务，才能重新启动接收新任务。
使用`python -I`调用该入口时须显式将固定runtime的`scripts`和`vendor`加入
`sys.path`，不能依赖被隔离模式忽略的PYTHONPATH或脚本目录。
已完成、缺失或身份不匹配的任务不能靠清空日志解除；HTTP驱动使用其独立恢复入口。

2026-11-13在自有NAS broker完成一次平台任务执行中SIGKILL的真实验收：
原任务失败/清理阻断保持，显式恢复socket与精确容器清理后，
同库新任务COMPLETED；原失败平台响应完整保持、没有重放原任务。
14项相关检查通过，原9个通道和journal/生产身份保持，临时服务停止且容器均移除。
权威为
`recovery/broker-crash-recovery-20261113/run-20261113-54926ee4352c/resume-af849e1a/completion-37fb3fad/verification.json`。
脚本SHA`7d78448e15822dc0ad7f7d81197c30b33086b52bb78a7002236e51581a199d1e`；
只在新验收broker使用，既有常驻通道未换版。可信离线样例不证明真实来源健康、
HTTP驱动/多会话强杀恢复、NAS整机恢复或长期运行；前期助手失败均保留。

## 状态和日志

每次启动写入数据目录的 `tunnels/<本次UUID>/`：

- `state.json`：进程、配置 SHA、启动时各通道连接观察和退出状态。不是实时业务健康证明。
- `events.jsonl`：连接/失败代码，单文件 1 MiB、保留两个轮转文件，不记录密码、源码或帧正文。
- `stdout.log` / `stderr.log`：启动和退出摘要，不回显认证响应。

页面“模型与连接 → 隔离执行 → 检查连接”才是当前 broker 身份观察。
CLI 也可只读检查，不监听新端口、不发送源：

```powershell
py -3.14 -X utf8 plugin-maintenance/scripts/managed_runner_tunnel.py check --config 'D:\源主动维护项目\workspace\plugin-maintenance\server\data\mvp-18990\runner-tunnels.json'
```

以输出中每个通道的 `connected` / `ready` 为准，不仅看进程退出码。
历史 `recovery` 中的 setup/tunnel 脚本不再作为本机日常启动入口。

## 验证

当前自动来源准备同时支持公网HTTP和HTTPS字面来源，原协议/端口不改写；
HTTP也走批准IP固定连接，HTTPS证书不降级。新通道`public_http_https`
可按真实Location接入公网HTTP(S)跳转，旧`public_https`冻结策略仍只引入HTTPS。
不复制跨源Cookie/Bearer，也不导入账号凭据。
同一任务内，已观察到的站点响应Cookie按Domain/Path/Secure衔接已登记来源：
显式Domain可覆盖匹配子域；从子域设置父域Cookie时，父域须属于已登记来源。
不带Domain的Cookie只保留在原主机，域名回退时不透传；源端HTTP库误带的已观察Cookie
会从出站头中剔除，不再因此中断来源。未知Cookie仍拒绝，删除同步至相关来源。
平台保存的管理员Cookie/Authorization不进入这份响应Cookie台账，也不随别名复制。
这不是IPv6/任意动态初始来源支持，详见[HTTP来源交付边界](http-origins-2026-11-13.md)。
下方双通道及6来源的数量/PID是历史运维验收，不覆盖当前多通道状态。

最终定向命令：

```powershell
py -3.14 -X utf8 -m unittest -v test_managed_runner_tunnel
```

在 `plugin-maintenance/scripts` 下执行，19/19 通过。
当前真实 SSH 关闭后重连、双通道身份、平台 HTTP、原6源历史/预算/连接及 NAS journal
保持已核对；独立离线实例页面200，Java退出后启动器/隧道和两个端口均消失，
状态为 `STOPPED`。权威私有证据：
`recovery/runner-tunnel-ops-20261113/final-verification.json`、
`final-live-ssh-reconnect.json`、`offline-pilot-before.json`及`offline-pilot-exit.json`。
该隧道批次没有新增模型调用或爬虫执行，不认证 NAS broker 重启、OS 自启或长期观察。

后续 broker 定向命令 `py -3.14 -X utf8 -m unittest -v test_managed_broker_service`
最终 9/9，通过接单锁竞态、繁忙拒绝、结果保留、owner 失效和 journal 字节保持检查。
当前脚本 SHA `99ebea9d1cf3996e82b95be433a63914fa3d4e26ca46a94c46646a698a132970`；
一次真实 NAS 产品 `restart` 从 PID3652687 到3652936，运行身份完全一致，
工作台黄果/央视两通道 ready，6源历史/连接/预算、全部 journal 和生产身份保持。
权威 `recovery/broker-lifecycle-20261113/verification.json`。
该批黄果 journal 为空、央视只保持不变；模型新增0、源执行0，JAR/bundle均未变。

后续央视真实历史任务停启：产品 `restart` 从 PID3673581 到3674153，
保留原任务 `601dcac9-5a58-456f-bb49-7bd08362ee3f`，journal SHA
`a6a78f331563c722f6e3f2ca0c2558776a1b46d5d7503006024f3d553abd6d08` 不变，
首次接管及产品重启后经原 executor 协议查询完整 status 均与原件精确一致。
工作台双通道 ready、6源历史/连接/预算、黄果进程和生产身份均保持。
权威 `recovery/broker-history-restart-20261113/verification.json`。
本轮复用相同产品脚本，没有重跑单元套件、JAR构建、源或模型。
这关闭选定已完成 loader 任务的历史保留重启，不泛称未完成任务、任意日志布局、
异常强杀或NAS整机恢复已通过。
