# 爬虫修复经验：经源码核对的资源吸收

审阅日期：2026-11-10。本文是数据型参考，不授予联网、执行代码、使用凭据、安装依赖或发布权限。
资料来源与逐项目审查见 [资源学习记录](crawler-resource-study-2026-11-10.md)。
以下规则进入现有知识目录，随目录 SHA 冻结；不会重新解释或修改历史验收结果。

## 可检索规则

### response-contract-first
先核对状态、MIME、返回形态和业务字段，再修解析器。
对分类分页核对接口 total、实际条目数和页码关系，不硬编码总数。
JSON 合法或 HTTP 200 不证明分类、图片、播放合同成立。
参考 Scrapy 的请求/响应分层及本项目宿主装载器；网络响应与源返回合同分开记录。
Playwright 的 response 事件与状态只证明响应已出现；internalBody 等待请求结束，重定向 hop 正文不可用。
网络失败、正文不可用和有效空列表分开；二进制保留原始字节与 SHA，不能用 text 的替换解码证明媒体完整。
Colly 的 MaxBodySize 可在无额外超限错误时返回截断前缀；其显式 gzip 分支先限制压缩流再解压，不保证解压后大小。
区分完整正文、采样前缀、传输字节和解压字节；不能把截断造成的 JSON/DOM 缺失直接当站点改版，也不放大宿主预算换成功。
DrissionPage 的 NavResult.ok 只按状态判断，未知状态为 None；导航成功不证明业务字段成立，空正文、传输失败与有效空列表分开。
其 SessionPage.raw_data/html 对 Response 作真值判断，错误状态正文可能被隐藏；ChromiumTab 浏览器模式的 raw_data 实为 HTML 字符串，不能当原始媒体字节。
Playwright MCP 的 response-body 读取失败可直接返回而不 addError；无内容或没有 isError 不能区分未捕获、读取失败和有效空正文。
其网络列表的“成功”包含小于 400 的状态，重定向也可能被默认筛掉；缺媒体条目不能直接归因为源未请求，仍检查真实媒体字节与业务合同。
browsers-benchmark 的 calculate_metrics 对无样本返回 0，分母只含已收集结果，不含未到达的配置目标；零值、缺测、失败及有效空业务必须分开。
借鉴逐目标保留 error 与性能样本的方式，汇总同时核对计划数、实际数和缺失原因，不能把部分结果的 100% 当全部冻结节点通过。
Scrapy 的 HTTP11 reader 分别检查声明长度和实际累计下载量，HttpCompressionMiddleware 另检查解压后大小与编码层数；告警阈值不是拒绝阈值，max_size=0 表示不限量。
其 gunzip 可在已有输出后容忍 CRC/截断错误，_inflate 未用 eof 验证完整流；Response 的 partial/dataloss 或有内容不代表完整正文，沿原有有界宿主证据区分完整响应与前缀。
Scrapy/Playwright 的 301/302 可把 POST 改成无正文 GET，303 也有方法改写，307/308 则保留方法/正文；R04 同时核对首跳、各跳及最终实发参数，不能用最终 200 证明 JSON/FORM 已到达原入口。
Playwright 的 params、data、form 分别走查询、JSON/原始正文、表单编码；显式 Content-Type 可被保留而与正文不符。R04 验证 GET/FORM/JSON、pg/flag 时绑定实际方法、MIME、参数来源和 worker 实参，不把三种调用压成同一请求。
pydoll 的 get_network_response_body 仅返回 body 字符串并丢弃 base64Encoded；不能据此把 Base64 文本当媒体字节。其 expect_response 则关联 requestId 与 loadingFinished/loadingFailed，并按标志解码；同名正文接口仍须分别追踪真实实现。
ResponseHandle.ok 只看 2xx，即使 loadingFailed 后 body=None 也可能为真；204/205/304 的合法空正文与失败未收到正文不同。沿原装载器核对状态、完成事件、字节身份及业务必需字段，不能用 ok 或 URL 命中补绿。
Scrapy 的 @scrapes 只检查输出 item 的字段键，不检查非空、类型或条目关联，零 item 时也无缺字段错误；@returns 另检查指定类型的数量，两者不能互相替代。
借鉴独立的数量与逐行合同检查；合理空结果须有当前节点允许的原因，必需 ID、标题、封面及下游输出仍由原装载器核验，不能用字段存在或数量达标代替。
Scrapy MediaPipeline 保留每个请求的成功/失败结果，但 ImagesPipeline.item_completed 只留下成功项；借鉴逐资源回执，不把缩短后的图片列表按位置贴回原卡片，必需封面失败不能静默消失。
其 get_images 可旋转、转码和生成缩略图，image_downloaded 的校验和取首个处理结果；本项目 C09 保留原响应 SHA、实际解码及条目来源，转换后图片身份不能替代原响应身份，downloaded 也不证明客户端显示。
Scrapy LinkExtractor.matches 只查域名与 allow/deny 正则，不应用 extract_links 的 scheme、扩展名和文本条件；Crawlee addRequests 先过滤再执行 transformRequestFunction，变换后须核对实际 URL。匹配、归一化或改写都不证明媒体地址有效；保留原 URI/查询字节和来源，不猜高清路径。
Playwright Tracing.flushHarEntries 会写出尚未结束的网络条目，HarTracer 读取正文失败后也可通知 onEntryFinished；size=-1、只有响应头或条目结束均不证明正文完整。R04 和媒体节点仍沿原宿主合同分别核验实际调用、完成状态及字节，不按 trace 条数或结束通知补绿。
Crawl4AI 的 js_only 跳过 goto 并填入 status_code=200、空响应头；这只是当前页面处理分支，不是本次目标 URL 的 HTTP 观察值。已有页面、页面脚本完成与新鲜业务响应分别验收，不能用默认 200 补绿。
Crawlee ThrottlingRequestManager.markRequestAsHandled 在底层提交前调用 recordProgress；BasicCrawler 的重试耗尽分支也调用它。清除限流计时、handled 计数或队列结束不是业务成功，仍按当前装载器的冻结节点和实际失败回执判断，不能当作候选修复通过。
Playwright APIRequestContext 先累计并解压正文，再 Buffer.concat、保存并返回 APIResponse；之后 body() 读取的是存储结果，不是限量流式下载。原 headers 仍可含 gzip/压缩长度，摘要必须注明原始传输、解压正文或采样前缀，不能互换，也不能在 body() 返回后截断来冒充下载限额。
其 Chromium 正文回读在特定空结果/声明长度分支可用 Network.loadNetworkResource 重取 GET 静态资源（包括 media），fulfilled 请求则跳过该补取；同 URL 的回读字节未必来自首个请求。保留实际请求及补取来源，不能用额外取到的字节覆盖原调用失败或 R05 同链缺测。

