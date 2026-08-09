# 网盘资源多播放线路 调查与设计

> 目标：让 alist-tvbox 的网盘资源像主流 TVBox 爬虫 jar（如 FishZhizhen/my.jar）那样，为同一文件集返回多条播放线路（转存原画 / 原画代理 / 智能线路 / 无限线路），而非当前的单一转存原画链接。
>
> 调查日期：2026-08-08。涉及三个组件：alist-tvbox（Java）、PowerList（Go，AList/OpenList fork）、参考爬虫 my.jar（FishZhizhen，DEX）。

---

## 1. 三个组件的角色

| 组件 | 位置 | 职责 |
|---|---|---|
| **alist-tvbox** | `/home/harold/workspace/alist-tvbox`（Java，Spring Boot 4） | TVBox VOD API + 自带 spider。构建 `vod_play_from`/`vod_play_url`，解析播放直链。**当前只产出 1 条线路**。 |
| **PowerList** | `/home/harold/GolandProjects/PowerList`（Go，OpenList fork，作者同为 power721） | 存储与取链后端。**不输出 TVBox JSON**，只通过标准 AList 接口（`/api/fs/get`、`/d/*`、`/p/*`）提供直链。**内部已有 Quark 全部 3 种取链原语**，但按请求自动选一种，无按模式指定入口。 |
| **参考爬虫 my.jar** | `/home/harold/Downloads/fty/my/my.jar`（DEX，FishZhizhen） | 反编译参考：`/home/harold/Downloads/fty/fish06140630.jadx/sources/com/github/catvod/spider/`。展示多线路的 `vod_play_from`/`vod_play_url` 格式与按 `flag` 路由解析。 |
| **TVBox 播放器** | `/home/harold/StudioProjects/TV`（FongMi Android 源码） | 消费方。`vod_play_from` 的每条 `$$$` 段显示为一个线路 tab，点集时回传 `flag=<线路名>` + `id=<集id>` 给 spider 的 `playerContent`。 |

**数据流（当前）**：
```
TVBox app ──详情──> alist-tvbox /api (getPlaylist) ──vod_play_from=站点名, vod_play_url=siteId@proxyId@fid
TVBox app ──播放──> alist-tvbox /play (getPlayUrl) ──/api/fs/get──> PowerList ──> 1 个 raw_url + header
```

---

## 2. 现状（alist-tvbox 单线路）

### 2.1 详情装配（构建 vod_play_from / vod_play_url）
- `TvBoxService.getPlaylist(...)` `:2029-2122`：主装配入口。
  - `:2058` `movieDetail.setVod_play_from(site.getName())`（默认=站点名）。
  - `:2108-2111`：仅当**多个子文件夹**时，把文件夹名用 `$$$` 拼成多段 `vod_play_from`——但这是「不同文件夹=不同集」，**不是同集多画质**。
- `dfs(...)` `:2124-2221`：遍历文件夹，集用 `#` 拼（`:2201`）。
  - `:2194` `buildPlayUrl(site, source, index, filepath)` → 集 id。
  - `:2189/:2195` `title + "$" + url`。
- 第二套并行装配块 `:2266-2382`（首段硬编码 `"默认"`）。
- `getDetail(...)` 变体：`:1853-1879` 多版本（`版本1$$$版本2`）、`:1760` 单文件。

### 2.2 集 id 格式
- `buildPlayUrl(site, path)` `:2708` → `siteId@proxyUrl`
- `buildPlayUrl(site, folderId, fileId, path)` `:2712` → `siteId@proxyUrl@folderId@fileId`
- `proxyService.generateProxyUrl(site, path)` 生成不透明 proxyId。

### 2.3 播放解析（getPlayUrl，产出 1 个直链）
- `TvBoxService.getPlayUrl(siteId, path, getSub, client, type)` `:1407-1561`：
  1. `:1437` `aListService.getFile(site, path)` → `FsDetail`（PowerList `/api/fs/get`）。
  2. `:1443` `url = fsDetail.getRawUrl()` —— **只有一个**。
  3. `:1455-1467` provider → `DriverType`（Quark/UC/Thunder/115/Baidu/123/139/189/Ali/GuangYa）。
  4. `:1473-1519` 决定代理 vs 直链 + per-driver header。
     - **关键**：Quark `:1479-1491` / UC `:1492-1505` 已能区分两种：
       - `#x-referer=raw` 标记 → **免转存直链**，用 `URL+"\ "` 作 Referer（绕 checkplay 回调）。
       - 无标记 → **原画/自有文件**，用 Cookie + 站点 Referer。
       - 注释见 `:1482-1483`、`:1495-1497`。**该标记由 PowerList 在免转存直链末尾追加。**
  5. `:1521-1536` `multiUrls` ← `FsDetail.multiSource`（PowerList 多账号分片直链，供 spider.jar 客户端分片，**不是 vod_play_from 线路**）。
