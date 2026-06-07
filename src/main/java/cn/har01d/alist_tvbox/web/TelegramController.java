package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.SearchRequest;
import cn.har01d.alist_tvbox.dto.tg.TelegramLoginRequest;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannel;
import cn.har01d.alist_tvbox.dto.tg.TgPrivateChannelSelectionRequest;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccount;
import cn.har01d.alist_tvbox.dto.tg.TgProviderAccountChannelSyncResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderLoginResponse;
import cn.har01d.alist_tvbox.dto.tg.TgProviderStatus;
import cn.har01d.alist_tvbox.dto.tg.TgProviderSyncResponse;
import cn.har01d.alist_tvbox.entity.TelegramChannel;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TelegramService;
import cn.har01d.alist_tvbox.service.TgPrivateChannelService;
import cn.har01d.alist_tvbox.service.TgProviderClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class TelegramController {
    private final TelegramChannelRepository telegramChannelRepository;
    private final TelegramService telegramService;
    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final TgProviderClient tgProviderClient;
    private final TgPrivateChannelService tgPrivateChannelService;

    public TelegramController(TelegramChannelRepository telegramChannelRepository,
                              TelegramService telegramService,
                              SubscriptionService subscriptionService,
                              ObjectMapper objectMapper,
                              TgProviderClient tgProviderClient,
                              TgPrivateChannelService tgPrivateChannelService) {
        this.telegramChannelRepository = telegramChannelRepository;
        this.telegramService = telegramService;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.tgProviderClient = tgProviderClient;
        this.tgPrivateChannelService = tgPrivateChannelService;
    }

    @GetMapping("/api/telegram/search")
    public List<Message> searchByKeyword(String wd) {
        return telegramService.search(wd, 100, false, false);
    }

    @GetMapping("/api/telegram/private/channels")
    public List<TgPrivateChannel> privateChannels() {
        return tgPrivateChannelService.channels();
    }

    @PutMapping("/api/telegram/private/channels")
    public List<TgPrivateChannel> savePrivateChannels(@RequestBody TgPrivateChannelSelectionRequest request) {
        return tgPrivateChannelService.saveChannels(request);
    }

    @PostMapping("/api/telegram/private/channels/sync")
    public TgProviderSyncResponse syncPrivateChannels(@RequestBody(required = false) TgPrivateChannelSelectionRequest request) {
        return tgPrivateChannelService.syncChannels(request);
    }

    @PostMapping("/api/telegram/private/channels/sync-list")
    public List<TgProviderAccountChannelSyncResponse> syncPrivateChannelList() {
        return tgPrivateChannelService.syncAccountChannels();
    }

    @GetMapping("/api/telegram/private/search")
    public List<Message> searchPrivateChannels(String wd) {
        return tgPrivateChannelService.search(wd, 100);
    }

    @GetMapping("/api/telegram/provider/status")
    public TgProviderStatus providerStatus() {
        return tgProviderClient.status();
    }

    @GetMapping("/api/telegram/user")
    public Map<String, Object> user() {
        try {
            return tgProviderClient.accounts().stream()
                    .findFirst()
                    .<Map<String, Object>>map(this::toTelegramUser)
                    .orElseGet(this::emptyTelegramUser);
        } catch (RuntimeException e) {
            log.warn("get tg-provider account failed", e);
            return emptyTelegramUser();
        }
    }

    @PostMapping("/api/telegram/login/send-code")
    public TgProviderLoginResponse sendCode(@RequestBody TelegramLoginRequest request) {
        return tgProviderClient.sendCode(request.phone());
    }

    @PostMapping("/api/telegram/login/sign-in")
    public TgProviderLoginResponse signIn(@RequestBody TelegramLoginRequest request) {
        return tgProviderClient.signIn(request.phone(), request.code());
    }

    @PostMapping("/api/telegram/login/password")
    public TgProviderAccount password(@RequestBody TelegramLoginRequest request) {
        return tgProviderClient.password(request.phone(), request.password());
    }

    @PostMapping("/api/telegram/logout")
    public void logout() {
        try {
            tgProviderClient.accounts().stream()
                    .findFirst()
                    .ifPresent(account -> tgProviderClient.deleteAccount(account.id()));
        } catch (RuntimeException e) {
            log.warn("tg-provider logout failed", e);
        }
    }

    private Map<String, Object> toTelegramUser(TgProviderAccount account) {
        return Map.of(
                "id", account.id(),
                "username", StringUtils.defaultString(account.username()),
                "first_name", StringUtils.defaultString(account.firstName()),
                "last_name", StringUtils.defaultString(account.lastName()),
                "phone", StringUtils.defaultString(account.phone()));
    }

    private Map<String, Object> emptyTelegramUser() {
        return Map.of("id", 0, "username", "", "first_name", "", "last_name", "", "phone", "");
    }

    @GetMapping("/tg-search")
    public Object browse(String id, String t, String ac, String wd, String title, boolean web, @RequestParam(required = false, defaultValue = "1") int pg) throws IOException {
        return browse("", id, t, ac, wd, title, web, pg);
    }

    @GetMapping("/tg-search/{token}")
    public Object browse(@PathVariable String token, String id, String t, String ac, String wd, String title, boolean web, @RequestParam(required = false, defaultValue = "1") int pg) throws IOException {
        subscriptionService.checkToken(token);
        if (StringUtils.isNotBlank(id)) {
            return telegramService.detail(id, ac, title);
        } else if (StringUtils.isNotBlank(t)) {
            if (t.equals("0")) {
                return telegramService.searchMovies("", web, 5);
            }
            return telegramService.list(t, web, pg);
        } else if (StringUtils.isNotBlank(wd)) {
            return telegramService.searchMovies(wd, web, 20);
        }
        return telegramService.category(web);
    }

    @GetMapping("/tgsc")
    public Object browsePrivate(String id, String t, String ac, String wd, String title, boolean web, @RequestParam(required = false, defaultValue = "1") int pg) throws IOException {
        return browsePrivate("", id, t, ac, wd, title, web, pg);
    }

    @GetMapping("/tgsc/{token}")
    public Object browsePrivate(@PathVariable String token, String id, String t, String ac, String wd, String title, boolean web, @RequestParam(required = false, defaultValue = "1") int pg) throws IOException {
        subscriptionService.checkToken(token);
        if (StringUtils.isNotBlank(id)) {
            return telegramService.detail(id, ac, title);
        } else if (StringUtils.isNotBlank(t)) {
            return tgPrivateChannelService.list(t, pg);
        } else if (StringUtils.isNotBlank(wd)) {
            return tgPrivateChannelService.searchMovies(wd, 20);
        }
        return tgPrivateChannelService.category();
    }

    @GetMapping("/tg-db")
    public Object db(String id, String t, String ac, String wd, String sort, Integer year, String genre, String region, @RequestParam(required = false, defaultValue = "1") int pg, @RequestParam(required = false, defaultValue = "30") int size) throws IOException {
        return db("", id, t, ac, wd, sort, year, genre, region, pg, size);
    }

    @GetMapping("/tg-db/{token}")
    public Object db(@PathVariable String token, String id, String t, String ac, String wd, String sort, Integer year, String genre, String region, @RequestParam(required = false, defaultValue = "1") int pg, @RequestParam(required = false, defaultValue = "30") int size) throws IOException {
        subscriptionService.checkToken(token);
        if (StringUtils.isNotBlank(id)) {
            return telegramService.detail(id, ac, "");
        } else if (StringUtils.isNotBlank(t)) {
            if (t.equals("0")) {
                t = "suggestion";
            }
            return telegramService.listDouban(t, ac, sort, year, genre, region, pg, size);
        } else if (StringUtils.isNotBlank(wd)) {
            return telegramService.searchDouban(wd, 20);
        }
        return telegramService.categoryDouban();
    }

    @GetMapping("/tgsz")
    public Map<String, Object> searchZx(String keyword, String channelUsername, HttpServletResponse response) {
        response.setHeader("server", "hypercorn-h11");
        return telegramService.searchZx(keyword, channelUsername);
    }

    @GetMapping("/tgs")
    public String searchPg(String keyword, String channelUsername, String encode, HttpServletResponse response) {
        response.setHeader("server", "hypercorn-h11");
        return telegramService.searchPg(keyword, channelUsername, encode);
    }

    @PostMapping("/tgs")
    public String searchPgPost(@RequestBody String body, HttpServletResponse response) throws JsonProcessingException {
        String json = new String(Base64.getDecoder().decode(body));
        log.debug("searchPgPost: {} {}", body, json);
        SearchRequest request = objectMapper.readValue(json, SearchRequest.class);
        response.setHeader("server", "hypercorn-h11");
        if ("2".equals(request.getPage())) {
            return "";
        }
        return telegramService.searchPg(request.getKeyword(), request.getChannelUsername(), request.getEncode());
    }

    @GetMapping(value = "/tgs/s/{id}", produces = "text/plain;charset=UTF-8")
    public String searchWeb(@PathVariable String id, String keyword, String encode, HttpServletResponse response) {
        response.setHeader("server", "hypercorn-h11");
        return telegramService.searchWeb(keyword, id, encode);
    }

    @PostMapping(value = "/tgs/s/{id}", produces = "text/plain;charset=UTF-8")
    public String searchWebPost(@PathVariable String id, @RequestBody String body, HttpServletResponse response) throws JsonProcessingException {
        String json = new String(Base64.getDecoder().decode(body));
        SearchRequest request = objectMapper.readValue(json, SearchRequest.class);
        response.setHeader("server", "hypercorn-h11");
        if ("2".equals(request.getPage())) {
            return "";
        }
        return telegramService.searchWeb(request.getKeyword(), request.getChannelUsername(), request.getEncode());
    }

    @GetMapping("/api/telegram/channels")
    public List<TelegramChannel> list() {
        return telegramService.list();
    }

    @PostMapping("/api/telegram/resolveUsername")
    public TelegramChannel create(@RequestBody TelegramChannel channel) {
        return telegramService.create(channel);
    }

    @PostMapping("/api/telegram/channels")
    public TelegramChannel save(@RequestBody TelegramChannel channel) {
        return telegramChannelRepository.save(channel);
    }

    @PutMapping("/api/telegram/channels")
    public List<TelegramChannel> updateAll(@RequestBody List<TelegramChannel> channels) {
        return telegramService.updateAll(channels);
    }

    @DeleteMapping("/api/telegram/channels/{id}")
    public void delete(@PathVariable Long id) {
        telegramChannelRepository.deleteById(id);
    }

    @PostMapping("/api/telegram/reloadChannels")
    public List<TelegramChannel> reloadChannels() throws IOException {
        return telegramService.reloadChannels();
    }

    @PostMapping("/api/telegram/validateChannels")
    public List<TelegramChannel> validateChannels() {
        return telegramService.validateChannels();
    }

}
