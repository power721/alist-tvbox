package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.qqmusic.QqMusicLoginStatus;
import cn.har01d.alist_tvbox.dto.qqmusic.QqMusicQrCode;
import cn.har01d.alist_tvbox.service.QqMusicService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
public class QqMusicController {
    private final QqMusicService qqMusicService;

    public QqMusicController(QqMusicService qqMusicService) {
        this.qqMusicService = qqMusicService;
    }

    // type=qq 或 wx，返回二维码图片（base64）与会话 key
    @PostMapping("/api/qqmusic/login")
    public QqMusicQrCode login(@RequestParam(defaultValue = "qq") String type)
            throws IOException, InterruptedException {
        return qqMusicService.createQrLogin(type);
    }

    // status: waiting / scanned / success / expired / failed；success 时 extend 为爬虫配置 JSON
    @GetMapping("/api/qqmusic/check")
    public QqMusicLoginStatus check(@RequestParam String key) {
        return qqMusicService.checkLogin(key);
    }

    // 立即刷新所有 QQ音乐插件凭据，返回刷新成功的数量
    @PostMapping("/api/qqmusic/refresh")
    public Map<String, Object> refresh() {
        return Map.of("refreshed", qqMusicService.refreshAll());
    }
}
