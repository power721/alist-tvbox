package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.InflaterInputStream;

/**
 * B站直播弹幕客户端(移植 pure_live BiliBiliDanmaku)。
 * 大端包:packetLen(4) + headerLen(2,=16) + protover(2,0=JSON/2=zlib/3=brotli) + operation(4) + sequence(4)。
 * operation:2=心跳,3=人气回应(已弃用,恒为1),5=通知,7=进房,8=进房回应。
 * 实时人气(房间接口 online 字段,与详情页同口径)走 HTTP 轮询,心跳回应里已拿不到。
 */
@Slf4j
public class BilibiliDanmakuClient extends AbstractDanmakuClient {
    private static final int OP_HEARTBEAT = 2;
    private static final int OP_MESSAGE = 5;
    private static final int OP_AUTH = 7;
    private static final int OP_AUTH_REPLY = 8;
    private static final int HEADER_LEN = 16;
    private static final long ONLINE_INTERVAL = 30_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BiliDanmakuArgs args;
    // 基类 scheduler 为 private,子类留存同一实例供人气轮询任务使用
    private final ScheduledExecutorService scheduler;
    // 共享 OkHttpClient 的 readTimeout 为 0(WS 用),HTTP 轮询复用连接池但要有界
    private final OkHttpClient httpClient;
    private final Request onlineRequest;
    private ScheduledFuture<?> onlineTask;

