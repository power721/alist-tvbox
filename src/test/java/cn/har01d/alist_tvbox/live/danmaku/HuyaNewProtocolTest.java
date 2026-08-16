package cn.har01d.alist_tvbox.live.danmaku;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 虎牙 webh5 新协议探针(诊断用,默认不跑):自建 baseinfo + WupReq(huyaliveui.getLivingInfo),
 * 验证不带浏览器 cookie、用随机 guid 能否让服务端持续推送弹幕。
 * <pre>
 * mvn -o test -Dtest=HuyaNewProtocolTest -Dhuya.new=1 -Dhuya.uid=1571877666 -Dhuya.seconds=70
 * </pre>
 */
@EnabledIfSystemProperty(named = "huya.new", matches = "1")
class HuyaNewProtocolTest {

    private static final String HUYA_UA = "webh5&2608121011&websocket";

    /** 最小 Tars 编码器(TarsWriter 没有 map,这里自带一份,验证通过再并回主代码)。 */
    private static final class W {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void head(int type, int tag) {
            if (tag < 15) {
                out.write((tag << 4) | type);
            } else {
                out.write((15 << 4) | type);
                out.write(tag);
            }
        }

        void num(long n, int tag) {
            if (n == 0) {
                head(12, tag);
            } else if (n >= -128 && n <= 127) {
                head(0, tag);
                out.write((int) n);
            } else if (n >= -32768 && n <= 32767) {
                head(1, tag);
                raw(n, 2);
            } else if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) {
                head(2, tag);
                raw(n, 4);
            } else {
                head(3, tag);
                raw(n, 8);
            }
        }

