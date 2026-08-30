package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.dto.MediaSubscriptionDto;
import cn.har01d.alist_tvbox.dto.MediaSubscriptionRequest;
import cn.har01d.alist_tvbox.dto.MetadataSearchItem;
import cn.har01d.alist_tvbox.dto.telegram.BotCallbackQuery;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Telegram 交互的业务编排:菜单/订阅列表/详情/搜索/追加/退订/巡检。
 * <p>
 * 零业务逻辑 —— 全部委托 {@link MediaSubscriptionService}(uid 第一参数,归属校验 getOwned 内建)与
 * {@link MediaSubscriptionCheckService};本类只做 TG 交互适配:渲染、编辑锚点、callback 分发。
 * 搜索结果本体暂存在内存(单实例口径,10min 过期),callback 只携带索引。
 * token 由轮询层逐次传入以支持热切换,一律不入日志。
 */
@Component
public class TelegramSubscriptionBot {
    private static final Logger log = LoggerFactory.getLogger(TelegramSubscriptionBot.class);
    static final int MAX_KEYWORD_LENGTH = 100;

    /** 搜索结果暂存:chatId → 结果集(点击 pick/add/res 翻页时按索引取回)。 */
    record SearchState(String keyword, List<MetadataSearchItem> items, int page) {
    }

    private final MediaSubscriptionService subscriptionService;
    private final MediaSubscriptionCheckService checkService;
    private final TelegramBotClient client;
    private final Cache<String, SearchState> searchStates = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100)
            .build();

    public TelegramSubscriptionBot(MediaSubscriptionService subscriptionService,
                                    MediaSubscriptionCheckService checkService,
                                    TelegramBotClient client) {
        this.subscriptionService = subscriptionService;
        this.checkService = checkService;
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

    /** 搜索提示由 Router 在进入会话时发出(新消息,记 message_id 作为后续编辑锚点)。 */
    public long sendSearchPrompt(String token, String chatId) {
        TelegramRenderer.Rendered prompt = TelegramRenderer.searchPrompt();
        return client.sendMessage(token, chatId, prompt.text(), prompt.keyboard());
    }

    /** 用户输入关键词:全源元数据搜索 → 暂存 → 编辑提示消息为结果列表。 */
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
        try {
            Map<String, Object> report = subscriptionService.metaSearch("", trimmed);
            items = (List<MetadataSearchItem>) report.get("items");
        } catch (Exception e) {
            log.warn("telegram meta search failed: uid={} keyword={}", uid, trimmed, e);
            editOrSend(token, chatId, promptMessageId, TelegramRenderer.message("❌ 搜索失败,请稍后重试。"));
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
                        subscriptionService.isSubscribedTitle(uid, item.getName())));
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
            default -> {
            }
        }
        return null;
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
        client.sendMessage(token, String.valueOf(chatId), rendered.text(), rendered.keyboard());
    }

    void edit(String token, String chatId, long messageId, TelegramRenderer.Rendered rendered) {
        try {
            client.editMessageText(token, chatId, messageId, rendered.text(), rendered.keyboard());
        } catch (TelegramApiException e) {
            // 编辑目标失效(消息被删/超龄不可编辑):降级发新消息,不炸会话(口径同通知服务 editBindingLost)
            String message = StringUtils.defaultString(e.getMessage());
            if (message.contains("message to edit not found") || message.contains("message can't be edited")
                    || message.contains("message not found") || message.contains("MESSAGE_ID_INVALID")) {
                client.sendMessage(token, chatId, rendered.text(), rendered.keyboard());
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
            client.editMessageText(token, chatId, messageId, rendered.text(), rendered.keyboard());
        } catch (TelegramApiException e) {
            client.sendMessage(token, chatId, rendered.text(), rendered.keyboard());
        }
    }
}
