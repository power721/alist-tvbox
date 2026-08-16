package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import cn.har01d.alist_tvbox.live.danmaku.MiniProto.ProtoReader;
import cn.har01d.alist_tvbox.live.danmaku.MiniProto.ProtoWriter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;

/**
 * 抖音直播弹幕客户端(移植 pure_live DouyinDanmaku)。
 * protobuf PushFrame/Response,payload gzip 压缩;X-Bogus 本地签名;心跳/进房发 payloadType=hb,needAck 时回 ack。
 */
@Slf4j
public class DouyinDanmakuClient extends AbstractDanmakuClient {
    private static final String SERVER_URL = "wss://webcast100-ws-web-lq.douyin.com/webcast/im/push/v2/";
    private static final String VERSION_CODE = "180800";
    private static final String SDK_VERSION = "1.0.14-beta.0";

    private final DouyinDanmakuArgs args;

    public record DouyinDanmakuArgs(String webRid, String roomId, String userId, String cookie) {
    }

    public DouyinDanmakuClient(DouyinDanmakuArgs args, OkHttpClient okHttpClient, ScheduledExecutorService scheduler) {
        super("douyin-danmaku", SERVER_URL + buildQuery(args) + "&signature=" + signature(args.roomId(), args.userId()),
                buildHeaders(args), 10_000, okHttpClient, scheduler);
        this.args = args;
    }

    private static Map<String, String> buildHeaders(DouyinDanmakuArgs args) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", args.cookie());
        headers.put("Origin", "https://live.douyin.com");
        return headers;
    }

    static String buildQuery(DouyinDanmakuArgs args) {
        long ts = System.currentTimeMillis();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_name", "douyin_web");
        params.put("version_code", VERSION_CODE);
        params.put("webcast_sdk_version", SDK_VERSION);
        params.put("update_version_code", SDK_VERSION);
        params.put("compress", "gzip");
        params.put("cursor", "h-1_t-" + ts + "_r-1_d-1_u-1");
        params.put("host", "https://live.douyin.com");
        params.put("aid", "6383");
        params.put("live_id", "1");
        params.put("did_rule", "3");
        params.put("debug", "false");
        params.put("maxCacheMessageNumber", "20");
        params.put("endpoint", "live_pc");
        params.put("support_wrds", "1");
        params.put("im_path", "/webcast/im/fetch/");
        params.put("user_unique_id", args.userId());
        params.put("device_platform", "web");
        params.put("cookie_enabled", "true");
        params.put("screen_width", "1920");
        params.put("screen_height", "1080");
        params.put("browser_language", "zh-CN");
        params.put("browser_platform", "Win32");
        params.put("browser_name", "Mozilla");
        params.put("browser_version", USER_AGENT.replace("Mozilla/", ""));
        params.put("browser_online", "true");
        params.put("tz_name", "Asia/Shanghai");
        params.put("identity", "audience");
        params.put("room_id", args.roomId());
        params.put("heartbeatDuration", "0");
        StringBuilder sb = new StringBuilder("?");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 1) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        // 保留开头的 '?':SERVER_URL 以 / 结尾,削掉问号会让 query 黏进路径,握手被 502 拒绝
        return sb.toString();
    }

    static String signature(String roomId, String userId) {
        // X-Bogus 的签名输入:k=v 用逗号连接(非 &)
        String[][] params = {
                {"live_id", "1"},
                {"aid", "6383"},
                {"version_code", VERSION_CODE},
                {"webcast_sdk_version", SDK_VERSION},
                {"room_id", roomId},
                {"sub_room_id", ""},
                {"sub_channel_id", ""},
                {"did_rule", "3"},
                {"user_unique_id", userId},
                {"device_platform", "web"},
                {"device_type", ""},
                {"ac", ""},
                {"identity", "audience"},
        };
        StringBuilder sigParam = new StringBuilder();
        for (String[] param : params) {
            if (sigParam.length() > 0) {
                sigParam.append(',');
            }
            sigParam.append(param[0]).append('=').append(param[1]);
        }
        String msStub = XBogus.md5Hex(sigParam.toString());
        return XBogus.generate(msStub, 1);
    }

    @Override
    protected void onConnected(WebSocket ws) {
        send(hbFrame());
    }

    @Override
    protected byte[] heartbeatMessage() {
        return hbFrame();
    }

    private static byte[] hbFrame() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeString(7, "hb"); // payloadType
        return writer.toByteArray();
    }

    @Override
    protected void handleMessage(byte[] data) {
        long logId = 0;
        byte[] payload = null;
        ProtoReader frame = new ProtoReader(data);
        while (frame.nextField()) {
            switch (frame.tag()) {
                case 2 -> logId = frame.readVarint(); // logId
                case 8 -> payload = frame.readBytes(); // payload
                default -> frame.skip();
            }
        }
        if (payload == null || payload.length == 0) {
            return;
        }
        byte[] decompressed = gunzip(payload);
        String internalExt = null;
        boolean needAck = false;
        ProtoReader response = new ProtoReader(decompressed);
        while (response.nextField()) {
            switch (response.tag()) {
                case 1 -> parseMessage(response.readBytes()); // messagesList
                case 5 -> internalExt = response.readString();
                case 9 -> needAck = response.readBool();
                default -> response.skip();
            }
        }
        if (needAck) {
            sendAck(logId, internalExt);
        }
    }

    private void parseMessage(byte[] bytes) {
        String method = null;
        byte[] payload = null;
        ProtoReader message = new ProtoReader(bytes);
        while (message.nextField()) {
            switch (message.tag()) {
                case 1 -> method = message.readString();
                case 2 -> payload = message.readBytes();
                default -> message.skip();
            }
        }
        if (method == null || payload == null) {
            return;
        }
        if ("WebcastChatMessage".equals(method)) {
            parseChat(payload);
        } else if ("WebcastRoomUserSeqMessage".equals(method)) {
            parseRoomUserSeq(payload);
        }
    }

    private void parseChat(byte[] payload) {
        String content = null;
        String nickName = null;
        ProtoReader chat = new ProtoReader(payload);
        while (chat.nextField()) {
            switch (chat.tag()) {
                case 2 -> nickName = parseNickName(chat.readBytes()); // user
                case 3 -> content = chat.readString(); // content
                default -> chat.skip();
            }
        }
        if (content != null && !content.isEmpty()) {
            emit(LiveDanmaku.chat(nickName == null ? "" : nickName, content, null));
        }
    }

    private String parseNickName(byte[] userBytes) {
        ProtoReader user = new ProtoReader(userBytes);
        while (user.nextField()) {
            if (user.tag() == 3) { // nickName
                return user.readString();
            }
            user.skip();
        }
        return "";
    }

    private void parseRoomUserSeq(byte[] payload) {
        ProtoReader reader = new ProtoReader(payload);
        while (reader.nextField()) {
            if (reader.tag() == 7) { // totalUser
                emit(LiveDanmaku.online(String.valueOf(reader.readVarint())));
            } else {
                reader.skip();
            }
        }
    }

    private void sendAck(long logId, String internalExt) {
        // 对齐 pure_live/stream-rec:payloadType 用服务端下发的 internalExt
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(2, logId);
        writer.writeString(7, internalExt == null ? "ack" : internalExt);
        send(writer.toByteArray());
    }

    static byte[] gunzip(byte[] data) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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

    public static String randomUserId() {
        long id = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_999_999_999_999L);
        return String.valueOf(id);
    }
}
