# 虎牙直播弹幕协议逆向记录

> 2026-08-16。起因:`live/danmaku/HuyaDanmakuClient`(移植自 pure_live)连接后只能收到约 15 秒弹幕就停止推送。

## 1. 结论先行

pure_live 那套虎牙协议**已对当前虎牙部分失效**:注册能成功、能收到进房瞬间的几条弹幕,之后服务端不再推送。虎牙网页版早已换用另一套协议。

已排除的假设(都做过 A/B 对照,见 `HuyaProbeTest`):

| 假设 | 结论 |
|---|---|
| 心跳间隔太长(60s) | 否。30s / 10s / 首个心跳立即发,均无改善 |
| 心跳包字节抄错 | 否。与 pure_live 逐字节相同 |
| 心跳包内 lTid/lSid 是死值(偏移 79/84 = 61796367) | 属实但无关。换成本房间频道号后反而收不到 |
| 缺 WS 层心跳(命令 5) | 否。发了更差 |
| 周期性重发进房包 | 否。直接归零 |
| 缺 `Origin` 头 | 否。无变化 |
| 本机 IP 被限流 | 部分成立(连续测试推送量单调下降),但**不是主因** —— 用户在另一个 IP 同样复现 |

对照数据(同一时段、同一房间、70 秒):

```
original(pure_live 固定心跳)  frames=11 danmaku=7   到达时刻 1s 3s 6s 9s 9s 14s 14s ← 之后全停
patched(换成本房间频道号)     frames=1  danmaku=0
wscmd5(WS 层心跳)            frames=1  danmaku=0
none(完全不发心跳)            frames=4  danmaku=2   1s 2s
```

## 2. 网页版实际使用的协议

抓包来源:未登录状态直接打开 `https://www.huya.com/660002`(主播 uid `1571877666`)。
信令库 `https://fedlib.msstatic.com/fedbasic/huyabaselibs/taf-signal/taf-signal.global.0.1.2.prod.js`。

### 2.1 连接

```
wss://ded35397-ws.va.huya.com/?baseinfo=<base64(Tars) 再 URL 转义>
```

host 由服务端下发的 IP 列表决定,但 JS 里保留了三个常量,`cdnws.api.huya.com` 仍可连:

```js
DEBUG_IP   = "testws.va.huya.com"
DEFAULT_IP = "ws.api.huya.com"
CDN_IP     = "cdnws.api.huya.com"
this.url = this.wsProtocol + r + this.baseinfo   // r 取自 wsIps / httpWs
```

`baseinfo`(Tars 编码 → base64 → `encodeURIComponent`):

```
tag0  ZERO
tag1  string  guid,32 位小写 hex(随机)
tag2  string  "webh5&2608121011&websocket"
tag3  string  "HUYA&ZH&2052"
tag4  string  ""
tag5  string  "44299.76949,57293.104883"
tag6  ZERO
tag7  string  ""
tag8  string  ""
tag9  string  ""
tag10 map     { "HUYA_NET": "0", "HUYA_VSDKUA": "webh5&2608121011&websocket" }   // key 用 tag0,value 用 tag1
```

### 2.2 WS 帧外层

```
tag0 int      命令码
tag1 bytes    payload
tag2 ZERO
tag3 string   形如 "c1b0a8cd2b27807e:c1b0a8cd2b27807e:0:0"(trace,疑似可选)
tag4 ZERO
tag5 ZERO
tag6 string   32 位 hex(疑似可选)
```

命令码:

| 值 | 含义 |
|---|---|
| 1 | RegisterReq(**旧**协议进房,现在只能收到十几秒) |
| 2 | RegisterRsp |
| 3 | WupReq(通用 RPC) |
| 4 | WupRsp |
| 7 | MsgPushReq(推送,uri 1400=弹幕 / 8006=在线人数,**格式没变**) |
| 22 | RegisterGroupReq(**订阅消息组**) |

### 2.3 Wup 包(命令 3 的 payload)

前置 4 字节大端整包长(含自身),其后 Tars:

```
tag1  byte    iVersion = 3
tag2  ZERO    cPacketType
tag3  ZERO    iMessageType
tag4  byte    iRequestId(递增)
tag5  string  sServantName
tag6  string  sFuncName
tag7  bytes   sBuffer = UniAttribute
tag8  ZERO    iTimeout
tag9  map{}   context
tag10 map{}   status
```

`sBuffer` 是 UniAttribute:`map{ "tReq": <bytes> }`(size 与 key/value 都带 head,key 用 tag0、value 用 tag1)。

**关键陷阱**:`tReq` 的 bytes 里**还要再包一层 struct(tag0)**,里面 tag0 才是 `tId`。少这一层服务端回:

