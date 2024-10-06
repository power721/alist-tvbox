package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.tg.Chat;
import cn.har01d.alist_tvbox.dto.tg.Message;
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
import telegram4j.mtproto.store.FileStoreLayout;
import telegram4j.mtproto.store.StoreLayout;
import telegram4j.mtproto.store.StoreLayoutImpl;
import telegram4j.tl.BaseChat;
import telegram4j.tl.BaseMessage;
import telegram4j.tl.Channel;
import telegram4j.tl.ImmutableInputPeerChannel;
import telegram4j.tl.ImmutableInputPeerChat;
import telegram4j.tl.InputMessagesFilterEmpty;
import telegram4j.tl.InputPeer;
import telegram4j.tl.InputPeerSelf;
import telegram4j.tl.InputUserSelf;
import telegram4j.tl.User;
import telegram4j.tl.messages.ChannelMessages;
import telegram4j.tl.messages.DialogsSlice;
import telegram4j.tl.messages.Messages;
import telegram4j.tl.request.messages.ImmutableGetDialogs;
import telegram4j.tl.request.messages.ImmutableGetHistory;
import telegram4j.tl.request.messages.ImmutableSearch;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TelegramService {
    private final SettingRepository settingRepository;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
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

    public void reset() {
        settingRepository.deleteById("tg_phone");
        settingRepository.deleteById("tg_code");
        settingRepository.deleteById("tg_password");
        settingRepository.save(new Setting("tg_phase", "0"));
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
                    settingRepository.deleteById("tg_code");
                    settingRepository.deleteById("tg_password");
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

            settingRepository.save(new Setting("tg_phase", "9"));
            log.info("Telegram连接成功");
            client.onDisconnect().block();
            log.info("Telegram关闭连接");
        }).start();
    }

    public User getUser() {
        if (client == null) {
            return null;
        }
        return client.getServiceHolder().getUserService().getUser(InputUserSelf.instance()).block();
    }

    public List<Chat> getAllChats() {
        if (client == null) {
            return List.of();
        }
        DialogsSlice dialogs = (DialogsSlice) client.getServiceHolder().getChatService().getDialogs(ImmutableGetDialogs.of(0, 0, 0, InputPeerSelf.instance(), 100, 0)).block();
        return dialogs.chats().stream().filter(e -> e instanceof Channel).map(Channel.class::cast).map(Chat::new).toList();
    }

    public List<Message> getHistory(String id) {
        if (client == null) {
            return List.of();
        }
        String[] parts = id.split("\\$");
        InputPeer inputPeer = ImmutableInputPeerChannel.of(Long.parseLong(parts[0]), Long.parseLong(parts[1]));

        Messages messages = client.getServiceHolder().getChatService().getHistory(ImmutableGetHistory.of(inputPeer, 0, 0, 0, 100, 0, 0, 0)).block();
        log.info("{}", messages);
        if (messages instanceof ChannelMessages) {
            return ((ChannelMessages) messages).messages().stream().map(e -> (BaseMessage) e).map(e -> new Message("", e)).toList();
        }
        return List.of();
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

    public Map<String, Object> searchZx(String keyword, String username) {
        String[] channels = username.split(",");
        List<Future<List<Message>>> futures = new ArrayList<>();
        for (String channel : channels) {
            Future<List<Message>> future = executorService.submit(() -> search(channel, keyword));
            futures.add(future);
        }

        int total = 0;
        List<String> result = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Future<List<Message>> future = futures.get(i);
            String channel = channels[i];
            try {
                List<Message> list = future.get(2000, TimeUnit.MILLISECONDS);
                total += list.size();
                result.add(channel + "$$$" + list.stream().filter(e -> e.getContent().contains("http")).map(Message::toZxString).collect(Collectors.joining("##")));
            } catch (InterruptedException e) {
                break;
            } catch (ExecutionException | TimeoutException e) {
                log.warn("", e);
            }
        }

        log.info("Search TG get {} results.", total);
        return Map.of("results", result);
    }

    public String searchPg(String keyword, String username, String encode) {
        String[] channels = username.split(",");
        List<Message> list = new ArrayList<>();
        List<Future<List<Message>>> futures = new ArrayList<>();
        for (String channel : channels) {
            String name = channel.split("\\|")[0];
            Future<List<Message>> future = executorService.submit(() -> search(name, keyword));
            futures.add(future);
        }

        for (int i = 0; i < futures.size(); i++) {
            Future<List<Message>> future = futures.get(i);
            String channel = channels[i];
            String[] parts = channel.split("\\|");
            int timeout = 5000;
            if (parts.length == 2) {
                timeout = Integer.parseInt(parts[1]);
            }

            try {
                list.addAll(future.get(timeout, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                break;
            } catch (ExecutionException | TimeoutException e) {
                log.warn("", e);
            }
        }

        log.info("Search TG get {} results.", list.size());
        return list.stream()
                .map(Message::toPgString)
                .map(e -> {
                    if ("1".equals(encode)) {
                        return Base64.getEncoder().encodeToString(e.getBytes());
                    }
                    return e;
                })
                .collect(Collectors.joining("\n"));
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
        int minDate = (int) (Instant.now().minus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).toEpochMilli() / 1000);
        Messages messages = client.getServiceHolder().getChatService().search(ImmutableSearch.of(inputPeer, keyword, InputMessagesFilterEmpty.instance(), minDate, 0, 0, 0, 100, 0, 0, 0)).block();
        List<Message> result = List.of();
        if (messages instanceof ChannelMessages) {
            result = ((ChannelMessages) messages).messages().stream().map(e -> (BaseMessage) e).map(e -> new Message(username, e)).toList();
        }
        log.info("Search {} from {}, get {} results.", keyword, username, result.size());
        return result;
    }

    @PreDestroy
    public void disconnect() {
        client.disconnect().block();
    }
}
