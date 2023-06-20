package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Subscription;
import cn.har01d.alist_tvbox.entity.SubscriptionRepository;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionRepository subscriptionRepository, SubscriptionService subscriptionService) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public Subscription save(@RequestBody Subscription subscription) {
        return subscriptionRepository.save(subscription);
    }

    @GetMapping
    public List<Subscription> findAll() {
        return subscriptionService.findAll();
    }

    @DeleteMapping("/{id}")
    public void deleteById(@PathVariable Integer id) {
        if (id == 0) {
            return;
        }
        subscriptionRepository.deleteById(id);
    }

}
