package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.dto.telegram.BotCallbackQuery;
import cn.har01d.alist_tvbox.dto.telegram.BotChat;
import cn.har01d.alist_tvbox.dto.telegram.BotMessage;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.service.DoubanService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.PianDanService;
import cn.har01d.alist_tvbox.service.PianDanSubscriptionService;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务编排:搜索流(全源 metaSearch → 暂存 → 编辑)、追加订阅(元数据直绑参数 + 幂等短路)、
 * 退订确认、编辑失效降级新消息。
 */
class TelegramSubscriptionBotTest {

    private final MediaSubscriptionService subscriptionService = mock(MediaSubscriptionService.class);
    private final MediaSubscriptionCheckService checkService = mock(MediaSubscriptionCheckService.class);
    private final PianDanService pianDanService = mock(PianDanService.class);
    private final PianDanSubscriptionService pianDanSubscriptionService = mock(PianDanSubscriptionService.class);
    private final DoubanService doubanService = mock(DoubanService.class);
    private final TelegramBotClient client = mock(TelegramBotClient.class);
    private TelegramSubscriptionBot bot;

    @BeforeEach
    void setUp() {
        bot = new TelegramSubscriptionBot(subscriptionService, checkService, pianDanService,
                pianDanSubscriptionService, doubanService, client);
    }

    private MetadataSearchItem item(String provider, String id, String name) {
        MetadataSearchItem item = new MetadataSearchItem();
        item.setProvider(provider);
        item.setId(id);
        item.setName(name);
        item.setYear("2026");
        return item;
    }

    private BotCallbackQuery callback(long chatId, long messageId, String data) {
        BotCallbackQuery query = new BotCallbackQuery();
        query.setId("q1");
        BotMessage msg = new BotMessage();
        BotChat chat = new BotChat();
        chat.setId(chatId);
        msg.setChat(chat);
        msg.setMessageId(messageId);
        query.setMessage(msg);
        query.setData(data);
        return query;
    }

    private void runSearch(String keyword) {
        when(subscriptionService.metaSearch("tmdb", keyword)).thenReturn(Map.of(
                "items", List.of(item("tmdb", "42", "斗破苍穹"), item("douban", "99", "斗破苍穹 特别篇")),
                "errors", Map.of()));
        when(subscriptionService.metaSearch("douban", keyword))
                .thenReturn(Map.of("items", List.of(), "errors", Map.of()));
        when(subscriptionService.metaSearch("bangumi", keyword))
                .thenReturn(Map.of("items", List.of(), "errors", Map.of()));
        bot.runSearch("TOKEN", "100", 5, keyword, 55L);
    }