```
STATUS_RESULT_DESC = "read 'struct' type mismatch, tag: 0, get type: 12.;"
```

### 2.4 网页建连后的调用序列(同一条 WS)

| # | servant.func |
|---|---|
| 1 | `huyaliveui.getLivingInfo` |
| 2 | `launch.wsTimeSync` |
| 3 | `hypcdngw.clientQueryPcdnSchedule` |
| 4 | `mediaui.getStreamInfoByRoomFake` |
| 5 | `launch.wsTimeSync` |
| 6 | `huyaliveui.getLivingMultiStreamInfo` |
| 7 | `hypcdngw.onClientGetStunServerInfo` |
| 8 | `presenteruid.getPresenterLiveScheduleInfo` |

`getLivingInfo` 的 `tReq`(外层已按 2.3 包一层 struct):

```
tag0 struct tId:
     tag0 ZERO       lUid = 0(未登录)
     tag1 string     sGuid,与 baseinfo 同一个
     tag2 string     ""
     tag3 string     "webh5&2608121011&websocket"
     tag4 string     sCookie(浏览器 cookie 全文;未登录时只有 guid 等匿名标识)
     tag5 ZERO
     tag6 string     "chrome"
     tag7 string     ""
tag1 ZERO
tag2 ZERO
tag3 int     主播 uid(= profileRoom 的 data.profileInfo.uid)
tag4 string  ""
tag5 string  ""
tag6 ZERO
tag7 ZERO
tag8 byte    1
```

`launch.wsTimeSync` 的 `tReq`:`struct{ tag0 string guid, tag1 short }`。

### 2.5 订阅(命令 22)——弹幕的关键

payload:

```
tag0 string  组名
tag1 list<struct{ tag0 short/int, tag1 bytes(内含主播 uid + 昵称), tag2 long }>
```

抓到两个组:

```
live:1571877666
chat:1571877666-aibarrage-5a5f5e987cf211e12b0eb0c554d44181
```

`chat:` 组才是弹幕。后缀 `-aibarrage-<32hex>` 来源未确认,推测在 `getLivingInfo` 的响应里。
`tag1` 里带主播昵称,说明这些数据取自 `getLivingInfo` 的响应 —— 即**订阅依赖进房响应**。

## 3. 当前卡点

`getLivingInfo` **始终收不到响应**(包格式已正确 —— 服务端不再报 struct 类型错;同一连接上 `launch.wsTimeSync` 能拿到正常 cmd4 响应)。拿不到响应就拿不到订阅所需数据。

已排除的变量:

- guid:随机生成 vs 抓包里的真实 guid,表现相同
- host:`cdnws.api.huya.com`(能回 wsTimeSync)vs 抓包里的 `ded35397-ws.va.huya.com`(已过期,0 帧)
- 订阅帧:原样重放抓包的命令 22 帧(不含 guid/cookie,不绑会话),无效
- cookie:空 / 最小 `guid=...`,均无 getLivingInfo 响应

下一步方向:

1. 拿到 `getLivingInfo` 的**响应帧**(抓包),确认 `-aibarrage-` 后缀与订阅 `tag1` 的数据来源
2. 或者确认 `getLivingInfo` 静默失败的原因(可能 sCookie 有必填项,或 WS 帧外层 tag3/tag6 那两个 hash 是必需的签名)
3. 若代价过高,可考虑放弃虎牙实时弹幕

## 4. 诊断工具(均 `@EnabledIfSystemProperty`,默认不跑)

```bash
# 旧协议心跳 A/B 对照
mvn -o test -Dtest=HuyaProbeTest -Dhuya.probe=1 \
    -Dhuya.uid=1571877666 -Dhuya.mode=original -Dhuya.seconds=70
# mode: original | patched | wscmd5 | both | none

# 原样重放抓包帧(/tmp/huya_new.txt:第 1 行 URL,其后每行一个 base64 帧)
mvn -o test -Dtest=HuyaReplayTest -Dhuya.replay=1

# 自建新协议(baseinfo + getLivingInfo + wsTimeSync + 订阅)
mvn -o test -Dtest=HuyaNewProtocolTest -Dhuya.new=1 -Dhuya.uid=1571877666 \
    [-Dhuya.host=...] [-Dhuya.guid=...] [-Dhuya.cookie=...] [-Dhuya.subfile=/tmp/huya_sub.txt]
```

`HuyaNewProtocolTest` 内含一个最小 Tars 编码器 `W`(比主代码的 `TarsWriter` 多了 map/struct),
新协议若跑通,应把它并回 `TarsWriter`。

## 5. 主代码现状

虎牙**未做任何改动**,仍是 pure_live 那套(表现为进房后十几秒有弹幕、之后停)。
本轮所有实验改动均已撤回。斗鱼 / B站 / 抖音不受影响。
