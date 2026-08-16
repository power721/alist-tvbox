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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 虎牙新协议(webh5)重放探针(诊断用,默认不跑)。读取 /tmp/huya_new.txt:
 * 第 1 行 = WS URL(含 baseinfo),第 2 行起 = 抓包得到的客户端帧(base64),按序重放。
 * <pre>
 * mvn -o test -Dtest=HuyaReplayTest -Dhuya.replay=1 -Dhuya.seconds=70
 * </pre>
 */
@EnabledIfSystemProperty(named = "huya.replay", matches = "1")
class HuyaReplayTest {

    @Test
    void replay() throws Exception {
        List<String> lines = Files.readAllLines(Path.of(System.getProperty("huya.file", "/tmp/huya_new.txt")));
        String url = lines.get(0).trim();
        int seconds = Integer.parseInt(System.getProperty("huya.seconds", "70"));

        OkHttpClient client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        AtomicInteger frames = new AtomicInteger();
        AtomicInteger danmaku = new AtomicInteger();
        StringBuilder timeline = new StringBuilder();
        StringBuilder uris = new StringBuilder();
        long start = System.currentTimeMillis();

        WebSocket ws = client.newWebSocket(new Request.Builder()
                .url(url)
                .header("Origin", "https://www.huya.com")
                .header("User-Agent", AbstractDanmakuClient.USER_AGENT)
                .build(), new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket socket, @NotNull Response response) {
                System.out.println("[replay] connected");
                for (int i = 1; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (!line.isEmpty()) {
                        socket.send(ByteString.of(Base64.getDecoder().decode(line)));
                    }
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
                        if (uris.length() < 400) {
                            uris.append(uri).append(' ');
                        }
                        if (uri == 1400) {
                            danmaku.incrementAndGet();
                            timeline.append(at).append("s ");
                        }
                    } else if (uris.length() < 400) {
                        uris.append("cmd").append(type).append(' ');
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onFailure(@NotNull WebSocket socket, @NotNull Throwable t, Response response) {
                System.out.println("[replay] failure: " + t + " resp=" + response);
            }
        });

        Thread.sleep(seconds * 1000L);
        ws.cancel();
        System.out.printf("[replay] %ds → frames=%d danmaku=%d%n  uris: %s%n  at: %s%n",
                seconds, frames.get(), danmaku.get(), uris, timeline);
    }
}
