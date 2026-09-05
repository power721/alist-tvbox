package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEvent;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.util.Constants;
import org.apache.commons.lang3.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    static final int RESULTS_PAGE_SIZE = 10;
    private static final int MAX_TEXT_LENGTH = 3800;
    /** 服务层封面代理前缀({@code MediaSubscriptionService.proxiedCover});海报要还原成上游直链才抓得到。 */
    private static final String COVER_PROXY_PREFIX = "/images?url=";
    /** 日历条目行封顶(22 订阅 × 10 天可轻松过百行);超出截断并注明。 */
    private static final int CALENDAR_MAX_LINES = 40;
    /** 今明快捷跳转钮上限,超出只在正文里列。 */
    private static final int CALENDAR_MAX_SHORTCUTS = 6;
    /** 播出/事件时间带周几(EE,中文短称"周六"):欧美剧/追番周播,周几才是更新心智锚点。 */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd EE HH:mm", Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(Constants.ZONE_ID));

    /**
     * 渲染结果:文本 + inline keyboard + 可选海报直链。
     * <p>
     * 海报不发图片消息 —— TG 不允许把文本消息编辑成媒体消息(editMessageMedia 要求消息本身带媒体),
     * 而本 Bot 全程单锚点编辑;改走 link preview(link_preview_options.url,图不必出现在正文里),
     * 编辑模型一点不动。抓不到图 TG 只是不渲染预览,不报错 —— 天然静默降级。
     */
    public record Rendered(String text, List<List<TelegramButton>> keyboard, String poster) {
        public Rendered(String text, List<List<TelegramButton>> keyboard) {
            this(text, keyboard, null);
        }
    }

    private TelegramRenderer() {
    }

    /**
     * 封面 → TG 可抓的直链:服务层把封面一律改写成本地 {@code /images?url={原址}}(豆瓣防盗链代理),
     * Telegram 服务器抓不到内网路径 —— 这里还原上游直链。
     * <p>
     * 还原后仍非人人可抓:TMDB(image.tmdb.org)公网直取没问题,豆瓣(doubanio)有防盗链、TG 抓图不带
     * Referer,多半 403 出不了图。抓不到只是没预览,故不做可达性探测。
     */
    static String posterUrl(String cover) {
        if (StringUtils.isBlank(cover)) {
            return null;
        }
        if (cover.startsWith("http")) {
            return cover;
        }
        if (!cover.startsWith(COVER_PROXY_PREFIX)) {
            return null; // 其它相对路径(占位图等)TG 一样抓不到,不如不给
        }
        try {
            String encoded = cover.substring(COVER_PROXY_PREFIX.length());
            int and = encoded.indexOf('&');
            String url = URLDecoder.decode(and < 0 ? encoded : encoded.substring(0, and), StandardCharsets.UTF_8);
            return url.startsWith("http") ? url : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Rendered menu() {
        return new Rendered("🎬 <b>追剧助手</b>\n\n欢迎!选择操作:",
                List.of(
                        row(new TelegramButton("📺 我的订阅", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0)),
                                new TelegramButton("🔍 搜索追剧", TelegramCallbackData.SEARCH)),
                        row(new TelegramButton("🎞 片单追更", TelegramCallbackData.PIAN_DAN),
                                new TelegramButton("📅 追更日历", TelegramCallbackData.CALENDAR)),
                        row(new TelegramButton("🔄 最近更新", TelegramCallbackData.INBOX))));
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
        return new Rendered(truncate(text), keyboard, posterUrl(dto.getCover()));
    }

    public static Rendered searchPrompt() {
        return new Rendered("🔍 <b>搜索追剧</b>\n\n请直接输入剧名(100 字以内):\n例如:庆余年 / 斗破苍穹",
                List.of(row(new TelegramButton("❌ 取消", TelegramCallbackData.CANCEL))));
    }

    /** /search 带参的过渡锚点:发出后立刻被编辑成结果/错误文案,无需键盘。 */
    public static Rendered searching(String keyword) {
        return new Rendered("🔍 正在搜索「" + esc(abbrev(keyword, 40)) + "」…", null);
    }

    /** 搜索源不可用(未配 API Key / 熔断 / 网络不通):与「真没搜到」分开报,免得用户白换关键词。 */
    public static Rendered searchUnavailable(String reason) {
        return new Rendered("⚠️ <b>搜索源暂时不可用</b>\n\n" + esc(abbrev(reason, 200))
                        + "\n\n请到网页端「追剧设置 → 元数据」检查 TMDB API Key 与搜索源配置,或稍后重试。\n"
                        + "也可以先用「🎞 片单追更」从榜单里挑剧,那条路不依赖搜索。",
                List.of(row(new TelegramButton("🔄 重试搜索", TelegramCallbackData.SEARCH),
                                new TelegramButton("🎞 片单追更", TelegramCallbackData.PIAN_DAN)),
                        row(new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME))));
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
        return searchDetail(item, index, resultPage, subscribed, null, null, null);
    }

    /**
     * 搜索结果详情。{@code detail} 为可选的元数据详情补全 —— provider.search 只回 名称/年份/评分/封面,
     * 简介一律没有(豆瓣的 description 是「剧集」这类类型标签,TMDB/Bangumi 连这个都没有),
     * 只靠搜索条目渲染出来就两行,像没加载完。拉不到详情时退化成原来的薄版本。
     * <p>
     * {@code seasons} 非空即多季剧,按季展开订阅(与片单条目详情同一口径),已追季显示 ✅ 并跳订阅列表 ——
     * 否则多季剧从搜索进只能订到默认季,想按季订还得绕去片单。
     */
    public static Rendered searchDetail(MetadataSearchItem item, int index, int resultPage, boolean subscribed,
                                        MovieDetail detail, List<Integer> seasons, Set<Integer> subscribedSeasons) {
        StringBuilder text = new StringBuilder("🎬 <b>").append(esc(abbrev(item.getName(), 60))).append("</b>");
        if (StringUtils.isNotBlank(item.getYear())) {
            text.append("(").append(esc(item.getYear())).append(")");
        }
        text.append("\n\n来源:").append(esc(item.getProvider()));
        if (StringUtils.isNotBlank(item.getScore())) {
            text.append(" · 评分 ").append(esc(item.getScore()));
        }
        if (detail != null && StringUtils.isNotBlank(detail.getType_name())) {
            text.append("\n类型:").append(esc(abbrev(detail.getType_name(), 60)));
        }
        if (detail != null && StringUtils.isNotBlank(detail.getVod_actor())) {
            text.append("\n主演:").append(esc(abbrev(detail.getVod_actor(), 60)));
        }
        String overview = detail != null && StringUtils.isNotBlank(detail.getVod_content())
                ? detail.getVod_content() : item.getDescription();
        if (StringUtils.isNotBlank(overview)) {
            text.append("\n\n").append(esc(StringUtils.abbreviate(overview, 400)));
        }
        List<List<TelegramButton>> keyboard = new ArrayList<>();
        if (seasons != null && !seasons.isEmpty()) {
            appendSeasonButtons(keyboard, seasons, subscribedSeasons, TelegramCallbackData.ADD, index);
        } else if (subscribed) {
            keyboard.add(row(new TelegramButton("✅ 已在追剧列表 · 查看", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0))));
        } else {
            keyboard.add(row(new TelegramButton("➕ 加入追剧", TelegramCallbackData.of(TelegramCallbackData.ADD, index))));
        }
        keyboard.add(row(new TelegramButton("◀ 返回结果", TelegramCallbackData.of(TelegramCallbackData.RESULT_BACK, resultPage))));
        keyboard.add(row(new TelegramButton("🔍 重新搜索", TelegramCallbackData.SEARCH),
                new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME)));
        String poster = detail != null && StringUtils.isNotBlank(detail.getVod_pic())
                ? detail.getVod_pic() : item.getCover();
        return new Rendered(truncate(text), keyboard, posterUrl(poster));
    }

    /**
     * 多季剧的按季订阅钮:三列一排,已追季显示 ✅ 并跳订阅列表(不给重复订阅入口)。
     * 搜索详情与片单条目详情共用,只有 action 不同({@code add} / {@code pdadd}),季号都走第二参数。
     */
    private static void appendSeasonButtons(List<List<TelegramButton>> keyboard, List<Integer> seasons,
                                            Set<Integer> subscribedSeasons, String addAction, int index) {
        List<TelegramButton> group = new ArrayList<>();
        for (Integer season : seasons) {
            boolean followed = subscribedSeasons != null && subscribedSeasons.contains(season);
            group.add(followed
                    ? new TelegramButton("✅ 第" + season + "季",
                            TelegramCallbackData.of(TelegramCallbackData.SUBS, 0))
                    : new TelegramButton("➕ 第" + season + "季",
                            TelegramCallbackData.of(addAction, index, season)));
            if (group.size() == 3) {
                keyboard.add(List.copyOf(group));
                group.clear();
            }
        }
        if (!group.isEmpty()) {
            keyboard.add(List.copyOf(group));
        }
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
            appendSeasonButtons(keyboard, seasons, subscribedSeasons, TelegramCallbackData.PIAN_DAN_ADD, index);
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
        // 片单条目的 vod_pic 直取 TMDB/豆瓣列表,未过服务层封面代理,原样可用
        return new Rendered(truncate(text), keyboard, posterUrl(item.getVod_pic()));
    }

    @SuppressWarnings("unchecked")
    public static Rendered inbox(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return new Rendered("🔄 最近 3 天没有新动态。", inboxNav());
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
        return new Rendered(truncate(text), inboxNav());
    }

    /** 最近更新页导航:日历是它的时间对偶(已播 vs 待播),放同一屏方便来回切。 */
    private static List<List<TelegramButton>> inboxNav() {
        return List.of(
                row(new TelegramButton("📅 追更日历", TelegramCallbackData.CALENDAR),
                        new TelegramButton("📺 我的订阅", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0))),
                row(new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME)));
    }

    /**
     * 追更日历:{@code MediaSubscriptionService.schedule(uid)} 的成品数据(昨天 → 未来 8 天共 10 天)直渲染,
     * 与网页端横向日历条同源 —— 同剧同时段多集已在服务端压成区间,已完结订阅已剔除。
     * <p>
     * 空天跳过,但今天/明天即使没排播也出「—」当锚点(全跳过会让人以为是拉取失败)。
     * 键盘给今明两天去重后的剧各一个直达详情钮 —— 看到「今晚 20:00 更新」下一步必然是去看抓没抓到。
     */
    public static Rendered calendar(List<Map<String, Object>> days) {
        List<Map<String, Object>> safe = days == null ? List.of() : days;
        boolean any = safe.stream().anyMatch(day -> !dayItems(day).isEmpty());
        if (!any) {
            return new Rendered("📅 近 10 天没有排播日程。\n\n日程来自订阅绑定的元数据(TMDB/豆瓣分集播出日期),"
                    + "刚订阅或未绑定条目的剧要等首轮巡检补齐。", calendarNav());
        }
        StringBuilder text = new StringBuilder("📅 <b>追更日历</b>(昨天 → 未来 8 天)\n");
        int lines = 0;
        boolean truncated = false;
        for (Map<String, Object> day : safe) {
            List<Map<String, Object>> items = dayItems(day);
            String label = String.valueOf(day.getOrDefault("label", ""));
            boolean anchor = "今天".equals(label) || "明天".equals(label);
            if (items.isEmpty() && !anchor) {
                continue;
            }
            if (truncated) {
                break;
            }
            text.append("\n<b>").append(esc(label)).append(" ")
                    .append(esc(String.valueOf(day.getOrDefault("date", "")))).append("</b>");
            if (items.isEmpty()) {
                text.append("\n —");
                continue;
            }
            for (Map<String, Object> item : items) {
                if (lines >= CALENDAR_MAX_LINES) {
                    text.append("\n…仅显示前 ").append(CALENDAR_MAX_LINES).append(" 条");
                    truncated = true;
                    break;
                }
                text.append("\n ").append(clock(item.get("airTime")))
                        .append(" ").append(esc(abbrev(String.valueOf(item.getOrDefault("name", "")), 30)));
                Object episodes = item.get("episodes");
                if (episodes != null && StringUtils.isNotBlank(String.valueOf(episodes))) {
                    text.append(" 第").append(esc(abbrev(String.valueOf(episodes), 16))).append("集");
                }
                if (Boolean.TRUE.equals(item.get("paused"))) {
                    text.append(" ⏸");
                }
                lines++;
            }
        }
        return new Rendered(truncate(text), calendarKeyboard(safe));
    }

    /** 今明两天出现的剧去重后直达详情(最多 6 个,两列);顺序即排播顺序。 */
    private static List<List<TelegramButton>> calendarKeyboard(List<Map<String, Object>> days) {
        List<List<TelegramButton>> keyboard = new ArrayList<>();
        Set<Object> seen = new LinkedHashSet<>();
        List<TelegramButton> pair = new ArrayList<>();
        for (Map<String, Object> day : days) {
            String label = String.valueOf(day.getOrDefault("label", ""));
            if (!"今天".equals(label) && !"明天".equals(label)) {
                continue;
            }
            for (Map<String, Object> item : dayItems(day)) {
                Object id = item.get("subscriptionId");
                if (!(id instanceof Number number) || !seen.add(id) || seen.size() > CALENDAR_MAX_SHORTCUTS) {
                    continue;
                }
                pair.add(new TelegramButton(abbrev(String.valueOf(item.getOrDefault("name", "")), 22),
                        TelegramCallbackData.of(TelegramCallbackData.SUB, number.longValue())));
                if (pair.size() == 2) {
                    keyboard.add(List.copyOf(pair));
                    pair.clear();
                }
            }
        }
        if (!pair.isEmpty()) {
            keyboard.add(List.copyOf(pair));
        }
        keyboard.addAll(calendarNav());
        return keyboard;
    }

    private static List<List<TelegramButton>> calendarNav() {
        return List.of(row(new TelegramButton("📺 我的订阅", TelegramCallbackData.of(TelegramCallbackData.SUBS, 0)),
                new TelegramButton("🏠 主菜单", TelegramCallbackData.HOME)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dayItems(Map<String, Object> day) {
        return day != null && day.get("items") instanceof List<?> items
                ? (List<Map<String, Object>>) items : List.of();
    }

    /** 日历时钟:与 schedule() 的分天时区同源,不能用 systemDefault —— 容器 TZ 非上海时会出现「归到今天却显示昨晚」。 */
    private static String clock(Object airTime) {
        return airTime instanceof Number number
                ? CLOCK_FORMAT.format(Instant.ofEpochMilli(number.longValue())) : "";
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
        return "🔄 连载中";
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
