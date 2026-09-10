# 收藏 / 稍后再看 设计方案

> 状态：设计稿（未实现）。2026-09-09 起草，基于当时代码现状的摸底结论。

## 1. 目标与非目标

**目标**：给用户一条「发现 → 标记想看 → 观看 → 转追剧」的轻量动线。在片单/榜单/详情页一键把片加入用户级队列，四端（TVBox、WebHome、手机浏览器、管理网页）都能查看和操作，并能一键转追剧订阅。

**非目标（边界，防功能膨胀）**：
- **不做后台巡检 / 定时搜索 / 更新提醒** —— 这是与追剧订阅的本质边界。watchlist 是纯静态标记，零定时任务、零后台网络。想要「更新了通知我」就是转订阅（`PianDanSubscriptionService.subscribe` 现成编排）。
- **不做多收藏夹** —— 片单系统是纯虚拟聚合无实体表（`PianDanService` 不含 Repository），多收藏夹要引入用户态数据污染其定位，二期再议。
- 第一版不联动播放进度（History 联动见 §8 取舍）。

## 2. 概念模型

单表 + 状态列，三态一步到位建列、分期暴露 UI：

| 状态 | 语义 | UI 暴露 |
|---|---|---|
| `WANT` | 稍后再看（待看队列） | 一期 |
| `WATCHED` | 已看完归档 | 二期（一期列表里读侧显示，可手动流转） |
| `FAVORITE` | 收藏（长期留档，与 WATCHED 正交） | 二期 |

一期对外的名字就叫「稍后再看」；「收藏」= FAVORITE 态二期开 UI，数据结构不动。

## 3. 数据模型

新表 `watchlist_item`，V53 Java 迁移（近期惯例：改表全走 `db/migration/current` Java 迁移，且必须同步注册 `config/NativeFlywayMigrationConfig.java`；实体包已在 Main.java scan list，改完跑 `reflect-config` 再生）。

```java
@Entity
@Table(name = "watchlist_item",
       indexes = {
         @Index(name = "idx_watchlist_uid", columnList = "uid"),
         @Index(name = "idx_watchlist_uid_status", columnList = "uid, status")
       },
       uniqueConstraints = @UniqueConstraint(columnNames = {"uid", "vod_id"}))
public class WatchlistItem {
    private Long id;
    private int uid;                 // 写入方必须显式赋值（同 History.uid 惯例）
    private String vodId;            // 片单 vod_id 原样：tmdb:{tv|movie}:{id} / db:{纯数字} / s:{标题}[@{年份}]
    private String status = "WANT";  // String 常量，同 MediaSubscription.status 形态（MediaSubscription.java:185），避开 H2 enum ordinal 坑
    private String title;
    private Integer year;
    private Integer season;          // 可空；新番标记（转订阅时透传）
    private String pic;              // 快照，列表页免打 TMDB
    private String remarks;          // 评分等快照（拼角标前先幂等归一，见 §7）
    private Instant createdTime;
    private Instant statusTime;      // 状态流转时间（WATCHED 归档排序用）
}
```

要点：
- **唯一键 (uid, vod_id)**：加入入口收敛在片单/榜单详情页，那里 vodId 是稳定形态（tmdb:/db:），天然去重。管理页手动添加产生 `s:` 形态，标注「标题匹配可能不准」。
- **快照字段只增不改**：列表展示自给自足，不依赖 TMDB 缓存（`PianDanService.detailCache` 5min 且返回共享实例必须 copy，`copyDetail` 惯例）。
- 转订阅成功后**条目保留**，列表角标显示「已追」（复用 §7 的幂等助手），用户自行移出或留着。

## 4. API 设计

### 4.1 管理端 REST（`/api/watchlist`，session 用户走 `currentUid()`，同 `MediaSubscriptionController.java:434` 惯例）

