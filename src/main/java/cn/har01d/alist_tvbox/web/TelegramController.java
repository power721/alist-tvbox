package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.TelegramService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import telegram4j.tl.User;
import telegram4j.tl.messages.Messages;

@RestController
@RequestMapping("/api/telegram")
public class TelegramController {
    private final TelegramService telegramService;

    public TelegramController(TelegramService telegramService) {
        this.telegramService = telegramService;
    }

    @PostMapping("/login")
    public void login() {
        telegramService.connect();
    }

    @PostMapping("/search")
    public Messages search(String channel, String q) {
        return telegramService.search(channel, q);
    }

    @GetMapping("/user")
    public User getUser() {
        return telegramService.getUser();
    }
}
