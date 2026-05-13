package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.PluginFilterService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PluginFilterContentController {
    private final SubscriptionService subscriptionService;
    private final PluginFilterService pluginFilterService;

    public PluginFilterContentController(SubscriptionService subscriptionService, PluginFilterService pluginFilterService) {
        this.subscriptionService = subscriptionService;
        this.pluginFilterService = pluginFilterService;
    }

    @GetMapping(value = "/plugin-filters/{token}/{id}.py", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> content(@PathVariable String token, @PathVariable Integer id) {
        subscriptionService.checkToken(token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_PLAIN)
                .body(pluginFilterService.readContent(id));
    }
}
