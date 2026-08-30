package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                        row(new TelegramButton("🎞 片单追更", TelegramCallbackData.PIAN_DAN),
                                new TelegramButton("🔄 最近更新", TelegramCallbackData.INBOX))));
    }

    public static Rendered subsPage(List<MediaSubscriptionDto> all, int page) {
        if (all.isEmpty()) {
            return new Rendered("📺 <b>我的追剧订阅</b>\n\n还没有订阅任何媒体,先搜索一部吧。",
                    List.of(row(new TelegramButton("🔍 搜索追剧", TelegramCallbackData.SEARCH),
                                    new TelegramButton("🎞 片单追更", TelegramCallbackData.PIAN_DAN)),
                            row(new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
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

    /**
     * 片单分类页:豆瓣/TMDB 榜单(subscriptionCategory 已剔除纯电影类目),两列铺一屏。
     * <p>
     * 分类索引即 callback 参数 —— 分类集合按配置静态构建(无网络),每次回调按同一口径重建,索引稳定;
     * 真变了(改片单配置)也只是点到邻近分类,不越权、不炸会话。
     */
    public static Rendered pianDanCategories(List<Category> categories) {
        if (categories == null || categories.isEmpty()) {
            return new Rendered("🎞 片单暂时不可用,请稍后重试。",
                    List.of(row(new TelegramButton("🔍 搜索追剧", TelegramCallbackData.SEARCH),
                            new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
        }
        List<List<TelegramButton>> keyboard = new ArrayList<>();
        List<TelegramButton> pair = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            pair.add(new TelegramButton(abbrev(categories.get(i).getType_name(), 16),
                    TelegramCallbackData.of(TelegramCallbackData.PIAN_DAN_CATEGORY, i)));
            if (pair.size() == 2) {
                keyboard.add(List.copyOf(pair));
                pair.clear();
            }
        }
        if (!pair.isEmpty()) {
            keyboard.add(List.copyOf(pair));
        }
        keyboard.add(row(new TelegramButton("🔍 搜索追剧", TelegramCallbackData.SEARCH),
                new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME)));
        return new Rendered("🎞 <b>片单追更</b>\n\n从榜单里挑一部直接加入追剧,选个分类:", keyboard);
    }

    /** 片单条目列表:一屏 10 条,已追条目带 ✅;{@code subscribed} 是本页内已订阅条目的下标集合。 */
    public static Rendered pianDanList(String categoryName, List<MovieDetail> items, Set<Integer> subscribed,
                                       int page, boolean hasNext) {
        StringBuilder text = new StringBuilder("🎞 <b>").append(esc(categoryName)).append("</b>");
        List<List<TelegramButton>> keyboard = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            text.append("\n\n这一页没有内容,换个分类或返回上一页试试。");
        } else {
            text.append("(第 ").append(page + 1).append(" 页)\n");
            for (int i = 0; i < items.size(); i++) {
                MovieDetail item = items.get(i);
                boolean followed = subscribed != null && subscribed.contains(i);
                text.append("\n").append(i + 1).append(". <b>").append(esc(abbrev(item.getVod_name(), 40))).append("</b>");
                if (StringUtils.isNotBlank(item.getVod_year())) {
                    text.append("(").append(esc(item.getVod_year())).append(")");
                }
                if (StringUtils.isNotBlank(item.getVod_remarks())) {
                    text.append(" · ").append(esc(abbrev(item.getVod_remarks(), 12)));
                }
                if (followed) {
                    text.append(" ✅已追");
                }
                keyboard.add(row(new TelegramButton(
                        (followed ? "✅ " : "") + abbrev(item.getVod_name(), 24),
                        TelegramCallbackData.of(TelegramCallbackData.PIAN_DAN_ENTRY, i))));
            }
        }
        List<TelegramButton> nav = new ArrayList<>();
        if (page > 0) {
            nav.add(new TelegramButton("◀ 上一页", TelegramCallbackData.of(TelegramCallbackData.PIAN_DAN_PAGE, page - 1)));
        }
        if (hasNext) {
            nav.add(new TelegramButton("下一页 ▶", TelegramCallbackData.of(TelegramCallbackData.PIAN_DAN_PAGE, page + 1)));
        }
        if (!nav.isEmpty()) {
            keyboard.add(nav);
        }
        keyboard.add(row(new TelegramButton("🎞 换个分类", TelegramCallbackData.PIAN_DAN),
                new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME)));
        return new Rendered(truncate(text), keyboard);
    }

    /**
     * 片单条目详情:TMDB 条目带简介/演员/季号,豆瓣条目只有标题。
     * <p>
     * 多季剧按季展开(与电视端「➕ 追剧·第N季」同口径,订阅精确到季),已追季显示 ✅ 并跳订阅列表 ——
     * 退订统一走「我的订阅 → 退订」的两步确认,不在片单里放删除动作。
     */
    public static Rendered pianDanEntry(MovieDetail item, int index, int page, boolean subscribed,
                                        List<Integer> seasons, Set<Integer> subscribedSeasons) {
        StringBuilder text = new StringBuilder("🎬 <b>").append(esc(abbrev(item.getVod_name(), 60))).append("</b>");
        if (StringUtils.isNotBlank(item.getVod_year())) {
            text.append("(").append(esc(item.getVod_year())).append(")");
        }
        if (StringUtils.isNotBlank(item.getType_name())) {
            text.append("\n\n类型:").append(esc(abbrev(item.getType_name(), 60)));
        }
        if (StringUtils.isNotBlank(item.getVod_remarks())) {
            text.append("\n评分:").append(esc(abbrev(item.getVod_remarks(), 12)));
        }
        if (StringUtils.isNotBlank(item.getVod_actor())) {
            text.append("\n主演:").append(esc(abbrev(item.getVod_actor(), 60)));
        }
        if (StringUtils.isNotBlank(item.getVod_content())) {
            text.append("\n\n").append(esc(StringUtils.abbreviate(item.getVod_content(), 400)));
        }
        List<List<TelegramButton>> keyboard = new ArrayList<>();
        if (seasons != null && !seasons.isEmpty()) {
            List<TelegramButton> group = new ArrayList<>();
            for (Integer season : seasons) {
                boolean followed = subscribedSeasons != null && subscribedSeasons.contains(season);
                group.add(followed
                        ? new TelegramButton("✅ 第" + season + "季",
                                TelegramCallbackData.of(TelegramCallbackData.SUBS, 0))
                        : new TelegramButton("➕ 第" + season + "季",
                                TelegramCallbackData.of(TelegramCallbackData.PIAN_DAN_ADD, index, season)));
                if (group.size() == 3) {
                    keyboard.add(List.copyOf(group));
                    group.clear();
                }
            }
            if (!group.isEmpty()) {
                keyboard.add(List.copyOf(group));
            }
        } else if (subscribed) {
            keyboard.add(row(new TelegramButton("✅ 已在追剧列表 · 查看",
                    TelegramCallbackData.of(TelegramCallbackData.SUBS, 0))));
        } else {
            keyboard.add(row(new TelegramButton("➕ 加入追剧",
                    TelegramCallbackData.of(TelegramCallbackData.PIAN_DAN_ADD, index))));
        }
        keyboard.add(row(new TelegramButton("◀ 返回列表",
                TelegramCallbackData.of(TelegramCallbackData.PIAN_DAN_PAGE, page))));
        keyboard.add(row(new TelegramButton("🎞 换个分类", TelegramCallbackData.PIAN_DAN),
                new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME)));
        return new Rendered(truncate(text), keyboard);
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
                new TelegramButton("🎞 片单追更", TelegramCallbackData.PIAN_DAN)));
        keyboard.add(row(new TelegramButton("🔄 最近更新", TelegramCallbackData.INBOX),
                new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME)));
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

    /**
     * Telegram HTML parse mode 只认 {@code &amp; &lt; &gt; &quot;} 四个实体,其余原样显示 ——
     * escapeHtml4 会把「·」转成 {@code &middot;}、「é」转成 {@code &eacute;},客户端照实体文本渲染。
     * 剧名/片单分类名带「·」极常见(豆瓣·热门电视剧、哈利·波特),故只转义解析器真正在意的字符。
     */
    static String esc(String text) {
        return StringUtils.defaultString(text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static List<TelegramButton> row(TelegramButton... buttons) {
        return List.of(buttons);
    }
}
