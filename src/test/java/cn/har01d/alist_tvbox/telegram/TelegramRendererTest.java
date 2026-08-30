package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 渲染层:HTML 转义、分页边界、空态文案、超长截断。 */
class TelegramRendererTest {

    private MediaSubscriptionDto dto(int id, String name) {
        MediaSubscriptionDto dto = new MediaSubscriptionDto();
        dto.setId(id);
        dto.setName(name);
        dto.setSeason(1);
        dto.setStatus(cn.har01d.alist_tvbox.entity.MediaSubscription.STATUS_ACTIVE);
        dto.setCurrentEpisodes(5);
        dto.setOfficialEpisodes(12);
        return dto;
    }

    @Test
    void escapesHtmlInNames() {
        TelegramRenderer.Rendered rendered = TelegramRenderer.subsPage(
                List.of(dto(1, "<script>alert('x')</script>&")), 0);
        assertFalse(rendered.text().contains("<script>"));
        assertTrue(rendered.text().contains("&lt;script&gt;"));
        assertTrue(rendered.text().contains("&amp;"));
    }

    @Test
    void emptySubscriptionsShowsGuide() {
        TelegramRenderer.Rendered rendered = TelegramRenderer.subsPage(List.of(), 0);
        assertTrue(rendered.text().contains("还没有订阅"));
        assertEquals(TelegramCallbackData.SEARCH, rendered.keyboard().get(0).get(0).callbackData());
    }

    @Test
    void subsPagePaginates() {
        List<MediaSubscriptionDto> all = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            all.add(dto(i, "剧" + i));
        }
        TelegramRenderer.Rendered page1 = TelegramRenderer.subsPage(all, 0);
        assertEquals(10, page1.keyboard().stream().filter(row -> row.size() == 1 && row.get(0).callbackData().startsWith("sub:")).count());
        assertTrue(page1.keyboard().stream().anyMatch(row ->
                row.stream().anyMatch(b -> b.callbackData().equals("subs:1"))));

