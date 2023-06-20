package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.VersionDto;
import cn.har01d.alist_tvbox.service.IndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IndexController {
    private final IndexService service;

    public IndexController(IndexService service) {
        this.service = service;
    }

    @GetMapping("/index/version")
    public VersionDto getRemoteVersion() {
        return new VersionDto(service.getRemoteVersion().trim());
    }

}
