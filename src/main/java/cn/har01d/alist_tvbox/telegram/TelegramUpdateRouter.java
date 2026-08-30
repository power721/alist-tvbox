package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.dto.telegram.BotMessage;
import cn.har01d.alist_tvbox.dto.telegram.BotUpdate;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Update 分发:身份解析 → 会话/限流 → 业务编排。
 * <p>
 * <b>身份</b>:私聊 chat.id 与用户级 Setting {@code msub_telegram_chat_id:u{uid}} 的值匹配即绑定
 * (与通知配置同一份数据,配过通知 = Bot 天然可用);全局值匹配 → id 最小 ADMIN(共享 token=管理级口径)。
 * 未绑定 chat 只回引导文案,不进任何业务。
 * <b>会话</b>:仅搜索关键词输入需要多轮状态(callback data 携带其余上下文),Caffeine 内存态,单实例口径。
 */
@Component
public class TelegramUpdateRouter {
    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateRouter.class);
    static final String CHAT_ID_KEY = "msub_telegram_chat_id";
    private static final Duration BINDING_TTL = Duration.ofSeconds(60);
    private static final Duration SESSION_TTL = Duration.ofMinutes(10);
    private static final Duration SEARCH_COOLDOWN = Duration.ofSeconds(3);

    /** 搜索输入会话:等待用户下一条文本,锚定提示消息便于就地编辑成结果列表。 */
    private record SearchSession(long promptMessageId) {
    }

    private final TelegramSubscriptionBot bot;
    private final TelegramBotClient client;
    private final SettingRepository settingRepository;
    private final UserRepository userRepository;
    private final Cache<Long, Optional<Integer>> bindings = Caffeine.newBuilder()
            .expireAfterWrite(BINDING_TTL).maximumSize(500).build();
    private final Cache<Long, SearchSession> sessions = Caffeine.newBuilder()
            .expireAfterWrite(SESSION_TTL).maximumSize(200).build();
    private final Cache<Long, Boolean> searchCooldown = Caffeine.newBuilder()
            .expireAfterWrite(SEARCH_COOLDOWN).maximumSize(500).build();

    public TelegramUpdateRouter(TelegramSubscriptionBot bot, TelegramBotClient client,
                                SettingRepository settingRepository, UserRepository userRepository) {
        this.bot = bot;
        this.client = client;
        this.settingRepository = settingRepository;
        this.userRepository = userRepository;
    }

    public void dispatch(String token, BotUpdate update) {
        try {
            if (update.getCallbackQuery() != null) {
                onCallback(token, update);
            } else if (update.getMessage() != null) {
                onMessage(token, update);
            }
        } catch (Exception e) {
            // 单条 update 失败不炸轮询:记录定位要素(token 除外)
            log.warn("telegram update failed: updateId={} chatId={}", update.getUpdateId(),
                    chatIdOf(update), e);
        }
    }

    private void onMessage(String token, BotUpdate update) {
        BotMessage message = update.getMessage();
        long chatId = message.getChat().getId();
        String text = StringUtils.defaultString(message.getText()).trim();
        if (text.isEmpty()) {
            return;
        }
        Integer uid = resolveUid(chatId);
        if (uid == null) {
            if (text.startsWith("/")) {
                sendBindingGuide(token, chatId);
            }
            return;
        }
        if (text.startsWith("/")) {
            // 命令与参数分开取:@bot 名只属于命令token,不能混进参数(/search@bot 庆余年)
            String[] parts = text.split("\\s+", 2);
            String command = parts[0].split("@", 2)[0];
            String args = parts.length > 1 ? parts[1].trim() : "";
            switch (command) {
                case "/start", "/help" -> {
                    sessions.invalidate(chatId);
                    bot.sendMenu(token, String.valueOf(chatId));
                }
                case "/subs", "/subscriptions" -> bot.sendSubscriptions(token, String.valueOf(chatId), uid);
                case "/piandan", "/pd" -> bot.sendPianDan(token, String.valueOf(chatId));
                case "/calendar", "/cal" -> bot.sendCalendar(token, String.valueOf(chatId), uid);
                case "/search" -> searchCommand(token, chatId, uid, args);
                default -> bot.sendMenu(token, String.valueOf(chatId));
            }
            return;
        }
        SearchSession session = sessions.getIfPresent(chatId);
        if (session != null) {
            if (searchCooldown.getIfPresent(chatId) != null) {
                client.sendMessage(token, String.valueOf(chatId),
                        "⏳ 操作太快了,请稍候几秒再试。", null);
                return;
            }
            searchCooldown.put(chatId, Boolean.TRUE);
            sessions.invalidate(chatId);
            bot.runSearch(token, String.valueOf(chatId), uid, text, session.promptMessageId());
        }
        // 无会话的普通文本:静默忽略(交互入口收敛在菜单/命令)
    }

    private void onCallback(String token, BotUpdate update) {
        var query = update.getCallbackQuery();
        long chatId = query.getMessage().getChat().getId();
        TelegramCallbackData.Callback cb = TelegramCallbackData.parse(query.getData());
        if (cb == null) {
            answer(token, query.getId(), null);
            return;
        }
        if (cb.action().equals(TelegramCallbackData.SEARCH)) {
            // 搜索入口可能点自任意消息:就地编辑成提示并进入会话
            Integer uid = resolveUid(chatId);
            if (uid == null) {
                answer(token, query.getId(), "未绑定账号");
                return;
            }
            enterSearch(token, chatId, query.getMessage().getMessageId());
            answer(token, query.getId(), null);
            return;
        }
        if (cb.action().equals(TelegramCallbackData.CANCEL)) {
            sessions.invalidate(chatId);
            bot.edit(token, String.valueOf(chatId), query.getMessage().getMessageId(), TelegramRenderer.menu());
            answer(token, query.getId(), null);
            return;
        }
        Integer uid = resolveUid(chatId);
        if (uid == null) {
            answer(token, query.getId(), "未绑定账号");
            return;
        }
        String toast = null;
        try {
            toast = bot.handleCallback(token, uid, query, cb);
        } catch (BadRequestException e) {
            // 归属校验失败/订阅不存在:统一文案,不外泄细节
            log.info("telegram callback rejected: uid={} chatId={} data={}", uid, chatId, query.getData());
            bot.edit(token, String.valueOf(chatId), query.getMessage().getMessageId(),
                    TelegramRenderer.notFound(cb.arg()));
        } catch (Exception e) {
            log.warn("telegram callback failed: uid={} chatId={} data={}", uid, chatId, query.getData(), e);
            toast = "❌ 操作失败,请稍后重试";
        }
        answer(token, query.getId(), toast);
    }

    /** 命令入口:发新提示消息并记锚点。 */
    private void enterSearch(String token, long chatId) {
        long messageId = bot.sendSearchPrompt(token, String.valueOf(chatId));
        sessions.put(chatId, new SearchSession(messageId));
    }

    /** /search 带参:占位消息即编辑锚点,立刻执行搜索;无参进入输入会话等下一条文本。 */
    private void searchCommand(String token, long chatId, int uid, String args) {
        if (args.isEmpty()) {
            enterSearch(token, chatId);
            return;
        }
        if (searchCooldown.getIfPresent(chatId) != null) {
            client.sendMessage(token, String.valueOf(chatId), "⏳ 操作太快了,请稍候几秒再试。", null);
            return;
        }
        searchCooldown.put(chatId, Boolean.TRUE);
        long messageId = bot.sendSearching(token, String.valueOf(chatId), args);
        bot.runSearch(token, String.valueOf(chatId), uid, args, messageId);
    }

    /** 回调入口:就地编辑既有消息为提示,该消息即锚点。 */
    private void enterSearch(String token, long chatId, long messageId) {
        bot.edit(token, String.valueOf(chatId), messageId, TelegramRenderer.searchPrompt());
        sessions.put(chatId, new SearchSession(messageId));
    }

    /** chat → uid;未绑定返回 null。60s 缓存,绑定变更(网页改配置)最迟一分钟生效。 */
    Integer resolveUid(long chatId) {
        return bindings.get(chatId, id -> Optional.ofNullable(lookupUid(id))).orElse(null);
    }

    private Integer lookupUid(long chatId) {
        String target = String.valueOf(chatId).trim();
        for (Setting setting : settingRepository.findByNameStartingWith(CHAT_ID_KEY)) {
            String name = setting.getName();
            if (CHAT_ID_KEY.equals(name)) {
                continue; // 全局行单独判定
            }
            if (target.equals(StringUtils.trimToEmpty(setting.getValue()))) {
                int at = name.lastIndexOf(":u");
                try {
                    return Integer.parseInt(name.substring(at + 2));
                } catch (NumberFormatException e) {
                    log.warn("malformed telegram binding row: {}", name);
                }
            }
        }
        String global = settingRepository.findById(CHAT_ID_KEY)
                .map(Setting::getValue).map(String::trim).orElse("");
        if (!global.isEmpty() && global.equals(target)) {
            return userRepository.findFirstByRoleOrderByIdAsc(Role.ADMIN)
                    .map(User::getId).orElse(1);
        }
        return null;
    }

    private void sendBindingGuide(String token, long chatId) {
        String text = "🤖 还没有绑定你的账号。\n\n你的 Chat ID:<code>" + chatId + "</code>\n\n"
                + "请到网页端「追剧设置 → Telegram 通知」把上面的数字填入 Chat ID 并保存,"
                + "然后回来发送 /start 即可使用。";
        client.sendMessage(token, String.valueOf(chatId), text, null);
    }

    private void answer(String token, String callbackQueryId, String text) {
        try {
            client.answerCallbackQuery(token, callbackQueryId, text);
        } catch (Exception e) {
            // 重复/过期 answer:吞掉即可,不影响交互
            log.debug("answer callback query failed: {}", e.getMessage());
        }
    }

    private static Long chatIdOf(BotUpdate update) {
        if (update.getCallbackQuery() != null && update.getCallbackQuery().getMessage() != null) {
            return update.getCallbackQuery().getMessage().getChat().getId();
        }
        if (update.getMessage() != null) {
            return update.getMessage().getChat().getId();
        }
        return null;
    }
}
