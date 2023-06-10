package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;

@Slf4j
@Service
public class ParseService {
    private final RestTemplate restTemplate;

    public ParseService(RestTemplateBuilder builder) {
        restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, "*/*")
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .requestFactory(() -> new SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(HttpURLConnection connection, String httpMethod) {
                        connection.setInstanceFollowRedirects(false);
                    }
                }).build();
    }

    public String parse(String url) {
        log.info("parse url: {}", url);
        String result = url;

        if (url.contains("/redirect")) {
            ResponseEntity<Void> response = restTemplate.getForEntity(url, Void.class);
            String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (location != null) {
                result = location;
            }
            log.info("result: {}", result);
        }

        return result;
    }
}
