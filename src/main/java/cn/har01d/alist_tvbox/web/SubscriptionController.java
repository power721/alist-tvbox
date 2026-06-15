package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Subscription;
import cn.har01d.alist_tvbox.entity.SubscriptionRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        if (subscription.getId() != null && subscription.getId() == 0) {
            subscription.setId(null);
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
    @Transactional(timeout = 10)  // 10秒超时保护
    public List<Subscription> findAll() {
        long start = System.currentTimeMillis();
        log.info("📋 开始查询订阅列表");

        try {
            List<Subscription> result = subscriptionService.findAll();
            long duration = System.currentTimeMillis() - start;

            log.info("✅ 订阅列表查询成功: {} 条, 耗时 {}ms", result.size(), duration);

            // 检查是否有异常数据
            for (Subscription sub : result) {
                if (sub.getOverride() != null && sub.getOverride().length() > 100_000) {
                    log.warn("⚠️ 发现超大 override 字段: id={}, size={}KB",
                            sub.getId(), sub.getOverride().length() / 1024);
                }
            }

            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("❌ 订阅列表查询失败, 耗时 {}ms", duration, e);
            // 返回空列表而不是抛出异常，防止页面完全无法加载
            return Collections.emptyList();
        }
    }

    @DeleteMapping("/{id}")
    public void deleteById(@PathVariable Integer id) {
        if (id == 0) {
            return;
        }
        subscriptionRepository.deleteById(id);
    }

    @GetMapping("/global-config")
    public Map<String, Object> getGlobalConfig() {
        return subscriptionService.getGlobalConfig();
    }

    @PutMapping("/global-config")
    public void updateGlobalConfig(@RequestBody Map<String, Object> config) {
        subscriptionService.updateGlobalConfig(config);
    }

    @GetMapping("/{sid}/catalog")
    public Map<String, Object> getCatalog(@PathVariable String sid) {
        return subscriptionService.getCatalog(sid);
    }

}

