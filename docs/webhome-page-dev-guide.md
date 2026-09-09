# WebHome 自定义网页开发指南

日期:2026-09-09
面向:自定义网页源(webhome/pages)的页面开发者与移植者。目标是「一份 HTML,webhtv 原生端
与 OK影视/原版 FongMi 普通端零改动通用」,唯一桥是 `window.fm` SDK 契约
(契约源:webhtv `HomeWebController.getSdk()`,vox/aw 宿主 `assets/webhome-sdk.js` 同款)。

## 1. 形态概览

| 端 | 加载方式 | SDK 来源 |
|---|---|---|
| webhtv / fish | 原生 homePage 站点直接渲染 | 宿主注入完整 `window.fm` |
| OK影视 / 原版 FongMi | spring.jar `csp_WebHome` 全屏 WebView 弹窗 | spider 注入对齐契约的 `window.fm`(桥经 `atvWebHome` JS 接口反射宿主/服务端) |

- 上传:`data/static/webhome/pages/*.html`(网页端「文件-静态文件」上传),自动注册为
  `csp_WebHome` 站点,key 取文件名(中文回落插件 id)。经 `/webhome/**` no-cache 下发,
  外部覆盖优先、classpath 内置兜底,改页面重传即生效。
- 页面 URL 自动带 `?pt=`(播放同步专用令牌,继续观看可同源 fetch `/api/playback/changes`)。
- 站点 ext(base64 JSON):`url` 页面地址 / `token` vod token / `pt` 播放令牌 /
  `panSearch` 盘搜后端能力开关 —— 裸订阅(无 token)时 token/panSearch 为空,后端能力随关。

## 2. 最小骨架

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>我的首页</title></head>
<body>
<div id="app">加载中…</div>
<script>
var fm = null;
function ensureFm() {
  if (window.fm) { fm = window.fm; return boot(); }
  window.addEventListener('fmsdk', function () { fm = window.fm; boot(); });
  // 浏览器预览等 600ms 无桥自行降级(fm.req 等不可用,需 fetch 兜底)
  setTimeout(function () { if (!fm) boot(); }, 600);
}
function boot() {
  var el = document.getElementById('app');
  if (!fm) { el.textContent = '浏览器预览模式'; return; }
  // 网络一律走 fm.req(原生 OkHttp,无 CORS);浏览器 fetch 只做预览兜底
  fm.req('https://api.example.com/list', { responseType: 'json', timeout: 15 })
    .then(function (res) { el.textContent = res.ok ? 'ok' : 'HTTP ' + res.status; });
}
ensureFm();
</script>
</body>
</html>
```

要点:**`window.fm` 存在 ≠ 完整桥可用**的年代已过,但页面仍须监听 `fmsdk` 事件重初始化
(OK影视 端注入晚于页面脚本首跑,首帧可见+加载完成双时机注入,幂等)。

## 3. fm SDK 契约与两端实现对照

| 方法 | 作用 | webhtv | OK影视(csp_WebHome) |
|---|---|---|---|
| `req(url, opts)` | 网络请求,信封 `{ok,status,url,headers,body}`,opts: method/headers/body/responseType(text\|json)/timeout(秒) | 原生 | ✓ 桥内 OkHttp |
| `res(url, {headers})` | 资源网关(防盗链图),同步返回地址 | 本地网关 | ✓ 客户端 `VideoStreamProxy /res/`(注入页面 UA/Referer 回源;magnet/data-uri/异常直通) |
| `vod(siteKey, vodId, title, pic)` | 跳原生详情/播放 | 原生 | ✓ VideoActivity.start |
| `search(keyword)` | 原生全局搜索(带词直达结果) | 原生 | ✓ 优先 CollectActivity(keyword extra 启动即全站聚合搜;OK影视 TV 的 SearchActivity 不读 extra 属丢词根因),SearchActivity(keyword/key)回落 |
| `history()` | 最近观看 | 原生 | ✓ 反射→宿主库读→服务端播放记录三层 |
| `play(url, title)` | 直链/`push://` 前缀播放 | playUrl | ✓ 转宿主 push_agent(剥 `push://`) |
| `pan.play({type,url,password,title})` | 网盘直开 | 原生 | ✓ push_agent:提取码按约定嵌 URL(115 系 `password` 其余 `pwd`)→服务端 `/parse`→代理播放 |
| `pan.check(items)` / `check` | 盘有效性检测 | 原生 | ✓ 服务端 `/check-links`(PanCheck>TG-Search>PanSou 三级链);**结果按请求序返回**(未检/限流=`idle`) |
| `cache.get/set/del(key)` | 键值存储 | 原生 | localStorage(`fm_` 前缀,同 origin 持久) |
| `openLive/openKeep/openSetting` | 宿主功能页 | 原生 | ✓ findClass 标准 Activity(未找到降级 toast) |
| `back()` / `reload()` | 导航 | 原生 | ✓ WebView 回退/关弹窗;location.reload |
| `site()` / `config()` / `device()` | 环境信息 | 原生 | 静态:站点名/页面地址/`driveCheck`;UiModeManager 判 leanback(type 0)/mobile(1) |
| `ext.toast(msg)` | 原生提示 | 原生 | ✓ |
| `ui.setToolbar/setChrome/restoreChrome/getViewport` | 窗口控制 | 原生 | 中性桩(getViewport 返回窗口尺寸近似) |
| `vodInline/preloadArtwork/ctrl/stat/openVod` | 内嵌播放等 | 原生 | 桩(resolve 空值,页面须容错) |

