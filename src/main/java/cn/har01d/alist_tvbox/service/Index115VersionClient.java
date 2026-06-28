package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.Index115ShareRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class Index115VersionClient {
    private static final String VERSION_URL = "https://d.har01d.cn/115.version.txt";

    private final RestTemplate restTemplate;

    public Index115VersionClient(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    public Index115ShareRef fetch() {
        try {
            return parse(restTemplate.getForObject(VERSION_URL, String.class));
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