    public BilibiliDanmakuClient(BiliDanmakuArgs args, OkHttpClient okHttpClient, ScheduledExecutorService scheduler) {
        super("bili-danmaku", "wss://" + args.host() + "/sub",
                args.cookie().isEmpty() ? Map.of() : Map.of("Cookie", args.cookie()),
                60_000, okHttpClient, scheduler);
        this.args = args;
        this.scheduler = scheduler;
        this.httpClient = okHttpClient.newBuilder().readTimeout(5, TimeUnit.SECONDS).build();
        Request.Builder builder = new Request.Builder()
                .url("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + args.roomId())
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://live.bilibili.com/");
        if (!args.cookie().isEmpty()) {
            builder.header("Cookie", args.cookie());
        } else {
            // 裸请求过不了风控(-352),游客身份补随机 buvid3,与 BilibiliService.buildHttpEntity 同格式
            builder.header("Cookie", "buvid3=" + UUID.randomUUID()
                    + ThreadLocalRandom.current().nextInt(10000, 99999) + "infoc");
        }
        this.onlineRequest = builder.build();
    }

    public record BiliDanmakuArgs(long roomId, long uid, String token, String buvid, String host, String cookie) {
    }

    @Override
    public void start() {
        super.start();
        // B站心跳回应的人气值已弃用(登录/游客都恒为1),改为轮询 get_info 的 online(详情页同口径)
        onlineTask = scheduler.scheduleWithFixedDelay(this::fetchOnline, 3, ONLINE_INTERVAL, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (onlineTask != null) {
            onlineTask.cancel(false);
            onlineTask = null;
        }
        super.stop();
    }

    private void fetchOnline() {
        try (Response response = httpClient.newCall(onlineRequest).execute()) {
            JsonNode body = MAPPER.readTree(response.body().byteStream());
            long online = body.path("data").path("online").asLong(0);
            if (online > 0) {
                emit(LiveDanmaku.online(String.valueOf(online)));
            }
        } catch (Exception e) {
            log.debug("fetch bili online failed: room={}", args.roomId(), e);
        }
    }

    @Override
    protected void onConnected(WebSocket ws) {
        Map<String, Object> join = new HashMap<>();
        // uid 必须与请求头里 Cookie 的登录身份一致(token 也是按该身份签发的)。
        // 带着登录 Cookie 却报 uid=0 会被服务端直接断开(表现为 connected 后立刻 EOFException)。
        join.put("uid", args.uid());
        join.put("roomid", args.roomId());
        join.put("protover", 3);
        join.put("buvid", args.buvid());
        join.put("platform", "web");
        join.put("type", 2);
        join.put("key", args.token());
        try {
            send(encode(MAPPER.writeValueAsString(join), OP_AUTH));
        } catch (IOException e) {
            log.error("serialize join data failed", e);
        }
    }

    @Override
    protected byte[] heartbeatMessage() {
        return encode("", OP_HEARTBEAT);
    }

    static byte[] encode(String body, int operation) {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        byte[] buffer = new byte[data.length + HEADER_LEN];
        writeInt(buffer, 0, buffer.length);
        buffer[4] = 0;
        buffer[5] = HEADER_LEN;
        buffer[6] = 0; // protover 发送固定 0
        buffer[7] = 0;
        writeInt(buffer, 8, operation);
        writeInt(buffer, 12, 1);
        System.arraycopy(data, 0, buffer, HEADER_LEN, data.length);
        return buffer;
    }

    private static void writeInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) ((value >> 24) & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 3] = (byte) (value & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    @Override
    protected void handleMessage(byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace("bili ws frame: {} bytes, first16: {}", data.length,
                    java.util.HexFormat.of().formatHex(java.util.Arrays.copyOfRange(data, 0, Math.min(16, data.length))));
        }
        // 一个帧可粘多个包,按 packetLen 切分
        int offset = 0;
        while (offset + HEADER_LEN <= data.length) {
            int packetLen = readInt(data, offset);
            if (packetLen < HEADER_LEN || offset + packetLen > data.length) {
                break;
            }
            int protover = readShort(data, offset + 6);
            int operation = readInt(data, offset + 8);
            byte[] body = new byte[packetLen - HEADER_LEN];
            System.arraycopy(data, offset + HEADER_LEN, body, 0, body.length);
            // 心跳回应(op=3)的人气值已弃用恒为 1,不解析;人气走 fetchOnline 轮询
            if (operation == OP_AUTH_REPLY) {
                // 进房回应 {"code":0};非 0 表示被拒(uid/key/buvid 与 Cookie 身份不匹配等)
                String reply = new String(body, StandardCharsets.UTF_8);
                if (!reply.contains("\"code\":0")) {
                    log.warn("B站弹幕进房被拒: room={} uid={} reply={}", args.roomId(), args.uid(), reply);
                }
            } else if (operation == OP_MESSAGE) {
                byte[] raw = body;
                if (protover == 2) {
                    raw = inflate(new InflaterInputStream(new ByteArrayInputStream(body)));
                } else if (protover == 3) {
                    try {
                        raw = inflate(new BrotliInputStream(new ByteArrayInputStream(body)));
                    } catch (IOException e) {
                        log.debug("brotli decompress failed", e);
                    }
                }
                parseMessages(new String(raw, StandardCharsets.UTF_8));
            }
            offset += packetLen;
        }
    }

    private static byte[] inflate(InputStream in) {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private void parseMessages(String text) {
        for (String item : text.split("[\\x00-\\x1f]+")) {
            if (item.length() > 2 && item.startsWith("{")) {
                parseMessage(item);
            }
        }
    }

    private void parseMessage(String json) {
        try {
            JsonNode obj = MAPPER.readTree(json);
            String cmd = obj.path("cmd").asText("");
            if (cmd.contains("DANMU_MSG")) {
                JsonNode info = obj.path("info");
                if (!info.isArray() || info.isEmpty()) {
                    return;
                }
                String message = info.path(1).asText("");
                String userName = info.path(2).path(1).asText("");
                if (!message.isEmpty()) {
                    long color = info.path(0).path(3).asLong(0);
                    emit(LiveDanmaku.chat(userName, message, color == 0 ? null : String.format("#%06X", color & 0xFFFFFF)));
                }
            }
        } catch (IOException e) {
            log.debug("parse bili danmaku json failed", e);
        }
    }
}
