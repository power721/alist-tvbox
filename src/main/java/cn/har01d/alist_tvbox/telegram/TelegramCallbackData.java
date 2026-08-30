package cn.har01d.alist_tvbox.telegram;

/**
 * callback data 编解码:格式 {@code action} / {@code action:arg} / {@code action:arg:arg2}(arg 为 id/页码/索引,
 * pdadd 第二参数为季号)。
 * <p>
 * TG 限制 64 字节,全集合最长约 20 字节;结构化上下文(搜索结果/片单条目本体)走服务端暂存,不塞进 callback。
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
    /** 追更日历(播出时间轴):无状态,每次实时拉 schedule(uid) */
    public static final String CALENDAR = "cal";
    // 片单追更:分类列表 → 分类条目(索引)→ 条目详情 → 按季订阅
    public static final String PIAN_DAN = "pd";
    public static final String PIAN_DAN_CATEGORY = "pdc";
    public static final String PIAN_DAN_PAGE = "pdl";
    public static final String PIAN_DAN_ENTRY = "pde";
    public static final String PIAN_DAN_ADD = "pdadd";

    private TelegramCallbackData() {
    }

    /** 解析后的回调:action + 可选数值参数(订阅 id / 页码 / 暂存索引),pdadd 第二参数为季号。 */
    public record Callback(String action, int arg, Integer arg2) {
    }

    public static Callback parse(String data) {
        if (data == null || data.isBlank() || data.length() > 64) {
            return null;
        }
        String[] parts = data.split(":");
        String action = parts[0];
        Integer arg = null;
        Integer arg2 = null;
        try {
            if (parts.length > 1) {
                arg = Integer.parseInt(parts[1]);
            }
            if (parts.length > 2) {
                arg2 = Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (parts.length > 3) {
            return null;
        }
        switch (action) {
            case HOME, SEARCH, CANCEL, INBOX, CALENDAR, PIAN_DAN -> {
                return new Callback(action, 0, null);
            }
            case SUBS, SUB, SUB_DELETE, SUB_DELETE_CONFIRM, SUB_CHECK, SUB_UPDATE, SUB_PAUSE,
                 SUB_RESUME, PICK, ADD, RESULT_PAGE, RESULT_BACK, PIAN_DAN_CATEGORY, PIAN_DAN_PAGE,
                 PIAN_DAN_ENTRY, PIAN_DAN_ADD -> {
                return arg == null ? null : new Callback(action, arg, arg2);
            }
            default -> {
                return null;
            }
        }
    }

    public static String of(String action, long arg) {
        return action + ":" + arg;
    }

    public static String of(String action, long arg, long arg2) {
        return action + ":" + arg + ":" + arg2;
    }
}
