package cn.har01d.alist_tvbox.live.web;

import cn.har01d.alist_tvbox.live.service.LiveService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class LiveController {
    private final LiveService liveService;
    private final SubscriptionService subscriptionService;

    public LiveController(LiveService liveService, SubscriptionService subscriptionService) {
        this.liveService = liveService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/live")
    public Object browse(String ids, String wd, String t, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        return browse("", ids, wd, t, sort, pg);
    }

    @GetMapping("/live/{token}")
    public Object browse(@PathVariable String token, String ids, String wd, String t, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        subscriptionService.checkToken(token);
        if (ids != null && !ids.isEmpty()) {
            if (ids.equals("recommend")) {
                return liveService.home();
            }
            return liveService.detail(ids);
        } else if (wd != null && !wd.isEmpty()) {
            return liveService.search(wd);
        } else if (t != null && !t.isEmpty()) {
            if (t.equals("0")) {
                return liveService.home();
            }
            return liveService.list(t, sort, pg);
        }
        return liveService.category();
    }

    @GetMapping("/live-play")
    public Object play(String id, HttpServletRequest request) throws IOException {
        return play("", id, request);
    }

    @GetMapping("/live-play/{token}")
    public Object play(@PathVariable String token, String id, HttpServletRequest request) throws IOException {
        subscriptionService.checkToken(token);

        return liveService.play(id);
    }
}