    @Test
    void searchMergesProvidersTmdbFirstWithDedup() {
        // TMDB 优先,豆瓣/Bangumi 补足;同名+同年跨源去重保前源(5 条去 1 条剩 4 条)
        when(subscriptionService.metaSearch("tmdb", "庆余年")).thenReturn(Map.of(
                "items", List.of(item("tmdb", "1", "庆余年"), item("tmdb", "2", "剑来")), "errors", Map.of()));
        when(subscriptionService.metaSearch("douban", "庆余年")).thenReturn(Map.of(
                "items", List.of(item("douban", "3", "庆余年"), item("douban", "4", "某国漫")), "errors", Map.of()));
        when(subscriptionService.metaSearch("bangumi", "庆余年")).thenReturn(Map.of(
                "items", List.of(item("bangumi", "5", "葬送的芙莉莲")), "errors", Map.of()));
        bot.runSearch("TOKEN", "100", 5, "庆余年", 55L);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("共 4 条"), any(), any());
    }

    @Test
    void searchSurfacesProviderFailureInsteadOfEmptyResult() {
        when(subscriptionService.metaSearch("tmdb", "庆余年")).thenReturn(Map.of(
                "items", List.of(), "errors", Map.of("tmdb", "401 Unauthorized")));
        when(subscriptionService.metaSearch("douban", "庆余年")).thenReturn(Map.of(
                "items", List.of(), "errors", Map.of("douban", "network timeout")));
        when(subscriptionService.metaSearch("bangumi", "庆余年")).thenReturn(Map.of(
                "items", List.of(), "errors", Map.of()));
        bot.runSearch("TOKEN", "100", 5, "庆余年", 55L);
        // 三源全空且带失败原因:报原因而不是「换个关键词试试」
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L),
                argThat(text -> text.contains("搜索源暂时不可用") && text.contains("401 Unauthorized")
                        && text.contains("douban:network timeout")), any(), any());
    }

    @Test
    void partialProviderFailureStillShowsResults() {
        // TMDB 挂了但豆瓣有结果:照常出结果,不整单判死
        when(subscriptionService.metaSearch("tmdb", "庆余年")).thenReturn(Map.of(
                "items", List.of(), "errors", Map.of("tmdb", "401 Unauthorized")));
        when(subscriptionService.metaSearch("douban", "庆余年")).thenReturn(Map.of(
                "items", List.of(item("douban", "3", "庆余年")), "errors", Map.of()));
        when(subscriptionService.metaSearch("bangumi", "庆余年")).thenReturn(Map.of(
                "items", List.of(), "errors", Map.of()));
        bot.runSearch("TOKEN", "100", 5, "庆余年", 55L);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L),
                argThat(text -> text.contains("共 1 条") && !text.contains("不可用")), any(), any());
    }

    @Test
    void emptyResultWithoutErrorStaysAsNoMatch() {
        for (String provider : List.of("tmdb", "douban", "bangumi")) {
            when(subscriptionService.metaSearch(provider, "不存在的剧"))
                    .thenReturn(Map.of("items", List.of(), "errors", Map.of()));
        }
        bot.runSearch("TOKEN", "100", 5, "不存在的剧", 55L);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("没有找到"), any(), any());
    }

    @Test
    void runSearchEditsPromptIntoResults() {
        runSearch("斗破苍穹");
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("共 2 条"), any(), any());
    }

    @Test
    void runSearchFailureEditsPromptIntoNotice() {
        for (String provider : List.of("tmdb", "douban", "bangumi")) {
            when(subscriptionService.metaSearch(provider, "坏词")).thenThrow(new RuntimeException("provider down"));
        }
        bot.runSearch("TOKEN", "100", 5, "坏词", 55L);
        // 提示消息仍有锚点:三源调用全炸,就地编辑成不可用提示(带调用失败原因),不发新消息
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L),
                argThat(text -> text.contains("搜索源暂时不可用") && text.contains("调用失败")), any(), any());
    }

    @Test
    void keywordTooLongRejected() {
        bot.runSearch("TOKEN", "100", 5, "长".repeat(101), 55L);
        verify(subscriptionService, never()).metaSearch(anyString(), anyString());
    }

    @Test
    void addSubscriptionBindsMetadataDirectly() {
        runSearch("斗破苍穹"); // 暂存索引 0/1
        when(subscriptionService.isSubscribedTitle(5, "斗破苍穹")).thenReturn(false);
        MediaSubscriptionDto created = new MediaSubscriptionDto();
        created.setId(88);
        created.setName("斗破苍穹");
        when(subscriptionService.create(eq(5), any())).thenReturn(created);

        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "add:0"), TelegramCallbackData.parse("add:0"));

        ArgumentCaptor<cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest> captor = ArgumentCaptor.forClass(
                cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest.class);
        verify(subscriptionService).create(eq(5), captor.capture());
        assertEquals("斗破苍穹", captor.getValue().getName());
        assertEquals("tmdb", captor.getValue().getMetaProvider());
        assertEquals("42", captor.getValue().getMetaId());
        verify(checkService).checkAsync(5, 88);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("已加入追剧"), any(), any());
    }

    @Test
    void addSubscriptionIdempotentWhenTitleMatches() {
        runSearch("斗破苍穹");
        when(subscriptionService.isSubscribedTitle(5, "斗破苍穹")).thenReturn(true);
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "add:0"), TelegramCallbackData.parse("add:0"));
        verify(subscriptionService, never()).create(anyInt(), any());
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("已经在追剧列表"), any(), any());
    }

    @Test
    void expiredSearchStateRedirectsToPrompt() {
        String result = bot.handleCallback("TOKEN", 5, callback(100L, 55L, "add:0"),
                TelegramCallbackData.parse("add:0"));
        assertEquals("搜索结果已过期,请重新搜索", result);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("请直接输入剧名"), any(), any());
    }

    @Test
    void deleteConfirmRemovesAndNotifies() {
        MediaSubscriptionDto dto = new MediaSubscriptionDto();
        dto.setId(7);
        dto.setName("测试剧");
        dto.setStatus(cn.har01d.alist_tvbox.entity.MediaSubscription.STATUS_ACTIVE);
        when(subscriptionService.detail(5, 7)).thenReturn(Map.of("subscription", dto));
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "subdel:7"), TelegramCallbackData.parse("subdel:7"));
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("确定退订"), any(), any());

        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "subdelc:7"), TelegramCallbackData.parse("subdelc:7"));
        verify(subscriptionService).delete(5, 7);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("已退订"), any(), any());
    }

    @Test
    void checkCallbackReturnsToastAndStaysAsync() {
        String result = bot.handleCallback("TOKEN", 5, callback(100L, 55L, "subchk:7"),
                TelegramCallbackData.parse("subchk:7"));
        verify(checkService).checkAsync(5, 7);
        assertTrue(result.contains("巡检"));
    }

    @Test
    void lightCheckCallbackSurfacesText() {
        when(checkService.checkUpdateNow(5, 7)).thenReturn("官方已播 12 集,本地 10 集");
        String result = bot.handleCallback("TOKEN", 5, callback(100L, 55L, "subupd:7"),
                TelegramCallbackData.parse("subupd:7"));
        assertEquals("官方已播 12 集,本地 10 集", result);
    }

    @Test
    void editNotFoundFallsBackToSendMessage() {
        org.mockito.Mockito.doThrow(new TelegramApiException(
                "telegram editMessageText failed: Bad Request: message to edit not found"))
                .when(client).editMessageText(anyString(), anyString(), anyLong(), anyString(), any(), any());
        bot.sendMenu("TOKEN", "100");
        verify(client).sendMessage(eq("TOKEN"), eq("100"), contains("追剧助手"), any(), any());
    }

    @Test
    void pickShowsSubscribedState() {
        runSearch("斗破苍穹");
        when(subscriptionService.isSubscribedTitle(5, "斗破苍穹")).thenReturn(true);
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pick:0"), TelegramCallbackData.parse("pick:0"));
        // 详情文本独有「来源:」行,与结果列表区分;已订阅 → 按钮跳列表而非追加
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("来源:"), any(), any());
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), anyString(),
                argThat(kb -> kb != null && kb.get(0).get(0).callbackData().startsWith("subs:")), any());
    }

    @Test
    void pickBackfillsDetailFromTmdb() {
        runSearch("斗破苍穹");
        MovieDetail detail = entry("tmdb:tv:42", "斗破苍穹");
        detail.setVod_content("萧炎三年之约");
        detail.setVod_actor("配音甲 / 配音乙");
        detail.setType_name("动画 / 剧情");
        detail.setVod_pic("https://image.tmdb.org/p.jpg");
        when(pianDanService.tmdbDetail("tv", 42)).thenReturn(detail);

        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pick:0"), TelegramCallbackData.parse("pick:0"));

        // search 只回名称/年份/评分,简介与主演靠详情补,海报也换成详情里的直链
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L),
                argThat(text -> text.contains("萧炎三年之约") && text.contains("配音甲") && text.contains("动画")),
                any(), eq("https://image.tmdb.org/p.jpg"));
    }

    @Test
    void pickFallsBackWhenDetailUnavailable() {
        runSearch("斗破苍穹");
        when(pianDanService.tmdbDetail("tv", 42)).thenThrow(new RuntimeException("tmdb down"));
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pick:0"), TelegramCallbackData.parse("pick:0"));
        // 详情拉不到且本地库也查不到:不炸,退化成薄版本
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("来源:"), any(), any());
    }

    @Test
    void pickDoubanItemEnrichesFromLocalDoubanDb() {
        // 豆瓣条目:名称+年份查本地豆瓣库补 简介/类型/演职员/封面(item() 造的年份是 2026)
        runSearch("斗破苍穹");
        Movie movie = new Movie();
        movie.setName("斗破苍穹 特别篇");
        movie.setGenre("动画 / 奇幻");
        movie.setActors("配音甲 / 配音乙");
        movie.setDescription("三年之约后的新篇章");
        movie.setCover("https://img.doubanio.com/x.jpg");
        when(doubanService.getByName("斗破苍穹 特别篇", 2026)).thenReturn(movie);
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pick:1"), TelegramCallbackData.parse("pick:1"));
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L),
                argThat(text -> text.contains("三年之约后的新篇章") && text.contains("配音甲") && text.contains("动画")),
                any(), eq("https://img.doubanio.com/x.jpg"));
    }

    @Test
    void tmdbDetailFailureFallsBackToLocalDouban() {
        // TMDB 详情拉挂也走本地豆瓣库兜底,而非直接薄版本
        runSearch("斗破苍穹");
        when(pianDanService.tmdbDetail("tv", 42)).thenThrow(new RuntimeException("tmdb down"));
        Movie movie = new Movie();
        movie.setDescription("萧炎三年之约");
        when(doubanService.getByName("斗破苍穹", 2026)).thenReturn(movie);
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pick:0"), TelegramCallbackData.parse("pick:0"));
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("萧炎三年之约"), any(), any());
    }

    @Test
    void pickExpandsSeasonsFromDetail() {
        runSearch("斗破苍穹");
        MovieDetail detail = entry("tmdb:tv:42", "斗破苍穹");
        detail.setExt(List.of(1, 2, 3));
        when(pianDanService.tmdbDetail("tv", 42)).thenReturn(detail);
        when(subscriptionService.isSubscribedTitle(5, "斗破苍穹 第2季")).thenReturn(true);

        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pick:0"), TelegramCallbackData.parse("pick:0"));

        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), anyString(),
                argThat(kb -> kb != null
                        && kb.get(0).stream().anyMatch(b -> b.callbackData().equals("add:0:1"))
                        && kb.get(0).stream().anyMatch(b -> b.text().equals("✅ 第2季"))
                        && kb.get(0).stream().anyMatch(b -> b.callbackData().equals("add:0:3"))), any());
    }

    @Test
    void addWithSeasonBindsSeasonAndChecksSeasonTitle() {
        runSearch("斗破苍穹");
        when(subscriptionService.isSubscribedTitle(5, "斗破苍穹 第3季")).thenReturn(false);
        MediaSubscriptionDto created = new MediaSubscriptionDto();
        created.setId(90);
        created.setName("斗破苍穹");
        created.setSeason(3);
        when(subscriptionService.create(eq(5), any())).thenReturn(created);

        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "add:0:3"), TelegramCallbackData.parse("add:0:3"));

        ArgumentCaptor<cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest> captor = ArgumentCaptor.forClass(
                cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest.class);
        verify(subscriptionService).create(eq(5), captor.capture());
        assertEquals("斗破苍穹", captor.getValue().getName()); // 名字存裸名,季号单列
        assertEquals(3, captor.getValue().getSeason());
        verify(checkService).checkAsync(5, 90);
    }

    @Test
    void addWithSeasonIdempotentPerSeason() {
        runSearch("斗破苍穹");
        // 整剧未订、第 3 季已订:按季标题判定,不能被裸名判定吞掉
        when(subscriptionService.isSubscribedTitle(5, "斗破苍穹")).thenReturn(false);
        when(subscriptionService.isSubscribedTitle(5, "斗破苍穹 第3季")).thenReturn(true);
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "add:0:3"), TelegramCallbackData.parse("add:0:3"));
        verify(subscriptionService, never()).create(anyInt(), any());
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L),
                argThat(text -> text.contains("斗破苍穹 第3季")), any(), any());
    }

    // ---------- 片单追更 ----------

    private CategoryList categories(String... idAndNames) {
        CategoryList list = new CategoryList();
        for (int i = 0; i < idAndNames.length; i += 2) {
            Category category = new Category();
            category.setType_id(idAndNames[i]);
            category.setType_name(idAndNames[i + 1]);
            list.getCategories().add(category);
        }
        return list;
    }

    private MovieDetail entry(String vodId, String name) {
        MovieDetail item = new MovieDetail();
        item.setVod_id(vodId);
        item.setVod_name(name);
        item.setVod_year("2026");
        return item;
    }

    /** 上游一页 20 条(TMDB 口径),机器人一屏 10 条。 */
    private MovieList upstreamPage(int pagecount, String prefix) {
        List<MovieDetail> items = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            items.add(entry("tmdb:tv:" + i, prefix + i));
        }
        MovieList list = new MovieList();
        list.setList(items);
        list.setPagecount(pagecount);
        return list;
    }

    private void openCategory() {
        when(pianDanService.subscriptionCategory()).thenReturn(categories("douban:hot_tv", "豆瓣·热门电视剧"));
        when(pianDanService.list(eq("douban:hot_tv"), eq("web"), anyInt(), eq(20), any()))
                .thenReturn(upstreamPage(2, "剧"));
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pdc:0"), TelegramCallbackData.parse("pdc:0"));
    }

    @Test
    void categoryOpensFirstScreenOfTenItems() {
        openCategory();
        verify(pianDanService).list("douban:hot_tv", "web", 1, 20, Map.of());
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L),
                argThat(text -> text.contains("豆瓣·热门电视剧") && text.contains("剧10") && !text.contains("剧11")),
                argThat(kb -> kb != null && kb.stream().anyMatch(row ->
                        row.stream().anyMatch(b -> b.callbackData().equals("pdl:1")))), any());
    }

    @Test
    void secondScreenReusesSameUpstreamPage() {
        openCategory();
        // 上游一页 20 条 → 第二屏取后 10 条,仍是上游第 1 页(按 10 条问上游会丢掉每页后 10 条)
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pdl:1"), TelegramCallbackData.parse("pdl:1"));
        verify(pianDanService, org.mockito.Mockito.times(2)).list("douban:hot_tv", "web", 1, 20, Map.of());
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L),
                argThat(text -> text.contains("剧11") && text.contains("剧20")), any(), any());

        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pdl:2"), TelegramCallbackData.parse("pdl:2"));
        verify(pianDanService).list("douban:hot_tv", "web", 2, 20, Map.of());
    }

    @Test
    void subscribedEntriesMarkedInList() {
        when(subscriptionService.isSubscribedTitle(5, "剧3")).thenReturn(true);
        openCategory();
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("✅已追"), any(), any());
    }

    @Test
    void entryDetailExpandsSeasons() {
        openCategory();
        MovieDetail detail = entry("tmdb:tv:1", "剧1");
        detail.setVod_content("简介");
        detail.setExt(List.of(1, 2, 3));
        when(pianDanService.tmdbDetail("tv", 1)).thenReturn(detail);
        when(subscriptionService.isSubscribedTitle(5, "剧1 第2季")).thenReturn(true);

        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pde:0"), TelegramCallbackData.parse("pde:0"));

        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("简介"),
                argThat(kb -> kb != null
                        && kb.get(0).stream().anyMatch(b -> b.callbackData().equals("pdadd:0:1"))
                        // 已追的第 2 季不给重复订阅入口,跳订阅列表
                        && kb.get(0).stream().anyMatch(b -> b.text().equals("✅ 第2季"))
                        && kb.get(0).stream().anyMatch(b -> b.callbackData().equals("pdadd:0:3"))), any());
    }

    @Test
    void addFromPianDanPassesEntryPayload() {
        openCategory();
        MediaSubscriptionDto dto = new MediaSubscriptionDto();
        dto.setId(66);
        dto.setName("剧1");
        dto.setStatus(cn.har01d.alist_tvbox.entity.MediaSubscription.STATUS_ACTIVE);
        when(pianDanSubscriptionService.subscribe(eq(5), anyString()))
                .thenReturn(new PianDanSubscriptionService.Result(dto, false, "剧1", 3, "已加入追剧"));

        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pdadd:0:3"), TelegramCallbackData.parse("pdadd:0:3"));

        verify(pianDanSubscriptionService).subscribe(5, "tmdb:tv:1|剧1|3");
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("已加入追剧"), any(), any());
    }

    @Test
    void addFromPianDanIdempotentWhenExisted() {
        openCategory();
        when(pianDanSubscriptionService.subscribe(eq(5), anyString()))
                .thenReturn(new PianDanSubscriptionService.Result(null, true, "剧1", null, "已在追剧中"));
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pdadd:0"), TelegramCallbackData.parse("pdadd:0"));
        verify(pianDanSubscriptionService).subscribe(5, "tmdb:tv:1|剧1");
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("已经在追剧列表"), any(), any());
    }

    @Test
    void expiredPianDanStateFallsBackToCategories() {
        when(pianDanService.subscriptionCategory()).thenReturn(categories("douban:hot_tv", "豆瓣·热门电视剧"));
        String result = bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pde:0"),
                TelegramCallbackData.parse("pde:0"));
        assertEquals("片单浏览已过期,请重新选择分类", result);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("片单追更"), any(), any());
    }

    @Test
    void unknownCategoryIndexFallsBackToCategories() {
        when(pianDanService.subscriptionCategory()).thenReturn(categories("douban:hot_tv", "豆瓣·热门电视剧"));
        String result = bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pdc:9"),
                TelegramCallbackData.parse("pdc:9"));
        assertEquals("片单分类已变更,请重新选择", result);
        verify(pianDanService, never()).list(anyString(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void pianDanListFailureReturnsToast() {
        when(pianDanService.subscriptionCategory()).thenReturn(categories("douban:hot_tv", "豆瓣·热门电视剧"));
        when(pianDanService.list(anyString(), anyString(), anyInt(), anyInt(), any()))
                .thenThrow(new RuntimeException("upstream down"));
        String result = bot.handleCallback("TOKEN", 5, callback(100L, 55L, "pdc:0"),
                TelegramCallbackData.parse("pdc:0"));
        assertTrue(result.contains("片单加载失败"));
    }

    // ---------- 追更日历 ----------

    @Test
    void calendarCallbackRendersSchedule() {
        when(subscriptionService.schedule(5)).thenReturn(List.of(Map.of(
                "label", "今天", "date", "8/30", "today", true,
                "items", List.of(Map.of("subscriptionId", 7, "name", "重器",
                        "airTime", 1756555200000L, "episodes", "29-33", "paused", false)))));
        bot.handleCallback("TOKEN", 5, callback(100L, 55L, "cal"), TelegramCallbackData.parse("cal"));
        verify(subscriptionService).schedule(5);
        verify(client).editMessageText(eq("TOKEN"), eq("100"), eq(55L), contains("追更日历"), any(), any());
    }

    @Test
    void sendCalendarPostsFreshMessage() {
        when(subscriptionService.schedule(5)).thenReturn(List.of());
        bot.sendCalendar("TOKEN", "100", 5);
        verify(client).sendMessage(eq("TOKEN"), eq("100"), contains("没有排播日程"), any(), any());
    }
}
