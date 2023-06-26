package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.TokenDto;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
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

    @GetMapping("/vod")
    public Object api(String t, String ids, String wd, String sort,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      HttpServletRequest request) {
        return api("", t, ids, wd, sort, pg, request);
    }

    @GetMapping("/vod/{token}")
    public Object api(@PathVariable String token, String t, String ids, String wd, String sort,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      HttpServletRequest request) {
        if (!subscriptionService.getToken().equals(token)) {
            throw new BadRequestException();
        }

        log.debug("{} {} {}", request.getMethod(), request.getRequestURI(), decodeUrl(request.getQueryString()));
        log.info("path: {}  folder: {} keyword: {}  sort: {}", ids, t, wd, sort);
        if (ids != null && !ids.isEmpty()) {
            if (ids.startsWith("msearch:")) {
                return tvBoxService.msearch(ids.substring(8));
            } else if (ids.equals("recommend")) {
                return tvBoxService.recommend();
            }
            return tvBoxService.getDetail(ids);
        } else if (t != null && !t.isEmpty()) {
            return tvBoxService.getMovieList(t, sort, pg);
        } else if (wd != null && !wd.isEmpty()) {
            return tvBoxService.search(wd);
        } else {
            return tvBoxService.getCategoryList();
        }
    }

    @GetMapping("/profiles")
    public List<String> getProfiles() {
        return subscriptionService.getProfiles();
    }

    @GetMapping("/token")
    public String getToken() {
        return subscriptionService.getToken();
    }

    @PostMapping("/token")
    public String createToken(@RequestBody TokenDto dto) {
        return subscriptionService.createToken(dto);
    }

    @DeleteMapping("/token")
    public void deleteToken() {
        subscriptionService.deleteToken();
    }

    @GetMapping("/sub/{id}")
    public Map<String, Object> subscription(@PathVariable int id) {
        return subscription("", id);
    }

    @GetMapping("/sub/{token}/{id}")
    public Map<String, Object> subscription(@PathVariable String token, @PathVariable int id) {
        if (!subscriptionService.getToken().equals(token)) {
            throw new BadRequestException();
        }

        return subscriptionService.subscription(id);
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
