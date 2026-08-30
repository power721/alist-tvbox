package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Telegram 交互消息的文本 + inline keyboard 构造(纯函数,便于离线单测)。
 * <p>
 * 统一 HTML parse mode:外部文本(剧名/简介/事件明细)一律 {@link #esc} 转义,防注入炸发送;
 * 文本超长按行截断到 3800(TG 硬上限 4096,留编码膨胀余量,与通知卡片同口径)。
 */
public final class TelegramRenderer {
    /** 订阅列表每页条数 */
    static final int SUBS_PAGE_SIZE = 10;
    /** 搜索结果每页条数 */
    static final int RESULTS_PAGE_SIZE = 8;
    private static final int MAX_TEXT_LENGTH = 3800;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public record Rendered(String text, List<List<TelegramButton>> keyboard) {
    }

    private TelegramRenderer() {
    }

    public static Rendered menu() {
        return new Rendered("🎬 <b>追剧助手</b>\n\n欢迎!选择操作:",
                List.of(
                        row(new TelegramButton("📺 我的订阅", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0)),
                                new TelegramButton("🔍 搜索追剧", TelegramCallbackData.SEARCH)),
                        row(new TelegramButton("🔄 最近更新", TelegramCallbackData.INBOX))));
    }

    public static Rendered subsPage(List<MediaSubscriptionDto> all, int page) {
        if (all.isEmpty()) {
            return new Rendered("📺 <b>我的追剧订阅</b>\n\n还没有订阅任何媒体,先搜索一部吧。",
                    List.of(row(new TelegramButton("🔍 搜索追剧", TelegramCallbackData.SEARCH),
                            new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
        }
        int pages = (all.size() + SUBS_PAGE_SIZE - 1) / SUBS_PAGE_SIZE;
        int from = Math.min(page, pages - 1) * SUBS_PAGE_SIZE;
        int to = Math.min(from + SUBS_PAGE_SIZE, all.size());
        StringBuilder text = new StringBuilder("📺 <b>我的追剧订阅</b>(共 " + all.size() + " 部)\n");
        List<List<TelegramButton>> keyboard = new ArrayList<>();
        for (int i = from; i < to; i++) {
            MediaSubscriptionDto dto = all.get(i);
            text.append("\n").append(i + 1).append(". ").append(titleLine(dto));
            keyboard.add(row(new TelegramButton(buttonLabel(dto.getName(), dto.getSeason()),
                    TelegramCallbackData.of(TelegramCallbackData.SUB, dto.getId()))));
        }
        appendNav(keyboard, page, pages);
        return new Rendered(truncate(text), keyboard);
    }

    /** 订阅列表/详情共用的标题行:名字 + 季 + 进度 + 状态 emoji。 */
    private static String titleLine(MediaSubscriptionDto dto) {
        StringBuilder line = new StringBuilder("<b>").append(esc(name(dto))).append("</b>");
        if (dto.getSeason() != null && dto.getSeason() > 1) {
            line.append(" 第").append(dto.getSeason()).append("季");
        }
        int current = dto.getCurrentEpisodes() == null ? 0 : dto.getCurrentEpisodes();
        Integer official = dto.getOfficialEpisodes();
        line.append(" · ").append(current).append("/").append(official == null ? "?" : official);
        line.append(" ").append(statusEmoji(dto.getStatus()));
        return line.toString();
    }

    /** 详情页(数据来自 MediaSubscriptionService.detail 的聚合 Map)。 */
    @SuppressWarnings("unchecked")
    public static Rendered subDetail(MediaSubscriptionDto dto, Map<String, Object> detail) {
        StringBuilder text = new StringBuilder("📺 <b>").append(esc(name(dto))).append("</b>\n\n");
        text.append("状态:").append(statusText(dto.getStatus()));
        if (dto.getSeason() != null && dto.getSeason() > 0) {
            text.append(" · 第 ").append(dto.getSeason()).append(" 季");
        }
        text.append("\n本地已有:").append(dto.getCurrentEpisodes() == null ? 0 : dto.getCurrentEpisodes());
        Integer official = dto.getOfficialEpisodes();
        text.append(" 集 / 官方已播:").append(official == null ? "未知" : official).append(" 集");
        if (dto.getMissingEpisodes() != null && !dto.getMissingEpisodes().isEmpty()) {
            List<Integer> missing = dto.getMissingEpisodes();
            String joined = missing.size() > 10
                    ? StringUtils.join(missing.subList(0, 10), ",") + " 等 " + missing.size() + " 集"
                    : StringUtils.join(missing, ",");
            text.append("\n缺集:").append(joined);
        }
        if (dto.getNextAirTime() != null && dto.getNextAirTime() > 0) {
            text.append("\n下集播出:").append(TIME_FORMAT.format(Instant.ofEpochMilli(dto.getNextAirTime())));
        }
        Map<String, Object> media = detail == null ? null : (Map<String, Object>) detail.get("media");
        if (media != null) {
            Object overview = media.get("overview");
            if (overview != null && StringUtils.isNotBlank(String.valueOf(overview))) {
                text.append("\n\n").append(esc(StringUtils.abbreviate(String.valueOf(overview), 300)));
            }
        }
        boolean paused = MediaSubscription.STATUS_PAUSED.equals(dto.getStatus());
        List<List<TelegramButton>> keyboard = new ArrayList<>(List.of(
                row(new TelegramButton("⚡ 查更新", TelegramCallbackData.of(TelegramCallbackData.SUB_UPDATE, dto.getId())),
                        new TelegramButton("🔄 巡检", TelegramCallbackData.of(TelegramCallbackData.SUB_CHECK, dto.getId()))),
                row(new TelegramButton(paused ? "▶ 恢复" : "⏸ 暂停",
                        TelegramCallbackData.of(paused ? TelegramCallbackData.SUB_RESUME : TelegramCallbackData.SUB_PAUSE, dto.getId()))),
                row(new TelegramButton("❌ 退订", TelegramCallbackData.of(TelegramCallbackData.SUB_DELETE, dto.getId()))),
                row(new TelegramButton("🔍 搜索", TelegramCallbackData.SEARCH),
                        new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
        return new Rendered(truncate(text), keyboard);
    }

    public static Rendered searchPrompt() {
        return new Rendered("🔍 <b>搜索追剧</b>\n\n请直接输入剧名(100 字以内):\n例如:庆余年 / 斗破苍穹",
                List.of(row(new TelegramButton("❌ 取消", TelegramCallbackData.CANCEL))));
    }

    public static Rendered searchResults(String keyword, List<MetadataSearchItem> items, int page) {
        if (items.isEmpty()) {
            return new Rendered("🔍 没有找到「" + esc(keyword) + "」的相关结果,换个关键词试试。",
                    List.of(row(new TelegramButton("🔍 重新搜索", TelegramCallbackData.SEARCH)),
                            row(new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
        }
        int pages = (items.size() + RESULTS_PAGE_SIZE - 1) / RESULTS_PAGE_SIZE;
        int cur = Math.min(page, pages - 1);
        int from = cur * RESULTS_PAGE_SIZE;
        int to = Math.min(from + RESULTS_PAGE_SIZE, items.size());
        StringBuilder text = new StringBuilder("🔍 「").append(esc(keyword)).append("」的搜索结果(共 ")
                .append(items.size()).append(" 条)\n");
        List<List<TelegramButton>> keyboard = new ArrayList<>();
        for (int i = from; i < to; i++) {
            MetadataSearchItem item = items.get(i);
            text.append("\n").append(i + 1).append(". ").append(esc(abbrev(item.getName(), 40)));
            if (StringUtils.isNotBlank(item.getYear())) {
                text.append("(").append(esc(item.getYear())).append(")");
            }
            keyboard.add(row(new TelegramButton(
                    buttonLabel(abbrev(item.getName(), 24), item.getYear()),
                    TelegramCallbackData.of(TelegramCallbackData.PICK, i))));
        }
        List<TelegramButton> nav = new ArrayList<>();
        if (cur > 0) {
            nav.add(new TelegramButton("◀", TelegramCallbackData.of(TelegramCallbackData.RESULT_PAGE, cur - 1)));
        }
        if (cur < pages - 1) {
            nav.add(new TelegramButton("▶", TelegramCallbackData.of(TelegramCallbackData.RESULT_PAGE, cur + 1)));
        }
        if (!nav.isEmpty()) {
            keyboard.add(nav);
        }
        keyboard.add(row(new TelegramButton("🔍 重新搜索", TelegramCallbackData.SEARCH),
                new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME)));
        return new Rendered(truncate(text), keyboard);
    }

    public static Rendered searchDetail(MetadataSearchItem item, int index, int resultPage, boolean subscribed) {
        StringBuilder text = new StringBuilder("🎬 <b>").append(esc(abbrev(item.getName(), 60))).append("</b>");
        if (StringUtils.isNotBlank(item.getYear())) {
            text.append("(").append(esc(item.getYear())).append(")");
        }
        text.append("\n\n来源:").append(esc(item.getProvider()));
        if (StringUtils.isNotBlank(item.getScore())) {
            text.append(" · 评分 ").append(esc(item.getScore()));
        }
        if (StringUtils.isNotBlank(item.getDescription())) {
            text.append("\n\n").append(esc(StringUtils.abbreviate(item.getDescription(), 400)));
        }
        List<List<TelegramButton>> keyboard = new ArrayList<>();
        if (subscribed) {
            keyboard.add(row(new TelegramButton("✅ 已在追剧列表 · 查看", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0))));
        } else {
            keyboard.add(row(new TelegramButton("➕ 加入追剧", TelegramCallbackData.of(TelegramCallbackData.ADD, index))));
        }
        keyboard.add(row(new TelegramButton("◀ 返回结果", TelegramCallbackData.of(TelegramCallbackData.RESULT_BACK, resultPage))));
        keyboard.add(row(new TelegramButton("🔍 重新搜索", TelegramCallbackData.SEARCH),
                new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME)));
        return new Rendered(truncate(text), keyboard);
    }

    public static Rendered confirmDelete(MediaSubscriptionDto dto) {
        return new Rendered("❌ <b>确定退订?</b>\n\n📺 " + titleLine(dto)
                        + "\n\n退订将清理已挂载资源与历史记录。",
                List.of(
                        row(new TelegramButton("✅ 确认退订",
                                TelegramCallbackData.of(TelegramCallbackData.SUB_DELETE_CONFIRM, dto.getId()))),
                        row(new TelegramButton("保留", TelegramCallbackData.of(TelegramCallbackData.SUB, dto.getId())),
                                new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
    }

    public static Rendered subscribed(MediaSubscriptionDto dto) {
        return new Rendered("✅ 已加入追剧:" + titleLine(dto)
                        + "\n\n首轮巡检已开始,搜索与挂载完成后即可播放。",
                List.of(
                        row(new TelegramButton("📺 查看订阅", TelegramCallbackData.of(TelegramCallbackData.SUB, dto.getId()))),
                        row(new TelegramButton("🔍 继续搜索", TelegramCallbackData.SEARCH),
                                new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
    }

    public static Rendered alreadySubscribed(String name) {
        return new Rendered("✅ 「" + esc(abbrev(name, 60)) + "」已经在追剧列表里,无需重复添加。",
                List.of(row(new TelegramButton("📺 我的订阅", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0)),
                        new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
    }

    public static Rendered deleted(String name) {
        return new Rendered("✅ 已退订「" + esc(abbrev(name, 60)) + "」,挂载与记录已清理。",
                List.of(row(new TelegramButton("📺 我的订阅", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0)),
                        new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
    }

    @SuppressWarnings("unchecked")
    public static Rendered inbox(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return new Rendered("🔄 最近 3 天没有新动态。",
                    List.of(row(new TelegramButton("📺 我的订阅", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0)),
                            new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
        }
        StringBuilder text = new StringBuilder("🔄 <b>最近更新</b>(近 3 天)\n");
        String lastName = null;
        int lines = 0;
        for (Map<String, Object> event : events) {
            if (lines >= 30) {
                text.append("\n…仅显示前 30 条");
                break;
            }
            String name = String.valueOf(event.getOrDefault("name", ""));
            if (!name.equals(lastName)) {
                text.append("\n📺 <b>").append(esc(abbrev(name, 40))).append("</b>");
                lastName = name;
                lines++;
            }
            Object time = event.get("createdTime");
            String when = time instanceof Number number ? TIME_FORMAT.format(Instant.ofEpochMilli(number.longValue())) : "";
            text.append("\n").append(eventEmoji(String.valueOf(event.getOrDefault("type", ""))))
                    .append(" ").append(esc(abbrev(String.valueOf(event.getOrDefault("detail", "")), 60)));
            if (!when.isEmpty()) {
                text.append(" · ").append(when);
            }
            lines++;
        }
        return new Rendered(truncate(text),
                List.of(row(new TelegramButton("📺 我的订阅", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0)),
                        new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
    }

    public static Rendered notFound(long id) {
        return new Rendered("⚠️ 订阅 " + id + " 不存在或已删除。",
                List.of(row(new TelegramButton("📺 我的订阅", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0)),
                        new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
    }

    /** 单条提示文本(无键盘),入参由调用方负责转义。 */
    public static Rendered message(String text) {
        return new Rendered(text, null);
    }

    private static void appendNav(List<List<TelegramButton>> keyboard, int page, int pages) {
        List<TelegramButton> nav = new ArrayList<>();
        if (page > 0) {
            nav.add(new TelegramButton("◀ 上一页", TelegramCallbackData.of(TelegramCallbackData.SUBS, page - 1)));
        }
        if (page < pages - 1) {
            nav.add(new TelegramButton("下一页 ▶", TelegramCallbackData.of(TelegramCallbackData.SUBS, page + 1)));
        }
        if (!nav.isEmpty()) {
            keyboard.add(nav);
        }
        keyboard.add(row(new TelegramButton("🔍 搜索追剧", TelegramCallbackData.SEARCH),
                new TelegramButton("🔄 最近更新", TelegramCallbackData.INBOX)));
        keyboard.add(row(new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME)));
    }

    private static String statusEmoji(String status) {
        if (MediaSubscription.STATUS_PAUSED.equals(status)) {
            return "⏸";
        }
        if (MediaSubscription.STATUS_ENDED.equals(status)) {
            return "✅";
        }
        if (MediaSubscription.STATUS_ERROR.equals(status)) {
            return "⚠️";
        }
        return "🔄";
    }

    private static String statusText(String status) {
        if (MediaSubscription.STATUS_PAUSED.equals(status)) {
            return "⏸ 已暂停";
        }
        if (MediaSubscription.STATUS_ENDED.equals(status)) {
            return "✅ 已完结";
        }
        if (MediaSubscription.STATUS_ERROR.equals(status)) {
            return "⚠️ 异常";
        }
        return "🔄 追更中";
    }

    private static String eventEmoji(String type) {
        if (MediaSubscriptionEvent.TYPE_NEW_EPISODE.equals(type)) {
            return "✅";
        }
        if (MediaSubscriptionEvent.TYPE_SOURCE_REPLACED.equals(type)) {
            return "🔁";
        }
        if (MediaSubscriptionEvent.TYPE_GAP_FILLED.equals(type)) {
            return "🧩";
        }
        return "•";
    }

    private static String name(MediaSubscriptionDto dto) {
        return abbrev(dto.getName(), 60);
    }

    private static String abbrev(String text, int max) {
        return StringUtils.abbreviate(StringUtils.defaultString(text), max);
    }

    /** 按钮文案:名字 + 可选年份后缀,截短防溢出。 */
    private static String buttonLabel(String name, Integer season) {
        return season != null && season > 1 ? abbrev(name, 22) + " S" + season : abbrev(name, 26);
    }

    private static String buttonLabel(String name, String year) {
        return StringUtils.isBlank(year) ? name : name + " · " + year;
    }

    /** 超长按行截断(当前各视图自身字段有上限,此为兜底防御;包可见便于直测)。 */
    static String truncate(StringBuilder text) {
        String s = text.toString();
        if (s.length() <= MAX_TEXT_LENGTH) {
            return s;
        }
        int cut = s.lastIndexOf("\n", MAX_TEXT_LENGTH - 2);
        return (cut > 0 ? s.substring(0, cut) : s.substring(0, MAX_TEXT_LENGTH - 2)) + "…";
    }

    static String esc(String text) {
        return StringEscapeUtils.escapeHtml4(StringUtils.defaultString(text));
    }

    private static List<TelegramButton> row(TelegramButton... buttons) {
        return List.of(buttons);
    }
}