### session-header-scope
媒体头被策略拒绝时，先核对宿主批准的目标 origin 和 headerOrigins。
保留上游实际返回的 Referer/Origin 及同一合法会话，不能删头、复制登录凭据或泛化出站权限换取通过。
EGRESS_HEADER_ORIGIN_DENIED 属于环境策略阻断，不是源码解析失败。
参考 curl_cffi Session 的 cookie、重定向和环境代理开关；它们并不自动满足本项目出站策略。
其 discard_cookies 只阻止回写会话 Cookie，不等于不发送现有 Cookie，也不清空会话；禁用环境代理不限制重定向目标。
每个获准请求仍由原出站 owner 校验地址、头、TLS 和预算，不导入浏览器身份或因“会话维护”扩大权限。
Colly 的 AllowedDomains 比较 hostname，不验证解析出的 IP、端口或 TLS；域名允许不替代本项目精确出站合同。
其自定义 redirectHandler 早于默认十跳及 Authorization 清理分支返回；最终安全性还取决于 HTTP 客户端，不能只依赖尾段默认处理。
MediaCrawler 的 BilibiliClient.pong 按业务 isLogin 判断，并在登录流程返回后再次校验；Cookie 存在、公开详情成功或 SSH 成功均不证明 AT 管理权限。
其管理器把 Cookie 加入命令参数，启动时又拼接整个命令记录日志；不照搬这条链，本项目凭据仍仅在获准进程内使用，诊断与模型输入先脱敏。
DrissionPage 的 change_mode 默认可复制 Cookie/UA 并导航；复制会话不是身份复验，也不是导入个人浏览器凭据的授权，同名 Cookie 的字典投影还会丢失域和路径关联。
其 SessionOptions.make_session 只应用真值选项，trust_env=False 或 max_redirects=0 不进入赋值分支；声明关闭不等于实际生效，仍由原出站 owner 核对有效设置。
proxy_pool 的 httpsTimeOutValidator 只以 HEAD 200 判可用且 verify=False，格式正则也不校验 IP/端口范围；不借用其代理或关闭本项目证书校验来恢复业务。
其 last_status 按 HTTP 结果更新，HTTP 失败可留下旧 https=True；借鉴 last_time/check_count 的可追溯性，但每次仍核对实际目标、协议、TLS 和当前返回，库存/历史标志不是本次可用证明。
Scrapy CookiesMiddleware 按 cookiejar 选择存储并重建 Cookie 头；dont_merge_cookies 跳过合并却不清除手动头，不是禁止发送凭据的开关。Playwright context.request/page.request 共享浏览器 Cookie，newContext 才另建存储；不得擅自导入或导出个人登录态。
两者重定向都会按条件移除 Authorization，Playwright 每跳清除原 Cookie 头再按目标存储重建；自定义密钥头并不自动全部移除。每跳仍核对宿主批准的目标、头来源和预算，不靠强行透传凭据或改 TLS 换成功。
AT 的 SSH 登录、CLIENT API Key、管理 session、订阅 token 和用户凭证 token 是不同合同；401 先定位真实入口、TokenFilter 与 SessionTokenService，拒绝把 Cookie 存在、公开读取成功或 SSH 成功当 ADMIN 权限证明。
R05 的 EXT 应来自真实 SubscriptionService/T4Controller 和本次 Servlet 上下文，核对 loader/source/raw、t4_play_type、proxy_base 的生成来源；Base64 不是保密措施，诊断只留字段名、形态与脱敏身份，不手工构造等价 EXT 充当正式通过。
独立真实 AT 鉴权实验确认 USER 的 principal 被 UserController 类级 PreAuthorize 拒绝为 403；管理 session 与内容 token 不能互换。诊断先核对路由与方法权限，不改权限迁就测试；该实验不证明 NAS 凭据或 R04/R05 通过。
Scrapy OffsiteMiddleware 允许 dont_filter/allow_offsite 跳过范围检查，allowed_domains 还可由 Spider 动态改变；Crawlee SameHostname/SameDomain 不限制协议和端口，SameOrigin 也不核验公网地址、精确路径或凭据，不能作为未知源的宿主出站授权。
链接发现与访问授权分开：只沿批准响应的指定字段及受限 HLS 登记精确地址，由可信 owner 绑定本次源和策略身份。EGRESS_URL_DENIED 时核对地址来源及跨动作登记，不从 Spider 返回值、猜测码率路径或扩大域名权限补绿。
Crawl4AI 的 session_id 复用 Page/Context，proxy_session_id 仅选择粘性代理，两者没有自动绑定；不同页面会话在相同配置签名下也能共用 Context，同名页面会话则在重算配置签名前返回。会话名或代理配置存在不证明 Cookie 隔离、代理已换或实际出口一致。
本项目 R05 分别核对实际 Spider/会话、可信配置身份、源内会话头和客户端可访问的 proxy_base；地址延续不是 Cookie/Spider 状态延续，粘性网络代理不是 localProxy 媒体回调。现有 T4Call 每动作 init→destroy，非空 data 与 forward 未接通仍保留缺测，不以框架会话 API 或回执形态代替实现。
Crawlee 固定 v4 源码的 saveResponseCookies=false 为每请求克隆 CookieJar，仍读取已有 Cookie，且重定向链内可更新该副本；关闭响应写回不等于禁止发送。BaseHttpClient 按每跳目标重新取 Cookie，显式 Cookie 只合并到临时副本，跨 origin 删除旧凭据头也不等于目标不会收到其匹配的存储 Cookie；逐跳授权仍由本项目 owner 核验。
该客户端在返回响应头后、正文与业务检查前就写入 jar，Session.setCookie 还可吞错误返回；写入方法返回、markGood 或 getState 都不是业务认证或脱敏证明。诊断分开记录读/写/发送/失效且不保存值；当前出站层拒绝 Cookie 并过滤 Set-Cookie，不因借鉴会话方法而放宽，未来状态晋级须沿既有清理和 journal 提交边界验证。
Playwright 的 fetchResponseUid 是已抓取正文的引用：同连接 fulfill 可直接引用，服务端还能在其他 APIRequestContext 中查找；它不是本项目父 job、源、画像或策略的绑定。本项目继续用既有唯一子 job/摘要/清理屏障关联，不照搬全局正文表或将正文消费改为可重复提取。
其 RO-100 测试在页面请求已经取消后仍允许 fulfill 正常返回，Chromium delegate 也容忍部分协议错误；回填方法返回不证明客户端收到字节。R05 需从本次真实 proxy_base 发起请求并核对响应和媒体字节，旁路抓取、内存正文引用或成功文案不能代替该回调链。
Crawlee BrowserPool.newPage 接收 session 不等于绑定独立页面状态；实际选择按插件、容量和代理条件，默认 useIncognitoPages=false 的 Playwright 包装复用同一 Context。Cookie 注入/回收在 BrowserCrawler 调用方，extractPageState 当前只含 cookies，不代表 localStorage、源实例或媒体会话已延续。
其 BrowserCrawler 在导航后和 handler 的 finally 回写 Cookie，失败 handler 也可能改动 jar；顺序双请求测试只检查会话 jar，未断言第二次实发 Cookie，另一持久化测试为 test.skip。沿现有源/画像/运行器身份分别核验状态拥有者、失败后失效与实发证据，不向未知源导入个人登录态，也不放宽当前 Cookie 隔离。
Scrapy RefererMiddleware 选择 Request.meta、响应 Referrer-Policy、默认配置中的策略；无效 meta 会跳过响应策略回退默认。普通子请求用 setdefault，no-referrer 不清除手写 Referer；起始请求也不自动生成。生成策略不是头清洗或宿主授权，先核对实际生效分支。
其重定向 handle_referer 先删除旧 Referer，再按当前重定向响应生成，缺中间件则不恢复；与普通子请求的保留行为不同。媒体头变化先定位首跳/重定向及真实来源，不把 403 直接当解析改版，也不强行透传旧头或开启自动重定向换通过。
strip_url 默认删除 URL 用户信息和 fragment，却保留 path/query；scrapy-default 可向另一 HTTPS origin 发送这些部分，不能当脱敏器。本项目 headerOrigins 只准既定 origin 形态，不接受任意完整播放页地址；保留 EGRESS_HEADER_ORIGIN_DENIED，沿原 owner 核对批准值及 R05 实际回调，不自动裁头、补造 Referer 或扩权。

