package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
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
    public Object api(String t, String ids, String wd, String sort, Integer pg, HttpServletRequest request) {
        log.debug("{} {} {}", request.getMethod(), request.getRequestURI(), decodeUrl(request.getQueryString()));
        log.info("path: {}  folder: {} keyword: {}  sort: {}", ids, t, wd, sort);
        if (ids != null && !ids.isEmpty()) {
            return tvBoxService.getDetail(ids);
        } else if (t != null && !t.isEmpty()) {
            return tvBoxService.getMovieList(t, sort, pg);
        } else if (wd != null && !wd.isEmpty()) {
            return tvBoxService.search(wd);
        } else {
            return tvBoxService.getCategoryList();
        }
    }

    @GetMapping("/sub")
    public Map<String, Object> subscription(String url) {
        return subscriptionService.subscription(url);
    }

    @GetMapping("/sub/{id}")
    public Map<String, Object> subscription(@PathVariable int id) {
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
