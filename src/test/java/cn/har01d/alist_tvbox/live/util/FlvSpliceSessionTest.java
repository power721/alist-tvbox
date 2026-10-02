package cn.har01d.alist_tvbox.live.util;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FLV 租约拼接会话单测(pure_live test/flv_splice_relay_test.dart 六用例移植):
 * 关键帧无缝拼接、旧流提前断开续接、异时间线平移、解码器配置变更重发、换链失败保底、租约判定。
 */
class FlvSpliceSessionTest {
    private static final int FRAME = 40;
    private static final int GOP = 2000;
    private static final long BASE = 1_800_000_000_000L;
    private static final byte[] HEADER = {0x46, 0x4c, 0x56, 1, 5, 0, 0, 0, 9, 0, 0, 0, 0};

    // ------------------------------------------------------------------
    // 上游测试同构物:tag 构造与伪直播连接
    // ------------------------------------------------------------------

    static byte[] tag(int type, int ts, byte[] data) {
        int size = data.length;
        byte[] out = new byte[11 + size + 4];
        out[0] = (byte) type;
        out[1] = (byte) ((size >> 16) & 0xff);
        out[2] = (byte) ((size >> 8) & 0xff);
        out[3] = (byte) (size & 0xff);
        out[4] = (byte) ((ts >> 16) & 0xff);
        out[5] = (byte) ((ts >> 8) & 0xff);
        out[6] = (byte) (ts & 0xff);
        out[7] = (byte) ((ts >> 24) & 0xff);
        System.arraycopy(data, 0, out, 11, size);
        int total = 11 + size;
        out[11 + size] = (byte) ((total >> 24) & 0xff);
        out[12 + size] = (byte) ((total >> 16) & 0xff);
        out[13 + size] = (byte) ((total >> 8) & 0xff);
        out[14 + size] = (byte) (total & 0xff);
        return out;
    }

    static byte[] videoConfig(int variant) {
        return tag(9, 0, new byte[]{0x17, 0, 0, 0, 0, 1, 0x64, 0, (byte) variant});
    }

    static byte[] audioConfig() {
        return tag(8, 0, new byte[]{(byte) 0xaf, 0, 0x12, 0x10});
    }

    static byte[] video(int ts) {
        return tag(9, ts, new byte[]{(byte) (ts % GOP == 0 ? 0x17 : 0x27), 1, 0, 0, 0, 1, 2, 3});
    }

    static byte[] audio(int ts) {
        return tag(8, ts, new byte[]{(byte) 0xaf, 1, 9, 9});
    }

    /** 一条伪直播连接:先发头与配置,再从 start(含)按 40ms 帧距发到 end(不含),时间线平移 shift。 */
    static final class FakeLive implements FlvSpliceSession.TagReader {
        private final ArrayDeque<byte[]> queue;
        private final int end;
        private final int shift;
        private final IntConsumer onDeliver;
        private int next;
        boolean cancelled;

        FakeLive(int start, int end, int shift, int configVariant, IntConsumer onDeliver) {
            this.queue = new ArrayDeque<>(List.of(HEADER, videoConfig(configVariant), audioConfig()));
            this.end = end;
            this.shift = shift;
            this.onDeliver = onDeliver;
            this.next = start;
        }

        @Override
        public byte[] next() {
            if (cancelled) {
                return null;
            }
            if (queue.isEmpty()) {
                if (next >= end) {
                    return null;
                }
                queue.add(video(next + shift));
                queue.add(audio(next + shift + 20));
                if (onDeliver != null) {
                    onDeliver.accept(next);
                }
                next += FRAME;
            }
            return queue.poll();
        }

        @Override
        public void close() {
            cancelled = true;
        }
    }

    /** 收包下沉:packets[0] 恒为 FLV 头包,tags() 为其后全部 tag。 */
    static final class PacketSink extends OutputStream {
        final List<byte[]> packets = new ArrayList<>();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
            packets.add(Arrays.copyOfRange(b, off, off + len));
        }

        List<byte[]> tags() {
            return packets.subList(1, packets.size());
        }

