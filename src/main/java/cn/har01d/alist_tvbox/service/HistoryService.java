package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.entity.HistoryRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class HistoryService {
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
        return historyRepository.findByCid(cid, Sort.by("createTime").descending());
    }

    public History findById(int cid, String key) {
        key = decode(key);
        return historyRepository.findByCidAndKey(cid, key);
    }

    public void saveAll(List<History> histories) {
        for (var history : histories) {
            history.setKey(decode(history.getKey()));
            var exist = findById(history.getCid(), history.getKey());
            if (exist != null) {
                history.setId(exist.getId());
            }
        }
        historyRepository.saveAll(histories);
    }

    public History save(History history) {
        history.setKey(decode(history.getKey()));
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
        key = decode(key);
        historyRepository.deleteByCidAndKey(cid, key);
    }

    public void deleteAll() {
        historyRepository.deleteAll();
    }

    public void delete(List<Integer> ids) {
        for (var id : ids) {
            deleteById(id);
        }
    }

    public void deleteById(Integer id) {
        historyRepository.deleteById(id);
    }

    private String encode(String str) {
        return URLEncoder.encode(str, StandardCharsets.UTF_8);
    }

    private String decode(String str) {
        return URLDecoder.decode(str, StandardCharsets.UTF_8);
    }
}
