package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Emby;
import cn.har01d.alist_tvbox.service.EmbyService;
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
public class EmbyController {
    private final EmbyService embyService;
    private final SubscriptionService subscriptionService;

    public EmbyController(EmbyService embyService, SubscriptionService subscriptionService) {
        this.embyService = embyService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/api/emby")
    public List<Emby> list() {
        return embyService.findAll();
    }

    @PostMapping("/api/emby")
    public Emby create(@RequestBody Emby dto) {
        return embyService.create(dto);
    }

    @GetMapping("/api/emby/{id}")
    public Emby get(@PathVariable int id) {
        return embyService.getById(id);
    }

    @PostMapping("/api/emby/{id}")
    public Emby update(@PathVariable int id, @RequestBody Emby dto) {
        return embyService.update(id, dto);
    }

    @DeleteMapping("/api/emby/{id}")
    public void delete(@PathVariable int id) {
        embyService.delete(id);
    }

    @GetMapping("/emby")
    public Object browse(String ids, String wd, String t, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        return browse("", ids, wd, t, sort, pg);
    }

    @GetMapping("/emby/{token}")
    public Object browse(@PathVariable String token, String ids, String wd, String t, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        subscriptionService.checkToken(token);
        if (ids != null && !ids.isEmpty()) {
            if (ids.equals("recommend")) {
                return embyService.home();
            }
            return embyService.detail(ids);
        } else if (wd != null && !wd.isEmpty()) {
            return embyService.search(wd);
        } else if (t != null && !t.isEmpty()) {
            if (t.equals("0")) {
                return embyService.home();
            }
            return embyService.list(t, sort, pg);
        }
        return embyService.category();
    }

    @GetMapping("/emby-play")
    public Object play(String id, HttpServletRequest request) throws IOException {
        return play("", id, request);
    }

    @GetMapping("/emby-play/{token}")
    public Object play(@PathVariable String token, String id, HttpServletRequest request) throws IOException {
        subscriptionService.checkToken(token);

        String client = request.getHeader("X-CLIENT");
        return embyService.play(id);
    }
}
