# 多端播放记录同步设计

> 服务端 = AList-TvBox;消费者 = 安卓手机 / 安卓TV / Python桌面(atv-player) / 网页端。
> 优先级:安卓手机端 + 安卓TV端(客户端 = FongMi影视 / Fish WebHomeTV / 默影视 / OK影视)。
> **安卓端统一通过爬虫 jar 自动上报**(基于 `CatVodTVSpider`);Python 桌面端(atv-player)扩展其 `ApiClient`。

---

## 1. 调查结论

### 1.1 客户端矩阵

| 客户端 | 形态 | 自带云端同步 | 接入方式 |
|---|---|---|---|
| FongMi / OK影视 | 安卓 | ❌ 仅局域网 `/action?do=sync` | spider jar(主) |
| Fish WebHomeTV / 默影视 | 安卓 | ✅ webhook + 远端同步 | 自带(备选)/ spider jar |
| **atv-player** | **Python 桌面**(PySide6+libmpv) | **部分**:Tier-A 已走 `POST /api/history` | 扩展 `ApiClient` 补 Tier-B |
| AList-TvBox 网页端 | Web | ✅ 已用 `/api/history`(session→uid) | 复用现有 |

### 1.2 FongMi / OK影视:无云端同步

- 本地记录 = Room 表 `History`,主键 `key = siteKey@@@vodId@@@cid`。字段:`vodPic/vodName/vodFlag/vodRemarks/episodeUrl/position/duration/createTime/opening/ending/speed/scale/cid`。写入由 `VodHistoryPolicy.saveCurrent` **每 5s** 节流刷新。
- 本地服务 `127.0.0.1:9978` 起;路由 `/media /device /tvbus /action /proxy`。
- 局域网同步 `POST /action?do=sync&mode=0|1|2`,form `targets=History[]`,按 vodName+createTime LWW。**`mode=1` 接收口 → spider PULL 回写设备。**
- **打包后代码混淆、包名每次发布都变 → Java 类名反射不可行;spider 改用框架 `SQLiteDatabase` 直读 `tv.db`**(表/列名为 SQL 字面量不混淆,路径经 `Context.getDatabasePath()` 运行时取)。

### 1.3 `/media`(OK实测)— 仅退化用

`{url,state,speed,title(=vodName),artist(=集名),artwork,position,duration}`,**无 siteKey/vodId**。仅作 tv.db 读取失败的退化实时源。

### 1.4 Fish / 默:`webhtv.playback.v1`(备选)

webhook(单条 POST,事件 `progress/ended/deleted`,头 `X-WebHTV-Token/Idempotency-Key`)+ 远端同步(`GET`,头 `X-WebHTV-Since/Limit`,响应 `{nextSince,items[],deleted[]}`;先删后插 + 墓碑 + updatedAt LWW)。服务端协议与之线兼容。

### 1.5 spider jar 基础设施(`CatVodTVSpider`)

`Init` 持 `appContext/filesDir`,经 `{baseUrl}/cookies/{secret}` 下发;持久化只能用文件/SharedPreferences(**无内置 DB**)。`Spider` 基类**无周期回调**,需 `init` 自建 `ScheduledExecutorService`(`destroy` 取消)+ `homeContent/playerContent` 机会触发;**spider 懒加载**,须保证尽早 init(未决)。上报行为模板=`Emby.java`;`Push.java` 是投屏控制非上报(易误判)。

### 1.6 AList-TvBox 服务端现状

已有完整 `History` 子系统(entity/repo/service/controller + `/api/history` + `/history/{token}` + `/tv/action` + `syncHistory` 原型)。用户 `x_user`,**uid = `SecurityContextHolder…getDetails()`**(仅 USER/ADMIN/session 有)。**隐患**:订阅令牌端点 `permitAll` 到达 `HistoryService` 时认证 null → `getDetails()` NPE;新方案 controller 内显式 token→uid 绕开。Flyway 当前最新 **V9**,下一个 **V10**。

### 1.7 atv-player(Python 桌面)

PySide6 + libmpv,`httpx` 单一 `ApiClient` 封装,已对接 TVBox API + `/api/*`。鉴权 = `Authorization: <token>`(裸 session 令牌)存 `~/.local/share/atv-player/app.db`。

**两层历史(关键)**:
- **Tier A**(默认 `use_local_history=True`,`browse/douban/pansou` 等)→ `PlayerController.report_progress` 末尾 `ApiClient.save_history` = `POST /api/history?log=false`。**服务端已在收,无需新增 PUSH。** 读取经 `GET /api/history`(list)、`GET /history/{vod_token}?key=`(单条续看)。
- **Tier B**(`spider_plugin/telegram/telegram_channel/bilibili/youtube/emby/jellyfin/feiniu/direct_parse`,`use_local_history=False` + 本地 saver 闭包)→ 只写本地 SQLite `media_playback_history`,**服务端从不看见**。

