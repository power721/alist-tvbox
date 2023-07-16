package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.ParseService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/play")
public class PlayController {
    private final TvBoxService tvBoxService;
    private final BiliBiliService biliBiliService;
    private final ParseService parseService;
    private final SubscriptionService subscriptionService;

    public PlayController(TvBoxService tvBoxService, BiliBiliService biliBiliService, ParseService parseService, SubscriptionService subscriptionService) {
        this.tvBoxService = tvBoxService;
        this.biliBiliService = biliBiliService;
        this.parseService = parseService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping
    public Object play(Integer site, String path, String bvid, String type, HttpServletRequest request, HttpServletResponse response) throws IOException {
        return play("", site, path, bvid, type, request, response);
    }

    @GetMapping("/{token}")
    public Object play(@PathVariable String token, Integer site, String path, String bvid, String type, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!subscriptionService.getToken().equals(token)) {
            throw new BadRequestException();
        }

        log.debug("{} {} {}", request.getMethod(), request.getRequestURI(), request.getQueryString());
        log.info("get play url - site: {}  path: {}  bvid: {}  type: ", site, path, bvid, type);

        if (StringUtils.isNotBlank(bvid)) {
            return biliBiliService.getPlayUrl(bvid);
        }

        String url = tvBoxService.getPlayUrl(site, path);
        response.sendRedirect(parseService.parse(url));
        return "";
    }
}
