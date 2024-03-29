package cn.har01d.alist_tvbox.youtube;

import cn.har01d.alist_tvbox.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;

@Slf4j
@RestController
public class YoutubeController {
    private final YoutubeService youtubeService;
    private final SubscriptionService subscriptionService;

    public YoutubeController(YoutubeService youtubeService, SubscriptionService subscriptionService) {
        this.youtubeService = youtubeService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/youtube")
    public Object browse(String ids, String wd, String sort, String time, String t, @RequestParam(required = false, defaultValue = "1") Integer pg,HttpServletRequest request) throws IOException {
        return browse("", ids, wd, sort, time, t, pg, request);
    }

    @GetMapping("/youtube/{token}")
    public Object browse(@PathVariable String token, String ids, String wd, String sort, String time, String t, @RequestParam(required = false, defaultValue = "1") Integer pg,HttpServletRequest request) throws IOException {
        subscriptionService.checkToken(token);
        log.debug("{} {} {}", request.getMethod(), request.getRequestURI(), decodeUrl(request.getQueryString()));
        if (ids != null && !ids.isEmpty()) {
            if (ids.equals("recommend")) {
                return youtubeService.home();
            }
            return youtubeService.detail(ids);
        } else if (wd != null && !wd.isEmpty()) {
            return youtubeService.search(wd, sort, time, pg);
        } else if (t != null && !t.isEmpty()) {
            if (t.equals("0")) {
                return youtubeService.home();
            }
            return youtubeService.list(t, sort, time, pg);
        }
        return youtubeService.category();
    }

    @GetMapping("/watch")
    public Object play(String id, HttpServletRequest request) {
        return play("", id, request);
    }

    @GetMapping("/watch/{token}")
    public Object play(@PathVariable String token, String id, HttpServletRequest request) {
        subscriptionService.checkToken(token);

        String client = request.getHeader("X-CLIENT");
        log.debug("{} {} {} {}", request.getMethod(), request.getRequestURI(), decodeUrl(request.getQueryString()), client);
        return youtubeService.play(id, client);
    }

    @GetMapping("/youtube-proxy")
    public void proxy(String id, @RequestParam(defaultValue = "18") int q, HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxy("", id, q, request, response);
    }

    @GetMapping("/youtube-proxy/{token}")
    public void proxy(@PathVariable String token, String id, @RequestParam(defaultValue = "18") int q, HttpServletRequest request, HttpServletResponse response) throws IOException {
        subscriptionService.checkToken(token);

        log.debug("{} {} {}", request.getMethod(), request.getRequestURI(), decodeUrl(request.getQueryString()));
        youtubeService.proxy(id, q, request, response);
    }

    private String decodeUrl(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (Exception e) {
            return text;
        }
    }
}
