package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.VersionDto;
import cn.har01d.alist_tvbox.service.DoubanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DoubanController {
    private final DoubanService service;

    public DoubanController(DoubanService service) {
        this.service = service;
    }

    @GetMapping("/movie/version")
    public VersionDto getRemoteVersion() {
        return new VersionDto(service.getRemoteVersion().trim());
    }

}
