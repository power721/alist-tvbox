package cn.har01d.alist_tvbox.live.danmaku;

import cn.har01d.alist_tvbox.dto.LiveDanmaku;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Twitch 直播弹幕客户端(移植 pure_live TwitchDanmaku)。
 * 标准 IRC over WebSocket,匿名 justinfan 只读登录无需凭证;
 * 服务端周期性 PING,应答 PONG 即保活。带 tags 能力的 PRIVMSG 行格式:
 * {@code @color=#0000FF;display-name=Foo;... :login!login@login.tmi.twitch.tv PRIVMSG #channel :消息}
 */
@Slf4j
public class TwitchDanmakuClient extends AbstractDanmakuClient {
    private static final String SERVER_URL = "wss://irc-ws.chat.twitch.tv";
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final String channel;

    public TwitchDanmakuClient(String roomId, OkHttpClient okHttpClient, ScheduledExecutorService scheduler) {
        // login 全小写,大写 JOIN 会被服务端拒绝
        super("twitch-danmaku", SERVER_URL, Map.of(), 40_000, okHttpClient, scheduler);
        this.channel = roomId.toLowerCase(Locale.ROOT);
    }

    @Override
    protected void onConnected(WebSocket ws) {
        // 只申请 tags 能力(display-name/color);commands/membership 的事件用不上
        String nick = "justinfan" + (1000 + ThreadLocalRandom.current().nextInt(99000));
        sendText("CAP REQ :twitch.tv/tags");
        sendText("PASS SCHMOOPIIE");
        sendText("NICK " + nick);
        sendText("JOIN #" + channel);
    }

    @Override
    protected byte[] heartbeatMessage() {
        return null;
    }

    @Override
    protected void handleMessage(byte[] data) {
        // Twitch IRC 全部是文本帧
    }

    @Override
    protected void handleTextMessage(String text) {
        for (String line : text.split("\r?\n")) {
            handleLine(line);
        }
    }

    private void handleLine(String line) {
        if (line.isEmpty()) {
            return;
        }
        // 按 IRC 规范应答服务端保活探测
        if (line.startsWith("PING")) {
            sendText(line.replaceFirst("PING", "PONG"));
            return;
        }

        int privIndex = line.indexOf(" PRIVMSG ");
        if (privIndex < 0) {
            return;
        }

        Map<String, String> tags = parseTags(line, privIndex);
        // 消息体是 PRIVMSG 目标之后的第一个 ':' 起的整段(内容可再含 ':')
        int messageStart = line.indexOf(':', privIndex);
        if (messageStart < 0 || messageStart + 1 >= line.length()) {
            return;
        }
        String message = line.substring(messageStart + 1);

        String userName = tags.get("display-name");
        if (userName == null || userName.isEmpty()) {
            userName = parseLogin(line, privIndex);
        }
        if (userName.isEmpty() || message.isEmpty()) {
            return;
        }

        String color = tags.getOrDefault("color", "");
        emit(LiveDanmaku.chat(userName, message, COLOR_PATTERN.matcher(color).matches() ? color : null));
    }

    /** 解析行首 {@code @k=v;k=v;} 标签串为 Map,无标签返回空 Map */
    private static Map<String, String> parseTags(String line, int limit) {
        Map<String, String> tags = new HashMap<>();
        if (!line.startsWith("@")) {
            return tags;
        }
        int end = line.indexOf(' ');
        if (end < 0 || end > limit) {
            return tags;
        }
        for (String entry : line.substring(1, end).split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0) {
                tags.put(entry.substring(0, eq), entry.substring(eq + 1));
            }
        }
        return tags;
    }

    /** 从 {@code :login!login@... PRIVMSG} 前缀取登录名,display-name 缺失时兜底 */
    private static String parseLogin(String line, int privIndex) {
        if (line.startsWith(":")) {
            int bang = line.indexOf('!');
            if (bang > 0 && bang < privIndex) {
                return line.substring(1, bang);
            }
        }
        return "";
    }
}
