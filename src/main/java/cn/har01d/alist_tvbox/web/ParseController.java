package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.ParseResponse;
import cn.har01d.alist_tvbox.service.ParseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/parse")
public class ParseController {
    private final ParseService parseService;

    public ParseController(ParseService parseService) {
        this.parseService = parseService;
    }

    @GetMapping
    public ParseResponse parse(String url) {
        return new ParseResponse(parseService.parse(url));
    }
}
