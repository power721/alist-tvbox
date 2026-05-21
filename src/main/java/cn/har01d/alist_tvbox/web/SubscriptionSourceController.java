package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.SubscriptionSourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/subscription-sources")
public class SubscriptionSourceController {
    private final SubscriptionSourceService subscriptionSourceService;

    public SubscriptionSourceController(SubscriptionSourceService subscriptionSourceService) {
        this.subscriptionSourceService = subscriptionSourceService;
    }

    @GetMapping
    public List<SubscriptionSourceService.ManagedSource> findAll() {
        return subscriptionSourceService.findAll();
    }

    @PutMapping("/{id}")
    public SubscriptionSourceService.ManagedSource update(@PathVariable String id,
                                                          @RequestBody SubscriptionSourceService.ManagedSourceUpdate update) {
        return subscriptionSourceService.update(id, update);
    }

    @PostMapping("/reorder")
    public void reorder(@RequestBody List<String> ids) {
        subscriptionSourceService.reorder(ids);
    }
}