### schema-candidate-replay
借鉴 llm-scraper 的代码生成思路：模型产物只是候选代码，不是已修复证明。
固定输入源码 SHA、失败节点、输出合同，静态校验后在隔离装载器重放全部冻结必需节点。
生成的 JavaScript 不能直接保存成 Python Spider；禁止在宿主 eval、页面中盲执行或热替换有效源。
其 generate() 将 schema 放进提示文本，没有为生成代码配置结构化输出校验；去掉 Markdown 围栏也不验证代码。
示例在 page.evaluate 后另做 schema.parse；本项目仍通过 exact_edits_v1 静态校验和冻结装载合同验收。
参考 src/models.ts、src/index.ts、examples/codegen.ts；schema 通过不等于页面业务或媒体可用。
ScrapeGraphAI 把超时/JSON 错误装入非空 answer 对象，不能以 answer 非空作为修复成功。
Stagehand 先验证转换后的元素 ID，再从当前快照回填 URL；未知 ID 会变空，回填后仍须验证原始合同与地址来源。
Firecrawl 的 parseWithSchema 是后处理而非严格 schema 校验：缺失必需字段可填空串/0，错误类型和多余字段仍可保留；必须区分观察值、默认值及业务有效性。
其生成器检查函数形态和少数全局名，不是安全认证；重复 extract 定义和带初始化表达式的声明可通过。生成、清洗、schema 命名或静态黑名单都不替代现有隔离边界。
browser-use 的 completed/is_done 只说明交付完成，success 是 finish 的模型自报，结果对象即使 timeout 仍为真值；errors 只列终止错误，不覆盖已恢复的工具错误。
其 check_delivery 只做一次占位/文件存在检查，第二次相同占位仍可交付，数值 0 也不算空；不能把交付保护、结构化结果或模型自报导入冻结节点 pass。
Skyvern 的 fired_unverified 显式区分未验证修复；其 healed_runs 却统计完成/失败/未验证的尝试，healthy 也可同时 scored=false 或 outcome_risk=true，不能按名称映射修复成功。
借鉴按运行/节点关联修复尝试、输出义务和证据引用；resolve_block_outcome 对同标签任一 completed 优先且不看先后，不能消除其他源码/画像/运行器身份下的失败或替代本次全必需节点回执。
Crawlee 自适应分支的 resultChecker 默认放行；未配置检查器时默认 resultComparator 只比较 datasetItems，两份空结果或缺失下游链接仍可判等价。
只配置检查器时，比较是“两边分别通过”，不是输出相同；浏览器分支 ok 后提交也不再调用该检查器。借鉴独立的有效性与等价性检查，仍按本项目全部冻结必需节点验收，不把渲染分类当修复成功。
Scrapy ContractsManager._clean_req 丢弃回调输出，产生 Request 不代表其下游被执行；check 默认关闭 ITEM_PIPELINES/FEEDS。借鉴按样本独立检查前置响应和后置输出，但分类样本通过不能代替详情、播放及代理的真实串联。
其普通前置断言失败被记录后仍可调用解析回调；没有可执行合同也可能报告零项 OK。检查文案、退出码或预检钩子不是执行授权，现有隔离/出站门禁及冻结必需节点缺测状态保持独立。
Stagehand 的 successfulActionResult 只证明动作方法返回，未校验目标业务后置条件；twoStep 的第二次推理没有动作时可返回第一步成功，不能据此认证完整播放流程。
其 selfHealAction 重选 selector 却保留原 method 和 resolvedArgs；只借鉴定位变化后的候选重测，字段/方法/参数合同漂移仍须重新诊断，不把选择器恢复等同全部节点修复。
Firecrawl 的 tooStrictSelectors 用正则收集字面量并在整份文档计数，不保留原 querySelector 的卡片作用域，也会读到注释且漏掉变量表达式；零命中后放宽 > 得到匹配只能作为定位线索，不自动改选择器。
其 runWithRepair 按失效选择器数量减少决定重放，删掉查询也会减少诊断项；仍比较原页/独立页的逐条 ID、必需字段、原始地址与冻结节点，不能用诊断变少、非空结果或唯一命中认证修复。
ScrapeGraphAI 的 CodeGeneratorGraph 将语法、执行、schema、语义四阶段分开，但 validation/semantic 子循环改 generated_code 后仍检查旧 execution_result；空 differences 还可使总循环返回未执行的新候选。只借鉴错误分层，每次修改必须按候选 SHA 重新执行和绑定当前输出，不沿用旧检查或模型自报等价。
其 reference_answer 由同页 GenerateAnswerNode 生成，不是独立业务真值；are_content_equal 对所有字符串小写/去空白，不能用于大小写敏感的 ID、URL 路径、签名或逐集关联。仍用独立页及原始字段合同验证，不以模型互相认可补节点通过。
其 transform_schema 是丢弃 required/minItems 等约束的提示摘要，execute 还将它转为字符串，而修正状态模型要求 dict；提示摘要与可执行验证 schema 分开。create_sandbox_and_execute 使用完整 __builtins__ 的宿主 exec，不是本项目隔离容器，不移植；ScriptCreatorGraph 仅返回脚本文本也不等于执行验收。

