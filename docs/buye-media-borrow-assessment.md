# buye-media 借鉴评估

- 评估日期：2026-11-01
- 对象：`luckytjq/buye-media:0.7.7`（digest `sha256:5929…8e70`），闭源 Node.js monorepo，功能域与本项目高度重叠（网盘聚合/TG/T4/直播/订阅下发/追剧）
- ⚠️ **法律前提：闭源无许可证，禁止拷贝代码**。本文所有「借鉴」均指设计思路、模块契约、协议事实（接口端点/算法参数/坑位），实现须自研或另找开源上游。这与 ani-rss / MoviePilot / t4-sidecar 等开源评估不同。
- 分析物料：/tmp/buye-analysis/rootfs（docker export 全量静态分析）

## A 级（值得借鉴）

### A1. IPTV 官方直播采集：模块契约 + 协议事实
buye 的 `直播采集/内核` 是全镜像工程质量最高的部分（40+ extractors：省级卫视/央视频/凤凰/咪咕/大爱/GOODTV/北斗/国际台），对本项目的价值分两层：

**协议事实库**（reverse-engineered 事实，不受版权约束，可直接参考做 Java 实现）：
- 央视频 yangshipin：ckey = TEA（key `59b2f7cf…`）+ guard 二次 TEA + XOR 表，平台号 4330403、APP_VERSION `V8.22.1035.3031`；带 timeshift 回看（catchup-source 拼 playbackbegin/end）
- 咪咕 migu：账号驱动画质（userId/token/rateType/enableHDR/enableH265/enableClientDispatch），频道主键=裸数字 pID；同频道多分组重复需跨组去重（实测 645 条目→593 真实频道）
- 深圳sztv 用 hmac-sha512 签名、江西jxntv 用 md5 Authorization——各省台鉴权方案互不相同，逐台摸
- EPG/回看上游：`akiralereal/iPTV`（iFansClub）的 playback.xml，`catchup="append"` 回看参数模板可直接用

**工程模式**（思路借鉴）：
- **模块契约**：`configSchema` 字段描述同时驱动「后台渲染表单 + 后端校验」；`catalogVersion`（内置频道表变更→缓存版本不一致→主动重抓成功才落盘）；refresh 有 min/max/configurable 边界。对应本项目的 live/ 平台管理 tab。
- **deferredRef 延迟解析**：播放列表写盘只落 `${replace}/<ref>` 占位，播放请求到达才 resolve——IPTV 地址时效短的天然解法，本项目的直播聚合可参考。
- **upstreamHeaders 函数形态 + Cookie Domain/Path 域限定**：代理向官方 CDN 回源时，凭据按目标 URL 的 Cookie 域/路径限定发送，「不能把一个 CDN 的凭据泄漏给另一个 CDN」。**这条直接适用于本项目 ProxyService 的回源头管理**，是最小成本可先落地的一条。
- 每频道 HLS 路由标记（proxy/relay）+ `relayProxyCompatible` 白名单（拒绝服务端分片的 CDN 不强升全代理）——与斗鱼中继 expire=300 续流经验同族。

落地路径：Java 侧挑高价值平台移植（yangshipin/migu 先行），或走 T4 式 Node 侧车。法务灰色程度与现有虎牙/斗鱼聚合同级。

### A2. T4 源 TMDB 详情增强（buye「站源增强」）
buye 的站源增强实为**服务端订阅链路增强**，不是纯客户端行为：
- EnhancementStore 按（订阅,源key）存增强配置（`enh_<sha256>` 引用）
- 对订阅下发的 type4（T4）源，detail 响应经 `createDetailEnhancer(tmdbRequest)` 套 TMDB 海报/简介/集名/相关推荐
- 走用户权限过滤后的正式订阅快照（绝不给非管理员看全量目录）、预览有 12s 超时守卫、8MB 响应上限
- 客户端 jar 配合：海报先显旧值再淡入、剧集独立加载避免换线重建闪跳（纯 UX 手法，spring.jar 可抄思路）