**本地 Tier-B schema**(`local_playback_history.py`,PK `(source_kind, source_key, vod_id)`):
`source_kind/source_key/source_name/vod_id/vod_name/vod_pic/vod_remarks(集名)/episode(索引)/episode_url/position(ms)/opening(ms)/ending(ms)/speed/playlist_index/source_group_index/source_index/source_subgroup_index/drive_dir_id/updated_at(ms epoch)` —— TVBox `History` 的忠实超集。

**现成可复用**:
- PUSH 瓶颈 `PlayerController.report_progress`(`player_controller.py:519`,payload 552–570 含全字段;由 5s `report_timer` + 退出 `closeEvent` 触发),经序列化后台 worker `_controller_task_queue` 执行。
- device id = `AppIdentity.installation_id`(uuid4+sha256,不可变,已用于 `HeatService`)。
- PULL 定时模板 = `FollowingUpdateService`(QTimer `singleShot` 初次 + `start(interval)`)。
- HTTP 客户端 = `ApiClient`(`_request` 含 base_url/Authorization/超时/代理/401 处理),直接扩展。

---

## 2. 总体方案

服务端**一份** history 存储(`history` 表,per uid),多入口:
- 入:**`POST /api/history`**(现有,atv-player Tier-A / 网页)+ **`POST /api/playback/event(s)`**(新,安卓 spider / atv-player Tier-B / Fish webhook)。
- 出:**`GET /api/history`**(现有)+ **`GET /api/playback/changes`**(新,游标增量 PULL)。
- 身份**泛化**为 `(uid, source_kind, source_key, vod_id)`;令牌解析**同时接受**新建 sync 令牌与现有 session 令牌。

- **安卓(全四款)**:spider 框架 `SQLiteDatabase` 直读宿主 `tv.db` `History` 表 → 差量上报;PULL 经本机 `/action?do=sync&mode=1` 注入回本地库。统一、零客户端改造。
- **atv-player**:Tier-A 已通;**Tier-B 补 PUSH**(`report_progress` saver 闭包处)+ **补 PULL**(`PlaybackHistorySyncService`);复用 `ApiClient` + session 令牌 + `installation_id`。
- **Fish/默**:自带 webhook(备选,零改造)。
- **网页端**:复用 `/api/history`。

---

## 3. 服务端协议设计(兼容 webhtv.playback.v1)

新路径族 `/api/playback/**` 加入 `WebSecurityConfiguration.permitAll`(令牌即鉴权,controller 内显式 token→uid)。

### 3.1 身份与多用户:令牌 → uid(双源)

- 新增表 `playback_token`:`(id, uid, token UNIQUE, name, created_time, last_used_at)`,用户网页端自助生成/吊销。
- 请求头 `X-PlaySync-Token`(兼容 `X-WebHTV-Token` / `Authorization: Bearer`)→ 解析顺序:① `playback_token` 表;② **`session` 表**(via `TokenService`)→ 使 atv-player / 网页端的 session 令牌直接可用,无需另发令牌。
- 解析得 uid → 全程按 uid 隔离。

### 3.2 PUSH(上报)

- **`POST /api/playback/event`**(单条,webhook 兼容)+ **`POST /api/playback/events`**(批量,spider/atv-player)。`Idempotency-Key`/`dedupeKey` 去重(Caffeine,TTL~24h)。
- body **双形态兼容**:① FongMi 原生 `History` JSON(`key/vodName/.../cid`);② webhtv `PlaybackRecord`;③ atv-player `media_playback_history` 形态(`sourceKind/sourceKey/sourceName/vodId/positionMs/...`)。`PlaybackSyncInput` DTO 别名集宽容解析;服务端统一抽出 `(source_kind, source_key, vod_id)`。
- 事件:`progress/ended` → upsert(LWW);`deleted` → 写墓碑+删行。返回任意 2xx。

### 3.3 PULL(下发)

`GET /api/playback/changes`,头 `X-PlaySync-Token / X-PlaySync-Since / X-PlaySync-Limit / X-PlaySync-Source-Kind`(可选过滤)。响应 `{nextSince, items[], deleted[]}`;游标=服务端分配的单调 `change_seq`;删先于插;整页无遗漏才推进 `nextSince`。`updatedAt/deletedAt` 只用于 LWW,不再承担分页游标。