| 端点 | 语义 |
|---|---|
| `GET /api/watchlist?status=&page=&size=` | 分页列表（createdTime desc） |
| `POST /api/watchlist` | 添加 `{vodId, title, year, season, pic, remarks}`；重复 (uid, vodId) 幂等返回已存在 |
| `PATCH /api/watchlist/{id}/status` | 状态流转 WANT↔WATCHED↔FAVORITE |
| `DELETE /api/watchlist/{id}`、批量 DELETE | 移出（每分组自带全选/反选，用户 UI 惯例） |
| `GET /api/watchlist/exists?vodId=` | 轻量存在性查询（WebHome 详情浮层初始化按钮态用） |

### 4.2 TVBox 协议（`/media`，走 token → `resolveUid`，同 `MediaSubscriptionService.resolveTokenUser:165`）

- `MediaLibraryController.categories()`（`MediaLibraryController.java:291-306`）加短 id 分类 `want`「稍后再看」，建议顺序 `recent → want → active → ended → all`；**uid 无条目时不输出该分类**（count 查询很轻），免新用户空列表困惑。
- `browse()`（`MediaLibraryController.java:85-93`）在落入 `contentList` 之前显式加 `want` 分支 → `watchlistService.content(uid, pg)` 输出 `MovieDetail` 列表（分页惯例 20/页，createdTime desc）。
  - ⚠️ 必须显式分支：现状未知 tid 落 `contentList` 且 `filterByStatus` 的 `default -> true`（`MediaSubscriptionService.java:921`），不拦会显示成「全部订阅」。
- 列表条目 `vod_id` 用原始片单 id → 点击进 `pianDanDetail` 详情，**「媒体信息 / 全局搜索 / 追剧按钮」全套动作零新增复用**，转订阅闭环天然成立。
- TVBox 端**零爬虫改动**：`Media.homeContent` 对 `/media` 响应纯透传（CatVodTVSpider `Media.java:96-99`），新分类随下次 homeContent 即出现，无需重打 spring.jar。

### 4.3 动作条目（对称 msubadd 模式）

- 前缀 `watchadd-` / `watchdel-`，常量加在 `MediaSubscriptionService.java:62-90` 旁。
- 载荷 `{vodId}|{剧名}|{季?}` form 编码 —— 与 msubadd 完全同构，**解析直接复用 `PianDanSubscriptionService.pianDanEntry`（:137-181）**。
- 生成处：
  - `MediaLibraryController.pianDanDetail`（:249-281）动作行追加 `➕ 稍后再看$watchadd-{payload}` 或已在队列时 `➖ 移出稍后再看$watchdel-{payload}`（按钮文案即状态，TVBox 端无需额外状态查询）；
  - watchlist 自身列表的详情页天然同源（同 pianDanDetail）。
- 执行处：`PlayController.play`（:99-159）加两分支 → `watchlistService.add/remove(uid, payload)` → `Map.of("msg", "已加入稍后再看")` 回执。
- `watchdel` **不加确认框**（可随时加回，保持轻量；对照 msubunsub 才有 confirm）。
- **spider 侧零改动可上线**：走默认直通路径（`Media.java:188`），后端 msg 由播放器展示。原生 Toast / 确认框增强（`Media.java:153-164` 同款 if 块，10-20 行）随下一次 spring.jar 重打捎带，不为此单独发版。
- PlaybackSyncer 对新占位 id 的推送压制自动生效（`PlaybackSyncer.java:435-448` 对未注册 id 一律压制 PUSH），无需改。

## 5. WebHome（`src/main/resources/static/webhome/app.html`）

- 详情浮层三动作变四动作：「稍后再看」toggle，仿 `toggleSubscribe`（app.html L855-872）调 `GET /play?...watchadd-/watchdel-`；按钮初始态用 §4.1 的 exists 查询或解析详情动作行前缀。
- 首页固定行加「稍后再看」行（`loadRow` 新 tid=want，L1055-1069 模式），空则隐藏该行。
- 浏览器预览模式同口径可用（同源 fetch + GET /play，无 fm SDK 依赖）。
- ⚠️ 改 app.html 必须 bump `SubscriptionService.buildWebHomeSite` 的 `v=`（现 v=22，`SubscriptionService.java:1473-1484`），否则 WebView 缓存不刷新。

