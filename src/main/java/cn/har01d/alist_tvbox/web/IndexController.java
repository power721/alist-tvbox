package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.IndexRequest;
import cn.har01d.alist_tvbox.dto.IndexResponse;
import cn.har01d.alist_tvbox.dto.VersionDto;
import cn.har01d.alist_tvbox.service.IndexService;
import cn.har01d.alist_tvbox.util.Utils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/index")

public class IndexController {
    private final IndexService indexService;

    public IndexController(IndexService indexService) {
        this.indexService = indexService;
    }

    @GetMapping("/version")
    public VersionDto getRemoteVersion() {
        return new VersionDto(Utils.trim(indexService.getRemoteVersion()));
    }

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping
    public IndexResponse index(@RequestBody IndexRequest indexRequest) throws IOException {
        return indexService.index(indexRequest);
    }
}
