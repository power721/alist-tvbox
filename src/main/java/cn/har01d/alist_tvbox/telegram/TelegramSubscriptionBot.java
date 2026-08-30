package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.dto.telegram.BotCallbackQuery;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.PianDanService;
import cn.har01d.alist_tvbox.service.PianDanSubscriptionService;
import cn.har01d.alist_tvbox.service.metadata.TmdbMetadataProvider;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Telegram 交互的业务编排:菜单/订阅列表/详情/搜索/片单/追加/退订/巡检。
 * <p>
 * 零业务逻辑 —— 全部委托 {@link MediaSubscriptionService}(uid 第一参数,归属校验 getOwned 内建)、
 * {@link MediaSubscriptionCheckService} 与 {@link PianDanSubscriptionService}(片单条目 ⇄ 订阅编排,
 * 与电视端 msubadd- 同一口径);本类只做 TG 交互适配:渲染、编辑锚点、callback 分发。
 * 搜索结果与片单当前页本体暂存在内存(单实例口径,10min 过期),callback 只携带索引。
 * token 由轮询层逐次传入以支持热切换,一律不入日志。
 */
@Component
public class TelegramSubscriptionBot {
    private static final Logger log = LoggerFactory.getLogger(TelegramSubscriptionBot.class);
    static final int MAX_KEYWORD_LENGTH = 100;
    /** 片单每屏条数;上游一页取 {@link #PIAN_DAN_FETCH_SIZE} 条 → 每上游页两屏,翻页不跳过条目。 */
    static final int PIAN_DAN_PAGE_SIZE = 10;
    private static final int PIAN_DAN_FETCH_SIZE = PIAN_DAN_PAGE_SIZE * 2;

    /** 搜索结果暂存:chatId → 结果集(点击 pick/add/res 翻页时按索引取回)。 */
    record SearchState(String keyword, List<MetadataSearchItem> items, int page) {
    }

    /** 片单浏览暂存:chatId → 当前分类与本屏条目(pde/pdadd 的索引在此解析)。 */
    record PianDanState(String typeId, String typeName, int page, List<MovieDetail> items) {
    }