**截断规则**:`items` 与 `deleted` 必须按 `change_seq` 合并成**同一条变更流**再截断 —— 两边各自截 `limit`、游标取两边最大值时,较新的墓碑会把未下发的 `items` 尾巴推过游标,而下次查询用 `GreaterThan`,那些记录永久丢失。带 `X-PlaySync-Source-Kind` 时墓碑同样按 `source_kind` 过滤,但 `scope=all` 的墓碑(`source_kind` 为空)对所有来源生效,必须一并下发。

### 3.4 记录身份与冲突解决(泛化)

- **可移植身份 = `(uid, source_kind, source_key, vod_id)`**:
  - spider/TVBox/Fish:`source_kind="site"`,`source_key=siteKey`,`vod_id=vodId`。
  - Python 插件:`source_kind="spider_plugin"`,`source_key=PLUGIN_ID`。原始 `.py` 的 `PLUGIN_ID`、加密 `.txt` 的 `//@id`、atv-player 的 `manifest_id` 与服务端 `Plugin.externalId` 必须相同；插件名称仅用于展示，可自由重命名。
  - atv-player Tier-B:`source_kind=source_kind`(如 `telegram/bilibili`),`source_key=source_key`,`vod_id=vod_id`。
  - atv-player Tier-A(旧路径):保留 `key` 粒度身份(`source_*` 空),与新路径共存(不强行跨源合并,镜像 FongMi 的分源历史)。
- `history` 加可空规范列 `source_kind/source_key/vod_id` + 索引 `(uid, source_kind, source_key, vod_id)`(仅新路径记录填充);`key` 列原样存。
- 集数降级匹配:`episodeUrl → flag+episodeName → episodeName → 命中首条`。
- **LWW by `updatedAt`**(缺省取 `createTime`):`updatedAt <= 行.createTime` 丢弃,否则覆盖并 `createTime = updatedAt`;刷新 `client_key/updated_at`。

### 3.5 删除与墓碑

新增表 `playback_tombstone`:`(id, uid, scope, source_kind, source_key, vod_id, history_key, deleted_at, change_seq, expire_at)`,索引 `(uid, source_kind, source_key)`,90 天保留。收 `deleted` → 写墓碑+删行;upsert 时 `updatedAt ≤ 墓碑.deleted_at` 拒绝复活;PULL `deleted[]` 按 `change_seq > since` 生成。

**作用域**:`scope` 决定删除范围,且**不是每种 scope 都带条目身份** —— `all` 清空该用户全部记录(无 `source_*`/`vod_id`)、`site` 清空某来源(无 `vod_id`)、`item`(默认)按 `(kind,key,vod_id)` 删单条。三者各自一行墓碑,互不覆盖;upsert 的防复活水位取 item ∪ site ∪ all 三者 `deleted_at` 最大值。

**删除也走 LWW**:墓碑只能挡住**后续**的过期 upsert,无法还原已删掉的行。故迟到的删除(`deleted_at` 早于本地 `updated_at`)只写墓碑、不删该行,否则一条 `deleted_at=100` 的删除会抹掉 `updated_at=200` 的新记录。

---

## 4. 数据模型(V10–V13)

**列扩展**(跨库,**Java 迁移** `current/V10__PlaybackSyncIdentity.java`,沿用 V8 的 `findColumn/findTable` 守卫):
`history` 增 `source_kind VARCHAR / source_key VARCHAR / source_name VARCHAR / vod_id VARCHAR / updated_at BIGINT / client_key VARCHAR(64)`(皆可空;新路径填充,旧记录留空);索引 `(uid, source_kind, source_key, vod_id)`(参考三份 `V1__Create_current_schema.sql` 的标识符/索引差异)。

**新表**(同迁移 `CREATE TABLE IF NOT EXISTS` 或每库 `V10__add_playback_sync.sql`):
```sql
CREATE TABLE IF NOT EXISTS playback_token (
  id INTEGER NOT NULL PRIMARY KEY, uid INTEGER NOT NULL,
  token VARCHAR(64) NOT NULL UNIQUE, name VARCHAR(128),
  created_time BIGINT NOT NULL, last_used_at BIGINT);
CREATE TABLE IF NOT EXISTS playback_tombstone (
  id INTEGER NOT NULL PRIMARY KEY, uid INTEGER NOT NULL,
  scope VARCHAR(16), source_kind VARCHAR, source_key VARCHAR, vod_id VARCHAR,
  history_key TEXT, deleted_at BIGINT NOT NULL, expire_at BIGINT NOT NULL);
CREATE INDEX idx_pb_tomb ON playback_tombstone (uid, source_kind, source_key, vod_id);
```
实体用 `@TableGenerator(allocationSize=1)`;新 DTO 包 `dto.playback` **必须**加入 `Main.java` 反射扫描列表,再 `mvn compile && java -cp target/classes cn.har01d.alist_tvbox.Main` 重生成 `reflect-config.json`。

