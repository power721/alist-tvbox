package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.tg.Chat;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.SearchResponse;
import cn.har01d.alist_tvbox.dto.tg.SearchResult;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.model.Filter;
import cn.har01d.alist_tvbox.model.FilterValue;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import cn.har01d.alist_tvbox.util.BiliBiliUtils;
import cn.har01d.alist_tvbox.util.IdUtils;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import reactor.core.publisher.Mono;
import telegram4j.core.InitConnectionParams;
import telegram4j.core.MTProtoTelegramClient;
import telegram4j.core.auth.AuthorizationHandler;
import telegram4j.core.auth.CodeAuthorizationHandler;
import telegram4j.core.auth.QRAuthorizationHandler;
import telegram4j.core.auth.TwoFactorHandler;
import telegram4j.mtproto.store.FileStoreLayout;
import telegram4j.mtproto.store.StoreLayout;
import telegram4j.mtproto.store.StoreLayoutImpl;
import telegram4j.tl.BaseChat;
import telegram4j.tl.BaseMessage;
import telegram4j.tl.Channel;
import telegram4j.tl.ImmutableInputClientProxy;
import telegram4j.tl.ImmutableInputPeerChannel;
import telegram4j.tl.ImmutableInputPeerChat;
import telegram4j.tl.InputClientProxy;
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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static cn.har01d.alist_tvbox.util.Constants.FOLDER;