## 6. 管理网页（web-ui）

新建 `WatchlistView.vue`（不并入 3300 行的 MediaSubscriptionsView 巨石），路由进菜单：表格列 = 封面/标题/年份/季/评分快照/状态/加入时间/操作（转订阅、移出），带状态筛选 tab、批量选择、批量移出。转订阅按钮直接调 msubadd 同编排（`POST /api/media-subscriptions` 或复用 `PianDanSubscriptionService.subscribe(uid, payload)`）。

二期：手动添加对话框（标题+年份+季 → `s:` 形态）+ SearchView 片单类结果行加按钮。

## 7. 复用清单（本设计的核心卖点）

| 复用件 | 位置 | 用途 |
|---|---|---|
| `pianDanEntry` 载荷解析 | `PianDanSubscriptionService.java:137-181` | watchadd 载荷解析 |
| `subscribe(uid, payload)` 编排 | 同上 :48-72 | 转订阅（TMDB/豆瓣绑元数据、幂等、checkAsync 全套白得） |
| pianDanDetail 详情动作行 | `MediaLibraryController.java:249-281` | watchlist 条目详情全套动作 |
| `subscribedRemarks` 幂等角标 | `MediaSubscriptionService`（a591c2f5） | 「已追」角标第四标记点，统一走助手防前缀累加 |
| msg 动作通道 | `PlayController.java:99-159` | watchadd/watchdel 执行回执 |
| uid 隔离范式 | `MediaSubscription` idx + `resolveUid(token)` + `currentUid()` | 全套照抄 |
| WebHome toggleSubscribe 模式 | app.html L855-872 | 收藏按钮 |
| MovieDetail DTO | `tvbox/MovieDetail.java` | 列表条目输出 |

## 8. 已知取舍与风险

- **不联动播放进度**：片单 vod_id 形态的 History 记录与播放链路弱关联，「接着看」已有继续观看行/TVBox recent 承担，watchlist 只做「想看队列」，边界干净。二期若要「看完自动归档 WATCHED」，读侧用 `substantiallyWatched` 口径（`MediaSubscriptionCheckService.java:1712`），仍零后台任务。
- **角标幂等**：watchlist 列表对象每次新建无缓存共享，理论无累加风险，但角标拼接统一走 `subscribedRemarks` 助手，不另写拼接逻辑。
- **Native image**：V53 注册 `NativeFlywayMigrationConfig`；新实体后跑 `reflect-config` 再生流程。
- **`s:` 形态体验**：手动添加的条目详情靠标题匹配，详情可能不准——列表页标注来源，入口尽量收敛在稳定 id 的详情页。
- **旧客户端**：msg 通道、/media 新分类对旧 spring.jar 同样生效（透传 + 后端执行），无版本门槛。

## 9. 分期

**一期（纯后端 + WebHome + 管理页，零 spring.jar 重打）**
1. V53 迁移 + `WatchlistItem` 实体 + `WatchlistService`（add/remove/exists/content/分页/转订阅编排透传）
2. `PlayController` watchadd/watchdel 分支 + `pianDanDetail` 动作行（含已追角标第四标记点）
3. `/media` `want` 分类 + browse 路由 + 空则隐藏
4. `/api/watchlist` REST + `WatchlistView.vue`
5. WebHome 浮层按钮 + 首页行 + bump v=23
6. 单测：uid 隔离 / 幂等 add / payload 解析复用 / want 路由（防 default->true 回归）/ 角标幂等

**二期（随下次 spring.jar 重打捎带 + 锦上添花）**
- spider `Media.java` Toast 拦截块（10-20 行）
- WATCHED/FAVORITE 态 UI + 看完读侧归档
- 管理页手动添加 / SearchView 结果行按钮
- TG Bot 命令（/want 列表、加片）——可选