V11 为 `history/playback_tombstone` 增加 `change_seq`,并新增单行表 `playback_change_sequence` 以悲观锁分配全局单调游标;旧数据用 `updated_at/deleted_at` 回填。V12 将两表的 `vod_id` 放宽为 `TEXT`(身份字段不能截断),同步索引相应保留 `(uid, source_kind, source_key)`。
V13 为已执行过 V10 的部署补充 `history.source_name`,保证新旧安装都能保留客户端来源显示名。

---

## 5. 客户端接入

### 5.1 安卓(全四款):同步 spider jar ★主路径

> **实现说明(2026-08-10 更正)**:未新增 `csp_PlaySync` 源。同步由 **`Init.java` 发起**——新增 `PlaybackSyncer` 单例 + `Init.startPlaybackSync(server, extend)`,从约 13 个 spider 入口(10 个 `writeCookies` 调用者 + `PyProxy.parseConfig`/`playerContent` + `PianDan.init`)注入,绕开 spider 懒加载时序。理由:单例跨 spider reload 复用、入口更早、不占用一个 builtin 源位。

- **(a) 配置**:`startPlaybackSync` 从站点 `extend` base64 JSON 取 `server` + `playbackToken`(缺一不启动),另取 `playbackSourceKind/Key/Name` 与订阅 `configUrl`。`synchronized` 幂等:身份(`server`+`token`+`configUrl`)未变则复用既有 syncer,变更则 `stop()` 旧 syncer 再建新。
- **(b) 调度**:`PlaybackSyncer.start()` 起 `ScheduledExecutorService`(`scheduleWithFixedDelay` 60s,daemon 线程,`AtomicBoolean busy` 防重叠);`stop()`/`destroy` 取消。靠上述多入口保证尽早启动。
- **(c) 读源**——框架 `SQLiteDatabase` 直读宿主 `tv.db`(抗混淆/改名):
  ```java
  File dir = Init.appContext.getDatabasePath("_").getParentFile();   // databases/
  SQLiteDatabase sql = SQLiteDatabase.openDatabase(new File(dir,"tv").getPath(), null, SQLiteDatabase.OPEN_READONLY);
  Cursor c = sql.rawQuery("SELECT \"key\", vodName, vodFlag, vodRemarks, episodeUrl, position, duration, createTime, cid FROM History", null);
  // siteKey/vodId = key.split("@@@")[0/1]；映射为 source_kind="site", source_key=siteKey
  ```
  宿主每 5s 刷 position → 60s 轮询近实时。退化:表/列名不符 → 轮询 `/media`(弱身份)。
- **(d) PUSH**:与 `Init.filesDir/playback_sync_snapshot.json`(key→{createTime,position})差分 → `POST {server}/api/playback/events`,头 `X-PlaySync-Token`,body=FongMi `History[]` 原生 JSON(服务端 §3.2 兼容)。
- **(e) PULL 回写**(桥接,零反射写库):`GET {server}/api/playback/changes`(头 token/since)→ `items[]` 转 FongMi `History[]` → `POST 127.0.0.1:{port}/action?do=sync&mode=1&type=history&targets=<History[]>`(宿主 `History.sync` 导入)。游标存 SharedPreferences。`/action`/`mode`/`targets`/端口 9978 皆字面量,抗混淆。
- **(f) 多用户**:各用户同步令牌写入 spider 配置 → 按 uid 隔离。

### 5.2 Fish WebHomeTV / 默影视(备选,零改造)

Webhook `URL=/api/playback/event` + 远端同步 `URL=/api/playback/changes`,Token=令牌。与 spider 二选一(同设备勿双源)。

### 5.3 atv-player(Python 桌面)

- **Tier-A 已通**(browse/douban/pansou 经 `POST /api/history`,session→uid)。**不动。**
- **Tier-B 补 PUSH**(`spider_plugin/telegram/bilibili/youtube/emby/jellyfin/feiniu/direct_parse`):
  - 在 `PlayerController.report_progress` 调本地 saver 处(`player_controller.py:571`)加一路:`payload` 增 `sourceKind/sourceKey/sourceName`(取自 `session.source_kind/source_key/source_display_name`)→ `ApiClient.push_playback_events([payload])` = `POST /api/playback/events`(复用 `Authorization` session 头 + `installation_id` 作 client_key)。
  - **节流 ≥30s**(5s tick 太频;worker 已序列化不阻塞 UI);`closeEvent` 最终 flush。
  - 或包 `app.py:2025–2244` 的各 `playback_history_saver` 闭包(每个已知 `source_kind` 字面量),免改 `PlayerController`。