### browser-evidence-only
只有静态 HTTP 证据不足且页面确需渲染或交互时，才用已批准的浏览器组件补证。
优先定位 xhr/fetch 请求与响应，其次快照/DOM；固定页面和请求来源，避免把模型猜测 URL 当真实返回。
Cookie、Authorization、Set-Cookie、签名 URL 和存储状态不进入公开诊断或模型上下文。
清洗前保留获准范围内的原始响应与身份：llm-scraper 的 html 模式会就地删除 script、iframe、img、video 等节点。
text 模式会从 CDN 加载 Readability；因此不能把预处理假定为纯本地、无副作用的压缩。
按失败字段裁剪必要结构，先脱敏再送模型，避免播放器配置、封面或 iframe 来源因清洗丢失。
Firecrawl 的 simplifyHtml 会 flattenWrappers、合并重复行并裁剪地址，uniqueSelector 只验证当前原文档唯一；压缩页不承担条目计数、结构/语义一致或原始 URL 证据。truncateUrl 是压缩而非脱敏，短查询串会原样保留；沿原有可信脱敏和原页面逐条验收，不移植其全页回退或裁剪地址。
参考 Chrome DevTools MCP 的 network.ts 与 llm-scraper 的 preprocess.ts/cleanup.ts；不新增组件或远程脚本。
DevTools NetworkFormatter 内联文本限长发生在完整 buffer 读取之后，不是传输字节上限；正文超时只结束等待。
redactNetworkHeaders 仅控制头处理，URL 与请求/响应正文仍需本项目先脱敏；保存文件路径也不构成内容已脱敏证明。
NetworkCollector 按导航保留有限请求，SPA 不轮换导航；必须绑定当前页和请求身份，历史条目与未捕获不能当本次实际输出。
spider-flow 的调试链把 nodeId/event/key/value 发到窗口，输出节点还可包含完整响应与变量；借鉴节点关联，不复制全变量、正文或凭据到诊断窗口。
其 finish 是流程调用返回后的事件，节点异常可被保存为 ex 而继续流转，WebSocket 写入失败也会被吞掉；窗口有输出、无错误或显示结束均不证明冻结节点通过。
Crawlab 的 RunnerV2 将进程正常退出转为 finished，ServiceV2 在异常分支后也可写 finished 日志；任务状态、结果数量、节点回执与日志发送分别核对，不按结束文案判成功。
其日志读取在 ReadString 返回错误时先退出，可能丢弃末尾无换行数据；ReaderSize 不是单条或总字节上限，窗口缺日志不证明节点没有执行。
Playwright MCP 的包装入口委托 playwright-core；按锁定依赖定位实际 owner，网络请求保留稳定 index、当前导航边界及明确 part，详情默认不含正文，不能靠工具名推断返回数据。
默认 static=false 隐藏成功的非 fetch/xhr 请求，含媒体、图片及重定向；必要时按获准范围查看 static=true，all 历史和当前导航分开，不能猜 index 或用历史数据补本次缺失。
其文本输出可替换配置中的秘密值/URL 参数，但二进制保存不走文本脱敏；outputMaxSize 是写出后的目录回收，不限制正文下载且本次受保护文件可超预算，沿现有有界出站与模型输入边界取证。
browsers-benchmark 的 has_proxy 由引擎名包含 proxy 推断，no_proxy 也会命中；诊断窗口记录脱敏后的实际有效设置和版本，不能按名称、README 分数或缺挑战标记推断环境身份与业务恢复。
Skyvern 的常规修复视图不包含原始代码/提示/失败正文，可靠性窗口从正式终态运行取样并排除调试/子运行；借鉴来源分层，独立保留未测和输出风险，不把调试样本或汇总颜色当正式验收。
SkyCaiji 将 matchAction 的内容/网址匹配与 get_fields 的完整取值分开；借鉴按输入来源、提取、字段处理、公共处理、输出拆解，不把选择器单测当装载成功。
其页面调试还区分源码、内容标签、分页、关联页；在现有节点窗口保留脱敏的上游引用、条目身份、变换前后计数和筛除原因，不复制完整 HTML 或凭据。
collectSingle 可调用 collect_fields_vals，并非纯只读取值；前端 abort_test 只中止 AJAX 等待。调试权限与副作用、真实执行终止仍由原 owner 确认，不照搬可执行插件函数或远程 API 处理器。
nodriver 的 transactions 仅保留最近 25 条 CDP 命令；普通网络事件以 tx_id=None 独立分发，last_transaction 不是实际 HTTP 请求身份。诊断沿目标/session、requestId、loaderId 和重定向 hop 关联当前输入输出，不按最近命令或相同 URL 猜归属。
pydoll 的 network_logs 是 10000 条 deque，记录 requestWillBeSent 名称包含匹配，ExtraInfo 也可进入；它不是响应成功清单，条数限制也不是字节限制。其无容量上限的事件队列由单 worker 顺序等待回调；缺日志可能是淘汰、积压或捕获中断，须保留缺测而非归因站点。
Crawlee 的自适应链在 HTTP 异常后默认改跑浏览器，shouldPropagateError 可让明确故障保持原分类；单次异常不证明需要渲染，检查器拒绝后也只有浏览器成功才记录 clientOnly。
借鉴按相同输入比较实际条目及下游依赖，证据不足保留 inconclusive；不能仅凭非空、两个空列表相等或页面模式名升级组件。只在已获准且确需渲染时补证，鉴权/出站/缺组件仍回到原 owner。
Playwright trace 的动作 callId 与 HAR 的 _apiRequestRef 不是同一身份，后者指向 APIRequestContext；浏览器网络资源还可跨 chunk 保留。窗口沿本次 job、源/画像/运行器、动作和实际请求绑定，不按同 URL、相近时间或分组名称认定本次业务输出。
HarTracer 的 omitCookies/slimMode 不删除原始 headers，visitTraceEvent 也不是秘密值过滤器；TR-003 仅核对无 snapshots 的 context-options 场景。头、查询、form、正文及附件都须在本项目可信 owner 做白名单脱敏，不能把 HAR/trace 原件直接送模型。
Scrapy ItemLoader 的真实处理在 itemloaders：selector→内联处理→字段输入处理→累积→输出处理；_track_rule 在字段处理前计命中。节点窗口按阶段保留条目引用、计数与筛除原因，命中不等于最终有效行；其异常包含 value repr，不把原始异常文本直接送窗口或模型。
spider-flow 的 editor.js 将 output 按 nodeId 分表，将 debug 按 nodeId/event/key 定位参数；借鉴“节点→字段→值形态”的可追踪窗口，但 RequestExecutor.pause 位于 request.execute 之前，表达式值不是实发请求或响应证据。
同一调试窗口的重新开始沿用 tableMap/行号，WebSocketEvent 只有 eventType/timestamp/message，没有运行批次身份；本项目窗口继续绑定当前任务与源码/画像/运行器，不能把旧行、迟到 finish 或关闭窗口当本次覆盖。FastJsonSerializer 将 Long/BigInteger/BigDecimal 写为字符串，显示类型也不能反推原业务类型。
Stagehand 的快照按 frame 所属 CDP session 建索引，DOM.getFrameOwner 则向父 session 查询；同进程 iframe 找不到子文档时跳过，不能用父页面或非空 combinedTree 证明目标已读取。节点诊断绑定页面/frame/session 与本次任务，目标缺失保留缺测，不直接归因为解析器损坏。
其 focusLocator 失败可退回整页，ignoreLocators 解析失败可跳过，二者不是保密或外发白名单；combinedTree 与 perFrame/URL 映射的覆盖也可能不同。沿原可信 owner 限定获准输入并先脱敏，不把范围回退作为扩大模型输入的授权。
其 combinedUrlMap 来自 AX 节点的 url 属性，不是媒体网络清单；闭合 Shadow Root 的 CDP 主世界辅助查询与 extract 快照是不同分支，且不遍历 iframe 文档、跳过 user-agent roots，释放对象组失败也可被忽略。可访问结构不等于已获媒体、登录权限或完成清理，仍核对真实 HTTP/代理回调与字节。

