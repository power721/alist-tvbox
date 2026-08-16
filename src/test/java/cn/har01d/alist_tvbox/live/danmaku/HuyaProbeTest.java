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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 虎牙弹幕推送探针(诊断用,默认不跑)。同一时段内对照不同心跳包,排除限流/房间冷热带来的干扰。
 * <pre>
 * mvn -o test -Dtest=HuyaProbeTest -Dhuya.probe=1 -Dhuya.uid=1571877666 -Dhuya.mode=patched
 * </pre>
 * mode: original = pure_live 固定心跳; patched = 用房间频道号替换 lTid/lSid; none = 不发心跳
 */
@EnabledIfSystemProperty(named = "huya.probe", matches = "1")
class HuyaProbeTest {

    private static void writeIntBE(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) ((value >> 24) & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 3] = (byte) (value & 0xFF);
    }

    @Test
    void probe() throws Exception {
        long uid = Long.parseLong(System.getProperty("huya.uid", "1571877666"));
        String mode = System.getProperty("huya.mode", "original");
        int seconds = Integer.parseInt(System.getProperty("huya.seconds", "70"));
        long heartbeatMillis = Long.parseLong(System.getProperty("huya.hb", "30000"));

        OkHttpClient client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        AtomicInteger frames = new AtomicInteger();
        AtomicInteger danmaku = new AtomicInteger();
        long start = System.currentTimeMillis();
        StringBuilder timeline = new StringBuilder();

        WebSocket ws = client.newWebSocket(new Request.Builder()
                .url("wss://cdnws.api.huya.com")
                .header("User-Agent", AbstractDanmakuClient.USER_AGENT)
                .build(), new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket socket, @NotNull Response response) {
                socket.send(ByteString.of(HuyaDanmakuClient.buildJoinData(uid)));
            }

            @Override
            public void onMessage(@NotNull WebSocket socket, @NotNull ByteString bytes) {
                frames.incrementAndGet();
                long at = (System.currentTimeMillis() - start) / 1000;
                try {
                    TarsReader stream = new TarsReader(bytes.toByteArray());
                    if (stream.readInt(0) == 7) {
                        TarsReader push = new TarsReader(stream.readBytes(1));
                        push.readInt(0);
                        long uri = push.readInt(1);
                        if (uri == 1400) {
                            danmaku.incrementAndGet();
                            timeline.append(at).append("s ");
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });

        Thread heartbeat = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(heartbeatMillis);
                    if ("none".equals(mode)) {
                        continue;
                    }
                    if ("wscmd5".equals(mode) || "both".equals(mode)) {
                        // EWSCmdC2S_HeartBeat:WS 连接层心跳,pure_live 没发过
                        ws.send(ByteString.of(new byte[]{0x00, 0x05}));
                    }
                    if ("wscmd5".equals(mode)) {
                        continue;
                    }
                    byte[] packet = HuyaDanmakuClient.HEARTBEAT.clone();
                    if ("patched".equals(mode)) {
                        // 把包内抓包遗留的 lTid/lSid(偏移 79/84,大端 INT)换成本房间频道号
                        writeIntBE(packet, 79, (int) uid);
                        writeIntBE(packet, 84, (int) uid);
                    }
                    ws.send(ByteString.of(packet));
                }
            } catch (InterruptedException ignored) {
            }
        });
        heartbeat.setDaemon(true);
        heartbeat.start();

        Thread.sleep(seconds * 1000L);
        heartbeat.interrupt();
        ws.cancel();
        System.out.printf("[huya-probe] mode=%s hb=%dms %ds → frames=%d danmaku=%d%n  at: %s%n",
                mode, heartbeatMillis, seconds, frames.get(), danmaku.get(), timeline);
    }
}