另有 `window.fongmi`(结构化:`app.openSetting` 等)与 `window.fongmiClient`(mode/isLeanback)
可用;事件:`fmsdk`(就绪)、`fmurlchange`(仅 webhtv)、`fmresume/fmpause`(仅 webhtv)。

`config().driveCheck` 为 true 时页面才应启用盘检测过滤(玩偶页按此门禁)。

## 4. 后端自动能力(页面无感知,但要知道行为)

- **盘搜透明拦截**:ext.token+panSearch 开启时,页面 `fm.req` 指向已知公开 PanSou 实例
  (`so.252035.xyz`/`panso.xxmu.top`)的 `/api/search`、`/api/auth/login` 会被桥改写为
  本服务 `/pan-search/{token}` 同路径透传 —— 上游/登录态/配额由服务端配置承担。
  用户在页面里自选的其它实例**不在清单,原样放行**。未配上游或裸订阅时不拦,
  页面继续用内置公开地址。
- **盘检**:`pan.check` 批量送服务端,结果与请求同序;`rate_limited`/未命中回 `idle`
  (限流≠死链,防页面按 uncertain 隐藏误杀)。
- **res 网关**:外站图统一走客户端本地代理(127.0.0.1:5000 起,占用自动后移),
  页面指定的 UA/Referer 会注入回源请求。

## 5. 兼容红线(TV 盒子 WebView 可能停留在 Chromium 51)

- JS 基线 ES2017(async/await 及以下);**严禁 `?.`、`??`、逻辑赋值、可选 catch binding**。
- CSS:flex `gap`/`clamp`/`aspect-ratio`/`:is()`/`:has()` 需降级兜底;高度
  `var(--fm-web-height, 100vh)`;安全区 `max(var(--fm-safe-*), env())`。
- 网络统一 `fm.req`,浏览器 fetch 只作电脑预览 fallback。

## 6. 部署与调试

1. 上传 HTML 到 `webhome/pages/`(单文件;zip 可带 css/js/图片相对资源)。
2. 客户端刷新订阅配置,站点出现在「订阅源管理」可排序/改名。
3. 调试:电脑浏览器直接开页面 URL 走「浏览器预览模式」(SDK 回落);OK影视 真机看
   SpiderDebug 日志(`WebHome ...` 前缀)。

常见坑速查:

| 现象 | 根因 |
|---|---|
| `sdk(...).xxx is not a function` | 宿主桥缺该方法面(对照上表;OK影视 端桩方法已尽量补齐) |
| 页面拿到数据但"时好时坏" | 未监听 `fmsdk` 重初始化(桥注入晚于首跑) |
| 玩偶类页面点击网盘只弹提示 | 已修:pan.play 走 push_agent;仍失败看宿主是否含 push_agent 站 |
| 跳到搜索页但搜索词丢失 | 宿主 SearchActivity 不读 extra(OK影视 TV 形态);已改为优先跳 CollectActivity(带词启动即聚合搜) |
| 防盗链图不显示 | 走 `fm.res(url,{headers})` 而非裸 `<img src>` |

## 参考

- 契约源:`~/Downloads/fty/vox/vox.sep6/assets/webhome-sdk.js`(逐字对应 webhtv)。
- 集成设计:`docs/webhome-integration-design.md`;内置首页:`src/main/resources/static/webhome/app.html`。
- spider 实现:CatVodTVSpider `WebHome.java`(SDK 注入/桥)、`Push.java`(网盘解析)、
  `VideoStreamProxy.java`(/res 网关);服务端:`RemoteSearchController`(/pan-search、/check-links)。
