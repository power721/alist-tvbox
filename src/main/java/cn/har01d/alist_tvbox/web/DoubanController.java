package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.Versions;
import cn.har01d.alist_tvbox.service.DoubanService;
import cn.har01d.alist_tvbox.service.IndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DoubanController {
    private final DoubanService service;
    private final IndexService indexService;

    public DoubanController(DoubanService service, IndexService indexService) {
        this.service = service;
        this.indexService = indexService;
    }

    @GetMapping("/versions")
    public Versions getRemoteVersion() {
        Versions versions = new Versions();
        versions.setMovie(service.getRemoteVersion().trim());
        versions.setIndex(indexService.getRemoteVersion().trim());
        versions.setApp(service.getAppRemoteVersion().trim());
        return versions;
    }
}
