package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 虎牙弹幕在线探针(诊断用,默认不跑):用真实房间验证 {@link HuyaDanmakuClient} 能持续收到弹幕。
 * uid 取 mp.huya.com profileRoom 的 data.profileInfo.uid。
 * <pre>
 * curl -s 'https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=660527' | jq .data.profileInfo.uid
 * mvn -o test -Dtest=HuyaProbeTest -Dhuya.probe=1 -Dhuya.uid=1199565822350 -Dhuya.seconds=120
 * </pre>
 */
@EnabledIfSystemProperty(named = "huya.probe", matches = "1")
class HuyaProbeTest {

    @Test
    void probe() throws Exception {
        long uid = Long.parseLong(System.getProperty("huya.uid", "1199565822350"));
        int seconds = Integer.parseInt(System.getProperty("huya.seconds", "120"));

        OkHttpClient client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        AtomicInteger chat = new AtomicInteger();
        AtomicInteger online = new AtomicInteger();
        StringBuilder timeline = new StringBuilder();
        long start = System.currentTimeMillis();

        HuyaDanmakuClient danmakuClient = new HuyaDanmakuClient(uid, client, scheduler);
        danmakuClient.setListener(message -> {
            long at = (System.currentTimeMillis() - start) / 1000;
            if (LiveDanmaku.TYPE_ONLINE.equals(message.getType())) {
                online.incrementAndGet();
            } else {
                int n = chat.incrementAndGet();
                if (timeline.length() < 400) {
                    timeline.append(at).append("s ");
                }
                if (n <= 10 || n % 50 == 0) {
                    System.out.printf("  [%3ds] %s: %s (%s)%n", at,
                            message.getUserName(), message.getMessage(), message.getColor());
                }
            }
        });
        danmakuClient.start();

        // 每 15 秒报一次,便于看出中途是否停推
        for (int i = 0; i < seconds / 15; i++) {
            Thread.sleep(15_000);
            System.out.printf("[probe] t=%3ds chat=%d online=%d%n",
                    (System.currentTimeMillis() - start) / 1000, chat.get(), online.get());
        }
        danmakuClient.stop();
        scheduler.shutdownNow();
        System.out.printf("[probe] %ds → chat=%d online=%d%n  at: %s%n",
                seconds, chat.get(), online.get(), timeline);
    }
}