### learned-rules-holdout
AutoScraper 可从离线 HTML 与示例值建立规则，再序列化 stack_list 重放。
训练页面与验收页面分开；至少核对另一页、缺字段页及列表项关联，防止标题、ID、封面错配。
按 alias 汇总的列不能直接 zip；缺一个图片节点会让后一项封面错配，必须在同一条目容器内核对字段。
keep_blank 仅能保留已命中节点的空属性，不能补回不存在的节点；相同标题不能作为行身份，unique 去重会丢重复标题。
审阅快照把缺失 class/style 写成空串匹配，本机 BeautifulSoup 4.15.0 对缺省属性的页面已复现零命中。
固定依赖和学习选项，区分选择器变化与依赖行为；规则只作候选，不能用换依赖或填充伪数据绕过当前合同。
参考 auto_scraper.py、utils.py 及项目深入学习记录；离线五场景不是 AT/T4 真实业务通过证据。
Crawl4AI 的 baseSelector 在每个条目内抽字段可避免列错配；字段异常返回 default 或省略，仍须逐行校验必需字段。
其 expected_fields 严格模式只检查字段在某条记录中出现过；缺图行与默认图片不能据此标为真实封面通过。
Firecrawl 的键名归一化可能覆盖同名目标，字符串清洗会删除私用区/零宽字符，对象去重依赖 JSON 属性顺序；opaque ID/地址不作显示文本清洗，保留转换前后身份。
MediaCrawler 的评论主循环可在 next 不变时重复累计条目直至数量上限；数量达标不证明翻页完整，独立核对游标推进、条目身份和重复页，不用伪造页码修复。
SkyCaiji 的 set_field_val 先执行字段 process、再执行 common_process；exclude_cont_urls 可筛除已经提取的行，空输出要区分未命中、处理后为空及被规则排除。
其 get_field_vals 以 first_loop_field 的页内索引合并其他字段，标量可复用到每行；索引相等不证明同一条目，缺字段与标量复用必须核对真实 ID 和容器。
分页字段可按 delimiter 拼接，诊断需保留源页与原始条目顺序；不把拼接结果或标签缺省值当当前 AT 字段合同的观察值。
请求去重与条目去重是两回事：Crawlee 默认 uniqueKey 仅含归一化 URL，不包含 POST 方法/正文，分页 pg 改变仍可能被折叠；useExtendedUniqueKey 才加入方法与正文摘要，但仍不含 headers/会话。
Scrapy 默认 fingerprint 包含方法/正文但忽略 headers/fragment；其 request_seen 在入队前记录，不代表响应或业务已成功。排查缺页须核对实际实参与去重决策，不能靠随机键或 alwaysEnqueue 绕过预算和失败验收。
AutoScraper 的 get_result_exact 实为学习路径的索引重放；索引超出当前同级节点数量时用 min 取最后一项。独立样本须包含删行、插行和重排，并按真实 ID 比较，不能把 exact 命名当身份保证。
其规则 hash 在 alias 赋值前生成，unique_stack_list 又只按 hash 保留首条；同路径的两个 alias 可被合并。输出应逐项核对预期字段与条目绑定，不能靠别名数量或规则数量判定学习完整。
build(update=False) 在匹配前清空规则，未命中可留下空集合；update=True 只追加去重，不验证或淘汰旧规则。学习只在候选快照中发生，现有有效源码不因一次学习返回而被覆盖。
_get_soup 用 html 的真值选择离线分支，html="" 会转入网络请求。空样本先分类并保持获准离线边界，不把隐式联网得到的内容当原样本复放。
Scrapy/itemloaders 的 nested_xpath/nested_css 共享父 item 和 _values，不是列表逐行隔离；每张卡片建立独立记录，只在同一记录内部缩小作用域。XPath 用相对 .//，类名用 CSS token 匹配，混合标签标题从完整元素取文本，避免跨卡取值或只取首个文本节点。
MapCompose 会过滤 None 并展开列表，TakeFirst 只跳过 None/空串而保留空白、0、False；二者都不验证业务。按字段约定清洗标题，opaque ID/地址保留原值；记录处理前后数量，缺图、插行、重排及重复标题样本仍按原 ID 逐条核对，不能靠拼列、取首项或填默认值修复。
Crawl4AI 的 baseFields 随后会被同名 fields 覆盖；nested 取首个子容器，list 只走单字段处理，nested_list 才递归。借鉴逐容器抽取但明确基底 ID、字段覆盖及子列表来源；其 XPath 简化 CSS 转换不是完整 CSS 引擎，换策略前用同一留出页核对实际匹配，不按类名/API 名称假定等价。

