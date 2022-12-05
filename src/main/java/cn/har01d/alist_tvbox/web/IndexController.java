package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.IndexService;
import cn.har01d.alist_tvbox.tvbox.IndexRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/index")
public class IndexController {
    private final IndexService indexService;

    public IndexController(IndexService indexService) {
        this.indexService = indexService;
    }

    @Async
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping
    public void index(@RequestBody IndexRequest indexRequest) throws IOException {
        indexService.index(indexRequest);
    }
}
