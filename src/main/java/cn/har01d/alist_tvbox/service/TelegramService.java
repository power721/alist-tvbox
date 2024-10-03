package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.IdUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import telegram4j.core.MTProtoTelegramClient;
import telegram4j.core.auth.AuthorizationHandler;
import telegram4j.core.auth.CodeAuthorizationHandler;
import telegram4j.core.auth.TwoFactorHandler;
import telegram4j.core.event.domain.message.SendMessageEvent;
import telegram4j.core.spec.ReplyToMessageSpec;
import telegram4j.core.spec.SendMessageSpec;
import telegram4j.mtproto.store.FileStoreLayout;
import telegram4j.mtproto.store.StoreLayout;
import telegram4j.mtproto.store.StoreLayoutImpl;
import telegram4j.tl.BaseChat;
import telegram4j.tl.Channel;
import telegram4j.tl.ImmutableInputPeerChannel;
import telegram4j.tl.ImmutableInputPeerChat;
import telegram4j.tl.InputMessagesFilterEmpty;
import telegram4j.tl.InputPeer;
import telegram4j.tl.InputUserSelf;
import telegram4j.tl.Message;
import telegram4j.tl.User;
import telegram4j.tl.messages.ChannelMessages;
import telegram4j.tl.messages.Messages;
import telegram4j.tl.request.messages.ImmutableSearch;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class TelegramService {
    private final SettingRepository settingRepository;
    private MTProtoTelegramClient client;

    public TelegramService(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @PostConstruct
    public void init() {
        String tgPhase = settingRepository.findById("tg_phase").map(Setting::getValue).orElse("0");
        if ("9".equals(tgPhase)) {
            connect();
        }
    }

    public void connect() {
        if (client != null) {
            client.disconnect().block();
        }

        new Thread(() -> {
            int apiId = IdUtils.getApiId();
            String apiHash = IdUtils.getApiHash();
            AuthorizationHandler authHandler = new CodeAuthorizationHandler(new CodeAuthorizationHandler.Callback() {
                @Override
                public Mono<CodeAuthorizationHandler.PhoneNumberAction> onPhoneNumber(AuthorizationHandler.Resources res, CodeAuthorizationHandler.PhoneNumberContext ctx) {
                    log.info("Input the phone number.");
                    settingRepository.save(new Setting("tg_phase", "1"));
                    String phone = waitSettingAvailable("tg_phone");
                    settingRepository.save(new Setting("tg_phase", "2"));
                    return phone != null ? Mono.just(CodeAuthorizationHandler.PhoneNumberAction.of(phone)) : Mono.just(CodeAuthorizationHandler.PhoneNumberAction.cancel());
                }

                @Override
                public Mono<CodeAuthorizationHandler.CodeAction> onSentCode(AuthorizationHandler.Resources res, CodeAuthorizationHandler.PhoneCodeContext ctx) {
                    log.info("Input the verification code.");
                    settingRepository.save(new Setting("tg_phase", "3"));
                    String code = waitSettingAvailable("tg_code");
                    settingRepository.save(new Setting("tg_phase", "4"));
                    return code != null ? Mono.just(CodeAuthorizationHandler.CodeAction.of(code)) : Mono.just(CodeAuthorizationHandler.CodeAction.cancel());
                }

                @Override
                public Mono<String> on2FAPassword(AuthorizationHandler.Resources res, TwoFactorHandler.Context ctx) {
                    log.info("Input the 2FA password.");
                    settingRepository.save(new Setting("tg_phase", "5"));
                    String password = waitSettingAvailable("tg_password");
                    settingRepository.save(new Setting("tg_phase", "6"));
                    return password != null ? Mono.just(password) : Mono.empty();
                }
            });
            StoreLayout storeLayout = new FileStoreLayout(new StoreLayoutImpl(c -> c.maximumSize(1000)), Path.of("/data/t4j.bin"));
            client = MTProtoTelegramClient.create(apiId, apiHash, authHandler).setStoreLayout(storeLayout).connect().block();

            client.on(SendMessageEvent.class)
                    .filter(e -> e.getMessage().getContent().equals("!ping"))
                    .flatMap(e -> Mono.justOrEmpty(e.getChat())
                            // telegram api may not deliver chat info and in this situation it's necessary to retrieve chat
                            .switchIfEmpty(e.getMessage().getChat())
                            .flatMap(c -> c.sendMessage(SendMessageSpec.of("pong!")
                                    .withReplyTo(ReplyToMessageSpec.of(e.getMessage())))))
                    .subscribe();

            settingRepository.save(new Setting("tg_phase", "9"));
            log.info("Telegram登陆成功");
            // wait until the client is stopped through `client.disconnect()`
            client.onDisconnect().block();
        }).start();
    }

    public User getUser() {
        return client.getServiceHolder().getUserService().getUser(InputUserSelf.instance()).block();
    }

    private String waitSettingAvailable(String key) {
        for (int i = 0; i < 120; ++i) {
            String value = settingRepository.findById(key).map(Setting::getValue).orElse(null);
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        return null;
    }

    public List<Message> search(String username, String keyword) {
        var resolvedPeer = client.getServiceHolder().getUserService().resolveUsername(username).block();
        var chat = resolvedPeer.chats().get(0);
        InputPeer inputPeer = null;
        if (chat instanceof Channel) {
            inputPeer = ImmutableInputPeerChannel.of(chat.id(), ((Channel) chat).accessHash());
        } else if (chat instanceof BaseChat) {
            inputPeer = ImmutableInputPeerChat.of(chat.id());
        }
        Messages messages = client.getServiceHolder().getChatService().search(ImmutableSearch.of(inputPeer, keyword, InputMessagesFilterEmpty.instance(), -1, -1, -1, 0, 100, -1, -1, 0)).block();
        log.info("messages: {}", messages);
        if (messages instanceof ChannelMessages) {
            return ((ChannelMessages) messages).messages();
        }
        return List.of();
    }

    @PreDestroy
    public void disconnect() {
        client.disconnect().block();
    }
}
