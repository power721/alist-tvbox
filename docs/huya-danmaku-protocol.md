# 虎牙直播弹幕协议

> 2026-08-16。起因:`live/danmaku/HuyaDanmakuClient`(原移植自 pure_live)连接后只能收到约 15 秒弹幕就停止推送。
> **已解决**,本文记录当前协议与两个坑。

## 1. 结论

两个独立问题叠在一起:

1. **pure_live 那套协议已失效**。`wss://cdnws.api.huya.com` + 命令 1 `RegisterReq` 进房:注册仍返回成功、
   也会推十几秒弹幕,之后服务端就不再推送,且与心跳无关。网页版早已换成 `wsapi.huya.com` 上的
   **命令 16 订阅消息组**。
2. **OkHttp 强制协商 `permessage-deflate`**。换到新协议后仍然只能收约 2 秒:OkHttp 在
   `RealWebSocket.connect()` 里无条件加 `Sec-WebSocket-Extensions: permessage-deflate`,虎牙接受该扩展
   (回 `server_no_context_takeover;client_no_context_takeover`),但握手后约 2 秒就彻底停推 —— 此时连
   WS ping 都不回 pong,而连接还开着。去掉该扩展即可持续收弹幕。

对照数据(同一房间、同一时间窗、45 秒):

| 客户端 | 扩展 | 帧数 | 弹幕 |
|---|---|---|---|
| OkHttp 默认 | permessage-deflate | 2~4 | 0~1(只在第 0 秒) |
| OkHttp 去扩展 | 无 | 197 | 128(全程均匀) |
| JDK `java.net.http.WebSocket`(不支持该扩展) | 无 | 167 | 81(全程均匀) |
| Node undici `WebSocket` | 无 | — | 88(全程均匀) |

**坑**:OkHttp 对 WebSocket 调用**不执行 network interceptor**
(`RealCall.getResponseWithInterceptorChain()` 里 `if (!forWebSocket) interceptors += client.networkInterceptors`),
必须用 application interceptor 才能改掉这个请求头。

## 2. 当前协议

### 2.1 连接

```
wss://wsapi.huya.com/?baseinfo=<base64(Tars) 再 URL 转义>
```

`baseinfo`:

```
tag0 ZERO
tag1 string  guid,32 位小写 hex;可随机生成,也可留空(留空时 launch.wsLaunch 会下发一个)
tag2 string  "webh5&2608121011&websocket"
tag3 string  "HUYA&ZH&2052"
tag4~9       空串 / ZERO
tag10 map    可省略
```

主播 uid 取 `https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=<房间号>` 的
`data.profileInfo.uid`(即 `HuyaService.getAyyuid()`)。房间 660527 → `1199565822350`。

### 2.2 WS 帧外层

```
tag0 int     命令码
tag1 bytes   payload
tag2~6       可选(网页会带,服务端不要求)
```

命令码:

| 值 | 方向 | 含义 |
|---|---|---|
| 1 | → | RegisterReq(**旧**进房,已半废弃) |
| 3 / 4 | ↔ | WupReq / WupRsp(通用 RPC) |
| 7 | ← | MsgPushReq,单条推送 |
| 16 | → | RegisterGroupReq,**订阅消息组** |
| 17 | ← | 订阅结果,`{tag0 int 错误码(0=成功), tag1 list<string> 组名}` |
| 22 | ← | 群组批量推送 |
| 33 | → | 网页版每 30 秒发一次的保活(**非必需**) |

### 2.3 订阅(命令 16)—— 唯一必需的请求

payload:

```
tag0 list<string>  组名:["live:<主播uid>", "chat:<主播uid>"]
tag1 string        ""
```

**不需要** `getLivingInfo`、`wsLaunch`、`doLaunch` 等任何 RPC 打底,连上直接发就能订阅成功。
组名也**不需要** `-aibarrage-<hash>` / `-caption-<hash>` 后缀(那是 AI 字幕组,另一回事)。

实际字节(房间 660527):

```
00 10                                          cmd = 16
1d 00 00 2d                                    tag1 SimpleList,长 45
   09 00 02                                    tag0 LIST,2 个元素
   06 12 "live:1199565822350"
   06 12 "chat:1199565822350"
   16 00                                       tag1 空串
```

### 2.4 推送

**命令 7**(单条):

```
tag0 int    pushType
tag1 int    uri
tag2 bytes  消息体
```

**命令 22**(按组批量):

```
tag0 string             组名
tag1 list<struct{
        tag0 int    uri
        tag1 bytes  消息体
        tag2 long   msgId
     }>
```

两者的 uri 含义相同:

| uri | 含义 |
|---|---|
| 1400 | 弹幕 |
| 8006 | 在线人数 |
| 6111 | 用户进房 |
| 6892 | 贡献榜 |
| 6501 / 2100000 / … | 礼物、活动等 |

uri 1400 的消息体(与旧协议**没变**):

```
tag0 struct  发送者 { tag0 uid, tag2 昵称, tag4 头像 }
tag3 string  弹幕内容
tag6 struct  格式 { tag0 字体颜色(ARGB,-1 = 默认白) }
```

## 3. 不要再查的两条岔路

- **`wss://<十进制IP>-server.va.huya.com:<端口>/`**:是 PCDN / P2P 分发,不是弹幕。
  主机名前缀是 IPv4 的十进制编码(`2773734366` = `165.83.211.222`);收到的大帧是**对端列表**
  (每项 = 4 字节 IP + 若干 id + `"chrome"`/`"firefox"`/`"safari"`),发送帧里有客户端自己的公网 IP。
  抓包里的 `hypcdngw.clientQueryPcdnSchedule` / `onClientGetStunServerInfo` 就是在给它调度节点。
- **`wss://<hash>-ws.va.huya.com/`**:`liveui.doLaunch` 下发的备用接入点,有时效;`wsapi.huya.com` 长期可用。

已排除过的假设(旧协议时期,都做过 A/B):心跳间隔、心跳包字节、包内写死的 lTid/lSid、
WS 层命令 5 心跳、周期性重发进房包、缺 `Origin` 头、本机 IP 限流 —— 均非主因。

## 4. 抓包方法

环境里有 `google-chrome`,用 headless + CDP 直接抓,不必手工导出:

```bash
google-chrome --headless=new --disable-gpu --mute-audio --no-first-run \
  --user-data-dir=/tmp/huya-chrome --remote-debugging-port=9222 about:blank &
```

再用 Node(v22+ 有全局 `WebSocket`,零依赖)连 CDP:`Target.setAutoAttach`(`flatten: true`,
`waitForDebuggerOnStart: true`)→ 每个 session 上 `Network.enable` → 收
`Network.webSocketCreated` / `webSocketFrameSent` / `webSocketFrameReceived`。
二进制帧的 `payloadData` 是 base64。虎牙的 WS 建在 Worker 里,**必须**开 autoAttach 才抓得到。

> 抓包会带出浏览器 cookie(`udb_*`、`sdid`、`guid` 等)。落盘只放 `/tmp`,别写进仓库,用完删掉。

## 5. 探针

```bash
# 主播 uid
curl -s 'https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=660527' | jq .data.profileInfo.uid

# 用真实房间驱动 HuyaDanmakuClient,每 15 秒报一次,便于看出中途是否停推
mvn -o test -Dtest=HuyaProbeTest -Dhuya.probe=1 -Dhuya.uid=1199565822350 -Dhuya.seconds=180
```

实测 180 秒 → 388 条弹幕、9 次在线人数更新,全程不断。
