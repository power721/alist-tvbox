package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.Index115ShareRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

@Slf4j
public class Index115VersionClient {
    private final RestTemplate restTemplate;
    private final String url;

    public Index115VersionClient(RestTemplate restTemplate, String url) {
        this.restTemplate = restTemplate;
        this.url = url;
    }

    public Index115ShareRef fetch() {
        try {
            return parse(restTemplate.getForObject(url, String.class));
        } catch (Exception e) {
            log.warn("fetch 115.version.txt failed", e);
            return null;
        }
    }

    public static Index115ShareRef parse(String text) {
        if (text == null) {
            return null;
        }
        String line = text.trim();
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String shareCode = line.substring(0, colon).trim();
        String receiveCode = line.substring(colon + 1).trim();
        if (shareCode.isEmpty() || receiveCode.isEmpty()) {
            return null;
        }
        return new Index115ShareRef(shareCode, receiveCode);
    }
}
