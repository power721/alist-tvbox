package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.TokenDto;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class TvBoxController {
    private final TvBoxService tvBoxService;
    private final SubscriptionService subscriptionService;

    public TvBoxController(TvBoxService tvBoxService, SubscriptionService subscriptionService) {
        this.tvBoxService = tvBoxService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/vod1")
    public Object api1(String t, String f, String ids, String ac, String wd, String sort,
                       @RequestParam(required = false, defaultValue = "1") Integer pg,
                       @RequestParam(required = false, defaultValue = "100") Integer size,
                       HttpServletRequest request) {
        return api("", t, f, ids, ac, wd, sort, pg, size, 0, request);
    }

    @GetMapping("/vod1/{token}")
    public Object api1(@PathVariable String token, String t, String f, String ids, String ac, String wd, String sort,
                       @RequestParam(required = false, defaultValue = "1") Integer pg,
                       @RequestParam(required = false, defaultValue = "100") Integer size,
                       HttpServletRequest request) {
        return api(token, t, f, ids, ac, wd, sort, pg, size, 0, request);
    }

    @GetMapping("/vod")
    public Object api(String t, String f, String ids, String ac, String wd, String sort,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      @RequestParam(required = false, defaultValue = "100") Integer size,
                      HttpServletRequest request) {
        return api("", t, f, ids, ac, wd, sort, pg, size, 1, request);
    }

    @GetMapping("/vod/{token}")
    public Object api(@PathVariable String token, String t, String f, String ids, String ac, String wd, String sort,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      @RequestParam(required = false, defaultValue = "100") Integer size,
                      @RequestParam(required = false, defaultValue = "1") Integer type,
                      HttpServletRequest request) {
        subscriptionService.checkToken(token);

        String client = request.getHeader("X-CLIENT");
        log.info("type: {}  path: {}  folder: {}  ac: {}  keyword: {}  filter: {}  sort: {}  page: {}", type, ids, t, ac, wd, f, sort, pg);
        if (ids != null && !ids.isEmpty()) {
            if (ids.startsWith("msearch:")) {
                return tvBoxService.msearch(type, ids.substring(8));
            } else if (ids.equals("recommend")) {
                return tvBoxService.recommend(ac, pg);
            }
            return tvBoxService.getDetail(ac, ids);
        } else if (t != null && !t.isEmpty()) {
            if (t.equals("0")) {
                return tvBoxService.recommend(ac, pg);
            }
            return tvBoxService.getMovieList(client, ac, t, f, sort, pg, size);
        } else if (wd != null && !wd.isEmpty()) {
            return tvBoxService.search(type, ac, wd, pg);
        } else {
            return tvBoxService.getCategoryList(type);
        }
    }

    @GetMapping("/m3u8")
    public String m3u8(String path, HttpServletResponse response) {
        return m3u8("", path, response);
    }

    @GetMapping("/m3u8/{token}")
    public String m3u8(@PathVariable String token, String path, HttpServletResponse response) {
        subscriptionService.checkToken(token);
        response.setContentType("text/plain");
        return tvBoxService.m3u8(path);
    }

    @GetMapping("/api/profiles")
    public List<String> getProfiles() {
        return subscriptionService.getProfiles();
    }

    @GetMapping("/api/token")
    public String getToken() {
        return subscriptionService.getTokens();
    }

    @PostMapping("/api/token")
    public String createToken(@RequestBody TokenDto dto) {
        return subscriptionService.createToken(dto);
    }

    @DeleteMapping("/api/token")
    public void deleteToken() {
        subscriptionService.deleteToken();
    }

    @GetMapping("/sub/{id}")
    public Map<String, Object> subscription(@PathVariable String id) {
        return subscription("", id);
    }

    @GetMapping("/sub/{token}/{id}")
    public Map<String, Object> subscription(@PathVariable String token, @PathVariable String id) {
        subscriptionService.checkToken(token);

        return subscriptionService.subscription(token, id);
    }

    @GetMapping("/open")
    public Map<String, Object> open() throws IOException {
        return open("");
    }

    @GetMapping("/open/{token}")
    public Map<String, Object> open(@PathVariable String token) throws IOException {
        subscriptionService.checkToken(token);

        return subscriptionService.open();
    }

    @GetMapping("/node/{token}/{file}")
    public String node(@PathVariable String token, @PathVariable String file) throws IOException {
        subscriptionService.checkToken(token);

        return subscriptionService.node(file);
    }

    @PostMapping("/api/cat/sync")
    public int syncCat() {
        return subscriptionService.syncCat();
    }

    @GetMapping(value = "/repo/{id}", produces = "application/json")
    public String repository(@PathVariable String id) {
        return repository("", id);
    }

    @GetMapping(value = "/repo/{token}/{id}", produces = "application/json")
    public String repository(@PathVariable String token, @PathVariable String id) {
        subscriptionService.checkToken(token);

        return subscriptionService.repository(token, id);
    }
}
