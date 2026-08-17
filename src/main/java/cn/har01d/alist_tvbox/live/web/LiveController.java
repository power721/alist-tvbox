package cn.har01d.alist_tvbox.live.web;

import cn.har01d.alist_tvbox.dto.LiveFollowDto;
import cn.har01d.alist_tvbox.live.service.LiveFollowService;
import cn.har01d.alist_tvbox.live.service.LiveProxyService;
import cn.har01d.alist_tvbox.live.service.LiveService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
public class LiveController {
    private final LiveService liveService;
    private final LiveFollowService liveFollowService;
    private final LiveProxyService liveProxyService;
    private final SubscriptionService subscriptionService;

    public LiveController(LiveService liveService, LiveFollowService liveFollowService, LiveProxyService liveProxyService, SubscriptionService subscriptionService) {
        this.liveService = liveService;
        this.liveFollowService = liveFollowService;
        this.liveProxyService = liveProxyService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/live")
    public Object browse(String ids, String wd, String ac, String t, String platform, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        return browse("", ids, wd, ac, t, platform, sort, pg);
    }

    @GetMapping("/live/{token}")
    public Object browse(@PathVariable String token, String ids, String wd, String ac, String t, String platform, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        subscriptionService.checkToken(token);
        if (ids != null && !ids.isEmpty()) {
            if (ids.equals("recommend")) {
                return liveService.home();
            }
            return liveService.detail(ids, platform);
        } else if (wd != null && !wd.isEmpty()) {
            return liveService.search(wd);
        } else if (t != null && !t.isEmpty()) {
            if (t.equals("0")) {
                return liveService.home();
            }
            if (t.equals(LiveFollowService.CATEGORY_ID)) {
                return liveFollowService.list(liveFollowService.resolveUid(token));
            }
            return liveService.list(t, ac, sort, pg);
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

    @GetMapping("/live-proxy")
    public void proxy(String u, HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxy("", u, request, response);
    }

    @GetMapping("/live-proxy/{token}")
    public void proxy(@PathVariable String token, String u, HttpServletRequest request, HttpServletResponse response) throws IOException {
        subscriptionService.checkToken(token);
        liveProxyService.proxy(u, request, response);
    }

    @PostMapping("/live/follow")
    public Map<String, Object> follow(@RequestBody LiveFollowDto dto) {
        return follow("", dto);
    }

    @PostMapping("/live/{token}/follow")
    public Map<String, Object> follow(@PathVariable String token, @RequestBody LiveFollowDto dto) {
        subscriptionService.checkToken(token);
        int uid = liveFollowService.resolveUid(token);
        liveFollowService.follow(uid, dto.getPlatform(), dto.getRoomId());
        return Map.of("success", true, "followed", true);
    }

    @PostMapping("/live/unfollow")
    public Map<String, Object> unfollow(@RequestBody LiveFollowDto dto) {
        return unfollow("", dto);
    }

    @PostMapping("/live/{token}/unfollow")
    public Map<String, Object> unfollow(@PathVariable String token, @RequestBody LiveFollowDto dto) {
        subscriptionService.checkToken(token);
        int uid = liveFollowService.resolveUid(token);
        boolean deleted = liveFollowService.unfollow(uid, dto.getPlatform(), dto.getRoomId());
        return Map.of("success", true, "followed", false, "deleted", deleted);
    }
}
