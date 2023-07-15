package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.BiliBiliService;
import cn.har01d.alist_tvbox.service.ParseService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Slf4j
@Controller
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
    public void play(Integer site, String path, String bvid, Integer aid, Integer cid, HttpServletRequest request, HttpServletResponse response) throws IOException {
        play("", site, path, bvid, aid, cid, request, response);
    }

    @GetMapping("/{token}")
    public void play(@PathVariable String token, Integer site, String path, String bvid, Integer aid, Integer cid, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!subscriptionService.getToken().equals(token)) {
            throw new BadRequestException();
        }

        log.debug("{} {} {}", request.getMethod(), request.getRequestURI(), request.getQueryString());
        log.debug("get play url - site: {}  path: {}", site, path);

        if (StringUtils.isNotBlank(bvid)) {
            response.sendRedirect(biliBiliService.getPlayUrl(bvid, aid, cid));
            return;
        }

        String url = tvBoxService.getPlayUrl(site, path);
        response.sendRedirect(parseService.parse(url));
    }
}