- HTTP 入口：`PlayController.java:108-124` → `getPlayUrl(...)`。
- 重载：`:1652`(by id+index)、`:1660`(by id+path)。

### 2.4 FsDetail 模型（`model/FsDetail.java`）
字段：`name/type/isDir/modified/size/sign/thumb/provider/rawUrl/multiSource`。**无「模式」概念**。

### 2.5 关键结论
> 当前代码**没有「同文件集多并行线路」概念**。`$$$` 多段仅用于子文件夹/季。最近的是 `getPlayUrl.multiUrls`（PowerList 多账号分片），机制完全不同。

---

## 3. 参考数据解读（my.jar / FishZhizhen 的 4 类线路）

样本 `vod_play_from`：
```
夸克原画#0101$$$夸克原画#0102$$$夸克智#0101$$$夸克智#0102$$$夸克无限#0101$$$夸克无限#0102$$$百度原画#0103$$$百度原画#0104$$$百度无限#0103$$$百度无限#0104
```
- 线路名 = `<网盘><模式>`，`#NNNN` = **账号序号**（0101=夸克账号1，0103=百度账号1）。
- `vod_play_url` 用 `$$$` 对齐每条线路；线内集用 `#` 分隔；集格式 `显示名$id`。

解码后的集 id 形态：

| 线路 | 含义 | Quark id 形态 | Baidu id 形态 |
|---|---|---|---|
| **转存原画** | 转存到自己网盘 → 原画直链（Cookie/全速） | `[大小]文件名$h1++h2++id++base64token++h3`（5 段 `++`） | base64 JSON `{"uk","shareid","fid","size","randsk","surl","share_url","share_cookie","pname","qtype":"original"}` |
| **原画代理** | 原画链经代理注入 header（兼容性好） | 同上，走 proxy | 同上 |
| **智能线路** | TV 转码低码流（弱网流畅） | 转码 token | — |
| **无限线路** | 免转存分享直链（不占账号空间，可能限速） | 分享直链 | base64 JSON（免转存） |

播放器日志佐证按 `flag` 路由：
```
TV-player: key=FishZhizhen, flag=夸克原画#0101, id=b1f56961...++...
```

### 反编译要点（Fish spider）
- `Quark.java:61 detailContentVodPlayFrom(int i)`：按配置开关（`a0.x(...)`）**条件性**加入最多 3 条线路（原画/智/无限），`$$$` 拼接。
- `BaiDuPan.java:26 detailContentVodPlayFrom(int i)`：单条 `百度原画`+index。
- `M/c.java:554-555`：`TextUtils.join("$$$", playFromList)` / `playUrlList`。
- `playerContent(flag, id, ...)` 按 flag 解析（`Quark.java:172`）。
- 代码重度混淆（`short[]` 运行时解码字符串），但类结构清晰。

---

## 4. 能力矩阵（PowerList 已有取链原语 → 线路）

**关键发现**：PowerList 内部已有 Quark/UC 的全部 3 种取链原语，但 `Link()` **自动选一种**，无按请求指定入口。

### 4.1 Quark/UC（`drivers/quark_uc_share/`）—— 三原语齐全
- `driver.go:97-144 Link()` 路由：
  - `multiSourceEnabled` → `collectMultiAccountLinks`（多账号并发，`:112-122`），失败回退免转存（`:124-129`）。
  - 否则 → `resolveQuarkUCShareLink`（转存+speedup，`:135`）为主，`resolveShareDirectLink`（免转存，`:138`）兜底。
- `driver.go:146-171 link()`：`saveAndLink` = **转存原画**。
- `driver.go:161-166`：非 SVIP 账号 `getTvLink(forceStream=true)` = **智能转码**（注释「非 SVIP 原画直链易被限速,回落 TV 转码流」）。
- `util.go:1205 resolveShareDirectLink` = **免转存/无限**。
- `util.go:489-500 saveShareFile`（转存 + savedFileCache 复用）。
- `util.go:684-686,775` speedup token（夸克聊天会话换提速 token）。
- `util.go:1023-1031` 路由：「有 SVIP 主账号 → 转存原画全速；否则 → 免转存」。

