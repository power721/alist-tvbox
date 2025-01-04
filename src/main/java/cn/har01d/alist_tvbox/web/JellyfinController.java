package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Jellyfin;
import cn.har01d.alist_tvbox.service.JellyfinService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
public class JellyfinController {
    private final JellyfinService jellyfinService;
    private final SubscriptionService subscriptionService;

    public JellyfinController(JellyfinService jellyfinService, SubscriptionService subscriptionService) {
        this.jellyfinService = jellyfinService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/api/jellyfin")
    public List<Jellyfin> list() {
        return jellyfinService.findAll();
    }

    @PostMapping("/api/jellyfin")
    public Jellyfin create(@RequestBody Jellyfin dto) {
        return jellyfinService.create(dto);
    }

    @GetMapping("/api/jellyfin/{id}")
    public Jellyfin get(@PathVariable int id) {
        return jellyfinService.getById(id);
    }

    @PostMapping("/api/jellyfin/{id}")
    public Jellyfin update(@PathVariable int id, @RequestBody Jellyfin dto) {
        return jellyfinService.update(id, dto);
    }

    @DeleteMapping("/api/jellyfin/{id}")
    public void delete(@PathVariable int id) {
        jellyfinService.delete(id);
    }

    @GetMapping("/jellyfin")
    public Object browse(String ids, String wd, String t, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        return browse("", ids, wd, t, sort, pg);
    }

    @GetMapping("/jellyfin/{token}")
    public Object browse(@PathVariable String token, String ids, String wd, String t, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        subscriptionService.checkToken(token);
        if (ids != null && !ids.isEmpty()) {
            if (ids.equals("recommend")) {
                return jellyfinService.home();
            }
            return jellyfinService.detail(ids);
        } else if (wd != null && !wd.isEmpty()) {
            return jellyfinService.search(wd);
        } else if (t != null && !t.isEmpty()) {
            if (t.equals("0")) {
                return jellyfinService.home();
            }
            return jellyfinService.list(t, sort, pg);
        }
        return jellyfinService.category();
    }

    @GetMapping("/jellyfin-play")
    public Object play(String id, HttpServletRequest request) throws IOException {
        return play("", id, request);
    }

    @GetMapping("/jellyfin-play/{token}")
    public Object play(@PathVariable String token, String id, HttpServletRequest request) throws IOException {
        subscriptionService.checkToken(token);

        String client = request.getHeader("X-CLIENT");
        return jellyfinService.play(id);
    }
}
