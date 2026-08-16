package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.live.danmaku.MiniProto.ProtoReader;
import cn.har01d.alist_tvbox.live.danmaku.MiniProto.ProtoWriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹幕协议编解码单元测试(斗鱼 STT / 虎牙 Tars / B站包格式 / 抖音 X-Bogus 与 protobuf)。
 */
class DanmakuProtocolTest {

    // ---- 斗鱼 ----

    @Test
    void douyuSerialize() {
        byte[] data = DouyuDanmakuClient.serialize("type@=mrkl/");
        int len = data[0] & 0xFF | (data[1] & 0xFF) << 8 | (data[2] & 0xFF) << 16 | (data[3] & 0xFF) << 24;
        assertEquals(len, data[4] & 0xFF | (data[5] & 0xFF) << 8 | (data[6] & 0xFF) << 16 | (data[7] & 0xFF) << 24, "包长写两遍");
        assertEquals(689, data[8] & 0xFF | (data[9] & 0xFF) << 8, "小端 689");
        assertEquals(0, data[data.length - 1], "结尾 \\0");
        assertEquals("type@=mrkl/", new String(data, 12, len - 9, StandardCharsets.UTF_8));
        assertEquals(len + 4, data.length, "实际字节比长度字段多第二个长度字段");
    }

    @Test
    void douyuUnescape() {
        assertEquals("a/b@c", DouyuDanmakuClient.unescape("a@Sb@Ac"));
    }

    // ---- 虎牙 Tars ----

    @Test
    void huyaSubscribeFrame() {
        byte[] data = HuyaDanmakuClient.subscribe(List.of("live:123", "chat:123"));
        TarsReader outer = new TarsReader(data);
        assertEquals(16, outer.readInt(0), "命令 16 = 订阅消息组");
        byte[] payload = outer.readBytes(1);
        assertEquals(2, new TarsReader(payload).enterList(0), "两个消息组");
        String text = new String(payload, StandardCharsets.UTF_8);
        assertTrue(text.contains("live:123") && text.contains("chat:123"), "组名写进 tag0 的字符串列表");
    }

    /**
     * 命令 22 群组推送的 payload:tag0 = 组名,tag1 = list&lt;struct{ tag0 uri, tag1 bytes 消息体, tag2 msgId }&gt;。
     * 手工按抓包的字节布局拼一帧,验证 TarsReader 的 list/struct 遍历。
     */
    @Test
    void huyaGroupPushList() throws Exception {
        TarsWriter body = new TarsWriter();
        body.write("弹幕", 3);

        TarsWriter element = new TarsWriter();
        element.write(1400, 0);
        element.write(body.toByteArray(), 1);
        element.write(2018405541718215680L, 2);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        TarsWriter group = new TarsWriter();
        group.write("live:123", 0);
        payload.writeBytes(group.toByteArray());
        payload.write((1 << 4) | 9);        // tag1, LIST
        payload.write(0);                   // tag0, BYTE:元素个数
        payload.write(1);
        payload.write(10);                  // STRUCT_BEGIN
        payload.writeBytes(element.toByteArray());
        payload.write(11);                  // STRUCT_END

        TarsReader reader = new TarsReader(payload.toByteArray());
        assertEquals("live:123", reader.readString(0));
        assertEquals(1, reader.enterList(1));
        assertTrue(reader.enterStructElement());
        assertEquals(1400, reader.readInt(0));
        assertEquals("弹幕", new TarsReader(reader.readBytes(1)).readString(3));
        reader.endStruct();
    }

    @Test
    void tarsStringAndStruct() {
        TarsWriter writer = new TarsWriter();
        writer.write("弹幕测试", 2);
        TarsWriter inner = new TarsWriter();
        inner.write(16777215L, 0);
        writer.writeStruct(inner.toByteArray(), 6);

        TarsReader reader = new TarsReader(writer.toByteArray());
        assertEquals("弹幕测试", reader.readString(2));
        assertTrue(reader.enterStruct(6));
        assertEquals(16777215L, reader.readInt(0));
        reader.endStruct();
    }

    // ---- B站 ----

    @Test
    void biliEncodePacket() {
        byte[] data = BilibiliDanmakuClient.encode("", 2);
        assertEquals(16, data.length);
        assertEquals(16, data[4] << 8 | data[5] & 0xFF, "headerLen=16(大端)");
        assertEquals(0, data[6] << 8 | data[7] & 0xFF, "protover=0");
        assertEquals(2, data[8] << 24 | (data[9] & 0xFF) << 16 | (data[10] & 0xFF) << 8 | data[11] & 0xFF, "operation=2");
    }

