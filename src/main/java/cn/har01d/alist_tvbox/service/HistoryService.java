package cn.har01d.alist_tvbox.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.entity.HistoryRepository;

@Service
public class HistoryService {
    private static Logger logger = LoggerFactory.getLogger(HistoryService.class);

    private final HistoryRepository historyRepository;

    public HistoryService(HistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    public List<History> findAll() {
        return historyRepository.findAll();
    }

    public History get(Integer id) {
        return historyRepository.findById(id).orElse(null);
    }

    public List<History> findAll(int cid) {
        return historyRepository.findByCid(cid);
    }

    public History findById(int cid, String key) {
        key = URLEncoder.encode(key, StandardCharsets.UTF_8);
        logger.debug("findById cid:{} key:{}", cid, key);
        return historyRepository.findByCidAndKey(cid, key);
    }

    public void saveAll(List<History> histories) {
        for (var history : histories) {
            var exist = findById(history.getCid(), history.getKey());
            if (exist != null) {
                history.setId(exist.getId());
            }
        }
        historyRepository.saveAll(histories);
    }

    public History save(History history) {
        var exist = findById(history.getCid(), history.getKey());
        if (exist != null) {
            history.setId(exist.getId());
        }
        return historyRepository.save(history);
    }

    public void delete(int cid) {
        historyRepository.deleteByCid(cid);
    }

    public void delete(int cid, String key) {
        key = URLEncoder.encode(key, StandardCharsets.UTF_8);
        historyRepository.deleteByCidAndKey(cid, key);
    }

    public void deleteAll() {
        historyRepository.deleteAll();
    }

    public void deleteById(Integer id) {
        historyRepository.deleteById(id);
    }
}
