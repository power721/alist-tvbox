package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.tg.Chat;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.SearchRequest;
import cn.har01d.alist_tvbox.service.TelegramService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import telegram4j.tl.User;

import java.util.Base64;
import java.util.List;

@RestController
public class TelegramController {
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper;

    public TelegramController(TelegramService telegramService, ObjectMapper objectMapper) {
        this.telegramService = telegramService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/telegram/login")
    public void login() {
        telegramService.connect();
    }

    @GetMapping("/tg-search")
    public List<Message> search(String channelUsername, String keyword) {
        return telegramService.search(channelUsername, keyword);
    }

    @GetMapping("/tgs")
    public String searchPg(String keyword, String channelUsername, String encode) {
        return telegramService.searchPg(keyword, channelUsername, encode);
    }

    @PostMapping("/tgs")
    public String searchPgPost(@RequestBody String body) throws JsonProcessingException {
        String json = new String(Base64.getDecoder().decode(body));
        SearchRequest request = objectMapper.readValue(json, SearchRequest.class);
        return telegramService.searchPg(request.getKeyword(), request.getChannelUsername(), request.getEncode());
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
