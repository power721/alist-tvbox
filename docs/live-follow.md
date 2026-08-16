# 关注直播间

用户可以关注任意平台(虎牙/斗鱼/B站/网易CC/快手/抖音)的直播间,关注数据按用户存储在后端,TVBox 端与 web 端实时共享。

## 展示

- **TVBox 网络直播首页**:推荐列表最前面插入关注中的直播间(开播优先)。
- **"关注"分类**:分类列表第一位为"关注"(type_id=`follow`),展示全部关注房间,开播的排前面,`vod_remarks` 显示"平台 · 人气/未开播"。
- **web 管理端**:直播页新增"关注管理"页签(`/live/manage`),可查看状态、观看、取关。

## 关注操作

TVBox 无原生"关注"按钮,采用播放轨道约定:直播间详情页(`GET /live/{token}?ids={platform}${roomId}`)会在最后一个播放组追加"关注"组,组内固定两个选集(后端幂等):

- `关注$follow$平台$房间号`
- `取消关注$unfollow$平台$房间号`

组名按当前状态显示"关注"/"已关注"。播放器详情页打开后不会重新拉取(状态文字会过期),因此两个操作常驻,关注后可立即取关。

spider.jar(`csp_Live`)的 `playerContent` 拦截 `follow$` / `unfollow$` 前缀的 id,POST 到后端完成关注/取关。响应为带缩进的 JSON(`"success" : true`),spider 端解析 JSON 判断结果(不能子串匹配)。成功后 spider 再调详情接口取第一播放组的流地址直接返回给播放器,**恢复播放状态**;同时用 Android 原生 Toast 弹"已关注/已取消关注"(spider 运行在播放器进程内,经主线程 Handler + application context 弹出)。

注意:**FongMi 系播放器不支持 `toast://` 协议**(会被当普通播放地址),`msg` 字段是错误消息通道(阻断播放),只用于失败或未开播等无法恢复播放的场景。取不到流地址(如已下播)时返回 `{"jx":0,"parse":0,"msg":"已关注,直播间未开播"}`。web 端 LiveView 对同一 episode 调用管理接口。

关注时后端调用平台 `detail` 自动补全房间名/主播/封面(虎牙封面 URL 的 `\u002F` 转义会归一化),列表刷新时也会顺带更新房间名。

## 接口

内容接口(订阅 token 鉴权,TVBox/web 共用):

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/live/{token}?t=follow` | 关注列表(MovieList,开播在前) |
| POST | `/live/{token}/follow` | body `{"platform":"huya","roomId":"11342412"}` |
| POST | `/live/{token}/unfollow` | body 同上 |

无 token 变体 `/live/follow`、`/live/unfollow` 同样存在。以上路径在 Spring Security 中 permitAll,由订阅 token 把关。

管理接口(登录态,ADMIN|USER,数据按当前用户):

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/live/follows` | 关注列表(含 live 状态) |
| POST | `/api/live/follows` | body `{"platform":"...","roomId":"..."}` |
| DELETE | `/api/live/follows?platform=...&roomId=...` | 取消关注 |

## 身份归属

与播放记录同步一致:订阅 token 是已存在的用户名 → 归属该用户;共享订阅 token 或空 token → 归属首个管理员。多用户各自配置用户名 token 即可隔离关注列表。

## 开播状态刷新

`LiveFollowService.list/listDto` 并行调用各平台 `detail` 刷新开播状态(判定标准:返回的 `vod_play_url` 非空),每房间 Caffeine 缓存 2 分钟,总超时 8 秒;单个平台失败降级为已存元数据(状态"未开播")。刷新线程会传播请求上下文(`RequestAttributes`),以支持虎牙基于当前请求构造代理 URL。

## 存储

`live_follow` 表(Flyway `V19__LiveFollow`,跨库幂等 Java 迁移):`uid + platform + room_id` 唯一,存房间名/主播/封面/关注时间。Native image 需同步注册:`META-INF/services/org.flywaydb.core.api.migration.JavaMigration` 与 `config/NativeFlywayMigrationConfig.java`(已注册)。

spider 端实现见 CatVodTVSpider 仓库 `com.github.catvod.spider.Live`,`build.sh` 构建后自动拷贝 `spring.jar`/`spring.md5` 到本项目 `src/main/resources/static/`。