### snapshot-cache-boundary
抽取输入形态与缓存身份必须一致；DOM 缓存不能证明截图内容或新源码的输出。
修改源码、schema、画像或运行器后，按现有指纹规则重新验收相关节点，保留冷/热运行差异。
Stagehand 的截图抽取直接执行，不使用仅含 DOM 状态的缓存；Crawl4AI 的抓取缓存和抽取策略也分层。
这里吸收缓存边界经验，不引入其云缓存、数据库或新调度系统。
Crawl4AI 快照按 URL 读取已抽取结果，缓存新鲜度检查验证页面而非 schema；错误时还能 hit_fallback，不能当新鲜运行证据。
Stagehand 扩展层抽取缓存包含 schema，命中时只作 JSON 值检查；公开 SDK 返回前仍执行 resolvedSchema.parse(response.data)。借鉴消费端按调用者原 schema 再验返回值，形态通过仍不证明真实条目、媒体业务或当前源码身份。
Colly 的磁盘 GET 缓存按 URL 建键，不包括 Cookie、Authorization 或 schema；有会话差异时不能把相同 URL 当相同抽取输入。
Firecrawl 的 extractor 缓存包含模型/URL/schema/prompt 和可选 scope，不包含本次 HTML；缓存的是代码，仍会对本次 HTML 执行，不能混称旧业务结果缓存。
该实现会在运行校验前保存新代码，缓存存在不等于候选通过；其自定义请求 scope 由调用方提供，不能仅按 URL 或团队名认定不同授权上下文等价。
Scrapy 的请求指纹和 Crawlee 的 uniqueKey 是调度去重身份，不是源码/配置/schema/业务回执身份；默认忽略的会话字段不能公开落盘，沿现有受保护作用域核验，不把去重命中或 handled 标记升级为当前结果缓存通过。
Camoufox 的包装包、实际浏览器二进制和协议接口是独立身份；默认 launch_path 可下载，get_active_path 还能写活动配置。缺组件只记缺失，不在探测中隐式安装、更新或沿用另一个二进制的证据。
其 install_versioned 在解包前调用 verify_sha256，但没有发布摘要时会跳过；配对 pin 也可缺失或被显式选择覆盖。借鉴核对实际路径、版本与完整摘要，不把包名、版本文案或缓存目录短 SHA 当已验真组件。
Crawlee RenderingTypePredictor 的 urlComponents 只取 hostname/path 并按 label 分组，不含 query、scheme、port、方法、正文、会话或源码/schema；同路径分类、分页和播放动作不能共用业务通过证据。
预测状态只用于选择取证方式，不是装载回执缓存；其缺训练数据时先跑浏览器的默认策略不直接照搬，本项目仍按真实失败和现有授权决定是否需要该组件。
AutoScraper 的 save/load 只保存和读取 stack_list；stack_id 仅为规则 hash 的前八位，读取时没有重新验证 hash、schema 或依赖身份。序列化往返一致不证明本次页面或源码可用。
只借鉴可复放的规则数据，沿已有源码/画像/运行器指纹另绑定学习样本、解析依赖和验收来源；不把规则短 ID、文件存在或同一训练页 save/load 测试升级为当前业务回执。
Scrapy 的媒体管线按请求指纹共享在途任务并缓存成功或 Failure；后续 errback 返回恢复值可成为成功结果，却没有重新下载。重复失败先核对缓存来源和实际请求次数，不当作新鲜网络证据或自动触发改源码。
FilesPipeline 的 uptodate 来自存储时间与过期配置，cached 来自响应 flags；ImagesPipeline 默认路径按 URL 摘要，不是正文身份。只借鉴来源分层，沿原有源码/配置/运行器及原字节 SHA 验收，不把路径或缓存状态当本次资源通过。

