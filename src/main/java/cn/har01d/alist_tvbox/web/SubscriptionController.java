package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Subscription;
import cn.har01d.alist_tvbox.entity.SubscriptionRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    public SubscriptionController(SubscriptionRepository subscriptionRepository, SubscriptionService subscriptionService, ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public Subscription save(@RequestBody Subscription subscription) {
        if (StringUtils.isNotBlank(subscription.getSid())) {
            Subscription other = subscriptionRepository.findBySid(subscription.getSid()).orElse(null);
            if (other != null && !other.getId().equals(subscription.getId())) {
                throw new BadRequestException("订阅ID重复");
            }
        }
        subscription = subscriptionRepository.save(subscription);
        if (StringUtils.isBlank(subscription.getSid())) {
            subscription.setSid(String.valueOf(subscription.getId()));
            Subscription other = subscriptionRepository.findBySid(subscription.getSid()).orElse(null);
            if (other != null && !other.getId().equals(subscription.getId())) {
                throw new BadRequestException("订阅ID重复");
            }
        }
        if (StringUtils.isNotBlank(subscription.getOverride())) {
            try {
                var node = objectMapper.readTree(subscription.getOverride());
                subscription.setOverride(objectMapper.writeValueAsString(node));
            } catch (IOException e) {
                throw new BadRequestException("JSON格式错误", e);
            }
        }
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
