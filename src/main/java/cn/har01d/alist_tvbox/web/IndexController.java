package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.IndexRequest;
import cn.har01d.alist_tvbox.dto.IndexResponse;
import cn.har01d.alist_tvbox.dto.VersionDto;
import cn.har01d.alist_tvbox.service.IndexService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/index")
public class IndexController {
    private final IndexService indexService;

    public IndexController(IndexService indexService) {
        this.indexService = indexService;
    }

    @GetMapping("/version")
    public VersionDto getRemoteVersion() {
        return new VersionDto(indexService.getRemoteVersion().trim());
    }

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping
    public IndexResponse index(@RequestBody IndexRequest indexRequest) throws IOException {
        return indexService.index(indexRequest);
    }
}
