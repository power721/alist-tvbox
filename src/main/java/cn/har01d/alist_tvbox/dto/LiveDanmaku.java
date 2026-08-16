package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/**
 * 直播弹幕消息。seq 为房间会话内单调递增序号,客户端以此为游标做增量轮询。
 */
@Data
public class LiveDanmaku {
    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_ONLINE = "online";

    private long seq;
    private String userName;
    private String message;
    /** #RRGGBB,空表示默认白色 */
    private String color;
    /** chat=弹幕, online=在线人数(message 为数值文本) */
    private String type;

    public LiveDanmaku() {
    }

    public LiveDanmaku(String userName, String message, String color, String type) {
        this.userName = userName;
        this.message = message;
        this.color = color;
        this.type = type;
    }

    public static LiveDanmaku chat(String userName, String message, String color) {
        return new LiveDanmaku(userName, message, color, TYPE_CHAT);
    }

    public static LiveDanmaku online(String value) {
        return new LiveDanmaku("", value, null, TYPE_ONLINE);
    }
}
