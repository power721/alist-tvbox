package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 斗鱼直播弹幕客户端(移植 pure_live DouyuDanmaku)。
 * STT 文本协议,小端包:msgLength 写两遍 + msgType(689/690) + encrypted + reserved + body + \0。
 */
@Slf4j
public class DouyuDanmakuClient extends AbstractDanmakuClient {
    private static final String SERVER_URL = "wss://danmuproxy.douyu.com:8506";
    private static final int CLIENT_SEND = 689;

    private final String roomId;

    public DouyuDanmakuClient(String roomId, OkHttpClient okHttpClient, ScheduledExecutorService scheduler) {
        super("douyu-danmaku", SERVER_URL, Map.of(), 45_000, okHttpClient, scheduler);
        this.roomId = roomId;
    }

    @Override
    protected void onConnected(WebSocket ws) {
        send(serialize("type@=loginreq/roomid@=" + roomId + "/"));
        send(serialize("type@=joingroup/rid@=" + roomId + "/gid@=-9999/"));
    }

    @Override
    protected byte[] heartbeatMessage() {
        return serialize("type@=mrkl/");
    }

    static byte[] serialize(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        // 长度字段 = 4 + 2 + 1 + 1 + body + 1(不含第二个长度字段,斗鱼协议语义)
        int length = 4 + 4 + bytes.length + 1;
        // 实际字节 = 两个长度字段(8) + type/enc/res(4) + body + 尾0(1)
        byte[] buffer = new byte[bytes.length + 13];
        writeIntLE(buffer, 0, length);
        writeIntLE(buffer, 4, length);
        buffer[8] = (byte) (CLIENT_SEND & 0xFF);
        buffer[9] = (byte) ((CLIENT_SEND >> 8) & 0xFF);
        buffer[10] = 0; // encrypted
        buffer[11] = 0; // reserved
        System.arraycopy(bytes, 0, buffer, 12, bytes.length);
        buffer[buffer.length - 1] = 0;
        return buffer;
    }

    private static void writeIntLE(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    @Override
    protected void handleMessage(byte[] data) {
        // 一个 WebSocket 帧可能粘多个包,按包长循环切分(长度字段不含第二个长度字段,实际占 4 字节)
        int offset = 0;
        while (offset + 13 <= data.length) {
            int packetLength = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
            if (packetLength < 9 || offset + packetLength + 4 > data.length) {
                break;
            }
            int bodyLength = packetLength - 9; // 不含尾部的 \0
            String body = new String(data, offset + 12, bodyLength, StandardCharsets.UTF_8);
            parseStt(body);
            offset += packetLength + 4;
        }
    }

    private void parseStt(String body) {
        for (String segment : body.split("//")) {
            if (!segment.contains("type@=chatmsg")) {
                continue;
            }
            String userName = null;
            String message = null;
            String color = null;
            boolean hasDms = false;
            for (String field : segment.split("/")) {
                int index = field.indexOf("@=");
                if (index <= 0) {
                    continue;
                }
                String key = field.substring(0, index);
                String value = unescape(field.substring(index + 2));
                switch (key) {
                    case "nn" -> userName = value;
                    case "txt" -> message = value;
                    case "col" -> color = colorOf(parseIntSafe(value));
                    case "dms" -> hasDms = true;
                    default -> {
                    }
                }
            }
            // 无 dms 字段的是"阴间弹幕"(服务端过滤标记),跳过
            if (userName != null && message != null && hasDms) {
                emit(LiveDanmaku.chat(userName, message, color));
            }
        }
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String colorOf(int col) {
        return switch (col) {
            case 1 -> "#FF0000";
            case 2 -> "#1E87F0";
            case 3 -> "#7AC84B";
            case 4 -> "#FF7F00";
            case 5 -> "#9B39F4";
            case 6 -> "#FF69B4";
            default -> null;
        };
    }

    static String unescape(String value) {
        return value.replace("@S", "/").replace("@A", "@");
    }
}
