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
    void searchResultsPaginateByEight() {
        List<MetadataSearchItem> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MetadataSearchItem item = new MetadataSearchItem();
            item.setProvider("douban");
            item.setId(String.valueOf(100 + i));
            item.setName("结果" + i);
            items.add(item);
        }
        TelegramRenderer.Rendered page1 = TelegramRenderer.searchResults("关键词", items, 0);
        assertTrue(page1.text().contains("共 10 条"));
        // 第 9/10 条在第 2 页,首页只出现前 8 条的绝对索引按钮
        assertTrue(page1.keyboard().stream().anyMatch(r -> r.stream().anyMatch(b -> b.callbackData().equals("pick:7"))));
        assertFalse(page1.keyboard().stream().anyMatch(r -> r.stream().anyMatch(b -> b.callbackData().equals("pick:8"))));

        TelegramRenderer.Rendered page2 = TelegramRenderer.searchResults("关键词", items, 1);
        assertTrue(page2.keyboard().stream().anyMatch(r -> r.stream().anyMatch(b -> b.callbackData().equals("pick:8"))));
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
}