        List<Integer> timestamps(int type) {
            List<Integer> result = new ArrayList<>();
            for (byte[] t : tags()) {
                if (FlvTag.type(t) == type && !FlvTag.isVideoConfig(t) && !FlvTag.isAudioConfig(t)) {
                    result.add(FlvTag.timestamp(t));
                }
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // 六用例(pure_live 同款)
    // ------------------------------------------------------------------

    @Test
    void leaseRenewedWhileOldStreamAliveSplicesWithoutGapOrRepeat() throws Exception {
        long[] liveTs = {0};
        FakeLive a = new FakeLive(0, 30000, 0, 1, ts -> liveTs[0] = ts);
        FakeLive[] b = new FakeLive[1];
        PacketSink out = new PacketSink();
        FlvSpliceSession session = new FlvSpliceSession(
                new FlvSpliceSession.Lease("https://cdn/a.flv", BASE + 20000),
                url -> url.endsWith("/a.flv") ? a : b[0],
                current -> {
                    if (current.endsWith("/b.flv")) {
                        throw new IllegalStateException("no more leases");
                    }
                    // CDN 新连接从其缓存 GOP 起播,落后于旧连接当前位置
                    b[0] = new FakeLive((int) ((liveTs[0] / GOP - 1) * GOP), 60000, 0, 1, null);
                    return new FlvSpliceSession.Lease("https://cdn/b.flv", null);
                },
                () -> BASE + liveTs[0]);
        session.run(out);

        assertEquals(1, session.getSwitches());
        assertTrue(a.cancelled, "旧连接应已关闭");
        List<Integer> video = out.timestamps(FlvTag.VIDEO);
        assertEquals(0, video.get(0));
        assertEquals(60000 - FRAME, video.get(video.size() - 1));
        for (int i = 1; i < video.size(); i++) {
            assertEquals(FRAME, video.get(i) - video.get(i - 1), "video " + video.get(i - 1) + " -> " + video.get(i));
        }
        List<Integer> audio = out.timestamps(FlvTag.AUDIO);
        for (int i = 1; i < audio.size(); i++) {
            assertEquals(FRAME, audio.get(i) - audio.get(i - 1), "audio " + audio.get(i - 1) + " -> " + audio.get(i));
        }
        byte[] switchTag = out.tags().stream()
                .filter(t -> FlvTag.type(t) == FlvTag.VIDEO && FlvTag.timestamp(t) > 20000 && FlvTag.isKeyframe(t))
                .findFirst().orElseThrow();
        assertEquals(0, FlvTag.timestamp(switchTag) % GOP, "切换必须落在关键帧上");
        assertEquals(1, out.tags().stream().filter(FlvTag::isVideoConfig).count(), "相同配置不应重发");
    }

    @Test
    void oldStreamCutEarlyContinuesAtNextKeyframeOfNewOne() throws Exception {
        PacketSink out = new PacketSink();
        FlvSpliceSession session = new FlvSpliceSession(
                new FlvSpliceSession.Lease("https://cdn/a.flv", null),
                url -> url.endsWith("/a.flv") ? new FakeLive(0, 20000, 0, 1, null) : new FakeLive(24000, 30000, 0, 1, null),
                current -> {
                    if (current.endsWith("/b.flv")) {
                        throw new IllegalStateException("no more leases");
                    }
                    return new FlvSpliceSession.Lease("https://cdn/b.flv", null);
                },
                () -> BASE);
        session.run(out);

        List<Integer> video = out.timestamps(FlvTag.VIDEO);
        assertEquals(1, session.getSwitches());
        assertTrue(video.containsAll(List.of(20000 - FRAME, 24000)), "应含旧流末帧与新连接首关键帧");
        assertEquals(video.indexOf(24000), video.indexOf(20000 - FRAME) + 1, "新关键帧应紧跟旧流末帧");
        for (int i = 1; i < video.size(); i++) {
            assertTrue(video.get(i) > video.get(i - 1), "video " + video.get(i - 1) + " -> " + video.get(i));
        }
    }

    @Test
    void newConnectionOnAnotherTimelineIsShiftedToContinueOldOne() throws Exception {
        long[] liveTs = {0};
        PacketSink out = new PacketSink();
        FlvSpliceSession session = new FlvSpliceSession(
                new FlvSpliceSession.Lease("https://cdn/a.flv", BASE + 10000),
                url -> url.endsWith("/a.flv")
                        ? new FakeLive(0, 30000, 0, 1, ts -> liveTs[0] = ts)
                        : new FakeLive(10000, 16000, -1000000, 1, null),
                current -> {
                    if (current.endsWith("/b.flv")) {
                        throw new IllegalStateException("no more leases");
                    }
                    return new FlvSpliceSession.Lease("https://cdn/b.flv", null);
                },
                () -> BASE + liveTs[0]);
        session.run(out);

        List<Integer> video = out.timestamps(FlvTag.VIDEO);
        assertEquals(1, session.getSwitches());
        for (int i = 1; i < video.size(); i++) {
            int delta = video.get(i) - video.get(i - 1);
            assertTrue(delta >= 1 && delta <= FRAME, "video " + video.get(i - 1) + " -> " + video.get(i));
        }
    }

    @Test
    void changedDecoderConfigurationIsSentBeforeSwitchKeyframe() throws Exception {
        long[] liveTs = {0};
        PacketSink out = new PacketSink();
        FlvSpliceSession session = new FlvSpliceSession(
                new FlvSpliceSession.Lease("https://cdn/a.flv", BASE + 10000),
                url -> url.endsWith("/a.flv")
                        ? new FakeLive(0, 30000, 0, 1, ts -> liveTs[0] = ts)
                        : new FakeLive(8000, 16000, 0, 2, null),
                current -> {
                    if (current.endsWith("/b.flv")) {
                        throw new IllegalStateException("no more leases");
                    }
                    return new FlvSpliceSession.Lease("https://cdn/b.flv", null);
                },
                () -> BASE + liveTs[0]);
        session.run(out);

        List<byte[]> configs = out.tags().stream().filter(FlvTag::isVideoConfig).toList();
        assertEquals(2, configs.size(), "配置变化应重发");
        int index = out.tags().indexOf(configs.get(1));
        byte[] following = out.tags().listIterator(index + 1).next();
        assertEquals(FlvTag.VIDEO, FlvTag.type(following));
        assertTrue(FlvTag.isKeyframe(following), "配置后应紧跟切换关键帧");
        assertEquals(FlvTag.timestamp(following), FlvTag.timestamp(configs.get(1)), "重发配置时间戳=切换关键帧");
    }

    @Test
    void failedRenewalKeepsWorkingStreamToItsEnd() throws Exception {
        long[] liveTs = {0};
        PacketSink out = new PacketSink();
        FlvSpliceSession session = new FlvSpliceSession(
                new FlvSpliceSession.Lease("https://cdn/a.flv", BASE + 5000),
                url -> new FakeLive(0, 12000, 0, 1, ts -> liveTs[0] = ts),
                current -> {
                    throw new IllegalStateException("resolver offline");
                },
                () -> BASE + liveTs[0]);
        session.run(out);

        List<Integer> video = out.timestamps(FlvTag.VIDEO);
        assertEquals(0, session.getSwitches());
        assertEquals(0, video.get(0));
        assertEquals(12000 - FRAME, video.get(video.size() - 1));
        assertEquals(12000 / FRAME, video.size());
    }

    @Test
    void onlyLeasedPlainFlvInputsAreRelayed() {
        assertTrue(FlvSpliceSession.appliesTo("https://hdl.cdn/live/1.flv?expire=300"));
        assertFalse(FlvSpliceSession.appliesTo("https://hdl.cdn/live/1.flv"));
        assertFalse(FlvSpliceSession.appliesTo("https://hdl.cdn/live/1.flv?expire=0"));
        assertFalse(FlvSpliceSession.appliesTo("https://hdl.cdn/live/1.flv?expire=abc"));
        assertFalse(FlvSpliceSession.appliesTo("https://al.flv.huya.com/src/1.flv?wsTime=66f&fm=x"), "虎牙签名 FLV 不带 expire 不中继");
        assertFalse(FlvSpliceSession.appliesTo("https://hls.cdn/live/1.m3u8?expire=300"));
        assertFalse(FlvSpliceSession.appliesTo("rtmp://hdl.cdn/live/1.flv?expire=300"));
        assertFalse(FlvSpliceSession.appliesTo("::::"));
    }

    @Test
    void refreshAtKeepsQuarterOfShortLeases() {
        long issued = BASE;
        // 300s 租约:lead=min(75s,45s)=45s
        assertEquals(issued + 255_000, FlvSpliceSession.refreshAtEpochMs("https://c/live/1.flv?expire=300", issued));
        // 60s 短租约:lead=min(15s,45s)=15s,保留四分之三寿命
        assertEquals(issued + 45_000, FlvSpliceSession.refreshAtEpochMs("https://c/live/1.flv?expire=60", issued));
        assertNull(FlvSpliceSession.refreshAtEpochMs("https://c/live/1.flv", issued));
    }

    // ------------------------------------------------------------------
    // 真实 HTTP 管路:HttpFlvTagReader(建连/分帧/优雅关闭)+ 会话端到端续接
    // ------------------------------------------------------------------

    @Test
    void splicesAcrossRealHttpUpstreamEnds() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        a.writeBytes(HEADER);
        a.writeBytes(videoConfig(1));
        a.writeBytes(audioConfig());
        for (int ts = 0; ts < 8000; ts += FRAME) {
            a.writeBytes(video(ts));
            a.writeBytes(audio(ts + 20));
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(HEADER);
        b.writeBytes(videoConfig(1));
        b.writeBytes(audioConfig());
        for (int ts = 8400; ts < 12000; ts += FRAME) {
            b.writeBytes(video(ts));
            b.writeBytes(audio(ts + 20));
        }
        server.createContext("/a.flv", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/x-flv");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(a.toByteArray());
            exchange.getResponseBody().close();
        });
        server.createContext("/b.flv", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/x-flv");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(b.toByteArray());
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
            // 上游 404:建连即失败
            assertThrows(IOException.class,
                    () -> new HttpFlvTagReader(client, base + "/missing.flv", Map.of("User-Agent", "probe")));

            int[] renewals = {0};
            PacketSink out = new PacketSink();
            FlvSpliceSession session = new FlvSpliceSession(
                    new FlvSpliceSession.Lease(base + "/a.flv", null),
                    url -> new HttpFlvTagReader(client, url, Map.of("User-Agent", "probe")),
                    current -> {
                        if (renewals[0]++ > 0) {
                            throw new IllegalStateException("no more leases");
                        }
                        return new FlvSpliceSession.Lease(base + "/b.flv", null);
                    },
                    System::currentTimeMillis);
            session.run(out);

            assertEquals(1, session.getSwitches());
            // 输出必须仍是合法 FLV:除首包外不得再出现 FLV 签名头,时间戳严格递增
            FlvInputFramer replay = new FlvInputFramer();
            join(replay, out.packets);
            for (int i = 1; i < out.packets.size(); i++) {
                byte[] packet = out.packets.get(i);
                assertFalse(packet.length > 3 && packet[0] == 'F' && packet[1] == 'L' && packet[2] == 'V',
                        "换链后不得残留第二个 FLV 头包");
            }
            List<Integer> video = out.timestamps(FlvTag.VIDEO);
            assertEquals(0, video.get(0));
            assertEquals(12000 - FRAME, video.get(video.size() - 1));
            for (int i = 1; i < video.size(); i++) {
                assertTrue(video.get(i) > video.get(i - 1));
            }
        } finally {
            server.stop(0);
        }
    }

    /** 把已产出的包重新走一遍分帧器,校验字节级完整性。 */
    private static void join(FlvInputFramer framer, List<byte[]> packets) throws IOException {
        for (byte[] packet : packets) {
            framer.add(packet, 0, packet.length);
        }
        int count = 0;
        while (framer.hasNext()) {
            framer.next();
            count++;
        }
        assertEquals(packets.size(), count, "重放包数应一致");
    }
}
