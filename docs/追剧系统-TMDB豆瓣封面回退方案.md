# 追剧系统 TMDB/豆瓣封面回退方案

日期：2026-08-29

## 基线与范围

- 上游仓库：`power721/alist-tvbox`
- 上游最新标签：`1.60.1`
- 上游提交：`f58d4403d52a50596eb163310700fcbc98867755`
- 本地候选分支：`codex/media-subscription-image-fallback-20260829`
- 本次只修改元数据桥接、追剧订阅快照、图片代理、Flyway 迁移和回归测试。
- 用户已授权发布；发布前置条件为三位独立审计员均无中高等级问题。

## 源码 owner 分析

原链路是：

`TmdbMetadataProvider` 生成 TMDB 封面 → `MediaSubscription` 保存单一 `cover_url` → `/images` 代理单图 → 客户端展示。

因此客户端不是正确修复点。客户端只拿到订阅 DTO；真正缺少的是跨源候选没有进入订阅快照，以及图片代理在主图失败时没有备用地址。修复位于服务端，不依赖 FongMi WebView 或特定空壳客户端。

## 实施方案

1. `MetadataDetails.externalCovers` 保存 `provider -> cover URL` 候选，`externalStatuses` 区分 `MATCH`、`NO_MATCH` 和 `RETRY`。TMDB、豆瓣和 `RatingBridge` 只填充通用跨源结果，不把站点域名或个性化条目写入自适应模块。
2. 元数据桥接在确认豆瓣身份时保存豆瓣 ID 和封面。多季条目的宽松同名候选只允许借用评分；只有候选明确标注相同季号时，才持久化当前季豆瓣 ID 和封面。
3. `MediaSubscription` 增加 `cover_fallback_url` 和 `cover_fallback_status`。状态独立使用 `PENDING`、`MATCH`、`NO_MATCH`、`RETRY`，不再复用 `meta_sync_time` 表达补图进度；主封面仍为 `cover_url`，豆瓣图只作为 TMDB 主图失败时的备选，不改变主元数据 provider。
4. `metaProvider` 与 `metaId` 按原子组合校验和更新。请求 DTO 记录字段是否真实出现，能够区分“字段缺失”和 JSON 显式 `null`；显式清空 provider 时同步清 ID、豆瓣绑定和封面快照。创建和重复创建也使用相同原子门禁，并可修复历史半绑定记录。
5. 创建订阅或真正切换元数据条目后，事务提交后异步预热；Web 列表、Web 详情、TVBox 列表和 TVBox 详情也会为 V37 存量记录触发非阻塞预热。三个异步元数据刷新入口都在外网请求前捕获身份快照，返回后使用职责分离的条件更新：`updateMetadataSnapshot` 只写元数据列，`updateCoverSnapshot` 只写豆瓣身份、主图、备用图和备用图状态，不再整实体 `save`。条件包含 `id/provider/metaId/season/expectedDoubanId`，因此并发换季、换条目或手工修改豆瓣 ID 时旧结果会被丢弃。预热键包含豆瓣 ID，手工绑定与自动匹配不同时按手工 ID 获取备用图。
6. `NO_MATCH` 表示已完成且没有可靠豆瓣匹配，后续读取不再重复访问元数据站点；`RETRY` 表示瞬时失败，不写入 6 小时负缓存，provider 内存缓存和持久快照中的 `RETRY` 结果均视为不可复用。此时允许刷新主封面，但保持备用图状态为 `RETRY`，后续巡检可以恢复重试。
7. 完整巡检使用代次门禁。用户换季、换条目、暂停或恢复订阅时递增 `checkRevision`，运行中的旧代次不能写回检查状态或集数计数；退出后只补跑一次当前身份，避免旧结果覆盖新订阅状态和并发变化触发重复巡检。
8. `/images?url=主图&fallback=备图` 使用独立 OkHttp 客户端：连接超时 3 秒、读取超时 7 秒、单次调用使用剩余预算、单图最多 10 MiB。匿名图片使用 8 路许可池，服务端登记图片使用独立 16 路许可池；匿名请求耗尽时不会阻塞登记图片。许可在 URI/DNS 解析前取得，DNS 运行于有界线程池并有 2 秒超时。DNS、重定向、主图、fallback、临时文件和客户端写出共享 15 秒绝对总预算；payload 交给 `RequestLease` 后由其统一接管，超时清理器或调度失败路径都会关闭输入流、删除临时文件并只释放一次许可。
9. 图片代理禁止自动重定向，最多手工跟随 3 次。匿名 `/images?url=` 只接受公网地址；`/images/{id}` 只接受服务端专门登记的公网或 LAN 图片记录，普通播放记录不能复用为图片访问能力。LAN 登记只允许 RFC1918 或 IPv6 ULA 类地址，公网登记在后续 DNS 重绑定到私网时仍会被拒绝。每一跳解析全部 DNS 地址并校验，随后以同一份解析结果建连。
10. JPEG/PNG/GIF/WebP 使用魔数识别；SVG 仅在声明为 `image/svg+xml` 且结构合法时接受，并附加 CSP sandbox。空体、HTML 伪装图片、超限响应、畸形响应头、不安全重定向或外连失败均转用 fallback。图片响应只返回受控响应头，不复制 `Set-Cookie`、`Clear-Site-Data`、`X-Accel-Redirect` 等上游头；只有豆瓣域名使用豆瓣 Referer，通用图床不再携带该站点身份。
11. `/images` 查询日志、原始签名 URL 和最终错误响应均已脱敏；原始 `/play-urls` 枚举接口改为仅管理员可访问。Flyway V37 增加 `cover_fallback_url` 和 `cover_fallback_status`，并只把“TMDB 且缺豆瓣绑定或缺回退图”的存量行标记为待补绑。Java migration 同时注册 JVM SPI 和 Native 配置。

