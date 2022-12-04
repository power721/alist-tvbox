package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.TvBoxService;
import cn.har01d.alist_tvbox.tvbox.IndexRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;

@Slf4j
@RestController
@RequestMapping
public class TvBoxController {
    private final TvBoxService tvBoxService;

    public TvBoxController(TvBoxService tvBoxService) {
        this.tvBoxService = tvBoxService;
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

    @Async
    @PostMapping("/index")
    public void index(@RequestBody IndexRequest indexRequest) throws IOException {
        tvBoxService.index(indexRequest);
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
