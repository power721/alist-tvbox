package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;

import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 虎牙直播弹幕客户端(移植 pure_live HuyaDanmaku)。
 * Tars 二进制协议:wss://cdnws.api.huya.com,进房包 uid=ayyuid,uri=1400 弹幕 / uri=8006 在线人数。
 */
@Slf4j
public class HuyaDanmakuClient extends AbstractDanmakuClient {
    private static final String SERVER_URL = "wss://cdnws.api.huya.com";
    private static final int URI_MESSAGE = 1400;
    private static final int URI_ONLINE = 8006;

    private final long ayyuid;

    public HuyaDanmakuClient(long ayyuid, OkHttpClient okHttpClient, ScheduledExecutorService scheduler) {
        super("huya-danmaku", SERVER_URL, Map.of(), 30_000, okHttpClient, scheduler);
        this.ayyuid = ayyuid;
    }

    @Override
    protected void onConnected(WebSocket ws) {
        send(buildJoinData(ayyuid));
    }

    /** OnlineUserHeartBeat 请求的 Tars 编码(pure_live 固定字节,内嵌 adr_wap 设备标识) */
    private static final byte[] HEARTBEAT = {
            0x00, 0x03, 0x1d, 0x00, 0x00, 0x69, 0x00, 0x00, 0x00, 0x69, 0x10, 0x03, 0x2c, 0x3c, 0x4c, 0x56,
            0x08, 0x6f, 0x6e, 0x6c, 0x69, 0x6e, 0x65, 0x75, 0x69, 0x66, 0x0f, 0x4f, 0x6e, 0x55, 0x73, 0x65,
            0x72, 0x48, 0x65, 0x61, 0x72, 0x74, 0x42, 0x65, 0x61, 0x74, 0x7d, 0x00, 0x00, 0x3c, 0x08, 0x00,
            0x01, 0x06, 0x04, 0x74, 0x52, 0x65, 0x71, 0x1d, 0x00, 0x00, 0x2f, 0x0a, 0x0a, 0x0c, 0x16, 0x00,
            0x26, 0x00, 0x36, 0x07, 0x61, 0x64, 0x72, 0x5f, 0x77, 0x61, 0x70, 0x46, 0x00, 0x0b, 0x12, 0x03,
            (byte) 0xae, (byte) 0xf0, 0x0f, 0x22, 0x03, (byte) 0xae, (byte) 0xf0, 0x0f, 0x3c, 0x42, 0x6d, 0x52,
            0x02, 0x60, 0x5c, 0x60, 0x01, 0x7c, (byte) 0x82, 0x00, 0x0b, (byte) 0xb0, 0x1f, (byte) 0x9c,
            (byte) 0xac, 0x0b, (byte) 0x8c, (byte) 0x98, 0x0c, (byte) 0xa8, 0x0c, 0x20,
    };

    @Override
    protected byte[] heartbeatMessage() {
        return HEARTBEAT;
    }

    static byte[] buildJoinData(long uid) {
        TarsWriter oos = new TarsWriter();
        oos.write(uid, 0);
        oos.write(false, 1);
        oos.write("", 2);
        oos.write("", 3);
        oos.write(0, 4);
        oos.write(0, 5);
        oos.write(uid, 6);
        oos.write(3, 7);

        TarsWriter wscmd = new TarsWriter();
        wscmd.write(1, 0);
        wscmd.write(oos.toByteArray(), 1);
        return wscmd.toByteArray();
    }

    @Override
    protected void handleMessage(byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace("huya ws frame: {}", HexFormat.of().formatHex(data));
        }
        TarsReader stream = new TarsReader(data);
        long type = stream.readInt(0);
        if (type != 7) {
            return;
        }
        byte[] payload = stream.readBytes(1);
        TarsReader push = new TarsReader(payload);
        push.readInt(0); // pushType
        long uri = push.readInt(1);
        byte[] msg = push.readBytes(2);
        if (uri == URI_MESSAGE) {
            parseMessage(msg);
        } else if (uri == URI_ONLINE) {
            long online = new TarsReader(msg).readInt(0);
            if (online > 0) {
                emit(LiveDanmaku.online(String.valueOf(online)));
            }
        }
    }

    private void parseMessage(byte[] msg) {
        TarsReader message = new TarsReader(msg);
        String nickName = "";
        if (message.enterStruct(0)) {
            nickName = message.readString(2);
            message.endStruct();
        }
        String content = message.readString(3);
        if (content.isEmpty()) {
            return;
        }
        long fontColor = 0;
        if (message.enterStruct(6)) {
            fontColor = message.readInt(0);
        }
        emit(LiveDanmaku.chat(nickName, content, colorOf(fontColor)));
    }

    private static String colorOf(long argb) {
        int rgb = (int) (argb & 0xFFFFFF);
        if (rgb == 0) {
            return null;
        }
        return String.format("#%06X", rgb);
    }
}
