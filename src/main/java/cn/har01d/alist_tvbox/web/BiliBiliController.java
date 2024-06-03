package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.FilterDto;
import cn.har01d.alist_tvbox.dto.bili.CookieData;
import cn.har01d.alist_tvbox.dto.bili.QrCode;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
public class BiliBiliController {
    private final BiliBiliService biliBiliService;
    private final SubscriptionService subscriptionService;

    public BiliBiliController(BiliBiliService biliBiliService, SubscriptionService subscriptionService) {
        this.biliBiliService = biliBiliService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/bilibili")
    public Object api(String t, String ids, String wd,
                      boolean quick,
                      FilterDto filter,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        return api("", t, ids, wd, quick, filter, pg, request, response);
    }

    @GetMapping("/bilibili/{token}")
    public Object api(@PathVariable String token, String t, String ids, String wd,
                      boolean quick,
                      FilterDto filter,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        subscriptionService.checkToken(token);
        response.setContentType("application/json");

        log.info("path: {}  folder: {}  keyword: {}  filter: {}  quick: {} page: {}", ids, t, wd, filter, quick, pg);
        Object result;
        if (ids != null && !ids.isEmpty()) {
            if (ids.equals("recommend")) {
                result = biliBiliService.recommend();
            } else {
                result = biliBiliService.getDetail(ids);
            }
        } else if (t != null && !t.isEmpty()) {
            result = biliBiliService.getMovieList(t, filter, pg);
        } else if (wd != null && !wd.isEmpty()) {
            result = biliBiliService.search(wd, filter.getSort(), filter.getDuration(), 0, quick);
        } else {
            result = biliBiliService.getCategoryList();
        }
        return result;
    }

    @GetMapping("/api/bilibili/status")
    public Map<String, Object> getLoginStatus() {
        return biliBiliService.getLoginStatus();
    }

    @GetMapping("/api/bilibili/check")
    public int checkLogin(String key) {
        return biliBiliService.checkLogin(key);
    }

    @PostMapping("/api/bilibili/checkin")
    public void checkin() {
        biliBiliService.checkin();
    }

    @PostMapping("/api/bilibili/cookie")
    public Map<String, Object> updateCookie(@RequestBody CookieData cookieData) {
        return biliBiliService.updateCookie(cookieData);
    }

    @PostMapping("/api/bilibili/login")
    public QrCode scanLogin() throws IOException {
        return biliBiliService.scanLogin();
    }
}
