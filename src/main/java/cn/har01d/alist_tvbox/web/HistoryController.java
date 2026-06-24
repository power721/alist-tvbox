package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.service.HistoryService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class HistoryController {
    private final HistoryService historyService;
    private final SubscriptionService subscriptionService;

    public HistoryController(HistoryService historyService, SubscriptionService subscriptionService) {
        this.historyService = historyService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/api/history")
    public Page<History> list(Pageable pageable) {
        return historyService.list(pageable);
    }

    @PostMapping("/api/history")
    public History create(@RequestBody History history) {
        return historyService.save(history);
    }

    @GetMapping("/api/history/{id}")
    public History get(@PathVariable Integer id) {
        return historyService.get(id);
    }

    @PostMapping("/api/history/{id}")
    public History update(@PathVariable Integer id, @RequestBody History history) {
        history.setId(id);
        return historyService.save(history);
    }

    @PostMapping("/api/history/-/delete")
    public void delete(@RequestBody List<Integer> ids) {
        historyService.delete(ids);
    }

    @DeleteMapping("/api/history/{id}")
    public void delete(@PathVariable Integer id) {
        historyService.deleteById(id);
    }

    @GetMapping("/history/{token}")
    public Object pull(@PathVariable String token, String key) {
        subscriptionService.checkToken(token);

        if (StringUtils.isBlank(key)) {
            return historyService.findAll();
        } else {
            return historyService.findById(key);
        }
    }

    @PostMapping("/history/{token}")
    public void push(@PathVariable String token, @RequestBody List<History> history) {
        subscriptionService.checkToken(token);

        historyService.saveAll(history);
    }

    @DeleteMapping("/history/{token}")
    public void delete(@PathVariable String token, String key) {
        subscriptionService.checkToken(token);
        if (StringUtils.isBlank(key)) {
            historyService.deleteAll();
        } else {
            historyService.delete(key);
        }
    }
}
