package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PluginContentController {
    private final SubscriptionService subscriptionService;
    private final PluginService pluginService;

    public PluginContentController(SubscriptionService subscriptionService, PluginService pluginService) {
        this.subscriptionService = subscriptionService;
        this.pluginService = pluginService;
    }

    @GetMapping(value = "/plugins/{token}/{id}.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String content(@PathVariable String token, @PathVariable Integer id) {
        subscriptionService.checkToken(token);
        return pluginService.readContent(id);
    }
}