**对本项目**：`/t4/{t4token}` 服务端模式是天然挂载点——对第三方 T4 源的 detail 响应做 TMDB 增强（本仓库已有豆瓣/TMDB 数据管线），与凭证屏障/t4_proxy_mode 正交，是 T4 二期之后投入产出比最高的增量。注意坑：增强须按用户 token 过滤源目录（buye 同样守卫）、上游源响应要设超时与体积上限。

### A3. （A-）虚拟 Emby 协议面清单
`virtual-emby.js`(1936行)+`tracking-emby.js`(1179行) 把追剧库暴露成完整 Emby 服务器：AuthenticateByName、Users/Public、Items/Counts、Sessions/Capabilities、DisplayPreferences、Branding、System/Info 等。按本项目「TvBox 优先」定规列为留触发（见 B1），但**其端点清单与字段形状是现成的协议事实参考**——若将来做「订阅库 → Infuse/Emby 客户端」接入面，逆向成本已被 buye 摊平。

## B 级（留触发）

| 项 | 内容 | 触发条件 |
|---|---|---|
| B1 虚拟 Emby | 追剧库暴露为 Emby 协议，原生 Emby/Infuse 客户端直接消费 | Emby 客户端接入诉求出现时（参考 A3 清单） |
| B2 AI 网关 buye-aicht | GLM/Gemini **Web 会话 Cookie** 逆向包装成 OpenAI/Claude/Gemini 三套兼容 API；工具调用折叠成中间表示+提示词注入 | AI 搜索/推荐/客服诉求；Web 会话脆弱且有封号风险，优先官方 API |
| B3 小爱音箱点播 | miservice + 18 型号 TTS 指令表（OH2/LX06/X10A…每型不同 play 指令位） | 有音箱硬件点播诉求时；映射表是纯事实可直接用 |
| B4 用户自定义 cron 脚本 | 管理员可写 JS 脚本定时跑：编辑/预览下次执行/日志/停止 | 高级用户自动化诉求；Spring 侧须沙箱（js/groovy），RCE-by-design 要 ADMIN 门槛 |
| B5 配置快照备份 | 虚拟路径映射 + 勾选键 + 时间戳快照 + prune 上限 | 本仓库若无产品级配置备份则小而美可做 |
| B6 雷达推荐层 | 豆瓣 lane 化榜单（taste/k drama…）+ AI hint 缓存 6h/超时 7s | 依赖 B2；豆瓣部分本仓库已覆盖（recommend tags 多词） |
| B7 音乐聚合 | music-core + flist + YT Music（youtubei.js+bgutils 过 botguard） | 音乐面立项时 |

## 不搬（已覆盖或更强）

- **T4 契约/代理**：buye 的 SpiderAdapter（search/detail→Card/links）远简于本仓库行协议+凭证屏障+proxy_mode
- **追剧 tracking**：本仓库磁力兜底十期+离线归属闸门+配额月窗口远超其 tracking-* 全家桶
- **网盘适配**：30+ adapters 对其 cloud-drive-core
- **玩偶探测**：已内置 60min 探测+停放页判定；buye 的裸 IP 清单（38.76.197.172 等）与已知同源
- **多用户**：本仓库五轮加固 vs 其明文密码+默认 admin/admin
- **CatPaw 运行时桥**：与 T4 Python 运行时+/lp loopback 反代同构，本仓库已自研等价物
- 订阅 token 口径、WebDAV、TG bot、升级热更（脚本升级丢 py 已修）

## 安全注记（沿用镜像分析结论）

- 静态扫描无恶意行为（无挖矿/无遥测/无后门/无 setuid；作者域名仅用于旧订阅快照改写）
- 风险集中在：默认 admin/admin、明文密码、闭源不可持续审计、每日全量推送无法锁供应链
- 本评估仅审了单一 digest，引用结论时注意时效
