package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.PlayListService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/playlist")
public class PlayListController {
    private final PlayListService playListService;

    public PlayListController(PlayListService playListService) {
        this.playListService = playListService;
    }

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping
    public void generate(@RequestParam(required = false) String name, String path) throws IOException {
        playListService.generate(name, path);
    }
}
