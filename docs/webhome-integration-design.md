# WebHome 首页站点集成设计(alist-tvbox × WebHomeTV/默影视)

日期:2026-09-07
状态:**已演进为 csp_WebHome 单形态通吃(2026-09-09)** —— 能力探测/双形态方案已废弃:
webhtv/fish 按 `homePage` 字段原生渲染,原版 FongMi/OK影视 由 spring.jar `csp_WebHome`
弹 WebView 加载同一 URL 并注入对齐 webhtv 完整契约的 `window.fm` SDK(req/桥内 OkHttp、
res/客户端本地代理、pan.play/push_agent 网盘直开、pan.check/服务端盘检、盘搜桥层透明拦截)。
**页面开发/移植看 [webhome-page-dev-guide.md](webhome-page-dev-guide.md)**;本文保留
一期设计过程与客户端契约考证。下述「客户端能力判定」章节为历史方案,仅存档。

## 客户端能力判定(实现定论)

配置拉取请求无法被动区分客户端:fish 与原版 FongMi 包名同为 `com.fongmi.android.tv`,
且三个端拉配置都不带特征 UA/头(catvod OkHttp 默认栈)。实际方案:

1. **spider 运行时类探测**:spring.jar 的 csp_Media 在宿主进程内
   `Class.forName("com.fongmi.android.tv.web.HomeWebBridge"/"WebHomeRawAdapter")`
   探测(webhtv/fish 有、原版 FongMi 无),随 /media 请求上报 `X-CLIENT-CAPS: webhome`。
2. **webhtv 独占包名** `com.silent.android.webhtv` 经既有 `X-CLIENT` 头直接判定。
3. 服务端 `WebHomeService` 按 token 记忆(Setting 表 `webhome_capable_tokens`,重启保留),
   订阅配置生成时按 token 门禁注入 homePage 站点;原版 FongMi 收到 csp_Builtin 站点会
   解析失败,绝不无差别下发。首次配好订阅时无此站,spider 跑过一次后下次刷新配置出现。

## 已实现(2026-09-07)

- `static/webhome/app.html`:完整首页(继续观看 fm.history / 最近更新 / 连载中 / 已完结
  + 最多 4 个片单榜单行;fm.vod 进 csp_Media 详情;fm.search 搜索;edge 融合 chrome;
  遥控器四向焦点导航;ES5 兼容引导层,无 `?.`/`??`/逻辑赋值)。
- `WebHomeService` 能力记忆 + `MediaLibraryController` 捕获头。
- `SubscriptionService.addWebHomeSite()`:token 门禁注入 `atv_home`(sites 首位)。
- spider `Media.java` 类探测头,spring.jar 已重打包。
- native-image 资源注册 `static/webhome/.*`。

## 目标

为 WebHomeTV(Silent1566/webhtv,FongMi 深度魔改客户端)提供一个由 alist-tvbox 托管的
WebHome 自定义首页站点,把 alist-tvbox 的订阅追更、片单、最近更新等能力做成它的原生 TV 首页。

## 客户端侧契约(已从 webhome-devkit/docs/应用完整开发文档.md 核实)

1. **站点声明**:TVBox 配置 `sites[]` 里加一项:

   ```json
   {
     "key": "atv_home",
     "name": "影视首页",
     "type": 3,
     "api": "csp_Builtin",
     "homePage": "./atv-home.html"
   }
   ```

   `homePage` 相对路径以**配置 URL** 为基准解析。alist-tvbox 的配置地址是
   `http://host:5344/{sid}` 或 `/api` 形态,所以首页 HTML 由 alist-tvbox 同域托管即可,
   例如 `http://host:5344/tvbox/atv-home.html` 或新端点 `/webhome/index.html`。
   无需 `bridge: "full"`,默认注入完整 SDK。

2. **SDK**:页面注入 `window.fm`(`fongmi` 全名 + `fm` 短别名),`fmsdk` 事件就绪。
   关键能力:
   - `fm.req(url, options)`:Native OkHttp 请求,无 CORS 限制 → 页面直接调 alist-tvbox
     的订阅/片单/搜索 API(带 X-API-KEY 或路径 token)。
   - `fm.vod(siteKey, vodId, title, pic, {wallPic})`:跳原生详情/播放链路 → 直接喂
     `csp_Media` 站点的 vod_id(msub 快照/片单条目已支持 id 直读,零搜索零网络)。
   - `fm.vodInline(payload)`:多集直链临时播放(适合网盘直列场景,`episodes[].resolve`
     支持按集懒解析)。
   - `fm.play(url, title, {headers})`:播 /p 代理直链(网盘代理地址)。
   - `fm.search(keyword, {direct: true})`:跳原生全局搜索。(2026-09-09 现状:普通端优先跳 CollectActivity——OK影视 TV 的 SearchActivity 不读 extra 会丢词,详见网页开发指南)
   - `fm.history()`:本机最近观看(60 天),可做"继续观看"行。
   - `fm.ui.setChrome({mode: "edge"})`:首页融合模式(TV 映射 tv-full)。
   - `fm.pan.check/fm.device/fm.site/fm.config/fm.cache` 等辅助。

