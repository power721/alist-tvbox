package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.ParseService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Slf4j
@Controller
@RequestMapping("/play")
public class PlayController {
    private final TvBoxService tvBoxService;
    private final ParseService parseService;

    public PlayController(TvBoxService tvBoxService, ParseService parseService) {
        this.tvBoxService = tvBoxService;
        this.parseService = parseService;
    }

    @GetMapping
    public void play(Integer site, String path, HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.debug("{} {} {}", request.getMethod(), request.getRequestURI(), request.getQueryString());
        log.debug("get play url - site: {}  path: {}", site, path);
        String url = tvBoxService.getPlayUrl(site, path);
        response.sendRedirect(parseService.parse(url));
    }
}
