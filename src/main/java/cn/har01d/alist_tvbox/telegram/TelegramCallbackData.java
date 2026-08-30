package cn.har01d.alist_tvbox.telegram;

/**
 * callback data 编解码:格式 {@code action} 或 {@code action:arg}(arg 为 id/页码/搜索结果索引)。
 * <p>
 * TG 限制 64 字节,全集合最长约 12 字节;结构化上下文(搜索结果本体)走服务端暂存,不塞进 callback。
 * 解析容错:未知/畸形 data 返回 null,由调用方静默 answer 掉,防野按钮炸会话。
 */
public final class TelegramCallbackData {
    public static final String HOME = "home";
    public static final String SUBS = "subs";
    public static final String SUB = "sub";
    public static final String SUB_DELETE = "subdel";
    public static final String SUB_DELETE_CONFIRM = "subdelc";
    public static final String SUB_CHECK = "subchk";
    public static final String SUB_UPDATE = "subupd";
    public static final String SUB_PAUSE = "subpause";
    public static final String SUB_RESUME = "subresume";
    public static final String SEARCH = "search";
    public static final String CANCEL = "cancel";
    public static final String PICK = "pick";
    public static final String ADD = "add";
    public static final String RESULT_PAGE = "pickp";
    public static final String RESULT_BACK = "res";
    public static final String INBOX = "inbox";

    private TelegramCallbackData() {
    }

    /** 解析后的回调:action + 可选数值参数(订阅 id / 页码 / 搜索结果索引)。 */
    public record Callback(String action, int arg) {
    }

    public static Callback parse(String data) {
        if (data == null || data.isBlank() || data.length() > 64) {
            return null;
        }
        int sep = data.indexOf(':');
        String action = sep < 0 ? data : data.substring(0, sep);
        Integer arg = null;
        if (sep >= 0) {
            try {
                arg = Integer.parseInt(data.substring(sep + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        switch (action) {
            case HOME, SEARCH, CANCEL, INBOX -> {
                return new Callback(action, 0);
            }
            case SUBS, SUB, SUB_DELETE, SUB_DELETE_CONFIRM, SUB_CHECK, SUB_UPDATE, SUB_PAUSE,
                 SUB_RESUME, PICK, ADD, RESULT_PAGE, RESULT_BACK -> {
                return arg == null ? null : new Callback(action, arg);
            }
            default -> {
                return null;
            }
        }
    }

    public static String of(String action, long arg) {
        return action + ":" + arg;
    }
}