3. **兼容红线**(TV 盒子 WebView 可能停留在 Chromium 51):
   - JS 语法基线 ES2017(async/await 及以下),**严禁 `?.`、`??`、逻辑赋值、可选 catch**。
   - CSS:flex gap(84+)/clamp(79+)/aspect-ratio(88+)/:is()/:has() 均需降级兜底;
     高度用 `var(--fm-web-height, 100vh)`,安全区用 `max(var(--fm-safe-*), env())`。
   - 照搬 `webhome-devkit/examples/homepages/nostr.html` 头部 ES5 兼容引导层
     (polyfill + `no-*` 降级类 + `fm-native` 标记)。
   - 网络统一走 `fm.req`,浏览器 fetch 仅作电脑预览 fallback。

## alist-tvbox 侧改动

### 1. 首页 HTML 托管

- 单文件 `atv-home.html` 放 `data/tvbox/`(与 my.json 同目录,已有静态下发通道)
  或 classpath `resources/static/webhome/`。
- 走现有 TvBoxController 的静态文件路径最省事;若要带 token 个性化,新增
  `/webhome/{token}/index.html` 端点按 token 注入用户身份(页面内嵌 `window.ATV_TOKEN`)。
- 页面内容一期:最近更新(订阅 updatedTime 近 7 天)/连载中/已完结三个横向海报行
  + 最近观看(fm.history)行 + 搜索入口;点击卡片 → `fm.vod("csp_Media", vodId, ...)`。

### 2. sites 注入

`SubscriptionService.addSite()`(约 :1355)在 builtin 源循环后追加 homePage 站点:

```java
Map<String, Object> site = new HashMap<>();
site.put("key", "atv_home");
site.put("name", "影视首页");
site.put("type", 3);
site.put("api", "csp_Builtin");
site.put("homePage", baseUrl + "webhome/index.html"); // 绝对地址,避免相对解析歧义
site.put("searchable", 0);
sites.add(site);
```

- `api: csp_Builtin` 是客户端内置,无需 spider。
- 用 SubscriptionSource 机制做成可开关的源(默认关,网页订阅源列表可勾选),
  与 csp_Media 等同管道管理,而不是硬编码。
- `homePage` 用绝对 URL:多接口(@)拼接/反代场景下相对路径会解析错。

### 3. 页面数据 API

复用现有接口,零新增或少量新增:
- 订阅列表/分类:`fm.req` 调 TvBoxController 现有分类(classify)接口,
  或新增轻量 `/webhome/api/feeds`(返回订阅卡片:标题/海报/进度/角标)。
- 详情/播放:全部经 `fm.vod` 走 csp_Media 原生链路(集源行、订阅动作条目天然可用),
  页面自身不碰播放解析。
- 认证:X-API-KEY(嵌入式 token)或路径 token,与 spider 同口径。

### 4. 可选二期

- `fm.vodInline` + `episodes[].resolve`:网盘直链多集播放,点击时回调 alist-tvbox
  解析 /p 地址(减少详情页往返)。
- `wallPic` 传 TMDB backdrop,播放页背景优化。
- 推送 `/action` 配置 URL、ApkUrlPush 批量部署。

## 工作量评估

- 一期(静态首页 + sites 注入 + 三行卡片 + fm.vod 跳转):页面为主,后端 < 100 行,
  兼容层照搬 nostr.html 模板。
- 风险点:旧 WebView 兼容需在真机(最旧的盒子)过一遍焦点导航;TV 遥控器 UX 遵循
  文档 22.5 节。

## 参考

- 客户端文档:`/home/harold/StudioProjects/Silent1566_webhtv/webhome-devkit/docs/应用完整开发文档.md`
  第 14-22 章;示例 `examples/homepages/nostr.html`、模板 `templates/homepages/`。
- 客户端站点加载:`app/src/main/java/com/fongmi/android/tv/api/config/VodConfig.java`。
- alist-tvbox 注入点:`SubscriptionService.addSite()` / `buildCatalog()`。
