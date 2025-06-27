package cn.har01d.alist_tvbox.service;

import java.util.List;

import org.springframework.stereotype.Service;

import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.entity.HistoryRepository;

@Service
public class HistoryService {
    private final HistoryRepository historyRepository;
    private final SubscriptionService subscriptionService;

    public HistoryService(HistoryRepository historyRepository, SubscriptionService subscriptionService) {
        this.historyRepository = historyRepository;
        this.subscriptionService = subscriptionService;
    }

    public List<History> findAll() {
        return historyRepository.findAll();
    }

    public History get(Integer id) {
        return historyRepository.findById(id).orElse(null);
    }

    public List<History> findAll(String uid, int cid) {
        if (cid == 0) {
            cid = subscriptionService.getCid(uid);
        }
        return historyRepository.findByCid(cid);
    }

    public History findById(String uid, int cid, String key) {
        if (cid == 0) {
            cid = subscriptionService.getCid(uid);
        }
        return historyRepository.findByCidAndKey(cid, key);
    }

    public void saveAll(String uid, List<History> histories) {
        for (var history : histories) {
            if (history.getCid() == 0) {
                history.setCid(subscriptionService.getCid(uid));
            }
            var exist = findById(uid, history.getCid(), history.getKey());
            if (exist != null) {
                history.setId(exist.getId());
            }
        }
        historyRepository.saveAll(histories);
    }

    public History save(String uid, History history) {
        if (history.getCid() == 0) {
            history.setCid(subscriptionService.getCid(uid));
        }
        var exist = findById(uid, history.getCid(), history.getKey());
        if (exist != null) {
            history.setId(exist.getId());
        }
        return historyRepository.save(history);
    }

    public void delete(String uid, int cid) {
        if (cid == 0) {
            cid = subscriptionService.getCid(uid);
        }
        historyRepository.deleteByCid(cid);
    }

    public void delete(String uid, int cid, String key) {
        if (cid == 0) {
            cid = subscriptionService.getCid(uid);
        }
        historyRepository.deleteByCidAndKey(cid, key);
    }

    public void deleteAll() {
        historyRepository.deleteAll();
    }

    public void deleteById(Integer id) {
        historyRepository.deleteById(id);
    }
}