        void str(String s, int tag) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            if (b.length > 255) {
                head(7, tag);
                raw(b.length, 4);
            } else {
                head(6, tag);
                out.write(b.length);
            }
            out.writeBytes(b);
        }

        void bytes(byte[] b, int tag) {
            head(13, tag);
            head(0, 0);
            num(b.length, 0);
            out.writeBytes(b);
        }

        void struct(byte[] payload, int tag) {
            head(10, tag);
            out.writeBytes(payload);
            head(11, 0);
        }

        void emptyMap(int tag) {
            head(8, tag);
            num(0, 0);
        }

        void raw(long n, int len) {
            for (int i = len - 1; i >= 0; i--) {
                out.write((int) ((n >> (i * 8)) & 0xFF));
            }
        }

        void append(byte[] b) {
            out.writeBytes(b);
        }

        byte[] done() {
            return out.toByteArray();
        }
    }

    static String randomGuid() {
        StringBuilder sb = new StringBuilder(32);
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 32; i++) {
            sb.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return sb.toString();
    }

    /** URL 上的 baseinfo:Tars 编码后 base64,再 URL 转义。 */
    static String baseinfo(String guid) {
        W w = new W();
        w.num(0, 0);
        w.str(guid, 1);
        w.str(HUYA_UA, 2);
        w.str("HUYA&ZH&2052", 3);
        w.str("", 4);
        w.str("44299.76949,57293.104883", 5);
        w.num(0, 6);
        w.str("", 7);
        w.str("", 8);
        w.str("", 9);
        // tag10 map: {HUYA_NET: "0", HUYA_VSDKUA: <ua>}  key 用 tag0, value 用 tag1
        w.head(8, 10);
        w.num(2, 0);
        w.str("HUYA_NET", 0);
        w.str("0", 1);
        w.str("HUYA_VSDKUA", 0);
        w.str(HUYA_UA, 1);
        return URLEncoder.encode(Base64.getEncoder().encodeToString(w.done()), StandardCharsets.UTF_8);
    }

    /** 进房:命令 3 的 WupReq → huyaliveui.getLivingInfo */
    static byte[] getLivingInfo(String guid, long presenterUid, String cookie) {
        W id = new W();
        id.num(0, 0);                 // lUid
        id.str(guid, 1);              // sGuid
        id.str("", 2);                // sToken
        id.str(HUYA_UA, 3);           // sHuYaUA
        id.str(cookie, 4);            // sCookie
        id.num(0, 5);
        id.str("chrome", 6);
        id.str("", 7);

        W req = new W();
        req.struct(id.done(), 0);     // tId
        req.num(0, 1);
        req.num(0, 2);
        req.num(presenterUid, 3);     // 主播 uid
        req.str("", 4);
        req.str("", 5);
        req.num(0, 6);
        req.num(0, 7);
        req.num(1, 8);

        W uni = new W();              // UniAttribute: {"tReq": <bytes>}
        uni.head(8, 0);
        uni.num(1, 0);
        uni.str("tReq", 0);
        // tReq 本身还要再包一层 struct(tag0),里面 tag0 才是 tId;少这一层服务端会回
        // "read 'struct' type mismatch, tag: 0, get type: 12"
        W wrapper = new W();
        wrapper.struct(req.done(), 0);
        uni.bytes(wrapper.done(), 1);

        W wup = new W();
        wup.num(3, 1);                // iVersion
        wup.num(0, 2);                // cPacketType
        wup.num(0, 3);                // iMessageType
        wup.num(1, 4);                // iRequestId
        wup.str("huyaliveui", 5);
        wup.str("getLivingInfo", 6);
        wup.bytes(uni.done(), 7);     // sBuffer
        wup.num(0, 8);                // iTimeout
        wup.emptyMap(9);              // context
        wup.emptyMap(10);             // status
        byte[] body = wup.done();

        W packet = new W();           // Wup 包前置 4 字节整包长
        packet.raw(body.length + 4, 4);
        packet.append(body);

        W frame = new W();            // WS 帧
        frame.num(3, 0);              // 命令 3
        frame.bytes(packet.done(), 1);
        return frame.done();
    }

    /** 时间同步:命令 3 的 WupReq → launch.wsTimeSync,tReq = struct{ tag0 guid, tag1 short } */
    static byte[] wsTimeSync(String guid) {
        W req = new W();
        req.str(guid, 0);
        req.num(2462, 1);

        W wrapper = new W();
        wrapper.struct(req.done(), 0);

        W uni = new W();
        uni.head(8, 0);
        uni.num(1, 0);
        uni.str("tReq", 0);
        uni.bytes(wrapper.done(), 1);

        W wup = new W();
        wup.num(3, 1);
        wup.num(0, 2);
        wup.num(0, 3);
        wup.num(2, 4);
        wup.str("launch", 5);
        wup.str("wsTimeSync", 6);
        wup.bytes(uni.done(), 7);
        wup.num(0, 8);
        wup.emptyMap(9);
        wup.emptyMap(10);
        byte[] body = wup.done();

        W packet = new W();
        packet.raw(body.length + 4, 4);
        packet.append(body);

        W frame = new W();
        frame.num(3, 0);
        frame.bytes(packet.done(), 1);
        return frame.done();
    }

    /** 命令 22 = 注册消息组。payload: tag0 = 组名(如 live:<uid> / chat:<uid>) */
    static byte[] subscribe(String group) {
        W payload = new W();
        payload.str(group, 0);
        W frame = new W();
        frame.num(22, 0);
        frame.bytes(payload.done(), 1);
        return frame.done();
    }

    static void dumpWupRsp(byte[] packet) {
        String hex = java.util.HexFormat.of().formatHex(packet);
        System.out.println("[new] cmd4 raw(" + packet.length + " bytes): "
                + hex.substring(0, Math.min(1200, hex.length())));
    }

    @Test
    void probe() throws Exception {
        long uid = Long.parseLong(System.getProperty("huya.uid", "1571877666"));
        int seconds = Integer.parseInt(System.getProperty("huya.seconds", "70"));
        String guid = System.getProperty("huya.guid", randomGuid());
        String cookie = System.getProperty("huya.cookie", "guid=" + guid + "; huya_ua=webh5&0.0.1&websocket&&h5");
        String url = System.getProperty("huya.url",
                "wss://" + System.getProperty("huya.host", "cdnws.api.huya.com") + "/?baseinfo=" + baseinfo(guid));

        OkHttpClient client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        AtomicInteger frames = new AtomicInteger();
        AtomicInteger danmaku = new AtomicInteger();
        StringBuilder uris = new StringBuilder();
        StringBuilder timeline = new StringBuilder();
        long start = System.currentTimeMillis();

        WebSocket ws = client.newWebSocket(new Request.Builder()
                .url(url)
                .header("Origin", "https://www.huya.com")
                .header("User-Agent", AbstractDanmakuClient.USER_AGENT)
                .build(), new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket socket, @NotNull Response response) {
                System.out.println("[new] connected, guid=" + guid);
                socket.send(ByteString.of(getLivingInfo(guid, uid, cookie)));
                socket.send(ByteString.of(wsTimeSync(guid)));
                // 抓包得到的订阅帧原样重放(不含 guid/cookie,不绑定会话);没有该文件时用自建的简化订阅
                java.io.File file = new java.io.File(System.getProperty("huya.subfile", "/tmp/huya_sub.txt"));
                if (file.isFile()) {
                    try {
                        for (String line : java.nio.file.Files.readAllLines(file.toPath())) {
                            if (!line.isBlank()) {
                                socket.send(ByteString.of(Base64.getDecoder().decode(line.trim())));
                            }
                        }
                        System.out.println("[new] replayed subscribe frames");
                    } catch (Exception e) {
                        System.out.println("[new] replay failed: " + e);
                    }
                } else {
                    socket.send(ByteString.of(subscribe("live:" + uid)));
                    socket.send(ByteString.of(subscribe("chat:" + uid)));
                }
            }

            @Override
            public void onMessage(@NotNull WebSocket socket, @NotNull ByteString bytes) {
                frames.incrementAndGet();
                long at = (System.currentTimeMillis() - start) / 1000;
                try {
                    TarsReader stream = new TarsReader(bytes.toByteArray());
                    long type = stream.readInt(0);
                    if (type == 7) {
                        TarsReader push = new TarsReader(stream.readBytes(1));
                        push.readInt(0);
                        long uri = push.readInt(1);
                        if (uris.length() < 300) {
                            uris.append(uri).append(' ');
                        }
                        if (uri == 1400) {
                            danmaku.incrementAndGet();
                            timeline.append(at).append("s ");
                        }
                    } else if (uris.length() < 300) {
                        uris.append("cmd").append(type).append(' ');
                        if (type == 4) {
                            dumpWupRsp(stream.readBytes(1));
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onFailure(@NotNull WebSocket socket, @NotNull Throwable t, Response response) {
                System.out.println("[new] failure: " + t);
            }
        });

        Thread.sleep(seconds * 1000L);
        ws.cancel();
        System.out.printf("[new] %ds → frames=%d danmaku=%d%n  uris: %s%n  at: %s%n",
                seconds, frames.get(), danmaku.get(), uris, timeline);
    }
}