### 4.2 123 盘（`drivers/123_share/`）—— 转存 + 无限
- `driver.go:78-86 Link()`：默认 `thumbDirectLink`（**无限直链**，`unlimited.go:27`，`/share/get` 已签名 DownloadUrl），失败回退 download/info（转存）。
- `unlimited.go:20-31` 注释：「免登录、不转存、不占额度」。
- `meta.go` 开关 `DisableUnlimited`。**无转码**。

### 4.3 百度（`drivers/baidu_share2/`）—— 仅转存
- `driver.go:226-237 Link()` → `resolveBaiduShareLink`，**单一转存路径**。
- **无免转存、无转码**。要支持「百度无限」需在 PowerList 新写免转存路径（参考 Quark `resolveShareDirectLink`）。

### 4.4 其它（115/139/189/Ali/Thunder/GuangYa）
- 仅各自的单一取链。保持单线路即可。

### 4.5 线路能力总表

| 线路 | alist-tvbox 侧动作 | PowerList 原语 | Quark/UC | 123 | Baidu | 其它 |
|---|---|---|---|---|---|---|
| 转存原画 | 直链 + Cookie header | `saveAndLink`/转存 | ✅ | ✅ | ✅ | ✅(各自取链) |
| 原画代理 | `useProxy=true`（走 `/p`） | 同上 | ✅ | ✅ | ✅ | ✅ |
| 智能线路 | 直链 | `getTvLink(forceStream=true)` | ✅ | ❌ | ❌ | ❌ |
| 无限线路 | 直链 + `#x-referer=raw` Referer | `resolveShareDirectLink`/`thumbDirectLink` | ✅ | ✅ | ❌(需新增) | ❌ |

> **重要**：「原画代理」纯 alist-tvbox 侧行为（代理开关 toggle），**无需 PowerList 改动**，所有 driver 都能支持。

### 4.6 PowerList 改动可行性（前置条件）
- `model.LinkArgs`（`internal/model/args.go:21`）仅 4 字段：`IP/Header/Type/Redirect`。
- 内联构造于 3 处：`server/handles/fsread.go:342`（`/api/fs/get`）、`down.go:33`（`/d`）、`down.go:63`（`/p`）。
- **加 `LinkMode string` 字段 + 3 处读 `c.Query("link_mode")` 改动极小**。driver 的 `Link()` 按 mode 分支即可（原语都已存在）。

---

## 5. 实现方案（最小改动，分两边）

### 方案 A：PowerList（Go）—— 取链模式可按请求指定
1. `internal/model/args.go` `LinkArgs` 加 `LinkMode string`。取值：`""`(默认=自动) / `"transfer"`(转存原画) / `"direct"`(免转存无限) / `"transcode"`(智能转码)。
2. 3 处调用点读 query：
   - `server/handles/fsread.go:342`（FsGet）
   - `server/handles/down.go:33`（Down `/d`）
   - `server/handles/down.go:63`（Proxy `/p`）
   填 `LinkMode: c.Query("link_mode")`。
3. `drivers/quark_uc_share/driver.go:97 Link()` 按 `args.LinkMode` 分支：`transfer`→`resolveQuarkUCShareLink`；`direct`→`resolveShareDirectLink`；`transcode`→`getTvLink(forceStream=true)`；空→现状自动。
4. `drivers/123_share/driver.go:78 Link()` 按 mode 选 `thumbDirectLink` vs download/info。
5. （可选，Baidu 无限）`drivers/baidu_share2/` 新增免转存路径。
- 注意缓存键：`quarkUCShareLinkCache`(`driver.go:31`)、`baiduShareLinkCache` 按 `file.GetID()` 缓存，**需把 LinkMode 并入 cache key**，否则不同模式串台。