    @Test
    void biliHeartbeatReplyIgnored() throws Exception {
        // 心跳回应(op=3)的人气值已弃用恒为 1,不得 emit;在线人数走 ONLINE_RANK_COUNT
        var client = new BilibiliDanmakuClient(new BilibiliDanmakuClient.BiliDanmakuArgs(1, 0, "t", "", "h", ""),
                new okhttp3.OkHttpClient(), java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
        // emit 以 running 门控,不起真实 WS 连接,反射置位模拟运行中
        var running = AbstractDanmakuClient.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(client, true);
        List<cn.har01d.alist_tvbox.dto.LiveDanmaku> received = new java.util.ArrayList<>();
        client.setListener(received::add);
        client.handleMessage(BilibiliDanmakuClient.encode("\u0000\u0000\u0000\u0001", 3));
        assertTrue(received.isEmpty(), "弃用的心跳人气不应下发");
        client.handleMessage(BilibiliDanmakuClient.encode(
                "{\"cmd\":\"ONLINE_RANK_COUNT\",\"data\":{\"count\":72862,\"count_text\":\"7万+\"}}", 5));
        assertEquals(1, received.size());
        assertEquals("online", received.get(0).getType());
        assertEquals("72862", received.get(0).getMessage());
        client.stop();
    }

    // ---- 抖音 X-Bogus ----

    @Test
    void xbogusDeterministic() {
        String msStub = "0123456789abcdef0123456789abcdef";
        String first = XBogus.generate(msStub, 1, 0x21, 0x42);
        String second = XBogus.generate(msStub, 1, 0x21, 0x42);
        String other = XBogus.generate(msStub, 1, 0x22, 0x42);
        assertEquals(first, second, "相同随机数输出确定");
        assertEquals(16, first.length());
        assertTrue(first.chars().allMatch(c -> "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe".indexOf(c) >= 0), "字符全部来自 X-Bogus 字母表");
        assertTrue(!first.equals(other), "随机数影响输出");
    }

    @Test
    void douyinSignature() {
        String sign = DouyinDanmakuClient.signature("7382735338101328680", "7273033021933946427");
        assertEquals(16, sign.length());
    }

    @Test
    void douyinQueryStartsWithQuestionMark() {
        String query = DouyinDanmakuClient.buildQuery(
                new DouyinDanmakuClient.DouyinDanmakuArgs("123", "7382735338101328680", "7273033021933946427", ""));
        // 服务端地址以 / 结尾,query 必须自带问号,否则参数黏进路径、握手返回 502
        assertTrue(query.startsWith("?app_name=douyin_web"), query.substring(0, Math.min(40, query.length())));
        assertTrue(query.contains("&room_id=7382735338101328680"));
    }

    // ---- 抖音 protobuf ----

    @Test
    void protoWriterReaderRoundTrip() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(2, 123456789012345L);
        writer.writeString(7, "hb");
        writer.writeBytes(8, new byte[]{1, 2, 3});

        ProtoReader reader = new ProtoReader(writer.toByteArray());
        long logId = 0;
        String payloadType = null;
        byte[] payload = null;
        while (reader.nextField()) {
            switch (reader.tag()) {
                case 2 -> logId = reader.readVarint();
                case 7 -> payloadType = reader.readString();
                case 8 -> payload = reader.readBytes();
                default -> reader.skip();
            }
        }
        assertEquals(123456789012345L, logId);
        assertEquals("hb", payloadType);
        assertNotNull(payload);
        assertEquals(3, payload.length);
        assertEquals(1, payload[0]);
    }

    @Test
    void protoSkipLengthDelimitedKeepsPosition() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeString(5, "compress_type=gzip"); // 待跳过的 headersList
        writer.writeString(5, "im-internal_ext");
        writer.writeString(7, "hb");
        writer.writeBytes(8, new byte[]{9, 8, 7});

        ProtoReader reader = new ProtoReader(writer.toByteArray());
        String payloadType = null;
        byte[] payload = null;
        while (reader.nextField()) {
            switch (reader.tag()) {
                case 7 -> payloadType = reader.readString();
                case 8 -> payload = reader.readBytes();
                default -> reader.skip();
            }
        }
        // skip 少跳长度前缀会让后面所有字段错位,这里必须原样读到 hb 与 payload
        assertEquals("hb", payloadType);
        assertNotNull(payload);
        assertEquals(3, payload.length);
        assertEquals(9, payload[0]);
    }

    @Test
    void gzipDecompress() throws Exception {
        String text = "{\"messagesList\":[],\"needAck\":true}";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        byte[] decompressed = DouyinDanmakuClient.gunzip(out.toByteArray());
        assertEquals(text, new String(decompressed, StandardCharsets.UTF_8));
    }
}
