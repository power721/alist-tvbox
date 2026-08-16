package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 虎牙直播弹幕客户端。
 * <p>
 * 走网页版当前使用的 webh5 信令:连接 {@code wss://wsapi.huya.com/?baseinfo=<Tars>},
 * 用命令 16 订阅消息组 {@code live:<主播uid>} / {@code chat:<主播uid>},此后服务端以
 * 命令 7(单条)和命令 22(按组批量)推送,两者的消息体都用 uri 区分:1400 弹幕、8006 在线人数。
 * <p>
 * 旧的 {@code cdnws.api.huya.com} + 命令 1 进房包(pure_live 那套)已被虎牙半废弃:注册仍返回成功、
 * 也会推十几秒弹幕,之后服务端就不再推送,且与心跳无关。逆向过程见 docs/huya-danmaku-protocol.md。
 */
public class HuyaDanmakuClient extends AbstractDanmakuClient {
    private static final String SERVER_URL = "wss://wsapi.huya.com/";
    private static final String HUYA_UA = "webh5&2608121011&websocket";

    private static final int CMD_PUSH = 7;
    private static final int CMD_SUBSCRIBE = 16;
    private static final int CMD_GROUP_PUSH = 22;

    private static final int URI_MESSAGE = 1400;
    private static final int URI_ONLINE = 8006;

    private final long presenterUid;

    /**
     * @param presenterUid 主播 uid,取 mp.huya.com profileRoom 接口的 data.profileInfo.uid
     */
    public HuyaDanmakuClient(long presenterUid, OkHttpClient okHttpClient, ScheduledExecutorService scheduler) {
        super("huya-danmaku", SERVER_URL + "?baseinfo=" + baseinfo(randomGuid()), Map.of(),
                30_000, withoutDeflate(okHttpClient), scheduler);
        this.presenterUid = presenterUid;
    }

    /**
     * 去掉 OkHttp 默认带上的 {@code Sec-WebSocket-Extensions: permessage-deflate}。
     * <p>
     * 虎牙会接受该扩展并回 {@code server_no_context_takeover;client_no_context_takeover},但握手后约 2 秒
     * 就彻底停止推送(此时连 WS ping 都不回 pong,连接仍开着);不带该扩展则可持续收弹幕。
     * 注意 OkHttp 对 WebSocket 调用**不执行** network interceptor,只能用 application interceptor。
     */
    private static OkHttpClient withoutDeflate(OkHttpClient okHttpClient) {
        return okHttpClient.newBuilder()
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                        .removeHeader("Sec-WebSocket-Extensions").build()))
                .build();
    }

    @Override
    protected void onConnected(WebSocket ws) {
        send(subscribe(List.of("live:" + presenterUid, "chat:" + presenterUid)));
    }

    /** 服务端不要求应用层心跳,靠 OkHttp 的 WebSocket ping 保活即可 */
    @Override
    protected byte[] heartbeatMessage() {
        return null;
    }

    private static String randomGuid() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** URL 上的 baseinfo:设备/语言标识的 Tars 编码,base64 后再 URL 转义 */
    static String baseinfo(String guid) {
        TarsWriter oos = new TarsWriter();
        oos.write(0, 0);
        oos.write(guid, 1);
        oos.write(HUYA_UA, 2);
        oos.write("HUYA&ZH&2052", 3);
        oos.write("", 4);
        oos.write("", 5);
        oos.write(0, 6);
        oos.write("", 7);
        oos.write("", 8);
        oos.write("", 9);
        return URLEncoder.encode(Base64.getEncoder().encodeToString(oos.toByteArray()), StandardCharsets.UTF_8);
    }

    /** 命令 16 = 订阅消息组;订阅成功后服务端才开始推送该组的消息 */
    static byte[] subscribe(List<String> groups) {
        TarsWriter payload = new TarsWriter();
        payload.write(groups, 0);
        payload.write("", 1);

        TarsWriter frame = new TarsWriter();
        frame.write(CMD_SUBSCRIBE, 0);
        frame.write(payload.toByteArray(), 1);
        return frame.toByteArray();
    }

    @Override
    protected void handleMessage(byte[] data) {
        TarsReader stream = new TarsReader(data);
        long cmd = stream.readInt(0);
        byte[] payload = stream.readBytes(1);
        if (cmd == CMD_PUSH) {
            TarsReader push = new TarsReader(payload);
            push.readInt(0); // pushType
            long uri = push.readInt(1);
            dispatch(uri, push.readBytes(2));
        } else if (cmd == CMD_GROUP_PUSH) {
            TarsReader group = new TarsReader(payload);
            group.readString(0); // 组名
            int size = group.enterList(1);
            for (int i = 0; i < size; i++) {
                if (!group.enterStructElement()) {
                    break;
                }
                long uri = group.readInt(0);
                byte[] body = group.readBytes(1);
                group.endStruct();
                dispatch(uri, body);
            }
        }
    }

    private void dispatch(long uri, byte[] body) {
        if (body.length == 0) {
            return;
        }
        if (uri == URI_MESSAGE) {
            parseMessage(body);
        } else if (uri == URI_ONLINE) {
            long online = new TarsReader(body).readInt(0);
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
