package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.ParseRequest;
import cn.har01d.alist_tvbox.service.ParseService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class ParseController {
    private final ParseService parseService;
    private final SubscriptionService subscriptionService;

    public ParseController(ParseService parseService, SubscriptionService subscriptionService) {
        this.parseService = parseService;
        this.subscriptionService = subscriptionService;
    }

    @PostMapping("/parse")
    public Object parse(@RequestBody ParseRequest request,
                        @RequestParam(required = false, defaultValue = "") String ac) {
        return parse("", request, ac);
    }

    @PostMapping("/parse/{token}")
    public Object parse(@PathVariable String token,
                        @RequestBody ParseRequest request,
                        @RequestParam(required = false, defaultValue = "") String ac) {
        log.debug("parse: {} {} {}", token, request, ac);
        subscriptionService.checkToken(token);

        return parseService.parse(request, ac);
    }
}