@Slf4j
@Service
public class TelegramService {
    private final AppProperties appProperties;
    private final SettingRepository settingRepository;
    private final MovieRepository movieRepository;
    private final ShareService shareService;
    private final TvBoxService tvBoxService;
    private final RestTemplate restTemplate;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Math.min(10, Runtime.getRuntime().availableProcessors() * 2));
    private final OkHttpClient httpClient = new OkHttpClient();
    private final LoadingCache<String, InputPeer> cache = Caffeine.newBuilder().build(this::resolveUsername);
    private final LoadingCache<String, List<Message>> searchCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).build(this::getFromChannel);
    private final Cache<String, MovieList> douban = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).build();
    private MTProtoTelegramClient client;
    private final List<String> fields = new ArrayList<>(List.of("id", "name", "genre", "description", "language", "country", "directors", "editors", "actors", "cover", "dbScore", "year"));
    private final List<FilterValue> filters = Arrays.asList(
            new FilterValue("原始顺序", ""),
            new FilterValue("评分⬇️", "dbScore,desc;year,desc"),
            new FilterValue("评分⬆️", "dbScore,asc;year,desc"),
            new FilterValue("年份⬇️", "year,desc;dbScore,desc"),
            new FilterValue("年份⬆️", "year,asc;dbScore,desc"),
            new FilterValue("名字⬇️", "name,desc;year,desc;dbScore,desc"),
            new FilterValue("名字⬆️", "name,asc;year,desc;dbScore,desc"),
            new FilterValue("类型⬇️", "genre,desc;year,desc;dbScore,desc"),
            new FilterValue("类型⬆️", "genre,asc;year,desc;dbScore,desc"),
            new FilterValue("地区⬇️", "country,desc;year,desc;dbScore,desc"),
            new FilterValue("地区⬆️", "country,asc;year,desc;dbScore,desc"),
            new FilterValue("语言⬇️", "language,desc;year,desc;dbScore,desc"),
            new FilterValue("语言⬆️", "language,asc;year,desc;dbScore,desc"),
            new FilterValue("ID⬇️", "id,desc"),
            new FilterValue("ID⬆️", "id,asc")
    );
    private final List<FilterValue> filters2 = Arrays.asList(
            new FilterValue("全部类型", ""),
            new FilterValue("喜剧", "喜剧"),
            new FilterValue("爱情", "爱情"),
            new FilterValue("动作", "动作"),
            new FilterValue("科幻", "科幻"),
            new FilterValue("动画", "动画"),
            new FilterValue("悬疑", "悬疑"),
            new FilterValue("冒险", "冒险"),
            new FilterValue("家庭", "家庭"),
            new FilterValue("剧情", "剧情"),
            new FilterValue("历史", "历史"),
            new FilterValue("奇幻", "奇幻"),
            new FilterValue("音乐", "音乐"),
            new FilterValue("歌舞", "歌舞"),
            new FilterValue("惊悚", "惊悚"),
            new FilterValue("恐怖", "恐怖"),
            new FilterValue("犯罪", "犯罪"),
            new FilterValue("灾难", "灾难"),
            new FilterValue("战争", "战争"),
            new FilterValue("传记", "传记"),
            new FilterValue("武侠", "武侠"),
            new FilterValue("情色", "情色"),
            new FilterValue("西部", "西部"),
            new FilterValue("真人秀", "真人秀"),
            new FilterValue("脱口秀", "脱口秀"),
            new FilterValue("纪录片", "纪录片"),
            new FilterValue("短片", "短片")
    );
    private final List<FilterValue> filters3 = Arrays.asList(
            new FilterValue("全部地区", ""),
            new FilterValue("中国", "中国"),
            new FilterValue("中国大陆", "中国大陆"),
            new FilterValue("中国香港", "中国香港"),
            new FilterValue("中国台湾", "中国台湾"),
            new FilterValue("美国", "美国"),
            new FilterValue("英国", "英国"),
            new FilterValue("韩国", "韩国"),
            new FilterValue("日本", "日本"),
            new FilterValue("法国", "法国"),
            new FilterValue("德国", "德国"),
            new FilterValue("意大利", "意大利"),
            new FilterValue("西班牙", "西班牙"),
            new FilterValue("印度", "印度"),
            new FilterValue("泰国", "泰国"),
            new FilterValue("俄罗斯", "俄罗斯"),
            new FilterValue("加拿大", "加拿大"),
            new FilterValue("澳大利亚", "澳大利亚"),
            new FilterValue("爱尔兰", "爱尔兰"),
            new FilterValue("瑞典", "瑞典"),
            new FilterValue("巴西", "巴西"),
            new FilterValue("丹麦", "丹麦")
    );

    public TelegramService(AppProperties appProperties,
                           SettingRepository settingRepository,
                           MovieRepository movieRepository,
                           ShareService shareService,
                           TvBoxService tvBoxService,
                           RestTemplateBuilder restTemplateBuilder) {
        this.appProperties = appProperties;
        this.settingRepository = settingRepository;
        this.movieRepository = movieRepository;
        this.shareService = shareService;
        this.tvBoxService = tvBoxService;
        this.restTemplate = restTemplateBuilder.build();
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
        settingRepository.deleteById("tg_qr_img");
        settingRepository.deleteById("tg_scanned");
    }

    public void logout() {
        if (client != null) {
            client.getServiceHolder().getAuthService().logOut().block();
            client.disconnect().block();
            client = null;
        }

        reset();
        settingRepository.save(new Setting("tg_phase", "0"));

        try {
            Files.deleteIfExists(Utils.getDataPath("t4j.bin"));
        } catch (IOException e) {
            log.warn("删除session文件失败", e);
        }
    }

    public void connect() {
        if (client != null) {
            client.disconnect().block();
        }

        new Thread(() -> {
            int apiId = IdUtils.getApiId();
            String apiHash = IdUtils.getApiHash();
            boolean qr = settingRepository.findById("tg_auth_type").map(Setting::getValue).orElse("qr").equals("qr");
            AuthorizationHandler authHandler;
            if (qr) {
                log.info("Telegram扫码登陆");
                settingRepository.deleteById("tg_scanned");
                settingRepository.deleteById("tg_qr_img");
                authHandler = new QRAuthorizationHandler(new QRAuthorizationHandler.Callback() {
                    @Override
                    public Mono<ActionType> onLoginToken(AuthorizationHandler.Resources res, QRAuthorizationHandler.Context ctx) {
                        settingRepository.save(new Setting("tg_phase", "0"));
                        log.info("Scan QR {}, expired: {}.", ctx.loginUrl(), ctx.expiresIn());
                        try {
                            String img = Utils.getQrCode(ctx.loginUrl());
                            settingRepository.save(new Setting("tg_qr_img", img));
                        } catch (IOException e) {
                            return Mono.error(e);
                        }
                        settingRepository.save(new Setting("tg_phase", "1"));
                        String scanned = waitSettingAvailable("tg_scanned");
                        settingRepository.deleteById("tg_password");
                        settingRepository.save(new Setting("tg_phase", "2"));
                        return scanned != null ? Mono.just(ActionType.STOP) : Mono.just(ActionType.RETRY);
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
            } else {
                log.info("Telegram验证码登陆");
                authHandler = new CodeAuthorizationHandler(new CodeAuthorizationHandler.Callback() {
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
            }
            StoreLayout storeLayout = new FileStoreLayout(new StoreLayoutImpl(c -> c.maximumSize(1000)), Utils.getDataPath("t4j.bin"));
            client = MTProtoTelegramClient
                    .create(apiId, apiHash, authHandler)
                    .setStoreLayout(storeLayout)
                    .setInitConnectionParams(initConnectionParams())
                    .connect()
                    .block();

            if (client == null) {
                settingRepository.save(new Setting("tg_phase", "0"));
                log.warn("Telegram连接失败");
                return;
            }

            settingRepository.save(new Setting("tg_phase", "9"));
            log.info("Telegram连接成功");
            client.onDisconnect().block();
            client = null;
            log.info("Telegram关闭连接");
        }).start();
    }

    private static InitConnectionParams initConnectionParams() {
        InputClientProxy proxy = null;
        Path path = Utils.getDataPath("proxy.txt");
        if (Files.exists(path)) {
            try {
                String text = Files.readString(path).trim();
                URI uri = new URI(text);
                int port = uri.getPort();
                if (port == -1) {
                    port = getDefaultPort(uri.getScheme());
                }
                log.info("use proxy {}:{}", uri.getHost(), port);
                proxy = ImmutableInputClientProxy.of(uri.getHost(), port);
            } catch (Exception e) {
                log.warn("Read proxy failed.", e);
            }
        }

        String appVersion = "1.0.0";
        String deviceModel = "AList-TvBox";
        String systemVersion = String.join(" ", System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"));

        String langCode = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);
        JsonNode node = JsonNodeFactory.instance.objectNode()
                .put("tz_offset", TimeZone.getDefault().getRawOffset() / 1000d);

        log.debug("InitConnectionParams: {} {} {}", langCode, systemVersion, node);
        return new InitConnectionParams(appVersion, deviceModel, langCode,
                "", systemVersion, langCode, proxy, node);
    }

    private static int getDefaultPort(String scheme) {
        if (scheme == null) return 8080;
        return switch (scheme.toLowerCase()) {
            case "http" -> 80;
            case "https" -> 443;
            case "socks", "socks5" -> 1080;
            default -> 8080;
        };
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
            return ((ChannelMessages) messages).messages().stream().filter(e -> e instanceof BaseMessage).map(BaseMessage.class::cast).map(e -> new Message("", e)).toList();
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
            Future<List<Message>> future = executorService.submit(() -> searchFromChannel(channel, keyword, 100));
            futures.add(future);
        }
        long startTime = System.currentTimeMillis();

        int total = 0;
        List<String> result = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            long remaining = Math.max(1, appProperties.getTgTimeout() - (System.currentTimeMillis() - startTime));
            Future<List<Message>> future = futures.get(i);
            String channel = channels[i];
            try {
                List<Message> list = future.get(remaining, TimeUnit.MILLISECONDS);
                total += list.size();
                result.add(channel + "$$$" + list.stream().filter(e -> e.getContent().contains("http")).map(Message::toZxString).collect(Collectors.joining("##")));
            } catch (InterruptedException e) {
                break;
            } catch (ExecutionException | TimeoutException e) {
                log.warn("", e);
            }
        }

        log.info("Search TG zx get {} results.", total);
        return Map.of("results", result);
    }

    public String searchPg(String keyword, String username, String encode) {
        log.info("search {} from channels {}", keyword, username);
        List<Message> results = List.of();
        if (StringUtils.isNotBlank(appProperties.getTgSearch())) {
            results = searchRemote(username, keyword, 100);
        }

        if (results.isEmpty()) {
            String[] channels = username.split(",");
            List<Future<List<Message>>> futures = new ArrayList<>();
            for (String channel : channels) {
                String name = channel.split("\\|")[0];
                Future<List<Message>> future = executorService.submit(() -> searchFromChannel(name, keyword, 100));
                futures.add(future);
            }

            results = getResult(futures);
        }

        log.info("Search TG pg get {} results.", results.size());
        return results.stream()
                .map(Message::toPgString)
                .map(e -> {
                    if ("1".equals(encode)) {
                        return Base64.getEncoder().encodeToString(e.getBytes());
                    }
                    return e;
                })
                .collect(Collectors.joining("\n"));
    }

    public MovieList detail(String tid) {
        ShareLink share = new ShareLink();
        share.setLink(tid);
        String path = shareService.add(share);

        return tvBoxService.getDetail("", "1$" + path + "/~playlist");
    }

    private String encodeUrl(String url) {
        return URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public CategoryList category() {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        String[] channels;
        if (client == null && StringUtils.isBlank(appProperties.getTgSearch())) {
            channels = appProperties.getTgWebChannels().split(",");
        } else {
            channels = appProperties.getTgChannels().split(",");
        }

        for (String type : appProperties.getTgDrivers()) {
            var category = new Category();
            category.setType_id("type:" + type);
            category.setType_name(getTypeName(type));
            category.setType_flag(0);
            list.add(category);
        }

        for (String channel : channels) {
            var category = new Category();
            category.setType_id(channel);
            category.setType_name(channel);
            category.setType_flag(0);
            list.add(category);
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    public MovieList list(String channel) throws IOException {
        if (channel.startsWith("type:")) {
            return loadMovies(channel.substring(5));
        }

        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        List<Message> messages;
        if (StringUtils.isNotBlank(appProperties.getTgSearch())) {
            messages = searchRemote(channel, "", 100);
        } else {
            messages = searchFromChannel(channel, "", 100);
        }

        for (Message message : messages) {
            if (appProperties.getTgDrivers().isEmpty() || appProperties.getTgDrivers().contains(message.getType())) {
                MovieDetail movieDetail = new MovieDetail();
                movieDetail.setVod_id(encodeUrl(message.getLink()));
                movieDetail.setVod_name(message.getName());
                movieDetail.setVod_pic(getPic(message.getType()));
                movieDetail.setVod_remarks(getTypeName(message.getType()));
                list.add(movieDetail);
            }
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        log.debug("list result: {}", result);
        return result;
    }

    public MovieList loadMovies(String type) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        List<Message> messages = search("", 100, true);
        for (Message message : messages) {
            if (type.equals(message.getType())) {
                MovieDetail movieDetail = new MovieDetail();
                movieDetail.setVod_id(encodeUrl(message.getLink()));
                movieDetail.setVod_name(message.getName());
                movieDetail.setVod_pic(getPic(message.getType()));
                movieDetail.setVod_remarks(getTypeName(message.getType()));
                list.add(movieDetail);
            }
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        return result;
    }

    public MovieList searchMovies(String keyword, int size) {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        List<Message> messages = search(keyword, size, false);
        for (Message message : messages) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id(encodeUrl(message.getLink()));
            movieDetail.setVod_name(message.getName());
            movieDetail.setVod_pic(getPic(message.getType()));
            movieDetail.setVod_remarks(getTypeName(message.getType()));
            list.add(movieDetail);
        }

        result.setList(list);
        result.setTotal(list.size());
        result.setLimit(list.size());

        return result;
    }

    public CategoryList categoryDouban() {
        CategoryList result = new CategoryList();
        List<Category> list = new ArrayList<>();

        {
            var category = new Category();
            category.setType_id("hot_tv");
            category.setType_name("热门电视剧");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("hot_movie");
            category.setType_name("热门电影");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_domestic");
            category.setType_name("国产剧");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_american");
            category.setType_name("欧美剧");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_animation");
            category.setType_name("动漫");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_variety_show");
            category.setType_name("综艺");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_korean");
            category.setType_name("韩剧");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_japanese");
            category.setType_name("日剧");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("suggestion_movie");
            category.setType_name("电影推荐");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("suggestion_tv");
            category.setType_name("电视剧推荐");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("movie_top250");
            category.setType_name("电影Top250");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("movie_real_time_hotest");
            category.setType_name("实时热门电影");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("movie_weekly_best");
            category.setType_name("一周口碑电影榜");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_real_time_hotest");
            category.setType_name("实时热门电视");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_chinese_best_weekly");
            category.setType_name("华语口碑剧集榜");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("tv_global_best_weekly");
            category.setType_name("全球口碑剧集榜");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("show_chinese_best_weekly");
            category.setType_name("国内口碑综艺榜");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("show_global_best_weekly");
            category.setType_name("国外口碑综艺榜");
            category.setType_flag(0);
            list.add(category);
        }

        {
            var category = new Category();
            category.setType_id("local");
            category.setType_name("浏览");
            category.setType_flag(0);
            list.add(category);
            List<FilterValue> years = new ArrayList<>();
            years.add(new FilterValue("全部", ""));
            int year = LocalDate.now().getYear();
            for (int i = 0; i < 20; ++i) {
                years.add(new FilterValue(String.valueOf(year - i), String.valueOf(year - i)));
            }
            result.getFilters().put("local", List.of(new Filter("sort", "排序", filters), new Filter("genre", "类型", filters2), new Filter("region", "地区", filters3), new Filter("year", "年份", years)));
        }

        {
            var category = new Category();
            category.setType_id("random");
            category.setType_name("随便看看");
            category.setType_flag(0);
            list.add(category);
        }

        result.setCategories(list);
        result.setTotal(result.getCategories().size());
        result.setLimit(result.getCategories().size());

        log.debug("category result: {}", result);
        return result;
    }

    public MovieList listDouban(String type, String sort, Integer year, String genre, String region, int page) {
        if (type.startsWith("s:")) {
            return searchMovies(type.substring(2), 30);
        }

        return getDoubanList(type, sort, year, genre, region, page);
    }

    private MovieList getDoubanList(String type, String sort, Integer year, String genre, String region, int page) {
        String key = type + "-" + page;
        MovieList result = douban.getIfPresent(key);
        if (result != null) {
            return result;
        }

        if (type.equals("local")) {
            return getLocalMovieList(sort, year, genre, region, page);
        }

        if (type.equals("random")) {
            return getRandomMovie();
        }

        if (type.startsWith("suggestion_")) {
            return getDoubanItems(type, page);
        }

        if (type.startsWith("hot_")) {
            return getDoubanItems(type, page);
        }

        result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        int size = 30;
        int start = (page - 1) * size;
        String url = "https://m.douban.com/rexxar/api/v2/subject_collection/" + type + "/items?os=linux&for_mobile=1&callback=&start=" + start + "&count=" + size + "&loc_id=108288&_=0";
        HttpEntity<Void> httpEntity = buildHttpEntity();

        var response = restTemplate.exchange(url, HttpMethod.GET, httpEntity, JsonNode.class);
        int total = response.getBody().get("total").asInt();
        ArrayNode items = (ArrayNode) response.getBody().get("subject_collection_items");
        for (JsonNode item : items) {
            MovieDetail movieDetail = getMovieDetail(item);
            list.add(movieDetail);
        }

        result.setList(list);
        result.setLimit(list.size());
        result.setTotal(total);
        result.setPagecount((total + size - 1) / size);

        douban.put(key, result);
        log.debug("list result: {}", result);
        return result;
    }

    private MovieList getLocalMovieList(String sort, Integer year, String genre, String region, int page) {
        MovieList result;
        result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        int size = 30;
        Pageable pageable;
        if (StringUtils.isNotBlank(sort)) {
            List<Sort.Order> orders = new ArrayList<>();
            for (String item : sort.split(";")) {
                String[] parts = item.split(",");
                Sort.Order order = parts[1].equals("asc") ? Sort.Order.asc(parts[0]) : Sort.Order.desc(parts[0]);
                orders.add(order);
            }
            pageable = PageRequest.of(page - 1, size, Sort.by(orders));
        } else {
            pageable = PageRequest.of(page - 1, size);
        }

        Page<Movie> res = searchMovies(year, genre, region, pageable);
        int total = (int) res.getTotalElements();

        for (Movie movie : res) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id("s:" + movie.getName());
            movieDetail.setVod_name(movie.getName());
            movieDetail.setVod_pic(movie.getCover());
            movieDetail.setVod_remarks(movie.getDbScore());
            movieDetail.setVod_tag(FOLDER);
            movieDetail.setCate(new CategoryList());
            list.add(movieDetail);
        }

        result.setList(list);
        result.setLimit(list.size());
        result.setTotal(total);
        result.setPagecount(res.getTotalPages());

        log.debug("list result: {}", result);
        return result;
    }

    public Page<Movie> searchMovies(Integer year, String genre, String region, Pageable pageable) {
        ExampleMatcher matcher = ExampleMatcher.matching()
                .withStringMatcher(ExampleMatcher.StringMatcher.CONTAINING)
                .withIgnoreNullValues();

        Movie movie = new Movie();
        if (year != null) {
            movie.setYear(year);
        }
        if (StringUtils.isNotBlank(genre)) {
            movie.setGenre(genre);
        }
        if (StringUtils.isNotBlank(region)) {
            movie.setCountry(region);
        }
        Example<Movie> example = Example.of(movie, matcher);
        return movieRepository.findAll(example, pageable);
    }

    private MovieList getRandomMovie() {
        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        int total = (int) movieRepository.count();
        int size = 30;
        int count = size + size / 2;
        int page = ThreadLocalRandom.current().nextInt(total / count);
        Collections.shuffle(fields);
        List<Sort.Order> orders = fields.stream().limit(3).map(e -> ThreadLocalRandom.current().nextBoolean() ? Sort.Order.asc(e) : Sort.Order.desc(e)).toList();
        Sort sort = Sort.by(orders);
        Pageable pageable = PageRequest.of(page, count, sort);
        Page<Movie> res = movieRepository.findAll(pageable);

        List<Movie> movies = new ArrayList<>(res.getContent());
        Collections.shuffle(movies);

        for (Movie movie : movies.subList(0, size)) {
            MovieDetail movieDetail = new MovieDetail();
            movieDetail.setVod_id("s:" + movie.getName());
            movieDetail.setVod_name(movie.getName());
            movieDetail.setVod_pic(movie.getCover());
            movieDetail.setVod_remarks(movie.getDbScore());
            movieDetail.setVod_tag(FOLDER);
            movieDetail.setCate(new CategoryList());
            list.add(movieDetail);
        }

        result.setList(list);
        result.setLimit(list.size());
        result.setTotal(total);
        result.setPagecount((total + size - 1) / size);

        log.debug("list result: {}", result);
        return result;
    }

    private MovieList getDoubanItems(String type, int page) {
        String key = type + "-" + page;
        int size = 30;
        int start = (page - 1) * size;
        String url = "https://m.douban.com/rexxar/api/v2/subject/recent_hot/movie?limit=" + size + "&start=" + start;
        if (type.equals("hot_tv")) {
            url = "https://m.douban.com/rexxar/api/v2/subject/recent_hot/tv?limit=" + size + "&start=" + start;
        } else if (type.equals("suggestion_movie")) {
            url = "https://m.douban.com/rexxar/api/v2/movie/suggestion?start=" + start + "&count=" + size + "&new_struct=1&with_review=1&for_mobile=1";
        } else if (type.equals("suggestion_tv")) {
            url = "https://m.douban.com/rexxar/api/v2/tv/suggestion?start=" + start + "&count=" + size + "&new_struct=1&with_review=1&for_mobile=1";
        }

        MovieList result = new MovieList();
        List<MovieDetail> list = new ArrayList<>();

        HttpEntity<Void> httpEntity = buildHttpEntity();

        var response = restTemplate.exchange(url, HttpMethod.GET, httpEntity, JsonNode.class);
        int total = response.getBody().get("total").asInt();
        ArrayNode items = (ArrayNode) response.getBody().get("items");
        for (JsonNode item : items) {
            MovieDetail movieDetail = getMovieDetail(item);
            list.add(movieDetail);
        }

        result.setList(list);
        result.setLimit(list.size());
        result.setTotal(total);
        result.setPagecount((total + size - 1) / size);

        douban.put(key, result);
        log.debug("list result: {}", result);
        return result;
    }

    private static HttpEntity<Void> buildHttpEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5");
        headers.set(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.set(HttpHeaders.REFERER, "https://movie.douban.com/");
        headers.set(HttpHeaders.USER_AGENT, Utils.getUserAgent());
        return new HttpEntity<>(null, headers);
    }

    private static MovieDetail getMovieDetail(JsonNode item) {
        double score = item.get("rating").get("value").asDouble();
        MovieDetail movieDetail = new MovieDetail();
        movieDetail.setVod_id("s:" + item.get("title").asText());
        movieDetail.setVod_name(item.get("title").asText());
        movieDetail.setVod_pic(item.get("pic").get("normal").asText());
        if (score > 0) {
            movieDetail.setVod_remarks(String.valueOf(score));
        }
        movieDetail.setVod_tag(FOLDER);
        movieDetail.setCate(new CategoryList());
        return movieDetail;
    }

    public MovieList searchDouban(String keyword, int size) {
        MovieList result = new MovieList();
        return result;
    }

    private String getTypeName(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> "阿里";
            case "1" -> "PikPak";
            case "2" -> "迅雷";
            case "3" -> "123";
            case "5" -> "夸克";
            case "6" -> "移动";
            case "7" -> "UC";
            case "8" -> "115";
            case "9" -> "天翼";
            case "10" -> "百度";
            default -> null;
        };
    }

    private String getPic(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "0" -> getUrl("/ali.jpg");
            case "1" -> getUrl("/pikpak.jpg");
            case "2" -> getUrl("/thunder.png");
            case "3" -> getUrl("/123.png");
            case "5" -> getUrl("/quark.png");
            case "7" -> getUrl("/uc.png");
            case "8" -> getUrl("/115.jpg");
            case "9" -> getUrl("/189.png");
            case "6" -> getUrl("/139.jpg");
            case "10" -> getUrl("/baidu.jpg");
            default -> null;
        };
    }

    private String getUrl(String path) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath(path)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    public List<Message> search(String keyword, int size, boolean cached) {
        List<Message> results = List.of();
        String[] channels = appProperties.getTgChannels().split(",");
        if (StringUtils.isNotBlank(appProperties.getTgSearch())) {
            results = searchRemote(appProperties.getTgChannels(), keyword, size);
        }

        if (results.isEmpty()) {
            if (client == null) {
                channels = appProperties.getTgWebChannels().split(",");
            }

            List<Future<List<Message>>> futures = new ArrayList<>();
            for (String channel : channels) {
                String name = channel.split("\\|")[0];
                Future<List<Message>> future = executorService.submit(() -> cached ? searchCache.get(name) : searchFromChannel(name, keyword, size));
                futures.add(future);
            }

            results = getResult(futures);
        }

        List<String> tgDrivers = appProperties.getTgDrivers();
        List<Message> list = results.stream()
                .filter(e -> tgDrivers.isEmpty() || tgDrivers.contains(e.getType()))
                .filter(e -> !e.getContent().toLowerCase().contains("pdf"))
                .filter(e -> !e.getContent().toLowerCase().contains("epub"))
                .filter(e -> !e.getContent().toLowerCase().contains("azw3"))
                .filter(e -> !e.getContent().toLowerCase().contains("mobi"))
                .filter(e -> !e.getContent().toLowerCase().contains("ppt"))
                .filter(e -> !e.getContent().contains("软件"))
                .filter(e -> !e.getContent().contains("图书"))
                .filter(e -> !e.getContent().contains("电子书"))
                .filter(e -> !e.getContent().contains("分享文件："))
                .sorted(comparator())
                .distinct()
                .toList();
        log.info("Search {} get {} results from {} channels.", keyword, list.size(), channels.length);
        return list;
    }

    private Comparator<Message> comparator() {
        Comparator<Message> type = Comparator.comparing(a -> appProperties.getTgDriverOrder().indexOf(a.getType()));
        return switch (appProperties.getTgSortField()) {
            case "type" -> type.thenComparing(Comparator.comparing(Message::getTime).reversed());
            case "name" -> Comparator.comparing(Message::getName);
            case "channel" -> Comparator.comparing(Message::getChannel).thenComparing(Comparator.comparing(Message::getTime).reversed());
            default -> Comparator.comparing(Message::getTime).reversed();
        };
    }

    private List<Message> searchRemote(String channels, String keyword, int size) {
        String api = appProperties.getTgSearch();
        if (!api.endsWith("/search")) {
            api = api + "/search";
        }
        String url = api + "?channels=" + channels + "&query=" + keyword + "&size=" + size + "&timeout=" + appProperties.getTgTimeout();
        try {
            var response = restTemplate.getForObject(url, SearchResponse.class);
            return response.getMessages().stream().flatMap(this::parseMessage).toList();
        } catch (Exception e) {
            log.warn("", e);
        }
        return List.of();
    }

    private List<Message> getResult(List<Future<List<Message>>> futures) {
        long startTime = System.currentTimeMillis();
        List<Message> results = new ArrayList<>();
        List<Future<List<Message>>> incompleteFutures = new ArrayList<>();

        for (Future<List<Message>> future : futures) {
            long remaining = Math.max(1, appProperties.getTgTimeout() - (System.currentTimeMillis() - startTime));

            try {
                List<Message> result = future.get(remaining, TimeUnit.MILLISECONDS);
                results.addAll(result);
            } catch (TimeoutException e) {
                incompleteFutures.add(future);
            } catch (InterruptedException | ExecutionException e) {
                log.warn("", e);
            }
        }

        Iterator<Future<List<Message>>> iterator = incompleteFutures.iterator();
        while (iterator.hasNext()) {
            Future<List<Message>> future = iterator.next();
            if (future.isDone()) {
                try {
                    results.addAll(future.get());
                    iterator.remove();
                } catch (InterruptedException | ExecutionException e) {
                    log.warn("", e);
                }
            }
        }

        incompleteFutures.forEach(f -> f.cancel(true));

        return results;
    }

    private List<Message> getFromChannel(String username) throws IOException {
        return searchFromChannel(username, "", 100);
    }

    public List<Message> searchFromChannel(String username, String keyword, int size) throws IOException {
        if (client == null) {
            List<Message> list = searchFromWeb(username, keyword);
            List<Message> result = list.stream().filter(e -> e.getType() != null).toList();
            log.info("Search {} from web {} get {} results.", keyword, username, result.size());
            return result;
        }
        List<Message> result = List.of();

        try {
            InputPeer inputPeer = cache.get(username);
            int minDate = (int) (Instant.now().minus(90, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).toEpochMilli() / 1000);
            Messages messages = client.getServiceHolder().getChatService().search(ImmutableSearch.of(inputPeer, keyword, InputMessagesFilterEmpty.instance(), minDate, 0, 0, 0, size, 0, 0, 0)).block();
            if (messages instanceof ChannelMessages) {
                result = ((ChannelMessages) messages).messages().stream().filter(e -> e instanceof BaseMessage).map(BaseMessage.class::cast).flatMap(e -> parseMessage(username, e)).toList();
            }
            log.info("Search {} from {} get {} results.", keyword, username, result.size());
        } catch (Exception e) {
            log.warn("search from channel {} failed", username, e);
        }
        return result;
    }

    private InputPeer resolveUsername(String username) {
        var resolvedPeer = client.getServiceHolder().getUserService().resolveUsername(username).block();
        var chat = resolvedPeer.chats().get(0);
        InputPeer inputPeer = null;
        if (chat instanceof Channel) {
            inputPeer = ImmutableInputPeerChannel.of(chat.id(), ((Channel) chat).accessHash());
        } else if (chat instanceof BaseChat) {
            inputPeer = ImmutableInputPeerChat.of(chat.id());
        }
        return inputPeer;
    }

    private Stream<Message> parseMessage(String channel, telegram4j.tl.BaseMessage message) {
        List<Message> list = new ArrayList<>();
        for (String link : Message.parseLinks(message.message())) {
            list.add(new Message(channel, message, link));
        }
        return list.stream();
    }

    private Stream<Message> parseMessage(SearchResult result) {
        List<Message> list = new ArrayList<>();
        for (String link : Message.parseLinks(result.getContent())) {
            list.add(new Message(result, link));
        }
        return list.stream();
    }

    public List<Message> searchFromWeb(String username, String keyword) throws IOException {
        String url = "https://t.me/s/" + username + "?q=" + keyword;

        String html = getHtml(url);

        List<Message> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements elements = doc.select("div.tgme_container div.tgme_widget_message_wrap");
        for (Element element : elements) {
            Element elTime = element.selectFirst("time");
            String time = elTime != null ? elTime.attr("datetime") : null;
            list.add(new Message(username, getTextWithNewlines(element.select(".tgme_widget_message_text").first()), time));
        }
        return list;
    }

    public static String getTextWithNewlines(Element element) {
        if (element == null) {
            return "";
        }
        Element clone = element.clone();
        clone.select("br").before("\\n");
        clone.select("br").remove();
        clone.select("p, div, li").before("\\n");
        String text = clone.text().replace("\\n", "\n");
        return text.trim();
    }

    public String searchWeb(String keyword, String username, String encode) {
        log.info("search {} from web channels {}", keyword, username);
        String[] channels = username.split(",");
        List<Future<List<String>>> futures = new ArrayList<>();
        for (String channel : channels) {
            Future<List<String>> future = executorService.submit(() -> searchWeb(channel, keyword));
            futures.add(future);
        }

        int total = 0;
        List<String> result = new ArrayList<>();
        for (Future<List<String>> future : futures) {
            try {
                List<String> list = future.get(appProperties.getTgTimeout(), TimeUnit.MILLISECONDS);
                total += list.size();
                for (String line : list) {
                    if ("1".equals(encode)) {
                        result.add(Base64.getEncoder().encodeToString(line.getBytes()));
                    } else {
                        result.add(line);
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (ExecutionException | TimeoutException e) {
                log.warn("", e);
            }
        }

        log.info("Search TG web get {} results.", total);
        return String.join("\n", result);
    }

    public List<String> searchWeb(String username, String keyword) throws IOException {
        String url = "https://t.me/s/" + username + "?q=" + keyword;

        String html = getHtml(url);

        List<String> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements elements = doc.select("div.tgme_container div.tgme_widget_message_wrap");
        for (Element element : elements) {
            Element elTime = element.selectFirst("time");
            String time = elTime != null ? elTime.attr("datetime") : Instant.now().atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            list.add(time + "\t" + username + "\t" + element.html().replace("\n", " ") + "\t");
        }
        Collections.reverse(list);
        log.info("Search TG web {} get {} results.", username, list.size());
        return list;
    }

    private String getHtml(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .addHeader("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7,ja;q=0.6,zh-TW;q=0.5")
                .addHeader("User-Agent", appProperties.getUserAgent())
                .addHeader("Referer", "https://t.me/")
                .build();

        Call call = httpClient.newCall(request);
        Response response = call.execute();
        String html = response.body().string();
        response.close();

        return html;
    }

    @PreDestroy
    public void disconnect() {
        client.disconnect().block();
    }
}