## 为什么采用 ID 绑定

图片请求不应在客户端或每次列表读取时重新按标题模糊匹配。TMDB ID 与豆瓣 ID 在新增或切换元数据时绑定一次，随后只读取订阅快照，具有以下边界：

- 不依赖特定客户端、WebView 或空壳软件。
- 不把站点域名、CDN 或个性化条目堆进自适应模块。
- 标题相同但年份或季号不一致时不伪造豆瓣身份。
- 豆瓣图床本身也可能返回 403/418，因此回退是增加可用机会，不是绕过图床访问控制的保证。

## 网络探针边界

当前环境对 `media.themoviedb.org`、`image.tmdb.org` 和 `img9.doubanio.com` 做了只读 HEAD 探针；示例请求出现非 2xx（TMDB 示例路径为 404，豆瓣图床为 418）。这些结果只作为当前网络观察，不当作站点永久合同，也不用于绕过挑战。实现通过后端代理统一处理主图和回退图，并检查图片声明、结构和魔数，避免把 HTML 挑战页当图片返回。

## 审计收敛

第一轮三审分别发现数据一致性 `3 medium + 1 low`、图片安全 `2 high + 2 medium + 1 low`、API/合同 `4 medium + 1 low`。第二轮三审继续发现合同 `1 high + 3 medium + 2 low`、数据一致性 `3 high + 2 medium + 2 low`、图片安全 `1 high + 2 medium + 1 low`。已修复以下类别的问题：

- 多季宽松同名候选可能错误绑定当前季豆瓣身份。
- 瞬时失败可能进入负缓存或被持久快照长期复用。
- provider/ID 分步修改、JSON `null`、换季和并发预热可能留下不一致快照。
- 三个异步刷新入口在外网 I/O 后整实体保存，可能覆盖并发换季、换条目或手工豆瓣 ID。
- `season=null` 的条件更新无法命中，且预热在零行更新后可能立即自旋。
- 手工豆瓣 ID 与自动匹配 ID 的封面混存，以及强制刷新未穿透 `RatingBridge` 负缓存。
- 主图为空时未提升已有豆瓣备用图，导致 DTO/TVBox 仍然缺图。
- 图片代理的匿名内网 SSRF、DNS 重绑定、慢上游/慢下游资源耗尽、上游响应头注入和签名 URL 日志泄露。
- 图片代理在取得并发许可前执行 DNS、各阶段没有共享总预算，以及普通播放记录可被复用为私网图片能力。
- 畸形 `Location`、`Content-Type` 等上游响应未稳定进入 fallback。

第三轮三审发现的中高问题已经全部修复并完成定向复核：巡检代次阻止旧季/旧条目写回，元数据刷新不再擦除预热的豆瓣身份和备用图，`meta_sync_time` 与备用图状态彻底分离，匿名与登记图片许可池相互隔离，payload 在清理任务接管前的失败路径也会完整回收。未开启额外审计轮次。

## 验证证据

- 主源码编译通过。
- 图片代理定向测试：`18/18` 通过，覆盖主图回退、空体、HTML 伪装、错误 MIME、10 MiB 上限、逐跳 DNS 固定建连、匿名私网阻断、专用登记内网放行、公网登记 DNS 重绑定阻断、普通播放记录拒绝、危险响应头剥离、许可前置和嵌套 URL 单次解码。
- 条件快照、追剧服务、元数据桥接和图片代理定向套件：`289` 项，`0` 失败，`0` 错误。
- 新增真实 H2/JPA 条件更新与并发测试，覆盖 `season=null`、用户字段不被元数据快照覆盖、三个异步刷新入口丢弃过期身份、手工豆瓣 ID 保留、负缓存强刷失效、`NO_MATCH` 不重复补图、旧巡检代次写回拒绝、元数据/备用图职责分离和匿名/登记图片容量隔离。
- 排除 Docker 专属 `PostgreSqlMigrationTest` 后的完整后端套件：`1064` 项，`0` 失败，`0` 错误，`13` 跳过。
- `git diff --check` 通过；33 个变更文件的代码未新增待办标记。敏感字面量扫描仅命中常量引用、测试用伪造签名 URL 和禁止透传的响应头样例，没有新增硬编码凭据。
- 本机没有 Docker，因此 PostgreSQL Testcontainers 用例未运行；真实 MySQL/PostgreSQL 迁移和 `mvn clean package -Pnative` 也未运行。
- Web UI 构建未复验：本次未修改前端源码或锁文件，现有本地依赖仍缺少 `node_modules/hls.js`。

## 发布门禁

- 三位独立审计员均无中高等级问题。
- 发布前重新拉取 `upstream/master`，确认本地分支未落后。
- 最终复查完整文件清单、`git diff --check`、敏感值扫描和提交差异。
- 使用普通非强制推送发布候选分支，并向 `power721/alist-tvbox:master` 创建 PR。
