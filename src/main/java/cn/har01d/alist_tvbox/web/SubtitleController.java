package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.BiliBiliService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/subtitles")
public class SubtitleController {

    private final BiliBiliService biliBiliService;

    public SubtitleController(BiliBiliService biliBiliService) {

        this.biliBiliService = biliBiliService;
    }

    @GetMapping(value = "", produces = "text/plain")
    public String getImage(String url) {
        log.debug("get subtitle by url: {}", url);
        return biliBiliService.getSubtitle(url);
    }

}
