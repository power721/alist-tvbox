package cn.har01d.alist_tvbox.web;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.service.HistoryService;
import cn.har01d.alist_tvbox.service.SubscriptionService;

@RestController
public class HistoryController {
    private final HistoryService historyService;
    private final SubscriptionService subscriptionService;

    public HistoryController(HistoryService historyService, SubscriptionService subscriptionService) {
        this.historyService = historyService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/api/history")
    public List<History> list() {
        return historyService.findAll();
    }

    @GetMapping("/api/history")
    public History create(@RequestBody History history) {
        return historyService.save("", history);
    }

    @GetMapping("/api/history/{id}")
    public History get(@PathVariable Integer id) {
        return historyService.get(id);
    }

    @GetMapping("/api/history/{id}")
    public History update(@PathVariable Integer id, @RequestBody History history) {
        history.setId(id);
        return historyService.save("", history);
    }

    @GetMapping("/api/history/{id}")
    public void delete(@PathVariable Integer id) {
        historyService.deleteById(id);
    }

    @GetMapping("/history/{token}/{uid}")
    public List<History> pull(@PathVariable String token, @PathVariable String uid, Integer cid, String key) {
        subscriptionService.checkToken(token);

        if (cid != null) {
            if (StringUtils.isBlank(key)) {
                return historyService.findAll(uid, cid);
            } else {
                var history = historyService.findById(uid, cid, key);
                if (history == null) {
                    return List.of();
                } else {
                    return List.of(history);
                }
            }
        } else {
            return historyService.findAll();
        }
    }

    @PostMapping("/history/{token}/{uid}")
    public void push(@PathVariable String token, @PathVariable String uid, @RequestBody List<History> history) {
        subscriptionService.checkToken(token);

        historyService.saveAll(uid, history);
    }

    @PostMapping("/history/{token}/{uid}/cid")
    public void pushCid(@PathVariable String token, @PathVariable String uid, int cid) {
        subscriptionService.checkToken(token);

        subscriptionService.saveCid(uid, cid);
    }

    @DeleteMapping("/history/{token}/{uid}")
    public void delete(@PathVariable String token, @PathVariable String uid, Integer cid, String key) {
        subscriptionService.checkToken(token);

        if (cid != null) {
            if (StringUtils.isBlank(key)) {
                historyService.delete(uid, cid);
            } else {
                historyService.delete(uid, cid, key);
            }
        } else {
            historyService.deleteAll();
        }
    }
}
