package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Device;
import cn.har01d.alist_tvbox.entity.DeviceRepository;
import cn.har01d.alist_tvbox.entity.History;
import cn.har01d.alist_tvbox.entity.HistoryRepository;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HistoryService {
    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final HistoryRepository historyRepository;
    private final DeviceRepository deviceRepository;
    private final AppProperties appProperties;
    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public HistoryService(HistoryRepository historyRepository,
                          DeviceRepository deviceRepository,
                          AppProperties appProperties,
                          SubscriptionService subscriptionService,
                          ObjectMapper objectMapper,
                          RestTemplateBuilder builder) {
        this.historyRepository = historyRepository;
        this.deviceRepository = deviceRepository;
        this.appProperties = appProperties;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.restTemplate = builder.build();
    }

    public History get(Integer id) {
        return historyRepository.findById(id).orElse(null);
    }

    public List<History> findAll() {
        return historyRepository.findAll(Sort.by("createTime").descending());
    }

    public History findById(String key) {
        return historyRepository.findByKey(key);
    }

    public void saveAll(List<History> histories) {
        for (var history : histories) {
            var exist = findById(history.getKey());
            if (exist != null) {
                history.setId(exist.getId());
            }
        }
        historyRepository.saveAll(histories);
    }

    public History save(History history) {
        var exist = findById(history.getKey());
        if (exist != null) {
            history.setId(exist.getId());
        }
        String url = history.getEpisodeUrl();
        if (url != null) {
            int index = url.indexOf("?");
            if (index > 0) {
                url = url.substring(0, index);
            }
            index = url.lastIndexOf("/");
            if (index > 0) {
                url = url.substring(index + 1);
            }
            history.setEpisodeUrl(url);
        }
        return historyRepository.save(history);
    }

    public void syncHistory(String mode, Device device, Device me, String config, List<History> histories) throws JsonProcessingException {
        Map<String, History> map = new HashMap<>();
        List<History> list = new ArrayList<>();

        for (History history : histories) {
            String[] parts = history.getKey().split("@@@");
            String key = parts[1];
            map.put(key, history);
            if (!parts[0].equals("csp_AList")) {
                continue;
            }
            history.setCid(0);
            history.setKey(key);
            History exist = historyRepository.findByKey(key);
            if (exist != null) {
                if (history.getCreateTime() > exist.getCreateTime() || history.getPosition() > exist.getPosition()) {
                    if ("0".equals(mode) || "2".equals(mode)) {
                        history.setId(exist.getId());
                        list.add(history);  // pull from TvBox
                        log.info("updated: {}", key);
                    }
                } else if (history.getCreateTime() < exist.getCreateTime() || history.getPosition() < exist.getPosition()) {
                    if ("0".equals(mode) || "1".equals(mode)) {
                        map.put(key, exist); // push to TvBox
                    }
                }
            } else {
                log.info("new: {}", key);
                if ("0".equals(mode) || "2".equals(mode)) {
                    list.add(history);
                }
            }
        }

        if (device != null) {
            Device exist = deviceRepository.findByUuid(device.getUuid());
            if (exist != null) {
                device.setId(exist.getId());
            }
            device.setConfig(config);
            deviceRepository.save(device);
        }

        List<History> old = historyRepository.findAll();

        log.warn("pull {} histories", list.size());
        historyRepository.saveAll(list);

        if (device == null || me.getIp().startsWith("http://127.0.0.1")) {
            log.warn("skip push: {} {}", device == null, me.getIp());
            return;
        }

        for (History history : old) {
            if (!map.containsKey(history.getKey())) {
                map.put(history.getKey(), history);
            }
        }

        List<History> result = new ArrayList<>(map.values());
        log.info("push {} histories", result.size());
        int cid = 0;
        if (config != null) {
            ObjectNode node = objectMapper.readValue(config, ObjectNode.class);
            cid = node.get("id").asInt();
        }

        for (History history : result) {
            history.setCid(cid);
            history.setKey("csp_AList@@@" + history.getKey() + "@@@1");
            String url = history.getEpisodeUrl();
            if (url != null && url.startsWith("1%24%")) {
                url = "1%7E%7E%7E%" + url.substring("1%24%".length());
                history.setEpisodeUrl(url);
            }
        }
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("do", "sync");
        formData.add("mode", "0");
        formData.add("type", "history");
        formData.add("device", objectMapper.writeValueAsString(me));
        formData.add("config", config);
        formData.add("targets", objectMapper.writeValueAsString(result));
        log.debug("push history: {}", formData);
        String json = restTemplate.postForObject(device.getIp() + "/action", formData, String.class);
        log.info(json);
    }

    public void sync(Integer id, Device me, int mode) throws JsonProcessingException {
        Device device = deviceRepository.findById(id).orElseThrow();

        String config = device.getConfig();
        if (config == null || config.isEmpty()) {
            String url = buildSubUrl();
            Map<String, Object> map = new HashMap<>();
            map.put("id", 1);
            map.put("type", 0);
            map.put("url", url);
            config = objectMapper.writeValueAsString(map);
        }
        syncHistory(String.valueOf(mode), device, me, config, List.of());
    }

    public void push(Integer id, String type, String name, String url, Device me) throws JsonProcessingException {
        Device device = deviceRepository.findById(id).orElseThrow();

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("do", type);
        if ("push".equals(type)) {
            formData.add("url", url);
        } else {
            formData.add("name", name);
            formData.add("text", url);
        }
        log.debug("push: {}", formData);
        String json = restTemplate.postForObject(device.getIp() + "/action", formData, String.class);
        log.info(json);

        if ("setting".equals(type)) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", 1);
            map.put("type", 0);
            map.put("url", url);
            String config = objectMapper.writeValueAsString(map);
            syncHistory("0", device, me, config, List.of());
        }
    }

    public void delete(String key) {
        historyRepository.deleteByKey(key);
    }

    public void deleteAll() {
        List<History> list = historyRepository.findAll();
        log.info("deleteAll: {}", list.size());
        historyRepository.deleteAll(list);
    }

    public void delete(List<Integer> ids) {
        for (var id : ids) {
            deleteById(id);
        }
    }

    public void deleteById(Integer id) {
        historyRepository.deleteById(id);
    }

    private String buildSubUrl() {
        String token = subscriptionService.getCurrentOrFirstToken();
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .scheme(appProperties.isEnableHttps() && !Utils.isLocalAddress() ? "https" : "http") // nginx https
                .replacePath("/sub/" + token + "/0")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

}
