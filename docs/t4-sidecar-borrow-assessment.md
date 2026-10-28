# t4-sidecar 借鉴评估

> 2026-10-28 评估。对象:~/workspace/alist-tvbox-t4-sidecar-main(Python 外挂式 T4 网关,
> 独立进程 + docker-compose,把 alist-tvbox 各端点翻译成 type 4,并 vendored 本仓库的
> Atvp.py 在服务端 Python 进程跑插件)。对照:本仓库已内置 T4(e890f2af 起,14 站点五动作
> + 全代理播放 + type3 移除)。
> 定性:同一问题域的**外挂路线**——主项目内置 T4 上线后,其 T4 适配层价值被覆盖,
> 但服务端 Python 插件运行时、HLS 会话化代理、兼容性事实库三块是真金。

## A 级(值得吸收)

### A1 服务端 Python 插件运行时(sidecar/worker/atvp_runtime.py)——T4 二期蓝图

正是本仓库"服务端化第三方插件源"的完整参考实现,且跑的就是我们自己的 Atvp.py:

- **子进程隔离 + 行协议**(worker/protocol.py):id 关联请求/响应;失败响应必须沿用请求 id
  (FAILURE_ANALYSIS 根因4:新生成 id 会把真实错误伪装成协议错误)
- **exec 加载 + 模块隔离**:`base.spider` shim 注入 sys.modules,退出时恢复 sys.modules/
  sys.path 快照;不注册模块会导致 dataclass 延迟注解 AttributeError(根因3)
- **AST 判定插件筛选参数形态**(`_filter_mode`):静态分析 categoryContent 内是否
  `json.loads(extend)` ——解决"插件宿主要 dict 还是 JSON 字符串不一"的硬坑,黑科技级技巧,
  二期直接可搬
- stdout 静默重定向到 stderr(插件乱 print 不污染行协议通道)
- JS 插件走 node 子进程同款行协议(线程锁串行);remote loader 仅允许 /Atvp.py 或
  /atvp/*.py(SSRF 收口)
- PluginHost 桥 localProxy(插件本地代理回调在服务端等价复刻)

二期立项时该文件为起点;Java 侧只需子进程管理 + 行协议 + 超时熔断。

### A2 HLS 会话化代理(sidecar/hls.py + playback.py)

本仓库 /live-proxy 已有 m3u8 改写(writeManifest),sidecar 更进一步:

- **会话资产树**:parent~child 键、分片按 sha256(kind+url) 去重、LRU+TTL **按会话整体
  淘汰**(预算 16384、闲置 2h)——教训:独立分片缓存会让"长清单挤掉自己的早期分片"
- **PNG 包装 TS 剥离的严格判定**(hls.png_ts_offset):PNG 块 CRC 校验 + 连续 8 个合法
  188 字节 TS 包 + 含 PAT 三条件同时成立才剥,否则报错不动手——"不猜测性变换"。
  对照本仓库 Node 版 TVBOX 的 4KB 魔数探测([[node-tvbox-fake-header-strip]]),
  若服务端也要剥伪装头,按此判定升级
- 加密/BYTERANGE 段不做任何猜测性变换

### A3 兼容性事实库(COMPATIBILITY_BASELINE/GATE、FAILURE_ANALYSIS)

校准内置 T4 的硬事实:

- FongMi type4 恒 GET(catvod OkHttp 三参 newCall = buildUrl 拼 query,已核 fongmi_tv 源码);
  "同地址须支持 GET/JSON POST/表单 POST"是杂牌壳子防御(M11)。我们 @GetMapping 对
  FongMi 够用;**@GetMapping→@RequestMapping 一行可覆盖表单 POST**(JSON POST 需专门
  合并 body,暂缓)
- "未声明身份不要冒充 FongMi,否则 AT 下发内嵌 DASH"(M14)——我们 X-CLIENT: t4 不冒充,
  且强制 durl 单文件,口径更稳
- 播放响应 header 可能是 dict 或 JSON 字符串,合同边界须规范化(根因5)——我们 T4 零头
  要求,不受影响;T3 侧知识备查
- `/vod` 详情返回的 ids 是 /p 代理 URL,误当播放 id 传 /play 即 400/502(0.2.3 最大教训)
  ——我们 T4 播放 id 与详情分离,未踩;直播直链透传分支(http 前缀)已防呆
- api 地址 http://内网/误配会致客户端 DioException;反代下 X-Forwarded-Proto 保 https
  ——我们 #1073 Utils.publicScheme 已收口
- type=4 客户端**不支持原生推送**(sidecar 对纯 T4 端服务端推送路由直接 501)——印证我们把
  push_agent 移出 T4 配置的口径
- **indexs=1 只有 FongMi 遵守**(点标题触发全局搜片);忽略 indexs 的客户端需要**服务端
  代劳搜片**(sidecar:前 6 可搜索源、3 路并发、同名+年份门禁合并)——这是内置 T4 的
  真空白:片单导航(csp_PianDan)在通用壳子上点卡片无详情无搜片,等于不可用。候选解法:
  T4 的 PianDan detail 实现为服务端跨源搜索结果(或文档注明局限)

## B 级(可选,暂不搬)

- **opaque id 进程内映射**(t4/opaque.py,token 映射防凭证/原始 id 出响应):我们 api URL
  已嵌 token、播放走 /proxy 短地址,泄露面小;且进程内映射重启失效(sidecar 自己承认升级后
  须重进详情)——同族权衡与我们标题缓存一致,不搬
- 诊断 trace_id 贯穿 + 失败去连锁统计(FAILURE_ANALYSIS 的方法论):排障靠 LogFilter grep
  够用
- 图片 MIME 按内容签名判活(PNG 头 JPEG 体类):卡片健康检查场景,暂无此需求

## 已覆盖/不搬

- T4 五动作参数适配(adapter._translate 与我们 T4Controller 同构,我们站点覆盖更全)
- DASH data: URI 对声明支持者转 HTTP .mpd 双轨——我们强制 durl 单文件更彻底
- 播放策略"普通直链+header 原样交客户端、受保护才代理"——我们全代理(凭证不出服务器
  是硬约束,占带宽是接受的代价)
- parse=0/jx=0 固定、不可解析明确 502 不交嗅探——同口径

## 结论

内置 T4 后该 sidecar 的存在意义只剩:不想动主服务部署的用户。对本仓库直接可落成的吸收
(①②已于 2026-10-28 落地:vod 端点 @RequestMapping+JSON body 兜底;PianDan 详情委托
/media 片单详情实现服务端代搜):③已落地(2026-10-28 二期):Alpine 升 3.21 + t4worker.py + PluginWorkerService,插件源进 T4;
蓝图吸收了行协议/AST 探测/stdout 静默/失败响应沿用请求 id 四要点。
