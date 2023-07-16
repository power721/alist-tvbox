package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;

@Slf4j
@RestController
@RequestMapping("/bilibili")
public class BiliBiliController {
    private final BiliBiliService biliBiliService;
    private final SubscriptionService subscriptionService;

    public BiliBiliController(BiliBiliService biliBiliService, SubscriptionService subscriptionService) {
        this.biliBiliService = biliBiliService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("")
    public Object api(String t, String f, String ids, String wd, String sort,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      HttpServletRequest request) throws IOException {
        return api("", t, f, ids, wd, sort, pg, request);
    }

    @GetMapping("/{token}")
    public Object api(@PathVariable String token, String t, String f, String ids, String wd,
                      @RequestParam(required = false, defaultValue = "") String sort,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      HttpServletRequest request) throws IOException {
        if (!subscriptionService.getToken().equals(token)) {
            throw new BadRequestException();
        }

        log.info("{} {} {}", request.getMethod(), request.getRequestURI(), decodeUrl(request.getQueryString()));
        log.info("path: {}  folder: {}  keyword: {}  filter: {}  sort: {}  page: {}", ids, t, wd, f, sort, pg);
        if (ids != null && !ids.isEmpty()) {
            if (ids.equals("recommend")) {
                return biliBiliService.recommend();
            }
            return biliBiliService.getDetail(ids);
        } else if (t != null && !t.isEmpty()) {
            return biliBiliService.getMovieList(t, f, sort, pg);
        } else if (wd != null && !wd.isEmpty()) {
            return biliBiliService.search(wd, sort, 0);
        } else {
            return biliBiliService.getCategoryList();
        }
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