### bounded-retry-at-owner
仅由拥有请求的组件对已分类的瞬时故障执行有界重试，并记录原因与耗尽状态。
鉴权、挑战页、出站拒绝、缺组件或结构合同失败不能用无界重试“修复”。
Scrapy 维护 retry_times/max_retry_times/dont_retry；Crawlee 将 SessionError 计入请求重试，NonRetryableError 立即终止。
Crawlee 的 RetryRequestError 可越过次数判断，429 延后也不计普通重试；不能照搬为本项目无界循环。
会话失效、请求失败和任务终止分开记录；不增加重试层，原有请求/字节/时限边界与取消清理仍必须成立。
ScrapeGraphAI 的 invoke_with_timeout 在链返回后计时，不能保证按时取消；分块并行和合并也不能各自重置总任务预算。
Stagehand Progress 共享截止并结束等待、清理晚到结果，但不自动停止底层任务；SDK rpcResponseTimeoutMs 对 act/extract/observe 默认不设响应截止，不能当本项目总任务预算。缓存请求另传 remainingTimeoutMs，所读测试核对原 request_id 的 browserbase.cancel；普通 RPC 等待超时不能推定已发该取消，仍由真实 owner 确认终止和清理。
curl_cffi iter_content 的 chunk_size 被忽略；限量由实际累计字节控制，不能只拿到头或一个 chunk 就报告完成。
其 close 等待 transfer Future，aclose 只等待异步任务；结束或抛错不证明资源已释放，沿已有宿主回执确认清理。
Colly 的限流槽等待与结束后 delay 发生在 HTTP Client.Do 之外，客户端请求超时不是完整任务截止；Retry 还要求非空正文可 seek。
Firecrawl 每次运行新建 askLlm 计数器，运行重试会重置局部预算；静态生成重试、选择器修复和内部模型调用仍须共享本项目已有任务总预算，不新增重试器。
MediaCrawler 的日志列表限长不限制未设容量的推送队列，stop 强杀后未等待退出确认即设 idle；有界窗口、发送 kill 或结束读日志均不证明子进程/容器已清理，仍核对宿主回执。
Crawlab Cancel 另查根 PID 消失，但 CleanUp 是空实现；所读 killProcessRecursive 正常枚举子进程后不终止当前进程，不把函数名或根 PID 检查当整个容器清理证明。
DrissionPage 的重试循环逐次使用 timeout 并另等 interval，不是总任务截止；ChromiumTab.close 默认不关闭 HTTP Session，页面关闭与请求/响应释放分别确认。
browser-use 的 Agent.run(timeout) 只包模型/单元循环，不含浏览器启动和关闭；REPL 超时会尝试关闭/重启但提示动作可能已经发生，重试前检查副作用，不照搬为 AT 总任务截止或清理回执。
Playwright MCP 在 DOMContentLoaded 后限时等待 load 且可吞掉等待错误；导航/工具返回不证明异步媒体就绪，结束等待、工具错误和真实底层清理仍分别核验。
pydoll 的 expect_response 在动作块之后分别等待响应与完成，每次都用完整 timeout，取正文还走独立命令；不是共享总截止，超时清除回调也不取消 HTTP。沿原任务预算判断晚到响应，不把命令超时、窗口结束或回调移除当业务终止。
nodriver 的 stop 发终止信号后没有等待进程退出，aclose 只等待监听任务而未追踪 create_task 产生的异步回调；pydoll 的 stop_process 有 terminate/wait/kill/wait，但仅面向持有的进程对象。借鉴明确失败 pending、等待监听退出和回读 exit code，实际容器/后代进程清理仍由本项目宿主确认。
Camoufox 的 AsyncCamoufox 在启动失败时释放 Playwright 会话，在 browser.close 抛错后仍用 finally 释放驱动；async_attach_vd 再为显示资源建立独立 finally。借鉴逐层释放与保留原失败，不把一个 close 返回等同全部资源退出。
其启动清理测试只用 FakeDisplay.killed 断言，不证明真实进程或端口释放；原 AT 鉴权实验分别等待 HttpClient 终止、Tomcat destroy、端口拒绝连接和持久层关闭。清理证据只属于该实验，不覆盖产品 R09/S07 或尚未实现的隔离桥接。
Crawlee 自适应两次尝试分别建立导航/handler 超时，不是本项目共享任务截止；比较重放、回退与模型调用仍消耗原有总预算。
其 StorageTransaction/MutationJournal 只管理受管存储和经过代理的状态修改，Map/Set、外部请求与绕开代理的副作用不保证回滚；借鉴比较前隔离候选、丢弃未采纳结果，不靠 rollback 名称重复执行有副作用动作。
Stagehand 的 replayCachedActions 逐项执行，后项失败抛错；withCache 将 replay_failed 转为整条 execute，代码中没有撤销已成功前项的分支，不能把自动重放当安全恢复。
缓存失败或未知动作结果先核对已提交副作用和当前任务身份；本项目保留 start/take 丢包不重发、取消栅栏与独立清理，不照搬整条动作重试，也不新增重试器。
Crawlee checkReadiness 区分 ready/waiting/finished，在途请求或后台入队使其等待；后台入队失败后也能进入 finished，未入队项不会因此成功。借鉴执行状态与业务覆盖分开，继续按冻结必需节点及失败回执判定，不能用空队列或 handled 数补绿。
其内存队列用无过期的在途集合，磁盘适配器把锁/持久化委托原生组件；所读恢复测试仅在正常 teardown 后新建存储实例，不是强杀或宿主重启证明。本项目继续由既有 broker 对遗留 RUNNING 阻断并按精确归属恢复，不用租约到期或重新入队证明旧容器已清理。
Crawl4AI 页面会话按 last_used 判断过期，但只 create_task(kill_session) 而不等待；粘性代理 TTL 按 created_at 判断，release_session 仅删分配记录，auto_release 只在所读 arun_many 的 dispatcher 收尾分支调用。过期、取消排队、代理解绑与页面/容器退出分别核验，不把异步清理已安排当可立即复用的证明。
Scrapy AutoThrottle 按 download_slot 的 latency/target_concurrency 调节 delay，非 200 的快速响应不降低 delay，但 200 登录页仍可影响节奏；它默认关闭，Slot.download_delay 的 jitter 还能低于基础 delay。只借鉴分类后调节，不能用此设置替代宿主硬性最小间隔、业务合同或总截止。
Crawlee 将 crawlDelayUntil 与 backoffUntil 分开，冷却时 fetchNextRequest 返回 null、checkReadiness 返回 waiting；并发 429 在已有退避期间不再累加指数，也不合并较晚更长的 waitMs。其 maxDelaySecs 还会截短 Retry-After，不能照搬提前访问；所需等待超出本项目剩余预算时保持受阻，不重置预算或修改源码换成功。
其 RequestThrottledError 延后分支不消耗普通重试数，maxDomainStallSecs/keepAlive 与任务总截止不是同一合同；stalled 或队列保留也不是解析器损坏或修复完成。沿原 owner 先区分限流、鉴权、传输和结构失败，仅在获准范围处理，取消和实际退出仍独立核验。
Playwright APIResponse.dispose 只删除该正文和日志引用；GlobalAPIRequestContext.dispose 经 _disposeImpl 关闭请求 agent 并通知在途请求，浏览器附属 request.dispose 则只 flush/清正文，其底层 _disposeImpl 由 BrowserContext.Close 触发。清正文、禁止后续 SDK 调用与底层请求/socket 退出分开核验，不能以同名 dispose 或 Map 已空代替宿主清理。
spider-flow 的 stop 由 WebSocket 入口设置 running=false，再 notifyAll 唤醒调试等待；所读 RequestExecutor 的发送/重试循环不检查 running，pause 返回后也没有取消栅栏。只借鉴“请求停止→实际执行收尾→宿主清理”的分层，不能复制为本项目停止合同或在暂停点持有单槽无限等待。
Scrapy 的 Crawler.stop_async 先清 crawling，打开中/关闭中或重复 graceful 停止可立即返回；AsyncCrawlerRunner.stop 与 join 用 asyncio.wait 等结束，不自动传播子异常，取消 stop 的等待也不自动取消已创建的子停止任务。停止返回、任务结束、失败回收与资源清理分别核验，不复制其标志或全局取消整个宿主事件循环。
其 CrawlerProcess 通过 stop→join 再结束 reactor；本项目正式父验收也须贯穿 loader 与后续 HTTP 子动作，父 broker 行 COMPLETED 不能代替整体验收终态。沿原任务身份对已登记子 job 传递取消、等待独立清理与 journal 提交，再投影结果；单槽、丢 ACK 阻断和晚到成功拒绝保持，不引入新取消框架。
Playwright 的 page/context.unrouteAll 先移除处理器，只有 behavior=wait 才等待所记录的在途回调；ignoreErrors 不等待而只隐藏后续异常，默认注销也不等待。注销名单、等待结束、网络终止与业务成功分开；不能将这种自动化清理策略当本项目取消或授权合同。
RouteHandler.stop(wait) 还排除 _didThrow 的激活项，正常 page/context.close 不等待任意用户路由回调；RO-196 和 RO-197/199 分别允许回调永不结束、关页后 continue/fulfill 不报错。方法正常返回或未报错不能替代当前子 job、容器退出与 journal 提交证明。
LongStandingScope 的 race/safeRace 只竞争 Promise，close 不取消底层工作，safeRace 可返回默认值；已关闭时传入已 fulfilled 的 Promise 也可能返回该值，且实参操作早已启动。沿既有 owner 在启动准入和结果晋级处复核取消状态，正常收尾不改写为整体验收取消，不靠关闭标志、吞错或 Promise 竞争补绿。
Crawlee 的 closePage 可在底层关闭悬挂后减少 activePages、删除页表并发出 PAGE_CLOSED；registerPageClosed 只异步启动 teardown，BrowserController.close 可吞关闭错误并安排稍后 kill。计数归零、事件或 close 返回不是退出证明，不用它们解除本项目单槽、独立清理或 journal 提交屏障。
其 BrowserCrawler.teardown 只对自有池调用 releaseAllBrowsers，保留池供下一 run 使用；destroy 才销毁自有池，外部注入池仍由调用者负责。借鉴逐资源明确拥有者与正常收尾/最终销毁的区别，不新增池或后台重试，不把正常 loader 收尾误作整体验收取消。

