# Telegram Bot 追剧助手 — 设计与实现

状态:已实现(2026-08-30)。Bot 层零业务逻辑,全部复用既有追剧 Service;零数据库迁移、零新依赖。

## 1. 功能

用户在 Telegram 里与 Bot 私聊(需已配置通知渠道):

- `/start` `/help` 主菜单;`/subs` 我的订阅;`/search` 搜索追剧
- 我的订阅:分页浏览 → 订阅详情(状态/进度/缺集/下集播出/简介)
- 搜索:输入剧名 → 全源元数据搜索(豆瓣/TMDB/Bangumi 聚合)→ 条目详情 → ➕ 加入追剧(幂等)
- 退订(两步确认)、⏸/▶ 暂停恢复、⚡ 查更新(轻量秒回)、🔄 巡检(异步,结果走既有 outbox TG 通知)
- 🔄 最近更新:近 3 天新集/换源/补缺事件

## 2. 与参考设计的差异(裁剪理由)

| 参考 | 本实现 | 理由 |
|---|---|---|
| Pengrad Bot SDK | 裸 HTTP(`TelegramBotClient`) | 项目 `callTelegram` 先例;避免新依赖+GraalVM 反射负担 |
| yaml `telegram.bot.*` | Setting 表既有键 | 项目配置惯例;已配通知的用户零额外配置 |
| `telegram_user` 绑定表 | 复用用户级 Setting `msub_telegram_chat_id:u{uid}` 反查 | 零迁移;与通知配置同源 |
| Redis 会话 | Caffeine 内存 | 单实例部署口径 |
| Facade 层 | 不需要 | `MediaSubscriptionService` 方法第一参数即 uid,归属校验 `getOwned` 内建 |

## 3. 架构

```
api.telegram.org ←→ TelegramBotClient(HTTP: getUpdates/sendMessage/editMessageText/answerCallbackQuery/setMyCommands)
                        ↑
   TelegramBotService(守护线程长轮询:offset 推进/启动跳过积压/token 热切换/409 退避;2 线程处理池)
                        ↓
   TelegramUpdateRouter(绑定解析 → 会话/限流 → answer 收口)
                        ↓
   TelegramSubscriptionBot(编排)→ MediaSubscriptionService / MediaSubscriptionCheckService
   TelegramRenderer(纯函数:HTML 文本+键盘)+ TelegramCallbackData(编解码)
```

包:`cn.har01d.alist_tvbox.telegram`(仿 live/ 独立模块先例);DTO 在 `dto/telegram/`(snake_case `@JsonProperty`)。

## 4. 关键决策

- **身份绑定**:私聊 chat.id 与 `msub_telegram_chat_id:u{uid}` 值匹配 → 该 uid;全局值匹配 → id 最小 ADMIN(共享 token=管理级口径,与 `resolveUid` 回落一致)。60s Caffeine 缓存,网页改配置最迟一分钟生效。未绑定 chat:仅命令触发引导(回显 Chat ID 指引去网页填写),其余静默。
- **启用条件**:Setting `msub_telegram_bot_enabled`(默认 true,追剧设置对话框有开关)+ 全局 `msub_telegram_bot_token` 非空。入站只消费全局 token;用户级 token 仅用于各自通知出站,互不干扰(sendMessage 与 getUpdates 不冲突,同 token 仅 getUpdates 消费者互斥,单实例单循环)。
- **积压跳过**:启动/换 token 后首批 update 只推进 offset 不执行——离线期间的旧命令重放只会制造困惑。换 token 时 offset 归零(TG 按 token 隔离 update 流)。
- **消息策略**:callback 一律 `editMessageText` 复用同一条消息;新消息仅命令/搜索提示/绑定引导。编辑目标失效(消息被删/超龄)自动降级 sendMessage;`message is not modified` 吞掉(口径同通知服务)。
- **answer 收口**:每个 callback 必被 `answerCallbackQuery`(Router 统一),防客户端转圈;toast 文案由 bot 返回值携带。
- **callback data**:`action:arg` 全部 <20 字节(TG 限 64);搜索结果本体 Caffeine 暂存(chatId→items,10min),callback 只带索引——过期统一引导重搜。
- **安全**:外部文本(剧名/简介/事件明细)全量 HTML escape;越权(别人的 sub id)在 `getOwned` 处抛 BadRequestException,Router 统一转 notFound 文案;关键词限长 100;每 chat 搜索 3s 冷却;token 不入日志。
- **幂等**:加入追剧走 `create` 语义匹配幂等 + `isSubscribedTitle` 预检(已订直接提示);退订/暂停重复调用无害;update 重复投递(at-least-once)安全。

## 5. Setting 键

| 键 | 语义 |
|---|---|
| `msub_telegram_bot_token`(全局) | 入站轮询 + 通知回退的 bot token |
| `msub_telegram_chat_id`(全局/用户级) | 通知目标;同时是 Bot 身份绑定的数据源 |
| `msub_telegram_bot_enabled`(全局,默认 true) | Bot 交互总开关(只收通知可关) |

## 6. 测试

`src/test/java/cn/har01d/alist_tvbox/telegram/`:CallbackData 编解码、Renderer(转义/分页/空态/截断)、Client(MockRestServiceServer:offset/parse_mode/not-modified 吞/错误上抛/answer 截断)、Router(绑定解析三态/命令路由/搜索会话/限流/越权 notFound/toast 透传)、Bot(create 直绑参数/幂等/退订确认/编辑降级)。

## 7. 未做(后续候选)

- 新集通知卡片加 inline 按钮(需要通知服务与 Bot client 合并出站路径)
- TMDB 多季在 Bot 内按季展开按钮(当前走 create 内建 `resolveSeason`)
- 绑定码流程(当前靠网页填 Chat ID,家庭场景够用)、群组 Bot、Webhook
