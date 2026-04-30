package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Feiniu;
import cn.har01d.alist_tvbox.service.FeiniuService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.List;

@RestController
public class FeiniuController {
    private final FeiniuService feiniuService;
    private final SubscriptionService subscriptionService;

    public FeiniuController(FeiniuService feiniuService, SubscriptionService subscriptionService) {
        this.feiniuService = feiniuService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/api/feiniu")
    public List<Feiniu> list() {
        return feiniuService.findAll();
    }

    @PostMapping("/api/feiniu")
    public Feiniu create(@RequestBody Feiniu dto) {
        return feiniuService.create(dto);
    }

    @GetMapping("/api/feiniu/{id}")
    public Feiniu get(@PathVariable int id) {
        return feiniuService.getById(id);
    }

    @PostMapping("/api/feiniu/{id}")
    public Feiniu update(@PathVariable int id, @RequestBody Feiniu dto) {
        return feiniuService.update(id, dto);
    }

    @DeleteMapping("/api/feiniu/{id}")
    public void delete(@PathVariable int id) {
        feiniuService.delete(id);
    }

    @GetMapping("/feiniu")
    public Object browse(String ids, String wd, String t, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        return browse("", ids, wd, t, sort, pg);
    }

    @GetMapping("/feiniu/{token}")
    public Object browse(@PathVariable String token, String ids, String wd, String t, String sort, @RequestParam(required = false, defaultValue = "1") Integer pg) throws IOException {
        subscriptionService.checkToken(token);
        if (ids != null && !ids.isEmpty()) {
            if (ids.equals("recommend")) {
                return feiniuService.home();
            }
            return feiniuService.detail(ids);
        } else if (wd != null && !wd.isEmpty()) {
            return feiniuService.search(wd);
        } else if (t != null && !t.isEmpty()) {
            if (t.equals("0")) {
                return feiniuService.home();
            }
            return feiniuService.list(t, sort, pg);
        }
        return feiniuService.category();
    }

    @GetMapping("/feiniu-play")
    public Object play(String id, Long t, HttpServletRequest request) throws IOException {
        return play("", id, t, request);
    }

    @GetMapping("/feiniu-play/{token}")
    public Object play(@PathVariable String token, String id, Long t, HttpServletRequest request) throws IOException {
        subscriptionService.checkToken(token);
        if (t == null || t == 0) {
            return feiniuService.play(id, token, baseUrl(request));
        }
        feiniuService.updateProgress(id, t);
        return "";
    }

    @RequestMapping("/feiniu-proxy/{token}")
    public void proxy(@PathVariable String token, int site, String path,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        subscriptionService.checkToken(token);
        feiniuService.proxy(site, path, token, baseUrl(request), request, response);
    }

    private String baseUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequest(request)
                .replacePath(null)
                .replaceQuery(null)
                .build()
                .toUriString();
    }
}
