# 逆向分析引擎交付与配置

逆向引擎为修复阻断提供页面、脚本和网络观察，不是另一套 Spider 执行器。
M5 第二单元包含 `plugin-maintenance/reverse-engine/` 的四份实现、依赖及锁文件、
`THIRD_PARTY.md` 和五份通用测试。第三方来源及许可证见该目录的说明。
不包含 `node_modules`、浏览器、凭据或绑定本机工作台及实际样本的 `tests/workbench-smoke.mjs`。

## 安装

在应用完整补丁系列后的副本中执行：

```powershell
npm.cmd --prefix plugin-maintenance/reverse-engine ci --ignore-scripts --no-audit --no-fund
```

依赖固定为 `chrome-remote-interface@0.34.0`、`playwright-core@1.58.2`、`webcrack@2.15.1`，必须保留 `--ignore-scripts`，
不运行 `isolated-vm` 的安装脚本。管理员显式指定本机 Chromium 系浏览器路径；
此步骤不下载浏览器、不接入个人浏览器会话、不导入 Cookie。
该目录与 Node/Python/浏览器是外置运行依赖，不封装在 Java JAR 内。

引擎 1.0.2 使用 CDP Fetch 请求阶段拦截，每次重定向仍经过原有受管 HTTP broker。
临时浏览器只在回环地址开放随机调试端口；不删除禁用原生出站的代理配置。
页面、弹窗、跨进程 Frame 及其 worker 在恢复运行前安装拦截，保留浏览器实际方法、
正文和最终地址；Set-Cookie 按独立响应头传回浏览器，不复制用户 Cookie。
脚本及请求身份按 CDP session 分隔，避免不同目标的脚本 ID 或重定向网络 ID 错绑。

1.0.3 中，浏览器传输以本次访客浏览器实际选择的 Cookie 为准，不把它误当成 Spider
提交的未观察凭据。保留同名不同 Path 的 Cookie 顺序，删除后不由 Python 旧副本复活；
服务器的 Set-Cookie 逐条交给浏览器执行 Domain/Path/HttpOnly 等规则。
该适配仅用于独立浏览器 broker，不放宽 Spider 执行器的凭据验证，不导入个人登录态。
拒绝回执携带现有脱敏字段原因；观察窗口结束后停止接收新请求，再收集脚本和清理。

`tests/redirect.test.mjs` 使用 `REVERSE_TEST_BROWSER` 指定的独立浏览器及内存响应，
验证导航、XHR、307/303、Cookie、同进程/跨进程 Frame 和弹窗，无外部站点请求。
未提供该变量时该项会跳过，不能以跳过结果宣称已完成真实浏览器验证。

## 独立工作台

### 运行时异常定位

引擎 `1.0.19` 在受管目标恢复前观察 Runtime 异常，至多八条异常/每条十二帧。
脚本位置按 session 绑定已解析源码并优先采集；原始错误消息和异常对象不保存。
本机 `0.70` 允许仅运行器、执行策略或检查器变化的旧任务继续独立访客诊断，
保留历史基线 freshness/staleInputs；源码/画像变化仍拒绝，不改变原验收、轮次或发布资格。
真实播放 POST/HTTP200 已观测，业务内容及媒体仍未验，见[本轮结果](runtime-exception-evidence-2026-11-15.md)。

在现有启动参数上同时提供两个必需参数；Node 不在 PATH 时再指定它的路径：

```powershell
& ./plugin-maintenance/scripts/start_local.ps1 `
  -ReverseEngineDirectory D:/review/plugin-maintenance/reverse-engine `
  -ReverseBrowserExecutable 'C:/Program Files/Google/Chrome/Application/chrome.exe' `
  -ReverseNodeExecutable 'C:/Program Files/nodejs/node.exe'
```

这只是配置示例，不应据此重复启动已有实例。端口、数据目录、JAR 与隔离运行器
沿用各实例的显式配置。独立 JAR 也支持 `application.yaml` 中的
`MAINTENANCE_REVERSE_ENGINE_DIR`、`MAINTENANCE_REVERSE_ENGINE_BROWSER`、
`MAINTENANCE_REVERSE_ENGINE_NODE` 环境变量别名。

配置了 `-ManagedRunnerTunnels` 时，启动器同时传入已有通道文件和
`managed_runner_tunnel.py`，网络经该来源运行宿主；否则网络位于工作台宿主。
远端模式仍需已有授权通道及 Python Paramiko 依赖，按[运行器运维](managed-runner-operations.md)
配置，不在交付包中附带真实连接文件。

## AT 内置模式

内置模式不加载独立服务的 `application.yaml`，使用相同的 Spring 属性键显式配置，
例如在已有 AT 启动参数后追加：

```text
--maintenance.reverse-engine.directory=/opt/maintenance/reverse-engine
--maintenance.reverse-engine.browser=/usr/bin/chromium
--maintenance.reverse-engine.node=/usr/bin/node
--maintenance.python=/usr/bin/python3
```

远端模式另提供 `maintenance.reverse-engine.tunnels-file` 和
`maintenance.reverse-engine.tunnel-script`；两者均指向实例自有文件。
目录、Node/Python 和浏览器须在 AT 进程所在环境可访问。配置不改变 AT 端口、
鉴权或数据库，也不授权生产换版。独立服务容器分支尚未交付。

## 证据边界

`readiness.ready` 仅表示必需文件存在，不证明浏览器能启动、远端通道可用或网站可访问。
依赖安装与打包通过也不是来源验收。观察回执绑定任务、原件和引擎身份；
脚本恢复只是诊断材料，不证明语义等价，不自动替换插件。
仍须由平台对候选执行真实联网复测；原有故障和失败历史保留。
不执行访问挑战或提升权限，论坛磁力站不以直接视频播放作为必需合同。