        TelegramRenderer.Rendered page2 = TelegramRenderer.subsPage(all, 1);
        assertTrue(page2.text().contains("剧12"));
        assertFalse(page2.text().contains("剧1</b>"));
        // 末页没有下一页
        assertFalse(page2.keyboard().stream().anyMatch(row ->
                row.stream().anyMatch(b -> b.callbackData().equals("subs:2"))));
    }

    @Test
    void searchResultsPaginateByTen() {
        List<MetadataSearchItem> items = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            MetadataSearchItem item = new MetadataSearchItem();
            item.setProvider("douban");
            item.setId(String.valueOf(100 + i));
            item.setName("结果" + i);
            items.add(item);
        }
        TelegramRenderer.Rendered page1 = TelegramRenderer.searchResults("关键词", items, 0);
        assertTrue(page1.text().contains("共 11 条"));
        // 第 11 条在第 2 页,首页只出现前 10 条的绝对索引按钮
        assertTrue(page1.keyboard().stream().anyMatch(r -> r.stream().anyMatch(b -> b.callbackData().equals("pick:9"))));
        assertFalse(page1.keyboard().stream().anyMatch(r -> r.stream().anyMatch(b -> b.callbackData().equals("pick:10"))));

        TelegramRenderer.Rendered page2 = TelegramRenderer.searchResults("关键词", items, 1);
        assertTrue(page2.keyboard().stream().anyMatch(r -> r.stream().anyMatch(b -> b.callbackData().equals("pick:10"))));
    }

    @Test
    void searchingPlaceholderEscapesKeywordWithoutKeyboard() {
        TelegramRenderer.Rendered rendered = TelegramRenderer.searching("庆余年 & <b>");
        assertTrue(rendered.text().contains("正在搜索「庆余年 &amp; &lt;b&gt;」"));
        assertNull(rendered.keyboard()); // 过渡消息立刻被编辑成结果,不带键盘
    }

    @Test
    void subscribedSearchDetailShowsViewButton() {
        MetadataSearchItem item = new MetadataSearchItem();
        item.setProvider("tmdb");
        item.setId("42");
        item.setName("斗破苍穹");
        TelegramRenderer.Rendered rendered = TelegramRenderer.searchDetail(item, 3, 0, true);
        assertEquals(TelegramCallbackData.of(TelegramCallbackData.SUBS, 0), rendered.keyboard().get(0).get(0).callbackData());

        rendered = TelegramRenderer.searchDetail(item, 3, 0, false);
        assertEquals(TelegramCallbackData.of(TelegramCallbackData.ADD, 3), rendered.keyboard().get(0).get(0).callbackData());
    }

    @Test
    void emptyInboxShowsPlaceholder() {
        TelegramRenderer.Rendered rendered = TelegramRenderer.inbox(List.of());
        assertTrue(rendered.text().contains("没有新动态"));
        rendered = TelegramRenderer.inbox(null);
        assertTrue(rendered.text().contains("没有新动态"));
    }

    @Test
    void inboxGroupsByName() {
        TelegramRenderer.Rendered rendered = TelegramRenderer.inbox(List.of(
                Map.of("name", "剧A", "type", "NEW_EPISODE", "detail", "S01E01", "createdTime", 1725000000000L),
                Map.of("name", "剧A", "type", "GAP_FILLED", "detail", "S01E02", "createdTime", 1725000000001L)));
        assertEquals(1, rendered.text().split("剧A", -1).length - 1); // 只作为分组标题出现一次
        assertTrue(rendered.text().contains("🧩"));
    }

    @Test
    void longTextTruncatedUnderTelegramLimit() {
        // 各视图自身字段有上限,分页后到不了 3800 —— truncate 是兜底防御,直测行为
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            text.append("第").append(i).append("行占位文本凑长度再加长一些确保超限触发截断\n");
        }
        String truncated = TelegramRenderer.truncate(text);
        assertTrue(truncated.length() <= 3800);
        assertTrue(truncated.endsWith("…"));
        String noNewline = TelegramRenderer.truncate(new StringBuilder("x".repeat(5000)));
        assertTrue(noNewline.length() <= 3800);
        assertTrue(noNewline.endsWith("…"));
        assertEquals("短文本", TelegramRenderer.truncate(new StringBuilder("短文本")));
    }

    @Test
    void escapesOnlyTelegramEntities() {
        // TG 只认 &amp;/&lt;/&gt;/&quot;,escapeHtml4 会把「·」译成 &middot; 让客户端显示实体文本
        assertEquals("豆瓣·热门电视剧", TelegramRenderer.esc("豆瓣·热门电视剧"));
        assertEquals("哈利·波特 &amp; 密室", TelegramRenderer.esc("哈利·波特 & 密室"));
        assertEquals("café &lt;b&gt;", TelegramRenderer.esc("café <b>"));
    }

    private MovieDetail entry(String vodId, String name) {
        MovieDetail item = new MovieDetail();
        item.setVod_id(vodId);
        item.setVod_name(name);
        item.setVod_year("2026");
        return item;
    }

    @Test
    void pianDanCategoriesPairPerRow() {
        List<Category> categories = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Category category = new Category();
            category.setType_id("douban:c" + i);
            category.setType_name("豆瓣·分类" + i);
            categories.add(category);
        }
        TelegramRenderer.Rendered rendered = TelegramRenderer.pianDanCategories(categories);
        assertTrue(rendered.text().contains("片单追更"));
        assertEquals(2, rendered.keyboard().get(0).size());
        assertEquals(1, rendered.keyboard().get(1).size()); // 奇数个末行落单
        assertEquals("pdc:2", rendered.keyboard().get(1).get(0).callbackData());
    }

    @Test
    void emptyPianDanCategoriesShowsPlaceholder() {
        assertTrue(TelegramRenderer.pianDanCategories(List.of()).text().contains("暂时不可用"));
        assertTrue(TelegramRenderer.pianDanCategories(null).text().contains("暂时不可用"));
    }

    @Test
    void pianDanListMarksSubscribedAndPages() {
        List<MovieDetail> items = List.of(entry("tmdb:tv:1", "剧甲"), entry("s:剧乙", "剧乙"));
        TelegramRenderer.Rendered rendered = TelegramRenderer.pianDanList("豆瓣·热门电视剧", items, Set.of(1), 1, true);
        assertTrue(rendered.text().contains("第 2 页"));
        assertTrue(rendered.text().contains("✅已追"));
        assertEquals("pde:0", rendered.keyboard().get(0).get(0).callbackData());
        assertTrue(rendered.keyboard().stream().anyMatch(row ->
                row.stream().anyMatch(b -> b.callbackData().equals("pdl:0"))));
        assertTrue(rendered.keyboard().stream().anyMatch(row ->
                row.stream().anyMatch(b -> b.callbackData().equals("pdl:2"))));

        // 首页无上一页,无下一页时不出翻页钮
        TelegramRenderer.Rendered single = TelegramRenderer.pianDanList("豆瓣·热门电视剧", items, Set.of(), 0, false);
        assertFalse(single.keyboard().stream().anyMatch(row ->
                row.stream().anyMatch(b -> b.callbackData().startsWith("pdl:"))));
    }

    @Test
    void emptyPianDanListKeepsNavigation() {
        TelegramRenderer.Rendered rendered = TelegramRenderer.pianDanList("豆瓣·热门电视剧", List.of(), Set.of(), 2, false);
        assertTrue(rendered.text().contains("没有内容"));
        assertTrue(rendered.keyboard().stream().anyMatch(row ->
                row.stream().anyMatch(b -> b.callbackData().equals("pdl:1"))));
    }

    @Test
    void pianDanEntryExpandsSeasonsThreePerRow() {
        MovieDetail item = entry("tmdb:tv:1", "末日地堡");
        item.setVod_content("简介");
        TelegramRenderer.Rendered rendered = TelegramRenderer.pianDanEntry(
                item, 4, 1, true, List.of(1, 2, 3, 4), Set.of(2));
        assertEquals(3, rendered.keyboard().get(0).size());
        assertEquals("pdadd:4:1", rendered.keyboard().get(0).get(0).callbackData());
        assertEquals("subs:0", rendered.keyboard().get(0).get(1).callbackData()); // 已追季跳订阅列表
        assertEquals("pdadd:4:4", rendered.keyboard().get(1).get(0).callbackData());
        assertTrue(rendered.keyboard().stream().anyMatch(row ->
                row.stream().anyMatch(b -> b.callbackData().equals("pdl:1")))); // 返回原页
    }

    @Test
    void pianDanEntryWithoutSeasonsTogglesByState() {
        MovieDetail item = entry("s:剧乙", "剧乙");
        assertEquals("pdadd:2", TelegramRenderer.pianDanEntry(item, 2, 0, false, List.of(), Set.of())
                .keyboard().get(0).get(0).callbackData());
        assertEquals("subs:0", TelegramRenderer.pianDanEntry(item, 2, 0, true, null, null)
                .keyboard().get(0).get(0).callbackData());
    }

    // ---------- 追更日历 ----------

    /** 上海时区 2026-08-30 20:00 的毫秒(日历时钟按 Asia/Shanghai 渲染,不随 JVM 默认时区飘)。 */
    private long air(int hour, int minute) {
        return java.time.LocalDateTime.of(2026, 8, 30, hour, minute)
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    private Map<String, Object> slot(int id, String name, long airTime, String episodes, boolean paused) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("subscriptionId", id);
        item.put("name", name);
        item.put("airTime", airTime);
        item.put("episodes", episodes);
        item.put("paused", paused);
        return item;
    }

    private Map<String, Object> day(String label, String date, Map<String, Object>... items) {
        Map<String, Object> day = new java.util.LinkedHashMap<>();
        day.put("label", label);
        day.put("date", date);
        day.put("today", "今天".equals(label));
        day.put("items", List.of(items));
        return day;
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarSkipsEmptyDaysButKeepsTodayAndTomorrowAnchors() {
        TelegramRenderer.Rendered rendered = TelegramRenderer.calendar(List.of(
                day("昨天", "8/29"),
                day("今天", "8/30", slot(1, "重器", air(20, 0), "29-33", false)),
                day("明天", "8/31"),
                day("周一", "9/1"),
                day("周三", "9/3", slot(2, "剑来", air(19, 30), "8", false))));
        String text = rendered.text();
        assertTrue(text.contains("今天 8/30"));
        assertTrue(text.contains("20:00 重器 第29-33集"));
        assertTrue(text.contains("明天 8/31"));
        assertTrue(text.contains("—"));            // 今明空天出锚点
        assertFalse(text.contains("昨天 8/29"));    // 其余空天跳过(抬头里的「昨天 →」不算)
        assertFalse(text.contains("周一"));
        assertTrue(text.contains("周三 9/3"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarMarksPausedAndOmitsMissingEpisodes() {
        // episodes 为 null:nextAirTime 回落径(episode=0),只报时间不报集数
        TelegramRenderer.Rendered rendered = TelegramRenderer.calendar(List.of(
                day("今天", "8/30", slot(3, "某国漫", air(12, 0), null, true))));
        assertTrue(rendered.text().contains("12:00 某国漫 ⏸"));
        assertFalse(rendered.text().contains("集"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarShortcutsCoverTodayAndTomorrowDeduped() {
        TelegramRenderer.Rendered rendered = TelegramRenderer.calendar(List.of(
                day("今天", "8/30", slot(1, "重器", air(20, 0), "29", false),
                        slot(2, "末日地堡", air(22, 0), "5", false)),
                day("明天", "8/31", slot(1, "重器", air(20, 0), "30", false),
                        slot(3, "某国漫", air(12, 0), "12", false)),
                day("周三", "9/3", slot(9, "剑来", air(19, 30), "8", false))));
        List<String> shortcuts = rendered.keyboard().stream().flatMap(List::stream)
                .map(TelegramButton::callbackData).filter(data -> data.startsWith("sub:")).toList();
        // 重器 今明都排 → 只出一次;周三的剧不进快跳
        assertEquals(List.of("sub:1", "sub:2", "sub:3"), shortcuts);
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarShortcutsCappedAtSix() {
        Map<String, Object>[] items = new Map[9];
        for (int i = 0; i < 9; i++) {
            items[i] = slot(i + 1, "剧" + i, air(20, 0), "1", false);
        }
        TelegramRenderer.Rendered rendered = TelegramRenderer.calendar(List.of(day("今天", "8/30", items)));
        assertEquals(6, rendered.keyboard().stream().flatMap(List::stream)
                .filter(b -> b.callbackData().startsWith("sub:")).count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarTruncatesAtFortyLines() {
        Map<String, Object>[] items = new Map[60];
        for (int i = 0; i < 60; i++) {
            items[i] = slot(i + 1, "剧" + i, air(20, 0), "1", false);
        }
        TelegramRenderer.Rendered rendered = TelegramRenderer.calendar(List.of(day("今天", "8/30", items)));
        assertTrue(rendered.text().contains("仅显示前 40 条"));
        assertFalse(rendered.text().contains("剧45"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyCalendarExplainsWhy() {
        assertTrue(TelegramRenderer.calendar(List.of()).text().contains("没有排播日程"));
        assertTrue(TelegramRenderer.calendar(null).text().contains("没有排播日程"));
        // 10 天全空(items 都为空)也算空态,不出一屏「—」
        TelegramRenderer.Rendered rendered = TelegramRenderer.calendar(List.of(day("今天", "8/30"), day("明天", "8/31")));
        assertTrue(rendered.text().contains("没有排播日程"));
        assertEquals(TelegramCallbackData.of(TelegramCallbackData.SUBS, 0),
                rendered.keyboard().get(0).get(0).callbackData());
    }

    @Test
    void menuAndInboxExposeCalendar() {
        assertTrue(TelegramRenderer.menu().keyboard().stream().flatMap(List::stream)
                .anyMatch(b -> b.callbackData().equals(TelegramCallbackData.CALENDAR)));
        assertTrue(TelegramRenderer.inbox(List.of()).keyboard().stream().flatMap(List::stream)
                .anyMatch(b -> b.callbackData().equals(TelegramCallbackData.CALENDAR)));
    }

    // ---------- 海报 ----------

    @Test
    void posterUrlRestoresUpstreamFromCoverProxy() {
        // 服务层 proxiedCover 的产物:TG 抓不到内网相对路径,得还原上游直链
        assertEquals("https://image.tmdb.org/t/p/w500/a b.jpg",
                TelegramRenderer.posterUrl("/images?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw500%2Fa+b.jpg"));
        // 多参数形态只取 url
        assertEquals("https://img.doubanio.com/x.jpg",
                TelegramRenderer.posterUrl("/images?url=https%3A%2F%2Fimg.doubanio.com%2Fx.jpg&w=200"));
        // 原始 http 直链原样透传(片单条目的 vod_pic 未过代理)
        assertEquals("https://image.tmdb.org/t/p/w500/x.jpg",
                TelegramRenderer.posterUrl("https://image.tmdb.org/t/p/w500/x.jpg"));
    }

    @Test
    void posterUrlRejectsUnreachableForms() {
        assertNull(TelegramRenderer.posterUrl(null));
        assertNull(TelegramRenderer.posterUrl(""));
        assertNull(TelegramRenderer.posterUrl("/assets/placeholder.png")); // 其它相对路径 TG 一样抓不到
        assertNull(TelegramRenderer.posterUrl("/images?url=%2Flocal%2Fx.jpg")); // 解出来仍非 http
    }

    @Test
    void detailViewsCarryPoster() {
        MediaSubscriptionDto dto = dto(1, "重器");
        dto.setCover("/images?url=https%3A%2F%2Fimage.tmdb.org%2Fp.jpg");
        assertEquals("https://image.tmdb.org/p.jpg", TelegramRenderer.subDetail(dto, null).poster());

        MetadataSearchItem item = new MetadataSearchItem();
        item.setProvider("tmdb");
        item.setId("42");
        item.setName("斗破苍穹");
        item.setCover("/images?url=https%3A%2F%2Fimage.tmdb.org%2Fs.jpg");
        assertEquals("https://image.tmdb.org/s.jpg", TelegramRenderer.searchDetail(item, 0, 0, false).poster());

        MovieDetail entry = entry("tmdb:tv:1", "剧甲");
        entry.setVod_pic("https://image.tmdb.org/e.jpg");
        assertEquals("https://image.tmdb.org/e.jpg",
                TelegramRenderer.pianDanEntry(entry, 0, 0, false, List.of(), Set.of()).poster());
    }

    @Test
    void listViewsCarryNoPoster() {
        // 列表/菜单不挂预览:一屏多剧挂谁都不对,且预览会把键盘挤下去
        assertNull(TelegramRenderer.menu().poster());
        assertNull(TelegramRenderer.subsPage(List.of(dto(1, "重器")), 0).poster());
        assertNull(TelegramRenderer.calendar(List.of()).poster());
    }
}
