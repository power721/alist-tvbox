package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.FilterDto;
import cn.har01d.alist_tvbox.dto.bili.CookieData;
import cn.har01d.alist_tvbox.dto.bili.QrCode;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URLDecoder;
import java.util.Map;

@Slf4j
@RestController
public class BiliBiliController {
    private final BiliBiliService biliBiliService;
    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    public BiliBiliController(BiliBiliService biliBiliService, SubscriptionService subscriptionService, ObjectMapper objectMapper) {
        this.biliBiliService = biliBiliService;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/bilibili")
    public String api(String t, String ids, String wd,
                      boolean quick,
                      FilterDto filter,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        return api("", t, ids, wd, quick, filter, pg, request, response);
    }

    @GetMapping("/bilibili/{token}")
    public String api(@PathVariable String token, String t, String ids, String wd,
                      boolean quick,
                      FilterDto filter,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        subscriptionService.checkToken(token);
        response.setContentType("application/json");

        log.debug("{} {} {}", request.getMethod(), request.getRequestURI(), decodeUrl(request.getQueryString()));
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
        return objectMapper.writeValueAsString(result);
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
