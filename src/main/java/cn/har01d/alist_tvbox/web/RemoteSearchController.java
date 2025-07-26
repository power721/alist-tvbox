package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.RemoteSearchService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RemoteSearchController {
    private final SubscriptionService subscriptionService;
    private final RemoteSearchService remoteSearchService;

    public RemoteSearchController(SubscriptionService subscriptionService, RemoteSearchService remoteSearchService) {
        this.subscriptionService = subscriptionService;
        this.remoteSearchService = remoteSearchService;
    }

    @GetMapping("/pansou")
    public Object pansou(String id, String wd, @RequestParam(required = false, defaultValue = "1") int pg) {
        return pansou("", id, wd, pg);
    }

    @GetMapping("/pansou/{token}")
    public Object pansou(@PathVariable String token, String id, String wd, @RequestParam(required = false, defaultValue = "1") int pg) {
        subscriptionService.checkToken(token);
        if (StringUtils.isNotBlank(id)) {
            return remoteSearchService.detail(id);
        } else if (StringUtils.isNotBlank(wd)) {
            return remoteSearchService.pansou(wd);
        }
        return null;
    }
}