### 方案 B：alist-tvbox（Java）—— 产出多线路 + 按模式解析
1. **配置**（新 setting）：每 driver 启用哪些线路（镜像 Fish `a0.x(...)` 开关）。建议默认：Quark/UC=4 条、123=转存原画+无限、Baidu=转存原画(+代理)、其它=转存原画。
2. **详情装配**（`dfs`/`getPlaylist` `:2124`/`:2029`）：对**同一文件集**按配置产出 N 条并行线路：
   - `vod_play_from` = 启用线路名 `$$$` 拼接（如 `夸克原画$$$夸克智$$$夸克无限`）。
   - 每条线路的集 id 追加模式标记：`siteId@proxyId@folderId@fileId@{mode}`。
   - 与现有「子文件夹=多段」逻辑需协调：单文件夹时直接用模式线路；多文件夹时可用「文件夹×模式」或仅首层模式线路（待定，见未决问题）。
3. **播放解析**（`getPlayUrl` `:1407`）：
   - 从集 id 解出 mode（或从 `/play` 收到的 flag 映射 label→mode）。
   - 调 PowerList `/api/fs/get?link_mode={mode}`（`AListService.getFileV3` `:288` 需透传 mode query）。
   - `原画代理` mode → `useProxy=true`（走 `buildProxyUrl`）。
   - `无限` mode → 复用 `:1480/:1493` 的 `#x-referer=raw` Referer 分支。
4. **路由透传**：`PlayController` `:108-124` / `getPlayUrl` 重载 `:1652/:1660` 把 mode 从 id 传到解析。

### 多账号处理
- Fish 用 `#0101/#0102` 每账号一条线路。
- alist-tvbox 架构中多账号由 PowerList 服务端 `MultiSource` 聚合（`getPlayUrl:1521`），**默认不需要每账号一条线路**。
- 若要客户端分片加速：保留 `multiUrls` 机制（已存在），与线路正交。

---

## 6. 未决问题（实现前需确认）

1. **线路范围**：先只做 Quark/UC（4 条全有）？还是连 123/Baidu？Baidu「无限」需在 PowerList 新写免转存路径（工作量较大）。
2. **多账号**：保持 PowerList 服务端聚合（单线路），还是也要暴露「每账号一条线路」给客户端分片？
3. **配置粒度**：线路开关放全局、每 driver、还是每站点？
4. **多文件夹 × 多模式组合**：`vod_play_from` 同时有文件夹段和模式段时如何排列（`文件夹$$$模式` 会被播放器当成同维线路）？需设计复合标签或限定单文件夹才出多模式。
5. **是否现在动 PowerList**：方案 A 是必要前置（否则无法按请求选模式）。确认可改 PowerList 则两边一起出补丁。
6. **缓存 key**：PowerList 现有 link cache 按 file id，加 mode 后必须并入 key，避免原画/转码串台。

---

## 7. 关键文件索引

### alist-tvbox（Java）
- 详情装配：`src/main/java/cn/har01d/alist_tvbox/service/TvBoxService.java:2029-2122`（getPlaylist）、`:2124-2221`（dfs）、`:2266-2382`（并行块）。
- 集 id：`:2708-2714`（buildPlayUrl）、`:2717-2736`（buildProxyUrl）。
- 播放解析：`:1407-1561`（getPlayUrl），尤其 `:1473-1519`（代理/header）、`:1521-1536`（multiUrls）。
- HTTP 入口：`src/main/java/cn/har01d/alist_tvbox/web/PlayController.java:108-124`。
- 模型：`src/main/java/cn/har01d/alist_tvbox/model/FsDetail.java`。
- AList 调用：`src/main/java/cn/har01d/alist_tvbox/service/AListService.java:279-316`（getFile/V3）。
- 转存子系统：`src/main/java/cn/har01d/alist_tvbox/service/offline/`、`ParseService.java:25-38`。

### PowerList（Go）
- LinkArgs：`internal/model/args.go:21`。
- 调用点：`server/handles/fsread.go:342`、`server/handles/down.go:33,63`。
- Quark/UC 路由：`drivers/quark_uc_share/driver.go:97-171`、`util.go:489,684,1023,1205`。
- 123：`drivers/123_share/driver.go:78`、`unlimited.go:20-57`。
- Baidu：`drivers/baidu_share2/driver.go:226-237`。
- 多账号分片：`internal/conf/const.go:133-136`、`internal/stream/util.go:130-347`、`server/handles/fsread.go:264-266`。

### 参考爬虫（反编译）
- `/home/harold/Downloads/fty/fish06140630.jadx/sources/com/github/catvod/spider/Quark.java:61`（playFrom）、`BaiDuPan.java:26`、`FishDrive.java`、`merge/M/c.java:554`。