    private final MediaSubscriptionService subscriptionService;
    private final MediaSubscriptionCheckService checkService;
    private final PianDanService pianDanService;
    private final PianDanSubscriptionService pianDanSubscriptionService;
    private final TelegramBotClient client;
    private final Cache<String, SearchState> searchStates = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100)
            .build();
    private final Cache<String, PianDanState> pianDanStates = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100)
            .build();

    public TelegramSubscriptionBot(MediaSubscriptionService subscriptionService,
                                    MediaSubscriptionCheckService checkService,
                                    PianDanService pianDanService,
                                    PianDanSubscriptionService pianDanSubscriptionService,
                                    TelegramBotClient client) {
        this.subscriptionService = subscriptionService;
        this.checkService = checkService;
        this.pianDanService = pianDanService;
        this.pianDanSubscriptionService = pianDanSubscriptionService;
        this.client = client;
    }

    // ---------- 命令与文本 ----------

    /** /start:回主菜单(任何时刻的逃生舱,同时意味着放弃进行中的搜索输入)。 */
    public void sendMenu(String token, String chatId) {
        send(token, chatId, TelegramRenderer.menu());
    }

    public void sendSubscriptions(String token, String chatId, int uid) {
        editFresh(token, chatId, TelegramRenderer.subsPage(subscriptionService.list(uid), 0));
    }

    /** /piandan:片单分类页(命令直达,发新消息作为后续编辑锚点)。 */
    public void sendPianDan(String token, String chatId) {
        editFresh(token, chatId, TelegramRenderer.pianDanCategories(pianDanCategories()));
    }

    /** /calendar:追更日历(与网页端横向日历条同源,每次实时拉,无暂存)。 */
    public void sendCalendar(String token, String chatId, int uid) {
        editFresh(token, chatId, TelegramRenderer.calendar(subscriptionService.schedule(uid)));
    }

    /** 搜索提示由 Router 在进入会话时发出(新消息,记 message_id 作为后续编辑锚点)。 */
    public long sendSearchPrompt(String token, String chatId) {
        TelegramRenderer.Rendered prompt = TelegramRenderer.searchPrompt();
        return client.sendMessage(token, chatId, prompt.text(), prompt.keyboard());
    }

    /**
     * 用户输入关键词:TMDB 元数据搜索 → 暂存 → 编辑提示消息为结果列表。
     * <p>
     * 只用 TMDB 而非全源:searchReport 是按源整块拼接(每源上限 10 条)而非交错或按相关度排,
     * bot 一页 8 条,第一页必然被排在最前那一源吃满 —— 与其在窗口里做混排,不如收敛到追更最需要的源
     * (TMDB 有官方集数与分集播出日期,直接喂给巡检排程)。单源意味着它挂了就没结果,故失败原因要报出来。
     */
    @SuppressWarnings("unchecked")
    public void runSearch(String token, String chatId, int uid, String keyword, long promptMessageId) {
        String trimmed = StringUtils.trimToEmpty(keyword);
        if (trimmed.isEmpty()) {
            edit(token, chatId, promptMessageId, TelegramRenderer.searchPrompt());
            return;
        }
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            editOrSend(token, chatId, promptMessageId,
                    TelegramRenderer.message("⚠️ 关键词过长(限 " + MAX_KEYWORD_LENGTH + " 字),请缩短后重试。"));
            return;
        }
        List<MetadataSearchItem> items;
        Map<String, String> errors;
        try {
            Map<String, Object> report = subscriptionService.metaSearch(TmdbMetadataProvider.NAME, trimmed);
            items = (List<MetadataSearchItem>) report.get("items");
            errors = report.get("errors") instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
        } catch (Exception e) {
            log.warn("telegram meta search failed: uid={} keyword={}", uid, trimmed, e);
            editOrSend(token, chatId, promptMessageId, TelegramRenderer.message("❌ 搜索失败,请稍后重试。"));
            return;
        }
        if ((items == null || items.isEmpty()) && !errors.isEmpty()) {
            // 源不可用(未配 API Key / 被熔断 / 网络不通)与「真没搜到」是两回事,报原因免得用户白换关键词
            log.info("telegram meta search unavailable: uid={} keyword={} errors={}", uid, trimmed, errors);
            editOrSend(token, chatId, promptMessageId,
                    TelegramRenderer.searchUnavailable(String.join(";", errors.values())));
            return;
        }
        searchStates.put(chatId, new SearchState(trimmed, items == null ? List.of() : items, 0));
        editOrSend(token, chatId, promptMessageId, TelegramRenderer.searchResults(trimmed,
                items == null ? List.of() : items, 0));
    }

    // ---------- 回调 ----------

    /**
     * callback 处理:编辑当前消息完成交互,返回值作为 answerCallbackQuery 的 toast 文案(null=仅止转圈)。
     * answer 统一由 Router 收口,保证每个 callback 必被应答。
     */
    public String handleCallback(String token, int uid, BotCallbackQuery query, TelegramCallbackData.Callback cb) {
        String chatId = String.valueOf(query.getMessage().getChat().getId());
        long messageId = query.getMessage().getMessageId();
        switch (cb.action()) {
            case TelegramCallbackData.HOME -> edit(token, chatId, messageId, TelegramRenderer.menu());
            case TelegramCallbackData.SUBS -> edit(token, chatId, messageId,
                    TelegramRenderer.subsPage(subscriptionService.list(uid), Math.max(cb.arg(), 0)));
            case TelegramCallbackData.SUB -> edit(token, chatId, messageId, renderDetail(uid, cb.arg()));
            case TelegramCallbackData.SUB_DELETE -> edit(token, chatId, messageId,
                    TelegramRenderer.confirmDelete(dtoOf(uid, cb.arg())));
            case TelegramCallbackData.SUB_DELETE_CONFIRM -> {
                MediaSubscriptionDto dto = dtoOf(uid, cb.arg());
                String name = dto.getName();
                subscriptionService.delete(uid, cb.arg());
                log.info("telegram unsubscribe: uid={} {} {}", uid, cb.arg(), name);
                edit(token, chatId, messageId, TelegramRenderer.deleted(name));
            }
            case TelegramCallbackData.SUB_CHECK -> {
                // 异步巡检,toast 即反馈;结果走既有 outbox 通知(配了通知的用户自动收卡片)
                checkService.checkAsync(uid, cb.arg());
                return "已开始巡检,完成后如有通知配置会推送提醒";
            }
            case TelegramCallbackData.SUB_UPDATE -> {
                return checkService.checkUpdateNow(uid, cb.arg());
            }
            case TelegramCallbackData.SUB_PAUSE -> {
                subscriptionService.pause(uid, cb.arg());
                edit(token, chatId, messageId, renderDetail(uid, cb.arg()));
            }
            case TelegramCallbackData.SUB_RESUME -> {
                subscriptionService.resume(uid, cb.arg());
                edit(token, chatId, messageId, renderDetail(uid, cb.arg()));
            }
            case TelegramCallbackData.PICK -> {
                SearchState state = searchStates.getIfPresent(chatId);
                if (state == null || cb.arg() >= state.items().size()) {
                    edit(token, chatId, messageId, TelegramRenderer.searchPrompt());
                    return "搜索结果已过期,请重新搜索";
                }
                MetadataSearchItem item = state.items().get(cb.arg());
                edit(token, chatId, messageId, TelegramRenderer.searchDetail(item, cb.arg(), state.page(),
                        subscriptionService.isSubscribedTitle(uid, item.getName()), searchItemDetail(item)));
            }
            case TelegramCallbackData.ADD -> {
                return addSubscription(token, uid, chatId, messageId, cb.arg());
            }
            case TelegramCallbackData.RESULT_PAGE, TelegramCallbackData.RESULT_BACK -> {
                SearchState state = searchStates.getIfPresent(chatId);
                if (state == null) {
                    edit(token, chatId, messageId, TelegramRenderer.searchPrompt());
                    return "搜索结果已过期,请重新搜索";
                }
                int page = Math.max(Math.min(cb.arg(), maxPage(state)), 0);
                searchStates.put(chatId, new SearchState(state.keyword(), state.items(), page));
                edit(token, chatId, messageId,
                        TelegramRenderer.searchResults(state.keyword(), state.items(), page));
            }
            case TelegramCallbackData.INBOX -> edit(token, chatId, messageId,
                    TelegramRenderer.inbox(subscriptionService.inbox(uid)));
            case TelegramCallbackData.CALENDAR -> edit(token, chatId, messageId,
                    TelegramRenderer.calendar(subscriptionService.schedule(uid)));
            case TelegramCallbackData.PIAN_DAN -> edit(token, chatId, messageId,
                    TelegramRenderer.pianDanCategories(pianDanCategories()));
            case TelegramCallbackData.PIAN_DAN_CATEGORY -> {
                List<Category> categories = pianDanCategories();
                if (cb.arg() < 0 || cb.arg() >= categories.size()) {
                    edit(token, chatId, messageId, TelegramRenderer.pianDanCategories(categories));
                    return "片单分类已变更,请重新选择";
                }
                Category category = categories.get(cb.arg());
                return openPianDan(token, uid, chatId, messageId,
                        category.getType_id(), category.getType_name(), 0);
            }
            case TelegramCallbackData.PIAN_DAN_PAGE -> {
                PianDanState state = pianDanStates.getIfPresent(chatId);
                if (state == null) {
                    return expirePianDan(token, chatId, messageId);
                }
                return openPianDan(token, uid, chatId, messageId, state.typeId(), state.typeName(),
                        Math.max(cb.arg(), 0));
            }
            case TelegramCallbackData.PIAN_DAN_ENTRY -> {
                return showPianDanEntry(token, uid, chatId, messageId, cb.arg());
            }
            case TelegramCallbackData.PIAN_DAN_ADD -> {
                return addPianDan(token, uid, chatId, messageId, cb.arg(), cb.arg2());
            }
            default -> {
            }
        }
        return null;
    }

    /**
     * 搜索结果详情补全:provider.search 只回 名称/年份/评分/封面,简介一律没有(TMDB 连字段都不填),
     * 光靠搜索条目渲染出来就两行像没加载完 —— 按 TMDB id 补拉一次剧集详情(命中 PianDanService 5min 短缓存,
     * 与片单详情共用同一份)。搜索走 /3/search/tv,故 mediaType 恒为 tv;拿不到就回落薄版本。
     */
    private MovieDetail searchItemDetail(MetadataSearchItem item) {
        if (!TmdbMetadataProvider.NAME.equals(item.getProvider()) || !StringUtils.isNumeric(item.getId())) {
            return null;
        }
        try {
            return pianDanService.tmdbDetail("tv", Integer.parseInt(item.getId()));
        } catch (RuntimeException e) {
            log.debug("load search item detail failed: {}", item.getId());
            return null;
        }
    }

    /** 追加订阅:幂等(语义匹配已订 → 提示;新建 → 元数据直绑零搜索零网络 + 首轮巡检)。 */
    private String addSubscription(String token, int uid, String chatId, long messageId, int index) {
        SearchState state = searchStates.getIfPresent(chatId);
        if (state == null || index >= state.items().size()) {
            edit(token, chatId, messageId, TelegramRenderer.searchPrompt());
            return "搜索结果已过期,请重新搜索";
        }
        MetadataSearchItem item = state.items().get(index);
        if (subscriptionService.isSubscribedTitle(uid, item.getName())) {
            edit(token, chatId, messageId, TelegramRenderer.alreadySubscribed(item.getName()));
            return null;
        }
        MediaSubscriptionRequest request = new MediaSubscriptionRequest();
        request.setName(item.getName());
        request.setKeyword(item.getName());
        request.setMetaProvider(item.getProvider());
        request.setMetaId(item.getId());
        MediaSubscriptionDto dto = subscriptionService.create(uid, request);
        checkService.checkAsync(uid, dto.getId());
        log.info("telegram subscribe: uid={} {} provider={} id={}", uid, dto.getId(), item.getProvider(), item.getId());
        edit(token, chatId, messageId, TelegramRenderer.subscribed(dto));
        return null;
    }

    // ---------- 片单追更 ----------

    /** 片单分类:与电视端「我的追剧」同一份(已剔除纯电影类目);构建纯内存,取不到时回空态。 */
    private List<Category> pianDanCategories() {
        try {
            return pianDanService.subscriptionCategory().getCategories();
        } catch (RuntimeException e) {
            log.warn("load pian-dan categories failed", e);
            return List.of();
        }
    }

    /**
     * 拉取并渲染片单某一屏:上游一页 20 条,机器人 10 条一屏 —— 页码换算成「上游页 + 半页偏移」,
     * 翻页既不丢条目也不重复(直接按 10 条问上游会丢掉每页后 10 条)。
     */
    private String openPianDan(String token, int uid, String chatId, long messageId,
                               String typeId, String typeName, int page) {
        MovieList result;
        try {
            result = pianDanService.list(typeId, "web", page / 2 + 1, PIAN_DAN_FETCH_SIZE, Map.of());
        } catch (RuntimeException e) {
            log.warn("load pian-dan list failed: {}", typeId, e);
            return "❌ 片单加载失败,请稍后重试";
        }
        List<MovieDetail> all = result.getList() == null ? List.of() : result.getList();
        int from = Math.min((page % 2) * PIAN_DAN_PAGE_SIZE, all.size());
        List<MovieDetail> items = List.copyOf(all.subList(from, Math.min(from + PIAN_DAN_PAGE_SIZE, all.size())));
        boolean hasNext = !items.isEmpty()
                && (from + items.size() < all.size() || page / 2 + 1 < result.getPagecount());
        pianDanStates.put(chatId, new PianDanState(typeId, typeName, page, items));
        Set<Integer> subscribed = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            if (subscriptionService.isSubscribedTitle(uid, items.get(i).getVod_name())) {
                subscribed.add(i);
            }
        }
        edit(token, chatId, messageId, TelegramRenderer.pianDanList(typeName, items, subscribed, page, hasNext));
        return null;
    }

    /** 条目详情:TMDB 条目补简介/演员/季号,已追状态按整剧与逐季分别判定(与电视端标题口径一致)。 */
    private String showPianDanEntry(String token, int uid, String chatId, long messageId, int index) {
        PianDanState state = pianDanStates.getIfPresent(chatId);
        if (state == null || index < 0 || index >= state.items().size()) {
            return expirePianDan(token, chatId, messageId);
        }
        MovieDetail detail = pianDanDetail(state.items().get(index));
        List<Integer> seasons = seasonsOf(detail);
        Set<Integer> subscribedSeasons = new HashSet<>();
        for (Integer season : seasons) {
            if (subscriptionService.isSubscribedTitle(uid, detail.getVod_name() + " 第" + season + "季")) {
                subscribedSeasons.add(season);
            }
        }
        edit(token, chatId, messageId, TelegramRenderer.pianDanEntry(detail, index, state.page(),
                subscriptionService.isSubscribedTitle(uid, detail.getVod_name()), seasons, subscribedSeasons));
        return null;
    }

    /** 片单条目加入追剧:载荷 {vodId}|{剧名}|{季?} 交给共用编排(元数据直绑,同剧幂等)。 */
    private String addPianDan(String token, int uid, String chatId, long messageId, int index, Integer season) {
        PianDanState state = pianDanStates.getIfPresent(chatId);
        if (state == null || index < 0 || index >= state.items().size()) {
            return expirePianDan(token, chatId, messageId);
        }
        MovieDetail item = state.items().get(index);
        String name = StringUtils.defaultString(item.getVod_name());
        String payload = item.getVod_id() + "|" + name + (season == null ? "" : "|" + season);
        PianDanSubscriptionService.Result result;
        try {
            result = pianDanSubscriptionService.subscribe(uid, payload);
        } catch (BadRequestException e) {
            // 条目载荷不可解析/元数据拉取失败:toast 即可,当前详情页原样留着让用户重试
            log.info("telegram pian-dan subscribe rejected: uid={} {}", uid, payload);
            return "❌ 条目信息获取失败,请稍后重试";
        }
        String title = season == null ? name : name + " 第" + season + "季";
        if (result.existed() || result.dto() == null) {
            edit(token, chatId, messageId, TelegramRenderer.alreadySubscribed(title));
            return null;
        }
        log.info("telegram pian-dan subscribe: uid={} {} {}", uid, result.dto().getId(), title);
        edit(token, chatId, messageId, TelegramRenderer.subscribed(result.dto()));
        return null;
    }

    /** 暂存过期:索引失去意义(继续用会点到别的条目),回分类页重来。 */
    private String expirePianDan(String token, String chatId, long messageId) {
        edit(token, chatId, messageId, TelegramRenderer.pianDanCategories(pianDanCategories()));
        return "片单浏览已过期,请重新选择分类";
    }

    /** TMDB 条目详情(命中 PianDanService 短缓存,返回共享实例 —— 只读不改);豆瓣条目与失败都回落列表条目。 */
    private MovieDetail pianDanDetail(MovieDetail item) {
        String vodId = StringUtils.defaultString(item.getVod_id());
        if (!vodId.startsWith(PianDanService.TMDB_PREFIX)) {
            return item;
        }
        String[] parts = vodId.split(":");
        if (parts.length < 3) {
            return item;
        }
        try {
            MovieDetail detail = pianDanService.tmdbDetail(parts[1], Integer.parseInt(parts[2]));
            return detail == null ? item : detail;
        } catch (RuntimeException e) {
            log.debug("load pian-dan entry detail failed: {}", vodId);
            return item;
        }
    }

    /** 剧集季号清单(tmdbDetail 放在 ext 里,已滤掉特典与未开播占位季);电影/豆瓣条目为空。 */
    private static List<Integer> seasonsOf(MovieDetail detail) {
        if (!(detail.getExt() instanceof List<?> values)) {
            return List.of();
        }
        List<Integer> seasons = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Number number) {
                seasons.add(number.intValue());
            }
        }
        return seasons;
    }

    private MediaSubscriptionDto dtoOf(int uid, int id) {
        // detail 内部 getOwned 做归属校验:别人的 id 抛「无权访问」,由 Router 统一转 notFound 文案
        return toDto(subscriptionService.detail(uid, id));
    }

    @SuppressWarnings("unchecked")
    private TelegramRenderer.Rendered renderDetail(int uid, int id) {
        try {
            Map<String, Object> detail = subscriptionService.detail(uid, id);
            return TelegramRenderer.subDetail(toDto(detail), detail);
        } catch (BadRequestException e) {
            return TelegramRenderer.notFound(id);
        }
    }

    /** detail() 的聚合 Map 里取出订阅 DTO(唯一入口,越权/不存在由 getOwned 抛出)。 */
    static MediaSubscriptionDto toDto(Map<String, Object> detail) {
        return (MediaSubscriptionDto) detail.get("subscription");
    }

    private static int maxPage(SearchState state) {
        return (state.items().size() + TelegramRenderer.RESULTS_PAGE_SIZE - 1) / TelegramRenderer.RESULTS_PAGE_SIZE - 1;
    }

    // ---------- 发送/编辑 ----------

    void send(String token, String chatId, TelegramRenderer.Rendered rendered) {
        client.sendMessage(token, String.valueOf(chatId), rendered.text(), rendered.keyboard(), rendered.poster());
    }

    void edit(String token, String chatId, long messageId, TelegramRenderer.Rendered rendered) {
        try {
            client.editMessageText(token, chatId, messageId, rendered.text(), rendered.keyboard(), rendered.poster());
        } catch (TelegramApiException e) {
            // 编辑目标失效(消息被删/超龄不可编辑):降级发新消息,不炸会话(口径同通知服务 editBindingLost)
            String message = StringUtils.defaultString(e.getMessage());
            if (message.contains("message to edit not found") || message.contains("message can't be edited")
                    || message.contains("message not found") || message.contains("MESSAGE_ID_INVALID")) {
                send(token, chatId, rendered);
                return;
            }
            throw e;
        }
    }

    /** 无历史锚点的页面(命令直达):发新消息。 */
    void editFresh(String token, String chatId, TelegramRenderer.Rendered rendered) {
        send(token, chatId, rendered);
    }

    void editOrSend(String token, String chatId, long messageId, TelegramRenderer.Rendered rendered) {
        try {
            client.editMessageText(token, chatId, messageId, rendered.text(), rendered.keyboard(), rendered.poster());
        } catch (TelegramApiException e) {
            send(token, chatId, rendered);
        }
    }
}
