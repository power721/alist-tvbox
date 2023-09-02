package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.bili.CookieData;
import cn.har01d.alist_tvbox.dto.bili.QrCode;
import cn.har01d.alist_tvbox.exception.BadRequestException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/bilibili")
public class BiliBiliController {
    private final BiliBiliService biliBiliService;
    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    public BiliBiliController(BiliBiliService biliBiliService, SubscriptionService subscriptionService, ObjectMapper objectMapper) {
        this.biliBiliService = biliBiliService;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("")
    public String api(String t, String f, String ids, String wd,
                      @RequestParam(required = false, defaultValue = "") String category,
                      @RequestParam(required = false, defaultValue = "") String type,
                      @RequestParam(required = false, defaultValue = "") String sort,
                      @RequestParam(required = false, defaultValue = "0") String duration,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        return api("", t, f, ids, wd, category, type, sort, duration, pg, request, response);
    }

    @GetMapping("/{token}")
    public String api(@PathVariable String token, String t, String f, String ids, String wd,
                      @RequestParam(required = false, defaultValue = "") String category,
                      @RequestParam(required = false, defaultValue = "") String type,
                      @RequestParam(required = false, defaultValue = "") String sort,
                      @RequestParam(required = false, defaultValue = "0") String duration,
                      @RequestParam(required = false, defaultValue = "1") Integer pg,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        if (!subscriptionService.getToken().equals(token)) {
            throw new BadRequestException();
        }
        response.setContentType("application/json");

        log.debug("{} {} {}", request.getMethod(), request.getRequestURI(), decodeUrl(request.getQueryString()));
        log.info("path: {}  folder: {}  category: {}  type: {} keyword: {}  filter: {}  sort: {} duration: {}  page: {}", ids, t, category, type, wd, f, sort, duration, pg);
        Object result;
        if (ids != null && !ids.isEmpty()) {
            if (ids.equals("recommend")) {
                result = biliBiliService.recommend();
            } else {
                result = biliBiliService.getDetail(ids);
            }
        } else if (t != null && !t.isEmpty()) {
            result = biliBiliService.getMovieList(t, category, type, sort, duration, pg);
        } else if (wd != null && !wd.isEmpty()) {
            result = biliBiliService.search(wd, sort, duration, 0);
        } else {
            result = biliBiliService.getCategoryList();
        }
        return objectMapper.writeValueAsString(result);
    }

    @GetMapping("/-/status")
    public Map<String, Object> getLoginStatus() {
        return biliBiliService.getLoginStatus();
    }

    @PostMapping("/cookie")
    public Map<String, Object> updateCookie(@RequestBody CookieData cookieData) {
        return biliBiliService.updateCookie(cookieData);
    }

    @PostMapping("/login")
    public QrCode scanLogin() throws IOException {
        return biliBiliService.scanLogin();
    }

    @GetMapping("/-/check")
    public int checkLogin(String key) {
        return biliBiliService.checkLogin(key);
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
