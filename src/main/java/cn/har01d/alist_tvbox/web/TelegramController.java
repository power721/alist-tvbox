package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.tg.Chat;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.SearchRequest;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TelegramService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import telegram4j.tl.User;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class TelegramController {
    private final TelegramService telegramService;
    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    public TelegramController(TelegramService telegramService, SubscriptionService subscriptionService, ObjectMapper objectMapper) {
        this.telegramService = telegramService;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/telegram/reset")
    public void reset() {
        telegramService.reset();
    }

    @PostMapping("/api/telegram/login")
    public void login() {
        telegramService.connect();
    }

    @PostMapping("/api/telegram/logout")
    public void logout() {
        telegramService.logout();
    }

    @GetMapping("/api/telegram/search")
    public List<Message> searchByKeyword(String wd) {
        return telegramService.search(wd, 100);
    }

    @GetMapping("/tg-search")
    public Object browse(String id, String wd, @RequestParam(required = false, defaultValue = "1") int pg) throws IOException {
        return browse("", id, wd, pg);
    }

    @GetMapping("/tg-search/{token}")
    public Object browse(@PathVariable String token, String id, String wd, @RequestParam(required = false, defaultValue = "1") int pg) throws IOException {
        subscriptionService.checkToken(token);
        if (id != null && !id.isEmpty()) {
            return telegramService.detail(id);
        } else if (wd != null && !wd.isEmpty() && pg == 1) {
            return telegramService.searchMovies(wd, 20);
        }
        return telegramService.searchMovies("", 5);
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

    @GetMapping(value = "/tg/s/{id}", produces = "text/plain;charset=UTF-8")
    public String searchWeb(@PathVariable String id, String keyword, String encode, HttpServletResponse response) {
        response.setHeader("server", "hypercorn-h11");
        return telegramService.searchWeb(keyword, id, encode);
    }

    @PostMapping(value = "/tg/s/{id}", produces = "text/plain;charset=UTF-8")
    public String searchWebPost(@PathVariable String id, @RequestBody String body, HttpServletResponse response) throws JsonProcessingException {
        String json = new String(Base64.getDecoder().decode(body));
        SearchRequest request = objectMapper.readValue(json, SearchRequest.class);
        response.setHeader("server", "hypercorn-h11");
        if ("2".equals(request.getPage())) {
            return "";
        }
        return telegramService.searchWeb(request.getKeyword(), request.getChannelUsername(), request.getEncode());
    }

    @GetMapping("/api/telegram/user")
    public User getUser() {
        return telegramService.getUser();
    }

    @GetMapping("/api/telegram/chats")
    public List<Chat> getAllChats() {
        return telegramService.getAllChats();
    }

    @GetMapping("/api/telegram/history")
    public List<Message> getChatHistory(String id) {
        return telegramService.getHistory(id);
    }
}
