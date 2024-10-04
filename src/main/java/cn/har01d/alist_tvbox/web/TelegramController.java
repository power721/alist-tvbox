package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.tg.Chat;
import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.service.TelegramService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import telegram4j.tl.User;

import java.util.List;

@RestController
public class TelegramController {
    private final TelegramService telegramService;

    public TelegramController(TelegramService telegramService) {
        this.telegramService = telegramService;
    }

    @PostMapping("/api/telegram/login")
    public void login() {
        telegramService.connect();
    }

    @GetMapping("/tg-search")
    public List<Message> search(String channelUsername, String keyword) {
        return telegramService.search(channelUsername, keyword);
    }

    @GetMapping("/tg-search/pg")
    public String searchPg(String keyword, String channelUsername, String encode) {
        return telegramService.searchPg(keyword, channelUsername, encode);
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
