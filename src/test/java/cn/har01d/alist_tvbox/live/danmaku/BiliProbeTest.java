package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站弹幕端到端探针(诊断用,默认不跑):真实房间驱动生产 {@link BilibiliDanmakuClient},
 * 验证弹幕消息与 get_info 轮询的实时人气(详情页同口径)。
 * <pre>
 * mvn -o test -Dtest=BiliProbeTest -Dbili.probe=1 -Dbili.room=545068 -Dbili.seconds=80 '-Dbili.cookie=SESSDATA=...; buvid3=...'
 * </pre>
 */
@EnabledIfSystemProperty(named = "bili.probe", matches = "1")
class BiliProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    // -Dbili.cookie= 传入登录 Cookie(生产 BILIBILI_COOKIE 同格式);缺省用随机 buvid3 游客
    private static final String COOKIE = System.getProperty("bili.cookie", "").isEmpty()
            ? "buvid3=" + UUID.randomUUID() + "infoc" : System.getProperty("bili.cookie");

    @Test
    void probe() throws Exception {
        long roomId = Long.parseLong(System.getProperty("bili.room", "545068"));
        int seconds = Integer.parseInt(System.getProperty("bili.seconds", "80"));
        OkHttpClient http = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();

        JsonNode nav = get(http, "https://api.bilibili.com/x/web-interface/nav");
        String imgKey = key(nav.path("data").path("wbi_img").path("img_url").asText());
        String subKey = key(nav.path("data").path("wbi_img").path("sub_url").asText());
        // 短号房间必须转真实房间号进房,挂在短号上收不到任何推送
        JsonNode h5 = get(http, "https://api.live.bilibili.com/xlive/web-room/v1/index/getH5InfoByRoom?room_id=" + roomId);
        long realRoomId = h5.path("code").asInt() == 0
                ? h5.path("data").path("room_info").path("room_id").asLong(roomId) : roomId;
        Map<String, Object> params = new HashMap<>();
        params.put("id", realRoomId);
        String url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?" + Utils.encryptWbi(params, imgKey, subKey);
        JsonNode info = get(http, url);
        String token = info.path("data").path("token").asText("");
        String host = info.path("data").path("host_list").path(0).path("host").asText("");
        Matcher buvid = Pattern.compile("buvid3=([^;]+)").matcher(COOKIE);
        Matcher uidMatcher = Pattern.compile("DedeUserID=(\\d+)").matcher(COOKIE);
        long uid = uidMatcher.find() ? Long.parseLong(uidMatcher.group(1)) : 0;
        System.out.printf("[probe] room=%d->%d code=%d host=%s uid=%d%n",
                roomId, realRoomId, info.path("code").asInt(), host, uid);

        var args = new BilibiliDanmakuClient.BiliDanmakuArgs(realRoomId, uid, token,
                buvid.find() ? buvid.group(1) : "", host, COOKIE);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "bili-probe");
            thread.setDaemon(true);
            return thread;
        });
        BilibiliDanmakuClient client = new BilibiliDanmakuClient(args, http, scheduler);
        AtomicInteger chat = new AtomicInteger();
        long start = System.currentTimeMillis();
        client.setListener(message -> {
            if (LiveDanmaku.TYPE_ONLINE.equals(message.getType())) {
                System.out.printf("[%3ds] online: %s%n", (System.currentTimeMillis() - start) / 1000, message.getMessage());
            } else if (chat.incrementAndGet() <= 5) {
                System.out.printf("[%3ds] chat: %s: %s%n", (System.currentTimeMillis() - start) / 1000,
                        message.getUserName(), message.getMessage());
            }
        });
        client.start();
        Thread.sleep(seconds * 1000L);
        client.stop();
        scheduler.shutdownNow();
        System.out.printf("[probe] done chat=%d dead=%s%n", chat.get(), client.isDead());
    }

    private static JsonNode get(OkHttpClient http, String url) throws Exception {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://live.bilibili.com/")
                .header("Cookie", COOKIE)
                .build();
        try (Response response = http.newCall(request).execute()) {
            return MAPPER.readTree(response.body().string());
        }
    }

    private static String key(String url) {
        int start = url.lastIndexOf('/') + 1;
        int end = url.lastIndexOf('.');
        return url.substring(start, end);
    }
}