### opaque-data-scope
遇到混淆或密文先保留原始字节和转换身份，只分析已获授权的样本及匹配加载链。
webcrack 的 deobfuscate 可调用 sandbox 执行解码表达式，必须在隔离环境评估，不能当纯 AST 格式化运行。
fontTools 的 RecordingPen 只记录轮廓操作；字形坐标不能未经归一化和已知样本校验就认作数字映射。
webcrack 的 ERR_MODULE_NOT_FOUND/原生版本不匹配可转为空解码结果，后续仍可能删除解码定义；进度或 changes 不能证明恢复完整。
必须核对解码结果数量、字符串类型及剩余调用依赖；webcrack:decode_error 或缺组件保留失败，不沿用原签名或热替换有效原件。
其 createNodeSandbox 的正常 release/dispose 在 eval 之后且不在 finally 中；异常需真实 owner 回执，不能凭函数名认定隔离或资源清理成立。
bundle 模块路径可被推断并改名，所读测试还跳过目录 index 场景；解包输出不是原始源文件身份，也不是 AT secspider 解密或验签。
fontTools 的 getBestCmap 返回 codepoint 到 glyph name，不提供数字语义；不能按名称猜值，也不能沿用另一个字体 SHA 的映射。
RecordingPen.addComponent 只记引用，变量组件还有 location/reset 标记；轮廓比较前须核对展开、轴位置和明确坐标变换。
replayRecording 通过 getattr 调用记录中的方法，不是任意输入校验器；只回放本次已知操作，未知字形和歧义映射继续未解决。
javascript-deobfuscator 的 ArrayUnpacker 只替换字面量索引，replaceCount>0 即可删原数组；动态引用、赋值及未替换依赖须单独核对，AST 输出不保证语义等价。
其 StringDecoder 按结构识别后先删 XOR 函数，未证明所有调用已恢复；String.fromCharCode 与 undefined 常量折叠也未核对局部绑定，需保留调用与作用域残留检查。
只借鉴分阶段静态候选与依赖核对，不把有限字符串变换当 AES/AT secspider 解密，不在宿主运行未知脚本；输出 SHA 与原件身份分开，仍交原装载器验收。
不猜 AES key、不绕过 DRM 或访问限制，也不把网站响应解码与 AT secspider 包恢复混为一谈。

## 采用边界

- 本轮复用现有 KnowledgeService、ProbeService、ModelClient 及宿主装载器，不新增爬虫平台。
- 源码研究不安装第三方包、全局技能或 MCP，不调用真实模型或启动浏览器；同期独立 NAS 实验另列产品回执，未发布。
- 采用的是自行归纳的方法和本项目最小代码修正；上游源码仅在私有研究目录保存，带原始许可证。
- 不采纳宣传性保证：任意站点自愈、改版不失效、反检测百分之百、无成本或高 QPS 均不是验收结果。
- 成功条件保持不变：当前源码、画像和运行器下，全部冻结必需节点取得可信装载器回执。