- **Tier-B 补 PULL**:新增 `PlaybackHistorySyncService`(抄 `FollowingUpdateService` QTimer,5–15min + 启动一次)→ `ApiClient.pull_playback_records(since)` → 逐条 `LocalPlaybackHistoryRepository.save_history(...)` 合并,门控 `server.updated_at > local.updated_at`。启动点 `AppCoordinator.start`(`_ensure_vod_token` 后)。

### 5.4 网页端(AList-TvBox 自身)

复用 `/api/history`(session,已有 uid);令牌管理页用 session 即可。

---

## 6. 实现步骤

1. **V10 迁移**:`history` 加列;建 `playback_token/playback_tombstone`;`Main.java` 加 `dto.playback` 扫描 + 重生成 reflect-config。
2. **令牌 + 解析**:`PlaybackTokenService`(生成/校验/吊销/last_used_at)+ `/api/playback/tokens` CRUD;解析双源(playback_token ∪ session)。
3. **核心协议**:`PlaybackSyncInput/DeleteInput` DTO(三形态别名)、`PlaybackSyncService`(token→uid、去重、泛化身份 LWW、集数匹配、墓碑、游标)、`POST /api/playback/event(s)`、`GET /api/playback/changes`;`permitAll` + controller 内鉴权。
4. **墓碑清理**:接入现有调度(每日 06:00)。
5. **网页端**:令牌管理 UI(如需)+ 历史页;前端门 `npm run build`。
6. **atv-player Tier-B**:`ApiClient.push_playback_events/pull_playback_records` + `report_progress` saver 钩子(节流)+ `PlaybackHistorySyncService`。
7. **联调(Fish/默 备选通道)**:URL+令牌 实测 PUSH/PULL。
8. **同步 spider(主路径,jar)**:`Init.startPlaybackSync` + `PlaybackSyncer.java`(SQLiteDatabase 读 History/Config + 差量上报 + `/action` 回写 + 快照/游标 + resume ID 往返 playlistIndex),随 spring.jar 发布。从 ~13 个 spider 入口注入而非新增 csp 源。
9. **(可选)修隐患**:旧 `/history/{token}`、`/tv/action` 补 token→uid 消除 NPE。

---

## 7. 未决问题

> 已定:✅ 配置 = 站点 `extend` JSON;✅ 令牌 = 新建 `playback_token` 表(并兼容 session);✅ 安卓读源 = `SQLiteDatabase` 直读 `tv.db`;✅ 身份泛化 `(uid, source_kind, source_key, vod_id)`。

1. ~~**spider 尽早 init**~~ ✅ 定(2026-08-10):不走新 csp 源;由 `Init.startPlaybackSync` 从 ~13 个 spider 入口(`writeCookies` 调用者 + `PyProxy` + `PianDan`)注入,单例复用、尽早启动。
2. **tv.db schema 验证**:库文件名 `tv`、`History` 表/列名(`@SerializedName` 字面量)在 fongmi/fish/默/**OK影视(停更闭源混淆)** 是否一致?需真机 dump 确认;否则 OK 降级 `/media`。
3. **Tier-A↔Tier-B 跨源合并**:同一剧在 atv-player「browse」与 FongMi「某站点」分别产生不同 `source_kind` 记录 → 分行(默认)。是否需要"按 vod_name 跨源聚合展示"?(FongMi 局域网同步正是按 vodName 聚合;可选复用其 `shouldMerge` ±10min 规则。)
4. **删除回写**:一期仅双向 upsert 是否可接受?删除传播(墓碑→宿主/atv-player 本地删行)放二期?
5. **身份唯一约束**:`(uid, source_kind, source_key, vod_id)` 加 DB 唯一约束(V10 新记录去重),还是仅 service 层?
6. **`completed`/已看完**:加列支持筛选,还是 `position/duration≥0.95` 运行时算?
7. **匿名上报归属**:无令牌/匿名(旧 `/tv/action`)归 admin(uid=1)还是拒绝?
8. ~~**PULL 游标**:`updated_at` 截断值(简单,同毫秒边界遗漏)还是单调 `version/seq` 列(稳)?~~ ✅ 定:使用服务端单调 `change_seq`,并将 items+墓碑合并为单一变更流后截断(见 3.3)。
